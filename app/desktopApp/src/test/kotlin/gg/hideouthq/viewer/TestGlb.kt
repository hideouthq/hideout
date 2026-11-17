package gg.hideouthq.viewer

import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.PreviewClip
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class GlbBuffer {
    private val data = ByteArrayOutputStream()
    private val views = mutableListOf<String>()

    fun view(bytes: ByteArray): Int {
        pad()
        views += """{"buffer":0,"byteOffset":${data.size()},"byteLength":${bytes.size}}"""
        data.write(bytes)
        return views.lastIndex
    }

    fun glb(json: String): ByteArray {
        pad()
        return glbOf(json.trimEnd().removeSuffix("}") + ""","bufferViews":[${views.joinToString(",")}],"buffers":[{"byteLength":${data.size()}}]}""", data.toByteArray())
    }

    private fun pad() {
        while (data.size() % 4 != 0) data.write(0)
    }
}

internal fun readModel(glb: ByteArray, clips: List<PreviewClip> = emptyList()): DecodedModel =
    runBlocking { SceneDecoder.read(glb.inputStream(), "model.glb.zst", clips) }

internal fun packedFiles(model: PreviewModel, glb: ByteArray): SceneFiles = readModel(glb, model.clips).let { SceneFiles(model, it.glb, it.textures) }

internal fun packedImages(views: List<Int>): String = views.joinToString(",", "[", "]") { """{"bufferView":$it,"mimeType":"image/ktx2"}""" }

internal fun floats(vararg values: Float): ByteArray = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array()

internal fun floats(values: List<Float>) = floats(*values.toFloatArray())

internal fun glbOf(json: String, bin: ByteArray): ByteArray {
    val text = json.encodeToByteArray().let { it + ByteArray((4 - it.size % 4) % 4) { ' '.code.toByte() } }
    val binary = bin + ByteArray((4 - bin.size % 4) % 4)
    return ByteBuffer.allocate(28 + text.size + binary.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0x46546C67).putInt(2).putInt(capacity())
        putInt(text.size).putInt(0x4E4F534A).put(text)
        putInt(binary.size).putInt(0x004E4942).put(binary)
    }.array()
}
