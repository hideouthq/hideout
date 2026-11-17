package gg.hideouthq.core.analytics

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class UsageAnalyticsTest {
    @Test fun nothingLeavesBeforeDisclosureAndAChoiceSurvivesRestart() = runTest {
        val storage = MemoryStorage()
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(storage, UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.begin(UsageOperation.ModInstall).finish(UsageOutcome.Completed)
        analytics.load()
        runCurrent()
        assertEquals(AnalyticsChoice.NotDisclosed, analytics.choice.value)
        assertTrue(sent.isEmpty())
        analytics.choose(false)
        val reopened = UsageAnalytics(storage, UsageTransport { _, event -> sent += event }, backgroundScope)
        reopened.load()
        reopened.begin(UsageOperation.ModInstall).finish(UsageOutcome.Completed)
        runCurrent()
        assertEquals(AnalyticsChoice.Disabled, reopened.choice.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun revocationCancelsDeliveryDropsTheQueueAndInvalidatesOldAttempts() = runTest {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val sent = mutableListOf<UsageEvent>()
        var block = true
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event ->
            if (block) { entered.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } }
            else sent += event
        }, backgroundScope)
        analytics.load()
        analytics.choose(true)
        val old = analytics.begin(UsageOperation.ModInstall)
        repeat(40) { analytics.begin(UsageOperation.CustomGameJoin) }
        runCurrent()
        assertTrue(entered.isCompleted)
        analytics.choose(false)
        runCurrent()
        assertTrue(stopped.isCompleted)
        block = false
        analytics.choose(true)
        old.finish(UsageOutcome.Completed)
        analytics.begin(UsageOperation.PreviewLoad).finish(UsageOutcome.Completed)
        runCurrent()
        assertFalse(sent.any { it is UsageEvent.Result && it.operation == UsageOperation.ModInstall })
        assertFalse(sent.any { it is UsageEvent.Started })
        assertTrue(sent.any { it is UsageEvent.Result && it.operation == UsageOperation.PreviewLoad })
    }

    @Test fun aRefusedWriteCannotEnableCollection() = runTest {
        val storage = object : CacheStorage {
            override suspend fun read(name: String): String? = null
            override suspend fun write(name: String, text: String) { error("disk full") }
        }
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(storage, UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.load()
        assertFailsWith<IllegalStateException> { analytics.choose(true) }
        assertFailsWith<IllegalStateException> { analytics.choose(false) }
        analytics.begin(UsageOperation.ModInstall).finish(UsageOutcome.Completed)
        runCurrent()
        assertTrue(sent.isEmpty())
        assertEquals(AnalyticsChoice.NotDisclosed, analytics.choice.value)
    }

    @Test fun disablingWhileEnableIsSavingCannotStartDelivery() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val storage = object : CacheStorage {
            var first = true
            override suspend fun read(name: String): String? = null
            override suspend fun write(name: String, text: String) {
                if (first) { first = false; entered.complete(Unit); release.await() }
            }
        }
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(storage, UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.load()
        val enabling = async { analytics.choose(true) }
        entered.await()
        val disabling = async { analytics.choose(false) }
        runCurrent()
        release.complete(Unit)
        enabling.await(); disabling.await()
        analytics.begin(UsageOperation.ModInstall).finish(UsageOutcome.Completed)
        runCurrent()
        assertEquals(AnalyticsChoice.Disabled, analytics.choice.value)
        assertTrue(sent.isEmpty())
    }

    @Test fun failuresDoNotStopLaterEventsAndActiveDayIsDeduplicatedAcrossRestart() = runTest {
        val storage = MemoryStorage()
        val sent = mutableListOf<Pair<String, UsageEvent>>()
        var fail = true
        val transport = UsageTransport { id, event -> if (fail) { fail = false; error("offline") } else sent += id to event }
        val analytics = UsageAnalytics(storage, transport, backgroundScope, newId = { "random-installation" })
        analytics.load(); analytics.choose(true)
        runCurrent()
        analytics.begin(UsageOperation.PreviewLoad).finish(UsageOutcome.Completed)
        analytics.recordActiveDay()
        runCurrent()
        analytics.close()
        val reopened = UsageAnalytics(storage, transport, backgroundScope, newId = { error("must reuse") })
        reopened.load(); reopened.recordActiveDay()
        reopened.begin(UsageOperation.PreviewLoad).finish(UsageOutcome.Failed)
        runCurrent()
        assertEquals(2, sent.size)
        assertTrue(sent.all { it.first == "random-installation" && it.second is UsageEvent.Result })
    }

    @Test fun publicModeIdentityCannotComeFromAFolderWithTheSameName() {
        val listed = FakeCustomGamesFeed.SampleMode
        val folder = FakeCustomGamesFeed.SampleFolder
        assertEquals(listed.id, folder.id)
        assertEquals(listed.listing.name, folder.listing.name)
        assertNull(PublicUsageMode.from(folder))
        val mode = assertNotNull(PublicUsageMode.from(listed))
        assertEquals(listed.id.value, mode.id)
        assertEquals(listed.listing.name, mode.name)
    }

    @Test fun connectedMinutesAreBatchedAndPausedTimeDoesNotCount() = runTest {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope, time = testScheduler.timeSource)
        analytics.load(); analytics.choose(true)
        val play = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        runCurrent()
        repeat(3) { play.setPlaying(true) }
        advanceTimeBy(11.minutes + 40.seconds)
        runCurrent()
        assertEquals(listOf(5, 5), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
        play.setPlaying(false)
        runCurrent()
        assertEquals(listOf(5, 5, 1), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
        advanceTimeBy(7.minutes)
        play.setPlaying(true)
        runCurrent()
        advanceTimeBy(2.minutes + 30.seconds)
        play.close()
        runCurrent()
        assertEquals(listOf(5, 5, 1, 2), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
        assertEquals(1, sent.filterIsInstance<UsageEvent.PlayStarted>().size)
        play.close(); play.setPlaying(true)
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(13, sent.filterIsInstance<UsageEvent.PlayTime>().sumOf { it.minutes.value })
    }

    @Test fun pausingAtTheFiveMinuteBoundaryCountsThatChunkOnlyOnce() = runTest {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope, time = testScheduler.timeSource)
        analytics.load(); analytics.choose(true)
        val play = analytics.beginPlay(PublicMode, UsagePlayRole.Guest)
        runCurrent()
        advanceTimeBy(5.minutes)
        play.setPlaying(false)
        runCurrent()
        assertEquals(listOf(5), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
        play.setPlaying(true)
        runCurrent()
        advanceTimeBy(5.minutes); runCurrent()
        play.setPlaying(false)
        runCurrent()
        assertEquals(listOf(5, 5), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
        play.close()
    }

    @Test fun aSubMinutePlayCountsTheSessionWithoutInventingMinutes() = runTest {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope, time = testScheduler.timeSource)
        analytics.load(); analytics.choose(true)
        val play = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        runCurrent()
        advanceTimeBy(59.seconds)
        play.close(); runCurrent()
        assertEquals(1, sent.filterIsInstance<UsageEvent.PlayStarted>().size)
        assertTrue(sent.filterIsInstance<UsageEvent.PlayTime>().isEmpty())
    }

    @Test fun revokingPlayDropsQueuedTimeAndCannotReviveTheOldSession() = runTest {
        var block = true
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event ->
            if (block) awaitCancellation() else sent += event
        }, backgroundScope, time = testScheduler.timeSource)
        analytics.load(); analytics.choose(true)
        val old = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        runCurrent(); advanceTimeBy(7.minutes); runCurrent()
        analytics.choose(false)
        runCurrent()
        block = false
        analytics.choose(true)
        old.setPlaying(false); old.setPlaying(true); old.close()
        advanceTimeBy(7.minutes); runCurrent()
        assertTrue(sent.none { it is UsageEvent.PlayStarted || it is UsageEvent.PlayTime })
        val fresh = analytics.beginPlay(PublicMode, UsagePlayRole.Guest)
        runCurrent(); advanceTimeBy(2.minutes)
        fresh.close(); runCurrent()
        assertEquals(1, sent.filterIsInstance<UsageEvent.PlayStarted>().size)
        assertEquals(listOf(2), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
    }

    @Test fun disabledAndClosedAnalyticsDoNotCollectOrBackfillPlaytime() = runTest {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope, time = testScheduler.timeSource)
        analytics.load(); analytics.choose(false)
        val disabled = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        advanceTimeBy(8.minutes); runCurrent()
        analytics.choose(true)
        disabled.setPlaying(false); disabled.setPlaying(true)
        advanceTimeBy(8.minutes); runCurrent()
        assertTrue(sent.none { it is UsageEvent.PlayStarted || it is UsageEvent.PlayTime })
        val enabled = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        runCurrent(); advanceTimeBy(2.minutes)
        analytics.close(); runCurrent()
        enabled.close()
        advanceTimeBy(8.minutes); runCurrent()
        assertEquals(1, sent.filterIsInstance<UsageEvent.PlayStarted>().size)
        assertTrue(sent.filterIsInstance<UsageEvent.PlayTime>().isEmpty())
    }

    @Test fun delayedPauseProcessingCannotCountPausedTimeOrResumeAfterClose() = runTest {
        val time = TestTimeSource()
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope, time = time)
        analytics.load(); analytics.choose(true)
        val play = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        runCurrent()
        time += 2.minutes
        play.setPlaying(false)
        time += 6.minutes
        runCurrent()
        assertEquals(listOf(2), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
        play.setPlaying(true); runCurrent()
        time += 1.minutes
        play.close()
        play.setPlaying(true)
        time += 10.minutes
        runCurrent()
        assertEquals(listOf(2, 1), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
        val neverObserved = analytics.beginPlay(PublicMode, UsagePlayRole.Guest)
        neverObserved.close(); neverObserved.setPlaying(true)
        time += 10.minutes
        advanceTimeBy(10.minutes); runCurrent()
        assertEquals(listOf(2, 1), sent.filterIsInstance<UsageEvent.PlayTime>().map { it.minutes.value })
    }

    @Test fun conflatedPauseAndResumeStillExcludeDisconnectedTime() = runTest {
        val time = TestTimeSource()
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope, time = time)
        analytics.load(); analytics.choose(true)
        val play = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        runCurrent()
        time += 2.minutes
        play.setPlaying(false)
        time += 10.minutes
        play.setPlaying(true)
        time += 1.minutes
        runCurrent()
        play.close(); runCurrent()
        assertEquals(3, sent.filterIsInstance<UsageEvent.PlayTime>().sumOf { it.minutes.value })
        assertEquals(1, sent.filterIsInstance<UsageEvent.PlayStarted>().size)
    }

    @Test fun aTimerAlreadyDueCannotCountAConflatedDisconnectedInterval() = runTest {
        val time = TestTimeSource()
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope, time = time)
        analytics.load(); analytics.choose(true)
        val play = analytics.beginPlay(PublicMode, UsagePlayRole.Host)
        runCurrent()
        advanceTimeBy(5.minutes)
        time += 2.minutes
        play.setPlaying(false)
        time += 10.minutes
        play.setPlaying(true)
        time += 1.minutes
        runCurrent()
        play.close(); runCurrent()
        assertEquals(3, sent.filterIsInstance<UsageEvent.PlayTime>().sumOf { it.minutes.value })
    }

    private companion object {
        val PublicMode = PublicUsageMode.from(FakeCustomGamesFeed.SampleMode)!!
    }
}
