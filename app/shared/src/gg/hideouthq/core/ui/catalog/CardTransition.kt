package gg.hideouthq.core.ui.catalog

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.transition.LocalNavTransition
import gg.hideouthq.core.ui.transition.LocalSharedTransitionScope
import gg.hideouthq.core.ui.transition.CoverTravel
import gg.hideouthq.core.ui.transition.LocalTravellingModId
import gg.hideouthq.core.ui.transition.TravelShape
import gg.hideouthq.core.ui.transition.NavTransitionKind
import gg.hideouthq.core.ui.transition.navEnterExit
import gg.hideouthq.core.ui.transition.sharedElement

private val TitleCrossfade = tween<Float>(HideoutMotion.TitleCrossfadeMillis, delayMillis = HideoutMotion.TitleCrossfadeDelayMillis, easing = LinearEasing)

object CardTransition {
    @Composable
    fun Modifier.cover(modId: String, shape: TravelShape = TravelShape.Card): Modifier {
        val travelling = LocalTravellingModId.current == modId
        return sharedElement(
            key = SharedKeys.cover(modId),
            enabled = travelling,
            cover = CoverTravel(source = shape),
            enter = if (travelling) EnterTransition.None else null,
            exit = if (travelling) fadeOut(tween(1, delayMillis = HideoutMotion.CoverResolveDelayMillis + HideoutMotion.CoverResolveMillis)) else null,
        )
    }

    @Composable
    fun Modifier.title(modId: String): Modifier =
        sharedElement(
            key = SharedKeys.title(modId),
            scaleContent = true,
            zIndexInOverlay = TitleZIndex,
            enabled = LocalTravellingModId.current == modId,
            enter = fadeIn(TitleCrossfade),
            exit = fadeOut(TitleCrossfade),
        )

    @Composable
    fun Modifier.decoration(modId: String): Modifier {
        val shared = LocalSharedTransitionScope.current ?: return this
        if (LocalTravellingModId.current != modId) return this
        val motion = HideoutTheme.motion
        return with(shared) {
            renderInSharedTransitionScopeOverlay(zIndexInOverlay = DecorationZIndex)
        }.navEnterExit(
            fadeIn(tween(HideoutMotion.EnterFadeMillis, delayMillis = DecorationReturnDelayMillis)),
            fadeOut(motion.exitFade()),
        )
    }

    private const val DecorationZIndex = 1f
    private const val TitleZIndex = 2f
    private const val DecorationReturnDelayMillis = 220

    @Composable
    fun Modifier.card(): Modifier {
        if (LocalNavTransition.current == NavTransitionKind.Section) return this
        val motion = HideoutTheme.motion
        return navEnterExit(
            EnterTransition.None,
            fadeOut(motion.exitFade()) + scaleOut(tween(HideoutMotion.ExitFadeMillis), targetScale = HideoutMotion.ExitScaleCard),
        )
    }
}
