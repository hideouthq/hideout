package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

/** How one step of a run stands. [Running.progress] is 0..1, or null while how far it is cannot be told. */
@Immutable
sealed interface StepStatus {
    data object Done : StepStatus

    data class Running(val progress: Float? = null) : StepStatus

    data object Waiting : StepStatus
}

@Composable
fun StatusCircle(status: StepStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val circle = modifier.size(HideoutDimens.StatusCircle.Size)
    when (status) {
        StepStatus.Done -> Box(circle.background(scheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            HideoutIcon(HideoutIcons.CheckBold, contentDescription = null, size = HideoutDimens.StatusCircle.Check, tint = scheme.secondary)
        }
        is StepStatus.Running -> {
            val known = status.progress
            val sweep by animateFloatAsState(known?.coerceIn(0f, 1f) ?: IndeterminateSweep, HideoutTheme.motion.spatialDefault(), label = "statusSweep")
            val turn = if (known == null && !HideoutTheme.motion.reduced) {
                val transition = rememberInfiniteTransition(label = "statusTurn")
                transition.animateFloat(0f, 360f, infiniteRepeatable(tween(TurnMillis, easing = LinearEasing), RepeatMode.Restart), label = "statusTurnAngle").value
            } else 0f
            val track = scheme.surfaceContainerHighest
            val arc = scheme.secondary
            Box(
                circle.background(scheme.surfaceContainerHigh, CircleShape).drawBehind {
                    val stroke = HideoutDimens.StatusCircle.ProgressStroke.toPx()
                    val diameter = HideoutDimens.StatusCircle.ProgressDiameter.toPx()
                    val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
                    val box = Size(diameter, diameter)
                    drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke))
                    drawArc(arc, turn - 90f, 360f * sweep, useCenter = false, topLeft = topLeft, size = box, style = Stroke(stroke, cap = StrokeCap.Round))
                },
            )
        }
        StepStatus.Waiting -> Box(circle.insetRing(CircleShape, scheme.surfaceContainerHighest) { HideoutDimens.StatusCircle.Ring.toPx() })
    }
}

private const val IndeterminateSweep = 0.3f
private const val TurnMillis = 1_100
