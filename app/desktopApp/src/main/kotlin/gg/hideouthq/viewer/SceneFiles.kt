package gg.hideouthq.viewer

import gg.hideouthq.data.preview.PreviewCache
import gg.hideouthq.data.preview.PreviewFileException
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewScene
import gg.hideouthq.preview.glbProblems
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SceneContent private constructor(
    val glb: ByteArray,
    val images: List<Ktx2Texture>,
    val scene: PreviewScene,
    val clips: List<PreviewClip>,
    val hiddenMeshSuffixes: List<String>,
) {
    val textures: Collection<Ktx2Texture> get() = images.toSet()

    companion object {
        fun read(glb: ByteArray, textures: PackedTextures, scene: PreviewScene, clips: List<PreviewClip>, hiddenMeshSuffixes: List<String>, gl: GlInfo): SceneContent {
            val runtime = SceneDecoder.runtimeScene(scene)
            SceneLimits.capabilityProblem(runtime, gl)?.let { throw UnsupportedScene(it) }
            val views = textures.images.distinctBy { it.view }.associate { it.view to Ktx2Texture.parse("Image view ${it.view}", textures.bytes, it.offset, it.length) }
            SceneLimits.texturesProblem(views.values, gl)?.let { throw UnsupportedScene(it) }
            return SceneContent(glb, textures.images.map { views.getValue(it.view) }, runtime, clips, hiddenMeshSuffixes)
        }
    }
}

class SceneFiles(
    val model: PreviewModel,
    val glb: ByteArray,
    val textures: PackedTextures,
) {
    fun content(gl: GlInfo) = SceneContent.read(glb, textures, checkNotNull(model.manifest.scene), model.clips, model.hiddenMeshSuffixes, gl)
}

class SceneProgress(val doneBytes: Long, val totalBytes: Long) {
    val fraction: Float get() = if (totalBytes <= 0) 0f else (doneBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

suspend fun PreviewCache.sceneFiles(model: PreviewModel, preflight: suspend () -> Unit = {}, onProgress: (SceneProgress) -> Unit = {}): SceneFiles {
    val manifest = model.manifest
    val scene = manifest.scene ?: throw PreviewFileException("The bundle has no scene")
    preflight()
    val asset = manifest.assets.first { it.path == scene.model }
    onProgress(SceneProgress(0, asset.bytes))
    val decoded = readAsset(model.entry.bundleId, asset, { onProgress(SceneProgress(it, asset.bytes)) }) { stored ->
        SceneDecoder.decode(stored, asset, manifest.clips)
    }
    val chunks = glbChunks(decoded.glb)
    val problems = glbProblems(scene.model, chunks.json, chunks.binaryBytes, manifest.copy(scene = SceneDecoder.runtimeScene(scene)))
    if (problems.isNotEmpty()) throw PreviewFileException("The model disagrees with its manifest: ${problems.joinToString("; ")}")
    return SceneFiles(model, decoded.glb, decoded.textures)
}

class GlbChunks(val json: String, val binaryBytes: Long?, val binaryAt: Int)

fun glbChunks(glb: ByteArray): GlbChunks {
    val header = ByteBuffer.wrap(glb).order(ByteOrder.LITTLE_ENDIAN)
    val size = glb.size.toLong()
    if (size < JsonAt || header.getInt(0) != GlbMagic || header.getInt(4) != 2 || header.getInt(8).toUInt().toLong() != size) {
        throw PreviewFileException("Not a glTF 2 binary")
    }
    val jsonBytes = header.getInt(GlbHeader).toUInt().toLong()
    if (header.getInt(GlbHeader + 4) != JsonChunk || jsonBytes > size - JsonAt) throw PreviewFileException("The glTF binary has no JSON chunk first")
    val jsonEnd = (JsonAt + jsonBytes).toInt()
    val binaryBytes = if (jsonEnd.toLong() == size) null else {
        val length = if (jsonEnd + ChunkHeader <= size) header.getInt(jsonEnd).toUInt().toLong() else -1
        if (length < 0 || header.getInt(jsonEnd + 4) != BinChunk || jsonEnd + ChunkHeader + length != size) {
            throw PreviewFileException("The glTF binary has something other than one BIN chunk after its JSON chunk")
        }
        length
    }
    if (jsonBytes % 4 != 0L || (binaryBytes ?: 0L) % 4 != 0L) throw PreviewFileException("The glTF binary has a chunk that is not 4-byte aligned")
    return GlbChunks(glb.decodeToString(JsonAt, jsonEnd, throwOnInvalidSequence = true), binaryBytes, jsonEnd + ChunkHeader)
}

internal const val GlbHeader = 12
internal const val ChunkHeader = 8
private const val JsonAt = GlbHeader + ChunkHeader
internal const val GlbMagic = 0x46546C67
internal const val JsonChunk = 0x4E4F534A
internal const val BinChunk = 0x004E4942
