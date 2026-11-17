package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.ItemAsset
import gg.hideouthq.core.data.deadlockstats.MatchDeathEvent
import gg.hideouthq.core.data.deadlockstats.MatchDetail
import gg.hideouthq.core.data.deadlockstats.MatchItemEvent
import gg.hideouthq.core.data.deadlockstats.MatchObjectiveEvent
import gg.hideouthq.core.data.deadlockstats.MatchParticipant
import gg.hideouthq.core.data.deadlockstats.MatchPlayerStatSnapshot
import gg.hideouthq.core.data.deadlockstats.MidBossEvent
import gg.hideouthq.core.model.Hero
import gg.hideouthq.feature.stats.AbilityRow
import gg.hideouthq.feature.stats.AbilityStep
import gg.hideouthq.feature.stats.CurvePoint
import gg.hideouthq.feature.stats.GraphView
import gg.hideouthq.feature.stats.ItemFate
import gg.hideouthq.feature.stats.Lane
import gg.hideouthq.feature.stats.MatchDeath
import gg.hideouthq.feature.stats.MatchDetailContent
import gg.hideouthq.feature.stats.MatchKind
import gg.hideouthq.feature.stats.MatchStat
import gg.hideouthq.feature.stats.ObjectiveEvent
import gg.hideouthq.feature.stats.ObjectiveKind
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.RankedStat
import gg.hideouthq.feature.stats.Team
import gg.hideouthq.feature.stats.components.deadBands
import gg.hideouthq.feature.stats.components.marksOf
import gg.hideouthq.feature.stats.components.minuteReadout
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchDetailProjectionTest {
    private val clock = StatsClock(1_792_270_000L, ZoneOffset.UTC)
    private val reference = StatsReference(listOf(RealMatch.violet), RealMatch.items, emptyList())

    private fun project(detail: MatchDetail, viewer: Long, reference: StatsReference = this.reference): MatchDetailContent =
        assertNotNull(matchDetailContent(DeadlockStatsState(accountId = viewer, matchDetails = mapOf(detail.matchId to detail)), detail.matchId, viewer, emptyMap(), reference, clock))

    private val real by lazy { project(RealMatch.detail, RealMatch.Mugi) }
    private val mugi by lazy { assertNotNull(real.you) }

    @Test
    fun theHeaderIsTheViewersFromTheirRecordAndLastSnapshot() {
        val header = real.header
        assertEquals(Outcome.Win, header.outcome)
        assertEquals(MatchKind.Normal, header.kind)
        assertEquals(2471, header.durationS)
        assertEquals(Team.Sapphire, header.team)
        assertEquals(34, header.level)
        assertEquals(listOf(7, 4, 29), listOf(header.kills, header.deaths, header.assists), "the scoreboard's, though the last snapshot says 5 deaths")
        assertEquals(9.0, header.kda)
        assertEquals(43_394, header.souls)
        assertEquals(60_544, header.heroDamage)
        assertEquals(2, header.heroDamagePlace, "second only to 72,912")
        assertEquals(listOf(109, 0, 6_264, 20_938), listOf(header.lastHits, header.denies, header.objectiveDamage, header.healing))
        assertTrue(header.mostAssists, "29 assists, the most in the match")
        assertNull(header.lobbyRank, "a normal match has no lobby badge")
    }

    @Test
    fun teamsAreTheViewersFirstWithTotalsFromPlayerRecordsAndPlayersBySouls() {
        assertEquals(Team.Sapphire, real.perspective)
        assertEquals(listOf(Team.Sapphire, Team.Amber), real.teams.map { it.team })
        val (ours, theirs) = real.teams
        assertEquals(listOf(true, false), listOf(ours.won, theirs.won))
        assertEquals(listOf(53, 43), listOf(ours.kills, theirs.kills))
        assertEquals(listOf(288_499, 271_805), listOf(ours.souls, theirs.souls))
        assertEquals(
            listOf(95_573_234L, 165_689_557L, 1_058_516_575L, 477_962_985L, 164_789_537L, RealMatch.Mugi),
            ours.players.map { it.accountId }, "54.2k, 51.6k, 48.2k, 47.2k, 43.8k, 43.4k",
        )
        assertEquals(12, real.players.size)
        assertFalse(real.spectator)
    }

    @Test
    fun statsAreRankedAcrossAllTwelveWithTiesSharedAndUnknownsUnranked() {
        fun stat(stat: MatchStat) = mugi.stat(stat)
        assertEquals(RankedStat(36.0 / 53, 1), stat(MatchStat.KillShare), "(7 + 29) / 53")
        assertEquals(RankedStat(322.0 / 574, 4), stat(MatchStat.Accuracy), "behind 63%, 62% and 62%")
        assertEquals(RankedStat(60_544 / (2_471 / 60.0), 2), stat(MatchStat.DamagePerMinute))
        assertEquals(RankedStat(21.0 / 78, 2), stat(MatchStat.Headshots))
        assertEquals(RankedStat(43_394 / (2_471 / 60.0), 10), stat(MatchStat.SoulsPerMinute))
        assertEquals(RankedStat(41_656.0, 9), stat(MatchStat.DamageTaken))
        assertEquals(RankedStat(109.0, 11), stat(MatchStat.LastHits))
        assertEquals(RankedStat(20_270.0, 6), stat(MatchStat.DamageMitigated))
        assertEquals(RankedStat(92.0, 9), stat(MatchStat.Creeps))
        assertEquals(RankedStat(4.0, 12), stat(MatchStat.Neutrals))
        assertEquals(RankedStat(20_938.0, 6), stat(MatchStat.Healing))
        assertEquals(RankedStat(8.0 + 21 + 37 + 79, 1), stat(MatchStat.TimeDead), "least time dead ranks first")
        assertEquals(RankedStat(965.0, 2), stat(MatchStat.LongestLife), "1,214 s (respawn 20:14) to the death at 36:19")
        val nori = real.players.single { it.accountId == 165_689_557L }
        assertEquals(RankedStat(null, null), nori.stat(MatchStat.Accuracy), "Graves fired no counted shots: unknown, not 0%")
        assertEquals(listOf(1, 1), real.players.filter { it.stat(MatchStat.Level).value == 36.0 }.map { it.stat(MatchStat.Level).rank }, "two at level 36 share first")
        assertEquals(1.0, real.players.maxOf { it.damageShare ?: 0.0 })
        assertEquals(60_544.0 / 72_912, mugi.damageShare)
    }

    @Test
    fun theBuildReadsPurchasesByUpgradeIdAndTheirFateByFlags() {
        val build = assertNotNull(mugi.build)
        assertEquals(listOf(21, 10, 0, 11), listOf(build.purchases.size, build.upgraded, build.sold, build.kept), "21 bought · 10 upgraded into others · 11 at the end")
        assertEquals(Triple(54, ItemFate.Upgraded, 892), build.purchases.first().let { Triple(it.boughtAtS, it.fate, it.goneAtS) }, "Extra Regen, upgraded at 14:52")
        assertEquals("Extra Regen", build.purchases.first().item.name)
        assertEquals(
            listOf(395_867_183L, 2_566_692_615L, 112_198_670L, 334_300_056L, 2_163_598_980L, 787_198_704L, 2_947_183_272L, 2_121_044_373L, 3_261_353_684L, 2_519_598_785L, 3_005_970_438L),
            mugi.items.map { it.id }, "weapon, then vitality, then spirit, each by when it was bought",
        )
        assertTrue(build.purchases.none { it.item.name in setOf("Still Life", "Living Ink", "Flow State", "Abstract Landscape") }, "abilities are never purchases")
    }

    @Test
    fun abilityRowsAreTheHerosSignatureSlotsNotTheUnlockOrder() {
        val order = assertNotNull(mugi.abilities)
        assertEquals(
            listOf(AbilityRow(1, "Living Ink", "living_ink.png"), AbilityRow(2, "Flow State", null), AbilityRow(3, "Still Life", "still_life.png"), AbilityRow(4, "Abstract Landscape", null)),
            order.rows,
        )
        assertEquals(
            listOf(
                AbilityStep(12, 2, 0), AbilityStep(56, 2, 1), AbilityStep(108, 0, 0), AbilityStep(170, 1, 0), AbilityStep(261, 2, 2),
                AbilityStep(352, 0, 1), AbilityStep(406, 3, 0), AbilityStep(550, 0, 2), AbilityStep(567, 1, 1), AbilityStep(616, 1, 2),
                AbilityStep(911, 2, 3), AbilityStep(1218, 0, 3), AbilityStep(1605, 1, 3), AbilityStep(1842, 3, 1), AbilityStep(1991, 3, 2),
            ),
            order.steps, "Still Life, unlocked first, is slot 3 because the hero data says so",
        )
        val unplaced = project(RealMatch.detail, RealMatch.Mugi, StatsReference(emptyList(), RealMatch.items, emptyList())).you!!.abilities!!
        assertEquals(listOf(null, null, null, null), unplaced.rows.map { it.slot }, "without the hero's slot map no row claims a number")
        assertEquals(listOf("Still Life", "Living Ink", "Flow State", "Abstract Landscape"), unplaced.rows.map { it.name })
    }

    @Test
    fun deathsResolveKillersByTheirUniqueSlotNotByRosterOrder() {
        val timeline = assertNotNull(real.timeline)
        val deaths = assertNotNull(timeline.deaths)
        assertEquals(96, deaths.size)
        assertTrue(timeline.deathsComplete)
        assertEquals(MatchDeath(227, 1_058_516_575L, 430_678_819L, 8), deaths.first { it.victim == 1_058_516_575L }, "slot 10 dies at 3:47 to slot 5")
        assertEquals(listOf(227..235), deadBands(timeline, 1_058_516_575L).take(1))
        assertEquals(53, deaths.count { real.players.single { p -> p.accountId == it.killer }.team == Team.Sapphire }, "every kill resolves to the scoreboard's 53")
        val marks = marksOf(timeline, RealMatch.Mugi)
        assertEquals(listOf(122, 563, 982, 1319, 1836, 2021, 2423), marks.filter { it.kill }.map { it.timeS })
        assertEquals(listOf(240 to 430_678_819L, 689 to 1_096_148_131L, 1177 to 241_465_507L, 2179 to 1_096_148_131L), marks.filter { !it.kill }.map { it.timeS to it.death.killer })
        assertEquals(listOf(2471 - 2469), deadBands(timeline, 241_465_507L).takeLast(1).map { it.last - it.first }, "a respawn after the end is cut at the end")
        assertEquals(
            410.0, real.players.single { it.accountId == 241_465_507L }.stat(MatchStat.TimeDead).value,
            "12 + 18 + 31 + 55 + 64 + 71 + 74 + 85: the recorded lengths, though the last band is cut at the end",
        )
    }

    @Test
    fun enemiesAreOrderedByContactWithNoneDimmed() {
        assertEquals(
            listOf(1_096_148_131L to (1 to 2), 241_465_507L to (2 to 1), 430_678_819L to (1 to 1), 849_720_871L to (2 to 0), 80_094_580L to (1 to 0), 180_116_887L to (0 to 0)),
            mugi.enemies.map { it.player.accountId to (it.kills!! to it.deaths!!) },
        )
        assertTrue(mugi.enemies.last().none)
    }

    @Test
    fun theTimelineSharesOneCurveForLineSummaryAndHover() {
        val timeline = assertNotNull(real.timeline)
        assertEquals(
            listOf(0 to 0.0, 180 to 1875.0, 360 to -691.0, 540 to 2776.0, 720 to -3433.0, 900 to -1985.0, 1200 to -16150.0, 1500 to -11462.0, 1800 to -13588.0, 2100 to 15705.0, 2400 to 8907.0, 2471 to 16694.0),
            timeline.lead.points.map { it.timeS to it.value }, "Archmother's souls minus the Hidden King's",
        )
        val summary = timeline.summary
        assertEquals(1039.0 / 2471, summary.aheadShare, "the seconds the drawn curve is above zero")
        assertEquals(listOf(53, 43, 13, 7), listOf(summary.kills, summary.killsAgainst, summary.objectives, summary.objectivesAgainst))
        assertEquals(CurvePoint(1200, -16_150.0), summary.furthestBehind)
        assertTrue((0..2471).all { (timeline.lead.valueAt(it.toDouble()) ?: 0.0) >= -16_150.0 - 1e-6 }, "the curve never dips below what was measured")
        val minute = minuteReadout(timeline, GraphView.SoulsLead, mugi, RealMatch.Mugi, Team.Sapphire, real.players.associate { it.accountId to it.team }, 1860)
        assertEquals(timeline.lead.valueAt(1860.0), minute.value)
        assertEquals(3 to 0, minute.kills, "kills at 30:36, 30:37 and 31:24, all by Archmother")
        assertEquals(listOf(1836), minute.marks.map { it.timeS })
        assertEquals(listOf(ObjectiveEvent(1873, ObjectiveKind.MidBoss, null, Team.Sapphire, Team.Sapphire)), minute.objectives, "31:30 belongs to the next minute")
        assertEquals(46_692.0, timeline.averageSouls.points.last().value)
    }

    @Test
    fun everyObjectiveIsCreditedToTheTeamThatTookIt() {
        val objectives = assertNotNull(real.timeline).objectives
        assertEquals(22, objectives.size, "20 structures and 2 mid-bosses; the 3 still standing at the end are not events")
        assertEquals(ObjectiveEvent(588, ObjectiveKind.Guardian, Lane.Yellow, Team.Amber), objectives.first(), "Archmother's own yellow guardian, taken by the Hidden King")
        assertEquals(ObjectiveEvent(1890, ObjectiveKind.BaseGuardian, Lane.Purple, Team.Sapphire), objectives.single { it.timeS == 1890 })
        assertEquals(listOf(ObjectiveKind.Shrine, ObjectiveKind.Shrine, ObjectiveKind.PatronWeakened), objectives.filter { it.timeS in 2107..2148 }.map { it.kind })
        assertEquals(ObjectiveEvent(2470, ObjectiveKind.Patron, null, Team.Sapphire), objectives.last())
        assertEquals(listOf(1873, 2148, 2415), objectives.filter { it.guide }.map { it.timeS })
    }

    private fun snaps(vararg souls: Pair<Int, Int?>) = souls.map { (time, value) -> MatchPlayerStatSnapshot(time, netWorth = value, playerDamage = value?.div(2)) }

    private fun participant(id: Long, team: Int, slot: Int?, deaths: List<MatchDeathEvent>?, vararg souls: Pair<Int, Int?>, deathCount: Int? = deaths?.size) =
        MatchParticipant(id, heroId = 86, playerSlot = slot, team = team, kills = 1, deaths = deathCount, assists = 0, netWorth = 1000, statSnapshots = snaps(*souls), deathDetails = deaths)

    @Test
    fun unrecordedDeathsAreUnknownNotZero() {
        val detail = MatchDetail(
            7, durationS = 600, winningTeam = 0,
            players = listOf(participant(1, 0, 1, null, 300 to 500, 600 to 900), participant(2, 1, 2, emptyList(), 300 to 400, 600 to 800)),
        )
        val content = project(detail, 1)
        val (me, them) = content.teams.map { it.players.single() }
        assertNull(me.stat(MatchStat.TimeDead).value, "no recorded deaths for player 1: unknown")
        assertNull(me.stat(MatchStat.LongestLife).value)
        assertEquals(RankedStat(0.0, 1), them.stat(MatchStat.TimeDead), "an empty record is a known zero")
        assertEquals(RankedStat(600.0, 1), them.stat(MatchStat.LongestLife), "alive the whole match")
        assertEquals(0 to null, me.enemies.single().let { it.kills to it.deaths }, "player 2's deaths are recorded and none are player 1's; player 1's are not recorded")
        val timeline = assertNotNull(content.timeline)
        assertEquals(emptyList(), timeline.deaths)
        assertFalse(timeline.deathsComplete)
        val none = project(detail.copy(players = detail.players.map { it.copy(deathDetails = null) }), 1)
        assertNull(none.timeline!!.deaths, "no player's deaths in the metadata: unavailable")
    }

    @Test
    fun anAmbiguousSlotKeepsTheDeathButCreditsNoKill() {
        val detail = MatchDetail(
            7, durationS = 600, winningTeam = 0,
            players = listOf(
                participant(1, 0, 3, listOf(MatchDeathEvent(100, killerPlayerSlot = 4, deathDurationS = 10)), 300 to 1, 600 to 2),
                participant(2, 1, 4, emptyList(), 300 to 1, 600 to 2),
                participant(3, 1, 4, listOf(MatchDeathEvent(200, killerPlayerSlot = 3, deathDurationS = 10), MatchDeathEvent(300, killerPlayerSlot = null, deathDurationS = 10)), 300 to 1, 600 to 2),
            ),
        )
        val timeline = assertNotNull(project(detail, 1).timeline)
        assertEquals(listOf(MatchDeath(100, 1, null, 10), MatchDeath(200, 3, 1, 10), MatchDeath(300, 3, null, 10)), timeline.deaths, "slot 4 names two players, so its kill names no one")
        assertFalse(timeline.deathsComplete)
        assertEquals(emptyList(), marksOf(timeline, 2).filter { it.kill })
    }

    @Test
    fun aDeathRecordShorterThanTheScoreboardKeepsItsMarkersButCountsNothing() {
        val detail = MatchDetail(
            7, durationS = 600, winningTeam = 0,
            players = listOf(
                participant(1, 0, 1, listOf(MatchDeathEvent(100, killerPlayerSlot = 2, deathDurationS = 10)), 300 to 500, 600 to 900, deathCount = 2),
                participant(2, 1, 2, listOf(MatchDeathEvent(200, killerPlayerSlot = 1, deathDurationS = 10)), 300 to 400, 600 to 800),
            ),
        )
        val content = project(detail, 1)
        val (me, them) = content.teams.map { it.players.single() }
        val timeline = assertNotNull(content.timeline)
        assertEquals(listOf(MatchDeath(100, 1, 2, 10), MatchDeath(200, 2, 1, 10)), timeline.deaths, "both recorded deaths are kept")
        assertFalse(timeline.deathsComplete, "player 1 died twice but one death is recorded")
        assertEquals(listOf(false to 100, true to 200), marksOf(timeline, 1).map { it.kill to it.timeS }, "their markers still show")
        assertNull(minuteReadout(timeline, GraphView.SoulsLead, me, 1, Team.Amber, mapOf(1L to Team.Amber, 2L to Team.Sapphire), 120).kills, "no kill count for 2:00")
        assertNull(me.stat(MatchStat.LongestLife).value)
        assertEquals(390.0, them.stat(MatchStat.LongestLife).value)
        assertEquals(1 to null, me.enemies.single().let { it.kills to it.deaths }, "player 2's one death is all of them; player 1's deaths to them are not")
        assertEquals(null to 1, them.enemies.single().let { it.kills to it.deaths }, "kills on player 1 are not complete")
        assertNull(me.stat(MatchStat.TimeDead).value)
        assertEquals(10.0, them.stat(MatchStat.TimeDead).value)
    }

    @Test
    fun aDeathCountTheEventsDoNotMatchLeavesTimeDeadUnknown() {
        val short = participant(1, 0, 1, listOf(MatchDeathEvent(100, 2, 10)), 600 to 1, deathCount = 2)
        assertEquals(null to null, lifeOf(short, 600))
        val untimed = participant(1, 0, 1, listOf(MatchDeathEvent(100, 2, null)), 600 to 1)
        assertEquals(null to null, lifeOf(untimed, 600))
        val two = participant(1, 0, 1, listOf(MatchDeathEvent(400, 2, 20), MatchDeathEvent(100, 2, 10)), 600 to 1)
        assertEquals(30.0 to 290.0, lifeOf(two, 600), "lives 0–100, 110–400, 420–600")
    }

    @Test
    fun aMissingSnapshotBreaksTheCurveInsteadOfBeingFilledIn() {
        val detail = MatchDetail(
            7, durationS = 900, winningTeam = 0,
            players = listOf(participant(1, 0, 1, emptyList(), 300 to 500, 600 to null, 900 to 900), participant(2, 1, 2, emptyList(), 300 to 400, 600 to 700, 900 to 800)),
        )
        val timeline = assertNotNull(project(detail, 1).timeline)
        assertEquals(listOf(listOf(0 to 0.0, 300 to 100.0), listOf(900 to 100.0)), timeline.lead.runs.map { run -> run.map { it.timeS to it.value } })
        assertNull(timeline.lead.valueAt(600.0), "no value where a player's souls are unknown")
        assertNull(timeline.summary.aheadShare, "not known throughout the match")
        assertNull(timeline.averageSouls.valueAt(600.0))
    }

    @Test
    fun purchasesAndAbilitiesFollowTheirIdsAndFlagsWithoutGuessing() {
        val reference = StatsReference(
            listOf(Hero("hero_artist", "Violet", numericId = 86, signatureAbilities = mapOf(1 to "ability_a"))),
            listOf(ItemAsset(10, "upgrade_a", "A", category = "weapon", cost = 800), ItemAsset(11, "upgrade_b", "B", category = "spirit"), ItemAsset(20, "ability_a", "Alpha"), ItemAsset(21, "ability_b", "Beta")),
            emptyList(),
        )
        val player = MatchParticipant(
            1, heroId = 86, team = 0, items = listOf(
                MatchItemEvent(60, 10, upgradeId = 1, soldTimeS = 300, flags = 1), MatchItemEvent(70, 11, upgradeId = 1, soldTimeS = 400, flags = 0),
                MatchItemEvent(80, 11, upgradeId = 1, soldTimeS = 500, flags = 4), MatchItemEvent(90, 10, upgradeId = null),
                MatchItemEvent(95, 99, upgradeId = 1), MatchItemEvent(96, 98, upgradeId = null),
                MatchItemEvent(100, 20, upgradeId = 0, upgradeInfo = 1L shl 16), MatchItemEvent(110, 20, upgradeId = 5, upgradeInfo = 3L shl 16),
                MatchItemEvent(120, 21, upgradeId = 6, upgradeInfo = 7L shl 16), MatchItemEvent(130, 21, upgradeId = 7, upgradeInfo = 2L shl 16),
                MatchItemEvent(140, 20, upgradeId = null, upgradeInfo = 7L shl 16),
            ),
        )
        val build = assertNotNull(buildOf(player, reference))
        assertEquals(
            listOf(ItemFate.Upgraded, ItemFate.Sold, ItemFate.Removed, ItemFate.Kept, ItemFate.Kept),
            build.purchases.map { it.fate }, "flags 1 upgraded, 0 sold, anything else only gone; an old row with a shop category; an unknown id with upgrade id 1",
        )
        assertEquals("", build.purchases.last().item.name, "a purchase the item list does not know stays unnamed")
        assertEquals(1, build.unresolved, "an old row the list does not know might be either")
        val abilities = assertNotNull(abilitiesOf(player, reference))
        assertEquals(listOf(AbilityRow(1, "Alpha", null), AbilityRow(null, "Beta", null)), abilities.rows, "Beta is not among the hero's signatures, so it gets no number")
        assertEquals(listOf(AbilityStep(100, 0, 0), AbilityStep(110, 0, 1), AbilityStep(120, 1, 2), AbilityStep(140, 0, 2)), abilities.steps, "the unknown tier code at 2:10 is left out")
    }

    @Test
    fun aSpectatorSeesTheHiddenKingsSideAndNoPersonalNumbers() {
        val content = project(RealMatch.detail, 77)
        assertTrue(content.spectator)
        assertEquals(Team.Amber, content.perspective)
        assertEquals(listOf(Team.Amber, Team.Sapphire), content.teams.map { it.team })
        assertNull(content.you)
        assertEquals(Outcome.Other, content.header.outcome)
        assertNull(content.header.heroDamage)
        assertEquals(-16_694.0, content.timeline!!.lead.points.last().value)
        assertEquals(CurvePoint(2471, -16_694.0), content.timeline!!.summary.furthestBehind)
    }

    @Test
    fun legacyObjectiveIdsKeepTheirOwnerAndKind() {
        val detail = MatchDetail(
            7, durationS = 1800, winningTeam = 0, players = listOf(participant(1, 0, 1, emptyList(), 900 to 1, 1800 to 2)),
            objectives = listOf(
                MatchObjectiveEvent(legacyObjectiveId = 13, destroyedTimeS = 1200), MatchObjectiveEvent(legacyObjectiveId = 28, destroyedTimeS = 1500),
                MatchObjectiveEvent(legacyObjectiveId = 32, destroyedTimeS = 1000), MatchObjectiveEvent(legacyObjectiveId = 29, destroyedTimeS = 0),
                MatchObjectiveEvent(legacyObjectiveId = 12, teamObjectiveId = 3, team = 0, destroyedTimeS = 900),
            ),
            midBoss = listOf(MidBossEvent(teamKilled = 0, teamClaimed = 1, destroyedTimeS = 1100)),
        )
        assertEquals(
            listOf(
                ObjectiveEvent(900, ObjectiveKind.Guardian, Lane.Blue, Team.Sapphire),
                ObjectiveEvent(1100, ObjectiveKind.MidBoss, null, Team.Sapphire, killedBy = Team.Amber),
                ObjectiveEvent(1200, ObjectiveKind.BaseGuardian, Lane.Green, Team.Sapphire),
                ObjectiveEvent(1500, ObjectiveKind.BaseGuardian, Lane.Yellow, Team.Amber),
            ),
            objectivesOf(detail, 1800), "the team-relative id wins; the mid objective and untaken ones are not events; a stolen Rejuvenator is the claimer's",
        )
    }

    @Test
    fun aMatchWithoutSnapshotsHasNoTimelineButKeepsItsScoreboard() {
        val detail = MatchDetail(42, durationS = 1800, winningTeam = 0, players = listOf(MatchParticipant(1, heroId = 2, team = 0, netWorth = 10), MatchParticipant(2, heroId = 13, team = 1)))
        val content = project(detail, 1)
        assertNull(content.timeline)
        assertNull(content.you!!.build)
        assertEquals(2, content.teams.size)
        assertNull(content.teams[1].souls, "a missing value is unavailable, not zero")
        assertNull(content.header.heroDamage)
    }

    @Test
    fun unknownItemsAreCountedSoTheItemListIsAskedForAgain() {
        assertEquals(0, real.unknownItems)
        val strange = RealMatch.detail.copy(players = RealMatch.detail.players.map { if (it.accountId == RealMatch.Mugi) it.copy(items = it.items + MatchItemEvent(60, 999, upgradeId = 1)) else it })
        assertEquals(1, project(strange, RealMatch.Mugi).unknownItems)
    }
}
