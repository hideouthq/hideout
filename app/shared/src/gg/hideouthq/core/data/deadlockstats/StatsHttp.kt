package gg.hideouthq.core.data.deadlockstats

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Clock

internal data class StatsResponse(val status: HttpStatusCode, val headers: io.ktor.http.Headers, val body: ByteArray)
internal class StatsHttpFailure(val status: Int) : IOException("HTTP $status")

internal fun HttpClient.forStats() = config {
    followRedirects = false
    expectSuccess = false
    install(HttpRequestRetry) { maxRetries = 0 }
    install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 10_000; socketTimeoutMillis = 15_000 }
}

internal suspend fun HttpClient.statsRequest(url: String, maxBytes: Int, request: HttpRequestBuilder.() -> Unit = {}): StatsResponse =
    prepareRequest(url, request).execute { response ->
        val channel = response.bodyAsChannel()
        try {
            if (response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { it > maxBytes } == true) {
                throw IOException("Response exceeds $maxBytes byte limit")
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = channel.readAvailable(buffer)
                if (count < 0) break
                if (count > maxBytes - output.size()) throw IOException("Response exceeds $maxBytes byte limit")
                output.write(buffer, 0, count)
            }
            StatsResponse(response.status, response.headers, output.toByteArray())
        } finally { channel.cancel(null) }
    }

internal fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    if (value == null) return null
    value.toLongOrNull()?.let { return if (it < 0) null else it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
    return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0) }.getOrNull()
}

/**
 * Endpoint gates for one stats API budget, which every repository given the same gate spends together. Starts within a
 * group keep its spacing, and groups never wait for each other or for an answer. A server cooldown holds every start in
 * its group until it ends; one longer than [LongCooldownMillis] fails at once instead, and none is ever shortened.
 * Waiting for the group's own queue is not a cooldown.
 */
class StatsRequestGate(private val clock: Clock = Clock.System) {
    private val mutex = Mutex()
    private val next = mutableMapOf<String, Long>()
    private val coolUntil = mutableMapOf<String, Long>()

    internal fun nowMillis() = clock.now().toEpochMilliseconds()

    internal suspend fun <T> request(group: String, spacingMillis: Long, block: suspend () -> T): T {
        while (true) {
            val wait = mutex.withLock {
                val now = nowMillis()
                val until = coolUntil[group] ?: 0
                if (until - now > LongCooldownMillis) throw StatsHttpFailure(429)
                val start = maxOf(now, until, next[group] ?: 0)
                next[group] = start + spacingMillis
                start - now
            }
            if (wait > 0) delay(wait)
            if (mutex.withLock { (coolUntil[group] ?: 0) <= nowMillis() }) return block()
        }
    }

    internal suspend fun coolDown(group: String, millis: Long) = mutex.withLock {
        coolUntil[group] = maxOf(coolUntil[group] ?: 0, nowMillis() + millis.coerceAtMost(86_400_000))
    }

    internal companion object {
        const val LongCooldownMillis = 30_000L
    }
}
