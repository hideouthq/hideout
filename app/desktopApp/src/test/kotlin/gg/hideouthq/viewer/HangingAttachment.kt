package gg.hideouthq.viewer

import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewClip
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class HangingAttachment(private val drop: Float = 0.5f, private val span: Float = 0.4f, private val fold: Float = 1f, private val remote: Float? = null, private val bodyDepth: Float = 0f) {
    val glb: ByteArray by lazy { build() }

    fun manifest(manifest: BundleManifest): BundleManifest = manifest.copy(
        scene = checkNotNull(manifest.scene).copy(maxJointsPerSkin = 2, influenceSets = 1, morphTargets = 0),
        clips = listOf(PreviewClip(Hold, 1000)),
        pictures = emptyList(),
    )

    fun sceneFiles(entry: IndexEntry, manifest: BundleManifest) = SceneFiles(PreviewModel(entry, manifest(manifest)), glb, PackedTextures.None)

    companion object {
        const val Hold = "hold"
    }

    private fun build(): ByteArray {
        val buffer = GlbBuffer()
        val body = listOf(-0.3f to 0f, 0.3f to 0f, -0.3f to 2f, 0.3f to 2f)
        val attachment = listOf(0.2f to -drop, 0.2f + span to -drop, 0.2f to 0.4f - drop, 0.2f + span to 0.4f - drop)
        val island = remote?.let { y -> listOf(-0.2f to y, 0.2f to y, -0.2f to y + 0.4f, 0.2f to y + 0.4f) }.orEmpty()
        val corners = body + attachment + island
        val positions = buffer.view(floats(corners.flatMapIndexed { i, (x, y) -> listOf(x, y, if (i < body.size) x * bodyDepth else 0f) }))
        val normals = buffer.view(floats(List(corners.size) { listOf(0f, 0f, 1f) }.flatten()))
        val joints = buffer.view(ByteArray(corners.size * 4) { i -> if (i % 4 == 0 && i / 4 in 4..7) 1 else 0 })
        val weights = buffer.view(floats(List(corners.size) { listOf(1f, 0f, 0f, 0f) }.flatten()))
        val triangles = (corners.indices step 4).flatMap { i -> listOf(i, i + 1, i + 3, i, i + 3, i + 2) }
        val indices = buffer.view(ByteBuffer.allocate(triangles.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { triangles.forEach { putShort(it.toShort()) } }.array())
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val handInverse = identity.copyOf().also { it[13] = drop }
        val inverseBinds = buffer.view(floats(*(identity + handInverse)))
        val holdTimes = buffer.view(floats(0f, 1f))
        val holdPlaces = buffer.view(floats(0f, 1.5f - drop, 0f, 0f, 1.5f - drop, 0f))
        val holdScales = if (fold == 1f) null else buffer.view(floats(fold, 1f, 1f, fold, 1f, 1f))

        return buffer.glb("""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
             "nodes":[{"name":"body","mesh":0,"skin":0},{"name":"root","children":[2]},{"name":"hand","translation":[0,${-drop},0]}],
             "skins":[{"joints":[1,2],"inverseBindMatrices":5}],
             "meshes":[{"name":"figure","primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"JOINTS_0":2,"WEIGHTS_0":3},"indices":4,"material":0}]}],
             "materials":[{"doubleSided":true,"pbrMetallicRoughness":{"baseColorFactor":[0.8,0.3,0.2,1],"metallicFactor":0,"roughnessFactor":0.8}}],
             "animations":[{"name":"$Hold","samplers":[{"input":6,"output":7,"interpolation":"LINEAR"}${if (holdScales == null) "" else ",{\"input\":6,\"output\":8,\"interpolation\":\"LINEAR\"}"}],"channels":[{"sampler":0,"target":{"node":2,"path":"translation"}}${if (holdScales == null) "" else ",{\"sampler\":1,\"target\":{\"node\":2,\"path\":\"scale\"}}"}]}],
             "accessors":[
               {"bufferView":$positions,"componentType":5126,"count":${corners.size},"type":"VEC3","min":[-0.3,${corners.minOf { it.second }},${-0.3f * bodyDepth}],"max":[${maxOf(0.3f, 0.2f + span)},${corners.maxOf { it.second }},${0.3f * bodyDepth}]},
               {"bufferView":$normals,"componentType":5126,"count":${corners.size},"type":"VEC3"},
               {"bufferView":$joints,"componentType":5121,"count":${corners.size},"type":"VEC4"},
               {"bufferView":$weights,"componentType":5126,"count":${corners.size},"type":"VEC4"},
               {"bufferView":$indices,"componentType":5123,"count":${triangles.size},"type":"SCALAR"},
               {"bufferView":$inverseBinds,"componentType":5126,"count":2,"type":"MAT4"},
               {"bufferView":$holdTimes,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
               {"bufferView":$holdPlaces,"componentType":5126,"count":2,"type":"VEC3"}${if (holdScales == null) "" else ",{\"bufferView\":$holdScales,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"}"}]}
        """.trimIndent().replace("\n", ""))
    }
}
