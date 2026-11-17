package gg.hideouthq.core.designsystem.component

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * Text that wraps like CSS `text-wrap: balance` (which Skia lacks): it keeps the line count a plain
 * wrap would need, but narrows itself until the lines are as even as possible, so a two-line title
 * breaks near the middle instead of leaving one word on the last line. A plain layout (not
 * subcomposition), so the text can take part in shared-element transitions.
 */
@Composable
fun BalancedText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier, textModifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Layout(content = { Text(text = text, style = style, color = color, modifier = textModifier) }, modifier = modifier) { measurables, constraints ->
        val max = constraints.maxWidth
        val width = if (max == Constraints.Infinity) {
            max
        } else {
            fun lines(w: Int) = measurer.measure(text, style, constraints = Constraints(maxWidth = w), density = this).lineCount
            val target = lines(max)
            if (target <= 1) {
                max
            } else {
                var low = 1
                var high = max
                while (low < high) {
                    val mid = (low + high) / 2
                    if (lines(mid) <= target) high = mid else low = mid + 1
                }
                low
            }
        }
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, maxWidth = width))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}
