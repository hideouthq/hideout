package gg.hideouthq.core.data.deadlockstats

import io.ktor.client.HttpClient
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.URI

class ReplayDownloadException(message: String, val retryAfterMillis: Long? = null, val httpStatus: Int? = null) : IOException(message)

class ReplayMetadataDownloader(client: HttpClient, private val maxBytes: Int = MatchMetadataDecoder.MaxCompressedBytes) : AutoCloseable {
    private val http = client.forStats()

    suspend fun download(ref: ReplayMetadataRef): ByteArray {
        var url = ref.url
        repeat(3) {
            val checked = ReplayCacheUrl.parseMetadataUrl(url)
            if (checked == null || checked.matchId != ref.matchId || checked.metadataSalt != ref.metadataSalt) {
                throw ReplayDownloadException("not an allowed replay-CDN URL for this match")
            }
            val response = try { http.statsRequest(checked.url, maxBytes) }
            catch (e: IOException) { throw ReplayDownloadException(e.message ?: "Metadata download failed") }
            when (val status = response.status.value) {
                in 200..299 -> return response.body
                301, 302, 303, 307, 308 -> url = try {
                    URI(checked.url).resolve(response.headers[HttpHeaders.Location] ?: throw ReplayDownloadException("Missing Location")).toString()
                } catch (e: IllegalArgumentException) { throw ReplayDownloadException("Invalid Location") }
                else -> throw ReplayDownloadException("HTTP $status from replay CDN", retryAfterMillis(response.headers[HttpHeaders.RetryAfter]), status)
            }
        }
        throw ReplayDownloadException("more than 2 redirects")
    }

    override fun close() = http.close()
}
