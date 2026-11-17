package gg.hideouthq.feature.moddetail.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import gg.hideouthq.preview.PreviewClip

/**
 * Where playback is in the current clip. On the model stage it is the only animation clock: it advances with the
 * frame clock while playing, at the chosen speed, wraps when looping, and the renderer samples [elapsedMillis].
 */
@Stable
class PlaybackClock internal constructor() {
    var elapsedMillis by mutableFloatStateOf(0f)
        internal set
    var lengthMillis by mutableFloatStateOf(1f)
        internal set

    val progress: Float get() = (elapsedMillis / lengthMillis).coerceIn(0f, 1f)

    fun seekTo(fraction: Float) {
        elapsedMillis = fraction.coerceIn(0f, 1f) * lengthMillis
    }
}

/**
 * The model stage's timeline for [clip]. A new one starts at zero in the same composition that chooses the clip or
 * starts or stops [running] (a model really drawn), so no frame pairs a clip with another's time and a viewport that
 * cannot draw shows zero. It advances only while [playing] and [running].
 */
@Composable
fun rememberPlaybackClock(
    clip: PreviewClip?,
    playing: Boolean,
    speed: Float,
    loop: Boolean,
    running: Boolean,
    onFinished: () -> Unit,
): PlaybackClock {
    val clock = remember(clip, running) { PlaybackClock().apply { lengthMillis = clip?.durationMillis?.toFloat() ?: 1f } }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(clock, playing, speed, loop, running) {
        if (!playing || !running || clip == null) return@LaunchedEffect
        if (clock.elapsedMillis >= clock.lengthMillis) clock.elapsedMillis = 0f
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val step = stepPlayback(clock.elapsedMillis, clock.lengthMillis, (now - last) / NanosPerMilli, speed, loop)
            last = now
            clock.elapsedMillis = step.elapsedMillis
            if (step.finished) {
                finished()
                break
            }
        }
    }
    return clock
}

internal data class PlaybackStep(val elapsedMillis: Float, val finished: Boolean)

internal fun stepPlayback(elapsedMillis: Float, lengthMillis: Float, frameMillis: Float, speed: Float, loop: Boolean): PlaybackStep {
    val next = elapsedMillis + frameMillis * speed
    return when {
        next < lengthMillis -> PlaybackStep(next, finished = false)
        loop -> PlaybackStep(next % lengthMillis, finished = false)
        else -> PlaybackStep(lengthMillis, finished = true)
    }
}

/**
 * A clock that follows a sound player: it jumps to each [position] the player reports and, while
 * [playing], runs on between reports so the scrubber glides rather than steps.
 */
@Composable
fun rememberSampleClock(position: () -> Int, lengthMillis: Int?, playing: Boolean): PlaybackClock {
    val clock = remember { PlaybackClock() }
    val reported = remember { ReportedPosition() }
    clock.lengthMillis = (lengthMillis ?: 0).coerceAtLeast(1).toFloat()
    LaunchedEffect(clock) {
        snapshotFlow { position() }.collect { millis ->
            reported.millis = millis
            reported.atFrameMillis = withFrameMillis { it }
            clock.elapsedMillis = millis.toFloat()
        }
    }
    LaunchedEffect(clock, playing) {
        if (!playing) return@LaunchedEffect
        reported.millis = position()
        reported.atFrameMillis = withFrameMillis { it }
        clock.elapsedMillis = reported.millis.toFloat()
        while (true) {
            withFrameMillis { now ->
                if (reported.atFrameMillis > 0) clock.elapsedMillis = (reported.millis + now - reported.atFrameMillis).toFloat().coerceAtMost(clock.lengthMillis)
            }
        }
    }
    return clock
}

private class ReportedPosition {
    var millis = 0
    var atFrameMillis = 0L
}

/** 1234 ms → "0:01.2". */
fun formatPlaybackTime(millis: Float): String {
    val tenths = (millis / 100).toInt()
    return "${tenths / 600}:${(tenths / 10 % 60).toString().padStart(2, '0')}.${tenths % 10}"
}

private const val NanosPerMilli = 1_000_000f
