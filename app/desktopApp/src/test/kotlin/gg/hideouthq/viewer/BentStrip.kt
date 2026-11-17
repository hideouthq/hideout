package gg.hideouthq.viewer

import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.AssetEncoding
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.sha256Hex
import com.github.luben.zstd.Zstd
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

internal fun encodedModel(model: PreviewAsset, glb: ByteArray): Pair<PreviewAsset, ByteArray> {
    val stored = Zstd.compress(glb, 6)
    return model.copy(bytes = stored.size.toLong(), sha256 = sha256Hex(stored), encoding = AssetEncoding("zstd", glb.size.toLong(), sha256Hex(glb))) to stored
}

internal object BentStrip {
    const val Bend = "bend"
    const val Sway = "sway"
    const val Leap = "leap"
    const val LeapDistance = 10f

    val glb: ByteArray by lazy { build() }

    private val leapingGlb: ByteArray by lazy { build(leap = true) }

    fun manifest(manifest: BundleManifest, leap: Boolean = false): BundleManifest = manifest.copy(
        scene = checkNotNull(manifest.scene).copy(maxJointsPerSkin = 2, influenceSets = 1, morphTargets = 0),
        clips = listOf(PreviewClip(Bend, 1000), PreviewClip(Sway, 500)) + listOfNotNull(PreviewClip(Leap, 1000).takeIf { leap }),
        pictures = emptyList(),
    )

    fun sceneFiles(entry: IndexEntry, manifest: BundleManifest) = SceneFiles(PreviewModel(entry, manifest(manifest)), glb, PackedTextures.None)

    fun leapingFiles(entry: IndexEntry, manifest: BundleManifest) = SceneFiles(PreviewModel(entry, manifest(manifest, leap = true)), leapingGlb, PackedTextures.None)

    fun texturedFiles(entry: IndexEntry, manifest: BundleManifest, images: List<ByteArray>) = packedFiles(PreviewModel(entry, manifest(manifest)), texturedGlb(images))

    fun texturedGlb(images: List<ByteArray>): ByteArray = build(images)

    private fun build(images: List<ByteArray> = emptyList(), leap: Boolean = false): ByteArray {
        val textured = images.isNotEmpty()
        val buffer = GlbBuffer()
        val ys = listOf(0f, 0f, 1f, 1f, 2f, 2f)
        val positions = buffer.view(floats(ys.mapIndexed { i, y -> listOf(if (i % 2 == 0) -0.3f else 0.3f, y, 0f) }.flatten()))
        val normals = buffer.view(floats(List(6) { listOf(0f, 0f, 1f) }.flatten()))
        val joints = buffer.view(ByteArray(24) { i -> if (i % 4 == 0 && i / 4 >= 4) 1 else 0 })
        val weights = buffer.view(floats(List(6) { listOf(1f, 0f, 0f, 0f) }.flatten()))
        val indices = buffer.view(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply { listOf(0, 1, 3, 0, 3, 2, 2, 3, 5, 2, 5, 4).forEach { putShort(it.toShort()) } }.array())
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        val tipInverse = identity.copyOf().also { it[13] = -1f }
        val inverseBinds = buffer.view(floats(*(identity + tipInverse)))
        val bendTimes = buffer.view(floats(0f, 1f))
        val bendTurns = buffer.view(floats(0f, 0f, 0f, 1f, 0f, 0f, sin(Math.PI / 4).toFloat(), cos(Math.PI / 4).toFloat()))
        val swayTimes = buffer.view(floats(0f, 0.5f))
        val swayTurns = buffer.view(floats(0f, 0f, 0f, 1f, 0f, 0f, sin(Math.PI / 16).toFloat(), cos(Math.PI / 16).toFloat()))
        val texcoords = if (textured) buffer.view(floats(ys.mapIndexed { i, y -> listOf(if (i % 2 == 0) 0f else 1f, y / 2) }.flatten())) else null
        val leapPlaces = if (leap) buffer.view(floats(0f, 0f, 0f, LeapDistance, 0f, 0f)) else null
        val leapAccessor = if (texcoords == null) 10 else 11
        val imageJson = packedImages(images.map(buffer::view)).removeSurrounding("[", "]")

        return buffer.glb("""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
             "nodes":[{"name":"body","mesh":0,"skin":0},{"name":"root","children":[2]},{"name":"tip","translation":[0,1,0]}],
             "skins":[{"joints":[1,2],"inverseBindMatrices":5}],
             "meshes":[{"name":"strip","primitives":[{"attributes":{"POSITION":0,"NORMAL":1,"JOINTS_0":2,"WEIGHTS_0":3${if (texcoords == null) "" else ",\"TEXCOORD_0\":10"}},"indices":4,"material":0}]}],
             "materials":[{"doubleSided":true,"pbrMetallicRoughness":{${if (!textured) "\"baseColorFactor\":[0.8,0.3,0.2,1]" else "\"baseColorTexture\":{\"index\":0}"},"metallicFactor":0,"roughnessFactor":0.8}}],${if (!textured) "" else "\"textures\":[{\"source\":0}],\"images\":[$imageJson],"}
             "animations":[
               {"name":"$Bend","samplers":[{"input":6,"output":7,"interpolation":"LINEAR"}],"channels":[{"sampler":0,"target":{"node":2,"path":"rotation"}}]},
               {"name":"$Sway","samplers":[{"input":8,"output":9,"interpolation":"LINEAR"}],"channels":[{"sampler":0,"target":{"node":1,"path":"rotation"}}]}${if (leapPlaces == null) "" else ",{\"name\":\"$Leap\",\"samplers\":[{\"input\":6,\"output\":$leapAccessor,\"interpolation\":\"LINEAR\"}],\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"translation\"}}]}"}],
             "accessors":[
               {"bufferView":$positions,"componentType":5126,"count":6,"type":"VEC3","min":[-0.3,0,0],"max":[0.3,2,0]},
               {"bufferView":$normals,"componentType":5126,"count":6,"type":"VEC3"},
               {"bufferView":$joints,"componentType":5121,"count":6,"type":"VEC4"},
               {"bufferView":$weights,"componentType":5126,"count":6,"type":"VEC4"},
               {"bufferView":$indices,"componentType":5123,"count":12,"type":"SCALAR"},
               {"bufferView":$inverseBinds,"componentType":5126,"count":2,"type":"MAT4"},
               {"bufferView":$bendTimes,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[1]},
               {"bufferView":$bendTurns,"componentType":5126,"count":2,"type":"VEC4"},
               {"bufferView":$swayTimes,"componentType":5126,"count":2,"type":"SCALAR","min":[0],"max":[0.5]},
               {"bufferView":$swayTurns,"componentType":5126,"count":2,"type":"VEC4"}${if (texcoords == null) "" else ",{\"bufferView\":$texcoords,\"componentType\":5126,\"count\":6,\"type\":\"VEC2\"}"}${if (leapPlaces == null) "" else ",{\"bufferView\":$leapPlaces,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"}"}]}
        """.trimIndent().replace("\n", ""))
    }
}
