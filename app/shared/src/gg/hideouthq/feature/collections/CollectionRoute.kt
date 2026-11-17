package gg.hideouthq.feature.collections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.copyText
import gg.hideouthq.feature.collections.components.MakeProfileSheet
import gg.hideouthq.feature.crosshairs.components.rememberCopied
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.collection_share_text
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun CollectionRoute(
    id: String,
    catalog: CatalogRepository,
    collections: CollectionsRepository,
    library: ModLibrary,
    modal: WindowModal,
    onSearch: (String) -> Unit,
    onDiscover: () -> Unit,
    onCollections: () -> Unit,
    onOpenMod: (String) -> Unit,
) {
    val viewModel = viewModel { CollectionViewModel(id, catalog, collections, library) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by rememberCopied()
    val heading = stringResource(Res.string.collection_share_text, state.page?.summary?.title.orEmpty())
    CollectionScreen(state, viewModel, copied, onSearch, onDiscover, onCollections, onOpenMod, onShare = {
        viewModel.shareText(heading)?.let { text ->
            scope.launch { copied = clipboard.copyText(text) }
        }
    })
    MakeProfileSheet(state.sheet, modal, viewModel)
}
