package gg.hideouthq.core.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun OpenPanelButton(label: String, icon: ImageVector, iconOnly: Boolean = false) {
    val frame = LocalPaneFrame.current ?: return
    val motion = HideoutTheme.motion
    AnimatedVisibility(
        visible = frame.overlay,
        enter = fadeIn(motion.effectsDefault()) + scaleIn(motion.spatialFast(), initialScale = HideoutMotion.ChipEnterScale),
        exit = fadeOut(motion.exitFade()) + scaleOut(targetScale = HideoutMotion.ChipEnterScale),
    ) {
        if (iconOnly) {
            HideoutTooltip(label) { HideoutIconButton(icon, label, frame::openSidePanel) }
        } else {
            HideoutButton(
                text = label,
                onClick = frame::openSidePanel,
                variant = ButtonVariant.Tonal,
                size = ButtonSize.Small,
                leadingIcon = icon,
                leadingIconSize = HideoutDimens.Icon.Small,
            )
        }
    }
}
