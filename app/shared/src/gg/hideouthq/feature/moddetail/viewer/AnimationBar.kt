package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.Pill
import gg.hideouthq.core.designsystem.component.PillDefaults
import gg.hideouthq.core.designsystem.component.PlayPauseButton
import gg.hideouthq.core.designsystem.component.rememberSelectionIndicatorState
import gg.hideouthq.core.designsystem.component.selectionIndicator
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

/**
 * The words of the animation bar and its menus. [lengths] holds each clip's length by its id. [categoryChanged] is
 * said of a pill and [clipChanged] of a clip the mod changes, which [changedTag] marks in a menu.
 */
@Immutable
data class AnimationLabels(
    val categories: Map<ViewerCategory, String>,
    val categoryChanged: String,
    val clipChanged: String,
    val changedTag: String,
    val lengths: Map<String, String>,
    val stepThrough: String,
)

/**
 * Each pill reports where it is through [onPillPlaced], so its menu can grow out of it. While not [enabled] every
 * control is dimmed and takes no input; while [dimmed] too, the whole bar is at the stage's loading opacity instead.
 */
@Composable
fun AnimationBar(
    groups: List<ClipGroup>,
    active: ViewerCategory?,
    open: ViewerCategory?,
    onPick: (ViewerCategory) -> Unit,
    playing: Boolean,
    onTogglePlaying: () -> Unit,
    speedLabel: String,
    onCycleSpeed: () -> Unit,
    loop: Boolean,
    onLoopChange: (Boolean) -> Unit,
    labels: ToolbarLabels,
    animationLabels: AnimationLabels,
    pillFocus: Map<ViewerCategory, FocusRequester>,
    onPillPlaced: (ViewerCategory, LayoutCoordinates) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    dimmed: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = PillDefaults.toolbarColors()
    val indicator = rememberSelectionIndicatorState(active, squash = true)
    val disabledAlpha = if (dimmed) 1f else HideoutMotion.DisabledAlpha
    val selected = if (enabled) colors.selected else colors.selected.copy(alpha = colors.selected.alpha * disabledAlpha)
    ToolbarFrame(modifier.then(if (dimmed) Modifier.alpha(HideoutDimens.Stage.LoadingAlpha) else Modifier)) {
        // Compose still runs a disabled control's click through its accessibility action.
        PlayPauseButton(
            playing = playing, onClick = { if (enabled) onTogglePlaying() }, playLabel = labels.play, pauseLabel = labels.pause,
            enabled = enabled, disabledAlpha = disabledAlpha,
        )
        Row(
            modifier = Modifier
                .selectionIndicator(indicator, selected, HideoutShapes.Full)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Toolbar.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            groups.forEach { group ->
                val category = group.category
                val pick = { if (enabled) { pillFocus.getValue(category).requestFocus(); onPick(category) } }
                Pill(
                    text = animationLabels.categories.getValue(category),
                    selected = category == active,
                    onClick = pick,
                    indicator = indicator,
                    key = category,
                    colors = colors,
                    height = HideoutDimens.Toolbar.PillHeight,
                    enabled = enabled,
                    role = Role.DropdownList,
                    disabledAlpha = disabledAlpha,
                    trailing = { color, onSelection ->
                        if (group.changed) ChangedDot(if (onSelection) color else scheme.primary)
                        if (category == active) MenuChevron(open = category == open, color = color)
                    },
                    modifier = Modifier
                        .focusRequester(pillFocus.getValue(category))
                        .onGloballyPositioned { onPillPlaced(category, it) }
                        .semantics {
                            when {
                                !enabled -> Unit
                                category == open -> collapse { pick(); true }
                                else -> expand { pick(); true }
                            }
                            if (group.changed) stateDescription = animationLabels.categoryChanged
                        },
                )
            }
        }
        Box(
            Modifier
                .padding(horizontal = HideoutDimens.Toolbar.DividerMargin)
                .width(HideoutDimens.Hairline)
                .height(HideoutDimens.Toolbar.DividerHeight)
                .background(scheme.surfaceContainerHighest),
        )
        Pill(
            text = speedLabel,
            selected = false,
            onClick = { if (enabled) onCycleSpeed() },
            colors = colors,
            height = HideoutDimens.Toolbar.PillHeight,
            modifier = Modifier.semantics { contentDescription = labels.speed },
            enabled = enabled,
            disabledAlpha = disabledAlpha,
        )
        LoopToggle(loop, { if (enabled) onLoopChange(it) }, labels.loop, enabled, disabledAlpha)
    }
}

@Composable
private fun ChangedDot(color: Color) {
    Box(Modifier.size(HideoutDimens.Toolbar.ChangedDot).background(color, CircleShape))
}

@Composable
private fun MenuChevron(open: Boolean, color: Color) {
    val turn by animateFloatAsState(if (open) HalfTurn else 0f, HideoutTheme.motion.spatialFast(), label = "menuChevron")
    HideoutIcon(
        icon = HideoutIcons.ChevronUp,
        contentDescription = null,
        size = HideoutDimens.Toolbar.Chevron,
        tint = color,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout((placeable.width - HideoutDimens.Toolbar.ChevronOverhang.roundToPx()).coerceAtLeast(0), placeable.height) { placeable.place(0, 0) }
            }
            .graphicsLayer { rotationZ = turn },
    )
}

private const val HalfTurn = 180f
