package gg.hideouthq.window

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.input.pointer.PointerButton
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

class MouseSideButtons(private val window: ComposeWindow, onPress: (PointerButton) -> Unit) : AutoCloseable {
    private val x11 = Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.X11.XToolkit"
    private val listener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            sideButton(event.button, x11)?.let(onPress)
        }
    }

    init {
        window.addMouseListener(listener)
    }

    override fun close() = window.removeMouseListener(listener)
}

/**
 * Windows and macOS number Back and Forward 4 and 5, as Compose reads them, but AWT's X11 toolkit gives 4 and 5 to the
 * horizontal scroll buttons (X11's 6 and 7), so there Back and Forward (X11's 8 and 9) arrive as 6 and 7.
 */
internal fun sideButton(awtButton: Int, x11: Boolean): PointerButton? {
    val back = if (x11) 6 else 4
    return when (awtButton) {
        back -> PointerButton.Back
        back + 1 -> PointerButton.Forward
        else -> null
    }
}
