package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsRepository
import gg.hideouthq.core.data.deadlockstats.LocalSteamIdentity
import gg.hideouthq.core.data.deadlockstats.ReplayCacheWatcherPort
import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstallation
import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstaller
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.FakeCatalogRepository
import gg.hideouthq.core.data.fake.FakeStore
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.network.schedulerEngine
import gg.hideouthq.core.network.schedulerGate
import gg.hideouthq.feature.stats.AccountLookup
import gg.hideouthq.feature.stats.MatchDetailState
import gg.hideouthq.feature.stats.MatchPendingPhase
import gg.hideouthq.feature.stats.MetricKind
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.StatsContent
import gg.hideouthq.feature.stats.StatsFilter
import gg.hideouthq.feature.stats.StatsMode
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.StatsSelection
import gg.hideouthq.feature.stats.StatsTab
import gg.hideouthq.feature.stats.TabContent
import gg.hideouthq.feature.stats.TeammateRow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class FakeWatcher(private val cacheFiles: List<Pair<ByteArray, Long>>) : ReplayCacheWatcherPort {
    var starts = 0
    var closes = 0

    override suspend fun start(onCandidate: suspend (header: ByteArray, writtenAt: Long) -> Unit) {
        starts++
        cacheFiles.forEach { (header, writtenAt) -> onCandidate(header, writtenAt) }
        awaitCancellation()
    }

    override fun close() {
        closes++
    }
}

private class FakeApi(var failHistory: Boolean = false) {
    val requests = mutableListOf<Pair<HttpMethod, Url>>()
    val uploads = mutableListOf<String>()
    var mateStats = """[{"mate_id":9,"matches_played":2,"wins":1,"matches":[11,12]}]"""
    var partyStats = mateStats
    var failParty = false
    var failMetrics = false
    var holdMetrics: CompletableDeferred<Unit>? = null
    var failSeasons = 0
    var newMatch = false
    var pendingMatch = false
    var detailsReady = false
    var holdDetail: CompletableDeferred<Unit>? = null
    var failSummaries = 0
    val now = 1_791_900_000L

    fun history(account: String) = when (account) {
        "555" -> """[{"match_id":11,"hero_id":2,"start_time":${now - 3600},"game_mode":1,"match_mode":1,"player_team":0,"player_kills":5,"player_deaths":1,
            "player_assists":3,"net_worth":30000,"match_duration_s":1800,"match_result":0},
            {"match_id":12,"hero_id":2,"start_time":${now - 7200},"game_mode":1,"match_mode":4,"player_team":0,"player_kills":5,"player_deaths":2,
            "player_assists":3,"net_worth":30000,"match_duration_s":1800,"match_result":1,"ranked_display_badge":74}${if (newMatch) NewMatch else ""}${if (pendingMatch) PendingMatch else ""}]"""
        else -> """[{"match_id":21,"hero_id":13,"start_time":${now - 3600},"game_mode":1,"match_mode":1,"player_team":1,"player_kills":9,"player_deaths":9,
            "player_assists":9,"net_worth":20000,"match_duration_s":1500,"match_result":1}]"""
    }

    fun client(scheduler: TestCoroutineScheduler) = HttpClient(schedulerEngine(scheduler, handler))

    private val handler: MockRequestHandler = { request ->
        synchronized(requests) { requests += request.method to request.url }
        val path = request.url.encodedPath
        when {
            path == "/v1/matches/salts" -> {
                synchronized(requests) { uploads += (request.body as io.ktor.http.content.TextContent).text }
                respond("")
            }
            path.endsWith("/match-history") -> if (failHistory) respondError(HttpStatusCode.ServiceUnavailable) else respond(history(path.split("/")[3]))
            path == "/v1/players/steam" -> respond(
                request.url.parameters["account_ids"]!!.split(",").joinToString(",", "[", "]") { id ->
                    """{"account_id":$id,"personaname":"${if (id.length == 3) "Mira" else "P$id"}","avatarfull":"https://avatars.test/$id.jpg"}"""
                },
            )
            path.endsWith("/rank") -> respond("""{"badge":74,"rank":7,"subrank":4}""")
            path == "/v1/players/steam-search" -> respond("""[{"account_id":777,"personaname":"Mirai"}]""")
            path.endsWith("/mate-stats") -> when {
                request.url.parameters["same_party"] != "true" -> respond(mateStats)
                failParty -> respondError(HttpStatusCode.InternalServerError)
                else -> respond(partyStats)
            }
            path.endsWith("/enemy-stats") -> respond("[]")
            path == "/v1/assets/items" -> respond(
                """[{"id":1,"class_name":"upgrade_test","name":"Test","item_slot_type":"weapon","cost":500},{"id":2,"class_name":"ability_test","name":"Ability"}]""",
            )
            path == "/v1/assets/ranks" -> respond("""[{"tier":7,"name":"Emissary","color":"#B47FEB","images":{}}]""")
            path == "/v1/assets/ranked-seasons" && failSeasons > 0 -> {
                failSeasons--
                respondError(HttpStatusCode.InternalServerError)
            }
            path == "/v1/assets/ranked-seasons" -> respond("""[{"name":"Season 1","calibration_matches":8,"intervals":[{"start_timestamp":${now - 86_400},"end_timestamp":${now + 86_400}}]}]""")
            path == "/v1/players/rank/distribution" -> respond("""[{"badge":74,"players":120},{"badge":75,"players":30}]""")
            path == "/v1/matches/metadata" && failSummaries > 0 -> {
                failSummaries--
                respondError(HttpStatusCode.InternalServerError)
            }
            path == "/v1/matches/42/metadata" || path == "/v1/matches/43/metadata" -> respond(
                """{"match_info":{"match_id":${path.split("/")[3]},"duration_s":1800,"winning_team":0,"players":[{"account_id":555,"team":0,"items":[""" +
                    """{"game_time_s":60,"item_id":1},{"game_time_s":90,"item_id":2}${if ("/42/" in path) """,{"game_time_s":120,"item_id":777}""" else ""}]}]}}""",
            )
            path == "/v1/matches/11/metadata" || path == "/v1/matches/21/metadata" -> {
                holdDetail?.await()
                if (!detailsReady) respondError(HttpStatusCode.NotFound) else respond(
                    """{"match_info":{"match_id":${path.split("/")[3]},"duration_s":1800,"winning_team":0,"players":[{"account_id":${if ("/11/" in path) 555 else 777},"hero_id":${if ("/11/" in path) 2 else 13},"team":0,"kills":6}]}}""",
                )
            }
            path == "/v1/matches/999/metadata" || path == "/v1/matches/14/metadata" -> respondError(HttpStatusCode.NotFound)
            path.endsWith("/metadata") -> respond("[]")
            path.startsWith("/v1/analytics/player-stats") -> {
                holdMetrics?.await()
                if (failMetrics) respondError(HttpStatusCode.InternalServerError) else respond("""{"kills":{"avg":6.0},"deaths":{"avg":5.0},"assists":{"avg":8.5}}""")
            }
            else -> respond("[]")
        }
    }

    fun paths() = synchronized(requests) { requests.map { it.second.encodedPath } }

    private val NewMatch = """,{"match_id":13,"hero_id":2,"start_time":${now - 600},"game_mode":1,"match_mode":1,"player_team":0,"player_kills":1,"player_deaths":1,
        "player_assists":1,"net_worth":10000,"match_duration_s":1500,"match_result":0}"""

    private val PendingMatch = """,{"match_id":14,"hero_id":2,"start_time":${now - 2410},"game_mode":1,"match_mode":1,"player_team":0,"player_kills":1,"player_deaths":1,
        "player_assists":1,"net_worth":10000,"match_duration_s":2400,"match_result":0}"""
}

private class CountingInstaller : SteamTrackingInstaller {
    val inspections = AtomicInteger()
    override suspend fun select(id: String) = inspect()
    override suspend fun inspect() = SteamTrackingInstallation(supported = true).also { inspections.incrementAndGet() }
    override suspend fun install() = SteamTrackingInstallation(supported = true, installed = true)
    override suspend fun uninstall() = SteamTrackingInstallation(supported = true)
}

private class FakePlatform(
    api: FakeApi,
    private val scheduler: TestCoroutineScheduler,
    private val storage: CacheStorage = MemoryStorage(),
    val identities: List<LocalSteamIdentity> = emptyList(),
    override val steamTrackingInstaller: SteamTrackingInstaller? = null,
) : StatsPlatform {
    val watchers = mutableListOf<FakeWatcher>()
    var cacheFiles: List<Pair<ByteArray, Long>> = emptyList()
    var visitors = 0
    var identityReads = 0
    private val client = api.client(scheduler)

    private fun gate() = schedulerGate(scheduler)

    override fun openTracked(scope: CoroutineScope): DeadlockStatsRepository {
        val watcher = FakeWatcher(cacheFiles).also { watchers += it }
        return DeadlockStatsRepository(client, storage, scope, watcher, FixedClock, requestGate = gate(), ioDispatcher = StandardTestDispatcher(scheduler))
    }

    override fun openVisitor(scope: CoroutineScope): DeadlockStatsRepository {
        visitors++
        return DeadlockStatsRepository(client, MemoryStorage(), scope, clock = FixedClock, requestGate = gate(), ioDispatcher = StandardTestDispatcher(scheduler))
    }

    override fun localIdentities(): List<LocalSteamIdentity> {
        identityReads++
        return identities
    }
}

private object FixedClock : Clock {
    override fun now() = Instant.fromEpochSeconds(1_791_900_000)
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsServiceTest {
    private fun TestScope.service(platform: FakePlatform, preferences: Preferences) =
        StatsService(platform, preferences, backgroundScope, FixedClock, StandardTestDispatcher(testScheduler))

    private suspend fun settle() = delay(300)

    private suspend fun <T> eventually(block: () -> T?): T = withTimeout(10_000) {
        var value = block()
        while (value == null) {
            delay(10)
            value = block()
        }
        value
    }

    private inner class Run(val api: FakeApi, val platform: FakePlatform, val preferences: Preferences, test: TestScope) {
        val backgroundScope = test.backgroundScope
        val service = test.service(platform, preferences)
        val source = test.sourceOf(service)

        suspend fun tracking(account: Long = 555): StatsSession {
            service.useAccount(account)
            return eventually { service.state.value.tracked }
        }
    }

    private fun TestScope.sourceOf(service: StatsService) =
        LiveStatsSource(service, FakeCatalogRepository(FakeStore(FakeConfig())), FixedClock, ZoneOffset.UTC, StandardTestDispatcher(testScheduler))

    private fun withStats(api: FakeApi = FakeApi(), identities: List<LocalSteamIdentity> = emptyList(), test: suspend Run.() -> Any?) = runTest {
        val run = Run(api, FakePlatform(api, testScheduler, identities = identities), Preferences(MemoryStorage()), this)
        try {
            run.test()
        } finally {
            run.service.close()
        }
    }

    @Test
    fun withNoAccountNothingIsFetchedAndSetupSuggestsTheLatestLogin() = withStats(identities = listOf(
        LocalSteamIdentity(76561198000000001, "Old", false, 100), LocalSteamIdentity(76561199118057941, "Mira", false, 200),
    )) {
        val setup = withTimeout(10_000) { source.content(StatsSelection(StatsFilter(), StatsTab.Overview), null).filterIsInstance<StatsContent.Setup>().first { it.suggestion is Loadable.Ready } }
        val suggestion = assertNotNull(setup.suggestion.valueOrNull)
        assertEquals(1_157_792_213L, suggestion.accountId)
        assertEquals("Mira", suggestion.name)
        assertEquals(emptyList(), api.paths(), "nothing is fetched before an account is chosen")
        assertEquals(0, platform.watchers.size, "no watcher without consent")
        assertNull(preferences.statsPreferences().accountId, "a suggestion is never chosen for the user")
    }

    @Test
    fun choosingAnAccountWatchesWithoutUploadingAndTurningOffStopsEverything() = withStats {
        val session = tracking()
        eventually { session.data.value.matchHistory.takeIf { it.size == 2 } }
        val watcher = platform.watchers.single()
        eventually { watcher.starts.takeIf { it == 1 } }
        assertTrue(session.data.value.watcherEnabled)
        assertTrue(api.requests.none { it.first == HttpMethod.Post }, "nothing is ever posted")
        assertEquals("Mira", session.data.value.profile?.personaName)

        service.setEnabled(false)
        eventually { if (service.state.value.tracked == null) Unit else null }
        assertEquals(1, watcher.closes, "turning Stats off stops the cache watcher")
        val before = api.requests.size
        session.refresh()
        settle()
        assertEquals(before, api.requests.size, "a closed session never fetches again")
        assertEquals(false, preferences.statsPreferences().enabled)
        service.setEnabled(true)
        assertEquals(555L, eventually { service.state.value.tracked }.accountId, "turning Stats on again opens the chosen account")
    }

    @Test
    fun sharingMatchIdsSendsWhatSteamCachedFromTheMomentItWasTurnedOn() = withStats {
        preferences.setShareMatchIds(true)
        val since = assertNotNull(preferences.statsPreferences().shareMatchIdsSince)
        platform.cacheFiles = listOf(cacheFile(1) to since - 60, cacheFile(2) to since)
        tracking()
        eventually { synchronized(api.requests) { api.uploads.toList() }.takeIf { it.isNotEmpty() } }
        settle()
        assertEquals(listOf("""[{"match_id":2,"cluster_id":1,"metadata_salt":99}]"""), synchronized(api.requests) { api.uploads.toList() })
    }

    @Test
    fun clearingSavedStatsKeepsTheAccountAndDoesNotImmediatelyRefetchHistory() = withStats {
        val before = tracking()
        eventually { before.data.value.matchHistory.takeIf { it.size == 2 } }
        settle()
        val historyRequests = api.paths().count { it.contains("match-history") }
        service.clearSaved()
        val after = assertNotNull(service.state.value.tracked)
        assertTrue(before !== after)
        assertEquals(555L, preferences.statsPreferences().accountId)
        assertTrue(preferences.statsPreferences().enabled)
        assertTrue(after.data.value.matchHistory.isEmpty())
        settle()
        assertEquals(historyRequests, api.paths().count { it.contains("match-history") })
        assertEquals(1, platform.watchers.first().closes)
    }

    @Test
    fun theSeasonsAndRankDistributionLoadOnceADayWithTheAccount() = withStats {
        service.useAccount(555)
        val seasons = eventually { service.state.value.seasons.valueOrNull }
        assertEquals("Season 1", seasons.single().name)
        assertEquals(150, eventually { service.state.value.distribution.valueOrNull }.sumOf { it.players }.toInt())
        service.useAccount(556)
        eventually { service.state.value.tracked?.takeIf { it.accountId == 556L } }
        settle()
        assertEquals(1, api.paths().count { it == "/v1/assets/ranked-seasons" }, "fetched once a day, not per account")
        assertEquals(1, api.paths().count { it == "/v1/players/rank/distribution" })
    }

    @Test
    fun switchingAccountsClosesTheOldSessionSoNothingOfItLeaks() = withStats {
        val first = tracking()
        eventually { first.data.value.matchHistory.takeIf { it.isNotEmpty() } }
        service.useAccount(666)
        val second = eventually { service.state.value.tracked?.takeIf { it.accountId == 666L } }
        assertEquals(1, platform.watchers.first().closes, "the first account's watcher stopped")
        val content = withTimeout(10_000) {
            source.content(StatsSelection(StatsFilter(), StatsTab.Matches), null).filterIsInstance<StatsContent.Active>()
                .first { it.accountId == 666L && (it.tab as? TabContent.Matches)?.content?.total == 1 }
        }
        val rows = (content.tab as TabContent.Matches).content.days.flatMap { it.rows }
        assertEquals(listOf(21L), rows.map { it.matchId }, "only the new account's matches")
        assertTrue(second.data.value.matchHistory.none { it.matchId == 11L })
    }

    @Test
    fun steamIsLookedAtForTheBridgeOnlyOnceTheDeveloperSettingIsOn() = runTest {
        val installer = CountingInstaller()
        val off = service(FakePlatform(FakeApi(), testScheduler, steamTrackingInstaller = installer), Preferences(MemoryStorage()))
        settle()
        off.close()
        assertEquals(0, installer.inspections.get(), "a player who never turned the bridge on never has Steam probed for it")

        val preferences = Preferences(MemoryStorage()).apply { setSteamTrackingEnabled(true) }
        val on = service(FakePlatform(FakeApi(), testScheduler, steamTrackingInstaller = installer), preferences)
        eventually { installer.inspections.get().takeIf { it > 0 } }
        on.close()
    }

    @Test
    fun aFailedRefreshKeepsTheSavedStatsAndSaysSo() = runTest {
        val api = FakeApi()
        val storage = MemoryStorage()
        val firstRun = service(FakePlatform(api, testScheduler, storage), Preferences(MemoryStorage()))
        firstRun.useAccount(555)
        val saved = eventually { firstRun.state.value.tracked }
        eventually { saved.data.value.matchHistory.takeIf { it.size == 2 } }
        firstRun.close()

        api.failHistory = true
        val preferences = Preferences(MemoryStorage())
        preferences.setStatsAccount(555)
        val service = service(FakePlatform(api, testScheduler, storage), preferences)
        val session = eventually { service.state.value.tracked }
        session.refresh()
        eventually { session.sync.value.takeIf { it.failed && !it.refreshing } }
        assertEquals(2, session.data.value.matchHistory.size, "the saved history is still shown")
        service.close()
    }

    @Test
    fun visitingSomeoneOpensASeparateSessionWithoutAWatcher() = withStats {
        val own = tracking()
        val visit = service.visiting(666) { flowOf(it) }.first()
        assertTrue(visit !== own)
        assertTrue(!visit.tracked)
        assertEquals(1, platform.watchers.size, "visits never watch Steam's cache")
        assertTrue(service.visiting(555) { flowOf(it) }.first() === own, "visiting yourself is your own session")
        eventually { visit.data.value.matchHistory.takeIf { it.isNotEmpty() } }
        assertEquals(listOf(21L), visit.data.value.matchHistory.map { it.matchId })
    }

    @Test
    fun lookingUpAnAccountWorksBeforeOneIsChosen() = withStats {
        eventually { service.state.value.preferences }
        val byId = assertIs<AccountLookup.Found>(source.lookUp("https://steamcommunity.com/profiles/76561199118057941/"))
        assertEquals(1_157_792_213L, byId.players.single().accountId)
        assertTrue(api.requests.any { it.second.parameters["account_ids"] == "1157792213" })
        val byName = assertIs<AccountLookup.Found>(source.lookUp("Mirai"))
        assertEquals("Mirai", byName.players.single().name)
        assertEquals(AccountLookup.Idle, source.lookUp("   "))
    }

    @Test
    fun aPartyMemberWithOneGameIsShownByNameEvenBehindManySingleGames() = withStats(FakeApi().apply {
        val party = """{"mate_id":99,"matches_played":1,"wins":0,"matches":[12]}"""
        mateStats = ((1..30).map { """{"mate_id":$it,"matches_played":1,"wins":1,"matches":[11]}""" } + party).joinToString(",", "[", "]")
        partyStats = "[$party]"
    }) {
        val session = tracking()
        eventually { session.data.value.mateStats.takeIf { it.isNotEmpty() } }
        fun named(rows: List<TeammateRow>?) = rows?.takeIf { it.any { row -> row.player.name == "P99" } }
        val selection = StatsSelection(StatsFilter(StatsMode.All, StatsPeriod.Month), StatsTab.Teammates)
        val teammates = withTimeout(10_000) {
            source.content(selection, null).filterIsInstance<StatsContent.Active>().map { named((it.tab as? TabContent.Teammates)?.content?.teammates?.valueOrNull) }.filterNotNull().first()
        }
        assertEquals(listOf(99L), teammates.map { it.player.accountId }, "single games are left out unless the API marks a party")
        assertTrue(teammates.single().party)
        val overview = withTimeout(10_000) {
            source.content(selection.copy(tab = StatsTab.Overview), null).filterIsInstance<StatsContent.Active>()
                .map { named((it.tab as? TabContent.Overview)?.content?.teammates?.valueOrNull) }.filterNotNull().first()
        }
        assertEquals(listOf(99L), overview.map { it.player.accountId }, "the overview shows the same teammates, by name")
    }

    @Test
    fun theGlobalFiltersReachTheSocialRequests() = withStats(FakeApi().apply { partyStats = "[]" }) {
        val session = tracking()
        eventually { session.data.value.mateStats.takeIf { it.isNotEmpty() } }
        val content = withTimeout(10_000) {
            source.content(StatsSelection(StatsFilter(StatsMode.Ranked, StatsPeriod.Week), StatsTab.Teammates), null).filterIsInstance<StatsContent.Active>()
                .first { (it.tab as? TabContent.Teammates)?.content?.teammates is Loadable.Ready }
        }
        assertEquals(1, content.matchCount, "one ranked match this week")
        val party = eventually { synchronized(api.requests) { api.requests.firstOrNull { it.second.parameters["same_party"] == "true" } } }
        assertNull(party.second.parameters["min_unix_timestamp"], "the party list covers the whole history; the filters apply to its match ids")
        assertTrue((content.tab as TabContent.Teammates).content.teammates.valueOrNull!!.isEmpty(), "one shared ranked match is too few, and not a party by itself")
        withTimeout(10_000) {
            source.content(StatsSelection(StatsFilter(StatsMode.All, StatsPeriod.Month), StatsTab.Teammates), null).filterIsInstance<StatsContent.Active>()
                .first { (it.tab as? TabContent.Teammates)?.content?.teammates is Loadable.Ready }
        }
        assertEquals(1, synchronized(api.requests) { api.requests.count { it.second.parameters["same_party"] == "true" } }, "another period asks for no new party list")
    }

    @Test
    fun aRefreshSkipsWhatNoScreenShowsAndAssetsUnderADayOld() = runTest {
        val api = FakeApi()
        val storage = MemoryStorage()
        val service = service(FakePlatform(api, testScheduler, storage), Preferences(MemoryStorage()))
        service.useAccount(555)
        val session = eventually { service.state.value.tracked }
        eventually { session.sync.value.takeIf { it.opened && !it.refreshing && session.data.value.rankAssets.isNotEmpty() } }
        assertTrue(api.paths().none { it == "/v1/players/hero-stats" }, "the all-time hero totals are not shown anywhere")
        assertEquals(1, api.paths().count { it == "/v1/assets/items" })
        service.close()

        val preferences = Preferences(MemoryStorage())
        preferences.setStatsAccount(555)
        val again = service(FakePlatform(api, testScheduler, storage), preferences)
        val reopened = eventually { again.state.value.tracked }
        reopened.refresh()
        eventually { api.paths().count { it.endsWith("/match-history") }.takeIf { it == 2 } }
        eventually { reopened.sync.value.takeIf { !it.refreshing } }
        settle()
        assertEquals(1, api.paths().count { it == "/v1/assets/items" }, "items saved under a day ago are not asked for again")
        assertEquals(1, api.paths().count { it == "/v1/assets/ranks" })
        again.close()
    }

    @Test
    fun aRefreshYouAskForRetriesWhatFailedAndKeepsWhatArrived() = withStats(FakeApi().apply { failMetrics = true }) {
        val session = tracking()
        eventually { session.sync.value.takeIf { !it.refreshing && session.data.value.rankAssets.isNotEmpty() } }
        withTimeout(10_000) { source.overviews().first { it.averages is Loadable.Failed && it.teammates is Loadable.Ready } }
        fun party() = synchronized(api.requests) { api.requests.count { it.second.parameters["same_party"] == "true" } }
        val partyBefore = party()
        api.failMetrics = false
        source.refresh(null)
        withTimeout(10_000) { source.overviews().first { it.averages is Loadable.Ready } }
        settle()
        assertEquals(partyBefore, party(), "the party list that arrived is not asked again")
    }

    @Test
    fun failedSummariesAreAskedAgainOnRetryAndNotInALoop() = withStats(FakeApi().apply { failSummaries = 1 }) {
        val session = tracking()
        fun asked() = api.paths().count { it == "/v1/matches/metadata" }
        eventually { session.data.value.matchHistory.takeIf { it.size == 2 } }
        withTimeout(10_000) { source.overviews().first { it.recent.size == 2 } }
        eventually { asked().takeIf { it == 1 } }
        eventually { session.sync.value.takeIf { !it.enriching } }
        withTimeout(10_000) { source.overviews().first { it.recent.size == 2 } }
        settle()
        assertEquals(1, asked(), "a failed batch waits instead of asking again at once")
        source.retry(null)
        eventually { asked().takeIf { it == 2 } }
    }

    @Test
    fun twoQuickRefreshesBothSucceed() = withStats {
        val session = tracking()
        eventually { session.sync.value.takeIf { !it.refreshing && session.data.value.fetchedAt["history"] != null } }
        repeat(2) { round ->
            val before = api.paths().count { it.endsWith("/match-history") }
            session.refresh()
            eventually { api.paths().count { it.endsWith("/match-history") }.takeIf { it > before } }
            eventually { session.sync.value.takeIf { !it.refreshing } }
            assertFalse(session.sync.value.failed, "refresh ${round + 1} in the same second as the last one succeeded")
        }
    }

    @Test
    fun anUnrecognisedItemAsksForTheItemListOnceEvenWithinADay() = withStats {
        val session = tracking()
        eventually { session.sync.value.takeIf { !it.refreshing && session.data.value.itemAssets.isNotEmpty() } }
        fun items() = api.paths().count { it == "/v1/assets/items" }
        assertEquals(1, items())
        withTimeout(10_000) { source.match(43, null).filterIsInstance<MatchDetailState.Ready>().first() }
        settle()
        assertEquals(1, items(), "an ability is a known entry, not a reason to ask again")
        withTimeout(10_000) { source.match(42, null).filterIsInstance<MatchDetailState.Ready>().first() }
        eventually { items().takeIf { it == 2 } }
        withTimeout(10_000) { source.match(42, null).filterIsInstance<MatchDetailState.Ready>().first() }
        settle()
        assertEquals(2, items(), "asked once, not on every look at the match")
    }

    @Test
    fun cachedExtrasAreAskedAgainOnlyWhenARefreshBringsNewMatches() = withStats {
        val session = tracking()
        eventually { session.sync.value.takeIf { !it.refreshing && session.data.value.rankAssets.isNotEmpty() } }
        withTimeout(10_000) { source.overviews().first { it.averages is Loadable.Ready } }
        fun asked() = api.paths().count { it.startsWith("/v1/analytics/player-stats") }
        val before = asked()
        session.refresh()
        eventually { api.paths().count { it.endsWith("/match-history") }.takeIf { it == 2 } }
        eventually { session.sync.value.takeIf { !it.refreshing } }
        withTimeout(10_000) { source.overviews().first { it.averages is Loadable.Ready } }
        settle()
        assertEquals(before, asked(), "the same matches need the same averages")

        api.newMatch = true
        session.refresh()
        eventually { session.data.value.matchHistory.takeIf { it.size == 3 } }
        eventually { session.sync.value.takeIf { !it.refreshing } }
        withTimeout(10_000) { source.overviews().first { asked() > before } }
    }

    private fun LiveStatsSource.overviews(filter: StatsFilter = StatsFilter()) =
        content(StatsSelection(filter, StatsTab.Overview), null).filterIsInstance<StatsContent.Active>().map { (it.tab as? TabContent.Overview)?.content }.filterNotNull()

    @Test
    fun rankAveragesShowLoadingThenAFailureWithRetryNeverAMissingAverage() = withStats {
        val session = tracking()
        eventually { session.data.value.rank }
        eventually { session.data.value.rankAssets.takeIf { it.isNotEmpty() } }
        api.holdMetrics = CompletableDeferred()
        val loading = withTimeout(10_000) { source.overviews().first { it.cohort?.tierName == "Emissary" } }
        assertEquals(Loadable.Loading, loading.averages, "on its way, not \"No rank average\"")
        assertEquals(RankCohort("Emissary", rankedOnly = true), loading.cohort, "with normal matches counted, the averages say they are ranked lobbies'")
        api.failMetrics = true
        api.holdMetrics!!.complete(Unit)
        withTimeout(10_000) { source.overviews().first { it.averages is Loadable.Failed } }
        api.failMetrics = false
        source.retry(null)
        val ready = withTimeout(10_000) { source.overviews().first { it.averages is Loadable.Ready } }
        assertEquals((6.0 + 8.5) / 5.0, ready.averages.valueOrNull!![MetricKind.Kda])
        val asked = synchronized(api.requests) { api.requests.map { it.second }.filter { it.encodedPath.startsWith("/v1/analytics/player-stats") } }
        assertTrue(asked.isNotEmpty() && asked.all { it.parameters["match_mode"] == "ranked" }, "averages at a rank are asked of ranked lobbies only")
    }

    @Test
    fun aFailedPartyListFailsTheTeammatesWithRetryInsteadOfSayingThereIsNoParty() = withStats(FakeApi().apply { failParty = true }) {
        val session = tracking()
        eventually { session.data.value.mateStats.takeIf { it.isNotEmpty() } }
        val selection = StatsSelection(StatsFilter(), StatsTab.Teammates, partyOnly = true)
        fun social() = source.content(selection, null).filterIsInstance<StatsContent.Active>().map { (it.tab as? TabContent.Teammates)?.content }.filterNotNull()
        withTimeout(10_000) { social().first { it.teammates is Loadable.Failed } }
        api.failParty = false
        source.retry(null)
        val ready = withTimeout(10_000) { social().first { it.teammates is Loadable.Ready } }
        assertEquals(listOf(9L), ready.teammates.valueOrNull!!.map { it.player.accountId })
    }

    @Test
    fun normalMatchesAloneCompareWithNoRankAndAskForNoAverages() = withStats {
        val session = tracking()
        eventually { session.data.value.rank }
        val normal = StatsFilter(StatsMode.Normal, StatsPeriod.Month)
        assertNull(withTimeout(10_000) { source.overviews(normal).first() }.cohort, "unranked lobbies have no rank to compare with")
        val heroes = withTimeout(10_000) {
            source.content(StatsSelection(normal, StatsTab.Heroes), null).filterIsInstance<StatsContent.Active>().map { (it.tab as? TabContent.Heroes)?.content }.filterNotNull().first()
        }
        assertNull(heroes.cohort)
        settle()
        assertTrue(api.paths().none { it.startsWith("/v1/analytics/player-stats") || it == "/v1/analytics/hero-stats" }, "nothing is asked that could only come back empty")
    }

    @Test
    fun thisSeasonWaitsForItsDatesShowsTheFailureAndAsksAgainOnRetry() = withStats(FakeApi().apply { failSeasons = 1 }) {
        service.useAccount(555)
        val selection = StatsSelection(StatsFilter(StatsMode.All, StatsPeriod.Season), StatsTab.Overview)
        val failed = withTimeout(10_000) { source.content(selection, null).filterIsInstance<StatsContent.Active>().first { it.season is Loadable.Failed } }
        assertNull(failed.tab, "no month of matches under this season's name")
        assertNull(failed.matchCount)
        source.retry(null)
        val ready = withTimeout(10_000) { source.content(selection, null).filterIsInstance<StatsContent.Active>().first { it.tab != null } }
        assertEquals(Loadable.Ready(true), ready.season)
        assertEquals(2, api.paths().count { it == "/v1/assets/ranked-seasons" }, "a failure is asked again on retry, not a day later")
        assertEquals(1, api.paths().count { it == "/v1/players/rank/distribution" }, "the distribution that arrived is not asked again")
    }

    @Test
    fun aVisitOnScreenIsNeverClosedAndGoingBackToAClosedOneOpensItAgain() = withStats {
        service.useAccount(555)
        eventually { service.state.value.tracked }
        val held = CompletableDeferred<StatsSession>()
        val showing = backgroundScope.launch { service.visiting(1001) { session -> flow<Unit> { held.complete(session); awaitCancellation() } }.collect {} }
        val shown = held.await()
        for (id in 1002L..1005L) service.visiting(id) { flowOf(it) }.first()
        assertTrue(service.visitedSession(1001) === shown, "the visit on screen stays open")
        assertNull(service.visitedSession(1002), "idle visits beyond two close, oldest first")
        showing.cancel()
        for (id in listOf(2001L, 2002L, 2003L, 2001L)) {
            val content = withTimeout(10_000) {
                source.content(StatsSelection(StatsFilter(), StatsTab.Matches), id).filterIsInstance<StatsContent.Active>()
                    .first { (it.tab as? TabContent.Matches)?.content?.total == 1 }
            }
            assertEquals(id, content.accountId, "going back to $id shows its stats again")
            assertFalse(content.isYou)
        }
    }

    @Test
    fun knownMatchesKeepTheirOwnSummaryWhenMetadataIsMissingAndRetryStillWorks() = withStats {
        service.useAccount(555)
        for ((match, visiting, hero) in listOf(Triple(11L, null, 2), Triple(21L, 777L, 13))) {
            api.detailsReady = false
            val pending = withTimeout(10_000) {
                source.match(match, visiting).filterIsInstance<MatchDetailState.Pending>().first { !it.content.checking }
            }
            assertEquals(hero, pending.content.header.hero?.id)
            assertEquals(if (visiting == null) 5 else 9, pending.content.header.kills)
            assertEquals(if (visiting == null) 1800 else 1500, pending.content.header.durationS)
            assertEquals(MatchPendingPhase.Unavailable, pending.content.phase)
            api.detailsReady = true
            source.retry(visiting)
            val ready = withTimeout(10_000) { source.match(match, visiting).filterIsInstance<MatchDetailState.Ready>().first() }
            assertEquals(6, ready.content.header.kills)
        }
        assertEquals(MatchDetailState.Failed(notFound = true), withTimeout(10_000) {
            source.match(999, null).filterIsInstance<MatchDetailState.Failed>().first()
        })
    }

    @Test
    fun checkMatchAsksOnlyThatMatchNotTheWholeSession() = withStats {
        service.useAccount(555)
        val pending = Channel<MatchDetailState.Pending>(Channel.UNLIMITED)
        backgroundScope.launch { source.match(11, null).filterIsInstance<MatchDetailState.Pending>().collect { pending.send(it) } }
        suspend fun nextChecking(checking: Boolean) = withTimeout(10_000) {
            var state = pending.receive()
            while (state.content.checking != checking) state = pending.receive()
            state
        }
        nextChecking(false)
        val before = api.paths()
        val historyBefore = before.count { it.endsWith("/match-history") }
        val metaBefore = before.count { it == "/v1/matches/11/metadata" }
        api.holdDetail = CompletableDeferred()
        source.checkMatch(11, null)
        val checking = nextChecking(true)
        assertEquals(11L, checking.content.matchId)
        assertEquals(5, checking.content.header.kills, "the checking screen retains this match's summary")
        eventually { api.paths().count { it == "/v1/matches/11/metadata" }.takeIf { it == metaBefore + 1 } }
        source.checkMatch(11, null)
        settle()
        assertEquals(metaBefore + 1, api.paths().count { it == "/v1/matches/11/metadata" }, "repeated clicks join the running check")
        assertEquals(historyBefore, api.paths().count { it.endsWith("/match-history") }, "a scoped check must not re-ask the match history")
        api.holdDetail?.complete(Unit)
        val finished = nextChecking(false)
        assertEquals(MatchPendingPhase.Unavailable, finished.content.phase)
    }

    @Test
    fun aPendingMatchIsCheckedAgainInTheBackgroundOnceItsPageCloses() = withStats(FakeApi().apply { pendingMatch = true }) {
        service.useAccount(555)
        withTimeout(10_000) { source.match(14, null).filterIsInstance<MatchDetailState.Pending>().first { !it.content.checking } }
        fun asked() = api.paths().count { it == "/v1/matches/14/metadata" }
        val before = asked()
        withTimeout(15_000) { while (asked() == before) delay(100) }
    }

    @Test
    fun aMatchPageSaysWhetherStatsIsOffOrNeedsAnAccount() = withStats {
        assertEquals(MatchDetailState.NoAccount, withTimeout(10_000) { source.match(42, null).first() })
        preferences.setStatsEnabled(false)
        withTimeout(10_000) { source.match(42, null).first { it == MatchDetailState.Off } }
        assertTrue(withTimeout(10_000) { source.search("41236118").first() }.off, "a search while off says Stats is off")
    }

    @Test
    fun arrivingAtSetupReadsSteamsLoginsAgain() = withStats(identities = listOf(LocalSteamIdentity(76561199118057941, "Mira", true, 200))) {
        eventually { service.state.value.identities }
        assertEquals(1, platform.identityReads)
        preferences.setStatsAccount(555)
        eventually { service.state.value.tracked }
        preferences.setStatsAccount(null)
        eventually { platform.identityReads.takeIf { it == 2 } }
        preferences.setStatsEnabled(false)
        eventually { service.state.value.preferences?.takeIf { !it.enabled } }
        preferences.setStatsEnabled(true)
        eventually { platform.identityReads.takeIf { it == 3 } }
    }

    @Test
    fun yourOwnAccountOpenedFromSearchIsYourOwnPage() = withStats {
        val session = tracking()
        val content = withTimeout(10_000) { source.content(StatsSelection(StatsFilter(), StatsTab.Overview), 555).filterIsInstance<StatsContent.Active>().first() }
        assertTrue(content.isYou)
        assertEquals(0, platform.visitors, "no second session for yourself")
        eventually { session.sync.value.takeIf { !it.refreshing && session.data.value.fetchedAt["history"] != null } }
        val before = api.paths().count { it.endsWith("/match-history") }
        source.refresh(555)
        eventually { api.paths().count { it.endsWith("/match-history") }.takeIf { it > before } }
    }

    private fun cacheFile(matchId: Long) = "GET http://replay1.valve.net/1422450/${matchId}_99.meta.bz2 HTTP/1.1\r\n".toByteArray(Charsets.ISO_8859_1)
}
