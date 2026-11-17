package gg.hideouthq.core.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Plays mods' short audio previews, one at a time: starting another stops the first. The desktop
 * app plays the real audio; elsewhere the player only keeps time.
 */
interface SoundSamplePlayer {
    /** The preview loaded now, playing or paused, or null. */
    val nowPlaying: StateFlow<PlayingSample?>

    /** Plays [preview] from its start, stopping whatever else plays. */
    fun play(preview: SamplePreview)

    fun pause()

    fun resume()

    /** Jumps to [fraction] (0..1) of the current preview. */
    fun seekTo(fraction: Float)

    /** Whether the current preview starts over when it ends, rather than stops. */
    fun setLooping(looping: Boolean)

    fun stop()
}

/** A mod's audio preview: where to fetch it, and whether it loops. */
data class SamplePreview(val modId: String, val url: String?, val looping: Boolean = false)

/** The preview loaded now, [positionMillis] in. [lengthMillis] is null while it is still loading. */
data class PlayingSample(
    val modId: String,
    val positionMillis: Int,
    val lengthMillis: Int?,
    val paused: Boolean = false,
    val looping: Boolean = false,
) {
    val elapsedSeconds: Int get() = positionMillis / MillisPerSecond

    val loading: Boolean get() = lengthMillis == null

    /** How far in, 0..1. */
    val progress: Float get() = lengthMillis?.takeIf { it > 0 }?.let { (positionMillis.toFloat() / it).coerceIn(0f, 1f) } ?: 0f

    private companion object {
        const val MillisPerSecond = 1_000
    }
}
