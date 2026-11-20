package gg.hideouthq.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.analytics.AnalyticsChoice
import gg.hideouthq.core.analytics.LocalUsageAnalytics
import gg.hideouthq.core.appupdate.AppUpdates
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.GameLauncher
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.designsystem.component.AppWindow
import gg.hideouthq.core.designsystem.component.NoticeCard
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.navigation.HideoutNavDisplay
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.navigation.HistoryEntry
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.sharing.InstallLinks
import gg.hideouthq.core.sharing.ProfileImports
import gg.hideouthq.core.sharing.ProfileShares
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.LocalPaneFrame
import gg.hideouthq.core.ui.SidePanelOverlay
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.copyText
import gg.hideouthq.core.ui.windowWidth
import gg.hideouthq.feature.appupdate.AppUpdateToast
import gg.hideouthq.feature.analytics.AnalyticsNotice
import gg.hideouthq.feature.appupdate.UpdateChip
import gg.hideouthq.feature.appupdate.WhatsNewDialog
import gg.hideouthq.feature.appupdate.updateChipState
import gg.hideouthq.feature.customgames.GameInProgress
import gg.hideouthq.feature.customgames.HostingChip
import gg.hideouthq.feature.customgames.HostingChipView
import gg.hideouthq.feature.install.InstallChoiceHost
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.notice_dismiss
import gg.hideouthq.resources.notice_try_again
import gg.hideouthq.resources.sidebar_profiles
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
fun AppShell(
    navigator: HideoutNavigator,
    sidePanelOverlay: SidePanelOverlay,
    modLibrary: ModLibrary,
    catalog: CatalogRepository,
    profileShares: ProfileShares,
    profileImports: ProfileImports,
    installLinks: InstallLinks,
    modal: WindowModal,
    keys: ShellKeys,
    launcher: GameLauncher,
    folders: FolderOpeningState,
    destination: @Composable (HistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
    showStats: Boolean = false,
    showCustomGames: Boolean = false,
    preferences: Preferences,
    appUpdates: AppUpdates,
    whatsNewOpen: Boolean,
    onWhatsNewOpen: (Boolean) -> Unit,
    hosting: HostingChipView? = null,
    game: GameInProgress? = null,
    onOpenGame: () -> Unit = {},
    onShowInstalled: (String) -> Unit = {},
) {
    val viewModel = viewModel { ShellViewModel(modLibrary, launcher, profileShares, profileImports, catalog::cachedMod) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(installLinks, viewModel) {
        installLinks.incoming.collect { link -> viewModel.openInstallLink(link)?.let { navigator.navigate(Route.ModDetail(it)) } }
    }
    val clipboard = LocalClipboard.current
    LaunchedEffect(state.shareLink) { state.shareLink?.let { link -> viewModel.linkCopied(link, clipboard.copyText(link.text)) } }
    val appUpdate by appUpdates.state.collectAsStateWithLifecycle()
    val savedCollapsed by preferences.sidebarCollapsed.collectAsState()
    val scope = rememberCoroutineScope()
    val narrow = windowWidth() < 1100.dp
    var manual by remember(narrow) { mutableStateOf<Boolean?>(null) }
    val collapsed = manual ?: (narrow || savedCollapsed)
    val toggleSidebar = {
        manual = !collapsed
        scope.launch { preferences.setSidebarCollapsed(!collapsed) }
        Unit
    }
    ShellKeysEffect(keys, toggleSidebar)
    var panel by remember { mutableStateOf<ChipPanel?>(null) }
    val toggle = { chip: ChipPanel -> panel = if (panel == chip) null else chip }
    val analytics = LocalUsageAnalytics.current
    val analyticsChoice = analytics?.choice?.collectAsState()?.value
    val showAnalytics = analyticsChoice == AnalyticsChoice.NotDisclosed
    val chipsShown = setOfNotNull(
        ChipPanel.Hosting.takeIf { hosting != null },
        ChipPanel.Update.takeIf { updateChipState(appUpdate, game) != null },
        ChipPanel.Analytics.takeIf { showAnalytics },
    )
    LaunchedEffect(chipsShown) { if (panel !in chipsShown) panel = null }
    LaunchedEffect(analyticsChoice) { if (showAnalytics && panel == null) panel = ChipPanel.Analytics }
    AppWindow(modifier) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Window.PaneGap)) {
            Sidebar(state, navigator.activeSection, navigator::openSection, viewModel::play, viewModel, showStats, showCustomGames, game != null, collapsed, toggleSidebar)
            ContentFrame(navigator, sidePanelOverlay, Modifier.weight(1f).fillMaxSize(), chips = {
                if (showAnalytics) AnalyticsNotice(analytics, panel == ChipPanel.Analytics, { toggle(ChipPanel.Analytics) })
                hosting?.let { HostingChip(it, panel == ChipPanel.Hosting, { toggle(ChipPanel.Hosting) }, onOpenGame) }
                UpdateChip(appUpdate, game, appUpdates, panel == ChipPanel.Update, { toggle(ChipPanel.Update) }, { onWhatsNewOpen(true) })
            }) {
                Box(Modifier.fillMaxSize()) {
                    HideoutNavDisplay(navigator, Modifier.fillMaxSize(), destination)
                    val frame = LocalPaneFrame.current
                    Box(Modifier.fillMaxSize().layout { measurable, constraints ->
                        val width = frame?.mainWidth?.roundToInt()?.coerceIn(0, constraints.maxWidth) ?: constraints.maxWidth
                        val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                    }) {
                        Column(Modifier.align(Alignment.BottomStart).padding(HideoutDimens.Notice.FloatingMargin)) {
                            LibraryNotices(state.notices, viewModel, onShowInstalled)
                            FolderOpeningNotice(folders)
                            if (state.profileEditor == null) state.profileProblem?.let { failure ->
                                val retry = failure.switchTarget != null
                                NoticeCard(
                                    lead = stringResource(Res.string.sidebar_profiles),
                                    message = LibraryLabels.problem(failure.problem),
                                    primaryLabel = stringResource(if (retry) Res.string.notice_try_again else Res.string.notice_dismiss),
                                    onPrimary = { if (retry) viewModel.retryProfileSwitch() else viewModel.dismissProfileProblem() },
                                    primaryEnabled = !retry || state.canEditChoices,
                                    secondaryLabel = if (retry) stringResource(Res.string.notice_dismiss) else null,
                                    onSecondary = viewModel::dismissProfileProblem,
                                )
                            }
                        }
                        ShellSnackbarHost(state.snackbar, state.canEditChoices, viewModel::undo, viewModel::dismissSnackbar, Modifier.align(Alignment.BottomCenter).padding(HideoutDimens.Snackbar.Margin))
                        AppUpdateToast(
                            appUpdate.justUpdatedTo.takeIf { state.snackbar == null },
                            onWhatsNew = {
                                appUpdates.acknowledgeUpdated()
                                onWhatsNewOpen(true)
                            },
                            onSeen = appUpdates::acknowledgeUpdated,
                            Modifier.align(Alignment.BottomCenter).padding(HideoutDimens.AppUpdate.ToastMargin),
                        )
                    }
                }
            }
        }
        InstallChoiceHost(modLibrary, catalog, navigator, modal)
        ProfileDialogs(state, viewModel, modal, collapsed)
        WhatsNewDialog(whatsNewOpen, appUpdate, game, appUpdates, modal, onClose = { onWhatsNewOpen(false) })
    }
}

private enum class ChipPanel { Hosting, Update, Analytics }
