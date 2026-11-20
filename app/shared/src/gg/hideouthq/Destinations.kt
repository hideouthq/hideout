package gg.hideouthq

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.navigation.HistoryEntry
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.navigation.Section
import gg.hideouthq.feature.autoexec.AutoexecRoute
import gg.hideouthq.feature.collections.CollectionRoute
import gg.hideouthq.feature.collections.CollectionsRoute
import gg.hideouthq.feature.browse.BrowseRoute
import gg.hideouthq.feature.browse.BrowseStart
import gg.hideouthq.feature.crosshairs.CrosshairsRoute
import gg.hideouthq.feature.customgames.CustomGameModeRoute
import gg.hideouthq.feature.customgames.CustomGameRoute
import gg.hideouthq.feature.customgames.CustomGamesRoute
import gg.hideouthq.feature.customgames.CustomGamesShelf
import gg.hideouthq.feature.discover.DiscoverNavigation
import gg.hideouthq.feature.discover.DiscoverRoute
import gg.hideouthq.feature.library.LibraryRoute
import gg.hideouthq.feature.moddetail.ModDetailRoute
import gg.hideouthq.feature.settings.SettingsRoute
import gg.hideouthq.feature.settings.SettingsTab
import gg.hideouthq.feature.stats.NoStatsSource
import gg.hideouthq.feature.stats.StatsMatchRoute
import gg.hideouthq.feature.stats.StatsNavigation
import gg.hideouthq.feature.stats.StatsRoute

private class DiscoverLinks(private val navigator: HideoutNavigator) : DiscoverNavigation {
    override fun search(query: String) = navigator.search(query)
    override fun openMod(modId: String) = navigator.navigate(Route.ModDetail(modId))
    override fun previewMod(modId: String) = navigator.previewMod(modId)
    override fun browseHero(heroId: String) = navigator.navigate(Route.Browse(heroId = heroId))
    override fun browseAllHeroes() = navigator.navigate(Route.Browse(allHeroes = true))
    override fun browseCategory(category: ModCategory) = navigator.navigate(Route.Browse(category = category))
    override fun openCollection(id: String) = navigator.navigate(Route.Collection(id))
    override fun openCollections() = navigator.navigate(Route.Collections)
}

private fun statsLinks(state: HideoutAppState) = StatsNavigation(state.navigator) { state.container.preferences.stats.value?.accountId }

private fun HideoutNavigator.search(query: String) {
    if (query.isNotEmpty()) navigate(Route.Browse(query = query))
}

@Composable
internal fun Destination(entry: HistoryEntry, state: HideoutAppState) {
    val navigator = state.navigator
    when (val route = entry.route) {
        Route.Discover -> DiscoverRoute(
            catalog = state.container.catalog,
            installations = state.container.modLibrary,
            player = state.container.soundPlayer,
            navigation = remember(navigator) { DiscoverLinks(navigator) },
            news = state.container.news,
            modal = state.modal,
            preferredHeroes = state.container.preferences.preferredHeroes,
            collections = state.container.collections,
            customGames = { modifier ->
                val on by state.container.preferences.customGames.collectAsState()
                state.container.customGames?.takeIf { on == true }?.let { games ->
                    CustomGamesShelf(games, onOpenMode = { navigator.navigate(Route.CustomGameMode(it)) }, onOpenAll = { navigator.openSection(Section.CustomGames) }, modifier)
                }
            },
        )
        is Route.Collection -> CollectionRoute(
            id = route.id,
            catalog = state.container.catalog,
            collections = state.container.collections,
            library = state.container.modLibrary,
            modal = state.modal,
            onSearch = navigator::search,
            onDiscover = { navigator.openSection(Section.Discover) },
            onCollections = { navigator.navigate(Route.Collections) },
            onOpenMod = { navigator.navigate(Route.ModDetail(it)) },
        )
        Route.Collections -> CollectionsRoute(
            catalog = state.container.catalog,
            collections = state.container.collections,
            onSearch = navigator::search,
            onDiscover = { navigator.openSection(Section.Discover) },
            onOpen = { navigator.navigate(Route.Collection(it)) },
        )
        is Route.Browse -> BrowseRoute(
            catalog = state.container.catalog,
            library = state.container.modLibrary,
            preferences = state.container.preferences,
            start = BrowseStart(route.heroId, route.category, route.query, route.allHeroes),
            onOpenMod = { navigator.navigate(Route.ModDetail(it)) },
        )
        is Route.ModDetail -> ModDetailRoute(
            modId = route.modId,
            previewRequest = navigator.previewRequest(entry.id),
            catalog = state.container.catalog,
            installations = state.container.modLibrary,
            player = state.container.soundPlayer,
            previews = state.container.previews,
            folders = state.folders,
            modal = state.modal,
            onShowLibrary = { navigator.navigate(Route.Library) },
            onBrowseCategory = { navigator.navigate(Route.Browse(category = it)) },
            onOpenMod = { navigator.navigate(Route.ModDetail(it)) },
            preferences = state.container.preferences,
            files = state.container.viewerFiles,
        )
        Route.Library -> LibraryRoute(
            library = state.container.modLibrary,
            migration = state.container.migration,
            folderPicker = state.container.folderPicker,
            catalog = state.container.catalog,
            preferences = state.container.preferences,
            folders = state.folders,
            modal = state.modal,
            onViewPage = { navigator.navigate(Route.ModDetail(it)) },
            onDiscover = { navigator.navigate(Route.Discover) },
            filePriorities = state.container.filePriorities,
            show = state.libraryShow,
            onShown = { state.libraryShow = null },
        )
        is Route.Stats -> StatsRoute(state.container.stats, route.visiting, remember(navigator) { statsLinks(state) })
        is Route.StatsMatch -> StatsMatchRoute(state.container.stats, route.matchId, route.visiting, remember(navigator) { statsLinks(state) })
        is Route.Crosshairs -> CrosshairsRoute(
            crosshairs = state.container.crosshairs,
            community = state.container.communityCrosshairs,
            tab = route.tab,
            modal = state.modal,
            onSearch = navigator::search,
        )
        Route.Autoexec -> AutoexecRoute(
            autoexec = state.container.autoexec,
            library = state.container.modLibrary,
            files = state.container.configFiles,
            onSearch = navigator::search,
            onChooseGame = {
                state.settingsTab = SettingsTab.Game
                navigator.navigate(Route.Settings)
            },
        )
        Route.CustomGames -> state.container.customGames?.let { games ->
            val preferences = state.container.preferences
            val notifyServers by preferences.notifyServers.collectAsState()
            val scope = rememberCoroutineScope()
            CustomGamesRoute(
                games, state.container.folderPicker, state.modal, { navigator.navigate(Route.CustomGameMode(it)) }, navigator::search,
                notifyServers, onNotifyServers = { on -> scope.launch { preferences.setNotifyServers(on) } },
            )
        }
        is Route.CustomGameMode -> state.container.customGames?.let { games ->
            CustomGameModeRoute(games, route.key, state.modal, { navigator.openSection(Section.CustomGames) }, navigator::search)
        }
        Route.CustomGame -> state.container.customGames?.let { games ->
            CustomGameRoute(
                games, state.container.customGamesSite, state.container.about.version, state.modal,
                onLibrary = { navigator.openSection(Section.CustomGames) }, onOpenMode = { navigator.navigate(Route.CustomGameMode(it)) }, onSearch = navigator::search,
            )
        }
        Route.Settings -> SettingsRoute(
            analytics = state.container.analytics,
            library = state.container.modLibrary,
            preferences = state.container.preferences,
            folderPicker = state.container.folderPicker,
            folders = state.folders,
            about = state.container.about,
            modal = state.modal,
            tab = state.settingsTab,
            onTab = { state.settingsTab = it },
            statsAvailable = state.container.stats !== NoStatsSource,
            customGames = state.container.customGames,
            statsService = state.container.statsService,
            onStatsAccount = { navigator.navigate(Route.Stats()) },
            reportExport = state.container.reportExport,
            previewFiles = state.container.previewFiles,
            appUpdates = state.container.appUpdates,
            onWhatsNew = { state.whatsNewOpen = true },
        )
    }
}
