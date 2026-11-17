package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.fake.MemoryStorage as Storage
import gg.hideouthq.core.data.deadlock.proto.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SteamTrackingTest {
    private class Port : SteamTrackingPort {
        override val status = MutableStateFlow(SteamTrackingStatus(SteamTrackingPhase.Connected))
        override val gcState = MutableStateFlow(SteamGcReadiness.Ready)
        override val session = MutableStateFlow<SteamGcSession?>(SteamGcSession(555, 1))
        override val events = MutableSharedFlow<SteamTrackingEvent>(extraBufferCapacity = 10)
        val cursors = mutableListOf<Long>()
        var metadataCalls = 0
        var limited = false
        var repeated = false
        var newest = 123L
        override suspend fun start() {}
        override suspend fun history(session: SteamGcSession, cursor: Long): SteamHistoryPage {
            cursors += cursor
            return SteamHistoryPage(listOf(MatchHistoryEntry(if (cursor == 0L) newest else 124)), if (cursor == 0L || repeated) 9 else 0)
        }
        override suspend fun metadata(session: SteamGcSession, matchId: Long): ReplayMetadataRef {
            metadataCalls++
            if (limited) throw SteamGcRateLimited("metadata")
            return ReplayCacheUrl.parseMetadataUrl("http://replay1.valve.net/1422450/${matchId}_42.meta.bz2")!!
        }
        override fun close() {}
    }

    @Test fun protobufFramingPreservesUnsignedJobBitsAndChecksBounds() {
        val job = 0xfedcba9876543210uL.toLong()
        val header = SteamGcHeader(job_id_target = job).encode()
        val body = byteArrayOf(8, 1)
        val frame = ByteBuffer.allocate(8 + header.size + body.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(9113 or Int.MIN_VALUE).putInt(header.size).put(header).put(body).array()
        val decoded = SteamGcCodec.decode(frame)
        assertEquals(job, decoded.job); assertEquals(9113, decoded.type); assertContentEquals(body, decoded.body)
        assertFailsWith<IllegalArgumentException> { SteamGcCodec.decode(frame.copyOf(7)) }
        ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).putInt(4, Int.MAX_VALUE)
        assertFailsWith<IllegalArgumentException> { SteamGcCodec.decode(frame) }
    }

    @Test fun rateLimitsAndMissingResultsAreNotSuccessfulEmptyHistory() {
        assertFailsWith<SteamGcRateLimited> { SteamGcCodec.history(byteArrayOf(8, 5)) }
        assertFailsWith<IllegalStateException> { SteamGcCodec.history(byteArrayOf()) }
    }

    @Test fun metadataNamesTheReplayFileOnTheGroupsHostWithAnUnsignedSalt() {
        val response = CMsgClientToGCGetMatchMetaDataResponse(
            result = CMsgClientToGCGetMatchMetaDataResponse.EResult.k_eResult_Success, metadata_salt = -1, replay_group_id = 404,
        )
        assertEquals(
            ReplayMetadataRef(37_959_196, 404, 4_294_967_295, "http://replay404.valve.net/1422450/37959196_4294967295.meta.bz2"),
            SteamGcCodec.metadata(response.encode(), 37_959_196),
        )
    }

    @Test fun backfillSurvivesOfflineRefreshAndResumesAtPersistedCursor() = runTest {
        val storage = Storage(); val port = Port()
        port.session.value = null
        val matches = mutableSetOf<Long>(); val downloaded = mutableSetOf<Long>()
        val collector = steamCollector(port, storage, downloaded, history = { matches += it.map { row -> row.matchId } })
        val job = backgroundScope.launch { collector.run() }
        collector.backfill(); collector.refresh()
        advanceTimeBy(100); runCurrent()
        assertTrue("\"historyCursor\":0" in assertNotNull(storage.read(SteamTrackingCollector.checkpointName(555))))
        assertTrue(port.cursors.isEmpty())
        port.session.value = SteamGcSession(555, 2)
        advanceTimeBy(500); runCurrent()
        assertTrue(9L in port.cursors)
        assertEquals(setOf(123L, 124L), matches)
        assertEquals(matches, downloaded)
        val saved = storage.checkpoint()
        assertNull(saved.historyCursor); assertEquals(setOf(123L, 124L), saved.historyIds)
        job.cancelAndJoin()
    }

    @Test fun explicitDetailRefreshSurvivesRestartWithExistingMetadata() = runTest {
        val storage = Storage()
        val downloaded = mutableListOf<Long>()
        fun newCollector(port: Port) = steamCollector(port, storage, hasMetadata = { true }) { downloaded += it.matchId }
        val offline = Port().also { it.session.value = null }
        val first = newCollector(offline)
        val job = backgroundScope.launch { first.run() }
        first.detail(42)
        advanceTimeBy(100); runCurrent()
        assertTrue(downloaded.isEmpty())
        job.cancelAndJoin()
        val resumed = backgroundScope.launch { newCollector(Port()).run() }
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(42L), downloaded)
        resumed.cancelAndJoin()
    }

    @Test fun upstreamThrottleSurvivesRestartAndClearThenAllowsRetry() = runTest {
        val storage = Storage(); val port = Port().also { it.limited = true }
        fun newCollector(p: Port) = steamCollector(p, storage, hasMetadata = { false }, ingest = {})
        val job = backgroundScope.launch { newCollector(port).run() }
        advanceTimeBy(100); runCurrent()
        assertEquals(1, port.metadataCalls)
        job.cancelAndJoin()
        val second = Port()
        val resumed = backgroundScope.launch { newCollector(second).run() }
        advanceTimeBy(100); runCurrent()
        assertEquals(0, second.metadataCalls)
        resumed.cancelAndJoin()
        SteamTrackingCollector.clear(storage, 555)
        assertTrue(storage.checkpoint().cooldowns.getValue("metadata") > clock().now().epochSeconds)
        val retrying = backgroundScope.launch { newCollector(second).run() }
        advanceTimeBy(31_000); runCurrent()
        assertEquals(1, second.metadataCalls)
        retrying.cancelAndJoin()
    }

    @Test fun repeatedCursorStopsTheBackfillAndWrongAccountSendsNothing() = runTest {
        val port = Port().also { it.repeated = true; it.session.value = SteamGcSession(666, 1) }
        val storage = Storage()
        val collector = steamCollector(port, storage, hasMetadata = { true }, ingest = {})
        val job = backgroundScope.launch { collector.run() }; collector.backfill()
        advanceTimeBy(100); runCurrent(); assertTrue(port.cursors.isEmpty())
        port.session.value = SteamGcSession(555, 2)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, port.cursors.count { it == 9L })
        val saved = storage.checkpoint()
        assertNull(saved.historyCursor); assertEquals(setOf(0L), saved.seenCursors)
        job.cancelAndJoin()
    }

    @Test fun accountUpdateFetchesTheNewMatchWithoutWaitingForPeriodicRefresh() = runTest {
        val port = Port(); val history = mutableSetOf<Long>(); val downloaded = mutableSetOf<Long>()
        val collector = steamCollector(port, downloaded = downloaded, history = { history += it.map { row -> row.matchId } })
        val job = backgroundScope.launch { collector.run() }
        advanceTimeBy(200); runCurrent()
        assertEquals(setOf(123L), downloaded)
        port.newest = 125
        port.events.emit(SteamTrackingEvent.AccountUpdated(port.session.value!!))
        advanceTimeBy(200); runCurrent()
        assertEquals(setOf(123L, 125L), history)
        assertEquals(history, downloaded)
        assertEquals(2, port.metadataCalls)
        job.cancelAndJoin()
    }

    @Test fun delayedCdnMetadataRetriesTheSavedReferenceWithoutSpendingAnotherGcRequest() = runTest {
        val port = Port(); val downloaded = mutableSetOf<Long>()
        var downloads = 0
        val collector = steamCollector(port, downloaded = downloaded, spacingMillis = 1000) {
            if (++downloads == 1) throw ReplayDownloadException("HTTP 404: metadata not published yet")
            downloaded += it.matchId
        }
        val job = backgroundScope.launch { collector.run() }
        advanceTimeBy(45_000); runCurrent()
        assertEquals(setOf(123L), downloaded)
        assertEquals(2, downloads)
        assertEquals(1, port.metadataCalls)
        job.cancelAndJoin()
    }
}
