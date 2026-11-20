package gg.hideouthq.feature.moddetail

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.ui.windowWidth
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.ImageViewer
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.OpenPanelButton
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.PaneTopBar
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.moddetail.ModDetailTransition.installBar
import gg.hideouthq.feature.moddetail.components.About
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.feature.moddetail.components.PictureSnackbar
import gg.hideouthq.feature.moddetail.components.StageTabs
import gg.hideouthq.feature.moddetail.components.DetailBreadcrumb
import gg.hideouthq.feature.moddetail.components.DetailLoadError
import gg.hideouthq.feature.moddetail.components.DetailSidePanel
import gg.hideouthq.feature.moddetail.components.InstallBar
import gg.hideouthq.feature.moddetail.components.ViewerTools
import gg.hideouthq.feature.moddetail.viewer.SoundStageActions
import gg.hideouthq.feature.moddetail.viewer.StageActions
import gg.hideouthq.feature.moddetail.viewer.ViewportStatus
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.detail_details
import gg.hideouthq.resources.detail_missing_message
import gg.hideouthq.resources.detail_missing_title
import gg.hideouthq.resources.detail_open_source
import org.jetbrains.compose.resources.stringResource

interface ModDetailActions : StageActions, SoundStageActions {
    fun openCategory() = Unit
    fun useTool(tool: ViewerTool)
    fun showTab(tab: StageTab)
    /** Shows the picture Save image wrote in the file manager. */
    fun revealPicture()
    fun dismissPicture()
    fun install()
    fun changeFiles()
    fun cancelInstall()
    fun setProfile(profile: ProfileId, selected: Boolean)
    fun setFavourite(favourite: Boolean)
    fun showInLibrary() = Unit
    fun showFiles() = Unit
    fun uninstall() = Unit
    fun openSource() = Unit
    /** Opens what the mod needs: a listed mod's page in Hideout, or the page its author linked in the browser. */
    fun openNeed(need: Need) = Unit
    /** Installs [modId], a mod this one needs, into the active profile. */
    fun installNeed(modId: String) = Unit
    fun retryDetail()
    /** Opens the installed mod's compatibility details. */
    fun openCompat() = Unit
}

/**
 * [samplePosition] is a sound preview's position, read every frame by the sound stage.
 */
@Composable
fun ModDetailScreen(state: ModDetailUiState, actions: ModDetailActions, modal: WindowModal, modifier: Modifier = Modifier, samplePosition: () -> Int = { 0 }) {
    val header = state.header
    val scroll = rememberScrollState()
    var viewing by remember(header?.modId) { mutableStateOf<Int?>(null) }
    var viewport by remember(header?.modId, header?.previewFor(state.selectedFile)) { mutableStateOf<ViewportStatus>(ViewportStatus.NoModel) }
    val stage = header?.let { stageKind(state, it) }
    val tabbed = stage != null && stageTabbed(state, stage)
    val loading = state.preview is Loadable.Loading || viewport is ViewportStatus.Loading
    PaneScaffold(
        modifier = modifier,
        scrolled = scroll.isScrolled,
        staggered = true,
        topBar = {
            PaneTopBar(
                start = {
                    if (header != null) {
                        DetailBreadcrumb(ModLabels.plural(header.category), header.heroName ?: header.kind, actions::openCategory)
                    }
                },
                center = { if (tabbed) StageTabs(state.viewer.tab, stage, stageImages(state, header).size, actions::showTab) },
                end = {
                    Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.TopBar.ToolGap), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            stage == StageKind.Model && state.viewer.tab == StageTab.Preview -> ViewerTools(state.viewer, availableTools(viewport, loading), loading, actions::useTool)
                            state.detail?.sourceUrl != null -> LinkButton(stringResource(Res.string.detail_open_source), actions::openSource)
                        }
                        OpenPanelButton(
                            label = stringResource(Res.string.detail_details),
                            icon = HideoutIcons.Info,
                            iconOnly = windowWidth() < HideoutDimens.Detail.CompactTopBarWindowWidth,
                        )
                    }
                },
            )
        },
        sidePanel = {
            if (header != null) {
                Column(Modifier.fillMaxSize()) {
                    DetailSidePanel(
                        header, state.detail, state.installSizeBytes, state.installation.installedFileIds.toSet(), state.installation.compatStatus != null,
                        state.needs, state.installation.canInstall, actions::openNeed, actions::installNeed, Modifier.weight(1f),
                    )
                    InstallBar(state, actions, Modifier.installBar())
                }
            }
        },
    ) {
        when {
            header != null && stage != null -> Box(Modifier.fillMaxSize()) {
                DetailBody(state, header, stage, actions, scroll, samplePosition, onOpenImage = { viewing = it }, onViewportStatus = { viewport = it })
                PictureSnackbar(state.picture, actions::revealPicture, actions::dismissPicture, Modifier.align(Alignment.BottomCenter).padding(HideoutDimens.Snackbar.Margin))
                viewing?.let { index ->
                    ImageViewer(header.modId, stageImages(state, header), index, onIndexChange = { viewing = it }, onDismiss = { viewing = null }, modal = modal)
                }
            }
            !state.loading -> EmptyState(
                icon = HideoutIcons.Cube,
                title = stringResource(Res.string.detail_missing_title),
                message = stringResource(Res.string.detail_missing_message),
            )
        }
    }
}

private fun availableTools(viewport: ViewportStatus, loading: Boolean): Set<ViewerTool> = when {
    loading -> emptySet()
    viewport == ViewportStatus.Showing -> ViewerTool.entries.toSet()
    else -> setOf(ViewerTool.AutoSpin, ViewerTool.Scene, ViewerTool.Fullscreen)
}

@Composable
private fun DetailBody(
    state: ModDetailUiState,
    header: ModHeader,
    stage: StageKind,
    actions: ModDetailActions,
    scroll: ScrollState,
    samplePosition: () -> Int,
    onOpenImage: (Int) -> Unit,
    onViewportStatus: (ViewportStatus) -> Unit,
) {
    val padding = HideoutDimens.MainPane.ContentPadding
    var paneHeight by remember { mutableStateOf(HideoutDimens.Stage.Height) }
    val density = LocalDensity.current
    Box(Modifier.fillMaxSize().onSizeChanged { paneHeight = with(density) { it.height.toDp() } }) {
        val fullscreen = state.viewer.fullscreen && stage == StageKind.Model
        val stageHeight by animateDpAsState(
            targetValue = if (fullscreen) paneHeight - padding else HideoutDimens.Stage.Height,
            animationSpec = HideoutTheme.motion.spatialDefault(),
            label = "stageHeight",
        )
        LaunchedEffect(fullscreen) { if (fullscreen) scroll.animateScrollTo(0) }
        ScrollColumn(
            modifier = Modifier.fillMaxSize(),
            state = scroll,
            contentPadding = PaddingValues(start = padding, end = padding, bottom = padding),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.SectionGap),
        ) {
            DetailStage(state, header, stage, actions, stageHeight, samplePosition, onOpenImage, onViewportStatus)
            state.detailError?.let { error -> DetailLoadError(error, actions::retryDetail, Modifier.staggeredEnter(1)) }
            state.detail?.let { detail -> About(detail, stageCredit(state, stage), Modifier.staggeredEnter(1)) }
        }
    }
}
