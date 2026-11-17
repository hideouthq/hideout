package gg.hideouthq.feature.moddetail.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.detail_failed
import gg.hideouthq.resources.detail_missing_message
import gg.hideouthq.resources.detail_missing_title
import gg.hideouthq.resources.retry
import gg.hideouthq.resources.sync_failed_offline
import gg.hideouthq.resources.sync_failed_unavailable
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailLoadError(error: DataError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val missing = error == DataError.NotFound
    EmptyState(
        modifier = modifier,
        icon = HideoutIcons.Warning,
        title = stringResource(if (missing) Res.string.detail_missing_title else Res.string.detail_failed),
        message = stringResource(
            when (error) {
                DataError.NotFound -> Res.string.detail_missing_message
                DataError.Offline -> Res.string.sync_failed_offline
                DataError.Unavailable -> Res.string.sync_failed_unavailable
            },
        ),
        action = if (missing) null else ({ HideoutButton(stringResource(Res.string.retry), onRetry, variant = ButtonVariant.Tonal) }),
    )
}
