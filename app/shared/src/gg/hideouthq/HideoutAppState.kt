package gg.hideouthq

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.EscapeClaim
import gg.hideouthq.core.ui.FileImportState
import gg.hideouthq.core.ui.InstallOrigins
import gg.hideouthq.core.ui.PlaybackShortcut
import gg.hideouthq.core.ui.SearchFocus
import gg.hideouthq.core.ui.SidePanelOverlay
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.customgames.QuitGuard
import gg.hideouthq.feature.settings.SettingsTab
import gg.hideouthq.feature.shell.FolderOpeningState
import gg.hideouthq.feature.shell.ShellKeys
import kotlin.time.Instant

/** App-wide UI state that outlives any one screen: dependencies, history and window keys. */
@Stable
class HideoutAppState(val container: AppContainer, start: Route = Route.Discover) {
    val navigator = HideoutNavigator(start)
    val searchFocus = SearchFocus()
    val playback = PlaybackShortcut()
    val sidePanelOverlay = SidePanelOverlay()
    val modal = WindowModal()
    val installOrigins = InstallOrigins()
    val fileImport = FileImportState(container.modLibrary, container.filePicker, modal)
    val folders = FolderOpeningState(container.folderOpener)
    val escape = EscapeClaim()
    val keys = ShellKeys(navigator, searchFocus, playback, sidePanelOverlay, modal, escape)
    val quit = QuitGuard()

    /** The Settings tab last shown, kept for the session. */
    var settingsTab by mutableStateOf(SettingsTab.Game)

    var whatsNewOpen by mutableStateOf(false)

    /** The installed mod Library is to pick when it shows next, as a notice's Show in Library asks; null once shown. */
    var libraryShow by mutableStateOf<String?>(null)

    /** When the custom game whose result the game screen last showed ended, so its top-bar chip stays away. */
    var gameEndSeen by mutableStateOf<Instant?>(null)
}

@Composable
fun rememberHideoutAppState(container: AppContainer, start: Route = Route.Discover): HideoutAppState =
    remember(container, start) { HideoutAppState(container, start) }
