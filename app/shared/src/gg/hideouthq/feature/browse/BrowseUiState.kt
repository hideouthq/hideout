package gg.hideouthq.feature.browse

import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.catalog.CatalogItem

enum class BrowseSort { Trending, Downloads, Newest }

/** How recently a mod must have been updated. */
enum class UpdatedWithin(val days: Int?) { Any(null), Week(7), Month(30), Year(365) }

data class BrowseFilters(
    /** Null means All. */
    val category: ModCategory? = null,
    val sort: BrowseSort = BrowseSort.Trending,
    val query: String = "",
    val heroIds: Set<String> = emptySet(),
    val heroQuery: String = "",
    val showAllHeroes: Boolean = false,
    val only3d: Boolean = false,
    val currentPatch: Boolean = true,
    val blurMature: Boolean = true,
    val updated: UpdatedWithin = UpdatedWithin.Any,
    val favourites: Boolean = false,
) {
    /** A filter the user set narrows the results, beyond the search words. A search looks through every category. */
    val narrowed: Boolean get() = heroIds.isNotEmpty() || only3d || updated != UpdatedWithin.Any || favourites
}

/** What Browse does with mature mods: leaves them out (Settings), or lists them with the cover blurred or as it is (the panel). */
enum class MatureMods { Hidden, Blurred, Shown }

sealed interface BrowseSuggestion {
    data class Hero(val id: String, val name: String) : BrowseSuggestion
    data class Category(val category: ModCategory) : BrowseSuggestion
}

data class HeroChip(val id: String, val name: String, val selected: Boolean)

data class BrowseUiState(
    val loading: Boolean = true,
    /** Set while there is nothing to show because the catalogue could not be loaded. */
    val loadError: DataError? = null,
    val filters: BrowseFilters = BrowseFilters(),
    val results: List<CatalogItem> = emptyList(),
    val suggestions: List<BrowseSuggestion> = emptyList(),
    /** Hero chips the panel shows right now (short list, everything, or matches for the hero search). */
    val heroChips: List<HeroChip> = emptyList(),
    val heroCount: Int = 0,
    /** Selected heroes, for the removable chips in the result line. */
    val selectedHeroes: List<HeroChip> = emptyList(),
    /** Whether the "3D preview" switch applies: there is a preview host. */
    val viewerFilters: Boolean,
    val mature: MatureMods = MatureMods.Blurred,
    val freshness: CatalogFreshness = CatalogFreshness(),
    val hasFavourites: Boolean = false,
)
