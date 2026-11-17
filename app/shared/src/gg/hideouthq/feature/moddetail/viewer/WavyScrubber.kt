package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * With [onSeek], clicking or dragging the track jumps to that fraction of the clip.
 */
@Composable
fun WavyScrubber(clock: PlaybackClock, playing: Boolean, modifier: Modifier = Modifier, name: String? = null, onSeek: ((Float) -> Unit)? = null) {
    val motion = HideoutTheme.motion
    val scheme = MaterialTheme.colorScheme
    val amplitude = remember { Animatable(if (playing) 1f else 0f) }
    val phase = remember { Animatable(0f) }
    val reveal = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(RevealMillis, delayMillis = RevealDelayMillis, easing = HideoutMotion.EmphasizedDecelerate))
    }
    LaunchedEffect(playing) {
        launch { amplitude.animateTo(if (playing) 1f else 0f, motion.spatialDefault()) }
        if (!playing || !motion.continuousMotion) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            phase.snapTo((phase.value + (now - last) / WaveTravelMillis) % 1f)
            last = now
        }
    }
    val type = HideoutTheme.typography
    Row(
        modifier = modifier
            .drawWithContent { clipRect(right = size.width * reveal.value) { this@drawWithContent.drawContent() } }
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clock.progress, 0f..1f)
                if (onSeek != null) setProgress { onSeek(it.coerceIn(0f, 1f)); true }
            },
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Scrubber.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (name != null) {
            Text(name, Modifier.widthIn(max = HideoutDimens.Scrubber.NameMaxWidth), style = type.labelBadge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(formatPlaybackTime(clock.elapsedMillis), style = type.mono, color = scheme.onSurfaceVariant)
        val played = scheme.primary
        val track = scheme.surfaceContainerHighest
        val seek = rememberUpdatedState(onSeek)
        Canvas(
            Modifier
                .weight(1f)
                .height(HideoutDimens.Scrubber.HandleHeight)
                .clipToBounds()
                .pointerInput(onSeek != null) {
                    if (onSeek == null) return@pointerInput
                    awaitEachGesture {
                        val trackFraction = { x: Float ->
                            val gap = HideoutDimens.Scrubber.Gap.toPx()
                            val handleWidth = HideoutDimens.Scrubber.HandleWidth.toPx()
                            val usable = (size.width - handleWidth - 2 * gap).coerceAtLeast(1f)
                            ((x - gap - handleWidth / 2) / usable).coerceIn(0f, 1f)
                        }
                        val down = awaitFirstDown()
                        seek.value?.invoke(trackFraction(down.position.x))
                        drag(down.id) { change ->
                            change.consume()
                            seek.value?.invoke(trackFraction(change.position.x))
                        }
                    }
                },
        ) {
            val gap = HideoutDimens.Scrubber.Gap.toPx()
            val handleWidth = HideoutDimens.Scrubber.HandleWidth.toPx()
            val usable = (size.width - handleWidth - 2 * gap).coerceAtLeast(0f)
            val waveEnd = usable * clock.progress
            val mid = size.height / 2
            val stroke = HideoutDimens.Scrubber.WaveStroke.toPx()
            if (waveEnd > stroke) {
                val wavelength = HideoutDimens.Scrubber.Wavelength.toPx()
                val lift = HideoutDimens.Scrubber.WaveAmplitude.toPx() * amplitude.value
                val path = Path()
                var x = stroke / 2
                val shift = phase.value * wavelength
                path.moveTo(x, mid - lift * sin(((x + shift) / wavelength) * 2 * PI).toFloat())
                while (x < waveEnd - stroke / 2) {
                    x = minOf(x + 1f, waveEnd - stroke / 2)
                    path.lineTo(x, mid - lift * sin(((x + shift) / wavelength) * 2 * PI).toFloat())
                }
                drawPath(path, played, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }
            val handleX = waveEnd + gap
            drawRoundRect(
                color = played,
                topLeft = Offset(handleX, 0f),
                size = Size(handleWidth, size.height),
                cornerRadius = CornerRadius(handleWidth / 2),
            )
            val trackStart = handleX + handleWidth + gap
            val trackHeight = HideoutDimens.Scrubber.TrackHeight.toPx()
            if (trackStart < size.width) {
                drawRoundRect(
                    color = track,
                    topLeft = Offset(trackStart, mid - trackHeight / 2),
                    size = Size(size.width - trackStart, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2),
                )
            }
        }
        Text(formatPlaybackTime(clock.lengthMillis), style = type.mono, color = scheme.onSurfaceVariant)
    }
}

private const val WaveTravelMillis = 600f
private const val RevealMillis = 450
private const val RevealDelayMillis = 120

