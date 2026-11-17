package gg.hideouthq.data

import gg.hideouthq.core.data.PlayingSample
import gg.hideouthq.core.data.SamplePreview
import gg.hideouthq.core.data.SoundSamplePlayer
import fr.delthas.javamp3.Sound
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.IOException
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

class JavaSoundSamplePlayer internal constructor(
    private val client: HttpClient,
    private val scope: CoroutineScope,
    private val openLine: (AudioFormat) -> SourceDataLine,
) : SoundSamplePlayer {
    constructor(
        client: HttpClient,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    ) : this(client, scope, { format -> AudioSystem.getSourceDataLine(format).apply { open(format) } })

    private val state = MutableStateFlow<PlayingSample?>(null)
    private val decoded = object : LinkedHashMap<String, Pcm>(DecodedKept, LoadFactor, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pcm>) = size > DecodedKept
    }
    private val lock = Any()
    private var request: Request? = null

    override val nowPlaying: StateFlow<PlayingSample?> = state.asStateFlow()

    override fun play(preview: SamplePreview) {
        val url = preview.url ?: return
        synchronized(lock) {
            request?.job?.cancel()
            val next = Request(preview.looping)
            request = next
            state.value = PlayingSample(preview.modId, positionMillis = 0, lengthMillis = null, looping = preview.looping)
            next.job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val pcm = synchronized(decoded) { decoded[url] } ?: decode(fetch(url)).also { synchronized(decoded) { decoded[url] = it } }
                    currentCoroutineContext().ensureActive()
                    play(next, pcm)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                } finally {
                    update(next) { null }
                }
            }
            next.job.start()
        }
    }

    override fun pause() = synchronized(lock) {
        request?.paused = true
        state.value = state.value?.copy(paused = true)
    }

    override fun resume() = synchronized(lock) {
        request?.paused = false
        state.value = state.value?.copy(paused = false)
    }

    override fun seekTo(fraction: Float) = synchronized(lock) {
        val clamped = fraction.coerceIn(0f, 1f)
        request?.seekRequest = clamped
        state.value = state.value?.let { it.copy(positionMillis = ((it.lengthMillis ?: 0) * clamped).toInt()) }
    }

    override fun setLooping(looping: Boolean) = synchronized(lock) {
        request?.looping = looping
        state.value = state.value?.copy(looping = looping)
    }

    override fun stop() = synchronized(lock) {
        request?.job?.cancel()
        request = null
        state.value = null
    }

    private fun update(owner: Request, change: (PlayingSample?) -> PlayingSample?) = synchronized(lock) {
        if (request === owner) state.value = change(state.value)
    }

    private suspend fun fetch(url: String): ByteArray {
        val response = client.get(url)
        if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value} for $url")
        return response.readRawBytes()
    }

    private fun decode(mp3: ByteArray): Pcm = Sound(ByteArrayInputStream(mp3)).use { sound ->
        val format = sound.audioFormat
        val bytes = sound.readAllBytes()
        attenuatePreview(bytes, format)
        Pcm(bytes, format)
    }

    private suspend fun play(owner: Request, pcm: Pcm) {
        val line = openLine(pcm.format)
        try {
            currentCoroutineContext().ensureActive()
            val frame = pcm.format.frameSize
            var offset = 0
            var lastReport = 0L
            update(owner) { it?.copy(lengthMillis = pcm.millisAt(pcm.bytes.size)) }
            while (true) {
                currentCoroutineContext().ensureActive()
                val seek = synchronized(lock) { owner.seekRequest.also { owner.seekRequest = null } }
                seek?.let { fraction ->
                    line.flush()
                    offset = (pcm.bytes.size * fraction).toInt() / frame * frame
                }
                if (owner.paused) {
                    if (line.isRunning) line.stop()
                    delay(PausePollMillis)
                    continue
                }
                if (!line.isRunning) line.start()
                if (offset >= pcm.bytes.size) {
                    if (!owner.looping) break
                    offset = 0
                }
                val count = minOf(ChunkBytes / frame * frame, pcm.bytes.size - offset)
                line.write(pcm.bytes, offset, count)
                offset += count
                val now = System.currentTimeMillis()
                if (now - lastReport >= ReportMillis) {
                    lastReport = now
                    val queued = line.bufferSize - line.available()
                    update(owner) { it?.copy(positionMillis = pcm.millisAt((offset - queued).coerceAtLeast(0))) }
                }
            }
            line.drain()
        } finally {
            line.close()
        }
    }

    private class Request(@Volatile var looping: Boolean) {
        lateinit var job: Job
        @Volatile var paused = false
        var seekRequest: Float? = null
    }

    private class Pcm(val bytes: ByteArray, val format: AudioFormat) {
        fun millisAt(byteOffset: Int): Int = (byteOffset.toLong() / format.frameSize * MillisPerSecond / format.frameRate.toLong()).toInt()
    }

    private companion object {
        const val DecodedKept = 4
        const val LoadFactor = 0.75f
        const val ChunkBytes = 4_096
        const val ReportMillis = 80L
        const val PausePollMillis = 40L
        const val MillisPerSecond = 1_000L
    }
}

/** JavaMP3 emits signed 16-bit PCM. Scale samples before caching, independent of mixer gain support. */
internal fun attenuatePreview(bytes: ByteArray, format: AudioFormat) {
    for (offset in bytes.indices step 2) {
        val low = offset + if (format.isBigEndian) 1 else 0
        val high = offset + if (format.isBigEndian) 0 else 1
        val sample = ((bytes[high].toInt() shl 8) or (bytes[low].toInt() and 0xff)).toShort()
        val quiet = (sample * 0.35f).toInt()
        bytes[low] = quiet.toByte()
        bytes[high] = (quiet shr 8).toByte()
    }
}
