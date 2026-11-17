package gg.hideouthq.window

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Window

private val Grip = 6.dp

private enum class Edge(val cursor: Int, val left: Boolean, val top: Boolean, val right: Boolean, val bottom: Boolean) {
    N(Cursor.N_RESIZE_CURSOR, false, true, false, false),
    S(Cursor.S_RESIZE_CURSOR, false, false, false, true),
    W(Cursor.W_RESIZE_CURSOR, true, false, false, false),
    E(Cursor.E_RESIZE_CURSOR, false, false, true, false),
    NW(Cursor.NW_RESIZE_CURSOR, true, true, false, false),
    NE(Cursor.NE_RESIZE_CURSOR, false, true, true, false),
    SW(Cursor.SW_RESIZE_CURSOR, true, false, false, true),
    SE(Cursor.SE_RESIZE_CURSOR, false, false, true, true),
}

/** An undecorated window loses the system's resize borders, so these resize it by hand. */
@Composable
internal fun BoxScope.WindowResizeGrips(window: Window, enabled: Boolean) {
    if (!enabled) return
    Grip(window, Edge.N, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(Grip).padding(horizontal = Grip))
    Grip(window, Edge.S, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(Grip).padding(horizontal = Grip))
    Grip(window, Edge.W, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(Grip).padding(vertical = Grip))
    Grip(window, Edge.E, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(Grip).padding(vertical = Grip))
    Grip(window, Edge.NW, Modifier.align(Alignment.TopStart).size(Grip * 2))
    Grip(window, Edge.NE, Modifier.align(Alignment.TopEnd).size(Grip * 2))
    Grip(window, Edge.SW, Modifier.align(Alignment.BottomStart).size(Grip * 2))
    Grip(window, Edge.SE, Modifier.align(Alignment.BottomEnd).size(Grip * 2))
}

@Composable
private fun Grip(window: Window, edge: Edge, modifier: Modifier) {
    Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor(edge.cursor)))
            .pointerInput(window, edge) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = MouseInfo.getPointerInfo().location
                    val bounds = Rectangle(window.bounds)
                    val min = window.minimumSize
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val pointer = MouseInfo.getPointerInfo().location
                        val dx = pointer.x - start.x
                        val dy = pointer.y - start.y
                        var x = bounds.x
                        var y = bounds.y
                        var w = bounds.width
                        var h = bounds.height
                        if (edge.right) w = maxOf(min.width, bounds.width + dx)
                        if (edge.bottom) h = maxOf(min.height, bounds.height + dy)
                        if (edge.left) {
                            w = maxOf(min.width, bounds.width - dx)
                            x = bounds.x + bounds.width - w
                        }
                        if (edge.top) {
                            h = maxOf(min.height, bounds.height - dy)
                            y = bounds.y + bounds.height - h
                        }
                        window.setBounds(x, y, w, h)
                        change.consume()
                    }
                }
            },
    )
}

