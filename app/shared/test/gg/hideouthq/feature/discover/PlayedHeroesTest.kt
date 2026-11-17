package gg.hideouthq.feature.discover

import gg.hideouthq.core.data.StatsPreferences
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchHistoryEntry
import gg.hideouthq.core.model.Hero
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayedHeroesTest {
    private val heroes = listOf(
        Hero("hero_inferno", "Infernus", numericId = 1),
        Hero("hero_haze", "Haze", numericId = 13),
        Hero("hero_wraith", "Wraith", numericId = 7),
        Hero("hero_lash", "Lash", numericId = 31),
    )
    private val account = 81_007_780L

    private fun history(vararg heroIds: Int?) = DeadlockStatsState(
        accountId = account,
        matchHistory = heroIds.mapIndexed { index, heroId ->
            MatchHistoryEntry(matchId = 1_000L - index, heroId = heroId, startTime = 1_800_000_000 - index * 3_600, gameMode = 1, matchMode = 1)
        },
    )

    @Test
    fun theThreeHeroesPlayedMostLeadWithTiesGoingToTheMostRecent() {
        val data = history(31, 13, 7, 13, 1, 7, 13)
        assertEquals(listOf("hero_haze", "hero_wraith", "hero_lash"), mostPlayedHeroes(data, heroes))
    }

    @Test
    fun onlyTheLastThirtyMatchesCount() {
        val data = history(*(List(30) { 13 } + List(40) { 1 }).toTypedArray())
        assertEquals(listOf("hero_haze"), mostPlayedHeroes(data, heroes))
    }

    @Test
    fun matchesWithoutAKnownHeroAreSkippedAndFewHeroesGiveFew() {
        assertEquals(listOf("hero_lash"), mostPlayedHeroes(history(null, 99, 31), heroes))
        assertEquals(emptyList(), mostPlayedHeroes(DeadlockStatsState(), heroes))
    }

    @Test
    fun discoverLeadsWithTheChosenAccountsHeroesAndWithNoneOnceStatsHasNoAccount() {
        val chosen = StatsPreferences(enabled = true, accountId = account)
        assertEquals(listOf("hero_haze", "hero_lash"), heroesToLeadWith(chosen, history(13, 31, 13), heroes))
        assertEquals(emptyList(), heroesToLeadWith(StatsPreferences(enabled = true, accountId = null), history(13), heroes), "an account Hideout only found leads nothing")
        assertEquals(emptyList(), heroesToLeadWith(chosen.copy(enabled = false), history(13), heroes), "Stats off leads nothing")
    }

    @Test
    fun whatIsSavedStaysUntilTheAccountsMatchesAreHere() {
        val chosen = StatsPreferences(enabled = true, accountId = account)
        assertNull(heroesToLeadWith(null, history(13), heroes), "before the preferences are read")
        assertNull(heroesToLeadWith(chosen, null, heroes), "before Stats has loaded")
        assertNull(heroesToLeadWith(chosen, history(13).copy(accountId = 7), heroes), "another account's matches")
        assertNull(heroesToLeadWith(chosen, history(), heroes), "no matches yet")
        assertNull(heroesToLeadWith(chosen, history(13), emptyList()), "before the catalogue knows the heroes")
    }
}
