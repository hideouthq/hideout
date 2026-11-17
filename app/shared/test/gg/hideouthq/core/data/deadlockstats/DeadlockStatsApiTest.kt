package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.network.RemoteException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeadlockStatsApiTest {
    private val accountId = SteamAccountId.ofAccountId(123456)!!

    @Test
    fun decodesMatchHistory() = runTest {
        val body = """[{"match_id":1,"hero_id":2,"hero_level":10,"start_time":100,"game_mode":1,"match_mode":4,
            |"player_team":0,"player_kills":5,"player_deaths":1,"player_assists":3,"denies":4,"net_worth":1000,
            |"last_hits":50,"match_duration_s":1800,"match_result":0,"player_match_outcome":1}]
        """.trimMargin().replace("\n", "")
        val api = DeadlockStatsApi(mockClient { respond(body) })

        val history = api.matchHistory(accountId)

        assertEquals(1, history.size)
        assertEquals(1L, history.single().matchId)
        assertEquals(10, history.single().heroLevel)
    }

    @Test
    fun deathDetailsOmittedFromTheApiStaysNullButAnExplicitEmptyListStaysEmpty() = runTest {
        val withDeaths = DeadlockStatsApi(mockClient { respond(
            """{"match_info":{"match_id":1,"players":[{"account_id":555,"player_slot":4,
                |"death_details":[{"game_time_s":227,"killer_player_slot":5,"death_duration_s":8}]}]}}""".trimMargin(),
        ) }).matchMetadata(1)
        val player = withDeaths.players.single()
        assertEquals(4, player.playerSlot)
        assertEquals(listOf(MatchDeathEvent(gameTimeS = 227, killerPlayerSlot = 5, deathDurationS = 8)), player.deathDetails)

        val omitted = DeadlockStatsApi(mockClient { respond(
            """{"match_info":{"match_id":2,"players":[{"account_id":555}]}}""",
        ) }).matchMetadata(2)
        assertEquals(null, omitted.players.single().deathDetails, "an API response that omits the field means unknown, not zero deaths")

        val knownEmpty = DeadlockStatsApi(mockClient { respond(
            """{"match_info":{"match_id":3,"players":[{"account_id":555,"death_details":[]}]}}""",
        ) }).matchMetadata(3)
        assertEquals(emptyList(), knownEmpty.players.single().deathDetails, "an explicit empty list means extraction ran and found none")
    }

    @Test
    fun aNotFoundResponseBecomesDataErrorNotFound() = runTest {
        val api = DeadlockStatsApi(mockClient { respondError(HttpStatusCode.NotFound) })

        val failure = assertFailsWith<RemoteException> { api.matchHistory(accountId) }
        assertEquals(DataError.NotFound, failure.error)
    }

    @Test
    fun retriesOn429UntilTheRetryAfterBudgetIsRespected() = runTest {
        var calls = 0
        val api = DeadlockStatsApi(
            mockClient {
                calls++
                if (calls < 3) {
                    respondError(HttpStatusCode.TooManyRequests, headers = headersOf(HttpHeaders.RetryAfter, "0"))
                } else {
                    respond("[]")
                }
            },
        )

        val result = api.matchHistory(accountId)

        assertEquals(emptyList(), result)
        assertEquals(3, calls)
    }

    @Test
    fun retriesOn503AndEventuallyGivesUp() = runTest {
        var calls = 0
        val api = DeadlockStatsApi(
            mockClient {
                calls++
                respondError(HttpStatusCode.ServiceUnavailable, headers = headersOf(HttpHeaders.RetryAfter, "0"))
            },
        )

        val failure = assertFailsWith<RemoteException> { api.matchHistory(accountId) }

        assertEquals(DataError.Unavailable, failure.error)
        assertTrue(calls in 2..8, "expected a bounded number of attempts, got $calls")
    }

    private fun mockClient(handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) { expectSuccess = false }
}
