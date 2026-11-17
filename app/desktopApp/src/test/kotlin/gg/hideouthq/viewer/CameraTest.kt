package gg.hideouthq.viewer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CameraTest {
    private val start = Camera(azimuthDegrees = -100f)

    @Test
    fun aDragOrbitsBothWaysPerDpAndPitchStopsAtItsBounds() {
        val turned = start.orbit(right = 10f, down = 0f)
        assertEquals(start.azimuthDegrees - 10 * Camera.DegreesPerDp, turned.azimuthDegrees)
        assertEquals(start.pitchDegrees, turned.pitchDegrees)
        val raised = start.orbit(right = 0f, down = 10f)
        assertEquals(start.azimuthDegrees, raised.azimuthDegrees)
        assertEquals(start.pitchDegrees + 10 * Camera.DegreesPerDp, raised.pitchDegrees)
        assertEquals(Camera.MaxPitchDegrees, start.orbit(0f, 10_000f).pitchDegrees)
        assertEquals(Camera.MinPitchDegrees, start.orbit(0f, -10_000f).pitchDegrees)
        assertEquals(start.copy(azimuthDegrees = turned.azimuthDegrees, pitchDegrees = raised.pitchDegrees), start.orbit(10f, 10f))
    }

    @Test
    fun orbitKeepsTheModelAnchoredAtEveryZoom() {
        for (zoom in listOf(Camera.MinZoom, 1.5f, Camera.MaxZoom)) {
            val turned = start.copy(zoom = zoom).orbit(38f, 19f)
            assertEquals(0f, turned.panX)
            assertEquals(0f, turned.panY)
            assertEquals(zoom, turned.zoom)
        }
    }

    @Test
    fun defaultFramingIsTheZoomOutLimit() {
        for (steps in listOf(0.5f, 1f, 1_000f)) {
            assertEquals(start, start.zoomed(steps))
            for (instant in listOf(false, true)) {
                val view = ViewCamera(start)
                view.at(FrameNanos, instant)
                view.scroll(steps, 0.4f, -0.3f)
                (2L..40L).forEach { assertEquals(start, view.at(it * FrameNanos, instant)) }
            }
        }
    }

    @Test
    fun wheelStepsAreAConstantRatioThatComposesAndStopsAtTheBounds() {
        val close = start.copy(zoom = 2f)
        val out = close.zoomed(1f)
        assertTrue(out.zoom < close.zoom)
        assertTrue(abs(close.zoomed(2f).zoom - out.zoom * out.zoom / close.zoom) < 1e-6f)
        assertTrue(abs(close.zoomed(0.5f).zoomed(0.5f).zoom - out.zoom) < 1e-6f)
        assertTrue(abs(out.zoomed(-1f).zoom - close.zoom) < 1e-6f)
        assertEquals(Camera.MaxZoom, start.zoomed(-1_000f).zoom)
        assertEquals(start.zoom, close.zoomed(1_000f).zoom)
        assertEquals(start.copy(zoom = out.zoom), out)
    }

    @Test
    fun centerScrollEasesMonotonicallyAndLandsOnItsGoalExactly() {
        val initial = start.copy(zoom = 1.5f)
        for (steps in listOf(-1f, 1f)) {
            val view = ViewCamera(initial)
            view.at(FrameNanos, instant = false)
            view.scroll(steps, 0f, 0f)
            assertEquals(initial, view.at(FrameNanos, instant = false), "No frame time moves nothing")
            val path = (2L..40L).map { view.at(it * FrameNanos, instant = false) }
            assertTrue(path.zipWithNext().all { (a, b) -> (b.zoom - a.zoom) * steps <= 0f }, "$path")
            assertTrue(path.map { it.zoom }.distinct().size > 3, "$path")
            assertTrue(path.all { it.panX == 0f && it.panY == 0f })
            assertEquals(initial.zoomed(steps), path.last())
        }
    }

    @Test
    fun zoomingInKeepsThePointerAnchorThroughoutEasing() {
        for (steps in listOf(-5f, -2f)) {
            val view = ViewCamera(start)
            view.at(FrameNanos, instant = false)
            view.scroll(steps, 0.35f, -0.2f)
            assertEquals(start, view.at(FrameNanos, instant = false))
            val path = (2L..40L).map { view.at(it * FrameNanos, instant = false) }
            assertTrue(path.map { it.zoom }.distinct().size > 3)
            path.forEach { assertAnchor(start, it, 0.35f, -0.2f) }
            assertEquals(view.goal, path.last())
        }
    }

    @Test
    fun rapidScrollAccumulatesStepsButRetargetsFromTheDisplayedCamera() {
        val view = ViewCamera(start)
        view.at(FrameNanos, instant = false)
        view.scroll(-3f, 0.35f, -0.2f)
        val displayed = view.at(2 * FrameNanos, instant = false)
        view.scroll(-2f, -0.4f, 0.25f)
        assertEquals(start.zoomed(-5f).zoom, view.goal.zoom, 1e-6f)
        assertEquals(displayed, view.at(2 * FrameNanos, instant = false), "Retargeting must not jump")
        (3L..40L).forEach { assertAnchor(displayed, view.at(it * FrameNanos, instant = false), -0.4f, 0.25f) }
    }

    @Test
    fun reversingScrollRecentersWithoutJumpingEvenIfThePointerMoves() {
        val view = ViewCamera(start)
        view.at(FrameNanos, instant = false)
        view.scroll(-5f, 0.35f, -0.2f)
        val displayed = view.at(2 * FrameNanos, instant = false)
        view.scroll(7f, -0.25f, 0.3f)
        assertEquals(start.zoomed(2f).zoom, view.goal.zoom, 1e-6f)
        assertEquals(displayed, view.at(2 * FrameNanos, instant = false))
        var previous = displayed
        (3L..40L).forEach {
            val frame = view.at(it * FrameNanos, instant = false)
            assertTrue(abs(frame.panX) <= abs(previous.panX) && abs(frame.panY) <= abs(previous.panY))
            if (frame.zoom <= 1f) {
                assertEquals(0f, frame.panX, 1e-6f)
                assertEquals(0f, frame.panY, 1e-6f)
            }
            previous = frame
        }
        assertEquals(0f, previous.panX)
        assertEquals(0f, previous.panY)
    }

    @Test
    fun zoomingOutPastDefaultRecentersWithoutUndershootingOrAccumulatingScroll() {
        for (instant in listOf(false, true)) {
            val view = ViewCamera(start)
            view.scroll(-7f, 0.4f, -0.3f)
            val close = view.at(FrameNanos, instant = true)
            view.scroll(1_000f, -0.4f, 0.3f)
            assertEquals(start, view.goal)
            val path = (2L..40L).map { view.at(it * FrameNanos, instant) }
            assertTrue(path.all { it.zoom in start.zoom..close.zoom })
            assertTrue(path.zipWithNext().all { (a, b) -> b.zoom <= a.zoom })
            assertEquals(start, path.last())
            view.scroll(1_000f, -0.2f, -0.3f)
            assertEquals(start, view.at(41 * FrameNanos, instant))
            view.scroll(-1f, 0.4f, -0.3f)
            assertEquals(start.zoomed(-1f).zoom, view.goal.zoom)
            assertAnchor(start, view.at(42 * FrameNanos, instant = true), 0.4f, -0.3f)
        }
    }

    @Test
    fun extraScrollAtEitherLimitDoesNotRetargetOffsetsEvenWhileEasing() {
        for (steps in listOf(-1_000f, 1_000f)) {
            val view = ViewCamera(start.copy(zoom = 1.5f))
            val control = ViewCamera(start.copy(zoom = 1.5f))
            for (camera in listOf(view, control)) {
                camera.at(FrameNanos, instant = false)
                camera.scroll(steps, 0.35f, -0.2f)
                camera.at(2 * FrameNanos, instant = false)
            }
            val goal = view.goal
            view.scroll(steps, -0.4f, 0.3f)
            assertEquals(goal, view.goal)
            (3L..40L).forEach {
                assertEquals(control.at(it * FrameNanos, instant = false), view.at(it * FrameNanos, instant = false))
            }
            view.scroll(steps, -0.4f, 0.3f)
            assertEquals(goal, view.at(41 * FrameNanos, instant = false))
        }
    }

    @Test
    fun centerScrollPreservesExistingOffsetsAndDragOnlyRotates() {
        val view = ViewCamera(start)
        view.scroll(-4f, 0.35f, -0.2f)
        val anchored = view.at(FrameNanos, instant = true)
        view.scroll(-1f, 0f, 0f)
        view.goal = view.goal.orbit(38f, 19f)
        val rotated = view.at(2 * FrameNanos, instant = false)
        assertEquals(anchored.panX, rotated.panX)
        assertEquals(anchored.panY, rotated.panY)
        assertEquals(start.orbit(38f, 19f).azimuthDegrees, rotated.azimuthDegrees)
        assertEquals(start.orbit(38f, 19f).pitchDegrees, rotated.pitchDegrees)
        assertEquals(CameraFraming.Reference(start.azimuthDegrees, start.pitchDegrees), view.framing)
    }

    private fun assertAnchor(before: Camera, after: Camera, x: Float, y: Float) {
        val pointX = x / before.zoom - before.panX
        val pointY = y / before.zoom - before.panY
        assertEquals(x, (pointX + after.panX) * after.zoom, 1e-6f, "Horizontal pointer anchor")
        assertEquals(y, (pointY + after.panY) * after.zoom, 1e-6f, "Vertical pointer anchor")
    }

    private companion object {
        const val FrameNanos = 16_000_000L
    }
}
