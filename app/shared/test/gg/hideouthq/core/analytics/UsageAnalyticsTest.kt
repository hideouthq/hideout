package gg.hideouthq.core.analytics

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
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
}
