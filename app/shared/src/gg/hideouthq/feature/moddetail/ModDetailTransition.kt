package gg.hideouthq.feature.moddetail

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.catalog.SharedKeys
import gg.hideouthq.core.ui.transition.CoverTravel
import gg.hideouthq.core.ui.transition.LocalTravellingModId
import gg.hideouthq.core.ui.transition.navEnterExit
import gg.hideouthq.core.ui.transition.sharedElement
import gg.hideouthq.feature.moddetail.viewer.StageChoreography

internal object ModDetailTransition {
    private const val ResolveDelayMillis = 140
    private const val ResolveFadeMillis = 220
    private const val ToolbarDelayMillis = 120
    private const val ToolbarRiseMillis = 350
    private const val InstallBarRiseMillis = 420
    private const val ToolScaleFrom = 0.8f

    @Composable
    fun Modifier.stage(modId: String, height: Dp): Modifier {
        val travelling = LocalTravellingModId.current == modId
        return sharedElement(
            key = SharedKeys.cover(modId),
            enabled = travelling,
            cover = CoverTravel(source = null, stageHeight = height),
            enter = fadeIn(tween(HideoutMotion.CoverResolveMillis, delayMillis = HideoutMotion.CoverResolveDelayMillis)),
            exit = if (travelling) fadeOut(tween(HideoutMotion.CoverReturnMillis, delayMillis = HideoutMotion.CoverReturnDelayMillis)) else null,
            zIndexInOverlay = StageZIndex,
        )
    }

    private const val StageZIndex = 0.5f

    @Composable
    fun stage(): StageChoreography {
        val motion = HideoutTheme.motion
        val resolve = fadeIn(tween(ResolveFadeMillis, delayMillis = ResolveDelayMillis))
        val rise = with(LocalDensity.current) { HideoutDimens.Toolbar.EnterOffset.roundToPx() }
        val toolbarRise = tween<IntOffset>(ToolbarRiseMillis, delayMillis = ToolbarDelayMillis, easing = HideoutMotion.FastSpatialEasing)
        return StageChoreography(
            resolve = Modifier.navEnterExit(resolve, fadeOut(motion.exitFade())),
            model = Modifier.navEnterExit(
                resolve + scaleIn(motion.spatialDefault(), initialScale = HideoutMotion.EnterScaleModel),
                fadeOut(motion.exitFade()),
            ),
            toolbar = Modifier.navEnterExit(
                fadeIn(tween(ResolveFadeMillis, delayMillis = ToolbarDelayMillis)) + slideInVertically(toolbarRise) { rise },
                fadeOut(motion.exitFade()),
            ),
        )
    }

    @Composable
    fun Modifier.installBar(): Modifier {
        val motion = HideoutTheme.motion
        val delay = HideoutMotion.EnterDelayMillis + motion.staggerDelay(HideoutMotion.StaggerCap)
        return navEnterExit(
            fadeIn(motion.enterFade(motion.staggerDelay(HideoutMotion.StaggerCap))) +
                slideInVertically(tween(InstallBarRiseMillis, delayMillis = delay, easing = HideoutMotion.EmphasizedDecelerate)) { it },
            fadeOut(motion.exitFade()),
        )
    }

    @Composable
    fun Modifier.tool(index: Int): Modifier {
        val motion = HideoutTheme.motion
        return navEnterExit(
            fadeIn(motion.enterFade(motion.staggerDelay(index))) + scaleIn(motion.spatialFast(), initialScale = ToolScaleFrom),
            fadeOut(motion.exitFade()) + scaleOut(tween(HideoutMotion.ExitFadeMillis), targetScale = ToolScaleFrom),
        )
    }

    @Composable
    fun Modifier.compare(): Modifier {
        val motion = HideoutTheme.motion
        return navEnterExit(fadeIn(motion.enterFade()), fadeOut(motion.exitFade()))
    }
}
