package gg.hideouthq.feature.stats

import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private class RecordingSource(saved: StatsFilter? = StatsFilter(StatsMode.Ranked, StatsPeriod.Week), private val isYou: Boolean = true) : StatsSource by NoStatsSource {
    val requests = mutableListOf<StatsSelection>()
    val saved = mutableListOf<StatsFilter>()
    val searches = mutableListOf<String>()
    private val filter = MutableStateFlow(saved)

    override val savedFilter: Flow<StatsFilter?> = filter
    override fun content(selection: StatsSelection, visiting: Long?): Flow<StatsContent> {
        requests += selection
        return flowOf(StatsContent.Active(1, null, SyncStatus(), 0, 0, null, isYou = isYou))
    }
    override fun search(query: String): Flow<SearchState> {
        searches += query
        return flowOf(SearchState(query.trim(), listOf(SearchResult.Match(42))))
    }
    override suspend fun lookUp(query: String): AccountLookup {
        delay(query.length * 1_000L)
        return AccountLookup.Found(listOf(PlayerVisual(query.length.toLong(), query)))
    }
    override fun saveFilter(filter: StatsFilter) {
        saved += filter
        this.filter.value = filter
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {
    private fun pages(test: suspend TestScope.(page: (RecordingSource, Long?) -> StatsViewModel) -> Unit) = withViewModels { viewModels ->
        test { source, visiting ->
            StatsViewModel(source, visiting).also { vm ->
                viewModels.keep(vm)
                backgroundScope.launch { vm.uiState.collect {} }
            }
        }
    }

    private fun viewModel(source: RecordingSource, visiting: Long? = null, test: suspend TestScope.(StatsViewModel) -> Unit) = pages { page ->
        val vm = page(source, visiting)
        advanceUntilIdle()
        test(vm)
    }

    @Test
    fun filterChangesAreSavedAndReachTheSource() {
        val source = RecordingSource()
        viewModel(source) { vm ->
            assertEquals(StatsFilter(StatsMode.Ranked, StatsPeriod.Week), vm.uiState.value.selection?.filter, "the saved filters to start")
            vm.selectTab(StatsTab.Teammates)
            vm.setMode(StatsMode.Normal)
            advanceUntilIdle()
            assertEquals(listOf(StatsFilter(StatsMode.Normal, StatsPeriod.Week)), source.saved)
            val last = source.requests.last()
            assertEquals(StatsTab.Teammates, last.tab)
            assertEquals(StatsFilter(StatsMode.Normal, StatsPeriod.Week), last.filter, "social requests carry the global filters")
            vm.setMode(StatsMode.Normal)
            advanceUntilIdle()
            assertEquals(1, source.saved.size, "choosing the same filter again changes nothing")
        }
    }

    @Test
    fun matchesChoicesStayOnTheTabAndResetPaging() {
        val source = RecordingSource()
        viewModel(source) { vm ->
            vm.selectTab(StatsTab.Matches)
            vm.showMore()
            advanceUntilIdle()
            assertEquals(2 * MatchesPageSize, source.requests.last().shown)
            vm.setResult(ResultFilter.Wins)
            advanceUntilIdle()
            assertEquals(MatchesPageSize, source.requests.last().shown, "a new filter starts from the first page")
            assertEquals(ResultFilter.Wins, source.requests.last().result)
            vm.setHeroFilter(2)
            vm.setPeriod(StatsPeriod.Month)
            advanceUntilIdle()
            assertEquals(2, source.requests.last().heroFilter)
            assertEquals(MatchesPageSize, source.requests.last().shown)
            vm.selectTab(StatsTab.Overview)
            advanceUntilIdle()
            assertEquals(StatsPeriod.Month, source.requests.last().filter.period)
        }
    }

    @Test
    fun aHeroOpensTheHeroesTabWithItSelected() = viewModel(RecordingSource()) { vm ->
        vm.openHero(13)
        advanceUntilIdle()
        assertEquals(StatsTab.Heroes, vm.uiState.value.selection?.tab)
        assertEquals(13, vm.uiState.value.selection?.selectedHero)
        vm.showOverview()
        advanceUntilIdle()
        assertEquals(StatsTab.Overview, vm.uiState.value.selection?.tab)
    }

    @Test
    fun searchWaitsForTypingToPauseAndOnlyAsksOnce() {
        val source = RecordingSource()
        viewModel(source) { vm ->
            var typed = ""
            "4123".forEach { typed += it; vm.setQuery(typed); advanceTimeBy(50); runCurrent() }
            assertEquals(emptyList(), source.searches)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(listOf("4123"), source.searches)
            assertIs<SearchResult.Match>(vm.uiState.value.search.results.single())
            vm.setQuery("")
            advanceUntilIdle()
            assertEquals(SearchState(), vm.uiState.value.search)
        }
    }

    @Test
    fun anUnreadFilterWaitsInsteadOfGuessing() = viewModel(RecordingSource(saved = null)) { vm ->
        assertNull(vm.uiState.value.selection)
        assertNull(vm.uiState.value.content)
    }

    @Test
    fun aFilterChangedOnAnotherStatsPageReachesThisOneAndStartsItsListAgain() = pages { page ->
        val source = RecordingSource()
        val own = page(source, null)
        val visit = page(source, 7)
        advanceUntilIdle()
        own.selectTab(StatsTab.Matches)
        own.showMore()
        advanceUntilIdle()
        visit.setPeriod(StatsPeriod.AllTime)
        visit.setMode(StatsMode.Normal)
        advanceUntilIdle()
        assertEquals(StatsFilter(StatsMode.Normal, StatsPeriod.AllTime), own.uiState.value.selection?.filter, "the page underneath follows")
        assertEquals(MatchesPageSize, own.uiState.value.selection?.shown, "a new filter starts from the first page")
        assertEquals(StatsTab.Matches, own.uiState.value.selection?.tab, "only the filters change")
        assertEquals(StatsFilter(StatsMode.Normal, StatsPeriod.AllTime), visit.uiState.value.selection?.filter, "and the page that changed them is not set back")
        assertEquals(2, source.saved.size, "nothing echoes back and forth")
    }

    @Test
    fun anOlderLookupNeverReplacesANewerOneAndClearingStopsIt() = viewModel(RecordingSource()) { vm ->
        vm.lookUp("slowname")
        advanceTimeBy(500)
        vm.lookUp("fast")
        advanceUntilIdle()
        assertEquals("fast", (vm.uiState.value.lookup as AccountLookup.Found).players.single().name)
        vm.lookUp("again")
        advanceTimeBy(500)
        vm.clearLookup()
        advanceUntilIdle()
        assertEquals(AccountLookup.Idle, vm.uiState.value.lookup, "a cleared lookup's answer does not come back")
    }

    @Test
    fun yourOwnAccountOpenedFromSearchIsNotVisiting() = viewModel(RecordingSource(isYou = true), visiting = 555) { vm ->
        assertNull(vm.uiState.value.visiting, "no \"Viewing you · Back to your stats\"")
    }

    @Test
    fun someoneElsesPageSaysItIsVisiting() = viewModel(RecordingSource(isYou = false), visiting = 7) { vm ->
        assertEquals(7L, vm.uiState.value.visiting)
    }
}
