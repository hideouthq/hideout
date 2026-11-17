package gg.hideouthq.viewer

import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewClip
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object LargeIndexPartition {
    const val Move = "move"

    private const val HalfRows = 65535
    private const val Cols = 86
    private const val Rows = 127
    private const val HalfWidth = 0.8f
    private const val HalfHeight = 1f
    private const val Gap = 0.4f
    private const val Shift = 0.05f

    val sourceGlb: ByteArray by lazy { build(partitioned = false) }
    val partitionedGlb: ByteArray by lazy { build(partitioned = true) }

    fun manifest(manifest: BundleManifest): BundleManifest = manifest.copy(
        scene = checkNotNull(manifest.scene).copy(maxJointsPerSkin = 1, influenceSets = 1, morphTargets = 2, extensionsRequired = emptyList()),
        clips = listOf(PreviewClip(Move, 1000)),
        pictures = emptyList(),
    )

    fun sourceFiles(entry: IndexEntry, manifest: BundleManifest) = SceneFiles(PreviewModel(entry, manifest(manifest)), sourceGlb, PackedTextures.None)

    fun partitionedFiles(entry: IndexEntry, manifest: BundleManifest) = SceneFiles(PreviewModel(entry, manifest(manifest)), partitionedGlb, PackedTextures.None)

    private class Half(val baseX: Float, deltaY: Float) {
        val positions = FloatArray(HalfRows * 3)
        val normals = FloatArray(HalfRows * 3)
        val weights = FloatArray(HalfRows * 4)
        val joints = ByteArray(HalfRows * 4)
        val delta = FloatArray(HalfRows * 3)
        val zero = FloatArray(HalfRows * 3)

        init {
            val cellW = HalfWidth / Cols
            val cellH = HalfHeight / Rows
            var v = 0
            fun vertex(x: Float, y: Float) {
                positions[v * 3] = x
                positions[v * 3 + 1] = y
                positions[v * 3 + 2] = 0f
                v++
            }
            for (r in 0 until Rows) for (c in 0 until Cols) {
                val x0 = baseX + c * cellW
                val x1 = x0 + cellW
                val y0 = r * cellH
                val y1 = y0 + cellH
                vertex(x0, y0); vertex(x1, y0); vertex(x1, y1)
                vertex(x0, y0); vertex(x1, y1); vertex(x0, y1)
            }
            repeat(3) { vertex(baseX, 0f) }
            check(v == HalfRows) { "built $v rows, not $HalfRows" }
            for (i in 0 until HalfRows) {
                normals[i * 3 + 2] = 1f
                weights[i * 4] = 1f
                delta[i * 3 + 1] = deltaY
            }
        }
    }

    private fun build(partitioned: Boolean): ByteArray {
        val half0 = Half(-(HalfWidth + Gap / 2), Shift)
        val half1 = Half(Gap / 2, -Shift)

        val buffer = GlbBuffer()
        val accessors = mutableListOf<String>()
        fun accessor(view: Int, componentType: Int, count: Int, type: String, bounds: String = ""): Int {
            accessors += """{"bufferView":$view,"componentType":$componentType,"count":$count,"type":"$type"$bounds}"""
            return accessors.lastIndex
        }

        fun positionAccessor(half: Half) =
            accessor(buffer.view(floats(*half.positions)), 5126, HalfRows, "VEC3", ""","min":[${half.baseX},0,0],"max":[${half.baseX + HalfWidth},$HalfHeight,0]""")
        fun normalAccessor(half: Half) = accessor(buffer.view(floats(*half.normals)), 5126, HalfRows, "VEC3")
        fun jointAccessor(half: Half) = accessor(buffer.view(half.joints), 5121, HalfRows, "VEC4")
        fun weightAccessor(half: Half) = accessor(buffer.view(floats(*half.weights)), 5126, HalfRows, "VEC4")
        fun targetAccessor(values: FloatArray, count: Int) = accessor(buffer.view(floats(*values)), 5126, count, "VEC3")

        val indicesUshort = accessor(
            buffer.view(ByteBuffer.allocate(HalfRows * 2).order(ByteOrder.LITTLE_ENDIAN).apply { for (i in 0 until HalfRows) putShort(i.toShort()) }.array()),
            5123,
            HalfRows,
            "SCALAR",
        )
        val inverseBind = accessor(buffer.view(floats(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)), 5126, 1, "MAT4")
        val times = accessor(buffer.view(floats(0f, 0.5f, 1f)), 5126, 3, "SCALAR", ""","min":[0],"max":[1]""")
        val weightsOutput = accessor(buffer.view(floats(0f, 0f, 1f, 0.5f, 0.4f, 1f)), 5126, 6, "SCALAR")

        val meshJson = if (partitioned) {
            val prim0 = """{"attributes":{"POSITION":${positionAccessor(half0)},"NORMAL":${normalAccessor(half0)},"JOINTS_0":${jointAccessor(half0)},"WEIGHTS_0":${weightAccessor(half0)}},"indices":$indicesUshort,"material":0,"targets":[{"POSITION":${targetAccessor(half0.delta, HalfRows)}},{"POSITION":${targetAccessor(half0.zero, HalfRows)}}]}"""
            val prim1 = """{"attributes":{"POSITION":${positionAccessor(half1)},"NORMAL":${normalAccessor(half1)},"JOINTS_0":${jointAccessor(half1)},"WEIGHTS_0":${weightAccessor(half1)}},"indices":$indicesUshort,"material":0,"targets":[{"POSITION":${targetAccessor(half1.zero, HalfRows)}},{"POSITION":${targetAccessor(half1.delta, HalfRows)}}]}"""
            """{"name":"halves","primitives":[$prim0,$prim1],"weights":[0,0]}"""
        } else {
            val total = HalfRows * 2
            val position = accessor(
                buffer.view(floats(*(half0.positions + half1.positions))),
                5126,
                total,
                "VEC3",
                ""","min":[${half0.baseX},0,0],"max":[${half1.baseX + HalfWidth},$HalfHeight,0]""",
            )
            val normal = accessor(buffer.view(floats(*(half0.normals + half1.normals))), 5126, total, "VEC3")
            val joint = accessor(buffer.view(half0.joints + half1.joints), 5121, total, "VEC4")
            val weight = accessor(buffer.view(floats(*(half0.weights + half1.weights))), 5126, total, "VEC4")
            val target0 = targetAccessor(half0.delta + half1.zero, total)
            val target1 = targetAccessor(half0.zero + half1.delta, total)
            val indicesUint = accessor(
                buffer.view(ByteBuffer.allocate(total * 4).order(ByteOrder.LITTLE_ENDIAN).apply { for (i in 0 until total) putInt(i) }.array()),
                5125,
                total,
                "SCALAR",
            )
            val primitive = """{"attributes":{"POSITION":$position,"NORMAL":$normal,"JOINTS_0":$joint,"WEIGHTS_0":$weight},"indices":$indicesUint,"material":0,"targets":[{"POSITION":$target0},{"POSITION":$target1}]}"""
            """{"name":"halves","primitives":[$primitive],"weights":[0,0]}"""
        }

        return buffer.glb("""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
             "nodes":[{"name":"body","mesh":0,"skin":0},{"name":"joint"}],
             "skins":[{"joints":[1],"inverseBindMatrices":$inverseBind}],
             "meshes":[$meshJson],
             "materials":[{"doubleSided":true,"pbrMetallicRoughness":{"baseColorFactor":[0.8,0.3,0.2,1],"metallicFactor":0,"roughnessFactor":0.8}}],
             "animations":[{"name":"$Move","samplers":[{"input":$times,"output":$weightsOutput,"interpolation":"LINEAR"}],"channels":[{"sampler":0,"target":{"node":0,"path":"weights"}}]}],
             "accessors":[${accessors.joinToString(",")}]}
        """.trimIndent().replace("\n", ""))
    }
}
