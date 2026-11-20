package gg.hideouthq.feature.moddetail

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.model.Requirement

/** Something the mod needs, as its page shows it. */
@Immutable
sealed interface Need {
    val name: String

    /** A mod in the catalogue, by its catalogue [name]: its page opens in Hideout, and [status] says whether it is installed and on. */
    data class Listed(override val name: String, val modId: String, val status: InstallStatus) : Need

    /** A page elsewhere, opened in the browser. */
    data class Elsewhere(override val name: String, val url: String) : Need

    /** Only named, with no page to open. */
    data class Named(override val name: String) : Need
}

/**
 * What [requirements] of mod [self] the page shows: a GameBanana mod the catalogue lists, by its title in [listed], with
 * [status] in the library; any other link as a page elsewhere; the rest by name.
 */
internal fun needsOf(requirements: List<Requirement>, self: String, listed: Map<String, String>, status: (String) -> InstallStatus): List<Need> =
    requirements.filter { it.modId != self }.map { requirement ->
        requirement.modId?.let { id -> listed[id]?.let { title -> Need.Listed(title, id, status(id)) } }
            ?: requirement.url?.let { Need.Elsewhere(requirement.name, it) }
            ?: Need.Named(requirement.name)
    }.distinct()
