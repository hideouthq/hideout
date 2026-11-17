package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
internal fun rememberShimmer(active: Boolean = true): State<Float> {
    if (HideoutTheme.motion.reduced || !active) return remember { mutableFloatStateOf(-1f) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    return transition.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "shimmerSweep")
}

@Composable
internal fun Modifier.skeleton(
    sweep: State<Float>,
    shape: Shape,
    base: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    light: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
): Modifier {
    return drawBehind {
        val at = sweep.value
        val brush = if (at < -0.5f) Brush.linearGradient(listOf(base, base)) else Brush.linearGradient(
            0f to base, (at - 0.2f).coerceIn(0f, 1f) to base, at.coerceIn(0f, 1f) to light, (at + 0.2f).coerceIn(0f, 1f) to base, 1f to base,
            start = Offset.Zero, end = Offset(size.width.coerceAtLeast(1f) * 1.6f, 0f),
        )
        drawOutline(shape.createOutline(size, layoutDirection, this), brush)
    }
}

@Composable
internal fun SkeletonBlock(sweep: State<Float>, width: Dp?, height: Dp, shape: Shape = RoundedCornerShape(6.dp), modifier: Modifier = Modifier) {
    Box((if (width != null) modifier.width(width) else modifier.fillMaxWidth()).height(height).skeleton(sweep, shape))
}
