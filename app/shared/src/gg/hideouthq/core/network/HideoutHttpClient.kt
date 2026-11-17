package gg.hideouthq.core.network

import gg.hideouthq.core.data.deadlockstats.retryAfterMillis
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.takeFrom
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val MaxRetries = 3
private const val RequestTimeoutMillis = 30_000L
private const val ConnectTimeoutMillis = 10_000L

/** The longest wait a "too many requests" may ask for; GameBanana asks for five to eight minutes. One longer fails the request. */
private val LongestBusyWait = 10.minutes

private const val BusyAnswers = 5

/**
 * The hosts that answered "too many requests", each with the moment it asked Hideout to wait until. A request to such a
 * host waits until then before it is sent, and again whenever it is told to wait again, as [tooManyWait] allows, so a
 * busy host holds requests back for a while before it fails them. [hosts] names a host only while a request waits for it or is
 * on its way after the wait.
 */
class BusyHosts(private val clock: Clock = Clock.System) {
    private val holding = mutableMapOf<Any, String>()
    private val until = mutableMapOf<String, Instant>()
    private val mutableHosts = MutableStateFlow<Map<String, Instant>>(emptyMap())
    val hosts: StateFlow<Map<String, Instant>> = mutableHosts.asStateFlow()

    /** Notes that [host] asked [request] to wait [wait] more, never moving its time earlier, and holds the request on it. */
    fun tooMany(request: Any, host: String, wait: Duration) = synchronized(holding) {
        val asked = clock.now() + wait
        until[host] = until[host]?.takeIf { it > asked } ?: asked
        holding[request] = host
        publish()
    }

    /**
     * Suspends [request] until [host] may be asked again; while it waits the host shows as busy, and [waiting] hears the
     * moment it waits for.
     */
    suspend fun waitFor(request: Any, host: String, waiting: (Instant) -> Unit = {}) {
        while (true) {
            val (left, at) = synchronized(holding) {
                val at = until[host]
                val left = at?.let { it - clock.now() }?.takeIf { it.isPositive() } ?: return
                if (holding.put(request, host) == null) publish()
                left to at
            }
            waiting(at)
            delay(left)
        }
    }

    /** [request] has its answer or was given up: its host no longer counts it as waiting. */
    fun release(request: Any) = synchronized(holding) {
        if (holding.remove(request) != null) publish()
    }

    private fun publish() {
        mutableHosts.value = holding.values.toSet().associateWith { until.getValue(it) }
    }
}

/** When GameBanana said it may be asked again, while a request waits for it; null when none does. */
fun Map<String, Instant>.gameBananaUntil(): Instant? =
    filterKeys { it == GameBananaHost || it.endsWith(".$GameBananaHost") }.values.maxOrNull()

private const val GameBananaHost = "gamebanana.com"

/**
 * How long to wait after the [tries]th "too many requests" in a row: what its [retryAfter] header asks for, or a backoff
 * from [tries] when it asks for no time or says nothing usable. Null when the request should fail instead, as the header
 * asks for longer than [LongestBusyWait] or it has had [BusyAnswers] such answers already.
 */
fun tooManyWait(retryAfter: String?, tries: Int, nowMillis: Long = System.currentTimeMillis()): Duration? {
    if (tries > BusyAnswers) return null
    val wait = retryAfterMillis(retryAfter, nowMillis)?.takeIf { it > 0 }?.milliseconds
        ?: (1L shl tries.coerceAtLeast(1)).seconds
    return wait.takeIf { it <= LongestBusyWait }
}

/**
 * The app's HTTP client on [engine]: it names itself with [userAgent], gives up on stalled requests, and retries a 5xx
 * answer with exponential backoff. With [busy], a host that answers "too many requests" holds every request to it until
 * the time it gave, and they then go on by themselves, as [BusyHosts] says, until [tooManyWait] gives up and the caller
 * sees the "too many requests" answer; without it, such an answer is retried like
 * a 5xx. Callers see the last attempt's response; a request that gets no answer fails at once, so an offline screen
 * shows straight away. Each request that gets no answer is written to [log].
 */
fun hideoutHttpClient(engine: HttpClientEngine, userAgent: String, log: (String) -> Unit = {}, busy: BusyHosts? = BusyHosts()): HttpClient = HttpClient(engine) {
    expectSuccess = false
    install(UserAgent) { agent = userAgent }
    logUnanswered(log)
    busy?.let { install(waitsOut(it)) }
    install(HttpRequestRetry) {
        retryIf(MaxRetries) { _, response -> response.status.value >= 500 || busy == null && response.status == HttpStatusCode.TooManyRequests }
        exponentialDelay()
    }
    install(HttpTimeout) {
        requestTimeoutMillis = RequestTimeoutMillis
        connectTimeoutMillis = ConnectTimeoutMillis
    }
}

/**
 * Writes each request that gets no answer to [log]: the host and why, never the path or query, which can name the
 * user's accounts. A failed TLS handshake behind an antivirus scanner shows up here.
 */
fun HttpClientConfig<*>.logUnanswered(log: (String) -> Unit) = HttpResponseValidator {
    handleResponseExceptionWithRequest { cause, request ->
        // Ktor's timeouts name the whole URL in their message.
        if (cause !is CancellationException) log("Couldn't reach ${request.url.host}: ${"$cause".substringBefore(" [url=")}")
    }
}

private fun waitsOut(busy: BusyHosts) = createClientPlugin("BusyHosts") {
    on(Send) { request ->
        val token = Any()
        val host = request.url.host
        try {
            busy.waitFor(token, host)
            var call = proceed(request)
            var tries = 0
            while (call.response.status == HttpStatusCode.TooManyRequests) {
                val wait = tooManyWait(call.response.headers[HttpHeaders.RetryAfter], ++tries) ?: break
                busy.tooMany(token, host, wait)
                busy.waitFor(token, host)
                call = proceed(again(request))
            }
            call
        } finally {
            busy.release(token)
        }
    }
}

private fun again(request: HttpRequestBuilder): HttpRequestBuilder {
    val copy = HttpRequestBuilder().takeFrom(request)
    request.executionContext.invokeOnCompletion { cause ->
        val job = copy.executionContext as CompletableJob
        if (cause == null) job.complete() else job.completeExceptionally(cause)
    }
    return copy
}
