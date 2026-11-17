package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchRow
import gg.hideouthq.core.data.deadlockstats.matchRow
import gg.hideouthq.feature.stats.MatchHeader
import gg.hideouthq.feature.stats.MatchPendingContent
import gg.hideouthq.feature.stats.MatchPendingPhase
import gg.hideouthq.feature.stats.Team
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal object MatchPendingTiming {
    val FastWindow = 120.seconds
    val FastInterval = 10.seconds
    val SlowInterval = 30.seconds
    val SlowPhaseAt = 180.seconds
    val Deadline = 1800.seconds
}

internal fun matchPendingPhase(elapsedS: Long?): MatchPendingPhase = when {
    elapsedS == null || elapsedS < 0 -> MatchPendingPhase.Unavailable
    elapsedS < MatchPendingTiming.SlowPhaseAt.inWholeSeconds -> MatchPendingPhase.OnTheWay
    elapsedS < MatchPendingTiming.Deadline.inWholeSeconds -> MatchPendingPhase.Slow
    else -> MatchPendingPhase.Unavailable
}

internal fun MatchRow.endTimeS(): Long? = startTime?.let { start -> durationS?.let { duration -> start.toLong() + duration } }

internal fun matchRetryDelay(now: Long, endTimeS: Long?, isComplete: Boolean): Duration? {
    if (isComplete) return null
    val elapsed = endTimeS?.let { now - it } ?: return null
    return when {
        elapsed < 0 -> null
        elapsed >= MatchPendingTiming.Deadline.inWholeSeconds -> null
        elapsed < MatchPendingTiming.FastWindow.inWholeSeconds -> MatchPendingTiming.FastInterval
        else -> MatchPendingTiming.SlowInterval
    }
}

internal fun pendingMatchHeader(row: MatchRow, team: Team?, reference: StatsReference, clock: StatsClock, phase: MatchPendingPhase, endTimeS: Long?): MatchHeader {
    val kind = matchKind(row.matchMode)
    return MatchHeader(
        hero = reference.hero(row.heroId),
        outcome = row.outcome(),
        kind = kind,
        durationS = row.durationS,
        team = team,
        level = row.heroLevel,
        lobbyRank = null,
        rankChange = rankChange(kind, row.rankBefore, row.rankAfter, reference, row.placement),
        mostAssists = false,
        kills = row.kills,
        deaths = row.deaths,
        assists = row.assists,
        kda = row.kda,
        souls = row.netWorth,
        heroDamage = null,
        heroDamagePlace = null,
        lastHits = row.lastHits,
        denies = row.denies,
        objectiveDamage = null,
        healing = null,
        finished = endTimeS?.let { if (phase == MatchPendingPhase.Unavailable) clock.clock(it) else clock.ago(it) },
    )
}

internal fun pendingMatchContent(data: DeadlockStatsState, matchId: Long, reference: StatsReference, clock: StatsClock, checking: Boolean): MatchPendingContent? {
    val row = data.matchRow(matchId) ?: return null
    val team = teamOf(data.matchHistory.firstOrNull { it.matchId == matchId }?.playerTeam)
    val endTimeS = row.endTimeS()
    val elapsed = endTimeS?.let { clock.now - it }
    val phase = matchPendingPhase(elapsed)
    return MatchPendingContent(
        matchId = matchId,
        header = pendingMatchHeader(row, team, reference, clock, phase, endTimeS),
        phase = phase,
        endedOn = endTimeS?.let { clock.started(it) },
        checking = checking,
    )
}
