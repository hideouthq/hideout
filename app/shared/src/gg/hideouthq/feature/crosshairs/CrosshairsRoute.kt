package gg.hideouthq.feature.crosshairs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.crosshair.Crosshairs
import gg.hideouthq.core.crosshair.community.CommunityCrosshairs
import gg.hideouthq.core.navigation.CrosshairsTab
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.copyText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

interface CrosshairsActions : CrosshairsPageActions {
    /** Copy code: the community crosshair in the panel, as a share code. */
    fun copyCode(onCopied: () -> Unit)

    /** Share: the crosshair in the editor, as a share code. */
    fun share(onCopied: () -> Unit)
}

@Composable
fun CrosshairsRoute(
    crosshairs: Crosshairs,
    community: CommunityCrosshairs,
    tab: CrosshairsTab,
    modal: WindowModal,
    onSearch: (String) -> Unit,
) {
    val viewModel = viewModel { CrosshairsViewModel(crosshairs, community, tab) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel, clipboard, scope) { RouteActions(viewModel, clipboard, scope) }
    CrosshairsScreen(state, actions, modal, onSearch)
}

private class RouteActions(
    private val vm: CrosshairsViewModel,
    private val clipboard: Clipboard,
    private val scope: CoroutineScope,
) : CrosshairsActions, CrosshairsPageActions by vm {
    override fun copyCode(onCopied: () -> Unit) = copy(vm.communityCode(), onCopied)
    override fun share(onCopied: () -> Unit) = copy(vm.shareCode(), onCopied)

    private fun copy(text: String?, onCopied: () -> Unit) {
        if (text != null) scope.launch { if (clipboard.copyText(text)) onCopied() }
    }
}
