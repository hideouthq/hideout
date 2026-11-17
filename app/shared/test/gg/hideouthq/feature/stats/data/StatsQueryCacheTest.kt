package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.Loadable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class StatsQueryCacheTest {
    private class StepClock(var seconds: Long) : Clock {
        override fun now() = Instant.fromEpochSeconds(seconds)
    }

    @Test
    fun aFailureIsAskedAgainOnUseOnlyAfterAPause() = runTest {
        val clock = StepClock(1_000)
        var runs = 0
        val cache = QueryCache(backgroundScope, { _, _, _ -> if (++runs == 1) error("down") else emptyList<Nothing>() }, clock)
        assertIs<Loadable.Failed>(cache.get(PartyQuery).first { it !is Loadable.Loading })
        clock.seconds += 60
        cache.get(PartyQuery)
        testScheduler.advanceUntilIdle()
        assertEquals(1, runs, "a minute later the failure still stands")
        clock.seconds += 5 * 60
        assertEquals(Loadable.Ready(emptyList()), cache.get(PartyQuery).first { it is Loadable.Ready })
        assertEquals(2, runs)
    }

    private fun policy(deadline: Long): (Long) -> Duration? = { now -> if (now >= deadline) null else 10.seconds }

    @Test
    fun ensureRetryWakesAtTheDelayNamedRechecksAtEveryWakeAndStopsForGoodOnceNamedNone() = runTest {
        val clock = StepClock(0)
        var runs = 0
        var automaticSeen: Boolean? = null
        val cache = QueryCache(backgroundScope, { _, _, automatic -> runs++; automaticSeen = automatic; Unit }, clock)
        cache.ensureRetry(PartyQuery, policy(25))
        assertEquals(0, runs, "nothing fires before the first delay elapses")

        clock.seconds = 10; testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals(1, runs, "fires once the first delay elapses")
        assertEquals(true, automaticSeen, "a background wake's own fetch is marked automatic")

        clock.seconds = 20; testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals(2, runs)

        clock.seconds = 30; testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals(2, runs, "the policy naming no further delay at the recheck stops the loop before firing again")

        clock.seconds = 1_000; testScheduler.advanceTimeBy(1_000_000); testScheduler.runCurrent()
        assertEquals(2, runs, "once stopped, nothing resumes it on its own")
    }

    @Test
    fun ensureRetryIsIdempotentWhileAWakeIsAlreadySleeping() = runTest {
        val clock = StepClock(0)
        var runs = 0
        val cache = QueryCache(backgroundScope, { _, _, _ -> runs++; Unit }, clock)
        cache.ensureRetry(PartyQuery, policy(100))
        cache.ensureRetry(PartyQuery) { 1.seconds }
        clock.seconds = 1; testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertEquals(0, runs, "the original 10s cadence stands; a repeated call does not reset or duplicate the wake")
        clock.seconds = 10; testScheduler.advanceTimeBy(9_000); testScheduler.runCurrent()
        assertEquals(1, runs)
    }

    @Test
    fun retryNowReschedulesItsOwnedPolicyAfterCompletionWithoutAnotherPageSubscription() = runTest {
        val clock = StepClock(0)
        val attempts = mutableListOf<Boolean>()
        val manual = CompletableDeferred<Unit>()
        val cache = QueryCache(backgroundScope, { _, _, automatic ->
            attempts += automatic
            if (!automatic) manual.await()
            emptyList<Nothing>()
        }, clock)
        cache.ensureRetry(PartyQuery, policy(100))
        clock.seconds = 5; testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        cache.retryNow(PartyQuery)
        val answer = cache.get(PartyQuery)
        testScheduler.runCurrent()
        assertEquals(listOf(false), attempts)
        assertEquals(Loadable.Loading, answer.value)

        clock.seconds = 10; testScheduler.advanceTimeBy(5_000); testScheduler.runCurrent()
        assertEquals(listOf(false), attempts, "the cancelled timer cannot overlap the manual request")
        manual.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(Loadable.Ready(emptyList()), answer.value)
        clock.seconds = 19; testScheduler.advanceTimeBy(9_000); testScheduler.runCurrent()
        assertEquals(listOf(false), attempts, "the next delay starts at request completion")
        clock.seconds = 20; testScheduler.advanceTimeBy(1_000); testScheduler.runCurrent()
        assertEquals(listOf(false, true), attempts, "the entry resumes automatic checks without another ensureRetry")
    }

    @Test
    fun backgroundRetryExhaustionPreventsImplicitRequestsButAllowsManualChecks() = runTest {
        val clock = StepClock(0)
        val attempts = mutableListOf<Boolean>()
        val cache = QueryCache(backgroundScope, { _, _, automatic -> attempts += automatic; error("still not published") }, clock)
        cache.ensureRetry(PartyQuery, policy(15))
        clock.seconds = 10; testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals(listOf(true), attempts)
        clock.seconds = 20; testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals(listOf(true), attempts, "the deadline recheck stops before another request")

        clock.seconds = 620
        assertIs<Loadable.Failed>(cache.get(PartyQuery).value)
        testScheduler.runCurrent()
        assertEquals(listOf(true), attempts, "even the ordinary failed-retry pause cannot resume exhausted work")
        cache.markStale()
        cache.get(PartyQuery)
        testScheduler.runCurrent()
        assertEquals(listOf(true), attempts, "markStale and get must make literally no extra request")
        cache.ensureRetry(PartyQuery, policy(1_000_000))
        clock.seconds = 640; testScheduler.advanceTimeBy(20_000); testScheduler.runCurrent()
        assertEquals(listOf(true), attempts)

        cache.retryNow(PartyQuery)
        testScheduler.runCurrent()
        assertEquals(listOf(true, false), attempts, "manual checks remain available after exhaustion")
        cache.markStale()
        clock.seconds = 1_640; testScheduler.advanceTimeBy(1_000_000); testScheduler.runCurrent()
        cache.get(PartyQuery)
        testScheduler.runCurrent()
        assertEquals(listOf(true, false), attempts, "a manual check does not reset the automatic budget")
    }

    @Test
    fun checkingAFailedQueryPublishesLoadingAndRetainsReadyValuesOnFailedRefetch() = runTest {
        val clock = StepClock(0)
        var succeeds = false
        val finish = CompletableDeferred<Unit>()
        var runs = 0
        val cache = QueryCache(backgroundScope, { _, _, _ ->
            if (++runs > 1) finish.await()
            if (!succeeds) error("not published")
            emptyList<Nothing>()
        }, clock)
        val answer = cache.get(PartyQuery)
        testScheduler.runCurrent()
        assertIs<Loadable.Failed>(answer.value)
        cache.retryNow(PartyQuery)
        assertEquals(Loadable.Loading, answer.value, "Check now must publish checking before its request completes")
        testScheduler.runCurrent()
        assertEquals(2, runs)
        cache.retryNow(PartyQuery)
        testScheduler.runCurrent()
        assertEquals(2, runs, "another click while checking is deduplicated")
        succeeds = true
        finish.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(Loadable.Ready(emptyList()), answer.value)
        succeeds = false
        cache.retryNow(PartyQuery)
        assertEquals(Loadable.Ready(emptyList()), answer.value, "a ready value stays visible during a refetch")
        testScheduler.runCurrent()
        assertEquals(Loadable.Ready(emptyList()), answer.value, "a failed refetch retains its ready value")
    }

    @Test
    fun manualCheckAtATimerWakeJoinsTheRequestAndKeepsItsPolicy() = runTest {
        val clock = StepClock(0)
        val finish = CompletableDeferred<Unit>()
        val attempts = mutableListOf<Boolean>()
        val cache = QueryCache(backgroundScope, { _, _, automatic ->
            attempts += automatic
            finish.await()
            emptyList<Nothing>()
        }, clock)
        cache.ensureRetry(PartyQuery, policy(100))
        clock.seconds = 10; testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        cache.retryNow(PartyQuery)
        val answer = cache.get(PartyQuery)
        testScheduler.runCurrent()
        assertEquals(listOf(true), attempts, "the manual ask joins the timer's in-flight request")
        finish.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(Loadable.Ready(emptyList()), answer.value)
        clock.seconds = 20; testScheduler.advanceTimeBy(10_000); testScheduler.runCurrent()
        assertEquals(listOf(true, true), attempts, "joining the request must not cancel future background checks")
    }
}
