package gg.hideouthq.feature.discover

import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.StatsPreferences
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsState
import gg.hideouthq.core.data.deadlockstats.MatchListFilter
import gg.hideouthq.core.data.deadlockstats.matches
import gg.hideouthq.core.model.Hero
import gg.hideouthq.feature.stats.data.StatsService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The three heroes played most in the account's last 30 matches, ranked and normal games as Stats lists them, by
 * catalogue id through [Hero.numericId]. A tie goes to the hero played most recently. Matches with no hero, or with a
 * hero the catalogue does not know, are skipped, so fewer heroes give fewer.
 */
fun mostPlayedHeroes(data: DeadlockStatsState, heroes: List<Hero>): List<String> {
    val byNumber = heroes.mapNotNull { hero -> hero.numericId?.let { it to hero.id } }.toMap()
    val played = data.matches(MatchListFilter(), limit = RecentMatches).rows.mapNotNull { row -> row.heroId?.let(byNumber::get) }
    val counts = played.groupingBy { it }.eachCount()
    return counts.keys.sortedWith(compareByDescending<String> { counts.getValue(it) }.thenBy { played.indexOf(it) }).take(LeadingHeroes)
}

/**
 * The heroes Discover should lead with now, or null to keep the ones saved. None once Stats is off or has no account.
 * [data]'s most played once the matches of the account [saved] names are here; until then, and while the catalogue's
 * heroes are not, what was saved stays, so Discover does not lose its lead at every start.
 */
fun heroesToLeadWith(saved: StatsPreferences?, data: DeadlockStatsState?, heroes: List<Hero>): List<String>? = when {
    saved == null -> null
    !saved.enabled || saved.accountId == null -> emptyList()
    data == null || data.accountId != saved.accountId -> null
    else -> mostPlayedHeroes(data, heroes).ifEmpty { null }
}

/**
 * Keeps [Preferences.preferredHeroes] the heroes the Stats account plays most, with nothing asked: saved whenever they
 * change, so the next start leads with them before Stats has loaded. Nothing is read of an account the user has not
 * chosen for Stats.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun followPlayedHeroes(stats: StatsService, catalog: CatalogRepository, preferences: Preferences) {
    val played = stats.state.map { it.tracked }.distinctUntilChanged().flatMapLatest { session -> session?.data ?: flowOf(null) }
    combine(preferences.stats, played, catalog.heroes) { saved, data, heroes -> heroesToLeadWith(saved, data, heroes) }
        .filterNotNull().distinctUntilChanged()
        .collect { heroes -> if (heroes != preferences.preferredHeroes.value) preferences.setPreferredHeroes(heroes) }
}

private const val RecentMatches = 30
private const val LeadingHeroes = 3
