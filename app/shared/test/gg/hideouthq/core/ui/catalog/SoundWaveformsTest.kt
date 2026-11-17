package gg.hideouthq.core.ui.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SoundWaveformsTest {
    @Test
    fun aModAlwaysGetsTheSameVariedWaveform() {
        val bars = SoundWaveforms.of("sound-77726", SoundWaveforms.StageBars)
        assertEquals(bars, SoundWaveforms.of("sound-77726", SoundWaveforms.StageBars))
        assertNotEquals(bars, SoundWaveforms.of("sound-77727", SoundWaveforms.StageBars))
        assertEquals(SoundWaveforms.StageBars, bars.size)
        assertTrue(bars.all { it in 0.16f..1f })
        assertTrue(bars.max() - bars.min() > 0.3f, "Bars should rise and fall, not sit flat")
    }
}
