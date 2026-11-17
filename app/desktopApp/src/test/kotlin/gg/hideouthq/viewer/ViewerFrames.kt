package gg.hideouthq.viewer

import com.badlogic.gdx.Gdx
import gg.hideouthq.core.model.SceneLighting
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import java.io.File
import kotlin.test.Test

class ViewerFrames {
    @Test
    fun render() = runBlocking {
        val job = StoreJob.from("hideout.viewerFrames")
        val frames = job.json.getValue("frames").jsonArray.map { it.jsonObject }
        val gl = TestGl.thread
        val info = TestGl.info()
        val results = File(job.out.apply { mkdirs() }, "results.jsonl")
        for (id in job.bundles) {
            val (model, content) = job.scene(id, info)
            val clip = model.clips.firstOrNull()
            val start = System.nanoTime()
            val renderer = gl.run { SceneRenderer.load(content, info, gl.liveScenes, gl.openFrames).also { Gdx.gl.glFinish() } }
            val loadMillis = (System.nanoTime() - start) / 1e6
            val folder = File(job.out, "$id/ktx2").apply { mkdirs() }
            for (frame in frames) {
                val side = frame.getValue("side").jsonPrimitive.int
                val camera = Camera(model.stillAzimuth, zoom = frame.getValue("zoom").jsonPrimitive.float, panY = frame.getValue("panY").jsonPrimitive.float)
                val request = FrameRequest(side, side, clip?.id, (clip?.durationMillis?.toFloat() ?: 0f) * 0.2f / 1000f, camera, SceneLighting.Studio,
                    CameraFraming.Reference(model.stillAzimuth))
                val pixels = gl.run({ it.close() }) { renderer.render(request) }.use { drawn ->
                    Bitmap.makeFromImage(drawn.image).use { checkNotNull(it.readPixels()) }
                }
                File(folder, "${frame.getValue("name").jsonPrimitive.content}.rgba").writeBytes(pixels)
            }
            gl.run { renderer.dispose() }
            val line = buildJsonObject {
                put("bundle", id)
                put("renderer", info.renderer)
                put("loadMillis", loadMillis)
                put("textures", content.textures.size)
                put("gpuBytes", content.textures.sumOf { it.gpuBytes() })
            }
            results.appendText("$line\n")
            println("viewer frames: $line")
        }
    }
}
