package gg.hideouthq.data

import gg.hideouthq.core.data.SamplePreview
import fr.delthas.javamp3.Sound
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class JavaSoundSamplePlayerTest {
    @Test
    fun signedSamplesAreScaledInBothByteOrders() {
        for (bigEndian in listOf(false, true)) {
            val format = AudioFormat(44_100f, 16, 1, true, bigEndian)
            val samples = listOf(-32768, -10000, 0, 10000, 32767)
            val bytes = samples.flatMap { sample ->
                val pair = listOf(sample.toByte(), (sample shr 8).toByte())
                if (bigEndian) pair.reversed() else pair
            }.toByteArray()
            attenuatePreview(bytes, format)
            val actual = bytes.toList().chunked(2).map { pair ->
                val ordered = if (bigEndian) pair.reversed() else pair
                ((ordered[1].toInt() shl 8) or (ordered[0].toInt() and 255)).toShort().toInt()
            }
            assertEquals(listOf(-11468, -3500, 0, 3500, 11468), actual)
        }
    }

    @Test
    fun decoderOutputIsQuietBeforeCachingAndNotAttenuatedAgain() = runTest {
        val mp3 = fixture()
        val original = Sound(ByteArrayInputStream(mp3)).use { sound ->
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, sound.audioFormat.encoding)
            assertEquals(16, sound.audioFormat.sampleSizeInBits)
            assertFalse(sound.audioFormat.isBigEndian)
            sound.readAllBytes()
        }
        var fetches = 0
        val output = ByteArrayOutputStream()
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { fetches++; respond(mp3) }
            }
        }
        try {
            val player = JavaSoundSamplePlayer(client, backgroundScope) { line(output) }
            player.play(SamplePreview("one", "https://fixture.invalid/one.mp3"))
            assertTrue(player.nowPlaying.value!!.loading)
            runCurrent()
            assertNull(player.nowPlaying.value)
            val first = output.toByteArray()
            assertEquals(original.size, first.size)
            assertTrue(first.any { it != 0.toByte() })
            for (offset in original.indices step 2) {
                fun sample(bytes: ByteArray) = ((bytes[offset + 1].toInt() shl 8) or (bytes[offset].toInt() and 255)).toShort().toInt()
                assertEquals((sample(original) * 0.35f).toInt(), sample(first))
            }
            output.reset()
            player.play(SamplePreview("one", "https://fixture.invalid/one.mp3"))
            runCurrent()
            assertEquals(1, fetches)
            assertContentEquals(first, output.toByteArray())
        } finally { client.close() }
    }

    @Test
    fun cancellingFetchNeverOpensAudio() = runTest {
        val response = CompletableDeferred<ByteArray>()
        var opened = false
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond(response.await()) }
            }
        }
        try {
            val player = JavaSoundSamplePlayer(client, backgroundScope) { opened = true; line(ByteArrayOutputStream()) }
            player.play(SamplePreview("one", "https://fixture.invalid/one.mp3"))
            runCurrent()
            assertTrue(player.nowPlaying.value!!.loading)
            player.stop()
            response.complete(fixture())
            runCurrent()
            assertNull(player.nowPlaying.value)
            assertFalse(opened)
        } finally { client.close() }
    }

    @Test
    fun failedFetchClearsLoadingWithoutOpeningAudio() = runTest {
        val client = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { respond("Unavailable", io.ktor.http.HttpStatusCode.ServiceUnavailable) }
            }
        }
        try {
            var opened = false
            val player = JavaSoundSamplePlayer(client, backgroundScope) { opened = true; line(ByteArrayOutputStream()) }
            player.play(SamplePreview("one", "https://fixture.invalid/one.mp3"))
            assertTrue(player.nowPlaying.value!!.loading)
            runCurrent()
            assertNull(player.nowPlaying.value)
            assertFalse(opened)
        } finally { client.close() }
    }

    private fun fixture() = checkNotNull(javaClass.getResourceAsStream("/audio/preview.mp3")).use { it.readAllBytes() }

    private fun line(output: ByteArrayOutputStream): SourceDataLine =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "write" -> {
                    output.write(args[0] as ByteArray, args[1] as Int, args[2] as Int)
                    args[2]
                }
                "isRunning" -> true
                "getBufferSize", "available" -> 0
                else -> null
            }
        } as SourceDataLine
}
