package gg.hideouthq.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.catalog.CatalogItem
import gg.hideouthq.core.ui.catalog.toCatalogItem
import gg.hideouthq.core.ui.catalog.whileCatalogLoads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val StopTimeoutMillis = 5_000L

data class BrowseStart(
    val heroId: String? = null,
    val category: ModCategory? = null,
    val query: String? = null,
    val allHeroes: Boolean = false,
)

/** The catalogue with live search, category, sort and the filter panel. One per visit, so going back restores it. */
class BrowseViewModel(
    private val catalog: CatalogRepository,
    start: BrowseStart,
    private val categories: Map<ModCategory, String>,
    library: ModLibrary? = null,
    preferences: Preferences? = null,
) : ViewModel(), BrowseActions {
    private val filters = MutableStateFlow(
        BrowseFilters(
            category = start.category,
            query = start.query.orEmpty(),
            heroIds = setOfNotNull(start.heroId),
            showAllHeroes = start.allHeroes,
        ),
    )

    private fun effective(filters: BrowseFilters): BrowseFilters =
        if (catalog.previewsAvailable) filters else filters.copy(only3d = false)

    private val availableFilters = filters.map(::effective).distinctUntilChanged()
    private val resultFilters = availableFilters.map { it.copy(heroQuery = "", showAllHeroes = false) }.distinctUntilChanged()
    private val hideMature = preferences?.hideMature?.filterNotNull() ?: flowOf(false)

    private val results = combine(catalog.mods, catalog.heroes, resultFilters, hideMature) { mods, heroes, filters, hidden ->
        val heroMap = heroes.associateBy { it.id }
        val mature = when {
            hidden -> MatureMods.Hidden
            filters.blurMature -> MatureMods.Blurred
            else -> MatureMods.Shown
        }
        val items = BrowseQuery.results(mods, heroMap, filters, mature, catalog.today, categories)
            .map { it.toCatalogItem(heroMap).copy(blurred = it.mature && mature == MatureMods.Blurred) }
        Results(items, heroes, mature)
    }

    private val badges = library?.state?.map { state ->
        buildMap<String, InstallStatus> {
            (state.library as? LibraryStatus.Ready)?.records?.forEach { record ->
                put(record.modId, InstallStatus.Installed(record.enabled))
            }
            state.operations.forEach { (id, operation) ->
                (operation.stage as? OperationStage.Downloading)?.let {
                    put(id, InstallStatus.Downloading(it.progress.percent))
                }
            }
        }
    }?.distinctUntilChanged() ?: flowOf(emptyMap())

    private val badgedResults = combine(results, badges) { results, badges ->
        Results(results.items.map { it.copy(installation = badges[it.id]) }, results.heroes, results.mature)
    }

    val uiState: StateFlow<BrowseUiState> = combine(badgedResults, catalog.popularHeroIds, availableFilters) { results, popular, filters ->
        val heroes = results.heroes
        BrowseUiState(
            loading = false,
            filters = filters,
            results = results.items,
            suggestions = BrowseQuery.suggestions(filters.query, heroes, filters, categories),
            heroChips = BrowseQuery.heroChips(heroes, popular, filters),
            heroCount = heroes.size,
            selectedHeroes = heroes.filter { it.id in filters.heroIds }.map { HeroChip(it.id, it.name, true) },
            viewerFilters = catalog.previewsAvailable,
            mature = results.mature,
        )
    }
        .combine(catalog.freshness) { state, freshness -> state.copy(freshness = freshness) }
        .whileCatalogLoads(catalog.freshness) { freshness ->
            val error = freshness.error.takeUnless { freshness.checking }
            BrowseUiState(loading = error == null, loadError = error, freshness = freshness, filters = effective(filters.value), viewerFilters = catalog.previewsAvailable)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), BrowseUiState(filters = effective(filters.value), viewerFilters = catalog.previewsAvailable))

    init {
        preferences?.let { viewModelScope.launch { it.readHideMature() } }
    }

    private var searchedFrom: ModCategory? = null

    /** A search looks through every category; a category chosen while searching narrows it. */
    override fun setQuery(value: String) = filters.update {
        val starts = it.query.isBlank() && value.isNotBlank()
        val ends = it.query.isNotBlank() && value.isBlank()
        when {
            starts -> it.copy(query = value, category = null).also { _ -> searchedFrom = it.category }
            ends -> it.copy(query = value, category = it.category ?: searchedFrom).also { searchedFrom = null }
            else -> it.copy(query = value)
        }
    }

    override fun setCategory(value: ModCategory?) = filters.update {
        searchedFrom = null
        it.copy(category = value)
    }
    override fun setSort(value: BrowseSort) = filters.update { it.copy(sort = value) }
    override fun setHeroQuery(value: String) = filters.update { it.copy(heroQuery = value) }
    override fun setShowAllHeroes(value: Boolean) = filters.update { it.copy(showAllHeroes = value) }
    override fun setOnly3d(value: Boolean) = filters.update { it.copy(only3d = value) }
    override fun setCurrentPatch(value: Boolean) = filters.update { it.copy(currentPatch = value) }
    override fun setBlurMature(value: Boolean) = filters.update { it.copy(blurMature = value) }
    override fun setUpdated(value: UpdatedWithin) = filters.update { it.copy(updated = value) }

    override fun toggleHero(heroId: String, selected: Boolean) = filters.update {
        it.copy(heroIds = if (selected) it.heroIds + heroId else it.heroIds - heroId)
    }

    /** Applies a "Filter by" suggestion and clears the search text. */
    override fun applySuggestion(suggestion: BrowseSuggestion) = filters.update {
        searchedFrom = null
        when (suggestion) {
            is BrowseSuggestion.Hero -> it.copy(heroIds = it.heroIds + suggestion.id, query = "")
            is BrowseSuggestion.Category -> it.copy(category = suggestion.category, query = "")
        }
    }

    /** The panel's Clear: heroes, switches and the update window back to their defaults. */
    override fun clearPanel() = filters.update {
        val defaults = BrowseFilters()
        it.copy(
            heroIds = defaults.heroIds,
            heroQuery = defaults.heroQuery,
            only3d = defaults.only3d,
            currentPatch = defaults.currentPatch,
            blurMature = defaults.blurMature,
            updated = defaults.updated,
        )
    }

    override fun clearAll() = filters.update { BrowseFilters(sort = it.sort) }

    /** Checks the catalogue for new and changed mods now, or tries loading it again after it failed. */
    override fun refresh() = catalog.refresh()

    private class Results(val items: List<CatalogItem>, val heroes: List<Hero>, val mature: MatureMods)
}
