package gg.hideouthq.feature.install

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DialogFrameTest {
    private val window = IntSize(1440, 900)
    private val content = IntSize(560, 522)
    private val button = Rect(304f, 290f, 424f, 338f)
    private val settled = IntRect(440, 189, 1000, 711)

    @Test
    fun aTravellingSurfaceStartsExactlyAtTheButtonAndSettlesCentredWhileTheContentStaysPut() {
        val start = dialogFrame(window, content, button, progress = 0f, reduced = false)
        assertEquals(IntRect(304, 290, 424, 338), start.now)
        assertEquals(settled, start.target, "the content lies where the dialog settles from the first frame")

        val halfway = dialogFrame(window, content, button, progress = 0.5f, reduced = false)
        assertEquals(IntRect(372, 240, 712, 525), halfway.now)
        assertEquals(settled, halfway.target)

        assertEquals(settled, dialogFrame(window, content, button, progress = 1f, reduced = false).now)
    }

    @Test
    fun withoutAButtonTheSurfaceGrowsFromALittleSmallerAroundItsOwnCentre() {
        val start = dialogFrame(window, content, from = null, progress = 0f, reduced = false).now
        assertTrue(abs(start.center.x - settled.center.x) <= 1 && abs(start.center.y - settled.center.y) <= 1, "centred, got $start")
        assertEquals(538, start.width, "96% of 560")
        assertEquals(settled, dialogFrame(window, content, from = null, progress = 1f, reduced = false).now)
    }

    @Test
    fun withReducedMotionTheSurfaceNeverMoves() {
        listOf(0f, 0.5f, 1f).forEach { progress ->
            assertEquals(settled, dialogFrame(window, content, button, progress, reduced = true).now)
        }
    }

    @Test
    fun anOvershootingSpringNeverTakesTheSurfaceOutsideTheWindow() {
        val compact = IntSize(1000, 680)
        val tall = IntSize(560, 632)
        val corner = Rect(940f, 620f, 1000f, 680f)
        val frame = dialogFrame(compact, tall, corner, progress = 1.4f, reduced = false)
        assertEquals(IntRect(220, 24, 780, 656), frame.target)
        assertTrue(frame.now.left >= 0 && frame.now.top >= 0 && frame.now.right <= 1000 && frame.now.bottom <= 680, "got ${frame.now}")
    }
}
