package gg.hideouthq.core.network

import gg.hideouthq.core.data.DataError
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class HideoutHttpClientTest {
    private fun TestScope.clock() = schedulerClock(testScheduler)

    private fun TestScope.engine(handler: MockRequestHandler) = schedulerEngine(testScheduler, handler)

    @Test
    fun theClientNamesItselfAndRetriesAServerError() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            assertEquals("Hideout/test", request.headers[HttpHeaders.UserAgent])
            if (++calls == 1) respondError(HttpStatusCode.ServiceUnavailable) else respond("ok")
        }
        assertEquals("ok", hideoutHttpClient(engine, "Hideout/test").getText("https://example.test/"))
        assertEquals(2, calls)
    }

    @Test
    fun aHostThatAsksForTimeIsBusyUntilTheTimeItGaveWhileItsRequestWaitsAndNoLonger() = runTest {
        val clock = clock()
        val busy = BusyHosts(clock)
        var calls = 0
        val waited = mutableListOf<Set<String>>()
        val engine = engine {
            if (++calls == 1) respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
            else respond("ok").also { waited += busy.hosts.value.keys }
        }
        val before = clock.now()
        val answer = async { hideoutHttpClient(engine, "Hideout/test", busy = busy).getText("https://gamebanana.com/apiv11/Mod/1") }
        val until = busy.hosts.first { it.isNotEmpty() }.getValue("gamebanana.com")
        assertTrue(until - before in 1.seconds..2.seconds, "the time Retry-After gave: ${until - before}")
        assertEquals("ok", answer.await())
        assertEquals(listOf(setOf("gamebanana.com")), waited, "the try after the wait still counts as held")
        assertEquals(emptyMap(), busy.hosts.value, "and once it has its answer the host is not busy")
    }

    @Test
    fun aBusyHostIsWaitedForButACancelledRequestLetsGoOfIt() = runTest {
        val busy = BusyHosts()
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "300"))
        }
        val waiting = launch { hideoutHttpClient(engine, "Hideout/test", busy = busy).getText("https://gamebanana.com/apiv11/Mod/1") }
        val until = busy.hosts.first { it.isNotEmpty() }.getValue("gamebanana.com")
        assertTrue(until - Clock.System.now() > 290.seconds, "about five minutes from now")
        assertEquals(1, calls, "asked once, then waiting rather than failing")
        waiting.cancelAndJoin()
        assertEquals(emptyMap(), busy.hosts.value)
    }

    @Test
    fun everyRequestToABusyHostWaitsForTheTimeItGaveBeforeItIsSent() = runTest {
        val busy = BusyHosts(clock())
        val first = Any()
        busy.tooMany(first, "gamebanana.com", 300.seconds)
        val sent = mutableListOf<Long>()
        val second = launch { busy.waitFor(Any(), "gamebanana.com"); sent += testScheduler.currentTime }
        val other = launch { busy.waitFor(Any(), "deadlock-api.com"); sent += testScheduler.currentTime }
        runCurrent()
        assertEquals(listOf(0L), sent, "another host is not held")
        busy.release(first)
        assertEquals(setOf("gamebanana.com"), busy.hosts.value.keys, "still busy while the second request waits")
        advanceTimeBy(299_000)
        assertEquals(listOf(0L), sent)
        advanceTimeBy(2_000)
        assertEquals(listOf(0L, 300_000L), sent)
        listOf(second, other).joinAll()
    }

    @Test
    fun aWaitIsWhatRetryAfterSaysOrABackoffThatDoubles() {
        val now = 1_700_000_000_000L
        assertEquals(300.seconds, tooManyWait("300", tries = 1, nowMillis = now))
        assertEquals(90.seconds, tooManyWait("Tue, 14 Nov 2023 22:14:50 GMT", tries = 1, nowMillis = now))
        assertEquals(2.seconds, tooManyWait(null, tries = 1, nowMillis = now))
        assertEquals(8.seconds, tooManyWait("soon", tries = 3, nowMillis = now))
        assertEquals(32.seconds, tooManyWait(null, tries = 5, nowMillis = now))
    }

    @Test
    fun aRetryAfterOfNoTimeOrAPastDateWaitsTheBackoffInstead() {
        val now = 1_700_000_000_000L
        assertEquals(2.seconds, tooManyWait("0", tries = 1, nowMillis = now))
        assertEquals(4.seconds, tooManyWait("0", tries = 2, nowMillis = now))
        assertEquals(2.seconds, tooManyWait("Tue, 14 Nov 2023 22:00:00 GMT", tries = 1, nowMillis = now))
    }

    @Test
    fun aWaitLongerThanTenMinutesOrASixthTooManyRequestsInARowFailsTheRequest() {
        val now = 1_700_000_000_000L
        assertEquals(10.minutes, tooManyWait("600", tries = 1, nowMillis = now))
        assertNull(tooManyWait("601", tries = 1, nowMillis = now))
        assertNull(tooManyWait("86400", tries = 1, nowMillis = now))
        assertNull(tooManyWait("Wed, 15 Nov 2023 22:13:20 GMT", tries = 1, nowMillis = now))
        assertNull(tooManyWait("300", tries = 6, nowMillis = now))
        assertNull(tooManyWait(null, tries = 6, nowMillis = now))
    }

    @Test
    fun aHostAskingForADayIsAskedOnceAndTheCallerSeesItBusyAtOnce() = runTest {
        val busy = BusyHosts()
        var calls = 0
        val engine = MockEngine {
            calls++
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "86400"))
        }
        val failed = assertFailsWith<RemoteException> { hideoutHttpClient(engine, "Hideout/test", busy = busy).getText("https://gamebanana.com/apiv11/Mod/1") }
        assertEquals(DataError.Unavailable, failed.error)
        assertEquals(1, calls)
        assertEquals(emptyMap(), busy.hosts.value, "nothing is held for a day")
    }

    @Test
    fun aHostThatKeepsSayingTooManyIsAskedSixTimesThenTheRequestFails() = runTest {
        val busy = BusyHosts(clock())
        var calls = 0
        val engine = engine {
            calls++
            respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
        }
        val failed = assertFailsWith<RemoteException> { hideoutHttpClient(engine, "Hideout/test", busy = busy).getText("https://gamebanana.com/apiv11/Mod/1") }
        assertEquals(DataError.Unavailable, failed.error)
        assertEquals(6, calls, "the first answer and five waited out")
        assertEquals(emptyMap(), busy.hosts.value)
    }

    @Test
    fun withoutBusyHostsATooManyRequestsIsRetriedLikeAServerError() = runTest {
        var calls = 0
        val engine = MockEngine { if (++calls == 1) respond("slow down", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "0")) else respond("ok") }
        assertEquals("ok", hideoutHttpClient(engine, "Hideout/test", busy = null).getText("https://deadlock-api.com/v1/x"))
        assertEquals(2, calls)
    }

    @Test
    fun failuresBecomeErrorsAScreenCanActOn() = runTest {
        val missing = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
        assertEquals(DataError.NotFound, assertFailsWith<RemoteException> { missing.getText("https://example.test/") }.error)

        val broken = HttpClient(MockEngine { respondError(HttpStatusCode.BadRequest) })
        assertEquals(DataError.Unavailable, assertFailsWith<RemoteException> { broken.getText("https://example.test/") }.error)

        val offline = HttpClient(MockEngine { throw IOException("No route to host") })
        assertEquals(DataError.Offline, assertFailsWith<RemoteException> { offline.getText("https://example.test/") }.error)
    }

    @Test
    fun aRequestThatGetsNoAnswerIsLoggedByHostOnly() = runTest {
        val logged = mutableListOf<String>()
        val offline = hideoutHttpClient(MockEngine { request -> throw IOException("PKIX path building failed [url=${request.url}]") }, "Hideout/test", logged::add)

        assertFailsWith<RemoteException> { offline.getText("https://gamebanana.com/apiv11/Member/12345?_csvProperties=x") }

        val line = logged.single()
        assertTrue(line.startsWith("Couldn't reach gamebanana.com: ") && line.endsWith("IOException: PKIX path building failed"), line)
        assertFalse("12345" in line || "apiv11" in line, "the path and query stay out: $line")
    }

}
