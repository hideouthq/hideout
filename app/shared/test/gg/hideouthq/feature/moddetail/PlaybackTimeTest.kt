package gg.hideouthq.feature.moddetail

import gg.hideouthq.feature.moddetail.viewer.PlaybackStep
import gg.hideouthq.feature.moddetail.viewer.formatPlaybackTime
import gg.hideouthq.feature.moddetail.viewer.stepPlayback
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackTimeTest {
    @Test
    fun formatsLikeTheDesign() {
        assertEquals("0:01.2", formatPlaybackTime(1_234f))
        assertEquals("1:05.0", formatPlaybackTime(65_000f))
    }

    @Test
    fun theTimelineAdvancesByFrameTimeAtTheChosenSpeed() {
        assertEquals(PlaybackStep(108f, finished = false), stepPlayback(100f, 2_867f, 16f, speed = 0.5f, loop = true))
        assertEquals(PlaybackStep(132f, finished = false), stepPlayback(100f, 2_867f, 16f, speed = 2f, loop = false))
    }

    @Test
    fun aLoopingClipWrapsAndAnotherHoldsItsLastPoseAndFinishes() {
        assertEquals(PlaybackStep(13f, finished = false), stepPlayback(2_860f, 2_867f, 10f, speed = 2f, loop = true))
        assertEquals(PlaybackStep(2_867f, finished = true), stepPlayback(2_860f, 2_867f, 10f, speed = 2f, loop = false))
        assertEquals(PlaybackStep(0f, finished = false), stepPlayback(2_857f, 2_867f, 10f, speed = 1f, loop = true))
    }
}
