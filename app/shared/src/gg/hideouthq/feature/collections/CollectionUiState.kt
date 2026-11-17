package gg.hideouthq.feature.collections

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.ui.catalog.CatalogItem

enum class CollectionSort { Popular, New }

@Immutable
data class CollectionUiState(
    val loading: Boolean = true,
    /** Null for a collection that is not published or has no skins in the catalogue. */
    val page: CollectionPage? = null,
    /** "Make a profile", while it is open. */
    val sheet: ProfileSheet? = null,
)

/**
 * One collection: its banner, the heroes to filter by (most skins first), the chosen filter and order, and the first
 * [cards] of the [matching] members that pass the filter.
 */
@Immutable
data class CollectionPage(
    val summary: CollectionSummary,
    val heroes: List<CollectionHero>,
    val hero: String?,
    val sort: CollectionSort,
    val cards: List<CatalogItem>,
    val matching: Int,
)

/**
 * Making a profile from a collection: its [name], why that name cannot be used, and one row per hero with the skin
 * picked for it. [blocked] says why the library cannot take a new profile now; [problem] why it refused the last one.
 */
@Immutable
data class ProfileSheet(
    val collection: String,
    val name: String,
    val nameProblem: LibraryProblem?,
    val rows: List<HeroPick>,
    val blocked: LibraryProblem?,
    val submitting: Boolean,
    val problem: LibraryProblem?,
) {
    val picks: Int get() = rows.count { it.picked != null }
    val canConfirm: Boolean get() = blocked == null && nameProblem == null && picks > 0 && !submitting
}

/** A hero's row: its skins in the collection, most downloaded first, and the one picked, or none. */
@Immutable
data class HeroPick(val hero: CollectionHero, val options: List<PickOption>, val picked: String?)

@Immutable
data class PickOption(val modId: String, val title: String, val installed: Boolean)
