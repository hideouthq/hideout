package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.network.RemoteException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StatsRequestTimingTest {
    private fun TestScope.clock() = object : Clock { override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime) }

    private fun TestScope.client(handler: MockRequestHandler) =
        HttpClient(MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) }))

    private fun TestScope.repository(client: HttpClient, gate: StatsRequestGate) = DeadlockStatsRepository(client, MemoryStorage(), backgroundScope, clock = clock(), baseUrl = "http://stats.test",
        requestGate = gate, ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun startsInOneGroupAreSpacedWhileOtherGroupsAndAnswersAreNotWaitedFor() = runTest {
        val gate = StatsRequestGate(clock())
        val starts = mutableMapOf<String, Long>()
        coroutineScope {
            launch { gate.request("analytics", 300) { starts["first"] = testScheduler.currentTime; delay(1500) } }
            launch { gate.request("analytics", 300) { starts["second"] = testScheduler.currentTime } }
            launch { gate.request("history", 300) { starts["other"] = testScheduler.currentTime } }
        }
        assertEquals(mapOf("first" to 0L, "second" to 300L, "other" to 0L), starts,
            "the second start keeps the spacing but not the first one's answer, and another group starts at once")
    }

    @Test
    fun aRetryAfterDateIsMeasuredOnTheGatesClock() = runTest {
        val starts = mutableListOf<Long>()
        val client = client {
            starts += testScheduler.currentTime
            if (starts.size > 1) respond("{}") else respondError(HttpStatusCode.ServiceUnavailable, headers = headersOf(HttpHeaders.RetryAfter, "Thu, 01 Jan 1970 00:00:07 GMT"))
        }
        val api = DeadlockStatsApi(client, "http://stats.test", StatsRequestGate(clock()))
        try {
            api.playerMetrics(MatchModeFilter.Ranked, DateWindow())
        } finally {
            api.close()
            client.close()
        }
        assertEquals(listOf(0L, 7_000L), starts)
    }

    @Test
    fun aBusyServerIsAskedAgainThenGivenUpOn() = runTest {
        val starts = mutableListOf<Long>()
        val client = client { starts += testScheduler.currentTime; respondError(HttpStatusCode.ServiceUnavailable) }
        val api = DeadlockStatsApi(client, "http://stats.test", StatsRequestGate(clock()))
        try {
            assertFailsWith<RemoteException> { api.playerMetrics(MatchModeFilter.Ranked, DateWindow()) }
        } finally {
            api.close()
            client.close()
        }
        assertEquals(4, starts.size, "four attempts")
    }

    @Test
    fun aLongServerCooldownFailsAtOnceAndIsRememberedForTheGroup() = runTest {
        var asked = 0
        val client = client {
            asked++
            respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "120"))
        }
        val api = DeadlockStatsApi(client, "http://stats.test", StatsRequestGate(clock()))
        try {
            assertFailsWith<RemoteException> { api.playerMetrics(MatchModeFilter.Ranked, DateWindow()) }
            assertFailsWith<RemoteException> { api.heroCounters(SteamAccountId.ofAccountId(555)!!, MatchModeFilter.All, DateWindow()) }
            assertEquals(1, asked, "the second analytics request is not sent during the two-minute cooldown")
            assertEquals(0L, testScheduler.currentTime, "neither waits for the cooldown")
        } finally {
            api.close()
            client.close()
        }
    }

    @Test
    fun ourOwnQueueIsWaitedForNotMistakenForAServerCooldown() = runTest {
        val gate = StatsRequestGate(clock())
        val answers = (1..20).map { async { runCatching { gate.request("summaries", 2_000) { testScheduler.currentTime } } } }.awaitAll()
        assertTrue(answers.all { it.isSuccess }, "a queue of twenty at 2 s apart is ours to wait for: ${answers.filter { it.isFailure }.size} failed")
        assertEquals((0L until 20L).map { it * 2_000 }, answers.map { it.getOrThrow() })
    }

    @Test
    fun repositoriesSharingAGateShareOneGroupsSpacingAndCooldown() = runTest {
        val starts = mutableListOf<Pair<String, Long>>()
        var refused = false
        val client = client { request ->
            starts += request.url.parameters["hero_ids"].orEmpty() to testScheduler.currentTime
            if (refused) respond("{}") else {
                refused = true
                respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "5"))
            }
        }
        val gate = StatsRequestGate(clock())
        val tracked = repository(client, gate)
        val visitor = repository(client, gate)
        try {
            coroutineScope {
                launch { tracked.playerMetrics(DateWindow(), MatchModeFilter.Ranked, heroId = 1) }
                delay(50)
                launch { visitor.playerMetrics(DateWindow(), MatchModeFilter.Ranked, heroId = 2) }
            }
        } finally {
            tracked.close(); visitor.close(); client.close()
        }
        assertEquals(listOf("1" to 0L, "1" to 5_000L, "2" to 5_350L), starts,
            "a cooldown the server set for one session holds the other's request too, which then keeps the spacing")
    }

    @Test
    fun closingOneRepositoryLeavesTheSharedBudgetWorkingForTheOthers() = runTest {
        val starts = mutableListOf<Pair<String, Long>>()
        val client = client { request ->
            starts += request.url.parameters["hero_ids"].orEmpty() to testScheduler.currentTime
            respond("{}")
        }
        val gate = StatsRequestGate(clock())
        val tracked = repository(client, gate)
        val visitor = repository(client, gate)
        try {
            val trackedWork = launch {
                tracked.playerMetrics(DateWindow(), MatchModeFilter.Ranked, heroId = 1)
                tracked.playerMetrics(DateWindow(), MatchModeFilter.Ranked, heroId = 2)
            }
            delay(100)
            val visit = async { visitor.playerMetrics(DateWindow(), MatchModeFilter.Ranked, heroId = 3) }
            delay(100)
            trackedWork.cancel()
            tracked.close()
            visit.await()
            visitor.playerMetrics(DateWindow(), MatchModeFilter.Ranked, heroId = 4)
        } finally {
            tracked.close(); visitor.close(); client.close()
        }
        assertEquals(listOf("1" to 0L, "3" to 700L, "4" to 1_050L), starts,
            "the closed session's waiting request never starts and its turn is not reused, while the other's keep their spacing")
    }

    @Test
    fun aSlowHistoryDoesNotHoldBackTheRestOfARefresh() = runTest {
        val rankAsked = CompletableDeferred<Unit>()
        val client = client { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/match-history") ->
                    if (withTimeoutOrNull(3_000) { rankAsked.await() } != null) respond("""[{"match_id":1,"start_time":100}]""") else respondError(HttpStatusCode.InternalServerError)
                path.endsWith("/rank") -> {
                    rankAsked.complete(Unit)
                    respond("""{"badge":74,"rank":7,"subrank":4}""")
                }
                else -> respond("[]")
            }
        }
        val repo = repository(client, StatsRequestGate(clock()))
        try {
            repo.selectAccount(SteamAccountId.ofAccountId(555)!!)
            repo.refresh()
            assertNotNull(repo.state.value.fetchedAt["history"], "the rank was asked while the history was on its way")
            assertNotNull(repo.state.value.rank)
        } finally {
            repo.close()
            client.close()
        }
    }
}
