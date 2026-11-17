package gg.hideouthq.viewer

import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewJson
import gg.hideouthq.preview.PreviewScene
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject

internal object FifthJoint {
    const val Move = "fifth-joint-moves"

    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/viewer/fifth-joint/$name")).use { it.readBytes() }

    private val spec = PreviewJson.parseToJsonElement(resource("spec.json").decodeToString()).jsonObject
    val scene = PreviewJson.decodeFromJsonElement<PreviewScene>(spec.getValue("scene"))
    val clips = PreviewJson.decodeFromJsonElement<List<PreviewClip>>(spec.getValue("clips"))
    val asset = PreviewJson.decodeFromJsonElement<List<PreviewAsset>>(spec.getValue("assets")).single()
    val stored: ByteArray get() = resource("model.glb.zst")

    suspend fun content(gl: GlInfo) = SceneDecoder.decode(stored.inputStream(), asset, clips).let { SceneContent.read(it.glb, it.textures, scene, clips, emptyList(), gl) }
}
