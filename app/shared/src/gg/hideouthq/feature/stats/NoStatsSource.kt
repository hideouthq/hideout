package gg.hideouthq.feature.stats

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/** Stats where this build has no backend: the app offers no Stats page, and nothing is fetched. */
object NoStatsSource : StatsSource {
    override val savedFilter: Flow<StatsFilter?> = flowOf(StatsFilter())
    override fun content(selection: StatsSelection, visiting: Long?): Flow<StatsContent> = emptyFlow()
    override fun match(matchId: Long, visiting: Long?): Flow<MatchDetailState> = emptyFlow()
    override fun search(query: String): Flow<SearchState> = flowOf(SearchState(query))
    override suspend fun lookUp(query: String): AccountLookup = AccountLookup.Failed(LookupFailure.Unavailable)
    override fun saveFilter(filter: StatsFilter) = Unit
    override fun refresh(visiting: Long?) = Unit
    override fun retry(visiting: Long?) = Unit
    override fun checkMatch(matchId: Long, visiting: Long?) = Unit
    override fun useAccount(accountId: Long) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
}
