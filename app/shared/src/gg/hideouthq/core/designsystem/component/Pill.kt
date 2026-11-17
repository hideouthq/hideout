package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Immutable
data class PillColors(
    val idle: Color,
    val hover: Color,
    val selected: Color,
    val content: Color,
    val selectedContent: Color,
)

object PillDefaults {
    @Composable
    fun colors(): PillColors {
        val scheme = MaterialTheme.colorScheme
        return PillColors(
            idle = scheme.surfaceContainer,
            hover = scheme.surfaceContainerHigh,
            selected = scheme.primary,
            content = scheme.onSurfaceVariant,
            selectedContent = scheme.onPrimary,
        )
    }

    @Composable
    fun toolbarColors(): PillColors {
        val scheme = MaterialTheme.colorScheme
        return colors().copy(idle = scheme.surfaceContainerHighest.copy(alpha = 0f), hover = scheme.surfaceContainerHighest)
    }
}

class PillGroupScope internal constructor(val indicator: SelectionIndicatorState)

@Composable
fun PillRow(
    selectedKey: Any?,
    modifier: Modifier = Modifier,
    content: @Composable PillGroupScope.() -> Unit,
) {
    val indicator = rememberSelectionIndicatorState(selectedKey, squash = true)
    val scope = remember(indicator) { PillGroupScope(indicator) }
    FlowRow(
        modifier = modifier.selectionIndicator(indicator, MaterialTheme.colorScheme.primary, HideoutShapes.Full),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.RowGap),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.RowGap),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        scope.content()
    }
}

/**
 * A 36dp pill. Idle surface2, hover surface3, selected primary with dark text. An optional [count]
 * trails the label at 70% opacity, and [trailing] after it gets the label's colour and whether it
 * is drawn on the selection. Inside a [PillRow], pass its [PillGroupScope.indicator] and a key.
 */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    leadingIcon: ImageVector? = null,
    indicator: SelectionIndicatorState? = null,
    key: Any = text,
    colors: PillColors = PillDefaults.colors(),
    height: Dp = HideoutDimens.Pill.Height,
    enabled: Boolean = true,
    role: Role = Role.Tab,
    disabledAlpha: Float = HideoutMotion.DisabledAlpha,
    trailing: PillTrailing? = null,
    textStyle: TextStyle? = null,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val motion = HideoutTheme.motion
    val background by animateColorAsState(
        targetValue = when {
            selected && indicator == null -> colors.selected
            states.hovered -> colors.hover
            else -> colors.idle
        },
        animationSpec = motion.effectsDefault(),
        label = "pillBackground",
    )
    val content by animateColorAsState(
        targetValue = if (selected && indicator == null) colors.selectedContent else colors.content,
        animationSpec = motion.effectsDefault(),
        label = "pillContent",
    )
    val pressed by animateFloatAsState(
        targetValue = if (states.pressed && indicator == null) HideoutMotion.PressScale else 1f,
        animationSpec = motion.spatialFast(),
        label = "pillPress",
    )
    val widen by animateDpAsState(
        targetValue = if (states.pressed && !motion.reduced && indicator != null) HideoutDimens.Pill.PressWiden / 2 else 0.dp,
        animationSpec = motion.spatialFast(),
        label = "pillWiden",
    )
    val paint = rememberUpdatedState { background }

    Box(
        modifier = modifier
            .then(
                if (indicator != null) {
                    Modifier.indicatorTarget(indicator, key, background = paint.value)
                } else {
                    Modifier.drawBehind { scale(pressed) { drawRoundRectFull(paint.value()) } }
                },
            )
            .height(height)
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .hideoutSelectable(selected, source, enabled = enabled, role = role, onClick = onClick)
            .alpha(if (enabled) 1f else disabledAlpha)
            .padding(horizontal = HideoutDimens.Pill.HorizontalPadding + widen),
        contentAlignment = Alignment.Center,
    ) {
        if (indicator == null) {
            PillLabel(text, count, leadingIcon, trailing, content, onSelection = selected, textStyle = textStyle)
        } else {
            val label = remember { arrayOfNulls<LayoutCoordinates>(1) }
            val labelModifier = Modifier.onPlaced { label[0] = it }
            val origin = { label[0]?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero }
            PillLabel(text, count, leadingIcon, trailing, content, onSelection = false, labelModifier.clipToShape(indicator, origin, inside = false), textStyle)
            PillLabel(text, count, leadingIcon, trailing, colors.selectedContent, onSelection = true, labelModifier.clipToShape(indicator, origin, inside = true), textStyle)
        }
    }
}

/** What a pill draws after its label, in [color] and knowing whether this copy of the label is [onSelection]. */
typealias PillTrailing = @Composable RowScope.(color: Color, onSelection: Boolean) -> Unit

@Composable
private fun PillLabel(
    text: String,
    count: Int?,
    leadingIcon: ImageVector?,
    trailing: PillTrailing?,
    color: Color,
    onSelection: Boolean,
    modifier: Modifier = Modifier,
    textStyle: TextStyle? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Pill.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingIcon != null) HideoutIcon(leadingIcon, null, size = HideoutDimens.Icon.Small, tint = color)
        Text(text = text, style = textStyle ?: HideoutTheme.typography.labelPill, color = color, maxLines = 1)
        if (count != null) {
            RollingText(
                text = count.toString(),
                style = HideoutTheme.typography.labelPill,
                color = color,
                modifier = Modifier.alpha(HideoutMotion.SecondaryCountAlpha),
            )
        }
        trailing?.invoke(this, color, onSelection)
    }
}

private fun Modifier.clipToShape(indicator: SelectionIndicatorState, origin: () -> Offset, inside: Boolean): Modifier =
    drawWithContent {
        val bounds = indicator.shapeBoundsInRoot()?.translate(-origin())
        if (bounds == null) {
            if (!inside) drawContent()
            return@drawWithContent
        }
        val outline = Path().apply { addRoundRect(RoundRect(bounds, CornerRadius(bounds.height / 2))) }
        clipPath(outline, if (inside) ClipOp.Intersect else ClipOp.Difference) { this@drawWithContent.drawContent() }
    }

private fun DrawScope.drawRoundRectFull(color: Color) {
    drawRoundRect(color, cornerRadius = CornerRadius(size.height / 2))
}
