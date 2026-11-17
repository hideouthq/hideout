package gg.hideouthq.feature.profileshare

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.library.LibraryProblem

@Immutable
data class ProfileImportUiState(
    /** The import, while it is open. */
    val sheet: ImportSheet? = null,
)

/** Why a pasted link or code led to no profile. */
enum class ImportProblem { Unreadable, Missing, Unreachable }

sealed interface ImportSheet {
    /** The field a link or code is pasted into; [problem] says why the last one led nowhere. [site] is the host codes are looked up on. */
    @Immutable
    data class Asking(val text: String, val problem: ImportProblem?, val site: String?) : ImportSheet {
        val canRead: Boolean get() = text.isNotBlank()
    }

    /** The site is asked for the profile a code names. */
    data object Looking : ImportSheet

    /**
     * The shared profile to make, [shared] as it was called: its [name], why that name cannot be used, and its mods in load order, winner first.
     * [blocked] says why the library cannot take a new profile now; [problem] why it refused the last one.
     */
    @Immutable
    data class Reviewing(
        val shared: String,
        val name: String,
        val nameProblem: LibraryProblem?,
        val mods: List<SharedRow>,
        val blocked: LibraryProblem?,
        val submitting: Boolean,
        val problem: LibraryProblem?,
    ) : ImportSheet {
        val canConfirm: Boolean get() = blocked == null && nameProblem == null && !submitting
    }
}

/** One mod of a shared profile; [title] and the rest are null when neither the catalogue nor the library knows it. */
@Immutable
data class SharedRow(val modId: String, val submission: Long, val title: String?, val hero: String?, val coverUrl: String?, val installed: Boolean)
