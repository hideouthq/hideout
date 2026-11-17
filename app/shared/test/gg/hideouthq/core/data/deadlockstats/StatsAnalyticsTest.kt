package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.network.schedulerEngine
import gg.hideouthq.core.network.schedulerGate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StatsAnalyticsTest {
    private val account = SteamAccountId.ofAccountId(555)!!

    private fun api(handler: (Url) -> String): Pair<DeadlockStatsApi, MutableList<Url>> {
        val urls = mutableListOf<Url>()
        val client = HttpClient(MockEngine { request -> urls += request.url; respond(handler(request.url)) })
        return DeadlockStatsApi(client) to urls
    }

    private fun TestScope.client(handler: MockRequestHandler) =
        HttpClient(schedulerEngine(testScheduler, handler))

    private fun TestScope.repository(client: HttpClient, storage: CacheStorage, clock: Clock = Clock.System) =
        DeadlockStatsRepository(client, storage, backgroundScope, clock = clock,
            requestGate = schedulerGate(testScheduler),
            ioDispatcher = StandardTestDispatcher(testScheduler))

    @Test
    fun lobbyMetricsAskForTheBandModeWindowAndHeroAndReadMeans() = runTest {
        val (api, urls) = api { """{"net_worth_per_min":{"avg":1138.8,"percentile50":1130.2,"std":182},"accuracy":{"avg":0.53}}""" }
        val metrics = api.playerMetrics(MatchModeFilter.Ranked, DateWindow(1000, 2000), 70, 79, heroId = 2)
        assertEquals(1138.8, metrics.getValue(PlayerMetric.SoulsPerMin).avg)
        assertEquals(0.53, metrics.getValue(PlayerMetric.Accuracy).avg)
        val query = urls.single().parameters
        assertEquals("/v1/analytics/player-stats/metrics", urls.single().encodedPath)
        assertEquals("ranked", query["match_mode"])
        assertEquals("normal", query["game_mode"])
        assertEquals("1000", query["min_unix_timestamp"])
        assertEquals("1999", query["max_unix_timestamp"], "half-open windows send an inclusive upper bound")
        assertEquals("70", query["min_average_badge"])
        assertEquals("79", query["max_average_badge"])
        assertEquals("2", query["hero_ids"])
        assertNull(query["account_ids"], "a lobby benchmark is not one player's numbers")
    }

    @Test
    fun accountScopedAnalyticsNameTheAccountAndHero() = runTest {
        val (api, urls) = api { url ->
            when {
                "hero-counter" in url.encodedPath -> """[{"hero_id":2,"enemy_hero_id":15,"wins":5,"matches_played":5,"kills":40}]"""
                "item-stats" in url.encodedPath -> """[{"item_id":3776945997,"matches":22,"wins":15,"losses":7,"players":1}]"""
                else -> """[{"account_id":7,"hero_id":1,"matches_played":12},{"account_id":7,"hero_id":13,"matches_played":3}]"""
            }
        }
        assertEquals(listOf(HeroCounter(2, 15, 5, 5)), api.heroCounters(account, MatchModeFilter.All, DateWindow()))
        assertEquals(listOf(ItemUsageStat(3_776_945_997, 22, 15, 7)), api.itemStats(account, 2, MatchModeFilter.Normal, DateWindow(100, 200)))
        assertEquals(2, api.heroCountsFor(listOf(SteamAccountId.ofAccountId(7)!!), MatchModeFilter.All, DateWindow()).size)
        assertEquals("555", urls[0].parameters["account_id"])
        assertEquals("ranked,unranked", urls[0].parameters["match_mode"])
        assertEquals("false", urls[0].parameters["same_lane_filter"], "a matchup is any enemy hero in the match, not only the one in your lane")
        assertEquals("2", urls[1].parameters["hero_id"])
        assertEquals("unranked", urls[1].parameters["match_mode"])
        assertEquals("7", urls[2].parameters["account_ids"])
        assertEquals(emptyList(), api.heroCountsFor(emptyList(), MatchModeFilter.All, DateWindow()), "no request for nobody")
        assertEquals(3, urls.size)
    }

    @Test
    fun seasonsKnowTheirStartAndDistributionRowsDecode() = runTest {
        val (api, _) = api { url ->
            if ("seasons" in url.encodedPath) {
                """[{"class_name":"season_1","name":"Beta Season 1","calibration_matches":8,"intervals":[
                    {"interval":1,"start_timestamp":100,"end_timestamp":200},{"interval":2,"start_timestamp":200,"end_timestamp":300}]}]"""
            } else {
                """[{"badge":74,"players":100,"rank":7,"subrank":4},{"badge":75,"players":20,"rank":7,"subrank":5}]"""
            }
        }
        val season = api.rankedSeasons().single()
        assertEquals(8, season.calibrationMatches)
        assertEquals(100L, season.startContaining(250), "the season, not the interval, starts the window")
        assertNull(season.startContaining(300), "an ended season contains nothing")
        assertEquals(listOf(RankDistributionEntry(74, 100), RankDistributionEntry(75, 20)), api.rankDistribution(DateWindow()))
    }

    @Test
    fun bulkSummariesKeepOnlyTheAccountsFinalNumbers() = runTest {
        val (api, urls) = api {
            """[{"match_id":42,"start_time":"2026-10-14 09:51:00","players":[{"account_id":555,"hero_id":20,
                "final_stats":{"time_stamp_s":1592,"player_damage":14963,"shots_hit":2543,"shots_missed":1677,"boss_damage":16822,
                "custom_user_stats":{"Bullet Stats##HitRate":22}}}]},
               {"match_id":43,"players":[{"account_id":999,"final_stats":{"player_damage":1}}]},
               {"match_id":44,"players":[{"account_id":555}]}]"""
        }
        val summaries = api.matchSummaries(account, listOf(42, 43, 44))
        assertEquals(1, summaries.size, "other players and missing stats are not summaries")
        with(summaries.single()) {
            assertEquals(42L, matchId)
            assertEquals(14_963, playerDamage)
            assertEquals(2_543, shotsHit)
            assertEquals(16_822, bossDamage)
        }
        val query = urls.single().parameters
        assertEquals("42,43,44", query["match_ids"])
        assertEquals("555", query["account_ids"])
        assertEquals("true", query["only_filtered_players"])
        assertEquals("true", query["include_player_final_stats"])
    }

    @Test
    fun summariesAreBatchedBoundedPersistedAndNotAskedForAgainWhileMissing() = runTest {
        val requested = mutableListOf<List<Long>>()
        var seconds = 10_000L
        val clock = object : kotlin.time.Clock { override fun now() = kotlin.time.Instant.fromEpochSeconds(seconds) }
        val client = client { request ->
            val ids = request.url.parameters["match_ids"]!!.split(",").map { it.toLong() }
            requested += ids
            respond("[" + ids.filter { it % 2 == 0L }.joinToString(",") { """{"match_id":$it,"players":[{"account_id":555,"final_stats":{"player_damage":${it * 10}}}]}""" } + "]")
        }
        val storage = MemoryStorage()
        val repo = repository(client, storage, clock)
        try {
            repo.selectAccount(account)
            val arrived = repo.refreshSummaries((1L..400L).toList())
            assertEquals(150, arrived)
            assertEquals(listOf(DeadlockStatsRepository.MaxSummaryRequest), requested.map { it.size }, "one request, bounded")
            assertEquals((1L..300L).toList(), requested.single(), "the first ones asked for, in order")
            assertEquals(150, repo.state.value.matchSummaries.size)
            assertEquals(20L, repo.state.value.matchSummaries.getValue(2).playerDamage?.toLong())
            requested.clear()
            repo.refreshSummaries((1L..70L).toList())
            assertEquals(emptyList(), requested, "odd ids the API lacks wait an hour; even ids are known")
            seconds += 3_601
            repo.refreshSummaries(listOf(1L, 3L))
            assertEquals(listOf(listOf(1L, 3L)), requested)

            val reopened = repository(client, storage, clock)
            reopened.selectAccount(account)
            assertEquals(150, reopened.state.value.matchSummaries.size, "summaries survive a restart")
            reopened.close()
        } finally {
            repo.close()
            client.close()
        }
    }

    @Test
    fun aMatchWithTheAccountsStatsInItsDetailIsNotSummarised() = runTest {
        var summaryRequests = 0
        val client = client { request ->
            if (request.url.encodedPath.endsWith("/42/metadata")) {
                respond("""{"match_info":{"match_id":42,"duration_s":1800,"winning_team":0,"players":[{"account_id":555,"team":0,"stats":[{"time_stamp_s":1800,"player_damage":1}]}]}}""")
            } else {
                summaryRequests++
                respond("[]")
            }
        }
        val repo = repository(client, MemoryStorage())
        try {
            repo.selectAccount(account)
            assertTrue(repo.fetchMatchDetail(42).isSuccess)
            assertEquals(0, repo.refreshSummaries(listOf(42L)))
            assertEquals(0, summaryRequests)
        } finally {
            repo.close()
            client.close()
        }
    }

    @Test
    fun aFailedSummaryRequestKeepsWhatWasKnownAndReportsTheFailure() = runTest {
        var fail = false
        val client = client { request ->
            if (fail) respondError(HttpStatusCode.NotFound) else {
                val id = request.url.parameters["match_ids"]!!.toLong()
                respond("""[{"match_id":$id,"players":[{"account_id":555,"final_stats":{"player_damage":5}}]}]""")
            }
        }
        val repo = repository(client, MemoryStorage())
        try {
            repo.selectAccount(account)
            repo.refreshSummaries(listOf(1L))
            fail = true
            assertFailsWith<Exception> { repo.refreshSummaries(listOf(2L)) }
            assertEquals(setOf(1L), repo.state.value.matchSummaries.keys)
        } finally {
            repo.close()
            client.close()
        }
    }

    @Test
    fun restoringACurrentArchiveReportsSnapshotWriteFailure() = runTest {
        val backing = MemoryStorage()
        var failWrites = false
        val storage = object : gg.hideouthq.core.data.CacheStorage by backing {
            override suspend fun write(name: String, text: String) {
                if (failWrites && name.startsWith("deadlock-stats-")) error("disk full")
                backing.write(name, text)
            }
        }
        MatchDetailArchive(storage).save(account.value, MatchDetail(42, durationS = 1800, winningTeam = 0,
            players = listOf(MatchParticipant(555, kills = 5))))
        val client = client { error("A current archive needs no network request") }
        val repo = repository(client, storage)
        try {
            repo.selectAccount(account)
            failWrites = true
            assertTrue(repo.fetchMatchDetail(42).isFailure)
            assertEquals("disk full", repo.state.value.lastPersistenceError)
        } finally { repo.close(); client.close() }
    }

    @Test
    fun fetchingADetailSaysWhetherItArrived() = runTest {
        var notFound = false
        val client = client { request ->
            if (notFound) respondError(HttpStatusCode.NotFound) else {
                assertEquals(HttpMethod.Get, request.method)
                respond("""{"match_info":{"match_id":42,"duration_s":1800,"winning_team":0,"players":[{"account_id":555,"team":0}]}}""")
            }
        }
        val repo = repository(client, MemoryStorage())
        try {
            repo.selectAccount(account)
            assertTrue(repo.fetchMatchDetail(42).isSuccess)
            assertTrue(repo.state.value.matchDetails.getValue(42).isComplete)
            notFound = true
            assertTrue(repo.fetchMatchDetail(43).isFailure)
        } finally {
            repo.close()
            client.close()
        }
    }
}
