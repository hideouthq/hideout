package gg.hideouthq.moddetail

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import gg.hideouthq.feature.moddetail.viewer.PlaybackClock
import gg.hideouthq.feature.moddetail.viewer.rememberSampleClock
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.awt.EventQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleClockTest {
    @Test
    fun startingAndResumingDoNotCountTimeSpentPaused() {
        var result: Result<Unit>? = null
        EventQueue.invokeAndWait { result = runCatching {
            val position = mutableIntStateOf(0)
            val playing = mutableStateOf(false)
            lateinit var clock: PlaybackClock
            val scene = ImageComposeScene(64, 64, Density(1f), coroutineContext = Dispatchers.Unconfined) {
                clock = rememberSampleClock({ position.intValue }, 1_000, playing.value)
            }
            try {
                var time = 0L
                fun frames(count: Int) = repeat(count) {
                    time += 16_000_000
                    scene.render(time).close()
                }
                frames(100)
                assertEquals(0f, clock.elapsedMillis)
                playing.value = true
                frames(2)
                assertTrue(clock.elapsedMillis in 0f..32f, "Starting must not count the 1600 ms spent idle, got ${clock.elapsedMillis}")
                position.intValue = 250
                frames(2)
                playing.value = false
                frames(100)
                val paused = clock.elapsedMillis
                playing.value = true
                frames(2)
                assertTrue(clock.elapsedMillis in 250f..282f, "Resuming must not count paused frames, got ${clock.elapsedMillis} after $paused")
            } finally {
                scene.close()
            }
        } }
        checkNotNull(result).getOrThrow()
    }
}
