package gg.hideouthq.window

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point

private const val DoubleClickMillis = 400L

internal fun Modifier.windowDragArea(window: Frame, state: WindowState): Modifier = pointerInput(window, state) {
    var lastClick = 0L
    awaitEachGesture {
        val down = awaitFirstDown()
        val startPointer = MouseInfo.getPointerInfo().location
        var startWindow = window.location
        var dragged = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.positionChange() == Offset.Zero && !dragged) continue
            val pointer = MouseInfo.getPointerInfo().location
            if (!dragged) {
                dragged = true
                if (state.placement == WindowPlacement.Maximized) {
                    val fraction = (startPointer.x - window.x).toFloat() / window.width.coerceAtLeast(1)
                    state.placement = WindowPlacement.Floating
                    startWindow = Point((startPointer.x - window.width * fraction).toInt(), startPointer.y - down.position.y.toInt())
                }
            }
            window.setLocation(startWindow.x + pointer.x - startPointer.x, startWindow.y + pointer.y - startPointer.y)
            change.consume()
        }
        if (!dragged) {
            val now = System.currentTimeMillis()
            if (now - lastClick < DoubleClickMillis) {
                state.toggleMaximized(window)
                lastClick = 0L
            } else {
                lastClick = now
            }
        }
    }
}
