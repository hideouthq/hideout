package gg.hideouthq.window

import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import gg.hideouthq.OnWindows
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.ui.WindowChrome
import java.awt.Frame
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit

@Stable
internal class DesktopWindowChrome(
    private val window: Frame,
    private val state: WindowState,
    private val onClose: () -> Unit,
) : WindowChrome {
    override val isMaximized: Boolean get() = state.placement == WindowPlacement.Maximized
    override val dragArea: Modifier = Modifier.windowDragArea(window, state)

    override fun minimize() {
        state.isMinimized = true
    }

    override fun toggleMaximize() = state.toggleMaximized(window)

    override fun close() = onClose()
}

/**
 * Windows gives a maximized undecorated window the whole screen, taskbar included, so there it is first held to the
 * [maximizedArea] of the screen it is on.
 */
internal fun WindowState.toggleMaximized(window: Frame) {
    if (placement == WindowPlacement.Maximized) {
        placement = WindowPlacement.Floating
        return
    }
    if (OnWindows) window.maximizedBounds = window.graphicsConfiguration.let { maximizedArea(it.bounds, it.insets) }
    placement = WindowPlacement.Maximized
}

internal fun usableArea(screen: Rectangle, insets: Insets) =
    Rectangle(screen.x + insets.left, screen.y + insets.top, screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom)

/**
 * A screen no taskbar takes any of, as when it hides itself, loses one pixel at the bottom, since Windows treats a
 * window covering the whole screen as fullscreen and would no longer show the hidden taskbar over it.
 */
internal fun maximizedArea(screen: Rectangle, insets: Insets): Rectangle = usableArea(screen, insets).apply { if (this == screen) height -= 1 }

/** Compose sizes a window by taking its dp as AWT units, the units AWT gives screens and insets in, so the two compare as they are. */
internal fun firstWindowSize(screen: Rectangle, insets: Insets): DpSize = usableArea(screen, insets).let { usable ->
    DpSize(HideoutDimens.Window.DefaultWidth.coerceAtMost(usable.width.dp), HideoutDimens.Window.DefaultHeight.coerceAtMost(usable.height.dp))
}

internal fun firstWindowSize(): DpSize = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.let { firstWindowSize(it.bounds, it.insets) }

private val GraphicsConfiguration.insets: Insets get() = Toolkit.getDefaultToolkit().getScreenInsets(this)
