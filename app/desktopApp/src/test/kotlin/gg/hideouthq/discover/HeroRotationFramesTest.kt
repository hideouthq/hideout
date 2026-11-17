package gg.hideouthq.discover

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import gg.hideouthq.feature.discover.components.HeroRotation
import gg.hideouthq.feature.discover.components.rememberHeroRotation
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeroRotationFramesTest {
    @Test
    fun aPausedRotationAsksForNoFramesAndCarriesOnWhenResumed() {
        lateinit var rotation: HeroRotation
        val scene = ImageComposeScene(100, 100, Density(1f)) {
            rotation = rememberHeroRotation(count = 3, current = 0, onAdvance = {})
        }
        try {
            var time = 0L
            fun settle() = repeat(5) { time += 16; scene.render(time * 1_000_000).close(); Thread.sleep(30) }
            settle()
            rotation.userPaused = true
            settle()
            assertFalse(scene.hasInvalidations(), "a paused clock keeps the window redrawing")
            val held = rotation.progress
            rotation.userPaused = false
            repeat(4) { settle() }
            assertTrue(rotation.progress > held, "the clock runs again after resuming")
        } finally {
            scene.close()
        }
    }
}
