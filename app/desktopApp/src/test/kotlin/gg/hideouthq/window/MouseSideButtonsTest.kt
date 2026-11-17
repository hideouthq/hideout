package gg.hideouthq.window

import androidx.compose.ui.input.pointer.PointerButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MouseSideButtonsTest {
    @Test
    fun onX11BackAndForwardArriveAsSixAndSevenAndHorizontalScrollIsNoSideButton() {
        assertEquals(PointerButton.Back, sideButton(6, x11 = true))
        assertEquals(PointerButton.Forward, sideButton(7, x11 = true))
        (1..5).forEach { assertNull(sideButton(it, x11 = true), "AWT button $it on X11") }
    }

    @Test
    fun onWindowsAndMacOsBackAndForwardAreFourAndFive() {
        assertEquals(PointerButton.Back, sideButton(4, x11 = false))
        assertEquals(PointerButton.Forward, sideButton(5, x11 = false))
        listOf(1, 2, 3, 6, 7).forEach { assertNull(sideButton(it, x11 = false), "AWT button $it") }
    }
}
