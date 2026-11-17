package gg.hideouthq.feature.discover.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroRotationTest {
    @Test
    fun ticksAccumulateAndAdvanceExactlyAtThePeriodKeepingAnyOverflow() {
        val rotation = HeroRotation(1_000L)
        assertFalse(rotation.tick(400))
        assertEquals(0.4f, rotation.progress, 0.001f)
        assertFalse(rotation.tick(400))
        assertEquals(0.8f, rotation.progress, 0.001f)
        assertTrue(rotation.tick(300))
        assertEquals(0.1f, rotation.progress, 0.001f)
    }

    @Test
    fun pausingHoldsProgressWithoutLosingIt() {
        val rotation = HeroRotation(1_000L)
        rotation.tick(400)
        rotation.hovered = true
        assertFalse(rotation.tick(500))
        assertEquals(0.4f, rotation.progress, 0.001f)
        rotation.hovered = false
        assertFalse(rotation.tick(100))
        assertEquals(0.5f, rotation.progress, 0.001f)
    }
}
