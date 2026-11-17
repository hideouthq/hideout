package gg.hideouthq.core.ui.transition

import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.util.lerp
import gg.hideouthq.core.designsystem.shape.CookieToStage
import gg.hideouthq.core.designsystem.shape.fitTo
import gg.hideouthq.core.designsystem.shape.toPath
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutTheme

enum class NavTransitionKind { Section, OpenMod, CloseMod }

val LocalNavTransition = compositionLocalOf { NavTransitionKind.Section }

val LocalTravellingModId = compositionLocalOf<String?> { null }

enum class TravelShape(val radius: Dp, val height: Dp) {
    Card(HideoutRadius.Card, HideoutDimens.Card.HeightBrowse),
    Cookie(HideoutRadius.Card, HideoutDimens.Hero.Art),
}

@Stable
class TravelOutline {
    var shape by mutableStateOf(TravelShape.Card)
}

val LocalTravelOutline = staticCompositionLocalOf { TravelOutline() }

/**
 * Marks an element that travels between screens under [key] (a card's cover becoming the viewer's
 * stage, a card title becoming the page title). Opening, the bounds follow the default spatial
 * spring (the slow one from the hero's cookie); closing, a quicker one without overshoot, so a large
 * stage never flies past its card. Both copies crossfade inside the shared bounds, this copy with
 * [enter] when it arrives and [exit] when it leaves; a [cover] is clipped to the travelling outline
 * (see [CoverTravel]). With [scaleContent] the content keeps
 * its own layout and is scaled to the bounds (text), otherwise it is laid out at each size.
 * It only travels while [enabled] (the mod it belongs to is the one opening or closing). Outside
 * navigation (previews, another frame) and with reduced motion it does nothing, so the screens
 * simply crossfade.
 */
@Composable
fun Modifier.sharedElement(
    key: String,
    cover: CoverTravel? = null,
    scaleContent: Boolean = false,
    enter: EnterTransition? = null,
    exit: ExitTransition? = null,
    zIndexInOverlay: Float = 0f,
    enabled: Boolean = true,
): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedVisibilityScope.current ?: return this
    val motion = HideoutTheme.motion
    if (motion.reduced) return this
    val closing = LocalNavTransition.current == NavTransitionKind.CloseMod
    val outline = LocalTravelOutline.current
    val declared = cover?.source
    if (declared != null && enabled) SideEffect { outline.shape = declared }
    val bounds = remember(closing, motion, outline) {
        BoundsTransform { _, _ ->
            when {
                closing -> motion.paneSpatialExit()
                outline.shape == TravelShape.Cookie -> motion.spatialSlow()
                else -> motion.spatialDefault()
            }
        }
    }
    return with(shared) {
        val enabledState = rememberUpdatedState(enabled)
        val config = remember {
            object : SharedTransitionScope.SharedContentConfig {
                override val SharedTransitionScope.SharedContentState.isEnabled: Boolean get() = enabledState.value
            }
        }
        val state = rememberSharedContentState(key, config)
        val clip = remember(cover, outline) { cover?.let { CoverClip(outline, it.stageHeight) } }
        this@sharedElement.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = visibility,
            enter = enter ?: fadeIn(motion.effectsDefault()),
            exit = exit ?: fadeOut(motion.exitFade()),
            boundsTransform = bounds,
            resizeMode = if (scaleContent) SharedTransitionScope.ResizeMode.scaleToBounds() else SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = clip ?: NoOverlayClip,
            zIndexInOverlay = zIndexInOverlay,
        )
    }
}

private val NoOverlayClip = object : SharedTransitionScope.OverlayClip {
    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path? = null
}

/**
 * A cover that travels to or from the stage. [source] is the outline it has on its own screen (null
 * for the stage itself); [stageHeight] is the stage's height, where the outline ends up.
 */
@Immutable
data class CoverTravel(val source: TravelShape?, val stageHeight: Dp = HideoutDimens.Stage.Height)

private class CoverClip(private val outline: TravelOutline, private val stageHeight: Dp) : SharedTransitionScope.OverlayClip {
    private val path = Path()

    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path {
        val shape = outline.shape
        val progress = with(density) {
            val from = shape.height.toPx()
            ((bounds.height - from) / (stageHeight.toPx() - from)).coerceIn(0f, 1f)
        }
        path.rewind()
        if (shape == TravelShape.Cookie) {
            CookieToStage.asCubics(progress).toPath(path).fitTo(bounds.size)
            path.translate(bounds.topLeft)
        } else {
            val radius = with(density) { lerp(shape.radius.toPx(), HideoutRadius.Stage.toPx(), progress) }
            path.addRoundRect(RoundRect(bounds, CornerRadius(radius)))
        }
        return path
    }
}
