package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import gg.hideouthq.core.designsystem.theme.HideoutDimens

@Composable
fun TopBar(
    modifier: Modifier = Modifier,
    start: @Composable () -> Unit = {},
    center: @Composable () -> Unit = {},
    end: @Composable () -> Unit = {},
) {
    Layout(
        modifier = modifier
            .fillMaxWidth()
            .height(HideoutDimens.TopBar.Height)
            .padding(horizontal = HideoutDimens.TopBar.HorizontalPadding),
        contents = listOf(start, center, end),
    ) { (startMeasurables, centerMeasurables, endMeasurables), constraints ->
        val gap = HideoutDimens.TopBar.SlotGap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val startPlaceables = startMeasurables.map { it.measure(loose) }
        val endPlaceables = endMeasurables.map { it.measure(loose) }
        val startWidth = startPlaceables.maxOfOrNull { it.width } ?: 0
        val endWidth = endPlaceables.maxOfOrNull { it.width } ?: 0
        val height = constraints.maxHeight
        val maxCenter = HideoutDimens.Search.MaxWidth.roundToPx()
        val centred = (constraints.maxWidth - 2 * (maxOf(startWidth, endWidth) + gap)).coerceIn(0, maxCenter)
        val between = (constraints.maxWidth - startWidth - endWidth - 2 * gap).coerceIn(0, maxCenter)
        val needed = centerMeasurables.maxOfOrNull { it.minIntrinsicWidth(height) } ?: 0
        val fitsCentred = needed <= centred
        val centerMax = if (fitsCentred) centred else between
        val centerPlaceables = centerMeasurables.map { it.measure(Constraints(maxWidth = centerMax, maxHeight = height)) }
        layout(constraints.maxWidth, height) {
            startPlaceables.forEach { it.placeRelative(0, (height - it.height) / 2) }
            centerPlaceables.forEach {
                val x = if (fitsCentred) (constraints.maxWidth - it.width) / 2 else startWidth + gap + (between - it.width) / 2
                it.placeRelative(x, (height - it.height) / 2)
            }
            endPlaceables.forEach { it.placeRelative(constraints.maxWidth - it.width, (height - it.height) / 2) }
        }
    }
}
