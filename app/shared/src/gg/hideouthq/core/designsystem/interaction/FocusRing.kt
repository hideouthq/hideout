package gg.hideouthq.core.designsystem.interaction

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun Modifier.focusRing(visible: Boolean, shape: Shape): Modifier {
    val width by animateDpAsState(
        targetValue = if (visible) HideoutDimens.FocusRing else 0.dp,
        animationSpec = HideoutTheme.motion.effectsFast(),
        label = "focusRing",
    )
    return insetRing(shape, MaterialTheme.colorScheme.primary) { width.toPx() }
}

/** Draws a ring of [widthPx] inside [shape]. [widthPx] is read at draw time, so animating it only redraws. */
fun Modifier.insetRing(shape: Shape, color: Color, widthPx: Density.() -> Float): Modifier =
    drawWithCache {
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        onDrawWithContent {
            drawContent()
            val width = widthPx()
            if (width > 0f) {
                clipPath(path) { drawPath(path, color, style = Stroke(width = width * 2)) }
            }
        }
    }
