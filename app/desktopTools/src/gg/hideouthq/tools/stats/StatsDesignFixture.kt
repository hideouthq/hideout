package gg.hideouthq.tools.stats

import androidx.compose.ui.graphics.Color
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.ItemAsset
import gg.hideouthq.core.data.deadlockstats.MatchDeathEvent
import gg.hideouthq.core.data.deadlockstats.MatchDetail
import gg.hideouthq.core.data.deadlockstats.MatchHistoryEntry
import gg.hideouthq.core.data.deadlockstats.MatchItemEvent
import gg.hideouthq.core.data.deadlockstats.MatchObjectiveEvent
import gg.hideouthq.core.data.deadlockstats.MatchParticipant
import gg.hideouthq.core.data.deadlockstats.MatchPlayerRank
import gg.hideouthq.core.data.deadlockstats.MatchPlayerStatSnapshot
import gg.hideouthq.core.data.deadlockstats.MidBossEvent
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.RankAsset
import gg.hideouthq.core.model.Hero
import gg.hideouthq.feature.stats.AxisLabel
import gg.hideouthq.feature.stats.BenchmarkKind
import gg.hideouthq.feature.stats.BenchmarkRow
import gg.hideouthq.feature.stats.BuildItem
import gg.hideouthq.feature.stats.Change
import gg.hideouthq.feature.stats.ChangeUnit
import gg.hideouthq.feature.stats.FeaturedHero
import gg.hideouthq.feature.stats.HeaderRank
import gg.hideouthq.feature.stats.HeroLine
import gg.hideouthq.feature.stats.HeroPanel
import gg.hideouthq.feature.stats.HeroStatRow
import gg.hideouthq.feature.stats.HeroVisual
import gg.hideouthq.feature.stats.HeroVsRank
import gg.hideouthq.feature.stats.HeroesContent
import gg.hideouthq.feature.stats.ItemCategory
import gg.hideouthq.feature.stats.ItemVisual
import gg.hideouthq.feature.stats.LocalAccount
import gg.hideouthq.feature.stats.MatchDay
import gg.hideouthq.feature.stats.MatchDetailContent
import gg.hideouthq.feature.stats.MatchKind
import gg.hideouthq.feature.stats.MatchRowUi
import gg.hideouthq.feature.stats.MatchesContent
import gg.hideouthq.feature.stats.Matchup
import gg.hideouthq.feature.stats.Matchups
import gg.hideouthq.feature.stats.MetricKind
import gg.hideouthq.feature.stats.MetricTile
import gg.hideouthq.feature.stats.Moment
import gg.hideouthq.feature.stats.OpponentRow
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.OverviewContent
import gg.hideouthq.feature.stats.PlayerVisual
import gg.hideouthq.feature.stats.RankChange
import gg.hideouthq.feature.stats.RankChart
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.RankPoint
import gg.hideouthq.feature.stats.RankVisual
import gg.hideouthq.feature.stats.SocialContent
import gg.hideouthq.feature.stats.StatsHeader
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.TeammateRow
import gg.hideouthq.feature.stats.Tint
import gg.hideouthq.feature.stats.TrackedTotals
import gg.hideouthq.feature.stats.WinRateTile
import gg.hideouthq.feature.stats.data.matchDetailContent
import gg.hideouthq.feature.stats.heroInitials
import java.time.ZoneOffset
import kotlin.math.round

object StatsDesignFixture {
    private fun tint(container: Long, content: Long) = Tint(Color(container), Color(content))

    private fun hero(id: Int, name: String, container: Long, content: Long, catalogId: String) =
        HeroVisual(id, name, heroInitials(name), tint = tint(container, content), catalogId = catalogId)

    val Seven = hero(2, "Seven", 0xFF26324A, 0xFFB4C7E6, "hero_gigawatt")
    val Silver = hero(80, "Silver", 0xFF3A2F2A, 0xFFE2C2A8, "hero_werewolf")
    val Haze = hero(13, "Haze", 0xFF352A3C, 0xFFD6C2E6, "hero_haze")
    val Infernus = hero(1, "Infernus", 0xFF41271F, 0xFFF0B394, "hero_inferno")
    val Abrams = hero(6, "Abrams", 0xFF2C3328, 0xFFC6D6B0, "hero_atlas")
    val Ivy = hero(20, "Ivy", 0xFF263A2E, 0xFFA8DDB8, "hero_tengu")
    val Wraith = hero(7, "Wraith", 0xFF2E2840, 0xFFC9BCF0, "hero_wraith")
    val Lash = hero(31, "Lash", 0xFF3A2A2A, 0xFFE8B4B0, "hero_lash")
    val McGinnis = hero(8, "McGinnis", 0xFF3A3326, 0xFFE6D0A0, "hero_forge")
    val Vindicta = hero(3, "Vindicta", 0xFF2A2A36, 0xFFC4C4E0, "hero_hornet")
    val Bebop = hero(15, "Bebop", 0xFF3A3424, 0xFFE3D3A0, "hero_bebop")
    val Kelvin = hero(12, "Kelvin", 0xFF243640, 0xFFA8D4E6, "hero_kelvin")
    val MoKrill = hero(18, "Mo & Krill", 0xFF30302A, 0xFFD6D2B0, "hero_krill")

    const val AccountId = 81_007_780L
    const val MatchId = 41_236_118L

    private fun rank(sub: Int) = RankVisual(70 + sub, "Archon")

    private fun player(id: Long, name: String, container: Long, content: Long) = PlayerVisual(id, name, tint = tint(container, content))

    private val green = 0xFF2B3A31 to 0xFFB5CCBA
    private val blue = 0xFF2A3440 to 0xFFB4C7D6
    private val grey = 0xFF33303A to 0xFFCBC4CB
    private val clay = 0xFF3D2F29 to 0xFFD4B5A6
    private val sand = 0xFF3A3527 to 0xFFD8C9A8
    private val rose = 0xFF3A2D30 to 0xFFD1C3C6

    private fun player(id: Long, name: String, colours: Pair<Long, Long>) = player(id, name, colours.first, colours.second)

    val Mirai = player(1001, "Mirai", green)
    val Lowtide = player(1002, "lowtide", blue)
    val Juniper = player(1003, "Juniper", grey)
    val Kettle = player(1004, "kettle", clay)
    val Sable = player(1005, "Sable", sand)
    val Nori = player(1006, "nori", rose)
    val Ramen = player(1007, "ramen", green)
    val PunkGhost = player(2001, "punk_ghost", grey)
    val OxMoth = player(2002, "0xMoth", sand)
    val Velvet = player(2003, "Velvet", rose)
    val Dune = player(2004, "dune", green)
    val Okto = player(2005, "okto", blue)
    val Mira = PlayerVisual(AccountId, "Mira")

    val header = StatsHeader(
        player = Mira, isYou = true, lastMatch = Moment.HoursAgo(2), updated = Moment.JustNow, mostPlayed = Seven,
        winStreak = 2, lossStreak = null, trackedSince = Moment.MonthYear(2, 2025),
        rank = HeaderRank(rank(4), change = 2, changePeriod = StatsPeriod.Month, topPercent = 22),
        totals = TrackedTotals(248, 139, 109, 139.0 / 248, 3.1, 186 * 3600L, 21),
    )

    private fun row(
        id: Long, hero: HeroVisual, outcome: Outcome, kind: MatchKind, duration: String, level: Int, k: Int, d: Int, a: Int,
        souls: Int, damage: Int, lastHits: Int, denies: Int, change: RankChange, at: Moment,
    ): MatchRowUi {
        val (minutes, seconds) = duration.split(":").map { it.toInt() }
        return MatchRowUi(
            id, hero, outcome, kind, minutes * 60 + seconds, level, k, d, a, (k + a).toDouble() / maxOf(d, 1),
            souls, damage, lastHits, denies, change, at,
        )
    }

    private val win = Outcome.Win
    private val loss = Outcome.Loss
    private val ranked = MatchKind.Ranked
    private val normal = MatchKind.Normal

    val matchRows: List<MatchRowUi> = listOf(
        row(MatchId, Seven, win, ranked, "34:12", 31, 12, 3, 9, 41_210, 38_420, 214, 18, RankChange.Up(1, rank(4)), Moment.HoursAgo(2)),
        row(41_236_002, Silver, win, ranked, "28:40", 27, 9, 5, 14, 33_800, 29_600, 176, 9, RankChange.NoChange, Moment.HoursAgo(3)),
        row(41_236_003, Seven, loss, ranked, "38:05", 29, 6, 8, 11, 36_100, 27_200, 198, 12, RankChange.NoChange, Moment.HoursAgo(5)),
        row(41_236_004, Haze, win, normal, "31:22", 30, 15, 4, 6, 39_500, 36_900, 231, 21, RankChange.Unranked, Moment.ClockTime(21, 40)),
        row(41_236_005, Infernus, loss, normal, "26:48", 24, 4, 7, 9, 24_300, 19_800, 143, 6, RankChange.Unranked, Moment.ClockTime(20, 55)),
        row(41_236_006, Seven, win, ranked, "33:10", 30, 10, 2, 12, 38_000, 33_100, 207, 15, RankChange.Up(1, rank(3)), Moment.ClockTime(22, 18)),
        row(41_236_007, Abrams, win, ranked, "35:44", 31, 7, 4, 17, 35_200, 26_400, 188, 7, RankChange.NoChange, Moment.ClockTime(21, 30)),
        row(41_236_008, Seven, loss, ranked, "29:58", 26, 5, 6, 8, 27_700, 22_000, 161, 10, RankChange.Down(1, rank(2)), Moment.ClockTime(20, 47)),
        row(41_236_009, Haze, win, normal, "30:31", 29, 13, 5, 7, 36_400, 34_200, 219, 16, RankChange.Unranked, Moment.ClockTime(19, 12)),
        row(41_236_010, Ivy, win, normal, "32:06", 28, 2, 3, 24, 29_900, 14_500, 120, 4, RankChange.Unranked, Moment.ClockTime(18, 35)),
    )

    private val recent = matchRows.take(5) + matchRows[5]

    private fun series(ys: List<Double>, averageY: Double, benchmark: Double, perUnit: Double) = ys.map { benchmark + (averageY - it) * perUnit }

    private val kdaYs = listOf(28.22, 22.16, 29.95, 16.11, 23.89, 10.92, 23.03, 26.49, 15.24, 20.43, 30.81, 7.46, 17.84, 25.62, 13.51, 21.3, 15.24, 24.76, 9.19, 12.65)
    private val soulsYs = listOf(31.73, 25.33, 28.53, 18.93, 23.2, 13.6, 30.67, 21.07, 16.8, 22.13, 26.4, 10.4, 14.67, 20.0, 11.47, 17.87, 8.27, 15.73, 9.33, 7.2)
    private val damageYs = listOf(16.0, 8.67, 21.33, 12.0, 17.33, 29.33, 13.33, 18.67, 23.33, 10.67, 28.0, 22.0, 16.0, 25.33, 20.67, 30.0, 17.33, 26.67, 22.67, 18.67)
    private val accuracyYs = listOf(26.15, 21.23, 28.62, 18.77, 23.69, 16.31, 26.15, 21.23, 13.85, 23.69, 18.77, 11.38, 21.23, 16.31, 23.69, 13.85, 18.77, 11.38, 16.31, 8.92)

    private val rankYs = listOf(
        78.83, 83.12, 76.68, 72.39, 80.98, 74.54, 65.95, 61.66, 68.1, 59.51, 55.22, 57.37, 48.78, 44.49, 40.2, 46.63,
        38.05, 31.61, 25.17, 18.73, 23.02, 29.46, 35.9, 42.34, 38.05, 40.2, 33.76, 35.9, 29.46, 31.61, 33.76,
    )

    private val rankChart: RankChart = run {
        val start = 1_789_430_400L
        val span = 30 * 86_400L
        val points = rankYs.mapIndexed { i, y ->
            val level = 2 + (85.27 - y) / 21.46
            val badge = 70 + round(level).toInt().coerceIn(2, 5)
            RankPoint(start + span * i / 30, badge, position = 7 * 6.0 + (level - 1))
        }
        val peak = points[19]
        RankChart(
            points = points, from = rank(2), to = rank(4), peak = rank(5), peakAt = peak, peakDay = Moment.Date(3, 10), rankedMatches = 31,
            startEpoch = start, endEpoch = start + span,
            axisLabels = listOf(
                AxisLabel(0f, Moment.Date(15, 9)), AxisLabel(0.233f, Moment.Date(22, 9)), AxisLabel(0.467f, Moment.Date(29, 9)),
                AxisLabel(0.7f, Moment.Date(6, 10)), AxisLabel(1f, Moment.Today),
            ),
            names = (72..75).associateWith { "Archon ${it - 70}" },
        )
    }

    val teammates: List<TeammateRow> = listOf(
        TeammateRow(Mirai, true, Moment.Today, 18, 13, 0.72, 3.8),
        TeammateRow(Lowtide, true, Moment.Weekday(6), 12, 8, 0.67, 3.5),
        TeammateRow(Juniper, false, Moment.Yesterday, 9, 4, 0.44, 2.6),
        TeammateRow(Kettle, false, Moment.Date(2, 10), 7, 5, 0.71, 3.9),
        TeammateRow(Sable, false, Moment.Date(28, 9), 6, 3, 0.50, 3.0),
        TeammateRow(Nori, false, Moment.Date(24, 9), 5, 3, 0.60, 3.2),
        TeammateRow(Ramen, false, Moment.Date(20, 9), 4, 1, 0.25, 2.4),
    )

    val opponents: List<OpponentRow> = listOf(
        OpponentRow(PunkGhost, Infernus, 7, 0.71),
        OpponentRow(OxMoth, Vindicta, 6, 0.33),
        OpponentRow(Velvet, Bebop, 5, 0.60),
        OpponentRow(Dune, MoKrill, 4, 0.75),
        OpponentRow(Okto, Haze, 4, 0.25),
    )

    val overview = OverviewContent(
        winRate = WinRateTile(
            winRate = 37.0 / 64, wins = 37, losses = 27, change = Change(4.0, ChangeUnit.Points),
            form = "LWWLWLWWLWWWLWWLWLWW".map { it == 'W' },
        ),
        metrics = listOf(
            MetricTile(MetricKind.Kda, 3.4, listOf(8.2, 4.9, 8.6), Change(0.3, ChangeUnit.Plain), series(kdaYs, 23.89, 2.9, 0.12)),
            MetricTile(MetricKind.SoulsPerMin, 1124.0, listOf(36_400.0), Change(6.0, ChangeUnit.Percent), series(soulsYs, 25.33, 1040.0, 15.0)),
            MetricTile(MetricKind.DamagePerMin, 1842.0, listOf(57_600.0), Change(-3.0, ChangeUnit.Percent), series(damageYs, 30.0, 1690.0, 25.0)),
            MetricTile(MetricKind.Accuracy, 0.41, listOf(0.17), Change(2.0, ChangeUnit.Points), series(accuracyYs, 26.15, 0.38, 0.006)),
        ),
        cohort = RankCohort("Archon", rankedOnly = false),
        averages = Loadable.Ready(mapOf(MetricKind.Kda to 2.9, MetricKind.SoulsPerMin to 1040.0, MetricKind.DamagePerMin to 1690.0, MetricKind.Accuracy to 0.38)),
        recent = recent,
        featured = FeaturedHero(Seven, 22, 12 * 3600L, 0.68, 4.1, 1210.0),
        heroes = listOf(HeroLine(Haze, 14, 3.6, 0.57), HeroLine(Silver, 11, 3.0, 0.55), HeroLine(Infernus, 8, 2.2, 0.38)),
        rank = rankChart,
        teammates = Loadable.Ready(teammates.take(4)),
        names = Loadable.Ready(Unit),
    )

    private val days = listOf(
        MatchDay(Moment.Today, 2, 1, matchRows.subList(0, 3)),
        MatchDay(Moment.Yesterday, 1, 1, matchRows.subList(3, 5)),
        MatchDay(Moment.WeekdayDate(6, 11, 10), 2, 1, matchRows.subList(5, 8)),
        MatchDay(Moment.WeekdayDate(5, 10, 10), 2, 0, matchRows.subList(8, 10)),
    )

    val heroOptions = listOf(Seven, Haze, Silver, Infernus, Abrams, Ivy)

    val matches = MatchesContent(heroOptions, selectedHero = null, total = 64, wins = 37, losses = 27, days = days, shown = 10)

    private fun item(id: Long, name: String, category: ItemCategory, cost: Long, code: String? = null) = ItemVisual(id, name, category, cost = cost, code = code)

    private val extraCharge = item(1, "Extra Charge", ItemCategory.Spirit, 800)
    private val extraHealth = item(2, "Extra Health", ItemCategory.Vitality, 800)
    private val headshotBooster = item(3, "Headshot Booster", ItemCategory.Weapon, 800)
    private val mysticBurst = item(4, "Mystic Burst", ItemCategory.Spirit, 800)
    private val extraSpirit = item(5, "Extra Spirit", ItemCategory.Spirit, 800, code = "Sp")
    private val enduringSpirit = item(6, "Enduring Spirit", ItemCategory.Vitality, 1600)
    private val improvedSpirit = item(7, "Improved Spirit", ItemCategory.Spirit, 1600)
    private val superiorDuration = item(8, "Superior Duration", ItemCategory.Spirit, 3200)
    private val escalatingExposure = item(9, "Escalating Exposure", ItemCategory.Spirit, 3200)
    private val boundlessSpirit = item(10, "Boundless Spirit", ItemCategory.Spirit, 6200)
    private val superiorStamina = item(11, "Superior Stamina", ItemCategory.Vitality, 3200)

    val heroes = HeroesContent(
        rows = listOf(
            HeroStatRow(Seven, 22, 15, 12 * 3600L, 0.68, 4.1, 1210.0, 2010.0, 0.44),
            HeroStatRow(Haze, 14, 8, 7 * 3600L, 0.57, 3.6, 1160.0, 1980.0, 0.41),
            HeroStatRow(Silver, 11, 6, 6 * 3600L, 0.55, 3.0, 1090.0, 1760.0, 0.38),
            HeroStatRow(Infernus, 8, 3, 4 * 3600L, 0.38, 2.2, 980.0, 1640.0, 0.36),
            HeroStatRow(Abrams, 5, 3, 3 * 3600L, 0.60, 2.8, 1020.0, 1450.0, 0.47),
            HeroStatRow(Ivy, 4, 2, 2 * 3600L, 0.50, 4.6, 940.0, 980.0, 0.33),
        ),
        cohort = RankCohort("Archon", rankedOnly = false),
        vsRank = Loadable.Ready(
            listOf(
                HeroVsRank(Seven, 0.68, 0.51), HeroVsRank(Haze, 0.57, 0.49), HeroVsRank(Silver, 0.55, 0.52),
                HeroVsRank(Infernus, 0.38, 0.50), HeroVsRank(Abrams, 0.60, 0.51), HeroVsRank(Ivy, 0.50, 0.51),
            ),
        ),
        panel = HeroPanel(
            hero = Seven, matches = 22, winRate = 0.68, playedSeconds = 12 * 3600L,
            benchmarks = listOf(
                BenchmarkRow(BenchmarkKind.SoulsPerMin, 1210.0),
                BenchmarkRow(BenchmarkKind.HeroDamagePerMin, 2010.0),
                BenchmarkRow(BenchmarkKind.Accuracy, 0.44),
                BenchmarkRow(BenchmarkKind.CritRate, 0.21),
                BenchmarkRow(BenchmarkKind.ObjectiveDamagePerMin, 380.0),
            ),
            averages = Loadable.Ready(
                mapOf(
                    BenchmarkKind.SoulsPerMin to 1065.0, BenchmarkKind.HeroDamagePerMin to 1830.0, BenchmarkKind.Accuracy to 0.41,
                    BenchmarkKind.CritRate to 0.19, BenchmarkKind.ObjectiveDamagePerMin to 412.0,
                ),
            ),
            build = Loadable.Ready(
                listOf(
                    BuildItem(extraCharge, 22), BuildItem(mysticBurst, 21), BuildItem(enduringSpirit, 20),
                    BuildItem(superiorDuration, 18), BuildItem(escalatingExposure, 15), BuildItem(boundlessSpirit, 11),
                ),
            ),
            buildOf = 22,
            matchups = Loadable.Ready(Matchups(best = Matchup(Bebop, 5, 0), hardest = Matchup(Haze, 1, 3))),
        ),
    )

    val social = SocialContent(Loadable.Ready(teammates), opponents, names = Loadable.Ready(Unit), usualHeroes = Loadable.Ready(Unit))

    private fun at(time: String): Int = time.split(":").map { it.toInt() }.let { (m, s) -> m * 60 + s }

    private const val Duration = 2052

    private const val Now = 1_791_914_400L
    private const val Started = 1_791_901_920

    private class Line(val player: PlayerVisual, val hero: HeroVisual, val team: Int, val slot: Int, val k: Int, val d: Int, val a: Int, val souls: Int, val damage: Int, val healing: Int)

    /** Player slots are not in roster order, as in real metadata. */
    private val lines = listOf(
        Line(Mira, Seven, 0, 7, 12, 3, 9, 41_210, 38_420, 4_120),
        Line(Mirai, Abrams, 0, 2, 7, 5, 15, 35_600, 29_100, 12_800),
        Line(Kettle, Wraith, 0, 11, 10, 6, 8, 37_300, 34_000, 1_200),
        Line(Nori, Lash, 0, 4, 8, 7, 10, 33_000, 27_900, 2_000),
        Line(Sable, McGinnis, 0, 9, 4, 5, 13, 30_400, 22_600, 3_300),
        Line(Lowtide, Ivy, 0, 1, 3, 4, 22, 31_900, 18_700, 21_400),
        Line(Okto, Haze, 1, 12, 9, 6, 5, 36_800, 31_200, 900),
        Line(PunkGhost, Infernus, 1, 5, 7, 8, 9, 32_100, 28_400, 2_600),
        Line(OxMoth, Vindicta, 1, 3, 7, 9, 4, 30_200, 25_000, 600),
        Line(Velvet, Bebop, 1, 10, 3, 7, 11, 28_500, 19_800, 1_400),
        Line(Ramen, Kelvin, 1, 6, 2, 6, 14, 27_900, 16_200, 18_900),
        Line(Dune, MoKrill, 1, 8, 2, 8, 9, 26_000, 14_700, 4_800),
    )

    private val heroColours = mapOf(
        2 to "#CC861E", 6 to "#2092AE", 7 to "#944D78", 31 to "#4174BB", 8 to "#23549D", 20 to "#9D8CA9",
        13 to "#AC6133", 1 to "#C93C26", 3 to "#A2C7E5", 15 to "#9F4734", 12 to "#74ABBC", 18 to "#C58428",
    )

    private val sevenAbilities = listOf("ability_seven_ball" to "Lightning Ball", "ability_seven_charge" to "Static Charge", "ability_seven_surge" to "Power Surge", "ability_seven_storm" to "Storm Cloud")

    private val catalog = lines.map { it.hero }.distinctBy { it.id }.map { hero ->
        Hero(
            hero.catalogId!!, hero.name, numericId = hero.id, colorHex = heroColours[hero.id],
            signatureAbilities = if (hero == Seven) sevenAbilities.mapIndexed { i, (className, _) -> i + 1 to className }.toMap() else emptyMap(),
        )
    }

    private val shop = listOf(extraCharge, extraHealth, headshotBooster, mysticBurst, extraSpirit, enduringSpirit, improvedSpirit, superiorDuration, escalatingExposure, boundlessSpirit, superiorStamina)

    private val assets = shop.map { item ->
        ItemAsset(item.id, "upgrade_${item.name.lowercase().replace(' ', '_')}", item.name, category = item.category!!.name.lowercase(), cost = item.cost)
    } + sevenAbilities.mapIndexed { i, (className, name) -> ItemAsset(101L + i, className, name) }

    private fun purchase(time: String, item: ItemVisual, gone: String? = null, upgraded: Boolean = false) =
        MatchItemEvent(at(time), item.id, upgradeId = 1, soldTimeS = gone?.let(::at) ?: 0, flags = if (upgraded) 1L else 0L)

    private fun ability(time: String, slot: Int, tier: Int) =
        MatchItemEvent(at(time), 100L + slot, upgradeId = if (tier == 0) 0 else 1_000 + slot * 10 + tier, upgradeInfo = listOf(1L, 3L, 7L, 15L)[tier] shl 16)

    private val yourItems = listOf(
        purchase("0:00", extraCharge), purchase("0:36", extraHealth, gone = "24:00"), purchase("2:18", headshotBooster),
        purchase("4:12", mysticBurst), purchase("7:30", extraSpirit, gone = "13:48", upgraded = true), purchase("10:06", enduringSpirit),
        purchase("13:48", improvedSpirit), purchase("17:18", superiorDuration), purchase("21:00", escalatingExposure),
        purchase("26:42", boundlessSpirit), purchase("30:18", superiorStamina),
        ability("0:20", 1, 0), ability("1:40", 2, 0), ability("2:50", 1, 1), ability("4:05", 3, 0), ability("6:30", 1, 2),
        ability("9:10", 4, 0), ability("12:00", 2, 1), ability("15:40", 1, 3), ability("19:20", 4, 1), ability("23:30", 2, 2), ability("28:10", 4, 2),
    )

    private fun othersItems(index: Int) = (0 until 6 + index % 4).map { n ->
        val item = shop[(index * 3 + n * 2) % shop.size]
        MatchItemEvent(240 + n * 260 + index * 7, item.id, upgradeId = 1, soldTimeS = 0, flags = 0)
    }

    private val deaths: Map<Long, List<MatchDeathEvent>> = run {
        fun expand(team: Int, count: (Line) -> Int): List<Line> {
            val left = lines.filter { it.team == team }.associateWith(count).toMutableMap()
            return buildList { while (left.values.any { it > 0 }) left.keys.forEach { line -> if (left.getValue(line) > 0) { add(line); left[line] = left.getValue(line) - 1 } } }
        }
        val pairs = expand(0) { it.k }.zip(expand(1) { it.d }) + expand(1) { it.k }.zip(expand(0) { it.d })
        val ordered = pairs.indices.sortedBy { (it * 37) % pairs.size }.map(pairs::get)
        ordered.mapIndexed { i, (killer, victim) ->
            val time = 70 + i * (Duration - 140) / ordered.size
            victim.player.accountId to MatchDeathEvent(time, killerPlayerSlot = killer.slot, deathDurationS = 8 + time / 30)
        }.groupBy({ it.first }, { it.second })
    }

    private fun snapshots(line: Line): List<MatchPlayerStatSnapshot> =
        listOf(180, 360, 540, 720, 900, 1200, 1500, 1800, Duration).map { time ->
            val share = time.toDouble() / Duration
            val souls = line.souls * Math.pow(share, if (line.team == 0) 1.25 else 1.0)
            val last = time == Duration
            MatchPlayerStatSnapshot(
                time, netWorth = if (last) line.souls else souls.toInt(), playerDamage = (line.damage * Math.pow(share, 1.3)).toInt(),
                playerHealing = if (last) line.healing else null, bossDamage = if (last) line.damage / 3 else null,
                playerDamageTaken = if (last) 20_000 + line.d * 3_100 else null, damageMitigated = if (last) 9_000 + line.a * 400 else null,
                shotsHit = if (last) 300 + line.k * 20 else null, shotsMissed = if (last) 420 - line.k * 9 else null,
                heroBulletsHit = if (last) 150 + line.k * 6 else null, heroBulletsHitCrit = if (last) 20 + line.k * 3 else null,
                creepKills = if (last) line.souls / 300 else null, neutralKills = if (last) 10 + line.a else null,
            )
        }

    private val objectives = listOf(
        MatchObjectiveEvent(teamObjectiveId = 1, team = 0, destroyedTimeS = 512), MatchObjectiveEvent(teamObjectiveId = 3, team = 1, destroyedTimeS = 640),
        MatchObjectiveEvent(teamObjectiveId = 4, team = 1, destroyedTimeS = 701), MatchObjectiveEvent(teamObjectiveId = 4, team = 0, destroyedTimeS = 760),
        MatchObjectiveEvent(teamObjectiveId = 7, team = 1, destroyedTimeS = 980), MatchObjectiveEvent(teamObjectiveId = 5, team = 0, destroyedTimeS = 1105),
        MatchObjectiveEvent(teamObjectiveId = 8, team = 1, destroyedTimeS = 1290), MatchObjectiveEvent(teamObjectiveId = 15, team = 1, destroyedTimeS = 1884),
        MatchObjectiveEvent(teamObjectiveId = 14, team = 1, destroyedTimeS = 1932), MatchObjectiveEvent(teamObjectiveId = 10, team = 1, destroyedTimeS = 1968),
        MatchObjectiveEvent(teamObjectiveId = 11, team = 1, destroyedTimeS = 1990), MatchObjectiveEvent(teamObjectiveId = 9, team = 1, destroyedTimeS = 2012),
        MatchObjectiveEvent(teamObjectiveId = 0, team = 1, destroyedTimeS = 2050), MatchObjectiveEvent(teamObjectiveId = 12, team = 0, destroyedTimeS = 0),
    )

    private val detail = MatchDetail(
        matchId = MatchId, durationS = Duration, matchOutcome = 0, winningTeam = 0, startTime = Started, gameMode = 1, matchMode = 4,
        players = lines.mapIndexed { index, line ->
            MatchParticipant(
                accountId = line.player.accountId, heroId = line.hero.id, playerSlot = line.slot, team = line.team,
                kills = line.k, deaths = line.d, assists = line.a, netWorth = line.souls,
                lastHits = if (line.player == Mira) 214 else 120 + line.souls / 400, denies = if (line.player == Mira) 18 else 3 + index % 7,
                level = if (line.player == Mira) 31 else 25 + line.souls / 6_000, playerMatchOutcome = if (line.team == 0) 1 else 2,
                rank = if (line.player == Mira) MatchPlayerRank(initialDisplayRank = 73) else null,
                items = if (line.player == Mira) yourItems else othersItems(index),
                statSnapshots = snapshots(line),
                deathDetails = deaths[line.player.accountId].orEmpty(),
            )
        },
        objectives = objectives,
        midBoss = listOf(MidBossEvent(teamKilled = 0, teamClaimed = 0, destroyedTimeS = 1362)),
        averageBadgeTeam0 = 74, averageBadgeTeam1 = 74,
    )

    val match: MatchDetailContent = checkNotNull(
        matchDetailContent(
            DeadlockStatsState(
                accountId = AccountId,
                matchHistory = listOf(MatchHistoryEntry(MatchId, heroId = Seven.id, matchMode = 4, startTime = Started, rankedDisplayBadge = 74)),
                matchDetails = mapOf(MatchId to detail),
                itemAssets = assets,
                rankAssets = listOf(RankAsset(7, "Archon", "#D8C9A8")),
            ),
            MatchId, AccountId, lines.associate { it.player.accountId to PlayerProfile(it.player.accountId, it.player.name) }, catalog, Now, ZoneOffset.UTC,
        ),
    )

    val suggestion = LocalAccount(accountId = AccountId, steamId64 = 76_561_198_041_273_508, name = "Mira")
}
