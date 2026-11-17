package gg.hideouthq.feature.appupdate

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.progressSemantics
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.sin

private const val WaveMillis = 1_400
private const val FullTurn = (2 * PI).toFloat()

@Composable
internal fun WavyProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), HideoutTheme.motion.effectsDefault(), label = "appUpdateProgress")
    val phase = wavePhase()
    val done = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val dimens = HideoutDimens.AppUpdate
    Canvas(modifier.progressSemantics(shown)) {
        val stroke = dimens.WaveStroke.toPx()
        val middle = size.height / 2
        val waveEnd = stroke / 2 + (size.width - stroke) * shown
        if (shown > 0f) drawWave(waveEnd, middle, phase.value, Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round), done)
        val dot = dimens.WaveStopDot.toPx()
        val trackEnd = size.width - dot * 2 - stroke
        val trackStart = waveEnd + dimens.WaveGap.toPx()
        if (trackStart < trackEnd) drawLine(track, Offset(trackStart, middle), Offset(trackEnd, middle), stroke, StrokeCap.Round)
        drawCircle(done, dot, Offset(size.width - dot, middle))
    }
}

private fun DrawScope.drawWave(end: Float, middle: Float, phase: Float, stroke: Stroke, color: Color) {
    val amplitude = HideoutDimens.AppUpdate.WaveAmplitude.toPx()
    val wavelength = HideoutDimens.AppUpdate.Wavelength.toPx()
    val start = stroke.width / 2
    val path = Path().apply {
        moveTo(start, middle + amplitude * sin(phase))
        var x = start
        while (x < end) {
            x = minOf(x + 1.dp.toPx(), end)
            lineTo(x, middle + amplitude * sin(FullTurn * (x - start) / wavelength + phase))
        }
    }
    drawPath(path, color, style = stroke)
}

@Composable
private fun wavePhase(): State<Float> {
    if (LocalReducedMotion.current) return remember { mutableStateOf(0f) }
    return rememberInfiniteTransition(label = "appUpdateWave").animateFloat(
        initialValue = 0f,
        targetValue = FullTurn,
        animationSpec = infiniteRepeatable(tween(WaveMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "appUpdateWavePhase",
    )
}

@Composable
internal fun UpdateProgressRing(fraction: Float, modifier: Modifier = Modifier) {
    val done = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val dimens = HideoutDimens.TopBarChip
    Canvas(modifier.size(dimens.Ring).progressSemantics(fraction)) {
        val width = dimens.RingStroke.toPx()
        val inset = Offset(width / 2, width / 2)
        val arc = Size(size.width - width, size.height - width)
        drawArc(track, startAngle = -90f, sweepAngle = 360f, useCenter = false, topLeft = inset, size = arc, style = Stroke(width))
        drawArc(done, startAngle = -90f, sweepAngle = 360f * fraction.coerceIn(0f, 1f), useCenter = false, topLeft = inset, size = arc, style = Stroke(width, cap = StrokeCap.Round))
    }
}
