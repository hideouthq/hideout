package gg.hideouthq.library

import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal data class Blob(val url: String, val sizeBytes: Long, val sha256: String)

internal enum class BlobFailure { Server, Connection, Disk, Mismatch }

internal suspend fun transferVerified(client: HttpClient, blob: Blob, part: Path, target: Path, onProgress: (Long) -> Unit): BlobFailure? = withContext(Dispatchers.IO) {
    try {
        fill(client, blob, part, target, onProgress)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        BlobFailure.Disk
    } catch (e: RuntimeException) {
        // The HTTP client refuses a URL it can't make sense of this way.
        BlobFailure.Server
    }
}

internal fun holdsBlob(file: Path, sizeBytes: Long, sha256: String): Boolean {
    if (!Files.isRegularFile(file) || Files.size(file) != sizeBytes) return false
    val digest = MessageDigest.getInstance("SHA-256")
    FileChannel.open(file, StandardOpenOption.READ).use { channel ->
        val buffer = ByteBuffer.allocate(1 shl 16)
        while (channel.read(buffer.clear()) >= 0) digest.update(buffer.array(), 0, buffer.position())
    }
    return digest.digest().toHexString() == sha256
}

private suspend fun fill(client: HttpClient, blob: Blob, part: Path, target: Path, onProgress: (Long) -> Unit): BlobFailure? {
    Files.createDirectories(part.parent)
    if (Files.exists(part) && Files.size(part) > blob.sizeBytes) Files.delete(part)
    if (!Files.exists(part)) Files.createFile(part)
    val resumed = Files.size(part) > 0
    when (val fetched = fetchRange(client, blob.url, part, blob.sizeBytes) { onProgress(it.doneBytes) }) {
        RangedFetch.Complete -> if (holdsBlob(part, blob.sizeBytes, blob.sha256)) {
            retryingBusy { Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            return null
        }
        is RangedFetch.Refused -> if (!resumed || fetched.status !in CannotContinue) return BlobFailure.Server
        is RangedFetch.TooLong, is RangedFetch.TooShort -> Unit
        is RangedFetch.Cut -> return BlobFailure.Connection
        is RangedFetch.Disk -> return BlobFailure.Disk
    }
    retryingBusy { Files.deleteIfExists(part) }
    return if (resumed) fill(client, blob, part, target, onProgress) else BlobFailure.Mismatch
}

private val CannotContinue = setOf(206, 416)
