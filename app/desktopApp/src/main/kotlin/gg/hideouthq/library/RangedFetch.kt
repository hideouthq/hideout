package gg.hideouthq.library

import gg.hideouthq.core.data.DownloadProgress
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

internal sealed interface RangedFetch {
    data object Complete : RangedFetch

    data class Refused(val status: Int, val host: String = "", val retryAfter: String? = null) : RangedFetch

    data class TooLong(val bytes: Long) : RangedFetch

    data class TooShort(val bytes: Long) : RangedFetch

    data class Cut(val detail: String) : RangedFetch

    data class Disk(val detail: String) : RangedFetch
}

internal val StallNotice: Duration = 5.seconds

internal suspend fun fetchRange(
    client: HttpClient,
    url: String,
    part: Path,
    sizeBytes: Long,
    stallNotice: Duration = StallNotice,
    progressEvery: Duration = ProgressEvery,
    ranges: (Boolean) -> Unit = {},
    ifRange: String? = null,
    fresh: (String?) -> Unit = {},
    onProgress: (DownloadProgress) -> Unit,
): RangedFetch {
    val have = Files.size(part)
    if (have == sizeBytes) return RangedFetch.Complete
    val seen = Seen(have)
    return try {
        coroutineScope {
            val watch = launch {
                while (true) {
                    delay(stallNotice / 5)
                    if (!seen.stalled && seen.lastByte.elapsedNow() >= stallNotice) {
                        seen.stalled = true
                        onProgress(DownloadProgress(seen.done, sizeBytes, stalled = true))
                    }
                }
            }
            try {
                client.prepareGet(url) {
                    if (have > 0) {
                        header(HttpHeaders.Range, "bytes=$have-")
                        ifRange?.let { header(HttpHeaders.IfRange, it) }
                    }
                }.execute { response ->
                    val resumed = response.status == HttpStatusCode.PartialContent && have > 0 && contentRangeStart(response.headers[HttpHeaders.ContentRange]) == have
                    when {
                        resumed -> ranges(true)
                        response.status == HttpStatusCode.OK -> {
                            ranges(response.headers[HttpHeaders.AcceptRanges]?.contains("bytes", ignoreCase = true) == true)
                            writing { FileChannel.open(part, StandardOpenOption.WRITE).use { it.truncate(0) } }
                            writing { fresh(validator(response.headers[HttpHeaders.ETag], response.headers[HttpHeaders.LastModified])) }
                        }
                        else -> return@execute RangedFetch.Refused(response.status.value, response.call.request.url.host, response.headers[HttpHeaders.RetryAfter])
                    }
                    val body = response.bodyAsChannel()
                    var done = writing { Files.size(part) }
                    seen.done = done
                    val startedBytes = done
                    val startedAt = System.nanoTime()
                    onProgress(DownloadProgress(done, sizeBytes, stalled = seen.stalled))
                    writing { FileChannel.open(part, StandardOpenOption.WRITE, StandardOpenOption.APPEND) }.use { out ->
                        val buffer = ByteArray(1 shl 16)
                        var reported = done
                        var reportedAt = startedAt
                        while (true) {
                            val read = body.readAvailable(buffer, 0, buffer.size)
                            // A connection that drops mid-body can end the channel as if the body were whole; only its
                            // cause tells the two apart.
                            if (read < 0) {
                                body.closedCause?.let { return@execute RangedFetch.Cut(it.message ?: "the connection was cut") }
                                break
                            }
                            if (read == 0) continue
                            if (done + read > sizeBytes) return@execute RangedFetch.TooLong(done + read)
                            writing {
                                val chunk = ByteBuffer.wrap(buffer, 0, read)
                                while (chunk.hasRemaining()) out.write(chunk)
                            }
                            done += read
                            seen.done = done
                            seen.lastByte = TimeSource.Monotonic.markNow()
                            val now = System.nanoTime()
                            if (seen.stalled || done - reported >= ProgressStep || now - reportedAt >= progressEvery.inWholeNanoseconds || done == sizeBytes) {
                                seen.stalled = false
                                reported = done
                                reportedAt = now
                                onProgress(DownloadProgress(done, sizeBytes, remainingDownloadSeconds(done - startedBytes, sizeBytes - done, now - startedAt)))
                            }
                        }
                        writing { out.force(true) }
                    }
                    if (done == sizeBytes) RangedFetch.Complete else RangedFetch.TooShort(done)
                }
            } finally {
                watch.cancel()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: WriteFailed) {
        RangedFetch.Disk(e.cause?.message ?: "the download could not be written")
    } catch (e: IOException) {
        RangedFetch.Cut(e.message ?: e::class.simpleName ?: "the connection failed")
    }
}

private class Seen(done: Long) {
    @Volatile var done: Long = done
    @Volatile var lastByte: TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow()
    @Volatile var stalled: Boolean = false
}

private class WriteFailed(cause: IOException) : Exception(cause)

private inline fun <T> writing(block: () -> T): T = try {
    block()
} catch (e: IOException) {
    throw WriteFailed(e)
}

private const val ProgressStep = 256L * 1024

internal val ProgressEvery: Duration = 1.seconds

internal fun remainingDownloadSeconds(receivedBytes: Long, remainingBytes: Long, elapsedNanos: Long): Long? {
    if (receivedBytes <= 0 || remainingBytes <= 0 || elapsedNanos < 1_000_000_000L) return null
    return kotlin.math.ceil(remainingBytes.toDouble() / receivedBytes * (elapsedNanos / 1_000_000_000.0)).toLong().coerceAtLeast(1)
}

/** `If-Range` may not use a weak ETag. */
private fun validator(etag: String?, lastModified: String?): String? = etag?.takeUnless { it.startsWith("W/") } ?: lastModified

internal fun contentRangeStart(header: String?): Long? = header?.let { Regex("""bytes (\d+)-""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
