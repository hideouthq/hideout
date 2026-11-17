package gg.hideouthq.core.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * Every duration, easing and spring in the app.
 *
 * The springs come from Material 3 Expressive's [MotionScheme.expressive] (spatial 0.6/800, 0.8/380,
 * 0.8/200; effects 1.0/3800, 1600, 800).
 * With [reduced] on, anything spatial stops moving (it snaps, and callers crossfade instead), and
 * staggering and continuous motion are switched off.
 */
@Immutable
class HideoutMotion internal constructor(
    private val expressive: MotionScheme,
    val reduced: Boolean,
) {
    val scheme: MotionScheme = if (reduced) ReducedMotionScheme(expressive) else expressive

    fun <T> spatialFast(): FiniteAnimationSpec<T> = scheme.fastSpatialSpec()

    fun <T> spatialDefault(): FiniteAnimationSpec<T> = scheme.defaultSpatialSpec()

    fun <T> spatialSlow(): FiniteAnimationSpec<T> = scheme.slowSpatialSpec()

    fun <T> effectsFast(): FiniteAnimationSpec<T> = scheme.fastEffectsSpec()

    fun <T> effectsDefault(): FiniteAnimationSpec<T> = scheme.defaultEffectsSpec()

    fun <T> effectsSlow(): FiniteAnimationSpec<T> = scheme.slowEffectsSpec()

    fun <T> paneSpatial(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PaneStiffness)

    fun <T> paneSpatialExit(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = PaneExitStiffness)

    fun <T> exitFade(): FiniteAnimationSpec<T> = tween(ExitFadeMillis, easing = LinearEasing)

    fun <T> sceneFade(): FiniteAnimationSpec<T> = tween(if (reduced) ReducedCrossfadeMillis else SceneFadeMillis, easing = LinearEasing)

    fun <T> figureRise(): FiniteAnimationSpec<T> = if (reduced) snap() else tween(FigureRiseMillis, easing = StandardEasing)

    fun <T> enterFade(extraDelayMillis: Int = 0): FiniteAnimationSpec<T> =
        tween(EnterFadeMillis, delayMillis = EnterDelayMillis + extraDelayMillis, easing = EffectsEasing)

    fun staggerDelay(index: Int): Int = if (reduced) 0 else min(index, StaggerCap - 1) * StaggerMillis

    val enterOffset: Dp get() = if (reduced) 0.dp else EnterOffset

    val continuousMotion: Boolean get() = !reduced

    companion object {
        const val CoverResolveDelayMillis = 90
        const val CoverResolveMillis = 260
        const val CoverReturnDelayMillis = 40
        const val CoverReturnMillis = 200
        const val TitleCrossfadeDelayMillis = 50
        const val TitleCrossfadeMillis = 120
        const val ExitFadeMillis = 90
        const val SceneFadeMillis = 300
        const val EnterFadeMillis = 200
        const val EnterDelayMillis = 60
        const val StayInPlaceHoldMillis = 100
        const val StaggerMillis = 30
        const val StaggerCap = 8
        const val ReducedCrossfadeMillis = 150
        const val StaggerRiseMillis = 400
        const val FigureRiseMillis = 500
        const val PaneStiffness = 380f
        const val PaneExitStiffness = 800f
        val EnterOffset = 12.dp
        val PanelContentOffset = 6.dp
        val RollOffset = 14.dp

        const val HoverBrightness = 1.12f
        const val HoverScaleCard = 1.015f
        const val PressScale = 0.97f
        const val PressScaleCard = 0.985f
        const val ExitScale = 0.985f
        const val ExitScaleCard = 0.96f
        const val EnterScaleCard = 0.92f
        const val EnterScaleModel = 0.92f
        const val ChipPopScale = 1.06f
        const val IconButtonPressScale = 0.92f
        const val TilePressScale = 0.94f
        const val InstalledPopScale = 1.15f
        const val TogglePressScale = 0.9f
        const val ChipEnterScale = 0.8f
        const val EmptyStateEnterScale = 0.8f
        const val PanelEnterScale = 0.96f
        const val DimmedAlpha = 0.45f
        const val DisabledAlpha = 0.4f
        const val SecondaryCountAlpha = 0.7f
        const val CoverIconAlpha = 0.3f
        const val CoverWaveformAlpha = 0.45f

        val FastSpatialEasing = CubicBezierEasing(0.42f, 1.67f, 0.21f, 0.90f)
        val EffectsEasing = CubicBezierEasing(0.34f, 0.80f, 0.34f, 1.00f)
        val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
        val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    }
}

private class ReducedMotionScheme(private val base: MotionScheme) : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        tween(HideoutMotion.ReducedCrossfadeMillis, easing = LinearEasing)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = base.fastEffectsSpec()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        tween(HideoutMotion.ReducedCrossfadeMillis, easing = LinearEasing)
}

internal fun hideoutMotion(reduced: Boolean) = HideoutMotion(MotionScheme.expressive(), reduced)

internal val LocalHideoutMotion = staticCompositionLocalOf { hideoutMotion(reduced = false) }
