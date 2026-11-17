package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.Animatable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.lerp
import gg.hideouthq.core.designsystem.interaction.animatedHoverBrightness
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutToggleable
import gg.hideouthq.core.designsystem.theme.brightened
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Composable
fun HideoutSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion

    val trackOff = scheme.surfaceContainerHighest
    val trackOn = scheme.secondary
    val thumbOff = scheme.onSurfaceVariant
    val thumbOn = scheme.onSecondary

    val position = remember { Animatable(if (checked) 1f else 0f) }
    val track = remember { Animatable(if (checked) trackOn else trackOff) }
    val thumb = remember { Animatable(if (checked) thumbOn else thumbOff) }
    LaunchedEffect(checked) {
        coroutineScope {
            launch { position.animateTo(if (checked) 1f else 0f, motion.spatialFast()) }
            launch { track.animateTo(if (checked) trackOn else trackOff, motion.effectsDefault()) }
            launch { thumb.animateTo(if (checked) thumbOn else thumbOff, motion.effectsDefault()) }
        }
    }
    val stretch by animateFloatAsState(
        targetValue = if (states.pressed) 1f else 0f,
        animationSpec = motion.spatialFast(),
        label = "switchThumbStretch",
    )
    val brightness = animatedHoverBrightness(states.hovered)

    Box(
        modifier = modifier
            .size(HideoutDimens.Switch.Width, HideoutDimens.Switch.Height)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .hideoutToggleable(checked, source, enabled = enabled, onValueChange = onCheckedChange)
            .drawBehind {
                val inset = HideoutDimens.Switch.ThumbInset.toPx()
                val thumbHeight = HideoutDimens.Switch.Thumb.toPx()
                val thumbWidth = lerp(thumbHeight, HideoutDimens.Switch.ThumbPressedWidth.toPx(), stretch.coerceIn(0f, 1f))
                val travel = size.width - 2 * inset - thumbWidth
                drawRoundRect(track.value.brightened(brightness.value), cornerRadius = CornerRadius(size.height / 2))
                drawRoundRect(
                    color = thumb.value,
                    topLeft = Offset(inset + travel * position.value, inset),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(thumbHeight / 2),
                )
            }
            .focusRing(states.focusVisible, HideoutShapes.Full),
    )
}
