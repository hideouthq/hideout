package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import gg.hideouthq.core.network.schedulerEngine
import gg.hideouthq.core.network.schedulerGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class DeadlockStatsRepositoryTest {
    private val accountId = SteamAccountId.ofAccountId(555)!!

    @Test
    fun refreshPopulatesStateAndPersistsIt() = runTest {
        val storage = MemoryStorage()
        val repo = repository(storage = storage, apiHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/match-history") -> respond(matchHistoryJson())
                request.url.encodedPath.endsWith("/rank") -> respond("""{"badge":45,"rank":4,"subrank":5}""")
                else -> respond("[]")
            }
        })
        repo.selectAccount(accountId)

        repo.refresh()

        assertEquals(1, repo.state.value.matchHistory.size)
        assertEquals(45, repo.state.value.rank?.badge)

        val reopened = repository(storage = storage, apiHandler = { respond("[]") })
        reopened.selectAccount(accountId)
        assertEquals(1, reopened.state.value.matchHistory.size)
    }

    @Test
    fun aFailingEndpointKeepsOldDataForThatFieldButStillUpdatesTheOthers() = runTest {
        var historyCalls = 0
        val repo = repository(apiHandler = { request ->
            when {
                request.url.encodedPath.endsWith("/match-history") -> {
                    historyCalls++
                    if (historyCalls == 1) respond(matchHistoryJson()) else respondError(HttpStatusCode.InternalServerError)
                }
                request.url.encodedPath.endsWith("/rank") -> respond("""{"badge":1,"rank":1,"subrank":1}""")
                else -> respond("[]")
            }
        })
        repo.selectAccount(accountId)
        repo.refresh()
        assertEquals(1, repo.state.value.matchHistory.size)

        repo.refresh()

        assertEquals(1, repo.state.value.matchHistory.size, "stale match history should survive a failed refetch")
        assertEquals(1, repo.state.value.rank?.badge, "an unrelated endpoint should still update")
    }

    @Test
    fun aSecondDiscoveryOfACompleteMatchDoesNotReDownload() = runTest {
        var downloadCalls = 0
        val repo = repository(apiHandler = { request ->
            if (request.url.host == "replay1.valve.net") downloadCalls++
            respond(metadataFile(CMsgMatchMetaDataContents.MatchInfo(match_id = 1, duration_s = 100, winning_team = 1,
                players = listOf(CMsgMatchMetaDataContents.Players(account_id = 1)))))
        })
        repo.selectAccount(accountId)

        val bytes = cacheFileBytes(matchId = 1)
        repo.onCacheCandidate(bytes)
        repo.onCacheCandidate(bytes)

        assertEquals(1, downloadCalls)
    }

    @Test
    fun searchByIdDoesNotCallTheNameSearchEndpoint() = runTest {
        var nameSearchCalled = false
        val repo = repository(apiHandler = { request ->
            if (request.url.encodedPath.endsWith("steam-search")) nameSearchCalled = true
            respond("""[{"account_id":555,"personaname":"x","profileurl":"u","avatarfull":"a"}]""")
        })

        val results = repo.searchPlayers("555")

        assertEquals(555L, results.single().accountId)
        assertFalse(nameSearchCalled)
    }

    private fun TestScope.repository(
        storage: MemoryStorage = MemoryStorage(),
        apiHandler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): DeadlockStatsRepository {
        val engine = schedulerEngine(testScheduler, apiHandler)
        val client = HttpClient(engine) { expectSuccess = false }
        val gate = schedulerGate(testScheduler)
        return DeadlockStatsRepository(client, storage, backgroundScope, requestGate = gate, ioDispatcher = StandardTestDispatcher(testScheduler))
    }

    private fun matchHistoryJson() = """[{"match_id":1,"hero_id":2,"hero_level":10,"start_time":100,"game_mode":1,"match_mode":4,
        |"player_team":0,"player_kills":5,"player_deaths":1,"player_assists":3,"denies":4,"net_worth":1000,
        |"last_hits":50,"match_duration_s":1800,"match_result":0,"player_match_outcome":1}]
    """.trimMargin().replace("\n", "")

    private fun cacheFileBytes(matchId: Long): ByteArray {
        val url = "http://replay1.valve.net/1422450/${matchId}_999.meta.bz2"
        return ("\u0000\u0001some-cache-index-bytes GET $url HTTP/1.1\r\n").toByteArray(Charsets.ISO_8859_1)
    }
}
