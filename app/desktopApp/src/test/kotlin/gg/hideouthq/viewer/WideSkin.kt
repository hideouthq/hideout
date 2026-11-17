package gg.hideouthq.viewer

import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.sha256Hex
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.sin

internal class WideSkin(private val compact: Boolean = false, private val zeroSlots: Int? = null) {
    val glb: ByteArray by lazy { build() }

    val joints: Int get() = if (compact) Used.size else Palette

    fun manifest(manifest: BundleManifest): BundleManifest = manifest.copy(
        scene = checkNotNull(manifest.scene).copy(maxJointsPerSkin = joints, influenceSets = 2, morphTargets = 1),
        clips = listOf(PreviewClip(Flex, 1000), PreviewClip(Twist, 800)),
        pictures = emptyList(),
    )

    fun sceneFiles(entry: IndexEntry, manifest: BundleManifest) = packedFiles(PreviewModel(entry, manifest(manifest)), glb)

    companion object {
        const val Flex = "flex"
        const val Twist = "twist"
        private val normalMap = TestKtx2.solid(4, 4, TestKtx2.Rgba(180, 128, 230))
        const val Palette = 512
        private const val Rows = 9
        private const val Slots = 8
        private const val RigNodes = 5
        private const val SecondSkinShift = 200
        private const val RowHeight = 0.25f
        private val Weights = floatArrayOf(8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f).map { it / 36f }

        fun slot(row: Int, slot: Int) = Palette - 1 - (slot * 24 + row)

        val Used: List<Int> = (0 until Rows).flatMap { row -> (0 until Slots).map { slot(row, it) } }.sorted()
    }

    private class Strip(val x0: Float, val x1: Float, val z: Float, val firstRow: Int, val lastRow: Int, val skin: Int, val material: Int, val textured: Boolean, val morph: Boolean) {
        val rows get() = firstRow..lastRow
        val vertices get() = rows.count() * 2
    }

    private fun build(): ByteArray {
        val strips = listOf(
            Strip(-0.3f, 0.3f, 0f, 0, Rows - 1, skin = 0, material = 0, textured = true, morph = true),
            Strip(0.35f, 0.75f, -0.05f, 0, Rows - 1, skin = 1, material = 0, textured = true, morph = true),
            Strip(-0.1f, 0.5f, 0.08f, 2, 6, skin = 0, material = 1, textured = false, morph = false),
            Strip(0.1f, 0.65f, 0.12f, 3, 7, skin = 1, material = 2, textured = false, morph = false),
        )
        val buffer = GlbBuffer()
        val accessors = mutableListOf<String>()
        fun shorts(values: List<Int>) = ByteBuffer.allocate(values.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(it.toShort()) } }.array()
        fun accessor(view: Int, componentType: Int, count: Int, type: String, bounds: String = ""): Int {
            accessors += """{"bufferView":$view,"componentType":$componentType,"count":$count,"type":"$type"$bounds}"""
            return accessors.lastIndex
        }

        fun skinSlot(skin: Int, first: Int): Int = when {
            compact && skin == 0 -> Used.indexOf(first)
            compact -> Used.lastIndex - Used.indexOf(first)
            skin == 0 -> first
            else -> (first - SecondSkinShift + Palette) % Palette
        }
        fun skinJoints(skin: Int): List<Int> = when {
            compact && skin == 0 -> Used
            compact -> Used.reversed()
            skin == 0 -> (0 until Palette).toList()
            else -> (0 until Palette).map { (it + SecondSkinShift) % Palette }
        }
        fun restHeight(first: Int) = RowHeight * minOf((Palette - 1 - first) % 24, Rows - 1)

        val primitives = strips.map { strip ->
            val positions = mutableListOf<Float>()
            val joints = MutableList(2) { mutableListOf<Int>() }
            val weights = MutableList(2) { mutableListOf<Float>() }
            for (row in strip.rows) for (x in listOf(strip.x0, strip.x1)) {
                positions += listOf(x, row * RowHeight, strip.z)
                for (slot in 0 until Slots) {
                    val weighted = zeroSlots == null || slot < 4
                    val weight = if (zeroSlots == null) Weights[(slot + row) % Slots] else if (weighted) Weights[slot] / Weights.take(4).sum() else 0f
                    joints[slot / 4] += if (weighted) skinSlot(strip.skin, slot(row, slot)) else zeroSlots!!
                    weights[slot / 4] += weight
                }
            }
            val n = strip.vertices
            val xs = positions.filterIndexed { i, _ -> i % 3 == 0 }
            val ys = positions.filterIndexed { i, _ -> i % 3 == 1 }
            val position = accessor(buffer.view(floats(positions)), 5126, n, "VEC3", ""","min":[${xs.min()},${ys.min()},${strip.z}],"max":[${xs.max()},${ys.max()},${strip.z}]""")
            val normal = accessor(buffer.view(floats(List(n) { listOf(0f, 0f, 1f) }.flatten())), 5126, n, "VEC3")
            val sets = (0 until 2).map { set ->
                accessor(buffer.view(shorts(joints[set])), 5123, n, "VEC4") to accessor(buffer.view(floats(weights[set])), 5126, n, "VEC4")
            }
            val texture = if (!strip.textured) "" else {
                val tangent = accessor(buffer.view(floats(List(n) { listOf(1f, 0f, 0f, 1f) }.flatten())), 5126, n, "VEC4")
                val uv = accessor(buffer.view(floats(strip.rows.flatMap { row -> listOf(0f, row / 8f, 1f, row / 8f) })), 5126, n, "VEC2")
                ""","TANGENT":$tangent,"TEXCOORD_0":$uv"""
            }
            val targets = if (!strip.morph) "" else {
                val deltas = strip.rows.flatMap { row -> listOf(-1f, 1f).flatMap { side -> listOf(0.08f * sin(row.toFloat()), 0f, 0.06f * side) } }
                val dx = deltas.filterIndexed { i, _ -> i % 3 == 0 }
                val dz = deltas.filterIndexed { i, _ -> i % 3 == 2 }
                val target = accessor(buffer.view(floats(deltas)), 5126, n, "VEC3", ""","min":[${dx.min()},0,${dz.min()}],"max":[${dx.max()},0,${dz.max()}]""")
                ""","targets":[{"POSITION":$target}]"""
            }
            val indices = accessor(buffer.view(shorts((0 until strip.rows.count() - 1).flatMap { r -> (2 * r).let { v -> listOf(v, v + 1, v + 3, v, v + 3, v + 2) } })), 5123, (strip.rows.count() - 1) * 6, "SCALAR")
            """{"attributes":{"POSITION":$position,"NORMAL":$normal,"JOINTS_0":${sets[0].first},"WEIGHTS_0":${sets[0].second},"JOINTS_1":${sets[1].first},"WEIGHTS_1":${sets[1].second}$texture},"indices":$indices,"material":${strip.material}$targets}"""
        }

        val skins = (0 until 2).map { skin ->
            val nodes = skinJoints(skin)
            val inverses = nodes.flatMap { first -> listOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -restHeight(first), 0f, 1f) }
            """{"joints":[${nodes.joinToString(",") { "${RigNodes + it}" }}],"inverseBindMatrices":${accessor(buffer.view(floats(inverses)), 5126, nodes.size, "MAT4")}}"""
        }

        fun turn(axis: Int, angle: Float) = FloatArray(4).also { it[axis] = sin(angle / 2); it[3] = cos(angle / 2) }.toList()
        fun clip(name: String, seconds: Float, axis: Int, angle: (row: Int, slot: Int) -> Float, morphs: Boolean): String {
            val times = accessor(buffer.view(floats(listOf(0f, seconds))), 5126, 2, "SCALAR", ""","min":[0],"max":[$seconds]""")
            val samplers = mutableListOf<String>()
            val channels = mutableListOf<String>()
            for (row in 0 until Rows) for (slot in 0 until Slots) {
                val rotation = accessor(buffer.view(floats(turn(axis, 0f) + turn(axis, angle(row, slot)))), 5126, 2, "VEC4")
                samplers += """{"input":$times,"output":$rotation,"interpolation":"LINEAR"}"""
                channels += """{"sampler":${samplers.lastIndex},"target":{"node":${RigNodes + slot(row, slot)},"path":"rotation"}}"""
            }
            if (morphs) for (node in 0..1) {
                val amounts = accessor(buffer.view(floats(listOf(0f, 0.8f - 0.3f * node))), 5126, 2, "SCALAR")
                samplers += """{"input":$times,"output":$amounts,"interpolation":"LINEAR"}"""
                channels += """{"sampler":${samplers.lastIndex},"target":{"node":$node,"path":"weights"}}"""
            }
            return """{"name":"$name","samplers":[${samplers.joinToString(",")}],"channels":[${channels.joinToString(",")}]}"""
        }
        val animations = listOf(
            clip(Flex, 1f, axis = 2, angle = { row, slot -> 0.012f * (row + 1) * (slot + 1) }, morphs = true),
            clip(Twist, 0.8f, axis = 1, angle = { row, slot -> if (slot % 2 == 0) 0.05f * (row + 1) else -0.02f * (slot + 1) }, morphs = false),
        )
        val rig = (0 until Palette).joinToString(",") { first -> """{"name":"j$first","translation":[0,${restHeight(first)},0]}""" }
        val normalView = buffer.view(normalMap)
        return buffer.glb("""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1,2,3,4]}],
             "nodes":[{"name":"body","mesh":0,"skin":0},{"name":"cape","mesh":1,"skin":1},{"name":"veil","mesh":2,"skin":0},{"name":"glass","mesh":3,"skin":1},
                      {"name":"rig","children":[${(0 until Palette).joinToString(",") { "${RigNodes + it}" }}]},$rig],
             "skins":[${skins.joinToString(",")}],
             "meshes":[${primitives.mapIndexed { i, p -> """{"name":"part$i","primitives":[$p]${if (strips[i].morph) ",\"weights\":[0]" else ""}}""" }.joinToString(",")}],
             "materials":[
               {"doubleSided":true,"normalTexture":{"index":0},"pbrMetallicRoughness":{"baseColorFactor":[0.75,0.55,0.35,1],"metallicFactor":0.1,"roughnessFactor":0.6}},
               {"alphaMode":"BLEND","doubleSided":true,"pbrMetallicRoughness":{"baseColorFactor":[0.2,0.5,0.9,0.5],"metallicFactor":0,"roughnessFactor":0.5}},
               {"alphaMode":"BLEND","doubleSided":true,"pbrMetallicRoughness":{"baseColorFactor":[0.9,0.3,0.4,0.45],"metallicFactor":0,"roughnessFactor":0.4}}],
             "textures":[{"source":0}],"images":${packedImages(listOf(normalView))},
             "animations":[${animations.joinToString(",")}],
             "accessors":[${accessors.joinToString(",")}]}
        """.trimIndent().replace("\n", ""))
    }
}
