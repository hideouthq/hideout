package gg.hideouthq.feature.discover.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalWindowInfo
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

private const val TickMillis = 200L

/**
 * The Discover hero's auto-advance clock: a period that elapses only while nothing holds it back,
 * so pausing (hover, focus, the pause button, an unfocused window, reduced motion) and resuming
 * never loses progress already made toward the next advance.
 */
@Stable
class HeroRotation internal constructor(private val periodMillis: Long) {
    var userPaused by mutableStateOf(false)
    internal var hovered by mutableStateOf(false)
    internal var focused by mutableStateOf(false)
    internal var windowFocused by mutableStateOf(true)
    internal var reducedMotion by mutableStateOf(false)
    private var elapsedMillis by mutableLongStateOf(0L)

    val paused: Boolean get() = userPaused || hovered || focused || !windowFocused || reducedMotion

    /** 0..1 toward the next advance; frozen while [paused]. */
    val progress: Float get() = (elapsedMillis.toFloat() / periodMillis).coerceIn(0f, 1f)

    internal fun tick(deltaMillis: Long): Boolean {
        if (paused || deltaMillis <= 0) return false
        elapsedMillis += deltaMillis
        if (elapsedMillis < periodMillis) return false
        elapsedMillis -= periodMillis
        return true
    }

    internal fun resetProgress() {
        elapsedMillis = 0L
    }
}

/**
 * Drives [HeroRotation]'s clock a frame at a time and calls [onAdvance] each time it completes; does
 * nothing with fewer than two items. [current] resets progress, so a manual selection (click or
 * arrow key) restarts the fill for the newly current thumbnail instead of carrying over the old one.
 */
@Composable
fun rememberHeroRotation(count: Int, current: Int, onAdvance: () -> Unit): HeroRotation {
    val motion = HideoutTheme.motion
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val rotation = remember { HeroRotation(HideoutDimens.Hero.PagerRotationPeriodMillis) }
    rotation.reducedMotion = motion.reduced
    rotation.windowFocused = windowFocused
    val latestOnAdvance = rememberUpdatedState(onAdvance)
    LaunchedEffect(rotation, count > 1) {
        if (count <= 1) return@LaunchedEffect
        while (true) {
            // Waiting for a frame makes Compose draw one, so a paused clock must not wait for frames at all.
            snapshotFlow { rotation.paused }.first { !it }
            var last = withFrameMillis { it }
            while (!rotation.paused) {
                delay(TickMillis)
                val now = withFrameMillis { it }
                if (rotation.tick(now - last)) latestOnAdvance.value()
                last = now
            }
        }
    }
    LaunchedEffect(current) { rotation.resetProgress() }
    return rotation
}
