package gg.hideouthq.feature.moddetail.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutIconToggleButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.TooltipSide
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.moddetail.StageKind
import gg.hideouthq.feature.moddetail.StageTab
import gg.hideouthq.feature.moddetail.ModDetailTransition.compare
import gg.hideouthq.feature.moddetail.ModDetailTransition.tool
import gg.hideouthq.feature.moddetail.ViewerState
import gg.hideouthq.feature.moddetail.ViewerTool
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.breadcrumb_separator
import gg.hideouthq.resources.stage_tab_images
import gg.hideouthq.resources.stage_tab_model
import gg.hideouthq.resources.stage_tab_sound
import gg.hideouthq.resources.stage_tabs
import gg.hideouthq.resources.tool_auto_spin
import gg.hideouthq.resources.tool_fullscreen
import gg.hideouthq.resources.tool_save_image
import gg.hideouthq.resources.tool_scene
import gg.hideouthq.resources.tool_reset_camera
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun DetailBreadcrumb(category: String, current: String?, onCategory: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val linkColor by animateColorAsState(
        targetValue = if (states.hovered) scheme.onSurface else scheme.outline,
        animationSpec = HideoutTheme.motion.effectsFast(),
        label = "breadcrumbLink",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Detail.BreadcrumbGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = category,
            style = type.captionSmall,
            color = linkColor,
            maxLines = 1,
            modifier = Modifier
                .focusRing(states.focusVisible, HideoutShapes.Tile)
                .hideoutClickable(source, role = Role.Button, onClick = onCategory),
        )
        if (current != null) {
            Text(stringResource(Res.string.breadcrumb_separator), style = type.captionSmall, color = scheme.outline)
            Text(current, style = type.breadcrumbCurrent, color = scheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
internal fun StageTabs(selected: StageTab, preview: StageKind, images: Int, onSelect: (StageTab) -> Unit) {
    val previewLabel = stringResource(if (preview == StageKind.Sound) Res.string.stage_tab_sound else Res.string.stage_tab_model)
    val imagesLabel = stringResource(Res.string.stage_tab_images)
    val group = stringResource(Res.string.stage_tabs)
    SegmentedControl(
        options = StageTab.entries,
        selected = selected,
        onSelect = onSelect,
        label = { if (it == StageTab.Preview) previewLabel else imagesLabel },
        icon = {
            when {
                it == StageTab.Images -> HideoutIcons.Image
                preview == StageKind.Sound -> HideoutIcons.Sound
                else -> HideoutIcons.Cube
            }
        },
        count = { if (it == StageTab.Images) images.toString() else null },
        modifier = Modifier
            .semantics { contentDescription = group }
            .compare(),
    )
}

@Composable
internal fun ViewerTools(viewer: ViewerState, available: Set<ViewerTool>, dimmed: Boolean, onTool: (ViewerTool) -> Unit) {
    val disabledAlpha = if (dimmed) 1f else HideoutMotion.DisabledAlpha
    Row(
        Modifier.then(if (dimmed) Modifier.alpha(HideoutDimens.Stage.LoadingAlpha) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.TopBar.ToolGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ViewerTool.entries.forEachIndexed { index, tool ->
            val description = stringResource(tool.label)
            val enabled = tool in available
            // Compose still runs a disabled control's click through its accessibility action.
            val use = { if (enabled) onTool(tool) }
            val checked = when (tool) {
                ViewerTool.AutoSpin -> viewer.autoSpin
                ViewerTool.Scene -> viewer.sceneOpen
                ViewerTool.Fullscreen -> viewer.fullscreen
                ViewerTool.SaveImage, ViewerTool.ResetCamera -> null
            }
            HideoutTooltip(description, Modifier.tool(index), side = TooltipSide.Below) {
                if (checked == null) {
                    HideoutIconButton(tool.icon, description, onClick = use, enabled = enabled, disabledAlpha = disabledAlpha)
                } else {
                    HideoutIconToggleButton(tool.icon, description, checked = checked, onCheckedChange = { use() }, enabled = enabled, disabledAlpha = disabledAlpha)
                }
            }
        }
    }
}

private val ViewerTool.label: StringResource
    get() = when (this) {
        ViewerTool.AutoSpin -> Res.string.tool_auto_spin
        ViewerTool.Scene -> Res.string.tool_scene
        ViewerTool.SaveImage -> Res.string.tool_save_image
        ViewerTool.ResetCamera -> Res.string.tool_reset_camera
        ViewerTool.Fullscreen -> Res.string.tool_fullscreen
    }

private val ViewerTool.icon: ImageVector
    get() = when (this) {
        ViewerTool.AutoSpin -> HideoutIcons.AutoSpin
        ViewerTool.Scene -> HideoutIcons.Image
        ViewerTool.SaveImage -> HideoutIcons.SaveImage
        ViewerTool.ResetCamera -> HideoutIcons.Reset
        ViewerTool.Fullscreen -> HideoutIcons.Fullscreen
    }
