package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIconToggleButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

data class ToolbarLabels(val play: String, val pause: String, val speed: String, val loop: String)

@Composable
internal fun ToolbarFrame(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .dropShadow(
                HideoutShapes.Toolbar,
                Shadow(
                    radius = HideoutDimens.Toolbar.ShadowBlur,
                    color = HideoutTheme.colors.toolbarShadow,
                    offset = DpOffset(0.dp, HideoutDimens.Toolbar.ShadowOffsetY),
                ),
            )
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, HideoutShapes.Toolbar)
            .height(HideoutDimens.Toolbar.Height)
            .padding(HideoutDimens.Toolbar.Padding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Toolbar.Gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun LoopToggle(loop: Boolean, onLoopChange: (Boolean) -> Unit, label: String, enabled: Boolean = true, disabledAlpha: Float = HideoutMotion.DisabledAlpha) {
    HideoutIconToggleButton(
        icon = HideoutIcons.Loop,
        contentDescription = label,
        checked = loop,
        onCheckedChange = onLoopChange,
        size = HideoutDimens.IconButton.SizeToolbar,
        iconSize = HideoutDimens.Icon.Small,
        hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        enabled = enabled,
        disabledAlpha = disabledAlpha,
    )
}
