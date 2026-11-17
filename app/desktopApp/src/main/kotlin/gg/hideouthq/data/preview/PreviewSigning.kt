package gg.hideouthq.data.preview

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.DEFAULT_PORT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.isSuccess
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class PreviewKey(secret: String, baseUrl: String) {
    private val key = SecretKeySpec(secret.encodeToByteArray(), Algorithm)

    val host: String = Url(baseUrl).signedHost()

    fun signature(method: String, host: String, path: String, time: Long): String =
        HexFormat.of().formatHex(Mac.getInstance(Algorithm).apply { init(key) }.doFinal("$method\n$host\n$path\n$time".encodeToByteArray()))

    companion object {
        const val TimeHeader = "X-Hideout-Time"
        const val SignatureHeader = "X-Hideout-Sig"

        const val WindowSeconds = 300L
        private const val Algorithm = "HmacSHA256"
    }
}

internal fun Url.signedHost(): String =
    host.lowercase() + if (specifiedPort == DEFAULT_PORT || specifiedPort == protocol.defaultPort) "" else ":$specifiedPort"

internal fun HttpClient.signPreviewRequests(key: PreviewKey, now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    val offset = AtomicLong()
    plugin(HttpSend).intercept { request ->
        val url = request.url.build()
        if (url.signedHost() != key.host) return@intercept execute(request)
        val time = now() + offset.get()
        sign(request, key, url, time)
        val call = execute(request)
        val served = call.response.headers[HttpHeaders.Date]?.let(::epochSeconds)
        if (call.response.status != HttpStatusCode.NotFound || served == null || abs(served - time) <= PreviewKey.WindowSeconds) return@intercept call
        call.response.bodyAsBytes()
        sign(request, key, url, served)
        execute(request).also { retried ->
            val status = retried.response.status
            if (status.isSuccess() || status == HttpStatusCode.NotModified) offset.set(served - now())
        }
    }
}

private fun sign(request: HttpRequestBuilder, key: PreviewKey, url: Url, time: Long) {
    request.headers[PreviewKey.TimeHeader] = time.toString()
    request.headers[PreviewKey.SignatureHeader] = key.signature(request.method.value, key.host, url.encodedPath.ifEmpty { "/" }, time)
}

private fun epochSeconds(date: String): Long? = runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
