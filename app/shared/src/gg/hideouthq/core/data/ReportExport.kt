package gg.hideouthq.core.data

import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.report.CustomGamesFacts

/** A bug report, saved as a text file where the user chooses: this computer, the game, the library, custom games, the settings and Hideout's log. */
fun interface ReportExport {
    /**
     * Asks where to save in a dialog called [title] and saves the report on [library], [customGames] and [settings] there
     * with the user's name left out; the file's name, or null when the user cancels. A report that cannot be written throws.
     * [customGames] is null where this build has none.
     */
    suspend fun export(title: String, library: LibraryState, customGames: CustomGamesFacts?, settings: SettingsSnapshot): String?

    companion object {
        val Unavailable = ReportExport { _, _, _, _ -> null }
    }
}
