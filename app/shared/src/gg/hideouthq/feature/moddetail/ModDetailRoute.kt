package gg.hideouthq.feature.moddetail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.analytics.LocalUsageAnalytics
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.core.data.SoundSamplePlayer
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.setFolder
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.navigation.PreviewRequest
import gg.hideouthq.core.ui.CompatDialog
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.launch

@Composable
fun ModDetailRoute(
    modId: String,
    previewRequest: PreviewRequest?,
    catalog: CatalogRepository,
    installations: ModLibrary,
    player: SoundSamplePlayer,
    previews: PreviewRepository,
    folders: FolderOpener,
    modal: WindowModal,
    onShowLibrary: () -> Unit,
    onBrowseCategory: (ModCategory) -> Unit,
    preferences: Preferences? = null,
    files: ViewerFiles = ViewerFiles.Unavailable,
) {
    val analytics = LocalUsageAnalytics.current
    val viewModel = viewModel { ModDetailViewModel(modId, catalog, installations, player, previews, opened = previewRequest, preferences = preferences, files = files, analytics = analytics) }
    LaunchedEffect(viewModel, previewRequest) { previewRequest?.let(viewModel::show) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val samplePosition = viewModel.samplePosition.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.stopSample() } }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var compat by remember(modId) { mutableStateOf(false) }
    val pickTitle = stringResource(Res.string.scene_pick_image)
    val saveTitle = stringResource(Res.string.save_image_title)
    val actions = remember(viewModel, onBrowseCategory, onShowLibrary, uriHandler, folders, files, modal, pickTitle, saveTitle) {
        object : ModDetailActions by viewModel {
            override fun openCategory() { viewModel.uiState.value.header?.let { onBrowseCategory(it.category) } }
            override fun openSource() { viewModel.uiState.value.detail?.sourceUrl?.let(uriHandler::openUri) }
            override fun showInLibrary() = onShowLibrary()
            override fun showFiles() { viewModel.uiState.value.installation.record?.let { record -> scope.launch { folders.open(record.gameRoot, record.setFolder()) } } }
            override fun uninstall() { viewModel.uiState.value.installation.record?.let { installations.uninstall(setOf(it.installId)) } }
            override fun openCompat() { compat = true }
            override fun chooseBackdrop() {
                scope.launch {
                    catching({ viewModel.showNotice(PictureNotice.BackdropFailed) }) { viewModel.useBackdrop(modal.holding { files.importBackdrop(pickTitle) }) }
                }
            }
            override fun savePicture(picture: ImageBitmap) {
                scope.launch {
                    val shown = viewModel.uiState.value
                    val name = pictureFileName(shown.header?.title.orEmpty(), shown.selectedVersion?.let { shown.header?.previewVariantNames?.get(it.fileId) })
                    val notice = catching({ PictureNotice.Failed }) { modal.holding { files.savePicture(picture, saveTitle, name) }?.let(PictureNotice::Saved) }
                    notice?.let(viewModel::showNotice)
                }
            }
        }
    }
    val scenePanel = state.viewer.sceneOpen
    DisposableEffect(scenePanel, modal) {
        val holder = Any()
        if (scenePanel) modal.hold(holder)
        onDispose { modal.release(holder) }
    }
    ModDetailScreen(state, actions, modal, samplePosition = { samplePosition.value })
    val page = state.installation
    CompatDialog(
        title = if (compat) page.record?.title else null,
        install = page.compat,
        enabled = page.record?.enabled == true,
        unavailable = page.compatUnavailable,
        canChange = page.canEditChoices,
        modal = modal,
        onApply = viewModel::applyRepair,
        onOriginal = viewModel::useOriginal,
        onCheckAgain = viewModel::checkCompatibility,
        onDismiss = { compat = false },
    )
}

private suspend fun <T> WindowModal.holding(block: suspend () -> T): T {
    val holder = Any()
    hold(holder)
    return try {
        block()
    } finally {
        release(holder)
    }
}
