package gg.hideouthq.library.compat

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32

internal object SyntheticModels {
    private val guid = ByteArray(16) { (it * 5).toByte() }

    fun float(v: Double) = Kv3.Number(Kv3Type.Float, java.lang.Float.floatToRawIntBits(v.toFloat()).toLong() and 0xFFFFFFFFL)

    fun double(v: Double) = Kv3.Number(Kv3Type.Double, java.lang.Double.doubleToRawLongBits(v))

    fun floats(vararg v: Double): Kv3.Typed = Kv3.Typed(Kv3Type.ArrayAuxiliary, Kv3Type.Float, 0, v.map(::float))

    fun doubles(vararg v: Double): Kv3.Typed = Kv3.Typed(Kv3Type.ArrayAuxiliary, Kv3Type.Double, 0, v.map(::double))

    fun strings(vararg v: String, flag: Int = 0): Kv3.Typed = Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.String, flag, v.map { Kv3.Str(it, flag) })

    fun obj(vararg members: Pair<String, Kv3>) = Kv3.Obj(members.toList())

    fun attachment(
        name: String,
        rotation: DoubleArray,
        offset: DoubleArray,
        influence: String = "root_motion",
        count: Int = 1,
        weight: Double = 1.0,
        rootTransform: Boolean = false,
        key: String = name,
        second: String = "",
    ): Kv3.Obj {
        val identity = floats(0.0, 0.0, 0.0, 1.0)
        val zero = floats(0.0, 0.0, 0.0)
        val flags = if (rootTransform) Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.Boolean, 0, listOf(Kv3.Bool(Kv3Type.Boolean, true), Kv3.Bool(Kv3Type.Boolean, false), Kv3.Bool(Kv3Type.Boolean, false)))
            else Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.False, 0, List(3) { Kv3.Bool(Kv3Type.False, false) })
        val value = obj(
            "m_name" to Kv3.Str(name),
            "m_influenceNames" to strings(influence, second, ""),
            "m_vInfluenceRotations" to Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.ArrayAuxiliary, 0, listOf(floats(*rotation), identity, identity)),
            "m_vInfluenceOffsets" to Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.ArrayAuxiliary, 0, listOf(floats(*offset), zero, zero)),
            "m_influenceWeights" to floats(weight, 1.0 - weight, 0.0),
            "m_bInfluenceRootTransform" to flags,
            "m_nInfluences" to if (count == 1) Kv3.Number(Kv3Type.Int64One, 1) else Kv3.Number(Kv3Type.Int32, count.toLong()),
            "m_bIgnoreRotation" to Kv3.Bool(Kv3Type.False, false),
        )
        return obj("key" to Kv3.Str(key), "value" to value)
    }

    fun mesh(attachments: List<Kv3.Obj>?): Kv3.Obj = Kv3.Obj(
        listOf("_class" to Kv3.Str("CRenderMesh"), "m_sceneObjects" to Kv3.List(emptyList())) +
            (attachments?.let { listOf("m_attachments" to Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.Object, 0, it)) } ?: emptyList()),
    )

    val OldPivot = doubleArrayOf(-0.5, -0.5, -0.5, 0.5)
    val Identity = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    val Root = doubleArrayOf(0.5, 0.5, 0.5, 0.5)

    fun data(root: DoubleArray = Root, keyValues: String? = null, refMeshes: List<Kv3> = emptyList(), scale: Double = 1.0, rootName: String = "root_motion"): Kv3.Obj = obj(
        "m_name" to Kv3.Str("models/heroes/test/test.vmdl"),
        "m_modelInfo" to Kv3.Obj(listOf("m_nFlags" to Kv3.Number(Kv3Type.UInt32, 8389632)) + (keyValues?.let { listOf("m_keyValueText" to Kv3.Str(it)) } ?: emptyList())),
        "m_refMeshes" to Kv3.List(refMeshes),
        "m_modelSkeleton" to obj(
            "m_boneName" to strings(rootName, "pelvis"),
            "m_nParent" to Kv3.Typed(Kv3Type.ArrayAuxiliary, Kv3Type.Int32, 0, listOf(Kv3.Number(Kv3Type.Int32, -1), Kv3.Number(Kv3Type.Int32, 0))),
            "m_bonePosParent" to Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.ArrayAuxiliary, 0, listOf(doubles(0.0, -0.0, -0.0), doubles(0.0, 47.5, -6.2))),
            "m_boneRotParent" to Kv3.Typed(Kv3Type.ArrayByteLength, Kv3Type.ArrayAuxiliary, 0, listOf(doubles(*root), doubles(*Root))),
            "m_boneScaleParent" to doubles(scale, 1.0),
        ),
    )

    fun block(root: Kv3, version: Int = 5, compression: Int = 1): ByteArray =
        Kv3Binary.encode(Kv3Document(version, guid, compression, emptyList(), root, Kv3Hints(0, 0, 0, 0, 0)))

    fun model(meshes: List<Kv3.Obj>, data: Kv3.Obj = data()): ByteArray =
        resource(listOf("MVTX" to ByteArray(41) { (it * 13).toByte() }) + meshes.map { "MDAT" to block(it) } + listOf("RED2" to block(obj("m_Note" to Kv3.Str("edit info"))), "DATA" to block(data)))

    fun table(type: String): ByteArray = resource(listOf("DATA" to block(obj("generic_data_type" to Kv3.Str(type), "footstep" to obj("m_soundEvent" to Kv3.Str("Test.Footstep", flag = 4))))))

    fun resource(blocks: List<Pair<String, ByteArray>>): ByteArray {
        val table = 16
        var at = table + 12 * blocks.size
        val offsets = blocks.map { (_, payload) -> ((at + 15) / 16 * 16).also { at = it + payload.size } }
        val size = (at + 15) / 16 * 16
        val out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0, size).putShort(4, 12).putShort(6, 1).putInt(8, 8).putInt(12, blocks.size)
        blocks.forEachIndexed { index, (type, payload) ->
            val entry = table + 12 * index
            out.position(entry)
            out.put(type.encodeToByteArray())
            out.putInt(entry + 4, offsets[index] - (entry + 4)).putInt(entry + 8, payload.size)
            out.position(offsets[index])
            out.put(payload)
        }
        return out.array()
    }

    class VpkFile(val path: String, val bytes: ByteArray, val chunk: Int? = null, val preload: Int = 0)

    fun vpk(files: List<VpkFile>, md5: Boolean = true, archiveHashes: ByteArray = ByteArray(0), signature: ByteArray = ByteArray(0), declaredSignature: Int = signature.size): Pair<ByteArray, Map<Int, ByteArray>> {
        fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val tree = ByteArrayOutputStream()
        val data = ByteArrayOutputStream()
        val chunks = sortedMapOf<Int, ByteArrayOutputStream>()
        fun cstr(s: String) {
            tree.write(s.encodeToByteArray())
            tree.write(0)
        }
        for ((extension, byExtension) in files.groupBy { it.path.substringAfterLast('.') }) {
            cstr(extension)
            for ((folder, byFolder) in byExtension.groupBy { it.path.substringBeforeLast('/') }) {
                cstr(folder)
                for (file in byFolder) {
                    cstr(file.path.substringAfterLast('/').substringBeforeLast('.'))
                    val sink = file.chunk?.let { chunks.getOrPut(it) { ByteArrayOutputStream() } } ?: data
                    tree.write(le(18).putInt(CRC32().apply { update(file.bytes) }.value.toInt()).putShort(file.preload.toShort()).putShort((file.chunk ?: 0x7FFF).toShort())
                        .putInt(sink.size()).putInt(file.bytes.size - file.preload).putShort(-1).array())
                    tree.write(file.bytes, 0, file.preload)
                    sink.write(file.bytes, file.preload, file.bytes.size - file.preload)
                }
                tree.write(0)
            }
            tree.write(0)
        }
        tree.write(0)
        val treeBytes = tree.toByteArray()
        val header = le(28).putInt(0x55AA1234).putInt(2).putInt(treeBytes.size).putInt(data.size()).putInt(archiveHashes.size).putInt(if (md5) 48 else 0).putInt(declaredSignature).array()
        val body = header + treeBytes + data.toByteArray() + archiveHashes
        val digest = { bytes: ByteArray -> MessageDigest.getInstance("MD5").digest(bytes) }
        val checksums = if (md5) (digest(treeBytes) + digest(archiveHashes)).let { it + digest(body + it) } else ByteArray(0)
        return body + checksums + signature to chunks.mapValues { it.value.toByteArray() }
    }

    fun keyValues(body: String) = "<!-- kv3 encoding:text:version{e21c7f3c-8a33-41c5-9977-a76d3a32aa0d} format:vmdlkeys4:version{9f5c1002-f19f-470c-bd42-c3c26c063d33} -->\n{\n$body\n}\n"
}
