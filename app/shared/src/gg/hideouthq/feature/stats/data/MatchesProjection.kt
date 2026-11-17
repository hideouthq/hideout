package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchListFilter
import gg.hideouthq.core.data.deadlockstats.MatchMode
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.MatchResultFilter
import gg.hideouthq.core.data.deadlockstats.MatchRow
import gg.hideouthq.core.data.deadlockstats.PlayerMatchOutcome
import gg.hideouthq.core.data.deadlockstats.heroPeriodAggregates
import gg.hideouthq.core.data.deadlockstats.matches
import gg.hideouthq.feature.stats.MatchDay
import gg.hideouthq.feature.stats.MatchKind
import gg.hideouthq.feature.stats.MatchRowUi
import gg.hideouthq.feature.stats.MatchesContent
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.RankChange
import gg.hideouthq.feature.stats.ResultFilter
import kotlin.math.roundToInt

internal fun MatchRow.outcome(): Outcome = when (result) {
    PlayerMatchOutcome.Win -> Outcome.Win
    PlayerMatchOutcome.Loss -> Outcome.Loss
    else -> Outcome.Other
}

internal fun matchKind(matchMode: Int?): MatchKind = when (MatchMode.ofCode(matchMode)) {
    MatchMode.Ranked -> MatchKind.Ranked
    MatchMode.Unranked -> MatchKind.Normal
    else -> MatchKind.Other
}

internal fun rankSteps(from: Int, to: Int): Int = linearRank(to) - linearRank(from)

internal fun linearRank(badge: Int): Int = (badge / 10) * 6 + (badge % 10 - 1).coerceIn(0, 5)

internal fun badgeOf(linear: Int): Int = (linear / 6) * 10 + linear % 6 + 1

internal fun averageBadge(badges: List<Int>): Int? =
    badges.filter { it > 0 }.takeIf { it.isNotEmpty() }?.let { valid -> badgeOf(valid.map(::linearRank).average().roundToInt()) }

internal fun rankChange(kind: MatchKind, before: Int?, after: Int?, reference: StatsReference, placement: Boolean = false): RankChange {
    if (kind != MatchKind.Ranked) return RankChange.Unranked
    if (placement) return reference.rank(after)?.let { RankChange.Placed(it) } ?: RankChange.Calibrating
    if (before == null || after == null || before <= 0) return RankChange.Unknown
    val to = reference.rank(after) ?: return RankChange.Unknown
    val steps = rankSteps(before, after)
    return when {
        steps > 0 -> RankChange.Up(steps, to)
        steps < 0 -> RankChange.Down(-steps, to)
        else -> RankChange.NoChange
    }
}

internal fun MatchRow.toUi(reference: StatsReference, playedAt: Moment?, now: Long): MatchRowUi {
    val kind = matchKind(matchMode)
    val elapsedSinceEnd = endTimeS()?.let { now - it }
    return MatchRowUi(
        matchId = matchId, hero = reference.hero(heroId), outcome = outcome(), kind = kind, durationS = durationS, level = heroLevel,
        kills = kills, deaths = deaths, assists = assists, kda = kda, souls = netWorth, heroDamage = heroDamage, lastHits = lastHits, denies = denies,
        rankChange = rankChange(kind, rankBefore, rankAfter, reference, placement), playedAt = playedAt,
        abandoned = abandonedTimeS?.let { it > 0 } == true,
        detailsPending = !hasDetail && elapsedSinceEnd != null && elapsedSinceEnd in 0 until MatchPendingTiming.Deadline.inWholeSeconds,
    )
}

internal fun ResultFilter.toFilter(): MatchResultFilter = when (this) {
    ResultFilter.All -> MatchResultFilter.All
    ResultFilter.Wins -> MatchResultFilter.Win
    ResultFilter.Losses -> MatchResultFilter.Loss
}

internal fun matchesContent(
    data: DeadlockStatsState,
    mode: MatchModeFilter,
    window: DateWindow,
    heroId: Int?,
    result: ResultFilter,
    shown: Int,
    reference: StatsReference,
    clock: StatsClock,
): MatchesContent {
    val filter = MatchListFilter(mode = mode, window = window, heroId = heroId, result = result.toFilter())
    val total = data.matches(filter, limit = PageRows)
    val read = ArrayList(total.rows)
    var next = total.nextCursor
    while (next != null && (read.size < shown || !dayClosed(read, shown, clock))) {
        val page = data.matches(filter, next, PageRows)
        read += page.rows
        next = page.nextCursor
    }
    val rows = read.take(shown)
    val dayCounts = read.filter { it.startTime != null }.groupBy { clock.date(it.startTime!!.toLong()) }
    val days = rows.groupBy { row -> row.startTime?.let { clock.date(it.toLong()) } }.map { (day, dayRows) ->
        val all = day?.let(dayCounts::get) ?: dayRows
        MatchDay(
            day = dayRows.first().startTime?.let { clock.heading(it.toLong()) },
            wins = all.count { it.result == PlayerMatchOutcome.Win },
            losses = all.count { it.result == PlayerMatchOutcome.Loss },
            rows = dayRows.map { row -> row.toUi(reference, row.startTime?.let { clock.rowTime(it.toLong()) }, clock.now) },
        )
    }
    val heroes = data.heroPeriodAggregates(MatchListFilter(mode = mode, window = window)).mapNotNull { reference.hero(it.heroId) }
    return MatchesContent(heroes, reference.hero(heroId), total.filteredTotal, total.filteredWins, total.filteredLosses, days, rows.size)
}

private fun dayClosed(read: List<MatchRow>, shown: Int, clock: StatsClock): Boolean {
    val lastShownDay = read.getOrNull(shown - 1)?.startTime?.let { clock.date(it.toLong()) }
    return read.size > shown && read.last().startTime?.let { clock.date(it.toLong()) } != lastShownDay
}

/** Rows read from the history at a time; the history allows up to 200. */
private const val PageRows = 200
