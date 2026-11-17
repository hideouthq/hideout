package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Immutable
data class WindowControlsLabels(
    val minimize: String,
    val maximize: String,
    val restore: String,
    val close: String,
)

@Composable
fun WindowControls(
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
    labels: WindowControlsLabels,
    modifier: Modifier = Modifier,
    tabbable: Boolean = true,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.WindowControls.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WindowControl(HideoutIcons.WindowMinimize, labels.minimize, onMinimize, tabbable)
        WindowControl(
            icon = if (maximized) HideoutIcons.WindowRestore else HideoutIcons.WindowMaximize,
            description = if (maximized) labels.restore else labels.maximize,
            onClick = onToggleMaximize,
            tabbable = tabbable,
        )
        WindowControl(HideoutIcons.WindowClose, labels.close, onClose, tabbable, destructive = true)
    }
}

@Composable
private fun WindowControl(icon: ImageVector, description: String, onClick: () -> Unit, tabbable: Boolean, destructive: Boolean = false) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val colors = HideoutTheme.colors
    val motion = HideoutTheme.motion
    val active = states.hovered || states.pressed
    val tint by animateColorAsState(
        targetValue = when {
            !active -> scheme.outline
            destructive -> colors.onCloseHover
            else -> scheme.onSurface
        },
        animationSpec = motion.effectsFast(),
        label = "windowControlTint",
    )
    IconButtonFrame(
        states = states,
        enabled = true,
        checked = false,
        size = HideoutDimens.WindowControls.Button,
        hoverColor = if (destructive) colors.closeHover else scheme.surfaceContainerHigh,
        modifier = Modifier
            .semantics { contentDescription = description }
            .focusProperties { canFocus = tabbable }
            .hideoutClickable(source, onClick = onClick),
    ) {
        Crossfade(icon, animationSpec = motion.effectsFast(), label = "windowControlGlyph") { glyph ->
            HideoutIcon(glyph, contentDescription = null, size = HideoutDimens.WindowControls.Icon, tint = tint)
        }
    }
}
