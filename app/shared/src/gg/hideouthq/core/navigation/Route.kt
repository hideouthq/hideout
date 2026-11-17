package gg.hideouthq.core.navigation

import gg.hideouthq.core.model.ModCategory

sealed interface Route {
    data object Discover : Route

    /**
     * The catalogue. [heroId] and [category] preselect filters (a hero avatar, "See all"); [query]
     * carries text typed into search on another screen; [allHeroes] opens the full hero list.
     */
    data class Browse(
        val heroId: String? = null,
        val category: ModCategory? = null,
        val query: String? = null,
        val allHeroes: Boolean = false,
    ) : Route

    data class ModDetail(val modId: String) : Route

    /** One skin collection's page, opened from Discover; it borrows the Discover section. */
    data class Collection(val id: String) : Route

    /** Every skin collection, from Discover's "All". */
    data object Collections : Route

    data object Library : Route

    /** Match stats: your own, or [visiting]'s when opened from search. */
    data class Stats(val visiting: Long? = null) : Route

    /** One match, drilled into from Stats; it borrows the Stats section like a mod page borrows Browse. */
    data class StatsMatch(val matchId: Long, val visiting: Long? = null) : Route

    data object Settings : Route

    /** The crosshairs, opened on [tab]: Browse from the sidebar, Yours from Autoexec's crosshair row. */
    data class Crosshairs(val tab: CrosshairsTab = CrosshairsTab.Browse) : Route

    data object Autoexec : Route

    data object CustomGames : Route

    /** A mode's page, by its library entry's key. It borrows the section it was opened from, as a mod page does. */
    data class CustomGameMode(val key: String) : Route

    /** The game screen, which follows the session. */
    data object CustomGame : Route
}

enum class CrosshairsTab { Browse, Yours }

enum class Section { Discover, Browse, Library, Stats, Crosshairs, Autoexec, CustomGames, Settings }

/** The section a route belongs to on its own, or null for routes that borrow it, like the mod page. */
val Route.ownSection: Section?
    get() = when (this) {
        Route.Discover -> Section.Discover
        is Route.Browse -> Section.Browse
        is Route.ModDetail, is Route.Collection, Route.Collections -> null
        Route.Library -> Section.Library
        is Route.Stats -> Section.Stats
        is Route.StatsMatch, is Route.CustomGameMode -> null
        Route.Settings -> Section.Settings
        is Route.Crosshairs -> Section.Crosshairs
        Route.Autoexec -> Section.Autoexec
        Route.CustomGames, Route.CustomGame -> Section.CustomGames
    }

/** Where a sidebar click lands. Browse opens on All. */
val Section.root: Route
    get() = when (this) {
        Section.Discover -> Route.Discover
        Section.Browse -> Route.Browse()
        Section.Library -> Route.Library
        Section.Stats -> Route.Stats()
        Section.Settings -> Route.Settings
        Section.Crosshairs -> Route.Crosshairs()
        Section.Autoexec -> Route.Autoexec
        Section.CustomGames -> Route.CustomGames
    }

/** Whether a route shows the side panel when it first appears (screens can change it later). */
val Route.hasSidePanel: Boolean
    get() = this is Route.Browse || this is Route.ModDetail || this == Route.Library
