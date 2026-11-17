package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun rememberPopScale(trigger: Any?, peak: Float, enabled: Boolean = true): State<Float> {
    val motion = HideoutTheme.motion
    val scale = remember { Animatable(1f) }
    val last = remember { arrayOf(trigger) }
    LaunchedEffect(trigger) {
        if (last[0] == trigger) return@LaunchedEffect
        last[0] = trigger
        if (!enabled || motion.reduced) return@LaunchedEffect
        scale.animateTo(peak, motion.effectsFast())
        scale.animateTo(1f, motion.spatialFast())
    }
    return scale.asState()
}
