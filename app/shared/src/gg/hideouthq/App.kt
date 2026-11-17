package gg.hideouthq

import gg.hideouthq.core.analytics.*

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.customGamesInSidebar
import gg.hideouthq.core.designsystem.component.AppWindow
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.navigation.Section
import gg.hideouthq.core.ui.FileImportHost
import gg.hideouthq.core.ui.LocalFileImport
import gg.hideouthq.core.ui.LocalInstallOrigins
import gg.hideouthq.core.ui.LocalPlaybackShortcut
import gg.hideouthq.core.ui.LocalEscapeClaim
import gg.hideouthq.core.ui.LocalSearchFocus
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import gg.hideouthq.core.ui.LocalWindowControlsSpot
import gg.hideouthq.core.ui.WindowControlsSpot
import gg.hideouthq.core.ui.rememberPatientClipboard
import gg.hideouthq.feature.customgames.JoinDialog
import gg.hideouthq.feature.customgames.QuitConfirm
import gg.hideouthq.feature.customgames.chipAway
import gg.hideouthq.feature.customgames.gameInProgress
import gg.hideouthq.feature.customgames.rememberPresent
import gg.hideouthq.feature.customgames.sessionRoute
import gg.hideouthq.feature.onboarding.OnboardingRoute
import gg.hideouthq.feature.profileshare.ProfileImportHost
import gg.hideouthq.feature.shell.AppShell
import gg.hideouthq.feature.stats.NoStatsSource
import kotlinx.coroutines.flow.filterNotNull

@Composable
fun App(state: HideoutAppState) {
    HideoutTheme(accent = state.container.preferences.appearance.collectAsState().value.accent) { AppContent(state) }
}

/**
 * The app without its theme, for entry points that add their own chrome inside the theme: first-run setup until it is
 * finished, then the shell. While the saved answer is read the window stays empty, so neither flashes.
 */
@Composable
fun AppContent(state: HideoutAppState) {
    val analytics = state.container.analytics
    if (analytics != null) {
        val choice by analytics.choice.collectAsState()
        LaunchedEffect(analytics) { analytics.load() }
        if (choice == null) { AppWindow {}; return }
        if (choice == AnalyticsChoice.NotDisclosed) {
            gg.hideouthq.feature.analytics.AnalyticsDisclosure(analytics)
            return
        }
    }
    val preferences = state.container.preferences
    val setupDone by preferences.setupDone.collectAsState()
    LaunchedEffect(preferences) { preferences.readSetup() }
    val statsPreferences by preferences.stats.collectAsState()
    val showStats = state.container.stats !== NoStatsSource && statsPreferences?.enabled != false
    LaunchedEffect(preferences) { preferences.readCustomGames() }
    val customGamesOn by preferences.customGames.collectAsState()
    val customGames = state.container.customGames
    val live = customGames?.live?.collectAsState()?.value ?: LiveSession()
    val showCustomGames = customGames != null && customGamesInSidebar(customGamesOn, live.session)
    // Waits for setup to finish, as there is no shell to show the page in before that. The flow keeps the newest link.
    if (customGames != null && setupDone == true) {
        LaunchedEffect(customGames) { customGames.lastLink.filterNotNull().collect { state.navigator.navigate(Route.CustomGames) } }
        LaunchedEffect(customGames) {
            var before = customGames.live.value
            customGames.live.collect { after ->
                sessionRoute(before, after)?.let(state.navigator::navigate)
                before = after
            }
        }
    }
    ProvideViewModelStoreOwner {
        CompositionLocalProvider(
            LocalUsageAnalytics provides analytics,
            LocalClipboard provides rememberPatientClipboard(),
            LocalSearchFocus provides state.searchFocus,
            LocalEscapeClaim provides state.escape,
            LocalPlaybackShortcut provides state.playback,
            LocalRemoteImages provides state.container.images,
            LocalInstallOrigins provides state.installOrigins,
            LocalFileImport provides state.fileImport,
            LocalWindowControlsSpot provides remember { WindowControlsSpot() },
            LocalModCardStyle provides preferences.appearance.collectAsState().value.modCards,
        ) {
            when (setupDone) {
                null -> AppWindow {}
                false -> OnboardingRoute(state.container, onImported = { state.navigator.navigate(Route.Library) })
                true -> Box(Modifier.fillMaxSize()) {
                    val onGame = state.navigator.current.route == Route.CustomGame
                    val endedAt = live.game?.endedAt
                    if (onGame && endedAt != null) SideEffect { state.gameEndSeen = endedAt }
                    AppShell(
                        navigator = state.navigator,
                        sidePanelOverlay = state.sidePanelOverlay,
                        modLibrary = state.container.modLibrary,
                        catalog = state.container.catalog,
                        profileShares = state.container.profileShares,
                        profileImports = state.container.profileImports,
                        modal = state.modal,
                        keys = state.keys,
                        launcher = state.container.launcher,
                        folders = state.folders,
                        destination = { entry -> Destination(entry, state) },
                        showStats = showStats,
                        showCustomGames = showCustomGames,
                        preferences = preferences,
                        appUpdates = state.container.appUpdates,
                        whatsNewOpen = state.whatsNewOpen,
                        onWhatsNewOpen = { state.whatsNewOpen = it },
                        hosting = customGames?.let { chipAway(rememberPresent(it, state.container.customGamesSite), endedAt, state.gameEndSeen) }?.takeIf { !onGame },
                        game = gameInProgress(live),
                        onOpenGame = { state.navigator.navigate(Route.CustomGame) },
                        onShowInstalled = { installId ->
                            state.libraryShow = installId
                            state.navigator.openSection(Section.Library)
                        },
                    )
                    FileImportHost(state.fileImport, state.container.modLibrary)
                    ProfileImportHost(state.container, state.modal)
                    if (customGames != null) {
                        JoinDialog(customGames, state.container.customGamesSite, state.modal)
                        QuitConfirm(state.quit, customGames, state.modal)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvideViewModelStoreOwner(content: @Composable () -> Unit) {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
