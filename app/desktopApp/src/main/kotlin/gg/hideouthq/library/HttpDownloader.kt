package gg.hideouthq.library

import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadProgress
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.FolderChoice
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.MaxTokenLength
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.TemporaryInfix
import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.core.network.tooManyWait
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

class HttpDownloader(
    private val client: HttpClient,
    defaultFolder: Path,
    private val hideoutAreas: List<Path>,
    private val retry: RetryWindow = RetryWindow(),
    private val time: TimeSource = TimeSource.Monotonic,
    private val busy: BusyHosts = BusyHosts(),
    private val stallNotice: Duration = StallNotice,
    private val progressEvery: Duration = ProgressEvery,
    private val busyWait: (retryAfter: String?, tries: Int) -> Duration? = ::tooManyWait,
) : NioTwinLinks<DownloadEntry>(), Downloader {
    override val defaultFolder: DownloadFolder = DownloadFolder(defaultFolder.toAbsolutePath().normalize().toString())

    override suspend fun checkFolder(path: String, games: Set<GameRoot>): FolderChoice = withContext(Dispatchers.IO) {
        val folder = try {
            DownloadFolder(Path.of(path).toAbsolutePath().normalize().toString())
        } catch (e: IllegalArgumentException) {
            return@withContext FolderChoice.Refused(FolderRefusal.Missing)
        }
        val refusal = try {
            val real = Path.of(folder.path).toRealPath()
            when {
                !Files.isDirectory(real) -> FolderRefusal.NotAFolder
                !Files.isWritable(real) -> FolderRefusal.NotWritable
                else -> placement(folder, real, games)
            }
        } catch (e: NoSuchFileException) {
            FolderRefusal.Missing
        } catch (e: IOException) {
            FolderRefusal.Unresolved
        }
        refusal?.let { FolderChoice.Refused(it) } ?: FolderChoice.Accepted(folder)
    }

    private fun placement(folder: DownloadFolder, real: Path, games: Set<GameRoot>): FolderRefusal? {
        val hideout = if (folder == defaultFolder) emptyList() else hideoutAreas + listOf(Path.of(defaultFolder.path))
        return when {
            games.any { real.isWithin(Path.of(it.path)) } -> FolderRefusal.InsideGame
            hideout.any { real.isWithin(it) } -> FolderRefusal.InsideHideout
            else -> null
        }
    }

    override suspend fun download(
        folder: DownloadFolder,
        submission: SubmissionRef,
        file: RemoteFile,
        onPublished: (ArchiveIdentity) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val target = try {
            val chosen = Path.of(folder.path)
            if (folder == defaultFolder) Files.createDirectories(chosen)
            ownFolder(ownFolder(chosen.toRealPath(), submission.modId), file.id.toString())
        } catch (e: IOException) {
            return@withContext DownloadResult.Failed(DownloadProblem.Disk(e.message ?: "the download folder cannot be made"))
        }
        val part = target.resolve(PartName)
        val result = try {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) Files.delete(part)
            if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) Files.createFile(part)
            if (Files.size(part) > file.sizeBytes) FileChannel.open(part, StandardOpenOption.WRITE).use { it.truncate(0) }
            attemptAll(file, part, target, onPublished, onProgress)
        } catch (e: IOException) {
            DownloadResult.Failed(DownloadProblem.Disk(e.message ?: "the download could not be written"))
        }
        val kept = (result as? DownloadResult.Failed)?.problem.let { it is DownloadProblem.Network || it is DownloadProblem.Http && it.status in Kept }
        if (!kept) forget(part)
        result
    }

    override suspend fun discardUnfinished(folder: DownloadFolder, submission: SubmissionRef, fileId: Long) = withContext(Dispatchers.IO) {
        val fileFolder = try {
            Path.of(folder.path).toRealPath().resolve(submission.modId).resolve(fileId.toString())
        } catch (e: IOException) {
            return@withContext
        }
        if (!Files.isDirectory(fileFolder, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(fileFolder.parent, LinkOption.NOFOLLOW_LINKS)) return@withContext
        forget(fileFolder.resolve(PartName))
    }

    private fun forget(part: Path) {
        retryingBusy { Files.deleteIfExists(part) }
        retryingBusy { Files.deleteIfExists(part.resolveSibling(ValidatorName)) }
        for (folder in listOf(part.parent, part.parent.parent)) {
            try {
                retryingBusy { Files.delete(folder) }
            } catch (e: IOException) {
                return
            }
        }
    }

    private suspend fun attemptAll(file: RemoteFile, part: Path, target: Path, onPublished: (ArchiveIdentity) -> Unit, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        var verificationRetried = false
        var outage: TimeMark? = null
        var tries = 0
        var told = 0
        var ranges = false
        val token = Any()
        val waiting: (Instant) -> Unit = { until -> onProgress(DownloadProgress(Files.size(part), file.sizeBytes, busyUntil = until)) }
        try {
            while (true) {
                busy.waitFor(token, Url(file.downloadUrl).host, waiting)
                val had = Files.size(part)
                when (val fetched = fetch(file, part, { ranges = ranges || it }, onProgress)) {
                    Fetch.Complete -> when (val checked = check(part, file)) {
                        is Check.Good -> return DownloadResult.Done(publish(part, target, file.fileName, checked).also(onPublished))
                        is Check.Bad -> {
                            if (verificationRetried) return DownloadResult.Failed(checked.problem)
                            verificationRetried = true
                            FileChannel.open(part, StandardOpenOption.WRITE).use { it.truncate(0) }
                        }
                    }
                    Fetch.Restart -> FileChannel.open(part, StandardOpenOption.WRITE).use { it.truncate(0) }
                    is Fetch.Busy -> {
                        val wait = busyWait(fetched.retryAfter, ++told) ?: return DownloadResult.Failed(DownloadProblem.Http(429))
                        busy.tooMany(token, fetched.host, wait)
                        busy.waitFor(token, fetched.host, waiting)
                    }
                    is Fetch.Retry -> {
                        val problem = (fetched.problem as? DownloadProblem.Network)?.copy(resumable = ranges && Files.size(part) > 0) ?: fetched.problem
                        if (Files.size(part) > had) {
                            outage = null
                            tries = 0
                        }
                        val began = outage ?: time.markNow().also { outage = it }
                        val wait = retry.wait(++tries, began.elapsedNow()) ?: return DownloadResult.Failed(problem)
                        onProgress(DownloadProgress(Files.size(part), file.sizeBytes, stalled = true))
                        delay(wait)
                    }
                    is Fetch.Fatal -> return DownloadResult.Failed(fetched.problem)
                }
            }
        } finally {
            busy.release(token)
        }
    }

    private sealed interface Fetch {
        data object Complete : Fetch
        data object Restart : Fetch
        data class Busy(val host: String, val retryAfter: String?) : Fetch
        data class Retry(val problem: DownloadProblem) : Fetch
        data class Fatal(val problem: DownloadProblem) : Fetch
    }

    private suspend fun fetch(file: RemoteFile, part: Path, ranges: (Boolean) -> Unit, onProgress: (DownloadProgress) -> Unit): Fetch {
        val named = part.resolveSibling(ValidatorName)
        val validator = if (Files.isRegularFile(named, LinkOption.NOFOLLOW_LINKS)) Files.readString(named).ifEmpty { null } else null
        val resuming = Files.size(part) in 1 until file.sizeBytes
        if (resuming && validator == null && file.md5 == null) FileChannel.open(part, StandardOpenOption.WRITE).use { it.truncate(0) }
        val fresh: (String?) -> Unit = { name -> if (name == null) Files.deleteIfExists(named) else Files.writeString(named, name) }
        return when (val fetched = fetchRange(client, file.downloadUrl, part, file.sizeBytes, stallNotice, progressEvery, ranges, validator, fresh, onProgress)) {
            RangedFetch.Complete -> Fetch.Complete
            is RangedFetch.Refused -> {
                val problem = DownloadProblem.Http(fetched.status)
                when {
                    fetched.status == 206 && Files.size(part) > 0 -> Fetch.Restart
                    fetched.status == 429 -> Fetch.Busy(fetched.host, fetched.retryAfter)
                    fetched.status == 408 || fetched.status >= 500 -> Fetch.Retry(problem)
                    else -> Fetch.Fatal(problem)
                }
            }
            is RangedFetch.TooLong -> Fetch.Fatal(DownloadProblem.SizeMismatch(file.sizeBytes, fetched.bytes))
            is RangedFetch.TooShort -> Fetch.Retry(DownloadProblem.SizeMismatch(file.sizeBytes, fetched.bytes))
            is RangedFetch.Cut -> Fetch.Retry(DownloadProblem.Network(fetched.detail))
            is RangedFetch.Disk -> Fetch.Fatal(DownloadProblem.Disk(fetched.detail))
        }
    }

    private sealed interface Check {
        data class Good(val size: Long, val sha256: String) : Check
        data class Bad(val problem: DownloadProblem) : Check
    }

    private fun check(part: Path, file: RemoteFile): Check {
        val md5 = MessageDigest.getInstance("MD5")
        val sha = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileChannel.open(part, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(1 shl 16)
            while (channel.read(buffer.clear()) >= 0) {
                md5.update(buffer.array(), 0, buffer.position())
                sha.update(buffer.array(), 0, buffer.position())
                size += buffer.position()
            }
        }
        if (size != file.sizeBytes) return Check.Bad(DownloadProblem.SizeMismatch(file.sizeBytes, size))
        val actualMd5 = md5.digest().toHexString()
        val expectedMd5 = file.md5
        if (expectedMd5 != null && expectedMd5 != actualMd5) return Check.Bad(DownloadProblem.Md5Mismatch(expectedMd5, actualMd5))
        return Check.Good(size, sha.digest().toHexString())
    }

    private fun publish(part: Path, target: Path, fileName: String, file: Check.Good): ArchiveIdentity {
        val name = downloadName(fileName)
        val stem = name.substringBeforeLast('.', name)
        val extension = name.removePrefix(stem)
        for (n in 1..MaxNames) {
            val candidate = target.resolve(if (n == 1) name else "$stem ($n)$extension")
            try {
                retryingBusy { Files.move(part, candidate) }
            } catch (e: FileAlreadyExistsException) {
                continue
            }
            retryingBusy { Files.deleteIfExists(part.resolveSibling(ValidatorName)) }
            return ArchiveIdentity(candidate.toAbsolutePath().toString(), file.size, file.sha256)
        }
        throw IOException("no free name for $name in $target")
    }

    override fun emptied(entry: DownloadEntry, file: Path) {
        retryingBusy { Files.delete(file.parent) }
        retryingBusy { Files.delete(file.parent.parent) }
    }

    override fun path(entry: DownloadEntry, create: Boolean): Path {
        val archive = Path.of(entry.archive.path)
        val fileFolder = archive.parent
        val modFolder = fileFolder?.parent
        val base = modFolder?.parent
        if (!archive.isAbsolute || archive.normalize() != archive || base == null ||
            modFolder.fileName?.toString() != entry.source.modId || fileFolder.fileName?.toString() != entry.source.fileId.toString()
        ) {
            throw IOException("${entry.archive.path} is not a download of ${entry.source.modId} file ${entry.source.fileId}")
        }
        plainFolders(fileFolder)
        return entry.anchor?.let { archive.resolveSibling("${archive.fileName}$TemporaryInfix${it.value}") } ?: archive
    }

    override fun nameOf(entry: DownloadEntry) = entry.archive.fileName

    private fun Path.isWithin(area: Path): Boolean = try {
        startsWith(area.toAbsolutePath().normalize().toRealPath())
    } catch (e: NoSuchFileException) {
        false
    }

    private companion object {
        const val PartName = "download.part"
        const val ValidatorName = "download.part.validator"
        val Kept = setOf(408, 429, 500, 502, 503, 504)
    }
}

class RetryWindow(private val window: Duration = 2.minutes, private val first: Duration = 1.seconds, private val longest: Duration = 30.seconds) {
    fun wait(tries: Int, lasted: Duration): Duration? =
        if (lasted >= window) null else minOf(first * (1 shl (tries - 1).coerceIn(0, 30)), longest, window - lasted)
}

private const val MaxNames = 100

/** Linux file systems allow 255 bytes in a name, and NTFS 255 UTF-16 units, never fewer. */
private const val MaxNameBytes = 255 - " ($MaxNames)".length - TemporaryInfix.length - MaxTokenLength

private const val MaxExtensionBytes = 16

/** A name Windows opens as a device instead of a file, with or without an extension. */
private val WindowsDevice = Regex("""(CON|PRN|AUX|NUL|COM[0-9¹²³]|LPT[0-9¹²³]) *(\..*)?""", RegexOption.IGNORE_CASE)

internal fun downloadName(fileName: String): String {
    val clean = fileName.substringAfterLast('/').substringAfterLast('\\')
        .filterNot { it.isISOControl() || it in "<>:\"|?*" }
        .trim(' ', '.')
        .ifEmpty { "download" }
    val name = fitted(clean)
    return if (WindowsDevice.matches(name)) fitted("_$name") else name
}

private fun fitted(name: String): String {
    if (name.utf8Size() <= MaxNameBytes) return name
    val stem = name.substringBeforeLast('.', name)
    val extension = name.removePrefix(stem).takeIf { it.utf8Size() <= MaxExtensionBytes }.orEmpty()
    val cut = if (extension.isEmpty()) name else stem
    return cut.utf8Prefix(MaxNameBytes - extension.utf8Size()).trimEnd(' ', '.') + extension
}

private fun String.utf8Size() = encodeToByteArray().size

/** The encoder never writes part of a code point. */
private fun String.utf8Prefix(bytes: Int): String {
    val text = CharBuffer.wrap(this)
    Charsets.UTF_8.newEncoder().encode(text, ByteBuffer.allocate(bytes), true)
    return substring(0, text.position())
}
