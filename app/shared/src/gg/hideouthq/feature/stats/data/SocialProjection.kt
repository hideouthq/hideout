package gg.hideouthq.feature.stats.data

import gg.hideouthq.core.data.deadlockstats.DateWindow
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchListFilter
import gg.hideouthq.core.data.deadlockstats.MatchModeFilter
import gg.hideouthq.core.data.deadlockstats.PlayedWithStats
import gg.hideouthq.core.data.deadlockstats.PlayedWithView
import gg.hideouthq.core.data.deadlockstats.PlayerProfile
import gg.hideouthq.core.data.deadlockstats.enemies
import gg.hideouthq.core.data.deadlockstats.matchIds
import gg.hideouthq.core.data.deadlockstats.mates
import gg.hideouthq.feature.stats.OpponentRow
import gg.hideouthq.feature.stats.TeammateRow

internal const val MaxTeammates = 12
internal const val MaxOpponents = 8

internal fun socialRows(data: DeadlockStatsState, mode: MatchModeFilter, window: DateWindow): Pair<List<PlayedWithView>, List<PlayedWithView>> {
    val filter = MatchListFilter(mode = mode, window = window)
    val mates = data.mates(filter = filter).sortedWith(compareByDescending<PlayedWithView> { it.matchesPlayed }.thenByDescending { it.wins }.thenBy { it.otherAccountId })
    val enemies = data.enemies(filter = filter).filter { it.matchesPlayed >= 2 }
        .sortedWith(compareByDescending<PlayedWithView> { it.matchesPlayed }.thenBy { it.otherAccountId }).take(MaxOpponents)
    return mates to enemies
}

internal fun partyIds(data: DeadlockStatsState, mode: MatchModeFilter, window: DateWindow, party: List<PlayedWithStats>?): Set<Long> {
    if (party == null) return emptySet()
    val kept = data.matchIds(MatchListFilter(mode = mode, window = window)).toSet()
    return party.filter { mate -> mate.matches.any { it in kept } }.map { it.otherAccountId }.toSet()
}

internal fun visibleMates(mates: List<PlayedWithView>, party: Set<Long>, partyOnly: Boolean, limit: Int): List<PlayedWithView> = mates
    .filter { it.matchesPlayed >= 2 || it.otherAccountId in party }
    .filter { !partyOnly || it.otherAccountId in party }
    .take(limit)

internal fun teammateRows(mates: List<PlayedWithView>, party: Set<Long>, profiles: Map<Long, PlayerProfile>, clock: StatsClock): List<TeammateRow> =
    mates.map { mate ->
        val decided = (mate.knownResultMatches ?: mate.matchesPlayed.toInt()).takeIf { it > 0 }
        TeammateRow(
            player = profiles[mate.otherAccountId].toVisual(mate.otherAccountId),
            party = mate.otherAccountId in party,
            lastPlayed = mate.lastSharedMatchAt?.let { clock.dayOf(it.toLong()) },
            games = mate.matchesPlayed.toInt(),
            wins = mate.wins.toInt(),
            winRate = decided?.let { mate.wins.toDouble() / it },
            kda = mate.yourKda.value,
        )
    }

internal fun opponentRows(
    enemies: List<PlayedWithView>,
    profiles: Map<Long, PlayerProfile>,
    usualHeroes: Map<Long, Int>,
    reference: StatsReference,
): List<OpponentRow> = enemies.map { enemy ->
    val decided = (enemy.knownResultMatches ?: enemy.matchesPlayed.toInt()).takeIf { it > 0 }
    OpponentRow(
        player = profiles[enemy.otherAccountId].toVisual(enemy.otherAccountId),
        usualHero = reference.hero(usualHeroes[enemy.otherAccountId]),
        met = enemy.matchesPlayed.toInt(),
        winRate = decided?.let { enemy.wins.toDouble() / it },
    )
}
