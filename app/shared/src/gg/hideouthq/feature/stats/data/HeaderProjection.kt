package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchListFilter
import gg.hideouthq.core.data.deadlockstats.MatchMode
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.PlayerMatchOutcome
import gg.hideouthq.core.data.deadlockstats.RankDistributionEntry
import gg.hideouthq.core.data.deadlockstats.RankedSeason
import gg.hideouthq.core.data.deadlockstats.profileSummary
import gg.hideouthq.core.data.deadlockstats.rankHistory
import gg.hideouthq.feature.stats.Calibration
import gg.hideouthq.feature.stats.HeaderRank
import gg.hideouthq.feature.stats.PlayerVisual
import gg.hideouthq.feature.stats.StatsHeader
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.TrackedTotals
import kotlin.math.ceil

internal fun headerOf(
    data: DeadlockStatsState,
    isYou: Boolean,
    fallbackName: String?,
    period: StatsPeriod,
    window: DateWindow?,
    seasons: List<RankedSeason>?,
    distribution: List<RankDistributionEntry>?,
    reference: StatsReference,
    clock: StatsClock,
): StatsHeader? {
    val summary = data.profileSummary(MatchListFilter()) ?: return null
    val mostPlayed = reference.hero(summary.mostPlayedHeroId)
    val streak = summary.currentStreak?.takeIf { it.length >= 2 }
    return StatsHeader(
        player = PlayerVisual(summary.accountId, summary.name?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName.orEmpty(), summary.avatarUrl),
        isYou = isYou,
        lastMatch = summary.lastMatchAt?.let { clock.ago(it.toLong(), clock::started) },
        updated = data.fetchedAt["history"]?.let { clock.ago(it) },
        mostPlayed = mostPlayed,
        winStreak = streak?.takeIf { it.outcome == PlayerMatchOutcome.Win }?.length,
        lossStreak = streak?.takeIf { it.outcome == PlayerMatchOutcome.Loss }?.length,
        trackedSince = summary.firstMatchAt?.let { clock.monthYear(it.toLong()) },
        rank = headerRank(data, period, window, seasons, distribution, reference, clock),
        totals = TrackedTotals(
            matches = summary.matchesTracked, wins = summary.wins, losses = summary.losses, winRate = summary.totals.winRate,
            kda = summary.totals.kda.value, playedSeconds = summary.playtimeSeconds, heroes = summary.heroesPlayedCount,
        ),
    )
}

private fun headerRank(
    data: DeadlockStatsState,
    period: StatsPeriod,
    window: DateWindow?,
    seasons: List<RankedSeason>?,
    distribution: List<RankDistributionEntry>?,
    reference: StatsReference,
    clock: StatsClock,
): HeaderRank? {
    val now = clock.now
    val badge = data.rank?.badge?.takeIf { it > 0 }
    val season = seasons?.firstOrNull { it.startContaining(now) != null }
    val seasonStart = season?.startContaining(now)
    val calibration = if (badge == null && seasonStart != null) {
        data.matchHistory.count { (it.rankedCalibrationMatch ?: 0) != 0 && (it.startTime ?: 0) >= seasonStart }
            .takeIf { it > 0 }?.let { Calibration(it, season.calibrationMatches) }
    } else null
    if (badge == null && calibration == null) return null
    return HeaderRank(
        rank = reference.rank(badge),
        calibration = calibration,
        change = window?.let { rankChangeIn(data, it) },
        changePeriod = period,
        topPercent = badge?.let { topPercent(it, distribution) },
        lastRanked = data.rank?.takeIf { badge != null }?.lastMatchStartTime?.let { clock.dayOf(it.toLong()) },
    )
}

internal fun rankChangeIn(data: DeadlockStatsState, window: DateWindow): Int? {
    val points = data.rankHistory(MatchListFilter(mode = MatchModeFilter.Ranked, window = window)).filter { it.badgeAfter != null }
    val first = points.firstOrNull() ?: return null
    val earlier = data.matchHistory.filter {
        MatchMode.ofCode(it.matchMode) == MatchMode.Ranked && it.rankedDisplayBadge?.let { badge -> badge > 0 } == true &&
            it.startTime != null && first.startTime != null && it.startTime < first.startTime
    }.maxByOrNull { it.startTime!! }?.rankedDisplayBadge
    val start = first.badgeBefore ?: earlier ?: first.badgeAfter!!
    return rankSteps(start, points.last().badgeAfter!!)
}

internal fun topPercent(badge: Int, distribution: List<RankDistributionEntry>?): Int? {
    val rows = distribution?.filter { it.badge > 0 && it.players > 0 } ?: return null
    val total = rows.sumOf { it.players }.takeIf { it > 0 } ?: return null
    val atOrAbove = rows.filter { it.badge >= badge }.sumOf { it.players }
    return ceil(atOrAbove * 100.0 / total).toInt().coerceIn(1, 100)
}
