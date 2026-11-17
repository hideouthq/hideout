package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion

@Composable
fun PlayPauseButton(
    playing: Boolean,
    onClick: () -> Unit,
    playLabel: String,
    pauseLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    disabledAlpha: Float = HideoutMotion.DisabledAlpha,
) {
    ButtonFrame(
        variant = ButtonVariant.Primary,
        size = ButtonSize.Regular,
        enabled = enabled,
        modifier = modifier
            .size(HideoutDimens.IconButton.SizeToolbar)
            .semantics { contentDescription = if (playing) pauseLabel else playLabel },
        contentPadding = PaddingValues(),
        onClick = onClick,
        disabledAlpha = disabledAlpha,
    ) { content ->
        if (loading) CircularProgressIndicator(Modifier.size(HideoutDimens.Icon.Small), color = content)
        else PlayPauseIcon(playing = playing, contentDescription = null, tint = content)
    }
}
