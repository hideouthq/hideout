package gg.hideouthq.viewer

import com.badlogic.gdx.math.Vector3
import com.badlogic.gdx.math.collision.BoundingBox
import kotlin.test.Test
import kotlin.test.assertContentEquals

class FramingBoundsTest {
    private fun box(x: Float, y: Float, z: Float, width: Float, height: Float, depth: Float) =
        BoundingBox(Vector3(x, y, z), Vector3(x + width, y + height, z + depth))

    @Test
    fun isolatedIslandsAreExcludedWithoutDroppingDetachedNearbyDetails() {
        val boxes = listOf(
            box(-0.3f, 0f, 0f, 0.6f, 2f, 0.3f),
            box(-0.2f, -2713f, 0f, 0.4f, 0.4f, 0.2f),
            box(-0.2f, 2.2f, 0f, 0.4f, 0.2f, 0.2f),
            box(0.2f, 1f, 0f, 8f, 0.4f, 0.2f),
        )
        val skeleton = box(0f, 0f, 0f, 0f, 2f, 0f)
        assertContentEquals(booleanArrayOf(true, false, true, true), framingComponents(boxes, skeleton))
        assertContentEquals(booleanArrayOf(true, true, false, true), framingComponents(boxes.reversed(), skeleton))
    }

    @Test
    fun changingModelUnitsDoesNotChangeTheSubject() {
        for (scale in listOf(0.001f, 1f, 1000f)) {
            val boxes = listOf(box(0f, 0f, 0f, 1f, 2f, 1f), box(0f, -2713f, 0f, 0.4f, 0.4f, 0.4f))
            boxes.forEach { it.set(Vector3(it.min).scl(scale), Vector3(it.max).scl(scale)) }
            assertContentEquals(booleanArrayOf(true, false), framingComponents(boxes, box(0f, 0f, 0f, 0f, 2f * scale, 0f)))
        }
    }

    @Test
    fun aLargerParkedObjectCannotReplaceTheBody() {
        val boxes = listOf(box(0f, 0f, 0f, 1f, 2f, 1f), box(0f, -2713f, 0f, 9f, 1f, 1f))
        assertContentEquals(booleanArrayOf(true, false), framingComponents(boxes, box(0f, 0f, 0f, 0f, 2f, 0f)))
    }

    @Test
    fun scenesWithoutASkeletonKeepAllTheirGeometry() {
        val boxes = listOf(box(0f, 0f, 0f, 1f, 2f, 1f), box(100f, 0f, 0f, 1f, 2f, 1f))
        assertContentEquals(booleanArrayOf(true, true), framingComponents(boxes, BoundingBox().inf()))
    }

    @Test
    fun aSkeletonWithNoNearbyGeometryDoesNotInventASubject() {
        val boxes = listOf(box(100f, 0f, 0f, 1f, 2f, 1f), box(150f, 0f, 0f, 1f, 2f, 1f))
        assertContentEquals(booleanArrayOf(true, true), framingComponents(boxes, box(0f, 0f, 0f, 0f, 2f, 0f)))
    }
}
