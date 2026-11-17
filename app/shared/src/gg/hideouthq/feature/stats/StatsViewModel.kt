package gg.hideouthq.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsSelection(
    val filter: StatsFilter,
    val tab: StatsTab = StatsTab.Overview,
    val heroFilter: Int? = null,
    val result: ResultFilter = ResultFilter.All,
    val shown: Int = MatchesPageSize,
    val selectedHero: Int? = null,
    val heroSort: HeroSort = HeroSort.MostPlayed,
    val partyOnly: Boolean = false,
)

data class StatsScreenState(
    /** Null until the saved filters have been read. */
    val selection: StatsSelection?,
    val content: StatsContent?,
    val search: SearchState = SearchState(),
    val lookup: AccountLookup = AccountLookup.Idle,
    val visiting: Long? = null,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class StatsViewModel(private val source: StatsSource, private val visiting: Long? = null) : ViewModel(), StatsPageActions {
    private val selection = MutableStateFlow<StatsSelection?>(null)
    private val searchBox = SearchBox(source)
    private val lookup = MutableStateFlow<AccountLookup>(AccountLookup.Idle)

    private val content = selection.filterNotNull().flatMapLatest { selection -> source.content(selection, visiting) }

    val uiState: StateFlow<StatsScreenState> = combine(selection, content.onStart<StatsContent?> { emit(null) }, searchBox.state, lookup) { selection, content, search, lookup ->
        val someoneElse = visiting.takeIf { content !is StatsContent.Active || !content.isYou }
        StatsScreenState(selection, content, search, lookup, someoneElse)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), StatsScreenState(null, null, visiting = visiting))

    private var saving: StatsFilter? = null
    private var lookupJob: Job? = null

    init {
        viewModelScope.launch {
            source.savedFilter.filterNotNull().collect { saved ->
                val pending = saving
                if (pending != null && saved != pending) return@collect
                saving = null
                selection.update { current ->
                    when {
                        current == null -> StatsSelection(filter = saved)
                        current.filter == saved -> current
                        else -> current.copy(filter = saved, shown = MatchesPageSize)
                    }
                }
            }
        }
    }

    override fun selectTab(tab: StatsTab) = edit { it.copy(tab = tab) }

    override fun setMode(mode: StatsMode) = setFilter { it.copy(mode = mode) }

    override fun setPeriod(period: StatsPeriod) = setFilter { it.copy(period = period) }

    override fun setHeroFilter(heroId: Int?) = edit { it.copy(heroFilter = heroId, shown = MatchesPageSize) }

    override fun setResult(result: ResultFilter) = edit { it.copy(result = result, shown = MatchesPageSize) }

    override fun showMore() = edit { it.copy(shown = it.shown + MatchesPageSize) }

    override fun openHero(heroId: Int) = edit { it.copy(tab = StatsTab.Heroes, selectedHero = heroId) }

    override fun selectHero(heroId: Int) = edit { it.copy(selectedHero = heroId) }

    override fun setHeroSort(sort: HeroSort) = edit { it.copy(heroSort = sort) }

    override fun setPartyOnly(partyOnly: Boolean) = edit { it.copy(partyOnly = partyOnly) }

    override fun showOverview() = edit { it.copy(tab = StatsTab.Overview) }

    override fun refresh() = source.refresh(visiting)

    override fun retry() = source.retry(visiting)

    override fun setQuery(text: String) = searchBox.setQuery(text)

    /** Looks [text] up for setup; a newer lookup or clearing it drops an older one's answer. */
    override fun lookUp(text: String) {
        lookupJob?.cancel()
        lookup.value = AccountLookup.Searching
        lookupJob = viewModelScope.launch { lookup.value = source.lookUp(text) }
    }

    override fun clearLookup() {
        lookupJob?.cancel()
        lookup.value = AccountLookup.Idle
    }

    override fun useAccount(accountId: Long) = source.useAccount(accountId)

    override fun turnOn() = source.setEnabled(true)

    private fun setFilter(change: (StatsFilter) -> StatsFilter) {
        val current = selection.value ?: return
        val filter = change(current.filter)
        if (filter == current.filter) return
        selection.value = current.copy(filter = filter, shown = MatchesPageSize)
        saving = filter
        source.saveFilter(filter)
    }

    private fun edit(change: (StatsSelection) -> StatsSelection) = selection.update { it?.let(change) }
}

private const val StopTimeoutMillis = 5_000L

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class SearchBox(source: StatsSource) {
    private val query = MutableStateFlow("")

    val state = combine(
        query,
        query.debounce(300).distinctUntilChanged().flatMapLatest { text -> if (text.isBlank()) flowOf(SearchState()) else source.search(text) }.onStart { emit(SearchState()) },
    ) { typed, found -> if (typed.isBlank()) SearchState() else if (found.query == typed.trim()) found else found.copy(query = typed, searching = true) }

    fun setQuery(text: String) {
        query.value = text
    }
}

data class StatsMatchState(val detail: MatchDetailState, val view: GraphView, val search: SearchState)

class StatsMatchViewModel(private val source: StatsSource, private val matchId: Long, private val visiting: Long?) : ViewModel(), StatsMatchActions {
    private val view = MutableStateFlow(GraphView.SoulsLead)
    private val searchBox = SearchBox(source)

    val uiState: StateFlow<StatsMatchState> = combine(source.match(matchId, visiting), view, searchBox.state, ::StatsMatchState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), StatsMatchState(MatchDetailState.Loading, GraphView.SoulsLead, SearchState()))

    override fun setView(view: GraphView) {
        this.view.value = view
    }

    override fun setQuery(text: String) = searchBox.setQuery(text)

    override fun retry() = source.retry(visiting)

    override fun checkMatch() = source.checkMatch(matchId, visiting)

    override fun turnOn() = source.setEnabled(true)
}
