package gg.hideouthq.core.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import gg.hideouthq.core.designsystem.component.WindowControls
import gg.hideouthq.core.designsystem.component.WindowControlsLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.window_close
import gg.hideouthq.resources.window_maximize
import gg.hideouthq.resources.window_minimize
import gg.hideouthq.resources.window_restore
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The window around the app, provided by the platform's entry point so shared UI never touches
 * window APIs. Absent (null) in offscreen previews.
 */
@Stable
interface WindowChrome {
    val isMaximized: Boolean

    /** Makes an empty area drag the window; double-click toggles maximize. Children keep their presses. */
    val dragArea: Modifier

    fun minimize()

    fun toggleMaximize()

    fun close()
}

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

@Composable
fun Modifier.windowDragArea(): Modifier = LocalWindowChrome.current?.let { then(it.dragArea) } ?: this

@Stable
class WindowControlsSpot {
    var bounds: Rect? by mutableStateOf(null)
}

val LocalWindowControlsSpot = staticCompositionLocalOf<WindowControlsSpot?> { null }

@Composable
fun WindowChromeControls(modifier: Modifier = Modifier, page: Boolean = true) {
    val chrome = LocalWindowChrome.current ?: return
    val spot = LocalWindowControlsSpot.current
    WindowControls(
        maximized = chrome.isMaximized,
        onMinimize = chrome::minimize,
        onToggleMaximize = chrome::toggleMaximize,
        onClose = chrome::close,
        labels = WindowControlsLabels(
            minimize = stringResource(Res.string.window_minimize),
            maximize = stringResource(Res.string.window_maximize),
            restore = stringResource(Res.string.window_restore),
            close = stringResource(Res.string.window_close),
        ),
        modifier = if (page && spot != null) modifier.onGloballyPositioned { spot.bounds = it.boundsInWindow() } else modifier,
        tabbable = page,
    )
}

/**
 * The window's controls drawn again above a whole-window modal's scrim, exactly where the page shows them, so a dialog
 * never stops the window being closed, minimized or maximized. Place it in the modal's full-window box.
 */
@Composable
fun ModalWindowControls() {
    val bounds = LocalWindowControlsSpot.current?.bounds ?: return
    WindowChromeControls(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }, page = false)
}
