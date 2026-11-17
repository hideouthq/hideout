package gg.hideouthq.core.ui.transition

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.delay

/**
 * The animation scope of the navigation entry a composable belongs to (null outside navigation,
 * for example in previews or another front end's frame). Screens use it through the helpers below.
 */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

val LocalNavEntryKey = staticCompositionLocalOf<Any> { Unit }

/** Applies [enter] when this entry arrives and [exit] when it leaves; does nothing outside navigation. */
@Composable
fun Modifier.navEnterExit(enter: EnterTransition, exit: ExitTransition): Modifier {
    val scope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(scope) { this@navEnterExit.animateEnterExit(enter, exit) }
}

/**
 * For things that sit in the same place on both screens (the search pill, the overflow button):
 * the arriving copy (on top) fades in quickly over the leaving one, which only fades once it is
 * covered, so only what differs (the placeholder text) is seen to change.
 */
@Composable
fun Modifier.navStayInPlace(): Modifier {
    val motion = HideoutTheme.motion
    return navEnterExit(
        fadeIn(motion.effectsFast()),
        fadeOut(tween(HideoutMotion.ExitFadeMillis, delayMillis = HideoutMotion.StayInPlaceHoldMillis)),
    )
}

@Composable
fun Modifier.navBodyEnterExit(rise: Boolean = true): Modifier {
    val motion = HideoutTheme.motion
    val offset = with(LocalDensity.current) { motion.enterOffset.roundToPx() }
    val enter = fadeIn(motion.enterFade()) + if (rise) slideInVertically(motion.spatialDefault()) { offset } else EnterTransition.None
    val scale = LocalNavTransition.current == NavTransitionKind.Section
    val exit = fadeOut(motion.exitFade()) +
        if (scale) scaleOut(tween(HideoutMotion.ExitFadeMillis), targetScale = HideoutMotion.ExitScale) else ExitTransition.None
    return navEnterExit(enter, exit)
}

@Composable
fun Modifier.staggeredEnter(index: Int): Modifier {
    val motion = HideoutTheme.motion
    val delay = motion.staggerDelay(index)
    val offset = with(LocalDensity.current) { motion.enterOffset.roundToPx() }
    val rise = tween<IntOffset>(
        durationMillis = HideoutMotion.StaggerRiseMillis,
        delayMillis = HideoutMotion.EnterDelayMillis + delay,
        easing = HideoutMotion.EmphasizedDecelerate,
    )
    return navEnterExit(fadeIn(motion.enterFade(delay)) + slideInVertically(rise) { offset }, ExitTransition.None)
}

@Composable
fun Modifier.chipCascade(index: Int): Modifier {
    val motion = HideoutTheme.motion
    val alpha = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        delay(motion.staggerDelay(index).toLong())
        alpha.animateTo(1f, motion.effectsDefault())
    }
    return graphicsLayer {
        this.alpha = alpha.value
        val scale = HideoutMotion.ChipEnterScale + (1f - HideoutMotion.ChipEnterScale) * alpha.value
        scaleX = scale
        scaleY = scale
    }
}
