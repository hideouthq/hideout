package gg.hideouthq.data.preview

import gg.hideouthq.core.data.collections.CollectionsSource
import gg.hideouthq.core.data.preview.PreviewFiles
import gg.hideouthq.core.data.preview.PreviewSource
import gg.hideouthq.data.desktopEngine
import gg.hideouthq.library.DesktopInstall
import gg.hideouthq.library.deleteTree
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.MANIFEST_FILE
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.sha256Hex
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.milliseconds

class PreviewFileException(message: String) : IOException(message)

class PreviewCache(baseUrl: String, val root: Path, userAgent: String, key: PreviewKey?) : PreviewSource, PreviewFiles, CollectionsSource {
    private val base = baseUrl.trimEnd('/')
    private val client = DesktopInstall.downloadClient(desktopEngine(), userAgent).config { followRedirects = false }.apply { key?.let { signPreviewRequests(it) } }
    private val locks = ConcurrentHashMap<Path, Mutex>()

    init {
        thread(isDaemon = true, name = "Preview cache trim") { if (Files.isDirectory(root)) trim(root, BudgetBytes) }
    }

    override suspend fun bytes(): Long = withContext(Dispatchers.IO) {
        val bundles = root.resolve(Bundles)
        if (!Files.isDirectory(bundles)) 0L else Files.walk(bundles).use { walk -> walk.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) { deleteTree(root.resolve(Bundles)) }

    override suspend fun indexText(held: String?): String? =
        fetchUnless(held, "$base/v1/index.json", MaxIndexBytes)?.decodeToString(throwOnInvalidSequence = true)

    override suspend fun collectionsText(): String = fetch("$base/v1/collections.json", MaxCollectionsBytes).decodeToString(throwOnInvalidSequence = true)

    override suspend fun manifestText(entry: IndexEntry): String =
        file(entry.bundleId, MANIFEST_FILE, MaxManifestBytes, entry.manifestSha256).decodeToString(throwOnInvalidSequence = true)

    suspend fun asset(bundleId: String, asset: PreviewAsset, unchecked: ByteArray? = null, onBytes: (Long) -> Unit = {}): ByteArray =
        file(bundleId, asset.path, asset.bytes, asset.sha256, unchecked, onBytes)

    suspend fun <T> readAsset(bundleId: String, asset: PreviewAsset, onBytes: (Long) -> Unit = {}, read: suspend (InputStream) -> T): T {
        val target = root.resolve(Bundles).resolve(bundleId).resolve(asset.path)
        return locks.computeIfAbsent(target) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                readKept(target, asset, { onBytes(asset.bytes) }, read)?.let { return@withContext it }
                retried { fetchFile("$base/v1/bundles/$bundleId/${asset.path}", asset, target, onBytes) }
                readKept(target, asset, {}, read) ?: throw PreviewFileException("${asset.path} changed in the cache as it was read")
            }
        }
    }

    private suspend fun <T> readKept(file: Path, asset: PreviewAsset, onKept: () -> Unit, read: suspend (InputStream) -> T): T? {
        if (!Files.isRegularFile(file) || Files.size(file) != asset.bytes) return null
        onKept()
        val digest = MessageDigest.getInstance("SHA-256")
        val outcome = DigestInputStream(Files.newInputStream(file), digest).use { stream ->
            val outcome = try {
                Result.success(read(stream))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            stream.transferTo(OutputStream.nullOutputStream())
            outcome
        }
        if (HexFormat.of().formatHex(digest.digest()) != asset.sha256) return null
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()))
        return outcome.getOrThrow()
    }

    private suspend fun fetchFile(url: String, asset: PreviewAsset, target: Path, onBytes: (Long) -> Unit) {
        Files.createDirectories(target.parent)
        val received = Files.createTempFile(target.parent, ".download-", ".part")
        val unzipped = received.resolveSibling("${received.fileName}.unzipped")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val (zipped, tag) = client.prepareGet(url) { header(HttpHeaders.AcceptEncoding, "gzip") }.execute { response ->
                if (response.status != HttpStatusCode.OK) refused(response.status, url)
                val zipped = zipped(response.headers[HttpHeaders.ContentEncoding], url)
                // Deflate's stored blocks add 5 bytes in 64 KiB to bytes that do not compress, as a model's mostly do.
                val limit = if (zipped) asset.bytes + (asset.bytes shr 6) + 1024 else asset.bytes
                val length = response.contentLength()
                if (length != null && length > limit) throw PreviewFileException("$url is larger than ${asset.bytes} bytes")
                val body = response.bodyAsChannel()
                val buffer = ByteArray(CopyBufferBytes)
                var size = 0L
                Files.newOutputStream(received).use { out ->
                    while (true) {
                        val read = body.readAvailable(buffer, 0, buffer.size)
                        if (read < 0) break
                        size += read
                        if (size > limit) throw PreviewFileException("$url is larger than ${asset.bytes} bytes")
                        if (!zipped) digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        onBytes(size)
                    }
                }
                if (length != null && size != length) throw IOException("$url ended after $size of $length bytes")
                zipped to response.headers[HttpHeaders.ETag]?.let(Sha256ETag::matchEntire)?.groupValues?.get(1)
            }
            val kept = if (!zipped) received else unzipped.also {
                GZIPInputStream(Files.newInputStream(received)).use { input ->
                    Files.newOutputStream(it).use { out ->
                        val buffer = ByteArray(CopyBufferBytes)
                        var size = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            size += read
                            if (size > asset.bytes) throw PreviewFileException("$url is larger than ${asset.bytes} bytes")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            val sha256 = HexFormat.of().formatHex(digest.digest())
            if (tag != null && tag != sha256) throw PreviewFileException("$url does not match its ETag")
            if (Files.size(kept) != asset.bytes || sha256 != asset.sha256) throw PreviewFileException("${asset.path} does not match its SHA-256")
            Files.move(kept, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(received)
            Files.deleteIfExists(unzipped)
        }
    }

    suspend fun unchecked(bundleId: String, path: String): ByteArray? = withContext(Dispatchers.IO) {
        val bundle = root.resolve(Bundles).resolve(bundleId)
        if (Files.exists(bundle.resolve(MANIFEST_FILE)) || Files.exists(bundle.resolve(path))) return@withContext null
        try {
            fetch("$base/v1/bundles/$bundleId/$path", MaxUncheckedBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun file(bundleId: String, path: String, limit: Long, sha256: String, unchecked: ByteArray? = null, onBytes: (Long) -> Unit = {}): ByteArray {
        val target = root.resolve(Bundles).resolve(bundleId).resolve(path)
        return locks.computeIfAbsent(target) { Mutex() }.withLock {
            withContext(Dispatchers.IO) {
                kept(target, sha256)?.also { onBytes(it.size.toLong()) } ?: run {
                    val bytes = unchecked?.takeIf { sha256Hex(it) == sha256 }?.also { onBytes(it.size.toLong()) }
                        ?: download("$base/v1/bundles/$bundleId/$path", limit, onBytes).also {
                            if (sha256Hex(it) != sha256) throw PreviewFileException("$path does not match its SHA-256")
                        }
                    Files.createDirectories(target.parent)
                    Files.write(target, bytes)
                    bytes
                }
            }
        }
    }

    private fun kept(file: Path, sha256: String): ByteArray? {
        if (!Files.isRegularFile(file)) return null
        val bytes = Files.readAllBytes(file)
        if (sha256Hex(bytes) != sha256) return null
        Files.setLastModifiedTime(file, FileTime.from(Instant.now()))
        return bytes
    }

    private suspend fun download(url: String, limit: Long, onBytes: (Long) -> Unit): ByteArray = retried { fetch(url, limit, onBytes) }

    private suspend fun <T> retried(fetch: suspend () -> T): T {
        repeat(MaxAttempts - 1) { attempt ->
            try {
                return fetch()
            } catch (e: PreviewFileException) {
                throw e
            } catch (e: IOException) {
                delay(RetryDelay * (attempt + 1))
            }
        }
        return fetch()
    }

    private suspend fun fetch(url: String, limit: Long, onBytes: (Long) -> Unit = {}): ByteArray = checkNotNull(fetchUnless(null, url, limit, onBytes))

    /** A SHA-256 ETag, weak as Cloudflare makes it when it compresses, must match the unzipped bytes; other ETags, like R2's, are not checked. */
    private suspend fun fetchUnless(held: String?, url: String, limit: Long, onBytes: (Long) -> Unit = {}): ByteArray? =
        client.prepareGet(url) {
            header(HttpHeaders.AcceptEncoding, "gzip")
            held?.let { header(HttpHeaders.IfNoneMatch, "\"$it\"") }
        }.execute { response ->
            when (response.status) {
                HttpStatusCode.OK -> Unit
                HttpStatusCode.NotModified -> if (held != null) return@execute null else refused(response.status, url)
                else -> refused(response.status, url)
            }
            val length = response.contentLength()
            if (length != null && length > limit) throw PreviewFileException("$url is larger than $limit bytes")
            val body = response.bodyAsChannel()
            val received = if (length != null) {
                ByteArray(length.toInt()).also { bytes ->
                    var size = 0
                    while (size < bytes.size) {
                        val read = body.readAvailable(bytes, size, bytes.size - size)
                        if (read < 0) throw IOException("$url ended after $size of ${bytes.size} bytes")
                        size += read
                        onBytes(size.toLong())
                    }
                }
            } else {
                val stream = ByteArrayOutputStream(CopyBufferBytes)
                val buffer = ByteArray(CopyBufferBytes)
                while (true) {
                    val read = body.readAvailable(buffer, 0, buffer.size)
                    if (read < 0) break
                    if (stream.size() + read > limit) throw PreviewFileException("$url is larger than $limit bytes")
                    stream.write(buffer, 0, read)
                    onBytes(stream.size().toLong())
                }
                stream.toByteArray()
            }
            val bytes = if (zipped(response.headers[HttpHeaders.ContentEncoding], url)) gunzip(received, url, limit) else received
            val tag = response.headers[HttpHeaders.ETag]?.let(Sha256ETag::matchEntire)?.groupValues?.get(1)
            bytes.also { if (tag != null && tag != sha256Hex(it)) throw PreviewFileException("$url does not match its ETag") }
        }

    private fun refused(status: HttpStatusCode, url: String): Nothing = when (status) {
        HttpStatusCode.NotModified -> throw IOException("HTTP 304 from $url, which was not asked")
        HttpStatusCode.NotFound -> throw PreviewFileException("$url is not on the preview host")
        else -> throw IOException("HTTP ${status.value} from $url")
    }

    private fun zipped(encoding: String?, url: String): Boolean = when (val value = encoding?.trim()?.lowercase()) {
        null, "", "identity" -> false
        "gzip" -> true
        else -> throw PreviewFileException("$url came in $value, which was not asked for")
    }

    private fun gunzip(zipped: ByteArray, url: String, limit: Long): ByteArray {
        val stream = ByteArrayOutputStream(CopyBufferBytes)
        val buffer = ByteArray(CopyBufferBytes)
        GZIPInputStream(zipped.inputStream()).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (stream.size() + read > limit) throw PreviewFileException("$url is larger than $limit bytes")
                stream.write(buffer, 0, read)
            }
        }
        return stream.toByteArray()
    }

    companion object {
        private const val BudgetBytes = 512L shl 20
        private const val Bundles = "bundles"
        private const val MaxIndexBytes = 16L shl 20
        private const val MaxCollectionsBytes = 1L shl 20
        private const val MaxManifestBytes = 4L shl 20
        private const val MaxUncheckedBytes = 8L shl 20
        private const val MaxAttempts = 3
        private const val CopyBufferBytes = 64 shl 10
        private val RetryDelay = 250.milliseconds
        private val Sha256ETag = Regex("(?:W/)?\"([0-9a-f]{64})\"")

        fun baseUrl(value: String?): String? {
            val uri = value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() } ?: return null
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrEmpty()) return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            return uri.toString().trimEnd('/')
        }
    }
}

internal fun trim(root: Path, budget: Long) {
    val files = Files.walk(root).use { walk ->
        walk.filter { Files.isRegularFile(it) }.map { it to Files.readAttributes(it, BasicFileAttributes::class.java) }.toList()
    }
    var total = files.sumOf { (_, attributes) -> attributes.size() }
    for ((file, attributes) in files.sortedBy { (_, attributes) -> attributes.lastModifiedTime() }) {
        if (total <= budget) return
        if (runCatching { Files.delete(file) }.isSuccess) total -= attributes.size()
    }
}
