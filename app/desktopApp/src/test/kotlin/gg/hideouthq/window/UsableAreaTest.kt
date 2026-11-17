package gg.hideouthq.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class UsableAreaTest {
    @Test
    fun aMaximizedWindowLeavesTheTaskbarOfItsOwnScreenFree() {
        assertEquals(Rectangle(0, 0, 1920, 1032), maximizedArea(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 48, 0)))
        assertEquals(Rectangle(1984, 0, 2496, 1440), maximizedArea(Rectangle(1920, 0, 2560, 1440), Insets(0, 64, 0, 0)))
        assertEquals(Rectangle(-1280, 40, 1280, 984), maximizedArea(Rectangle(-1280, 0, 1280, 1024), Insets(40, 0, 0, 0)))
    }

    @Test
    fun aMaximizedWindowStopsOnePixelShortOfAScreenWhoseTaskbarHidesItself() {
        assertEquals(Rectangle(0, 0, 1920, 1079), maximizedArea(Rectangle(0, 0, 1920, 1080), Insets(0, 0, 0, 0)))
        assertEquals(Rectangle(1920, 0, 2560, 1439), maximizedArea(Rectangle(1920, 0, 2560, 1440), Insets(0, 0, 0, 0)))
    }

    @Test
    fun theFirstWindowKeepsItsDefaultSizeWhereItFitsAndTheUsableAreaWhereItDoesNot() {
        assertEquals(DpSize(1440.dp, 900.dp), firstWindowSize(Rectangle(0, 0, 3440, 1440), Insets(0, 0, 30, 0)))
        assertEquals(DpSize(1440.dp, 900.dp), firstWindowSize(Rectangle(0, 0, 1440, 948), Insets(0, 0, 48, 0)), "exactly the default")
        assertEquals(DpSize(1280.dp, 752.dp), firstWindowSize(Rectangle(0, 0, 1280, 800), Insets(0, 0, 48, 0)), "Windows 11 at 1280x800")
        assertEquals(DpSize(1280.dp, 756.dp), firstWindowSize(Rectangle(1920, 0, 1280, 800), Insets(0, 0, 44, 0)), "a 1280x800 desktop beside another screen")
        assertEquals(DpSize(1366.dp, 728.dp), firstWindowSize(Rectangle(0, 0, 1366, 768), Insets(0, 0, 40, 0)), "a 1366x768 laptop")
        assertEquals(DpSize(1440.dp, 864.dp), firstWindowSize(Rectangle(0, 0, 2560, 1440), Insets(0, 0, 576, 0)), "only the height is held")
    }
}
