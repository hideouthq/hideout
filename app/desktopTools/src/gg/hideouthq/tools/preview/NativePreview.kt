package gg.hideouthq.tools.preview

import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewJson
import gg.hideouthq.preview.PreviewScene
import gg.hideouthq.viewer.Camera
import gg.hideouthq.viewer.DecodedModel
import gg.hideouthq.viewer.FrameRequest
import gg.hideouthq.viewer.GlThread
import gg.hideouthq.viewer.SceneContent
import gg.hideouthq.viewer.SceneDecoder
import gg.hideouthq.viewer.SceneRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.system.exitProcess

@Serializable
internal data class CaptureSpec(val scene: PreviewScene, val clips: List<PreviewClip>, val pictures: List<CapturePicture>, val assets: List<PreviewAsset> = emptyList())

@Serializable
internal data class CapturePicture(
    val role: String,
    val width: Int,
    val height: Int,
    val clip: String? = null,
    val seconds: Float = 0f,
    val azimuth: Float,
    val hiddenMeshSuffixes: List<String> = emptyList(),
) {
    fun frame() = FrameRequest(width, height, clip, seconds, Camera(azimuth), SceneLighting.Dramatic)
}

fun main(args: Array<String>) {
    val started = System.nanoTime()
    try {
        require(args.size == 4 && args[0] == "capture") { "usage: capture MODEL OUT SPEC_JSON" }
        runBlocking { captureNative(File(args[1]), File(args[2]), PreviewJson.decodeFromString<CaptureSpec>(args[3]), started) }
        exitProcess(0)
    } catch (failure: Throwable) {
        failure.printStackTrace(System.err)
        exitProcess(1)
    }
}

private class LocalScene(val model: DecodedModel, val modelSha256: String)

private suspend fun localScene(model: File, spec: CaptureSpec): LocalScene = withContext(Dispatchers.IO) {
    val digest = MessageDigest.getInstance("SHA-256")
    val decoded = DigestInputStream(model.inputStream(), digest).use { stream ->
        SceneDecoder.decode(stream, spec.assets.single { it.path == spec.scene.model }, spec.clips).also { stream.transferTo(OutputStream.nullOutputStream()) }
    }
    LocalScene(decoded, HexFormat.of().formatHex(digest.digest()))
}

private suspend fun captureNative(model: File, out: File, spec: CaptureSpec, started: Long) {
    out.resolve("pictures").mkdirs()
    val gl = GlThread()
    val info = gl.info()
    val readAt = System.nanoTime()
    val scene = localScene(model, spec)
    val loadedAt = System.nanoTime()
    val pictures = mutableListOf<JsonObject>()
    for ((hidden, group) in spec.pictures.groupBy { it.hiddenMeshSuffixes }) {
        val content = SceneContent.read(scene.model.glb, scene.model.textures, spec.scene, spec.clips, hidden, info)
        val renderer = gl.run { SceneRenderer.load(content, info, gl.liveScenes, gl.openFrames) }
        try {
            for (picture in group) {
                val frameAt = System.nanoTime()
                var request = picture.frame()
                var pixels = gl.run({ it.close() }) { renderer.render(request) }.use { it.pixels() }
                var alpha = pixels.alphaFacts()
                while (alpha.getValue("borderMaxAlpha").jsonPrimitive.int != 0 && request.camera.zoom > 0.1f) {
                    request = request.copy(camera = request.camera.copy(zoom = request.camera.zoom * 0.8f))
                    pixels = gl.run({ it.close() }) { renderer.render(request) }.use { it.pixels() }
                    alpha = pixels.alphaFacts()
                }
                out.resolve("pictures/${picture.role}.png").writeBytes(pixels.png())
                pictures += buildJsonObject {
                    put("role", picture.role)
                    put("clip", picture.clip)
                    put("seconds", picture.seconds)
                    put("zoom", request.camera.zoom)
                    put("alpha", alpha)
                    put("rgbaSha256", pixels.sha256)
                    put("renderEncodeMillis", elapsed(frameAt))
                }
            }
        } finally {
            gl.run { renderer.dispose() }
            check(gl.liveScenes.get() == 0 && gl.openFrames.get() == 0)
        }
    }
    val report = buildJsonObject {
        put("engine", "Hideout SceneRenderer")
        put("renderer", info.renderer)
        put("glVersion", info.version)
        put("modelSha256", scene.modelSha256)
        putJsonObject("sceneHashes") { put(model.name, scene.modelSha256) }
        put("textureCount", scene.model.textures.images.distinctBy { it.view }.size)
        put("readMillis", (loadedAt - readAt) / 1e6)
        put("startupMillis", (readAt - started) / 1e6)
        put("totalMillis", elapsed(started))
        putJsonArray("pictures") { pictures.forEach { add(it) } }
    }
    out.resolve("capture.json").writeText(report.toString())
    println(report)
}

private fun elapsed(started: Long) = (System.nanoTime() - started) / 1e6
