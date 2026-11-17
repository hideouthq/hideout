package gg.hideouthq.core.data.deadlockstats

import kotlin.test.*

class StatsRegressionTest {
    @Test fun partialRefreshCannotEraseKnownFields() {
        val before = MatchDetail(1, players = listOf(MatchParticipant(555, kills = 5, rank = MatchPlayerRank(initialDisplayRank = 64))))
        val after = MatchDetail(1, players = listOf(MatchParticipant(555, deaths = 2)))
        assertEquals(MatchParticipant(555, kills = 5, deaths = 2, rank = MatchPlayerRank(64)), mergeMatchDetail(before, after).players.single())
    }

    @Test fun multipleItemEventsInTheSameSecondSurviveRepeatedRefreshes() {
        val event = MatchItemEvent(gameTimeS = 394, itemId = 2918874166L)
        val detail = MatchDetail(1, players = listOf(MatchParticipant(555, items = listOf(event, event))))
        val merged = mergeMatchDetail(detail, detail)
        assertEquals(2, merged.players.single().items.size)
        assertEquals(2, mergeMatchDetail(merged, detail).players.single().items.size)
        val richer = detail.copy(players = listOf(MatchParticipant(555, items = listOf(
            event.copy(upgradeInfo = 196608L), event.copy(upgradeInfo = 458752L),
        ))))
        assertEquals(listOf(196608L, 458752L), mergeMatchDetail(merged, richer).players.single().items.map { it.upgradeInfo })
        val partial = detail.copy(players = listOf(MatchParticipant(555, items = listOf(event, event.copy(gameTimeS = 500)))))
        assertEquals(listOf(394, 394, 500), mergeMatchDetail(partial, richer).players.single().items.map { it.gameTimeS })
    }

    @Test fun deathEventsMergeOneByOneAndNeverRegressFromKnownToUnknown() {
        fun detail(vararg deaths: MatchDeathEvent) = MatchDetail(1, players = listOf(MatchParticipant(555, deathDetails = deaths.toList())))
        fun MatchDetail.deaths() = players.single().deathDetails
        val unknown = MatchDetail(1, players = listOf(MatchParticipant(555)))
        assertEquals(emptyList(), mergeMatchDetail(unknown, detail()).deaths(), "a known-empty refresh overwrites unknown")
        assertEquals(emptyList(), mergeMatchDetail(detail(), unknown).deaths(), "an unknown refresh never erases a known-empty result")

        val first = MatchDeathEvent(gameTimeS = 227, killerPlayerSlot = 5, deathDurationS = 8)
        val second = MatchDeathEvent(gameTimeS = 400, killerPlayerSlot = 2, deathDurationS = 10)
        assertEquals(listOf(first, second), mergeMatchDetail(detail(first, second), detail(first)).deaths(), "a partial refresh keeps the death it did not mention")

        val untimed = detail(MatchDeathEvent(killerPlayerSlot = 5, deathDurationS = 8), MatchDeathEvent(killerPlayerSlot = 9, deathDurationS = 10))
        assertEquals(untimed.deaths(), mergeMatchDetail(untimed, unknown).deaths())
        val refreshed = untimed.copy(players = untimed.players.map { it.copy(kills = 3) })
        assertEquals(untimed.deaths(), mergeMatchDetail(mergeMatchDetail(untimed, refreshed), refreshed).deaths())
    }

    @Test fun aNonDecisiveMatchDoesNotBecomeAWinFromWinningTeamDefault() {
        val detail = MatchDetail(1, matchOutcome = 2, winningTeam = 0,
            players = listOf(MatchParticipant(555, team = 0)))
        val history = detail.historyFor(555)!!
        assertNull(history.matchResult)
        assertNull(history.playerMatchOutcome)
    }
}
