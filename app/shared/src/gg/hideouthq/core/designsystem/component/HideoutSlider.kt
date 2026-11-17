package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.interaction.collectIsFocusVisibleAsState
import gg.hideouthq.core.designsystem.interaction.handCursor
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlin.math.roundToInt

private const val HaloAlpha = 0.35f

@Composable
fun HideoutSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(activeTrackColor = scheme.primary, inactiveTrackColor = scheme.surfaceContainerHighest)
    // Material's 48dp touch target would make the 36dp control taller than the design's row allows.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Slider(
            value = value,
            onValueChange = { onValueChange(range.start + ((it - range.start) / step).roundToInt() * step) },
            modifier = modifier.semantics { contentDescription = label }.handCursor(),
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            colors = colors,
            interactionSource = source,
            thumb = { SliderHandle(source) },
            track = { state ->
                SliderDefaults.Track(
                    state,
                    colors = colors,
                    drawStopIndicator = null,
                    drawTick = { _, _ -> },
                    thumbTrackGapSize = HideoutDimens.Slider.HandleGap,
                )
            },
        )
    }
}

@Composable
private fun SliderHandle(source: MutableInteractionSource) {
    val color = MaterialTheme.colorScheme.primary
    val focused by source.collectIsFocusVisibleAsState()
    val halo by animateFloatAsState(if (focused) 1f else 0f, HideoutTheme.motion.effectsFast(), label = "sliderHalo")
    Spacer(
        Modifier
            .size(HideoutDimens.Slider.HandleWidth, HideoutDimens.Slider.HandleHeight)
            .drawBehind {
                val radius = size.width / 2
                val ring = HideoutDimens.Slider.Halo.toPx() * halo
                if (ring > 0f) {
                    drawRoundRect(
                        color.copy(alpha = HaloAlpha),
                        topLeft = Offset(-ring, -ring),
                        size = Size(size.width + 2 * ring, size.height + 2 * ring),
                        cornerRadius = CornerRadius(radius + ring),
                    )
                }
                drawRoundRect(color, cornerRadius = CornerRadius(radius))
            },
    )
}
