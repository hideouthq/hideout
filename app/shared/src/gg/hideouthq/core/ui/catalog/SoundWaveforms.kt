package gg.hideouthq.core.ui.catalog

import kotlin.math.PI
import kotlin.math.sin

/**
 * Waveforms for sound mods, to draw before (or instead of) their real audio. Each is made up from
 * the mod's id, so a mod always looks the same, but they rise and fall like speech or music:
 * random heights, smoothed, swelling in the middle and tailing off at the end.
 */
object SoundWaveforms {
    const val RowBars = 16
    const val CoverBars = 28
    const val StageBars = 72

    private const val Floor = 0.16f
    private const val Swell = 0.35f

    /** [bars] heights in 0..1 for [seed]. */
    fun of(seed: String, bars: Int): List<Float> {
        val random = SeededRandom(seed)
        val raw = List(bars) { random.next() }
        return List(bars) { index ->
            val smoothed = (raw.getOrElse(index - 1) { raw[index] } + 2 * raw[index] + raw.getOrElse(index + 1) { raw[index] }) / 4
            val position = if (bars > 1) index.toFloat() / (bars - 1) else 0f
            val envelope = 1f - Swell + Swell * sin(PI * position).toFloat()
            (Floor + (1f - Floor) * smoothed * envelope).coerceIn(Floor, 1f)
        }
    }

    private class SeededRandom(seed: String) {
        private var state = seed.fold(FnvOffset) { hash, char -> (hash xor char.code.toLong()) * FnvPrime }.let { if (it == 0L) 1L else it }

        fun next(): Float {
            state = state xor (state shl 13)
            state = state xor (state ushr 7)
            state = state xor (state shl 17)
            return ((state ushr 40) and 0xFFFFFF).toFloat() / 0xFFFFFF
        }

        private companion object {
            const val FnvOffset = -0x340d631b7bdddcdbL
            const val FnvPrime = 0x100000001b3L
        }
    }
}
