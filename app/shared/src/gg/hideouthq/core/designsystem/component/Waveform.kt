package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun Waveform(
    bars: List<Dp>,
    playing: Boolean,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
) {
    val scheme = MaterialTheme.colorScheme
    val idle = scheme.surfaceContainerHighest
    val active = scheme.primary
    val wobble = rememberBreathing(playing, amount = 0.22f, baseHz = 1.6f, spreadHz = 0.37f, variants = 5)

    Canvas(modifier.size(HideoutDimens.SoundRow.WaveformWidth, HideoutDimens.SoundRow.WaveformHeight)) {
        val stroke = HideoutDimens.SoundRow.WaveformStroke.toPx()
        val pitch = HideoutDimens.SoundRow.WaveformBarPitch.toPx()
        val centre = size.height / 2
        val maxLength = size.height
        bars.forEachIndexed { index, bar ->
            val length = (bar.toPx() * (1f + wobble(index))).coerceIn(stroke, maxLength)
            val x = pitch / 2 + pitch * index
            val reached = ((progress.coerceIn(0f, 1f) * bars.size) - index).coerceIn(0f, 1f)
            drawLine(
                color = lerp(idle, active, reached),
                start = Offset(x, centre - length / 2),
                end = Offset(x, centre + length / 2),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * How far bar `index` of a playing waveform stretches, ±[amount]: each bar swings at its own rate,
 * [baseHz] plus [spreadHz] per step of `index % variants`, and the swing eases in and out with [playing].
 * Read it while drawing, so each frame only redraws.
 */
@Composable
internal fun rememberBreathing(playing: Boolean, amount: Float, baseHz: Float, spreadHz: Float, variants: Int): (index: Int) -> Float {
    val motion = HideoutTheme.motion
    val nanos = remember { mutableLongStateOf(0L) }
    val swing = remember { Animatable(0f) }
    LaunchedEffect(playing) { swing.animateTo(if (playing) 1f else 0f, motion.spatialDefault()) }
    LaunchedEffect(playing, motion.continuousMotion) {
        if (!playing || !motion.continuousMotion) return@LaunchedEffect
        val start = withFrameNanos { it } - nanos.longValue
        while (true) withFrameNanos { nanos.longValue = it - start }
    }
    return { index ->
        val seconds = nanos.longValue / NanosPerSecond
        sin(2 * PI * (baseHz + spreadHz * (index % variants)) * seconds + index).toFloat() * amount * swing.value
    }
}

private const val NanosPerSecond = 1_000_000_000f
