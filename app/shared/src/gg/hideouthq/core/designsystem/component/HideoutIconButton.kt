package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.interaction.InteractionStates
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun HideoutIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = HideoutDimens.IconButton.Size,
    iconSize: Dp = HideoutDimens.Icon.Size,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    hoverColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    disabledAlpha: Float = HideoutMotion.DisabledAlpha,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    IconButtonFrame(
        states = states,
        enabled = enabled,
        checked = false,
        size = size,
        hoverColor = hoverColor,
        disabledAlpha = disabledAlpha,
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .hideoutClickable(source, enabled, onClick = onClick),
    ) {
        HideoutIcon(icon, contentDescription = null, size = iconSize, tint = tint)
    }
}

@Composable
fun HideoutIconToggleButton(
    icon: ImageVector,
    contentDescription: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = HideoutDimens.IconButton.Size,
    iconSize: Dp = HideoutDimens.Icon.Size,
    hoverColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    enabled: Boolean = true,
    disabledAlpha: Float = HideoutMotion.DisabledAlpha,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val colors = MaterialTheme.colorScheme
    val tint by animateColorAsState(
        targetValue = if (checked) colors.primary else colors.onSurfaceVariant,
        animationSpec = HideoutTheme.motion.effectsDefault(),
        label = "iconToggleTint",
    )
    val settle = rememberPopScale(trigger = checked, peak = HideoutMotion.TogglePressScale)
    IconButtonFrame(
        states = states,
        enabled = enabled,
        checked = checked,
        size = size,
        hoverColor = hoverColor,
        disabledAlpha = disabledAlpha,
        modifier = modifier
            .semantics { this.contentDescription = contentDescription }
            .hideoutToggleable(checked, source, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
    ) {
        HideoutIcon(
            icon = icon,
            contentDescription = null,
            size = iconSize,
            tint = tint,
            modifier = Modifier.graphicsLayer {
                scaleX = settle.value
                scaleY = settle.value
            },
        )
    }
}

@Composable
internal fun IconButtonFrame(
    states: InteractionStates,
    enabled: Boolean,
    checked: Boolean,
    size: Dp,
    hoverColor: Color,
    modifier: Modifier,
    disabledAlpha: Float = HideoutMotion.DisabledAlpha,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val background by animateColorAsState(
        targetValue = when {
            checked -> colors.surfaceContainerHigh
            (states.hovered || states.pressed) && enabled -> hoverColor
            else -> hoverColor.copy(alpha = 0f)
        },
        animationSpec = motion.effectsFast(),
        label = "iconButtonBackground",
    )
    val press by animateFloatAsState(
        targetValue = if (states.pressed && enabled) HideoutMotion.IconButtonPressScale else 1f,
        animationSpec = motion.spatialFast(),
        label = "iconButtonPress",
    )
    Box(
        modifier = modifier
            .size(size)
            .drawBehind { drawCircle(background, radius = this.size.minDimension / 2 * press) }
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .alpha(if (enabled) 1f else disabledAlpha),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
