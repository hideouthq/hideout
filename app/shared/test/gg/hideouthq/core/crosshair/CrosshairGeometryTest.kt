package gg.hideouthq.core.crosshair

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrosshairGeometryTest {
    private fun box(left: Int, top: Int, right: Int, bottom: Int) = Box(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

    @Test
    fun theGamesDefaultAtActualSizeOn1080p() {
        val shapes = CrosshairGeometry.shapes(Crosshair.GameDefault, pxPerUnit = 1f)
        assertEquals(listOf(box(-1, -16, 1, 0), box(-1, 0, 1, 16), box(-16, -1, 0, 1), box(0, -1, 16, 1)), shapes.lines, "each line's centre is 4 + gap = 8 from the centre")
        assertEquals(Frame(box(-2, -17, 2, 1), box(-1, -16, 1, 0)), shapes.lineOutlines.first(), "2 + 2 by 16 + 2, its border inside, its leading edge rounded half up")
        assertEquals(4, shapes.lineOutlines.size)
        assertEquals(box(-2, -2, 2, 2), shapes.dot)
        assertEquals(Frame(box(-4, -4, 4, 4), box(-2, -2, 2, 2)), shapes.dotOutline, "4 + 2 × 2 across, a 2 wide ring")
        assertEquals(listOf(0.5f, 0.7f, 0.7f, 0.7f), listOf(shapes.lineOpacity, shapes.lineOutlineOpacity, shapes.dotOpacity, shapes.dotOutlineOpacity))
    }

    @Test
    fun zoomingComposesSizesFirstAndSnapsOnce() {
        val three = CrosshairGeometry.shapes(Crosshair.GameDefault, pxPerUnit = 3f)
        assertEquals(box(-3, -48, 3, 0), three.lines.first())
        assertEquals(Frame(box(-6, -51, 6, 3), box(-3, -48, 3, 0)), three.lineOutlines.first())
        assertEquals(box(-6, -96, 6, 0), CrosshairGeometry.shapes(Crosshair.GameDefault, pxPerUnit = 6f).lines.first())

        val thin = Crosshair.GameDefault.with(thickness = 1.0, length = 6.0, gap = 0.0)
        val sharp = CrosshairGeometry.shapes(thin, pxPerUnit = 4f / 3)
        assertEquals(box(0, -9, 1, -1), sharp.lines.first(), "at 1440p a one unit line is one pixel")
        val bare = CrosshairGeometry.shapes(thin.with(thickness = 0.0), pxPerUnit = 4f / 3)
        val outline = bare.lineOutlines.first().outer
        assertEquals(3f, outline.right - outline.left, "a 2 unit outline box at 1440p is 3 pixels, not 1 + 1")
    }

    @Test
    fun sizesAndTheGapAreCutToWholeUnitsTowardZero() {
        val cut = Crosshair.GameDefault.with(thickness = 2.9, length = 7.7, gap = -2.6)
        assertEquals(box(-1, -5, 1, 2), CrosshairGeometry.shapes(cut, pxPerUnit = 1f).lines.first(), "2 by 7, centred 4 - 2 from the centre")
    }

    @Test
    fun aLayerTheGameDoesNotDrawIsEmpty() {
        val dot = CrosshairGeometry.shapes(CrosshairPreset.Dot.crosshair, pxPerUnit = 2f)
        assertEquals(emptyList(), dot.lines)
        assertEquals(emptyList(), dot.lineOutlines)
        assertEquals(box(-4, -4, 4, 4), dot.dot)

        val classic = CrosshairGeometry.shapes(CrosshairPreset.Classic.crosshair, pxPerUnit = 1f)
        assertNull(classic.dot)
        assertNull(classic.dotOutline)
        val unseen = CrosshairGeometry.shapes(Crosshair.GameDefault.with(opacity = 0.0), pxPerUnit = 1f)
        assertEquals(emptyList(), unseen.lines)
        assertEquals(4, unseen.lineOutlines.size, "the outline has its own opacity, so it shows without the lines")
    }

    @Test
    fun classicAndWideGapAreOpenCrossesWhoseLinesNeverMeet() {
        for (preset in listOf(CrosshairPreset.Classic, CrosshairPreset.WideGap)) {
            val (top, bottom, left, right) = CrosshairGeometry.shapes(preset.crosshair, pxPerUnit = 1f).lines
            assertTrue(top.bottom < left.top && bottom.top > left.bottom && left.right < top.left && right.left > top.right, "${preset.title}'s lines stop short of the centre")
        }
    }
}
