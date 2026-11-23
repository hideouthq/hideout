package gg.hideouthq.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import kotlin.math.max

/**
 * CSS `grid-template-columns: repeat(auto-fit, minmax(min, 1fr))` with the cards' gap: as many columns of at least
 * [minCellWidth] as fit, cells sharing the row equally. Columns nobody fills are dropped so the items grow into them,
 * unless [keepEmptyColumns], which is `auto-fill`: a lone item keeps the width it has in a full row.
 * Rows are as tall as their tallest cell.
 */
@Composable
fun AdaptiveGrid(
    minCellWidth: Dp,
    modifier: Modifier = Modifier,
    columnGap: Dp = HideoutDimens.Card.Gap,
    rowGap: Dp = HideoutDimens.Card.Gap,
    keepEmptyColumns: Boolean = false,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = columnGap.roundToPx()
        val between = rowGap.roundToPx()
        val width = constraints.maxWidth
        val fit = max(1, (width + gap) / (minCellWidth.roundToPx() + gap))
        val columns = if (keepEmptyColumns) fit else fit.coerceAtMost(max(1, measurables.size))
        val cell = (width - gap * (columns - 1)) / columns
        val placeables = measurables.map { it.measure(Constraints(minWidth = cell, maxWidth = cell)) }
        val rows = placeables.chunked(columns)
        val heights = rows.map { row -> row.maxOf { it.height } }
        val height = heights.sum() + between * (rows.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, placeable -> placeable.place(c * (cell + gap), y) }
                y += heights[r] + between
            }
        }
    }
}

/** One item of a [FlexWrapRow]: its flex-basis and how much of the spare width it takes. */
data class FlexItem(val basis: Dp, val grow: Float)

/**
 * CSS `display: flex; flex-wrap: wrap` for a few items: they share lines while their bases fit and
 * the spare width on a line goes to them by [FlexItem.grow]. Items are centred in their line.
 */
@Composable
fun FlexWrapRow(
    items: List<FlexItem>,
    columnGap: Dp,
    rowGap: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gapX = columnGap.roundToPx()
        val gapY = rowGap.roundToPx()
        val available = constraints.maxWidth
        val bases = items.map { it.basis.roundToPx() }
        val lines = mutableListOf<MutableList<Int>>()
        var used = 0
        items.indices.forEach { i ->
            val current = lines.lastOrNull()
            if (current == null || used + gapX + bases[i] > available) {
                lines += mutableListOf(i)
                used = bases[i]
            } else {
                current += i
                used += gapX + bases[i]
            }
        }
        val widths = IntArray(items.size)
        lines.forEach { line ->
            val free = (available - gapX * (line.size - 1) - line.sumOf { bases[it] }).coerceAtLeast(0)
            val totalGrow = line.sumOf { items[it].grow.toDouble() }.toFloat()
            line.forEach { i -> widths[i] = bases[i] + (free * items[i].grow / totalGrow).toInt() }
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints(minWidth = widths[i], maxWidth = widths[i])) }
        val lineHeights = lines.map { line -> line.maxOf { placeables[it].height } }
        val height = (lineHeights.sum() + gapY * (lines.size - 1)).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(available, height) {
            var y = 0
            lines.forEachIndexed { l, line ->
                var x = 0
                line.forEach { i ->
                    val p = placeables[i]
                    p.place(x, y + (lineHeights[l] - p.height) / 2)
                    x += p.width + gapX
                }
                y += lineHeights[l] + gapY
            }
        }
    }
}

/**
 * A wrapping row whose last child sits at the end of its line, as a wrapping CSS flex row lays out a last item with an
 * automatic start margin: it shares the line when it fits and otherwise takes a line of its own. Each child is centred
 * in its line's height, or level with its bottom when [bottom]; lines are centred in any extra height.
 */
@Composable
fun EndAlignedFlow(modifier: Modifier, gap: Dp, lineGap: Dp, bottom: Boolean = false, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val lines = mutableListOf(mutableListOf<Int>())
        var used = 0
        placeables.forEachIndexed { index, placeable ->
            val line = lines.last()
            val needed = if (line.isEmpty()) placeable.width else used + gapPx + placeable.width
            if (line.isNotEmpty() && needed > width) {
                lines += mutableListOf(index)
                used = placeable.width
            } else {
                line += index
                used = needed
            }
        }
        val heights = lines.map { line -> line.maxOfOrNull { placeables[it].height } ?: 0 }
        val total = heights.sum() + lineGap.roundToPx() * (lines.size - 1).coerceAtLeast(0)
        val height = total.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = (height - total) / 2
            lines.forEachIndexed { l, line ->
                var x = 0
                line.forEach { index ->
                    val placeable = placeables[index]
                    val left = if (index == placeables.lastIndex) maxOf(x, width - placeable.width) else x
                    val spare = heights[l] - placeable.height
                    placeable.place(left, y + if (bottom) spare else spare / 2)
                    x = left + placeable.width + gapPx
                }
                y += heights[l] + lineGap.roundToPx()
            }
        }
    }
}

/**
 * [main] with the [sideWidth] column [side] at its end. Once [main] would be narrower than [MainMinWidth], [side] goes
 * under it instead, so the game pages' main cards keep the same room. Without [side], [main] takes the whole width.
 */
@Composable
internal fun MainAndSide(sideWidth: Dp, main: @Composable (Modifier) -> Unit, side: (@Composable (Modifier) -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when {
            side == null -> main(Modifier.fillMaxWidth())
            !fitsBeside(maxWidth, sideWidth) -> Column(verticalArrangement = Arrangement.spacedBy(SideGap)) {
                main(Modifier.fillMaxWidth())
                side(Modifier.fillMaxWidth())
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(SideGap)) {
                main(Modifier.weight(1f))
                side(Modifier.width(sideWidth))
            }
        }
    }
}

internal fun fitsBeside(width: Dp, sideWidth: Dp): Boolean = width >= MainMinWidth + SideGap + sideWidth

/** The narrowest a main card goes beside a side column: Browse's search keeps its placeholder beside the sort. */
private val MainMinWidth = 520.dp
private val SideGap = 12.dp
