package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
internal fun ImageOverlayButton(
    visible: Boolean,
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = HideoutDimens.ImageOverlay.Button,
) {
    val motion = HideoutTheme.motion
    val colors = HideoutTheme.colors
    AnimatedVisibility(visible, modifier, enter = fadeIn(motion.effectsFast()), exit = fadeOut(motion.effectsFast())) {
        HideoutIconButton(
            icon = icon,
            contentDescription = description,
            onClick = onClick,
            modifier = Modifier.background(colors.imageInset, CircleShape),
            size = size,
            tint = colors.onImage,
            hoverColor = colors.imageInsetHover,
        )
    }
}
