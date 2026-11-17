package gg.hideouthq.core.crosshair

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrosshairDrawsNothingTest {
    private val base = Crosshair.GameDefault

    private fun lines(thickness: Double) = base.copy(lines = base.lines.copy(thickness = ConvarNumber.of(thickness), border = base.lines.border.copy(opacity = ConvarNumber.Zero)))

    @Test
    fun zeroThicknessWithNoDotDrawsNothingAndHasNoShapes() {
        val crosshair = lines(0.0).copy(dot = base.dot.copy(opacity = ConvarNumber.Zero, border = base.dot.border.copy(opacity = ConvarNumber.Zero)))
        assertTrue(crosshair.drawsNothing)
        val shapes = CrosshairGeometry.shapes(crosshair, 1f)
        assertTrue(shapes.lines.isEmpty() && shapes.dot == null && shapes.lineOutlines.isEmpty() && shapes.dotOutline == null)
    }

    @Test
    fun aDotAloneStillDrawsSomething() {
        val crosshair = lines(0.0).copy(dot = base.dot.copy(size = ConvarNumber.of(4.0), opacity = ConvarNumber.of(1.0)))
        assertFalse(crosshair.drawsNothing)
    }

    @Test
    fun visibleLinesDrawSomething() {
        assertFalse(base.copy(lines = base.lines.copy(thickness = ConvarNumber.of(2.0), length = ConvarNumber.of(5.0), opacity = ConvarNumber.of(1.0))).drawsNothing)
    }
}
