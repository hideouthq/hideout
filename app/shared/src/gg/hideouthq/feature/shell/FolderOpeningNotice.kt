package gg.hideouthq.feature.shell

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import gg.hideouthq.core.designsystem.component.NoticeCard
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.ui.copyText
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.folder_copy_path
import gg.hideouthq.resources.folder_open_failed
import gg.hideouthq.resources.notice_dismiss
import gg.hideouthq.resources.notice_try_again
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FolderOpeningNotice(state: FolderOpeningState) {
    val request = state.failed ?: return
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    NoticeCard(
        lead = stringResource(Res.string.folder_open_failed),
        message = request.path,
        primaryLabel = stringResource(Res.string.notice_try_again),
        onPrimary = { scope.launch { state.retry() } },
        primaryEnabled = !state.opening,
        secondaryLabel = stringResource(Res.string.folder_copy_path),
        onSecondary = { scope.launch { if (clipboard.copyText(request.path)) state.dismiss(request) } },
        modifier = Modifier.widthIn(max = HideoutDimens.Notice.FloatingMaxWidth),
        tertiaryLabel = stringResource(Res.string.notice_dismiss),
        onTertiary = { state.dismiss(request) },
    )
}
