package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.HeroBenchmark
import gg.hideouthq.core.data.deadlockstats.HeroCounter
import gg.hideouthq.core.data.deadlockstats.ItemAsset
import gg.hideouthq.core.data.deadlockstats.ItemUsageStat
import gg.hideouthq.core.data.deadlockstats.MatchDetail
import gg.hideouthq.core.data.deadlockstats.MatchHistoryEntry
import gg.hideouthq.core.data.deadlockstats.MatchItemEvent
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.MatchObjectiveEvent
import gg.hideouthq.core.data.deadlockstats.MatchParticipant
import gg.hideouthq.core.data.deadlockstats.MatchPlayerStatSnapshot
import gg.hideouthq.core.data.deadlockstats.MatchPlayerSummary
import gg.hideouthq.core.data.deadlockstats.MetricDistribution
import gg.hideouthq.core.data.deadlockstats.MidBossEvent
import gg.hideouthq.core.data.deadlockstats.PlayedWithStats
import gg.hideouthq.core.data.deadlockstats.PlayerMetric
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.RankAsset
import gg.hideouthq.core.data.deadlockstats.RankDistributionEntry
import gg.hideouthq.core.data.deadlockstats.RankStatus
import gg.hideouthq.core.data.deadlockstats.RankedSeason
import gg.hideouthq.core.data.deadlockstats.SeasonInterval
import gg.hideouthq.core.model.Hero
import gg.hideouthq.feature.stats.BenchmarkKind
import gg.hideouthq.feature.stats.BuildItem
import gg.hideouthq.feature.stats.Calibration
import gg.hideouthq.feature.stats.ChangeUnit
import gg.hideouthq.feature.stats.HeroSort
import gg.hideouthq.feature.stats.HeroVsRank
import gg.hideouthq.feature.stats.ItemCategory
import gg.hideouthq.feature.stats.MatchKind
import gg.hideouthq.feature.stats.Matchups
import gg.hideouthq.feature.stats.MetricKind
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.ItemFate
import gg.hideouthq.feature.stats.Lane
import gg.hideouthq.feature.stats.ObjectiveEvent
import gg.hideouthq.feature.stats.ObjectiveKind
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.RankChange
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.RankedAt
import gg.hideouthq.feature.stats.ResultFilter
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.Team
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsProjectionsTest {
    private val day = 86_400L
    private val now = 1_791_900_000L
    private val clock = StatsClock(now, ZoneOffset.UTC)
    private val me = 1L
    private val window = DateWindow((now - 30 * day).toInt(), (now + 3600).toInt())

    private val reference = StatsReference(
        heroes = listOf(
            Hero("hero_gigawatt", "Seven", "seven.png", "seven_bg.png", numericId = 2, renderUrl = "seven_card.png", colorHex = "#CC861E"),
            Hero("hero_haze", "Haze", numericId = 13),
            Hero("hero_bebop", "Bebop", numericId = 15),
        ),
        items = listOf(
            ItemAsset(10, "upgrade_clip_size", "Extended Magazine", category = "weapon", cost = 800, iconUrl = "basic_magazine.webp", image = "clip.png"),
            ItemAsset(11, "upgrade_titan_round", "Titanic Magazine", category = "weapon", cost = 1600),
            ItemAsset(12, "upgrade_sprint", "Sprint Boots", category = "vitality", cost = 800),
            ItemAsset(13, "ability_thorns", "Entangling Thorns"),
            ItemAsset(14, "upgrade_charge", "Extra Charge", category = "spirit", cost = 800),
        ),
        ranks = listOf(RankAsset(7, "Emissary", "#B47FEB", subrankImages = mapOf(4 to "emissary4.png"))),
    )

    private fun entry(
        id: Long, daysAgo: Double, hero: Int = 2, won: Boolean? = true, mode: Int = 1, badge: Int? = null,
        k: Int = 6, d: Int = 3, a: Int = 9, souls: Int = 30_000, duration: Int = 1800, calibration: Int? = null,
    ) = MatchHistoryEntry(
        matchId = id, heroId = hero, heroLevel = 30, startTime = (now - daysAgo * day).toInt(), gameMode = 1, matchMode = mode, playerTeam = 0,
        kills = k, deaths = d, assists = a, denies = 5, netWorth = souls, lastHits = 200, matchDurationS = duration,
        matchResult = when (won) { true -> 0; false -> 1; null -> null }, playerMatchOutcome = if (won == null) 5 else null,
        rankedDisplayBadge = badge, rankedCalibrationMatch = calibration,
    )

    private val noExtras = OverviewExtras(null, Loadable.Ready(emptyMap()), Loadable.Ready(emptyList()), Loadable.Ready(Unit))

    private fun state(history: List<MatchHistoryEntry>, extra: (DeadlockStatsState) -> DeadlockStatsState = { it }): DeadlockStatsState =
        extra(DeadlockStatsState(accountId = me, matchHistory = history, fetchedAt = mapOf("history" to now - 30)))

    @Test
    fun theHeaderIsTrackedAllTimeWithRankChangeOverThePeriodAndTopShare() {
        val data = state(
            listOf(
                entry(1, 40.0, mode = 4, badge = 72), entry(2, 20.0, mode = 4, badge = 73), entry(3, 2.0, mode = 4, badge = 74),
                entry(4, 1.0), entry(5, 0.1, won = true), entry(6, 50.0, won = false, hero = 13),
            ),
        ) { it.copy(profile = PlayerProfile(me, personaName = null), rank = RankStatus(74, 7, 4)) }
        val distribution = listOf(RankDistributionEntry(73, 50), RankDistributionEntry(74, 30), RankDistributionEntry(75, 20))
        val header = assertNotNull(headerOf(data, true, "Mira", StatsPeriod.Month, window, null, distribution, reference, clock))
        assertEquals("Mira", header.player.name, "Steam's local name stands in until the profile has one")
        assertEquals(6, header.totals.matches)
        assertEquals(5, header.totals.wins)
        assertEquals(6 * 1800L, header.totals.playedSeconds)
        assertEquals(2, header.totals.heroes)
        assertEquals(5, header.winStreak)
        assertEquals(Moment.HoursAgo(2), header.lastMatch)
        assertEquals(Moment.JustNow, header.updated)
        assertEquals("Emissary 4", header.rank?.rank?.label)
        assertEquals("emissary4.png", header.rank?.rank?.imageUrl)
        assertEquals(2, header.rank?.change, "from the badge before the period's first ranked match")
        assertEquals(50, header.rank?.topPercent)
        assertEquals("seven_bg.png", header.mostPlayed?.artUrl, "the most played hero's art")
    }

    @Test
    fun anUnrankedPlayerInCalibrationShowsProgressInsteadOfABadge() {
        val season = RankedSeason("Season", 8, listOf(SeasonInterval(now - 10 * day, now + 10 * day)))
        val data = state(listOf(entry(1, 1.0, mode = 4, calibration = 1), entry(2, 2.0, mode = 4, calibration = 1), entry(3, 20.0, mode = 4, calibration = 1)))
        val rank = assertNotNull(headerOf(data, true, null, StatsPeriod.Month, window, listOf(season), null, reference, clock)?.rank)
        assertNull(rank.rank)
        assertEquals(Calibration(2, 8), rank.calibration, "only this season's calibration matches count")
        assertNull(headerOf(state(listOf(entry(1, 1.0))), true, null, StatsPeriod.Month, window, null, null, reference, clock)?.rank, "never ranked: no rank panel")
    }

    @Test
    fun overviewTilesCompareWithThePreviousPeriodAndTheRankBand() {
        val history = (0 until 10).map { entry(100L + it, 1.0 + it, won = it % 3 != 0, k = 8, d = 4, a = 8, souls = 36_000, duration = 1920) } +
            (0 until 10).map { entry(200L + it, 31.0 + it, won = it % 2 == 0, k = 6, d = 4, a = 6, souls = 30_000, duration = 1920) } +
            entry(300, 0.5, won = null, k = 8, d = 4, a = 8, souls = 36_000, duration = 1920)
        val data = state(history) { s -> s.copy(matchSummaries = (100L..104L).associateWith { MatchPlayerSummary(it, me, playerDamage = 60_000, shotsHit = 40, shotsMissed = 60) }) }
        val metrics = mapOf(
            PlayerMetric.Kills to MetricDistribution(6.0), PlayerMetric.Deaths to MetricDistribution(5.0), PlayerMetric.Assists to MetricDistribution(8.5),
            PlayerMetric.SoulsPerMin to MetricDistribution(1040.0), PlayerMetric.HeroDamagePerMin to MetricDistribution(1690.0), PlayerMetric.Accuracy to MetricDistribution(0.38),
        )
        val cohort = RankCohort("Emissary", rankedOnly = true)
        val overview = overviewContent(data, MatchModeFilter.All, window, OverviewExtras(cohort, Loadable.Ready(metrics), Loadable.Ready(emptyList()), Loadable.Ready(Unit)), reference, clock)
        with(overview.winRate) {
            assertEquals(6, wins)
            assertEquals(4, losses, "a not-scored match is neither")
            assertEquals(ChangeUnit.Points, change?.unit)
            assertEquals(10.0, change?.amount!!, 1e-9)
            assertEquals(10, form.size)
            assertEquals(listOf(false, true, true, false), form.take(4), "oldest first")
        }
        val tiles = overview.metrics.associateBy { it.kind }
        assertEquals(4.0, tiles.getValue(MetricKind.Kda).value)
        assertEquals(ChangeUnit.Plain, tiles.getValue(MetricKind.Kda).change?.unit)
        val averages = assertIs<Loadable.Ready<Map<MetricKind, Double>>>(overview.averages).value
        assertEquals((6.0 + 8.5) / 5.0, averages[MetricKind.Kda], "rank KDA from mean kills, deaths and assists")
        assertEquals(36_000 * 60.0 / 1920, tiles.getValue(MetricKind.SoulsPerMin).value)
        assertEquals(ChangeUnit.Percent, tiles.getValue(MetricKind.SoulsPerMin).change?.unit)
        assertEquals(20.0, tiles.getValue(MetricKind.SoulsPerMin).change?.amount!!, 1e-9)
        val damage = tiles.getValue(MetricKind.DamagePerMin)
        assertEquals(5, damage.coverage?.covered, "damage covers the five summarised matches")
        assertEquals(11, damage.coverage?.of)
        assertNull(damage.change, "the previous period has no damage numbers")
        assertEquals(1690.0, averages[MetricKind.DamagePerMin])
        assertEquals(0.4, tiles.getValue(MetricKind.Accuracy).value)
        assertEquals(11, tiles.getValue(MetricKind.Kda).series.size, "every match of the period, at most twenty")
        assertEquals(cohort, overview.cohort)
        assertEquals(6, overview.recent.size)
        assertEquals("Seven", overview.featured?.hero?.name)
        assertEquals("seven_card.png", overview.featured?.hero?.renderUrl)
    }

    @Test
    fun recentMatchesShowTimeSinceCompletion() {
        val history = listOf(
            entry(1, 0.0, duration = 2400).copy(startTime = (now - 2420).toInt()),
            entry(2, 0.0, duration = 1800).copy(startTime = (now - 2520).toInt()),
        )
        val overview = overviewContent(state(history), MatchModeFilter.All, window, noExtras, reference, clock)
        assertEquals(listOf(Moment.JustNow, Moment.MinutesAgo(12)), overview.recent.map { it.playedAt })
        assertEquals(listOf(2400, 1800), overview.recent.map { it.durationS })
    }

    @Test
    fun recentMatchesUseTheCompletionDayNotTheStartDayAcrossMidnight() {
        val zone = ZoneOffset.ofHours(-5)
        val startTime = OffsetDateTime.of(2025, 6, 1, 23, 50, 0, 0, zone).toEpochSecond()
        val duration = 2400
        val completedAt = startTime + duration
        val clock = StatsClock(completedAt + 5400, zone)
        val history = listOf(entry(1, 0.0, duration = duration).copy(startTime = startTime.toInt()))
        val window = DateWindow((startTime - day).toInt(), (clock.now + day).toInt())
        val overview = overviewContent(state(history), MatchModeFilter.All, window, noExtras, reference, clock)
        assertEquals(Moment.HoursAgo(1), overview.recent.single().playedAt, "bucketed by the completion day (today), not the match's start day (yesterday)")
    }

    @Test
    fun recentMatchesFallBackToStartTimeWithoutADurationAndToUnknownWithoutAStartTime() {
        val history = listOf(
            entry(1, 0.0).copy(startTime = (now - 600).toInt(), matchDurationS = null),
            entry(2, 0.0).copy(startTime = null),
        )
        val overview = overviewContent(state(history), MatchModeFilter.All, DateWindow(null, null), noExtras, reference, clock)
        assertEquals(listOf(Moment.MinutesAgo(10), null), overview.recent.map { it.playedAt }, "no duration falls back to start time; no start time is an unknown timestamp")
    }

    @Test
    fun theRankChartFollowsRankedMatchesInThePeriodAndIsAbsentForNormal() {
        val data = state(listOf(entry(1, 20.0, mode = 4, badge = 72), entry(2, 10.0, mode = 4, badge = 75), entry(3, 5.0, mode = 4, badge = 74), entry(4, 1.0)))
        val chart = assertNotNull(rankChart(data, MatchModeFilter.All, window, reference, clock))
        assertEquals(listOf(72, 75, 74), chart.points.map { it.badge })
        assertEquals("Emissary 2", chart.from?.label)
        assertEquals("Emissary 4", chart.to?.label)
        assertEquals("Emissary 5", chart.peak?.label)
        assertEquals(3, chart.rankedMatches)
        assertEquals(setOf(72, 73, 74, 75), chart.names.keys)
        assertEquals(Moment.Today, chart.axisLabels.last().moment)
        assertNull(rankChart(data, MatchModeFilter.Normal, window, reference, clock))
        assertNull(rankChart(state(listOf(entry(1, 1.0))), MatchModeFilter.All, window, reference, clock))
    }

    @Test
    fun matchDaysCountEveryMatchOfTheDayNotOnlyTheRowsShown() {
        val data = state((0 until 5).map { entry(10L + it, 0.1 + it * 0.01, won = it != 2) } + entry(20, 1.2, won = false, hero = 13, mode = 4, badge = 73))
        val content = matchesContent(data, MatchModeFilter.All, window, null, ResultFilter.All, shown = 3, reference = reference, clock = clock)
        assertEquals(6, content.total)
        assertEquals(3, content.shown)
        assertEquals(1, content.days.size)
        assertEquals(Moment.Today, content.days.single().day)
        assertEquals(4, content.days.single().wins)
        assertEquals(1, content.days.single().losses)
        assertEquals(listOf("Seven", "Haze"), content.heroOptions.map { it.name })
        val losses = matchesContent(data, MatchModeFilter.All, window, null, ResultFilter.Losses, shown = 20, reference = reference, clock = clock)
        assertEquals(2, losses.total)
        val haze = losses.days.flatMap { it.rows }.single { it.hero?.name == "Haze" }
        assertEquals(MatchKind.Ranked, haze.kind)
        assertEquals(RankChange.Unknown, haze.rankChange, "nothing earlier to compare with")
        assertEquals(Outcome.Loss, haze.outcome)
    }

    @Test
    fun heroesSortCompareWithTheRankAndOnlyOfferRealMatchups() {
        val history = (0 until 6).map { entry(10L + it, 1.0 + it, hero = 2, won = it < 4) } + (0 until 3).map { entry(20L + it, 2.0 + it, hero = 13, won = it == 0) }
        val data = state(history)
        val aggregates = heroAggregates(data, MatchModeFilter.All, window)
        val extras = HeroesExtras(
            cohort = RankCohort("Emissary", rankedOnly = false),
            benchmarks = Loadable.Ready(listOf(HeroBenchmark(2, 100, 51, 49), HeroBenchmark(13, 0, 0, 0))),
            heroMetrics = Loadable.Ready(mapOf(PlayerMetric.SoulsPerMin to MetricDistribution(900.0), PlayerMetric.Accuracy to MetricDistribution(0.5))),
            itemUsage = Loadable.Ready(listOf(ItemUsageStat(14, 6), ItemUsageStat(13, 6), ItemUsageStat(10, 7), ItemUsageStat(12, 2), ItemUsageStat(999, 6))),
            counters = Loadable.Ready(listOf(HeroCounter(2, 15, 3, 3), HeroCounter(2, 13, 1, 4), HeroCounter(2, 1, 2, 2), HeroCounter(13, 15, 0, 5))),
        )
        val content = heroesContent(aggregates, selectedHero(aggregates, null), HeroSort.WinRate, extras, reference)
        assertEquals(listOf("Seven", "Haze"), content.rows.map { it.hero.name })
        assertEquals(listOf(13, 2), heroesContent(aggregates, 2, HeroSort.Name, extras, reference).rows.map { it.hero.id })
        val vsRank = assertIs<Loadable.Ready<List<HeroVsRank>>>(content.vsRank).value
        assertEquals(1, vsRank.size, "a hero without rank games has no comparison")
        assertEquals(0.51, vsRank.single().rankAverage)
        val panel = assertNotNull(content.panel)
        val build = assertIs<Loadable.Ready<List<BuildItem>>>(panel.build).value
        assertEquals(listOf(10L, 14L, 12L), build.map { it.item.id }, "shop items by matches; abilities and unknown ids left out")
        assertEquals(7, panel.buildOf, "never \"in 7 of 6\"")
        assertEquals(ItemCategory.Weapon, build.first().item.category)
        assertEquals("basic_magazine.webp", build.first().item.iconUrl, "the shop art, not the glyph")
        val matchups = assertIs<Loadable.Ready<Matchups>>(panel.matchups).value
        assertEquals("Bebop", matchups.best?.enemy?.name)
        assertEquals(3, matchups.best?.wins)
        assertEquals("Haze", matchups.hardest?.enemy?.name)
        assertEquals(3, matchups.hardest?.losses)
        assertEquals(900.0, assertIs<Loadable.Ready<Map<BenchmarkKind, Double>>>(panel.averages).value[BenchmarkKind.SoulsPerMin])
        assertNull(panel.benchmarks.single { it.kind == BenchmarkKind.HeroDamagePerMin }.yours, "no damage numbers yet")
        assertEquals("hero_gigawatt", panel.hero.catalogId)
        assertEquals(13, selectedHero(aggregates, 13))
        assertEquals(2, selectedHero(aggregates, 99), "a hero not played in this view falls back to the most played")
    }

    @Test
    fun partyComesOnlyFromTheApiAndTheFiltersLimitTeammates() {
        val history = listOf(entry(1, 1.0, mode = 4), entry(2, 2.0), entry(3, 3.0), entry(4, 40.0))
        val data = state(history) {
            it.copy(
                mateStats = listOf(
                    PlayedWithStats(7, 3, 3, listOf(1, 2, 4)), PlayedWithStats(8, 2, 1, listOf(2, 3)), PlayedWithStats(9, 1, 1, listOf(3)),
                ),
                enemyStats = listOf(PlayedWithStats(20, 2, 1, listOf(1, 3)), PlayedWithStats(21, 1, 0, listOf(2))),
            )
        }
        val (mates, enemies) = socialRows(data, MatchModeFilter.All, window)
        val party = partyIds(data, MatchModeFilter.All, window, listOf(PlayedWithStats(9, 1, 1, listOf(3))))
        assertEquals(setOf(9L), party, "sharing matches with 7 and 8 is not a party")
        val rows = teammateRows(visibleMates(mates, party, partyOnly = false, MaxTeammates), party, mapOf(7L to PlayerProfile(7, "Mirai")), clock)
        assertEquals(listOf(7L, 8L, 9L), rows.map { it.player.accountId })
        assertEquals(2, rows.first().games, "the 40-day-old match is outside the period")
        assertEquals("Mirai", rows.first().player.name)
        assertEquals(Moment.Yesterday, rows.first().lastPlayed)
        assertEquals(listOf(9L), visibleMates(mates, party, partyOnly = true, MaxTeammates).map { it.otherAccountId })
        assertEquals(listOf(8L), visibleMates(socialRows(data, MatchModeFilter.Normal, window).first, emptySet(), false, MaxTeammates).map { it.otherAccountId },
            "one normal game together is too few, two are kept")
        assertEquals(emptySet(), partyIds(data, MatchModeFilter.Ranked, window, listOf(PlayedWithStats(9, 1, 1, listOf(3)))), "the party game is not ranked")
        val opponents = opponentRows(enemies, emptyMap(), mapOf(20L to 15), reference)
        assertEquals(listOf(20L), opponents.map { it.player.accountId }, "met once is left out")
        assertEquals("Bebop", opponents.single().usualHero?.name)
        assertEquals(1.0, opponents.single().winRate)
    }

    private fun player(id: Long, team: Int, hero: Int, souls: Int, damage: Int, items: List<MatchItemEvent> = emptyList()) = MatchParticipant(
        accountId = id, heroId = hero, team = team, kills = 5, deaths = 3, assists = 7, netWorth = souls, lastHits = 100, denies = 4, level = 28,
        items = items,
        statSnapshots = listOf(600 to souls / 3, 1200 to souls * 2 / 3, 1800 to souls).map { (t, worth) ->
            MatchPlayerStatSnapshot(t, netWorth = worth, playerDamage = damage * t / 1800, playerHealing = 500, bossDamage = 2_000)
        },
    )

    @Test
    fun theMatchPageCountsTheLeadForYourTeamAndReadsOldBuildsHonestly() {
        val items = listOf(
            MatchItemEvent(gameTimeS = 60, itemId = 10, soldTimeS = 700),
            MatchItemEvent(gameTimeS = 100, itemId = 13),
            MatchItemEvent(gameTimeS = 300, itemId = 12, soldTimeS = 1500),
            MatchItemEvent(gameTimeS = 700, itemId = 11),
            MatchItemEvent(gameTimeS = 900, itemId = 999),
        )
        val detail = MatchDetail(
            matchId = 42, durationS = 1800, winningTeam = 1, startTime = (now - 7200).toInt(), gameMode = 1, matchMode = 4,
            players = listOf(
                player(1, 1, 2, 40_000, 30_000, items), player(2, 1, 13, 30_000, 20_000),
                player(3, 0, 15, 35_000, 25_000), player(4, 0, 2, 20_000, 10_000),
            ),
            objectives = listOf(
                MatchObjectiveEvent(teamObjectiveId = 12, team = 0, destroyedTimeS = 1500),
                MatchObjectiveEvent(teamObjectiveId = 13, team = 0, destroyedTimeS = 1600),
                MatchObjectiveEvent(teamObjectiveId = 14, team = 1, destroyedTimeS = 0),
                MatchObjectiveEvent(teamObjectiveId = 3, team = 0, destroyedTimeS = 700),
            ),
            midBoss = listOf(MidBossEvent(teamKilled = 1, destroyedTimeS = 1100)),
            averageBadgeTeam0 = 73, averageBadgeTeam1 = 75,
        )
        val data = state(listOf(entry(41, 2.0, mode = 4, badge = 73), entry(42, 0.1, mode = 4, badge = 74))) { it.copy(matchDetails = mapOf(42L to detail)) }
        val content = assertNotNull(matchDetailContent(data, 42, me, mapOf(1L to PlayerProfile(1, "Mira")), reference, clock))
        val header = content.header
        assertEquals(Outcome.Win, header.outcome)
        assertEquals(Team.Sapphire, header.team)
        assertEquals("Emissary 4", header.lobbyRank?.label)
        assertEquals(RankChange.Up(1, reference.rank(74)!!), header.rankChange)
        assertEquals(30_000, header.heroDamage)
        assertEquals(1, header.heroDamagePlace)
        assertEquals(2_000, header.objectiveDamage)
        assertFalse(header.mostAssists, "everyone has 7")
        assertEquals(Moment.At(Moment.Today, 12, 0), content.started)
        val timeline = assertNotNull(content.timeline)
        assertEquals((40_000 + 30_000 - 35_000 - 20_000).toDouble(), timeline.lead.points.last().value, "Sapphire minus Amber for a Sapphire player")
        assertEquals(0.0, timeline.lead.points.first().value)
        assertEquals(31_250.0, timeline.averageSouls.points.last().value)
        assertEquals(
            listOf(
                ObjectiveEvent(700, ObjectiveKind.Guardian, Lane.Blue, Team.Sapphire),
                ObjectiveEvent(1100, ObjectiveKind.MidBoss, null, null, killedBy = Team.Sapphire),
                ObjectiveEvent(1500, ObjectiveKind.BaseGuardian, Lane.Yellow, Team.Sapphire),
                ObjectiveEvent(1600, ObjectiveKind.BaseGuardian, Lane.Green, Team.Sapphire),
            ),
            timeline.objectives, "every structure the other side owned, and a mid-boss whose Rejuvenator no one is recorded claiming",
        )
        val build = assertNotNull(content.you?.build)
        assertEquals(listOf(10L, 12L, 11L), build.purchases.map { it.item.id }, "abilities and unknown items are not shown as shop purchases")
        assertEquals(listOf(ItemFate.Removed, ItemFate.Removed, ItemFate.Kept), build.purchases.map { it.fate }, "without flags an upgrade and a sale look alike, so neither is claimed")
        assertEquals(1, build.unresolved, "item 999 is missing from the item list and may be a purchase")
        assertEquals(listOf(Team.Sapphire, Team.Amber), content.teams.map { it.team }, "your team first")
        val sapphire = content.teams[0]
        assertEquals(true, sapphire.won)
        assertTrue(sapphire.players.first().isYou)
        assertEquals("Mira", sapphire.players.first().player.name)
        assertEquals(10, sapphire.kills)
        assertFalse(content.spectator)
    }

    @Test
    fun anUnloadedItemListDoesNotLabelPurchasesAndAbilitiesAsUnrecognised() {
        val player = MatchParticipant(1, items = listOf(
            MatchItemEvent(gameTimeS = 60, itemId = 10),
            MatchItemEvent(gameTimeS = 100, itemId = 13),
        ))
        assertNull(buildOf(player, StatsReference(emptyList(), emptyList(), emptyList())))
    }

    @Test
    fun unknownIdsStayUnknown() {
        val unknown = assertNotNull(reference.hero(38))
        assertEquals("", unknown.name)
        assertNull(reference.hero(0))
        assertNull(reference.item(13)?.category)
        assertNull(reference.rank(0))
    }

    @Test
    fun typedAccountsAreReadFromLinksAndSteamIds() {
        assertEquals("76561198041273508", accountQuery(" https://steamcommunity.com/profiles/76561198041273508/ "))
        assertEquals("81007780", accountQuery("[U:1:81007780]"))
        assertEquals("mira", accountQuery("https://steamcommunity.com/id/mira/"), "a custom link is searched by its name")
        assertEquals("Mirai", accountQuery("Mirai"))
        assertEquals(null, accountQuery("   "))
    }

    @Test
    fun showMoreReachesEveryTrackedMatchPastTheFirstTwoHundred() {
        val history = (0 until 250).map { entry(1_000L + it, 0.01 + it * 0.1, won = it % 3 != 0) }
        val data = state(history)
        val page = matchesContent(data, MatchModeFilter.All, window, null, ResultFilter.All, shown = 240, reference = reference, clock = clock)
        assertEquals(240, page.shown)
        assertEquals(240, page.days.sumOf { it.rows.size })
        assertEquals(250, page.total)
        val lastDay = page.days.last()
        val date = clock.date(history.single { it.matchId == lastDay.rows.first().matchId }.startTime!!.toLong())
        assertEquals(history.count { clock.date(it.startTime!!.toLong()) == date }, lastDay.wins + lastDay.losses, "the page's last day counts all its matches")
        val all = matchesContent(data, MatchModeFilter.All, window, null, ResultFilter.All, shown = 260, reference = reference, clock = clock)
        assertEquals(250, all.shown, "the oldest of 250 is reachable")
        assertEquals(1_249L, all.days.last().rows.last().matchId)
    }

    @Test
    fun aLobbyAverageIsAMiddleRankNotTheMeanOfBadgeCodes() {
        assertEquals(71, averageBadge(listOf(66, 71)), "a 6th sub-rank and the next tier's 1st are one step apart, not badge 69")
        assertEquals(74, averageBadge(listOf(73, 75)))
        assertNull(averageBadge(listOf(0)))
        val detail = MatchDetail(42, durationS = 1800, winningTeam = 0, players = listOf(MatchParticipant(1, heroId = 2, team = 0)), averageBadgeTeam0 = 66, averageBadgeTeam1 = 71)
        val content = assertNotNull(matchDetailContent(state(emptyList()) { it.copy(matchDetails = mapOf(42L to detail)) }, 42, me, emptyMap(), reference, clock))
        assertEquals("Emissary 1", content.header.lobbyRank?.label)
    }

    @Test
    fun aHeroFilterKeepsItsNameWhenThePeriodHasNoMatchOfIt() {
        val content = matchesContent(state(listOf(entry(1, 1.0, hero = 2))), MatchModeFilter.All, window, 15, ResultFilter.All, shown = 20, reference = reference, clock = clock)
        assertEquals(0, content.total)
        assertEquals("Bebop", content.selectedHero?.name, "the filter still says what it is filtering for")
        assertEquals(listOf("Seven"), content.heroOptions.map { it.name })
    }

    @Test
    fun aLosingRecordIsNeverCalledTheBestMatchup() {
        val losing = matchups(listOf(HeroCounter(2, 13, 0, 3)), heroId = 2, reference = reference)
        assertNull(losing.best)
        assertEquals("Haze", losing.hardest?.enemy?.name)
        assertEquals(Matchups(null, null), matchups(listOf(HeroCounter(2, 13, 2, 4)), heroId = 2, reference = reference), "an even record is neither")
    }

    @Test
    fun aRankChangeNamesWhereItLandedOrSaysItIsUnknown() {
        assertEquals(RankChange.Up(2, reference.rank(74)!!), rankChange(MatchKind.Ranked, 72, 74, reference))
        assertEquals(RankChange.NoChange, rankChange(MatchKind.Ranked, 74, 74, reference))
        assertEquals(RankChange.Unknown, rankChange(MatchKind.Ranked, 72, 0, reference), "a badge that was not reported is no change of 43 sub-ranks")
        assertEquals(RankChange.Unknown, rankChange(MatchKind.Ranked, 0, 74, reference))
        assertEquals(RankChange.Unranked, rankChange(MatchKind.Normal, 72, 74, reference))
    }

    @Test
    fun placementMatchesSayTheyAreCalibratingUntilOneReportsABadge() {
        val data = state(listOf(
            entry(1, 3.0, mode = 4, badge = 0, calibration = 6), entry(2, 2.0, mode = 4, badge = 74, calibration = 7),
            entry(3, 1.0, mode = 4, badge = 74, calibration = 0),
        ))
        val rows = matchesContent(data, MatchModeFilter.Ranked, window, null, ResultFilter.All, 20, reference, clock).days.flatMap { it.rows }
        assertEquals(
            listOf(RankChange.NoChange, RankChange.Placed(reference.rank(74)!!), RankChange.Calibrating),
            rows.map { it.rankChange }, "placement games are not unknown changes",
        )
    }

    @Test
    fun aViewWithoutRankedMatchesStillSaysWhenTheRankWasLastReported() {
        val lastRanked = (now - 39 * day)
        val data = state(listOf(entry(1, 50.0, mode = 4, badge = 74), entry(2, 1.0))) {
            it.copy(rank = RankStatus(74, 7, 4, lastMatchStartTime = lastRanked.toInt()))
        }
        val header = assertNotNull(headerOf(data, true, null, StatsPeriod.Month, window, null, null, reference, clock))
        assertNull(header.rank?.change, "no ranked match in the last 30 days")
        assertEquals(clock.dayOf(lastRanked), header.rank?.lastRanked)
        val overview = overviewContent(data, MatchModeFilter.All, window, noExtras, reference, clock)
        assertNull(overview.rank)
        assertEquals(RankedAt(reference.rank(74)!!, clock.dayOf(lastRanked)), overview.lastRanked)
    }

    @Test
    fun theHeaderSaysWhenTheMatchHistoryArrivedAndGivesAnEarlierMatchItsDay() {
        val today = clock.date(now).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val lateYesterday = (today - day + 21 * 3600 + 40 * 60).toInt()
        val data = state(listOf(entry(1, 1.0).copy(startTime = lateYesterday))) { it.copy(fetchedAt = mapOf("history" to now - 3 * 3600)) }
        val header = assertNotNull(headerOf(data, true, null, StatsPeriod.Month, window, null, null, reference, clock))
        assertEquals(Moment.HoursAgo(3), header.updated, "the profile arriving since is not an update of the matches")
        assertEquals(Moment.At(Moment.Yesterday, 21, 40), header.lastMatch, "yesterday's 21:40 does not read as today's")
    }
}
