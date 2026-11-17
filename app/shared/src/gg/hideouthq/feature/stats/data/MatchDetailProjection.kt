package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchDetail
import gg.hideouthq.core.data.deadlockstats.MatchObjectiveEvent
import gg.hideouthq.core.data.deadlockstats.MatchParticipant
import gg.hideouthq.core.data.deadlockstats.MatchPlayerStatSnapshot
import gg.hideouthq.core.data.deadlockstats.PlayerMatchOutcome
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.previousRankedBadges
import gg.hideouthq.core.model.Hero
import gg.hideouthq.feature.stats.Lane
import gg.hideouthq.feature.stats.MatchCurve
import gg.hideouthq.feature.stats.MatchDeath
import gg.hideouthq.feature.stats.MatchDetailContent
import gg.hideouthq.feature.stats.MatchHeader
import gg.hideouthq.feature.stats.MatchPlayer
import gg.hideouthq.feature.stats.MatchStat
import gg.hideouthq.feature.stats.MatchTeam
import gg.hideouthq.feature.stats.MatchTimeline
import gg.hideouthq.feature.stats.ObjectiveEvent
import gg.hideouthq.feature.stats.ObjectiveKind
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.Team
import gg.hideouthq.feature.stats.TimelineSummary
import java.time.ZoneId

/** Lobby team 0 is the amber side (the Hidden King), team 1 the sapphire side (the Archmother). */
internal fun teamOf(code: Int?): Team? = when (code) {
    0 -> Team.Amber
    1 -> Team.Sapphire
    else -> null
}

private const val SpectatorPerspective = 0

/**
 * One loaded match as its page shows it to [accountId], with names from [profiles], heroes from the catalogue and items
 * and ranks from the saved stats.
 */
fun matchDetailContent(
    data: DeadlockStatsState,
    matchId: Long,
    accountId: Long,
    profiles: Map<Long, PlayerProfile>,
    heroes: List<Hero>,
    now: Long,
    zone: ZoneId,
): MatchDetailContent? = matchDetailContent(data, matchId, accountId, profiles, StatsReference(heroes, data.itemAssets, data.rankAssets), StatsClock(now, zone))

internal fun matchDetailContent(
    data: DeadlockStatsState,
    matchId: Long,
    accountId: Long,
    profiles: Map<Long, PlayerProfile>,
    reference: StatsReference,
    clock: StatsClock,
): MatchDetailContent? {
    val detail = data.matchDetails[matchId] ?: return null
    val history = data.matchHistory.firstOrNull { it.matchId == matchId }
    val me = detail.players.firstOrNull { it.accountId == accountId }
    val durationS = detail.durationS ?: history?.matchDurationS
    val perspectiveCode = me?.team?.takeIf { it in 0..1 } ?: SpectatorPerspective
    val records = MatchRecords(detail, durationS)
    val players = matchPlayers(records, accountId, profiles, reference)
    val viewer = players.firstOrNull { it.isYou }
    val kind = matchKind(detail.matchMode ?: history?.matchMode)
    val before = me?.rank?.initialDisplayRank?.takeIf { it > 0 } ?: data.previousRankedBadges()[matchId]
    val teams = listOf(perspectiveCode, 1 - perspectiveCode).mapNotNull { code -> teamOf(code, detail, players) }
    return MatchDetailContent(
        matchId = matchId,
        started = (detail.startTime ?: history?.startTime)?.let { clock.started(it.toLong()) },
        header = MatchHeader(
            hero = reference.hero(me?.heroId ?: history?.heroId),
            outcome = outcomeOf(detail, me),
            kind = kind,
            durationS = durationS,
            team = teamOf(me?.team),
            level = me?.level ?: history?.heroLevel,
            lobbyRank = reference.rank(averageBadge(listOfNotNull(detail.averageBadgeTeam0, detail.averageBadgeTeam1))),
            rankChange = rankChange(kind, before, history?.rankedDisplayBadge?.takeIf { it > 0 }, reference, (history?.rankedCalibrationMatch ?: 0) != 0),
            mostAssists = me?.assists?.let { mine -> mine > 0 && detail.players.all { it === me || (it.assists != null && it.assists < mine) } } == true,
            kills = me?.kills,
            deaths = me?.deaths,
            assists = me?.assists,
            kda = me?.let { kda(it.kills, it.deaths, it.assists) },
            souls = me?.netWorth,
            heroDamage = me?.let { records.latest(it)?.playerDamage },
            heroDamagePlace = viewer?.stat(MatchStat.HeroDamage)?.rank?.takeIf { it <= 3 },
            lastHits = me?.lastHits,
            denies = me?.denies,
            objectiveDamage = me?.let { records.latest(it)?.bossDamage },
            healing = me?.let { records.latest(it)?.playerHealing },
        ),
        perspective = teamOf(perspectiveCode)!!,
        timeline = timelineOf(records, perspectiveCode, teams),
        teams = teams,
        spectator = me == null,
        unknownItems = detail.players.sumOf { player -> player.items.count { it.itemId != null && reference.itemAsset(it.itemId) == null } },
    )
}

private fun kda(kills: Int?, deaths: Int?, assists: Int?): Double? =
    if (kills != null && deaths != null && assists != null) (kills + assists).toDouble() / deaths.coerceAtLeast(1) else null

private fun outcomeOf(detail: MatchDetail, me: MatchParticipant?): Outcome {
    if (me == null) return Outcome.Other
    val winner = detail.winningTeam
    if ((detail.matchOutcome == null || detail.matchOutcome == 0) && winner in 0..1 && me.team in 0..1) {
        return if (winner == me.team) Outcome.Win else Outcome.Loss
    }
    return when (PlayerMatchOutcome.ofCode(me.playerMatchOutcome)) {
        PlayerMatchOutcome.Win -> Outcome.Win
        PlayerMatchOutcome.Loss -> Outcome.Loss
        else -> Outcome.Other
    }
}

private fun teamOf(code: Int, detail: MatchDetail, players: List<MatchPlayer>): MatchTeam? {
    val members = detail.players.filter { it.team == code }
    if (members.isEmpty()) return null
    val team = teamOf(code)!!
    return MatchTeam(
        team = team,
        won = detail.winningTeam?.takeIf { it in 0..1 && (detail.matchOutcome == null || detail.matchOutcome == 0) }?.let { it == code },
        kills = members.sumOfAll { it.kills },
        souls = members.sumOfAll { it.netWorth },
        players = players.filter { it.team == team }.sortedWith(compareByDescending<MatchPlayer, Double?>(nullsFirst()) { it.stat(MatchStat.Souls).value }),
    )
}

internal fun <T> List<T>.sumOfAll(selector: (T) -> Int?): Int? {
    var sum = 0
    for (item in this) sum += selector(item) ?: return null
    return sum
}

private fun timelineOf(records: MatchRecords, perspective: Int, teams: List<MatchTeam>): MatchTimeline? {
    val duration = records.durationS ?: return null
    val players = records.detail.players
    val times = records.snapshotTimes
    if (times.isEmpty()) return null
    fun curve(value: (Int) -> Double?) = MatchCurve.of(listOf(0 to 0.0) + times.map { it to value(it) })
    fun teamSouls(code: Int, time: Int) = players.filter { it.team == code }.takeIf { it.isNotEmpty() }?.sumOfAll { records.at(it, time)?.netWorth }
    fun average(time: Int, field: (MatchPlayerStatSnapshot) -> Int?) =
        players.map { records.at(it, time)?.let(field) }.takeIf { values -> values.isNotEmpty() && values.all { it != null } }?.filterNotNull()?.average()
    val lead = curve { time ->
        val ours = teamSouls(perspective, time) ?: return@curve null
        val theirs = teamSouls(1 - perspective, time) ?: return@curve null
        (ours - theirs).toDouble()
    }
    val objectives = objectivesOf(records.detail, duration)
    val ours = teamOf(perspective)
    return MatchTimeline(
        durationS = duration,
        lead = lead,
        averageSouls = curve { time -> average(time) { it.netWorth } },
        averageDamage = curve { time -> average(time) { it.playerDamage } },
        objectives = objectives,
        deaths = records.deaths,
        deathsComplete = records.deathsComplete,
        summary = TimelineSummary(
            aheadShare = aheadShare(lead, duration),
            kills = teams.firstOrNull { it.team == ours }?.kills,
            killsAgainst = teams.firstOrNull { it.team != ours }?.kills,
            objectives = objectives.count { it.kind != ObjectiveKind.MidBoss && it.takenBy == ours },
            objectivesAgainst = objectives.count { it.kind != ObjectiveKind.MidBoss && it.takenBy != null && it.takenBy != ours },
            furthestBehind = lead.points.filter { it.value < 0 }.minByOrNull { it.value },
        ),
    )
}

internal fun aheadShare(lead: MatchCurve, durationS: Int): Double? {
    val run = lead.runs.singleOrNull() ?: return null
    if (durationS <= 0 || run.first().timeS > 0 || run.last().timeS < durationS) return null
    val ahead = (0 until durationS).count { second -> (lead.valueAt(second + 0.5) ?: 0.0) > 0 }
    return ahead.toDouble() / durationS
}

/**
 * Every destroyed structure, credited to the team that took it (the metadata's `team` is the owner), and every mid-boss
 * kill, credited to the team that claimed its Rejuvenator. A time of 0 is a structure still standing.
 */
internal fun objectivesOf(detail: MatchDetail, durationS: Int): List<ObjectiveEvent> {
    val structures = detail.objectives.mapNotNull { objective ->
        val time = objective.destroyedTimeS?.takeIf { it in 1..durationS } ?: return@mapNotNull null
        val (owner, slot) = objective.ownerAndSlot() ?: return@mapNotNull null
        val (kind, lane) = structureOf(slot) ?: return@mapNotNull null
        ObjectiveEvent(time, kind, lane, teamOf(1 - owner))
    }
    val bosses = detail.midBoss.mapNotNull { boss ->
        boss.destroyedTimeS?.takeIf { it in 1..durationS }?.let { ObjectiveEvent(it, ObjectiveKind.MidBoss, null, teamOf(boss.teamClaimed), teamOf(boss.teamKilled)) }
    }
    return (structures + bosses).sortedBy { it.timeS }
}

/** Valve's team-relative structure slots: 0 the Patron, 1–4 guardians, 5–8 walkers, 9 the weakened Patron, 10–11 shrines, 12–15 base guardians. */
private fun structureOf(slot: Int): Pair<ObjectiveKind, Lane?>? = when (slot) {
    0 -> ObjectiveKind.Patron to null
    in 1..4 -> ObjectiveKind.Guardian to laneOf(slot)
    in 5..8 -> ObjectiveKind.Walker to laneOf(slot - 4)
    9 -> ObjectiveKind.PatronWeakened to null
    10, 11 -> ObjectiveKind.Shrine to null
    in 12..15 -> ObjectiveKind.BaseGuardian to laneOf(slot - 11)
    else -> null
}

private fun laneOf(lane: Int): Lane? = when (lane) {
    1 -> Lane.Yellow
    2 -> Lane.Green
    3 -> Lane.Blue
    4 -> Lane.Purple
    else -> null
}

/**
 * An objective's owning team and team-relative slot: from `team` and `team_objective_id` when present, else from the
 * legacy id, whose 0–15 are team 0's slots and 16–31 team 1's.
 */
private fun MatchObjectiveEvent.ownerAndSlot(): Pair<Int, Int>? = when {
    teamObjectiveId != null -> team?.takeIf { it in 0..1 }?.let { it to teamObjectiveId }
    legacyObjectiveId in 0..31 -> legacyObjectiveId!! / 16 to legacyObjectiveId % 16
    else -> null
}

internal class MatchRecords(val detail: MatchDetail, val durationS: Int?) {
    private val snapshots = detail.players.associate { player ->
        player.accountId to player.statSnapshots.filter { it.timeStampS != null }.associateBy { it.timeStampS!! }
    }

    val snapshotTimes: List<Int> = detail.players.flatMap { player -> player.statSnapshots.mapNotNull { it.timeStampS } }
        .filter { time -> durationS != null && time in 1..durationS }.distinct().sorted()

    val bySlot: Map<Int, MatchParticipant> = detail.players.filter { it.playerSlot != null }.groupBy { it.playerSlot!! }
        .filterValues { it.size == 1 }.mapValues { it.value.single() }

    fun at(player: MatchParticipant, time: Int) = snapshots[player.accountId]?.get(time)

    fun latest(player: MatchParticipant) = player.statSnapshots.filter { it.timeStampS != null }.maxByOrNull { it.timeStampS!! }

    fun resolvable(player: MatchParticipant): Boolean = player.playerSlot?.let(bySlot::get) === player

    val deaths: List<MatchDeath>? = if (detail.players.all { it.deathDetails == null }) null else detail.players.flatMap { victim ->
        victim.deathDetails.orEmpty().mapNotNull { event ->
            val time = event.gameTimeS ?: return@mapNotNull null
            val killer = event.killerPlayerSlot?.let(bySlot::get)?.takeIf { it !== victim }
            MatchDeath(time, victim.accountId, killer?.accountId, event.deathDurationS)
        }
    }.sortedBy { it.timeS }

    val deathsComplete: Boolean = detail.players.isNotEmpty() && detail.players.all { it.deathsRecorded() && resolvable(it) }
}

internal fun MatchParticipant.deathsRecorded(): Boolean =
    deathDetails?.let { events -> events.all { it.gameTimeS != null } && (deaths == null || deaths == events.size) } == true

internal fun MatchRecords.curveOf(player: MatchParticipant, field: (MatchPlayerStatSnapshot) -> Int?): MatchCurve? {
    if (durationS == null) return null
    val own = player.statSnapshots.filter { it.timeStampS != null && it.timeStampS in 1..durationS }.sortedBy { it.timeStampS }
    if (own.isEmpty()) return null
    return MatchCurve.of(listOf(0 to 0.0) + own.map { it.timeStampS!! to field(it)?.toDouble() })
}
