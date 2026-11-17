package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.HeroBenchmark
import gg.hideouthq.core.data.deadlockstats.HeroCounter
import gg.hideouthq.core.data.deadlockstats.HeroPeriodAggregateView
import gg.hideouthq.core.data.deadlockstats.ItemUsageStat
import gg.hideouthq.core.data.deadlockstats.MatchListFilter
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.MetricDistribution
import gg.hideouthq.core.data.deadlockstats.PlayerMetric
import gg.hideouthq.core.data.deadlockstats.heroPeriodAggregates
import gg.hideouthq.feature.stats.BenchmarkKind
import gg.hideouthq.feature.stats.BenchmarkRow
import gg.hideouthq.feature.stats.BuildItem
import gg.hideouthq.feature.stats.HeroPanel
import gg.hideouthq.feature.stats.HeroSort
import gg.hideouthq.feature.stats.HeroStatRow
import gg.hideouthq.feature.stats.HeroVsRank
import gg.hideouthq.feature.stats.HeroesContent
import gg.hideouthq.feature.stats.Matchup
import gg.hideouthq.feature.stats.Matchups
import gg.hideouthq.feature.stats.RankCohort

internal const val MinMatchupGames = 3

internal const val BuildItems = 6

internal class HeroesExtras(
    val cohort: RankCohort?,
    val benchmarks: Loadable<List<HeroBenchmark>>,
    val heroMetrics: Loadable<Map<String, MetricDistribution>>,
    val itemUsage: Loadable<List<ItemUsageStat>>,
    val counters: Loadable<List<HeroCounter>>,
)

internal fun heroAggregates(data: DeadlockStatsState, mode: MatchModeFilter, window: DateWindow): List<HeroPeriodAggregateView> =
    data.heroPeriodAggregates(MatchListFilter(mode = mode, window = window))

internal fun selectedHero(aggregates: List<HeroPeriodAggregateView>, wanted: Int?): Int? =
    aggregates.firstOrNull { it.heroId == wanted }?.heroId ?: aggregates.firstOrNull()?.heroId

internal fun heroesContent(
    aggregates: List<HeroPeriodAggregateView>,
    selected: Int?,
    sort: HeroSort,
    extras: HeroesExtras,
    reference: StatsReference,
): HeroesContent {
    val rows = aggregates.mapNotNull { aggregate ->
        val totals = aggregate.totals
        reference.hero(aggregate.heroId)?.let { hero ->
            HeroStatRow(
                hero = hero, matches = totals.matches, wins = totals.wins, playedSeconds = totals.playtimeSeconds.value,
                winRate = totals.winRate, kda = totals.kda.value, soulsPerMin = totals.soulsPerMin.value,
                damagePerMin = totals.heroDamagePerMin.value, accuracy = totals.accuracy.value,
            )
        }
    }
    val vsRank = extras.benchmarks.map { benchmarks ->
        val bench = benchmarks.associateBy { it.heroId }
        rows.mapNotNull { row ->
            val yours = row.winRate ?: return@mapNotNull null
            val benchmark = bench[row.hero.id]?.takeIf { it.matches > 0 } ?: return@mapNotNull null
            HeroVsRank(row.hero, yours, benchmark.wins.toDouble() / benchmark.matches)
        }
    }
    val panelAggregate = aggregates.firstOrNull { it.heroId == selected }
    return HeroesContent(
        rows = sorted(rows, sort),
        cohort = extras.cohort,
        vsRank = vsRank,
        panel = panelAggregate?.let { heroPanel(it, extras, reference) },
    )
}

private fun sorted(rows: List<HeroStatRow>, sort: HeroSort): List<HeroStatRow> {
    fun <T : Comparable<T>> by(selector: (HeroStatRow) -> T?) = compareByDescending<HeroStatRow> { selector(it) != null }.thenByDescending(selector)
    val comparator = when (sort) {
        HeroSort.MostPlayed -> compareByDescending<HeroStatRow> { it.matches }
        HeroSort.WinRate -> by { it.winRate }
        HeroSort.Kda -> by { it.kda }
        HeroSort.SoulsPerMin -> by { it.soulsPerMin }
        HeroSort.DamagePerMin -> by { it.damagePerMin }
        HeroSort.Accuracy -> by { it.accuracy }
        HeroSort.Name -> compareBy<HeroStatRow> { it.hero.name.ifEmpty { "￿" } }
    }
    return rows.sortedWith(comparator.thenByDescending { it.matches }.thenBy { it.hero.id })
}

private fun heroPanel(aggregate: HeroPeriodAggregateView, extras: HeroesExtras, reference: StatsReference): HeroPanel? {
    val hero = reference.hero(aggregate.heroId) ?: return null
    val totals = aggregate.totals
    val usage = extras.itemUsage.map { stats ->
        stats.filter { reference.item(it.itemId)?.category != null }.sortedWith(compareByDescending<ItemUsageStat> { it.matches }.thenBy { it.itemId })
    }
    val buildOf = maxOf(totals.matches.toLong(), usage.valueOrNull?.maxOfOrNull { it.matches } ?: 0).toInt()
    return HeroPanel(
        hero = hero,
        matches = totals.matches,
        winRate = totals.winRate,
        playedSeconds = totals.playtimeSeconds.value,
        benchmarks = listOf(
            BenchmarkRow(BenchmarkKind.SoulsPerMin, totals.soulsPerMin.value),
            BenchmarkRow(BenchmarkKind.HeroDamagePerMin, totals.heroDamagePerMin.value),
            BenchmarkRow(BenchmarkKind.Accuracy, totals.accuracy.value),
            BenchmarkRow(BenchmarkKind.CritRate, totals.critRate.value),
            BenchmarkRow(BenchmarkKind.ObjectiveDamagePerMin, totals.objectiveDamagePerMin.value),
        ),
        averages = extras.heroMetrics.map(::benchmarkAverages),
        build = usage.map { stats -> stats.take(BuildItems).mapNotNull { stat -> reference.item(stat.itemId)?.let { BuildItem(it, stat.matches.toInt()) } } },
        buildOf = buildOf,
        matchups = extras.counters.map { counters -> matchups(counters, aggregate.heroId, reference) },
    )
}

private fun benchmarkAverages(metrics: Map<String, MetricDistribution>): Map<BenchmarkKind, Double> = buildMap {
    metrics[PlayerMetric.SoulsPerMin]?.avg?.let { put(BenchmarkKind.SoulsPerMin, it) }
    metrics[PlayerMetric.HeroDamagePerMin]?.avg?.let { put(BenchmarkKind.HeroDamagePerMin, it) }
    metrics[PlayerMetric.Accuracy]?.avg?.let { put(BenchmarkKind.Accuracy, it) }
    metrics[PlayerMetric.CritRate]?.avg?.let { put(BenchmarkKind.CritRate, it) }
    metrics[PlayerMetric.ObjectiveDamagePerMin]?.avg?.let { put(BenchmarkKind.ObjectiveDamagePerMin, it) }
}

internal fun matchups(counters: List<HeroCounter>, heroId: Int, reference: StatsReference): Matchups {
    val played = counters.filter { it.heroId == heroId && it.matchesPlayed >= MinMatchupGames }
    fun losses(counter: HeroCounter) = counter.matchesPlayed - counter.wins
    val rate = { counter: HeroCounter -> counter.wins.toDouble() / counter.matchesPlayed }
    val best = played.filter { it.wins > losses(it) }
        .sortedWith(compareByDescending(rate).thenByDescending { it.matchesPlayed }.thenBy { it.enemyHeroId }).firstOrNull()
    val hardest = played.filter { losses(it) > it.wins }
        .sortedWith(compareBy(rate).thenByDescending { it.matchesPlayed }.thenBy { it.enemyHeroId }).firstOrNull()
    fun matchup(counter: HeroCounter) = reference.hero(counter.enemyHeroId)?.let { Matchup(it, counter.wins.toInt(), losses(counter).toInt()) }
    return Matchups(best?.let(::matchup), hardest?.let(::matchup))
}
