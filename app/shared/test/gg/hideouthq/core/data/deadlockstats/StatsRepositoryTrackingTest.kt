package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import gg.hideouthq.core.network.schedulerClock
import gg.hideouthq.core.network.schedulerEngine
import gg.hideouthq.feature.stats.data.DetailQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class StatsRepositoryTrackingTest {
    private val account = SteamAccountId.ofAccountId(555)!!

    private fun TestScope.clock(aheadSeconds: Long = 0) = schedulerClock(testScheduler, 1_700_000_000_000 + aheadSeconds * 1000)

    private fun TestScope.trackingScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private fun TestScope.repository(
        client: HttpClient, storage: CacheStorage, scope: CoroutineScope, clock: Clock = clock(),
        steamTrackingFactory: (() -> SteamTrackingPort)? = { Port() },
    ) = DeadlockStatsRepository(client, storage, scope, clock = clock, steamTrackingFactory = steamTrackingFactory,
        requestGate = StatsRequestGate(clock), ioDispatcher = StandardTestDispatcher(testScheduler))

    private class Storage : CacheStorage {
        val files = ConcurrentHashMap<String, String>()
        private val json = Json { ignoreUnknownKeys = true }
        @Volatile var failSnapshot = false
        @Volatile var beforeSnapshot: suspend () -> Unit = {}
        override suspend fun read(name: String) = files[name]
        override suspend fun write(name: String, text: String) {
            if (name == "deadlock-stats-555.json") {
                beforeSnapshot()
                if (failSnapshot) error("Disk full")
            }
            files[name] = text
        }
        fun checkpoint() = files[SteamTrackingCollector.checkpointName(555)]?.let {
            json.decodeFromString<SteamTrackingCollector.Checkpoint>(it)
        }
    }
    private open class Port : SteamTrackingPort {
        override val status = MutableStateFlow(SteamTrackingStatus(SteamTrackingPhase.Connected))
        override val gcState = MutableStateFlow(SteamGcReadiness.Ready)
        override val session = MutableStateFlow<SteamGcSession?>(SteamGcSession(555, 1))
        override val events = MutableSharedFlow<SteamTrackingEvent>()
        override suspend fun start() = Unit
        override suspend fun history(session: SteamGcSession, cursor: Long) = SteamHistoryPage(
            listOf(MatchHistoryEntry(42, heroId = 2, matchDurationS = 1200, startTime = 100)), 0)
        override suspend fun metadata(session: SteamGcSession, matchId: Long) =
            ReplayCacheUrl.parseMetadataUrl("http://replay1.valve.net/1422450/${matchId}_99.meta.bz2")!!
        override fun close() = Unit
    }

    @Test fun apiFirstHistoryStillGetsSteamMetadataAndDurableScalarStats() = runTest {
        val storage = Storage()
        val client = client()
        val scope = trackingScope()
        val repo = repository(client, storage, scope)
        try {
            repo.selectAccount(account)
            assertTrue(repo.refresh())
            assertEquals(listOf(42L), repo.state.value.matchHistory.map { it.matchId })
            assertNull(repo.state.value.matchDetails[42])
            repo.configureSteamTracking(true)
            withTimeout(15_000) { repo.state.first { it.matchDetails[42]?.isComplete == true } }
            assertEquals(StatTotal(2400, 1), repo.state.value.periodSummary(DateWindow()).totals.heroDamage)
            assertEquals(2400, repo.state.value.matchSummaries[42]?.playerDamage)
            repo.close()
            scope.coroutineContext.job.cancelAndJoin()
            val reopened = repository(client, storage, backgroundScope, steamTrackingFactory = null)
            try {
                reopened.selectAccount(account)
                assertEquals(2400, reopened.state.value.matchSummaries[42]?.playerDamage)
            } finally { reopened.close() }
        } finally { repo.close(); scope.cancel(); client.close() }
    }

    @Test fun aMatchTheBridgeReadsIsSharedOnlyWhileSharingIsOn() = runTest {
        val uploads = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val client = client(salts = { uploads += it })
        val scope = trackingScope()
        val repo = repository(client, Storage(), scope)
        try {
            repo.selectAccount(account)
            repo.configureSteamTracking(true)
            withTimeout(15_000) { repo.state.first { it.matchDetails[42]?.isComplete == true } }
            assertTrue(uploads.isEmpty(), "sharing is off")

            repo.configureSteamTracking(false)
            val sharing = repository(client, Storage(), scope)
            try {
                sharing.configureSharing(since = 0)
                sharing.selectAccount(account)
                sharing.configureSteamTracking(true)
                withTimeout(15_000) { while (uploads.isEmpty()) delay(20) }
                assertEquals(listOf("""[{"match_id":42,"cluster_id":1,"metadata_salt":99}]"""), uploads.toList())
            } finally { sharing.close() }
        } finally { repo.close(); scope.cancel(); client.close() }
    }

    @Test fun turningSteamTrackingOffClosesTheBridgePortExactlyOnce() = runTest {
        val closes = java.util.concurrent.atomic.AtomicInteger()
        val client = client()
        val scope = trackingScope()
        val repo = repository(client, Storage(), scope, steamTrackingFactory = { object : Port() { override fun close() { closes.incrementAndGet() } } })
        try {
            repo.selectAccount(account)
            repo.configureSteamTracking(true)
            withTimeout(15_000) { repo.state.first { state -> state.matchHistory.any { it.matchId == 42L } } }
            repo.configureSteamTracking(false)
            withTimeout(5_000) { while (closes.get() == 0) delay(10) }
            delay(300)
            assertEquals(1, closes.get())
        } finally { repo.close(); scope.cancel(); client.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun freshMatchPublishesChartSamplesWithinOneRetryOfCdnAvailability() = runTest {
        val storage = Storage()
        storage.files[SteamTrackingCollector.checkpointName(555)] = Json.encodeToString(
            SteamTrackingCollector.Checkpoint(historyIds = setOf(42)))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = clock()
        var rows = listOf(MatchHistoryEntry(42))
        var gcLookups = 0
        val port = object : Port() {
            override suspend fun history(session: SteamGcSession, cursor: Long) = SteamHistoryPage(rows, 0)
            override suspend fun metadata(session: SteamGcSession, matchId: Long) =
                super.metadata(session, matchId).also { gcLookups++ }
        }
        var availableAt = Long.MAX_VALUE
        val downloads = mutableListOf<Long>()
        val client = mockClient { request ->
            if (request.url.host == "replay1.valve.net") {
                downloads += testScheduler.currentTime
                if (testScheduler.currentTime < availableAt) respondError(HttpStatusCode.NotFound)
                else respond(metadata(43))
            } else respond("[]")
        }
        val repo = DeadlockStatsRepository(client, storage, backgroundScope, clock = clock,
            steamTrackingFactory = { port }, requestGate = StatsRequestGate(clock), ioDispatcher = dispatcher)
        try {
            repo.selectAccount(account)
            repo.configureSteamTracking(true)
            advanceTimeBy(6000); runCurrent()
            assertEquals(0, gcLookups, "a warmed history must not redownload older matches")
            val endedAt = testScheduler.currentTime
            availableAt = endedAt + 90_000
            rows = listOf(MatchHistoryEntry(43, heroId = 2, startTime = (clock.now().epochSeconds - 1200).toInt(), matchDurationS = 1200))
            port.events.emit(SteamTrackingEvent.AccountUpdated(port.session.value!!))
            advanceTimeBy(89_000); runCurrent()
            assertTrue(repo.state.value.matchHistory.any { it.matchId == 43L })
            assertNull(repo.state.value.matchDetails[43], "history is available while the CDN still returns 404")
            advanceTimeBy(16_000); runCurrent()
            val detail = assertNotNull(repo.state.value.matchDetails[43])
            assertTrue(detail.isComplete)
            assertEquals(2400, detail.players.single().statSnapshots.single().playerDamage)
            assertEquals(2400, repo.state.value.matchSummaries[43]?.playerDamage)
            assertTrue(downloads.last() in availableAt..availableAt + 15_000,
                "publish chart data on the first 15-second retry after CDN availability: $downloads")
            assertEquals(1, gcLookups, "CDN retries reuse the saved reference")
            assertNotNull(storage.files["steam-match-555-43.json"], "the chart data must also be archived")
        } finally { repo.close(); client.close() }
    }

    @Test fun failedHistorySnapshotDoesNotAdvanceGcDiscoveryAndRecoversAfterRestart() = runTest {
        val storage = Storage().also { it.failSnapshot = true }
        val client = client()
        val scope = trackingScope()
        val repo = repository(client, storage, scope)
        try {
            repo.selectAccount(account)
            repo.configureSteamTracking(true)
            val state = withTimeout(10_000) { repo.state.first { it.steamTracking.phase != SteamTrackingPhase.Disabled } }
            assertEquals(SteamTrackingPhase.Failed, state.steamTracking.phase)
            assertEquals("Disk full", state.lastPersistenceError)
            assertEquals(emptySet(), storage.checkpoint()?.historyIds)
            assertNull(storage.files["deadlock-stats-555.json"])
        } finally { repo.close(); scope.coroutineContext.job.cancelAndJoin() }
        storage.failSnapshot = false
        val reopened = repository(client, storage, backgroundScope, clock = clock(aheadSeconds = 3600))
        try {
            reopened.selectAccount(account)
            reopened.configureSteamTracking(true)
            withTimeout(15_000) { reopened.state.first { it.matchDetails[42]?.isComplete == true } }
            assertEquals(42L, reopened.state.value.matchHistory.single().matchId)
            assertEquals(2400, reopened.state.value.matchSummaries[42]?.playerDamage)
            assertEquals(setOf(42L), storage.checkpoint()?.historyIds)
        } finally { reopened.close(); client.close() }
    }

    @Test fun failedMetadataSnapshotRemainsPendingUntilItCanBeSaved() = runTest {
        val storage = Storage()
        val client = client(beforeDownload = { storage.failSnapshot = true })
        val scope = trackingScope()
        val repo = repository(client, storage, scope)
        try {
            repo.selectAccount(account)
            repo.configureSteamTracking(true)
            withTimeout(15_000) { repo.state.first { it.lastPersistenceError != null } }
            val settled = withTimeout(10_000) { repo.state.first {
                it.steamTracking.phase == SteamTrackingPhase.Failed || it.steamTracking.phase == SteamTrackingPhase.Connected
            } }
            assertEquals(SteamTrackingPhase.Failed, settled.steamTracking.phase)
            assertEquals(listOf(42L), storage.checkpoint()?.pending)
            assertEquals("http://replay1.valve.net/1422450/42_99.meta.bz2", storage.checkpoint()?.references?.get(42))
        } finally { repo.close(); scope.coroutineContext.job.cancelAndJoin(); client.close() }
        storage.failSnapshot = false
        val recoveredClient = client()
        val reopened = repository(recoveredClient, storage, backgroundScope, clock = clock(aheadSeconds = 3600))
        try {
            reopened.selectAccount(account)
            reopened.configureSteamTracking(true)
            withTimeout(15_000) { reopened.state.first { it.matchSummaries[42]?.playerDamage == 2400 } }
            assertEquals(StatTotal(2400, 1), reopened.state.value.periodSummary(DateWindow()).totals.heroDamage)
            withTimeout(10_000) { reopened.state.first { it.steamTracking.phase == SteamTrackingPhase.Connected } }
            assertEquals(emptyList(), storage.checkpoint()?.pending)
            assertEquals(2400, DeadlockStatsSnapshotStore(storage).load(account)?.matchSummaries?.single()?.playerDamage)
        } finally { reopened.close(); recoveredClient.close() }
    }

    @Test fun closingDuringMetadataSnapshotKeepsTheReferenceForRestart() = runTest {
        val storage = Storage()
        val writing = CompletableDeferred<Unit>()
        val client = client(beforeDownload = {
            storage.beforeSnapshot = { writing.complete(Unit); awaitCancellation() }
        })
        val scope = trackingScope()
        val repo = repository(client, storage, scope)
        try {
            repo.selectAccount(account)
            repo.configureSteamTracking(true)
            withTimeout(15_000) { writing.await() }
            assertNull(repo.state.value.matchDetails[42])
        } finally { repo.close(); scope.coroutineContext.job.cancelAndJoin(); client.close() }
        assertEquals(listOf(42L), storage.checkpoint()?.pending)
        assertEquals("http://replay1.valve.net/1422450/42_99.meta.bz2", storage.checkpoint()?.references?.get(42))
        assertEquals(emptyList(), DeadlockStatsSnapshotStore(storage).load(account)?.matchSummaries, "The interrupted ingestion is not acknowledged")
        storage.beforeSnapshot = {}
        val recoveredClient = client()
        val reopened = repository(recoveredClient, storage, backgroundScope)
        try {
            reopened.selectAccount(account)
            reopened.configureSteamTracking(true)
            withTimeout(15_000) { reopened.state.first { it.matchSummaries[42]?.playerDamage == 2400 } }
            withTimeout(10_000) { reopened.state.first { it.steamTracking.phase == SteamTrackingPhase.Connected } }
            assertEquals(emptyList(), storage.checkpoint()?.pending)
            assertEquals(2400, DeadlockStatsSnapshotStore(storage).load(account)?.matchSummaries?.single()?.playerDamage)
        } finally { reopened.close(); recoveredClient.close() }
    }

    @Test fun automaticFetchesDoNotPrioritizeOrResetSteamsOwnBackoff() = runTest {
        val storage = Storage()
        storage.files[SteamTrackingCollector.checkpointName(555)] =
            Json { ignoreUnknownKeys = true }.encodeToString(SteamTrackingCollector.Checkpoint(historyIds = setOf(42)))
        val client = mockClient { request ->
            when {
                request.url.encodedPath.endsWith("/42/metadata") -> respondError(HttpStatusCode.ServiceUnavailable)
                request.url.encodedPath.endsWith("/match-history") -> respond("[]")
                else -> respond("[]")
            }
        }
        val requested = ConcurrentHashMap.newKeySet<Long>()
        val port = object : Port() {
            override suspend fun metadata(session: SteamGcSession, matchId: Long) = super.metadata(session, matchId).also { requested += matchId }
        }
        val scope = trackingScope()
        val clock = clock()
        val repo = repository(client, storage, scope, clock = clock, steamTrackingFactory = { port })
        try {
            repo.selectAccount(account)
            repo.configureSteamTracking(true)
            assertTrue(runCatching { DetailQuery(42).run(repo, account, clock.now().epochSeconds, automatic = true) }.isFailure,
                "the ordinary deadlock-api is down for this match")
            delay(3500)
            assertFalse(42L in requested, "an automatic DetailQuery must not move Steam's own queue or clear its backoff")
            assertTrue(runCatching { DetailQuery(42).run(repo, account, clock.now().epochSeconds, automatic = false) }.isFailure,
                "an explicit DetailQuery still prioritizes Steam")
            withTimeout(15_000) { while (42L !in requested) delay(20) }
        } finally { repo.close(); scope.cancel(); client.close() }
    }

    private fun TestScope.mockClient(handler: MockRequestHandler) =
        HttpClient(schedulerEngine(testScheduler, handler))

    private fun TestScope.client(beforeDownload: () -> Unit = {}, salts: (String) -> Unit = {}) = mockClient { request ->
        when {
            request.url.host == "replay1.valve.net" -> { beforeDownload(); respond(metadata()) }
            request.url.encodedPath == "/v1/matches/salts" -> { salts((request.body as io.ktor.http.content.TextContent).text); respond("") }
            request.url.encodedPath.endsWith("/match-history") -> respond("""[{"match_id":42,"hero_id":2,"start_time":100,"match_duration_s":1200}]""")
            request.url.encodedPath.endsWith("/rank") -> respond("""{"badge":45,"rank":4,"subrank":5}""")
            else -> respond("[]")
        }
    }

    private fun metadata(match: Long = 42) = metadataFile(CMsgMatchMetaDataContents.MatchInfo(
        match_id = match, duration_s = 1200, winning_team = 0, game_mode = 1, match_mode = 4,
        players = listOf(CMsgMatchMetaDataContents.Players(account_id = 555, hero_id = 2, team = 0,
            stats = listOf(CMsgMatchMetaDataContents.PlayerStats(time_stamp_s = 1200, player_damage = 2400, player_healing = 0)))),
    ))
}
