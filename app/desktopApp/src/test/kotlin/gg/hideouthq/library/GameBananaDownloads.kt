package gg.hideouthq.library

import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
}.toByteArray()

internal fun MockRequestHandleScope.download(request: HttpRequestData, files: Map<Long, ByteArray>, served: MutableList<LongRange> = mutableListOf()): HttpResponseData? {
    val path = request.url.encodedPath
    val id = path.substringAfterLast('/').substringBefore('.').toLongOrNull() ?: return null
    val bytes = files[id]
    return when {
        path.startsWith("/dl/") -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, if (bytes == null) "https://gamebanana.com/mods/$id" else "https://filecache42.gamebanana.com/mods/$id.bin"))
        bytes == null -> respond("<html>gone</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
        else -> {
            val (from, to) = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").split('-').map { it.toLong() }
            served += from..to
            val headers = headersOf(HttpHeaders.ContentRange to listOf("bytes $from-$to/${bytes.size}"), HttpHeaders.ContentLength to listOf("${to - from + 1}"))
            respond(bytes.copyOfRange(from.toInt(), to.toInt() + 1), HttpStatusCode.PartialContent, headers)
        }
    }
}
