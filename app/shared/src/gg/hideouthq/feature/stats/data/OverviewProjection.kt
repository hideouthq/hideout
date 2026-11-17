package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchListFilter
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.MetricDistribution
import gg.hideouthq.core.data.deadlockstats.PeriodTotals
import gg.hideouthq.core.data.deadlockstats.PlayerMatchOutcome
import gg.hideouthq.core.data.deadlockstats.PlayerMetric
import gg.hideouthq.core.data.deadlockstats.StatRatio
import gg.hideouthq.core.data.deadlockstats.heroPeriodAggregates
import gg.hideouthq.core.data.deadlockstats.matches
import gg.hideouthq.core.data.deadlockstats.periodSummary
import gg.hideouthq.core.data.deadlockstats.rankHistory
import gg.hideouthq.feature.stats.AxisLabel
import gg.hideouthq.feature.stats.Change
import gg.hideouthq.feature.stats.ChangeUnit
import gg.hideouthq.feature.stats.Coverage
import gg.hideouthq.feature.stats.FeaturedHero
import gg.hideouthq.feature.stats.HeroLine
import gg.hideouthq.feature.stats.MetricKind
import gg.hideouthq.feature.stats.MetricTile
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.OverviewContent
import gg.hideouthq.feature.stats.RankChart
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.RankPoint
import gg.hideouthq.feature.stats.RankedAt
import gg.hideouthq.feature.stats.TeammateRow
import gg.hideouthq.feature.stats.WinRateTile
import kotlin.math.max
import kotlin.math.round

internal class OverviewExtras(
    val cohort: RankCohort?,
    val lobbyMetrics: Loadable<Map<String, MetricDistribution>>,
    val teammates: Loadable<List<TeammateRow>>,
    val names: Loadable<Unit>,
)

internal fun overviewContent(
    data: DeadlockStatsState,
    mode: MatchModeFilter,
    window: DateWindow,
    extras: OverviewExtras,
    reference: StatsReference,
    clock: StatsClock,
): OverviewContent {
    val filter = MatchListFilter(mode = mode, window = window)
    val period = data.periodSummary(filter)
    val totals = period.totals
    val previous = period.previousWindow?.takeIf { it.matches > 0 }
    val oldestFirst = period.last20.reversed()
    val heroes = data.heroPeriodAggregates(filter)
    val featured = heroes.firstOrNull()?.let { top ->
        reference.hero(top.heroId)?.let { hero ->
            FeaturedHero(hero, top.totals.matches, top.totals.playtimeSeconds.value, top.totals.winRate, top.totals.kda.value, top.totals.soulsPerMin.value)
        }
    }
    return OverviewContent(
        winRate = WinRateTile(
            winRate = totals.winRate, wins = totals.wins, losses = totals.losses,
            change = points(totals.winRate, previous?.winRate),
            form = oldestFirst.mapNotNull { row -> when (row.result) { PlayerMatchOutcome.Win -> true; PlayerMatchOutcome.Loss -> false; else -> null } },
        ),
        metrics = listOf(
            MetricTile(
                MetricKind.Kda, totals.kda.value, listOf(totals.avgKills, totals.avgDeaths, totals.avgAssists),
                plain(totals.kda.value, previous?.kda?.value), oldestFirst.map { it.kda },
            ),
            MetricTile(
                MetricKind.SoulsPerMin, totals.soulsPerMin.value, listOf(totals.avgSouls),
                percent(totals.soulsPerMin.value, previous?.soulsPerMin?.value), oldestFirst.map { it.soulsPerMin },
                coverage(totals.soulsPerMin, totals),
            ),
            MetricTile(
                MetricKind.DamagePerMin, totals.heroDamagePerMin.value, listOf(totals.heroDamage.average),
                percent(totals.heroDamagePerMin.value, previous?.heroDamagePerMin?.value), oldestFirst.map { it.heroDamagePerMin },
                coverage(totals.heroDamagePerMin, totals),
            ),
            MetricTile(
                MetricKind.Accuracy, totals.accuracy.value, listOf(totals.critRate.value),
                points(totals.accuracy.value, previous?.accuracy?.value), oldestFirst.map { it.accuracy },
                coverage(totals.accuracy, totals),
            ),
        ),
        cohort = extras.cohort,
        averages = extras.lobbyMetrics.map(::metricAverages),
        recent = data.matches(filter, limit = 6).rows.map { row ->
            val completedAt = row.startTime?.let { start -> start.toLong() + (row.durationS ?: 0) }
            row.toUi(reference, completedAt?.let(clock::listTime), clock.now)
        },
        featured = featured,
        heroes = heroes.drop(1).take(3).mapNotNull { aggregate ->
            reference.hero(aggregate.heroId)?.let { HeroLine(it, aggregate.totals.matches, aggregate.totals.kda.value, aggregate.totals.winRate) }
        },
        rank = rankChart(data, mode, window, reference, clock),
        lastRanked = data.rank?.let { status ->
            reference.rank(status.badge)?.let { rank -> status.lastMatchStartTime?.let { RankedAt(rank, clock.dayOf(it.toLong())) } }
        },
        teammates = extras.teammates,
        names = extras.names,
    )
}

private fun metricAverages(metrics: Map<String, MetricDistribution>): Map<MetricKind, Double> = buildMap {
    lobbyKda(metrics)?.let { put(MetricKind.Kda, it) }
    metrics[PlayerMetric.SoulsPerMin]?.avg?.let { put(MetricKind.SoulsPerMin, it) }
    metrics[PlayerMetric.HeroDamagePerMin]?.avg?.let { put(MetricKind.DamagePerMin, it) }
    metrics[PlayerMetric.Accuracy]?.avg?.let { put(MetricKind.Accuracy, it) }
}

private fun lobbyKda(metrics: Map<String, MetricDistribution>): Double? {
    val kills = metrics[PlayerMetric.Kills]?.avg ?: return null
    val deaths = metrics[PlayerMetric.Deaths]?.avg ?: return null
    val assists = metrics[PlayerMetric.Assists]?.avg ?: return null
    return (kills + assists) / deaths.coerceAtLeast(1.0)
}

private fun coverage(ratio: StatRatio, totals: PeriodTotals): Coverage? =
    Coverage(ratio.matches, totals.matches).takeIf { ratio.value != null && ratio.matches < totals.matches }

internal fun axisLabels(start: Long, end: Long, clock: StatsClock): List<AxisLabel> {
    val span = (end - start).coerceAtLeast(1)
    val days = span / 86_400.0
    val step = when {
        days <= 1.5 -> span / 4.0
        days < 28 -> max(1.0, round(days / 4)) * 86_400
        else -> max(7.0, round(days / 4 / 7) * 7) * 86_400
    }
    val ticks = generateSequence(0.0) { it + step }.takeWhile { it < span - step / 2 }.map { start + it.toLong() }.toList()
    return ticks.map { AxisLabel(((it - start).toDouble() / span).toFloat(), clock.shortDate(it)) } +
        AxisLabel(1f, if (clock.isToday(end)) Moment.Today else clock.shortDate(end))
}

internal fun points(current: Double?, previous: Double?): Change? =
    if (current == null || previous == null) null else Change((current - previous) * 100, ChangeUnit.Points)

internal fun percent(current: Double?, previous: Double?): Change? =
    if (current == null || previous == null || previous == 0.0) null else Change((current / previous - 1) * 100, ChangeUnit.Percent)

internal fun plain(current: Double?, previous: Double?): Change? =
    if (current == null || previous == null) null else Change(current - previous, ChangeUnit.Plain)

internal fun rankChart(data: DeadlockStatsState, mode: MatchModeFilter, window: DateWindow, reference: StatsReference, clock: StatsClock): RankChart? {
    if (mode == MatchModeFilter.Normal) return null
    val points = data.rankHistory(MatchListFilter(mode = MatchModeFilter.Ranked, window = window))
        .mapNotNull { point -> point.badgeAfter?.let { badge -> point.startTime?.let { RankPoint(it.toLong(), badge) } } }
    if (points.isEmpty()) return null
    val peak = points.maxWith(compareBy<RankPoint> { linearRank(it.badge) }.thenBy { it.epochSeconds })
    val start = window.fromInclusive?.toLong() ?: points.first().epochSeconds
    val end = if (window.toExclusive != null) clock.now else points.last().epochSeconds.coerceAtLeast(start + 1)
    val labels = axisLabels(start, end, clock)
    val badges = points.map { it.badge }
    val low = badges.minBy(::linearRank)
    val high = badges.maxBy(::linearRank)
    val names = (linearRank(low)..linearRank(high)).associate { step ->
        val badge = badgeOf(step)
        badge to (reference.rank(badge)?.label ?: "")
    }
    return RankChart(
        points = points,
        from = reference.rank(points.first().badge), to = reference.rank(points.last().badge),
        peak = reference.rank(peak.badge), peakAt = peak, peakDay = clock.shortDate(peak.epochSeconds),
        rankedMatches = points.size, startEpoch = start, endEpoch = end, axisLabels = labels, names = names,
    )
}
