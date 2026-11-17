package gg.hideouthq.viewer

import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import org.jetbrains.skia.Bitmap
import kotlin.math.abs

/** A drawn frame's premultiplied RGBA rows, bottom row first, as glReadPixels left them. */
internal class Frame(val name: String, val pixels: ByteArray)

internal object ProofFrames {
    fun requests(model: PreviewModel, side: Int): List<Pair<String, FrameRequest>> {
        val azimuth = model.fallbackPicture?.azimuth?.toFloat() ?: -70f
        val clips = model.clips
        fun at(clip: Int, fraction: Float, angle: Float = azimuth, lighting: SceneLighting = SceneLighting.Studio, size: Int = side) =
            FrameRequest(size, size, clips[clip].id, fraction * clips[clip].durationMillis / 1000f, Camera(angle), lighting)
        return buildList {
            clips.indices.forEach { clip ->
                add("c%02d_t020".format(clip) to at(clip, 0.2f))
                add("c%02d_t075".format(clip) to at(clip, 0.75f))
            }
            add("c00_t050_side" to at(0, 0.5f, angle = azimuth + 100f))
            add("c00_t050_back" to at(0, 0.5f, angle = azimuth + 200f))
            add("c00_t050_studio" to at(0, 0.5f, lighting = SceneLighting.Dramatic))
            add("c00_t050_large" to at(0, 0.5f, size = side * 2))
            add("rest" to FrameRequest(side, side, null, 0f, Camera(azimuth), SceneLighting.Studio))
        }
    }

    suspend fun render(gl: GlThread, renderer: SceneRenderer, requests: List<Pair<String, FrameRequest>>): List<Frame> = requests.map { (name, request) ->
        val pixels = gl.run({ it.close() }) { renderer.render(request) }.use { frame -> Bitmap.makeFromImage(frame.image).use { checkNotNull(it.readPixels()) } }
        Frame(name, pixels)
    }

    fun difference(a: ByteArray, b: ByteArray): Pair<Int, Int> {
        require(a.size == b.size) { "Frames of ${a.size} and ${b.size} bytes" }
        var pixels = 0
        var largest = 0
        for (at in a.indices step 4) {
            var worst = 0
            for (c in 0 until 4) worst = maxOf(worst, abs((a[at + c].toInt() and 0xff) - (b[at + c].toInt() and 0xff)))
            if (worst > 0) pixels++
            largest = maxOf(largest, worst)
        }
        return pixels to largest
    }

    fun covered(pixels: ByteArray) = (pixels.indices step 4).count { pixels[it + 3].toInt() != 0 }
}
