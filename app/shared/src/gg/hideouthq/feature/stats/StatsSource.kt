package gg.hideouthq.feature.stats

import kotlinx.coroutines.flow.Flow

interface StatsSource {
    /** The saved mode and period, or null until read. */
    val savedFilter: Flow<StatsFilter?>

    /** [visiting] is someone else's account opened from search; null for your own. */
    fun content(selection: StatsSelection, visiting: Long?): Flow<StatsContent>

    fun match(matchId: Long, visiting: Long?): Flow<MatchDetailState>

    fun search(query: String): Flow<SearchState>

    /** Finds accounts for setup by account id, SteamID64, profile link or name. */
    suspend fun lookUp(query: String): AccountLookup

    fun saveFilter(filter: StatsFilter)

    /** Updates the account's stats now, and asks again for reference data that failed. */
    fun refresh(visiting: Long?)

    /** Asks again for whatever failed to load beside the saved stats: a match, rank averages, builds, parties, names. */
    fun retry(visiting: Long?)

    /** Checks a pending match's full metadata again right away, for its own Check now / Check again, not a whole retry. */
    fun checkMatch(matchId: Long, visiting: Long?)

    fun useAccount(accountId: Long)

    fun setEnabled(enabled: Boolean)
}
