package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.fake.MemoryStorage as Storage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SteamTrackingRegressionTest {
    private class Port : SteamTrackingPort {
        override val status = MutableStateFlow(SteamTrackingStatus(SteamTrackingPhase.Connected))
        override val gcState = MutableStateFlow(SteamGcReadiness.Ready)
        override val session = MutableStateFlow<SteamGcSession?>(SteamGcSession(555, 1))
        override val events = MutableSharedFlow<SteamTrackingEvent>()
        val requests = mutableListOf<String>()
        var rows = listOf(MatchHistoryEntry(123))
        var pages = 1L
        var invalid: Long? = null
        var inFlightOnce = false
        var interruptedOnce = false
        override suspend fun start() {}
        override suspend fun history(session: SteamGcSession, cursor: Long): SteamHistoryPage {
            requests += "history:$cursor"
            if (interruptedOnce) { interruptedOnce = false; throw SteamGcInterrupted("game exited") }
            return SteamHistoryPage(rows, if (cursor + 1 < pages) cursor + 1 else 0)
        }
        override suspend fun metadata(session: SteamGcSession, matchId: Long): ReplayMetadataRef {
            requests += "metadata:$matchId"
            if (matchId == invalid) return SteamGcCodec.metadata(byteArrayOf(8, 6), matchId)
            if (inFlightOnce) { inFlightOnce = false; return SteamGcCodec.metadata(byteArrayOf(8, 7), matchId) }
            return ReplayCacheUrl.parseMetadataUrl("http://replay1.valve.net/1422450/${matchId}_42.meta.bz2")!!
        }
        override fun close() {}
    }

    @Test fun waitingGcPreservesCheckpointsAndStillDownloadsSavedReferences() = runTest {
        val storage = storageWith(SteamTrackingCollector.Checkpoint(
            historyCursor = 9, pending = listOf(123, 124), references = mapOf(124L to "http://replay1.valve.net/1422450/124_42.meta.bz2")))
        val port = Port().also { it.gcState.value = SteamGcReadiness.Waiting }
        val downloaded = mutableSetOf<Long>()
        val phases = mutableListOf<SteamTrackingPhase>()
        val job = backgroundScope.launch { steamCollector(port, storage, downloaded, publish = { phases += it.phase }).run() }
        advanceTimeBy(100); runCurrent()
        assertEquals(setOf(124L), downloaded)
        assertTrue(port.requests.isEmpty())
        assertEquals(9L, storage.checkpoint().historyCursor)
        assertEquals(listOf(123L), storage.checkpoint().pending)
        assertTrue(phases.all { it == SteamTrackingPhase.WaitingForGc })
        port.gcState.value = SteamGcReadiness.Ready
        advanceTimeBy(100); runCurrent()
        assertTrue("history:0" in port.requests)
        job.cancelAndJoin()
    }

    @Test fun interruptionDoesNotSpendRetryBudgetOrDelayTheNextEligibleContext() = runTest {
        val storage = Storage()
        val port = Port().also { it.interruptedOnce = true; it.rows = emptyList() }
        val job = backgroundScope.launch { steamCollector(port, storage, spacingMillis = 1000, hasMetadata = { false }, ingest = {}).run() }
        advanceTimeBy(500); runCurrent()
        assertTrue(storage.checkpoint().cooldowns.isEmpty())
        assertTrue(storage.checkpoint().requestFailures.isEmpty())
        advanceTimeBy(1000); runCurrent()
        assertEquals(2, port.requests.count { it == "history:0" })
        job.cancelAndJoin()
    }

    @Test fun coldBackfillIsPacedWhileFreshAndExplicitDetailsTakePriority() = runTest {
        val storage = Storage()
        val oldRows = (1L..50L).map { MatchHistoryEntry(it) }
        val port = Port().also { it.rows = oldRows }
        val downloaded = mutableSetOf<Long>()
        val phases = mutableListOf<SteamTrackingPhase>()
        val collector = steamCollector(port, storage, downloaded, spacingMillis = 1000,
            backgroundMetadataMillis = 120_000, publish = { phases += it.phase })
        val job = backgroundScope.launch { collector.run() }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, port.requests.count { it.startsWith("metadata:") },
            "unseen old rows on the newest page must not all become urgent")
        assertFalse(SteamTrackingPhase.CoolingDown in phases, "local pacing is backfill, not an upstream throttle")

        port.rows = listOf(MatchHistoryEntry(999, startTime = 100, matchDurationS = 10)) + oldRows
        port.events.emit(SteamTrackingEvent.AccountUpdated(port.session.value!!))
        advanceTimeBy(3000); runCurrent()
        assertTrue(999L in downloaded, "the new match must not wait for background pacing")
        collector.detail(777)
        advanceTimeBy(2000); runCurrent()
        assertTrue(777L in downloaded, "an explicit detail must not wait for background pacing")
        advanceTimeBy(100_000); runCurrent()
        assertEquals(1, port.requests.count { it.startsWith("metadata:") && it !in setOf("metadata:999", "metadata:777") })
        job.cancelAndJoin()

        val resumed = backgroundScope.launch {
            steamCollector(port, storage, downloaded, spacingMillis = 1000, backgroundMetadataMillis = 120_000).run()
        }
        advanceTimeBy(5000); runCurrent()
        assertEquals(3, port.requests.count { it.startsWith("metadata:") }, "restart must preserve background pacing")
        advanceTimeBy(20_000); runCurrent()
        assertEquals(4, port.requests.count { it.startsWith("metadata:") }, "old history still makes progress")
        resumed.cancelAndJoin()
    }

    @Test fun restoredFreshPriorityWaitsForRealGcCooldownButNotBackgroundPacing() = runTest {
        val storage = storageWith(SteamTrackingCollector.Checkpoint(
            pending = listOf(123, 999), historyIds = setOf(123, 999), freshUntil = mapOf(999L to 400),
            cooldowns = mapOf("metadata" to 160, "background metadata" to 700)))
        val port = Port().also { it.rows = emptyList() }
        val downloaded = mutableSetOf<Long>()
        val job = backgroundScope.launch {
            steamCollector(port, storage, downloaded, spacingMillis = 1000, backgroundMetadataMillis = 120_000).run()
        }
        advanceTimeBy(59_000); runCurrent()
        assertTrue(port.requests.none { it.startsWith("metadata:") }, "fresh priority must honor an actual GC throttle")
        advanceTimeBy(3000); runCurrent()
        assertEquals(setOf(999L), downloaded, "restored fresh work must precede an older pending row")
        assertEquals(listOf("metadata:999"), port.requests.filter { it.startsWith("metadata:") })
        job.cancelAndJoin()
    }

    private suspend fun storageWith(checkpoint: SteamTrackingCollector.Checkpoint) =
        Storage().also { it.write(SteamTrackingCollector.checkpointName(555), Json.encodeToString(checkpoint)) }

    private suspend fun cdnFailure(status: HttpStatusCode): ReplayDownloadException =
        HttpClient(MockEngine { respondError(status) }).use { client ->
            val downloader = ReplayMetadataDownloader(client)
            try { assertFailsWith { downloader.download(ReplayMetadataRef(1, 1, 2, "http://replay1.valve.net/1422450/1_2.meta.bz2")) } }
            finally { downloader.close() }
        }

    @Test fun savedCdnReferenceRunsWhileAnotherMatchNeedsThrottledGc() = runTest {
        val storage = storageWith(SteamTrackingCollector.Checkpoint(
            pending = listOf(123, 124), references = mapOf(124L to "http://replay1.valve.net/1422450/124_42.meta.bz2"),
            cooldowns = mapOf("metadata" to 1000)))
        val port = Port().also { it.rows = emptyList() }
        val downloaded = mutableSetOf<Long>()
        val job = backgroundScope.launch { steamCollector(port, storage, downloaded).run() }
        advanceTimeBy(200); runCurrent()
        assertEquals(setOf(124L), downloaded)
        assertTrue(port.requests.none { it.startsWith("metadata:") })
        assertTrue("history:0" in port.requests)
        job.cancelAndJoin()
    }

    @Test fun savedReferencesDownloadOfflineAndWrongAccountWithoutLosingQueuedDetails() = runTest {
        for (session in listOf(null, SteamGcSession(666, 1))) {
            val storage = storageWith(SteamTrackingCollector.Checkpoint(
                pending = listOf(123, 124), references = mapOf(124L to "http://replay1.valve.net/1422450/124_42.meta.bz2")))
            val port = Port().also { it.session.value = session }
            val downloaded = mutableSetOf<Long>()
            val collector = steamCollector(port, storage, downloaded)
            collector.detail(125)
            val job = backgroundScope.launch { collector.run() }
            advanceTimeBy(100); runCurrent()
            assertEquals(setOf(124L), downloaded)
            assertEquals(emptyList(), port.requests)
            assertEquals(listOf(125L, 123L), storage.checkpoint().pending)
            job.cancelAndJoin()
        }
    }

    @Test fun inFlightMatchRetriesWithoutDelayingOtherMetadata() = runTest {
        val port = Port().also { it.inFlightOnce = true; it.rows = listOf(MatchHistoryEntry(123), MatchHistoryEntry(124)) }
        val downloaded = mutableSetOf<Long>()
        val job = backgroundScope.launch { steamCollector(port, downloaded = downloaded, spacingMillis = 1000).run() }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(setOf(124L), downloaded)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(setOf(123L, 124L), downloaded)
        assertEquals(2, port.requests.count { it == "metadata:123" })
        assertEquals(1, port.requests.count { it == "metadata:124" })
        job.cancelAndJoin()
    }

    @Test fun currentSessionGapRefreshesHistoryButStaleGenerationDoesNot() = runTest {
        val port = Port().also { it.rows = emptyList() }
        val job = backgroundScope.launch { steamCollector(port, hasMetadata = { false }, ingest = {}).run() }
        advanceTimeBy(50); runCurrent()
        assertEquals(listOf("history:0"), port.requests)
        port.events.emit(SteamTrackingEvent.Gap(SteamGcSession(555, 0), 1))
        advanceTimeBy(50); runCurrent()
        assertEquals(listOf("history:0"), port.requests)
        port.events.emit(SteamTrackingEvent.Gap(port.session.value!!, 1))
        advanceTimeBy(50); runCurrent()
        assertEquals(listOf("history:0", "history:0"), port.requests)
        job.cancelAndJoin()
    }

    @Test fun explicitDetailPreemptsBackfillPages() = runTest {
        val port = Port().also { it.pages = 20 }
        val downloaded = mutableSetOf<Long>()
        val collector = steamCollector(port, downloaded = downloaded)
        val job = backgroundScope.launch { collector.run() }
        collector.backfill()
        advanceTimeBy(70); runCurrent()
        collector.detail(777)
        advanceTimeBy(30); runCurrent()
        assertTrue(777L in downloaded, port.requests.toString())
        assertTrue("history:19" !in port.requests)
        job.cancelAndJoin()
    }

    @Test fun invalidMatchStopsAutomaticRetriesWithoutBlockingValidMatches() = runTest {
        val port = Port().also { it.invalid = 123; it.rows = listOf(MatchHistoryEntry(123), MatchHistoryEntry(124)) }
        val downloaded = mutableSetOf<Long>()
        val collector = steamCollector(port, downloaded = downloaded, spacingMillis = 1000)
        val job = backgroundScope.launch { collector.run() }
        collector.backfill()
        advanceTimeBy(300_000); runCurrent()
        assertEquals(setOf(124L), downloaded)
        assertEquals(1, port.requests.count { it == "metadata:123" })
        collector.backfill()
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, port.requests.count { it == "metadata:123" })
        collector.detail(123)
        advanceTimeBy(5000); runCurrent()
        assertEquals(2, port.requests.count { it == "metadata:123" })
        job.cancelAndJoin()
    }

    @Test fun exhaustedCdn404RetiresTheMatchWhileEquivalent5xxStaysPending() = runTest {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError)) {
            val failure = cdnFailure(status)
            val storage = Storage()
            val port = Port().also { it.rows = emptyList() }
            val collector = steamCollector(port, storage, spacingMillis = 1000, hasMetadata = { false }) { throw failure }
            val job = backgroundScope.launch { collector.run() }
            collector.detail(999)
            advanceTimeBy(100_000); runCurrent()
            assertTrue(999L in storage.checkpoint().pending, "status=$status should still be pending during early retries")
            advanceTimeBy(7_400_000); runCurrent()
            val saved = storage.checkpoint()
            val retired = status == HttpStatusCode.NotFound
            assertEquals(!retired, 999L in saved.pending, "status=$status pending state after 8 attempts")
            assertEquals(retired, 999L in saved.historyIds, "status=$status historyIds state after 8 attempts")
            assertEquals(1, port.requests.count { it == "metadata:999" }, "status=$status GC lookups (cached reference is reused across retries)")
            job.cancelAndJoin()
        }
    }

    @Test fun aFreshMatchsCdn404sAreRetriedQuietlyWithoutSpendingTheRetirementBudget() = runTest {
        val notFound = cdnFailure(HttpStatusCode.NotFound)
        val storage = Storage()
        val port = Port().also { it.rows = listOf(MatchHistoryEntry(999, startTime = 40, matchDurationS = 20)) }
        var attempts = 0
        val phases = mutableSetOf<SteamTrackingPhase>()
        val collector = steamCollector(port, storage, spacingMillis = 1000, hasMetadata = { false }, publish = { phases += it.phase }) {
            attempts++; throw notFound
        }
        val job = backgroundScope.launch { collector.run() }
        advanceTimeBy(200_000); runCurrent()
        val saved = storage.checkpoint()
        assertTrue(attempts > 8, "a fresh match is asked for again every 15 s, $attempts times")
        assertTrue(999L in saved.pending, "and survives more early 404s than the retirement budget")
        assertEquals(0, saved.metadataFailures[999] ?: 0)
        assertFalse(SteamTrackingPhase.Failed in phases, "metadata the CDN has not published yet is not a failure")
        job.cancelAndJoin()
    }

    @Test fun retiredMatchStaysRetiredAcrossRestartAndCanStillBeRetriedManually() = runTest {
        val notFound = cdnFailure(HttpStatusCode.NotFound)
        val storage = Storage()
        val port = Port().also { it.rows = listOf(MatchHistoryEntry(999)) }
        val downloaded = mutableSetOf<Long>()
        val summaries = mutableSetOf<Long>()
        var reported = SteamTrackingStatus()
        var failing = true
        fun newCollector() = steamCollector(port, storage, downloaded, 1000, history = { summaries += it.map { row -> row.matchId } },
            publish = { reported = it }) { if (failing) throw notFound else downloaded += it.matchId }

        var collector = newCollector()
        var job = backgroundScope.launch { collector.run() }
        advanceTimeBy(7_500_000); runCurrent()
        assertTrue(999L !in storage.checkpoint().pending)
        assertEquals(setOf(999L), summaries)
        assertEquals(0, reported.pendingMetadata)
        job.cancelAndJoin()

        collector = newCollector()
        job = backgroundScope.launch { collector.run() }
        collector.backfill()
        advanceTimeBy(5000); runCurrent()
        assertTrue(999L !in downloaded)
        assertEquals(1, port.requests.count { it == "metadata:999" })

        failing = false
        collector.detail(999)
        advanceTimeBy(2000); runCurrent()
        assertTrue(999L in downloaded)
        assertEquals(2, port.requests.count { it == "metadata:999" })
        job.cancelAndJoin()
    }
}
