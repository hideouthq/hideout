package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchHistoryEntry
import gg.hideouthq.feature.stats.MatchPendingPhase
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.Team
import kotlin.test.assertTrue
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class MatchPendingProjectionTest {
    private val clock = StatsClock(1_000_000L, ZoneOffset.UTC)
    private val reference = StatsReference(emptyList(), emptyList(), emptyList())

    @Test
    fun phaseBoundariesAt180And1800() {
        assertEquals(MatchPendingPhase.OnTheWay, matchPendingPhase(179))
        assertEquals(MatchPendingPhase.Slow, matchPendingPhase(180))
        assertEquals(MatchPendingPhase.Unavailable, matchPendingPhase(1800))
    }

    @Test
    fun unknownOrInvalidFutureEndIsUnavailableNotACountdown() {
        assertEquals(MatchPendingPhase.Unavailable, matchPendingPhase(null))
        assertEquals(MatchPendingPhase.Unavailable, matchPendingPhase(-1))
    }

    @Test
    fun retryDelayFastBeforeTheFastWindowSlowAfterNullAtTheDeadline() {
        assertEquals(10.seconds, matchRetryDelay(now = 119, endTimeS = 0, isComplete = false))
        assertEquals(30.seconds, matchRetryDelay(now = 120, endTimeS = 0, isComplete = false))
        assertNull(matchRetryDelay(now = 1800, endTimeS = 0, isComplete = false))
    }

    @Test
    fun retryDelayStopsOnceSavedEvenWithinTheFastWindow() {
        assertNull(matchRetryDelay(now = 5, endTimeS = 0, isComplete = true))
    }

    @Test
    fun retryDelayAsksNothingForAMissingOrFutureEnd() {
        assertNull(matchRetryDelay(now = 100, endTimeS = null, isComplete = false))
        assertNull(matchRetryDelay(now = 100, endTimeS = 101, isComplete = false))
    }

    private fun history(matchId: Long, startTime: Int?, durationS: Int?, playerTeam: Int? = 1) = MatchHistoryEntry(
        matchId = matchId, heroId = 2, startTime = startTime, matchDurationS = durationS, playerTeam = playerTeam,
        kills = 4, deaths = 1, assists = 23, netWorth = 38_640, lastHits = 182, denies = 6, matchMode = 1,
    )

    @Test
    fun pendingContentReadsTheRowNotFabricatedBreakdownTiles() {
        val endTime = 999_800
        val data = DeadlockStatsState(matchHistory = listOf(history(11, startTime = endTime - 2413, durationS = 2413)))
        val content = assertNotNull(pendingMatchContent(data, 11, reference, clock, checking = false))
        assertEquals(MatchPendingPhase.Slow, content.phase)
        assertEquals(4, content.header.kills)
        assertEquals(1, content.header.deaths)
        assertEquals(23, content.header.assists)
        assertEquals(38_640, content.header.souls)
        assertEquals(182, content.header.lastHits)
        assertEquals(6, content.header.denies)
        assertEquals(Team.Sapphire, content.header.team)
        assertNull(content.header.heroDamage, "hero damage is not fabricated from the history row")
        assertNull(content.header.objectiveDamage)
        assertNull(content.header.healing)
        assertNull(content.header.lobbyRank)
    }

    @Test
    fun finishedReadsRelativelyWhileCheckingAndAsAClockTimeOnceUnavailable() {
        val checking = DeadlockStatsState(matchHistory = listOf(history(11, startTime = 1_000_000 - 2413 - 38, durationS = 2413)))
        val checkingContent = assertNotNull(pendingMatchContent(checking, 11, reference, clock, checking = false))
        assertEquals(MatchPendingPhase.OnTheWay, checkingContent.phase)
        assertTrue(checkingContent.header.finished !is Moment.ClockTime, "still checking reads relatively, not as a bare clock time")

        val old = DeadlockStatsState(matchHistory = listOf(history(12, startTime = 1_000_000 - 2413 - 2000, durationS = 2413)))
        val oldContent = assertNotNull(pendingMatchContent(old, 12, reference, clock, checking = false))
        assertEquals(MatchPendingPhase.Unavailable, oldContent.phase)
        assertTrue(oldContent.header.finished is Moment.ClockTime, "unavailable reads as a bare clock time")
    }

    @Test
    fun aMatchWithNoKnownEndIsUnavailable() {
        val noDuration = DeadlockStatsState(matchHistory = listOf(history(13, startTime = 1_000_000 - 100, durationS = null)))
        val content = assertNotNull(pendingMatchContent(noDuration, 13, reference, clock, checking = false))
        assertEquals(MatchPendingPhase.Unavailable, content.phase)
        assertNull(content.endedOn)
    }
}
