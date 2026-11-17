package gg.hideouthq.core.designsystem.interaction

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun Modifier.pressScale(pressed: Boolean, pressedScale: Float = HideoutMotion.PressScale): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = HideoutTheme.motion.spatialFast(),
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** The hover brightness factor (1 → 1.12), animated with the fast effects spring. Read it at draw time. */
@Composable
fun animatedHoverBrightness(hovered: Boolean): State<Float> = animateFloatAsState(
    targetValue = if (hovered) HideoutMotion.HoverBrightness else 1f,
    animationSpec = HideoutTheme.motion.effectsFast(),
    label = "hoverBrightness",
)
