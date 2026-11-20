package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights

enum class SegmentedStyle { Pane, Card, Glass }

@Composable
fun <T : Any> SegmentedControl(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    icon: (T) -> ImageVector? = { null },
    count: (T) -> String? = { null },
    segmentHeight: Dp = HideoutDimens.Segmented.SegmentHeight,
    segmentPadding: Dp = HideoutDimens.Segmented.SegmentHorizontalPadding,
    style: SegmentedStyle = SegmentedStyle.Pane,
    labelMaxWidth: Dp = Dp.Unspecified,
) {
    val colors = segmentedColors(style)
    val indicator = rememberSelectionIndicatorState(selected, squash = true)
    Layout(
        modifier = modifier
            .background(colors.track, HideoutShapes.Full)
            .selectionIndicator(indicator, colors.indicator, HideoutShapes.Full)
            .padding(HideoutDimens.Segmented.Padding)
            .selectableGroup(),
        content = {
            options.forEach { option ->
                Segment(
                    text = label(option),
                    icon = icon(option),
                    count = count(option),
                    selected = option == selected,
                    colors = colors,
                    onClick = { onSelect(option) },
                    coverage = { indicator.coverage(option) },
                    modifier = Modifier.height(segmentHeight).indicatorTarget(indicator, option),
                    horizontalPadding = segmentPadding,
                    labelMaxWidth = labelMaxWidth,
                )
            }
        },
    ) { measurables, constraints ->
        val gap = HideoutDimens.Segmented.Gap.roundToPx()
        val height = segmentHeight.roundToPx()
        val totalGap = gap * (measurables.size - 1).coerceAtLeast(0)
        val natural = measurables.map { it.maxIntrinsicWidth(height) }
        val widths = if (constraints.hasFixedWidth) {
            shareEqually(constraints.maxWidth - totalGap, natural)
        } else {
            natural
        }
        val placeables = measurables.zip(widths).map { (m: Measurable, w) -> m.measure(Constraints.fixed(w, height)) }
        val width = (widths.sum() + totalGap).coerceIn(constraints.minWidth, constraints.maxWidth)
        layout(width, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, 0)
                x += it.width + gap
            }
        }
    }
}

private class SegmentedColors(
    val track: Color,
    val indicator: Color,
    val content: Color,
    val hovered: Color,
    val selected: Color,
    val press: Color,
)

@Composable
private fun segmentedColors(style: SegmentedStyle): SegmentedColors {
    val scheme = MaterialTheme.colorScheme
    val glass = HideoutTheme.colors
    return when (style) {
        SegmentedStyle.Pane, SegmentedStyle.Card -> SegmentedColors(
            track = if (style == SegmentedStyle.Pane) scheme.surfaceContainer else scheme.surfaceContainerHigh,
            indicator = scheme.surfaceContainerHighest,
            content = scheme.onSurfaceVariant,
            hovered = scheme.onSurface,
            selected = scheme.onSurface,
            press = scheme.surfaceContainerHigh,
        )
        SegmentedStyle.Glass -> SegmentedColors(
            track = glass.glassPill,
            indicator = glass.glassSelection,
            content = glass.onGlass,
            hovered = glass.onImage,
            selected = scheme.surface,
            press = glass.glassOnHero,
        )
    }
}

private fun shareEqually(available: Int, natural: List<Int>): List<Int> {
    val frozen = BooleanArray(natural.size)
    var share: Int
    while (true) {
        val open = natural.indices.filter { !frozen[it] }
        if (open.isEmpty()) return natural
        val remaining = available - natural.indices.filter { frozen[it] }.sumOf { natural[it] }
        share = remaining / open.size
        val newlyFrozen = open.filter { natural[it] > share }
        if (newlyFrozen.isEmpty()) break
        newlyFrozen.forEach { frozen[it] = true }
    }
    return natural.indices.map { if (frozen[it]) natural[it] else share }
}

@Composable
private fun Segment(
    text: String,
    icon: ImageVector?,
    count: String?,
    selected: Boolean,
    colors: SegmentedColors,
    onClick: () -> Unit,
    coverage: () -> Float,
    modifier: Modifier,
    horizontalPadding: Dp,
    labelMaxWidth: Dp,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val hover by animateFloatAsState(if (states.hovered) 1f else 0f, HideoutTheme.motion.effectsFast(), label = "segmentHover")
    val color = lerp(lerp(colors.content, colors.hovered, hover), colors.selected, coverage())
    val press by animateFloatAsState(if (states.pressed) 1f else 0f, HideoutTheme.motion.effectsFast(), label = "segmentPress")
    Box(
        modifier = modifier
            .drawBehind {
                if (press > 0f) {
                    drawRoundRect(colors.press, cornerRadius = CornerRadius(size.height / 2), alpha = press * (1f - coverage()))
                }
            }
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .hideoutSelectable(selected, source, onClick = onClick)
            .padding(
                start = if (icon != null) HideoutDimens.Segmented.IconSegmentStart else horizontalPadding,
                end = if (icon != null) HideoutDimens.Segmented.IconSegmentEnd else horizontalPadding,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.wrapContentWidth(unbounded = true), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Segmented.IconGap), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) HideoutIcon(icon, contentDescription = null, size = HideoutDimens.Segmented.Icon, tint = color)
            Text(text = text, Modifier.widthIn(max = labelMaxWidth), style = HideoutTheme.typography.labelChip, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            if (count != null) Text(count, style = HideoutTheme.typography.captionCompact.copy(fontWeight = HideoutWeights.Label), color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}
