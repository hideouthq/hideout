package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.handCursor
import gg.hideouthq.core.designsystem.theme.HideoutDimens

/**
 * A large waveform that is also the seek bar: [bars] (heights 0..1) spread across its width, the
 * part before [progress] in primary and the rest faint. While [playing] the bars breathe, each at
 * its own rate. Hovering shows where a click would land; clicking or dragging calls [onSeek] with
 * a fraction 0..1. [progress] is read while drawing, so it can change every frame cheaply.
 */
@Composable
fun SeekableWaveform(
    bars: List<Float>,
    progress: () -> Float,
    playing: Boolean,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    var hoverX by remember { mutableFloatStateOf(-1f) }
    val breathe = rememberBreathing(playing, amount = 0.18f, baseHz = 1.3f, spreadHz = 0.29f, variants = 7)
    val idle = scheme.surfaceContainerHighest
    val played = scheme.primary
    val input = if (!enabled) Modifier else Modifier
        .hoverable(source)
        .handCursor()
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    hoverX = if (event.type == PointerEventType.Exit) -1f else event.changes.first().position.x
                }
            }
        }
        .pointerInput(onSeek) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(onSeek) { detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) } }
    Canvas(modifier.then(input).semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress(), 0f..1f) }) {
        val pitch = HideoutDimens.SoundStage.BarPitch.toPx()
        val stroke = HideoutDimens.SoundStage.BarStroke.toPx()
        val count = minOf(bars.size, (size.width / pitch).toInt()).coerceAtLeast(1)
        val step = size.width / count
        val centre = size.height / 2
        val reached = progress() * count
        repeat(count) { index ->
            val bar = bars[index * bars.size / count]
            val length = (bar * size.height * (1f + breathe(index))).coerceIn(stroke, size.height)
            val x = step * index + step / 2
            val fill = (reached - index).coerceIn(0f, 1f)
            drawLine(lerp(idle, played, fill), Offset(x, centre - length / 2), Offset(x, centre + length / 2), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        if (states.hovered && hoverX >= 0f) {
            val cursor = HideoutDimens.SoundStage.CursorWidth.toPx()
            drawLine(scheme.onSurface.copy(alpha = CursorAlpha), Offset(hoverX, 0f), Offset(hoverX, size.height), strokeWidth = cursor, cap = StrokeCap.Round)
        }
    }
}

private const val CursorAlpha = 0.6f
