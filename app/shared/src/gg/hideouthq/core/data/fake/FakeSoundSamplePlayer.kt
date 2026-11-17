package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.PlayingSample
import gg.hideouthq.core.data.SamplePreview
import gg.hideouthq.core.data.SoundSamplePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class FakeSoundSamplePlayer(private val scope: CoroutineScope = CoroutineScope(SupervisorJob())) : SoundSamplePlayer {
    private val playing = MutableStateFlow<PlayingSample?>(null)
    private var clock: Job? = null

    override val nowPlaying: StateFlow<PlayingSample?> = playing.asStateFlow()

    override fun play(preview: SamplePreview) {
        playing.value = PlayingSample(preview.modId, 0, DefaultLengthMillis, looping = preview.looping)
        keepTime()
    }

    override fun pause() {
        clock?.cancel()
        playing.update { it?.copy(paused = true) }
    }

    override fun resume() {
        if (playing.value?.paused != true) return
        playing.update { it?.copy(paused = false) }
        keepTime()
    }

    override fun seekTo(fraction: Float) {
        playing.update { sample -> sample?.copy(positionMillis = ((sample.lengthMillis ?: 0) * fraction.coerceIn(0f, 1f)).toInt()) }
    }

    override fun setLooping(looping: Boolean) {
        playing.update { it?.copy(looping = looping) }
    }

    override fun stop() {
        clock?.cancel()
        playing.value = null
    }

    private fun keepTime() {
        clock?.cancel()
        clock = scope.launch {
            while (true) {
                delay(TickMillis)
                val sample = playing.value ?: break
                val length = sample.lengthMillis ?: break
                val next = sample.positionMillis + TickMillis.toInt()
                playing.value = when {
                    next < length -> sample.copy(positionMillis = next)
                    sample.looping -> sample.copy(positionMillis = 0)
                    else -> null
                }
            }
        }
    }

    private companion object {
        const val TickMillis = 100L
        const val DefaultLengthMillis = 8_000
    }
}
