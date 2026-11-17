package gg.hideouthq.core.designsystem.component

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals

class BesideAnchorTest {
    private val window = IntSize(1440, 900)
    private val tooltip = IntSize(400, 32)

    @Test
    fun opensAfterItsAnchorWhereItFits() {
        val anchor = IntRect(20, 100, 240, 140)
        assertEquals(IntOffset(254, 104), BesideAnchor(14).calculatePosition(anchor, window, LayoutDirection.Ltr, tooltip))
    }

    @Test
    fun opensBeforeItsAnchorAtTheWindowsEdgeRatherThanCoverIt() {
        val anchor = IntRect(1050, 670, 1400, 714)
        assertEquals(IntOffset(636, 676), BesideAnchor(14).calculatePosition(anchor, window, LayoutDirection.Ltr, tooltip))
    }
}
