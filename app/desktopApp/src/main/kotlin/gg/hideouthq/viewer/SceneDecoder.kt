package gg.hideouthq.viewer

import gg.hideouthq.data.preview.PreviewFileException
import gg.hideouthq.library.sha256
import gg.hideouthq.preview.MAX_DECODED_MODEL_BYTES
import gg.hideouthq.preview.MAX_EXPANDED_MODEL_BYTES
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewJson
import gg.hideouthq.preview.PreviewScene
import gg.hideouthq.preview.packedImageProblems
import com.github.luben.zstd.ZstdInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.meshoptimizer.MeshOptimizer
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.util.HexFormat

class DecodedModel(val glb: ByteArray, val textures: PackedTextures)

class PackedTextures(val bytes: ByteArray, val images: List<PackedImage>) {
    companion object {
        val None = PackedTextures(ByteArray(0), emptyList())
    }
}

class PackedImage(val view: Int, val offset: Int, val length: Int)

object SceneDecoder {
    val extensions = setOf("EXT_meshopt_compression", "KHR_mesh_quantization")

    fun runtimeScene(scene: PreviewScene) = scene.copy(extensionsRequired = scene.extensionsRequired.filterNot(extensions::contains))

    suspend fun decode(stored: InputStream, asset: PreviewAsset, clips: List<PreviewClip>): DecodedModel {
        currentCoroutineContext().ensureActive()
        val encoding = requireNotNull(asset.encoding) { "The model is not zstd-encoded" }
        require(encoding.codec == "zstd" && encoding.decodedBytes in 1..MAX_DECODED_MODEL_BYTES) { "Invalid model encoding" }
        val digest = sha256()
        return ZstdInputStream(object : FilterInputStream(stored) { override fun close() = Unit }).use { zstd ->
            val decoded = DigestInputStream(zstd, digest)
            val model = read(decoded, asset.path, clips, encoding.decodedBytes)
            require(decoded.read() == -1 && HexFormat.of().formatHex(digest.digest()) == encoding.decodedSha256) { "Decoded model checksum mismatch" }
            model
        }
    }

    suspend fun read(decoded: InputStream, modelPath: String, clips: List<PreviewClip>, size: Long? = null): DecodedModel {
        currentCoroutineContext().ensureActive()
        val header = ByteBuffer.wrap(decoded.exactly(GlbHeader + ChunkHeader)).order(ByteOrder.LITTLE_ENDIAN)
        val length = header.getInt(8).toUInt().toLong()
        val jsonBytes = header.getInt(GlbHeader).toUInt().toLong()
        if (header.getInt(0) != GlbMagic || header.getInt(4) != 2 || size != null && length != size || length > MAX_DECODED_MODEL_BYTES) {
            throw PreviewFileException("Not a glTF 2 binary of its stated size")
        }
        if (header.getInt(GlbHeader + 4) != JsonChunk || jsonBytes % 4 != 0L || GlbHeader + ChunkHeader + jsonBytes > length) {
            throw PreviewFileException("The glTF binary has no 4-byte aligned JSON chunk first")
        }
        val json = decoded.exactly(jsonBytes.toInt()).decodeToString(throwOnInvalidSequence = true)
        val binaryBytes = if (GlbHeader + ChunkHeader + jsonBytes == length) 0L else {
            val chunk = ByteBuffer.wrap(decoded.exactly(ChunkHeader)).order(ByteOrder.LITTLE_ENDIAN)
            val bytes = chunk.getInt(0).toUInt().toLong()
            if (chunk.getInt(4) != BinChunk || bytes % 4 != 0L || GlbHeader + 2 * ChunkHeader + jsonBytes + bytes != length) {
                throw PreviewFileException("The glTF binary has something other than one 4-byte aligned BIN chunk after its JSON chunk")
            }
            bytes
        }
        packedImageProblems(modelPath, json).takeIf { it.isNotEmpty() }?.let { throw PreviewFileException("The model's images are not packed in it: ${it.joinToString("; ")}") }
        val document = PreviewJson.parseToJsonElement(json).jsonObject
        val views = document.array("bufferViews").map { it.jsonObject }
        val imageViews = document.array("images").map { it.jsonObject.number("bufferView") }
        val firstImage = imageViews.minOrNull() ?: views.size
        val texturesAt = views.drop(firstImage).minOfOrNull { it.number("byteOffset", 0).toLong() } ?: binaryBytes
        if (texturesAt > binaryBytes || views.drop(firstImage).any { it.number("byteOffset", 0).toLong() + it.number("byteLength") > binaryBytes }) {
            throw PreviewFileException("The model's packed images lie outside its BIN chunk")
        }
        val glb = expand(decoded.exactly(texturesAt.toInt()), document, firstImage, clips)
        currentCoroutineContext().ensureActive()
        val textures = decoded.exactly((binaryBytes - texturesAt).toInt())
        val images = imageViews.map { view -> PackedImage(view, (views[view].number("byteOffset", 0) - texturesAt).toInt(), views[view].number("byteLength")) }
        return DecodedModel(glb, if (images.isEmpty()) PackedTextures.None else PackedTextures(textures, images))
    }

    private suspend fun expand(binary: ByteArray, document: JsonObject, keptViews: Int, clips: List<PreviewClip>): ByteArray {
        val binaryLength = binary.size
        var binarySize = 0L
        val writes = mutableListOf<(ByteBuffer, Int) -> Unit>()
        fun place(length: Long, write: (out: ByteBuffer, at: Int, binary: Int) -> Unit): Int {
            val at = (binarySize + 3) and -4L
            require(at + length <= MAX_EXPANDED_MODEL_BYTES) { "Expanded model is too large" }
            binarySize = at + length
            writes += { out, binary -> write(out, binary + at.toInt(), binary) }
            return at.toInt()
        }
        val views = document.array("bufferViews").take(keptViews).map { element ->
            currentCoroutineContext().ensureActive()
            val view = element.jsonObject
            val ext = view["extensions"]?.jsonObject?.get("EXT_meshopt_compression")?.jsonObject
            val at = if (ext == null) {
                require(view.number("buffer") == 0) { "External model buffer" }
                val offset = view.number("byteOffset", 0)
                val length = view.number("byteLength")
                require(offset.toLong() + length <= binaryLength) { "Model buffer range is invalid" }
                place(length.toLong()) { out, at, _ -> System.arraycopy(binary, offset, out.array(), at, length) }
            } else {
                require(ext.number("buffer") == 0) { "External compressed buffer" }
                val offset = ext.number("byteOffset", 0)
                val length = ext.number("byteLength")
                val count = ext.number("count")
                val stride = ext.number("byteStride")
                val mode = ext["mode"]!!.jsonPrimitive.content
                val filter = ext["filter"]?.jsonPrimitive?.content ?: "NONE"
                require(offset.toLong() + length <= binaryLength && count > 0) { "Invalid compressed range" }
                require(view.number("byteLength").toLong() == count.toLong() * stride) { "Compressed buffer length mismatch" }
                require(when (mode) {
                    "ATTRIBUTES" -> stride in 4..256 && stride % 4 == 0
                    "TRIANGLES" -> stride in setOf(2, 4) && count % 3 == 0
                    "INDICES" -> stride in setOf(2, 4)
                    else -> false
                }) { "Invalid Meshopt mode or stride" }
                require(filter == "NONE" || mode == "ATTRIBUTES" && when (filter) {
                    "OCTAHEDRAL" -> stride == 4 || stride == 8
                    "QUATERNION" -> stride == 8
                    "EXPONENTIAL" -> stride % 4 == 0
                    else -> false
                }) { "Invalid Meshopt filter" }
                place(count.toLong() * stride) { out, at, _ ->
                    val input = MemoryUtil.memAlloc(length)
                    try {
                        input.put(binary, offset, length).flip()
                        val output = MemoryUtil.memAlloc(count * stride)
                        try {
                            val status = when (mode) {
                                "ATTRIBUTES" -> MeshOptimizer.meshopt_decodeVertexBuffer(output, count.toLong(), stride.toLong(), input)
                                "TRIANGLES" -> MeshOptimizer.meshopt_decodeIndexBuffer(output, count.toLong(), stride.toLong(), input)
                                else -> MeshOptimizer.meshopt_decodeIndexSequence(output, count.toLong(), stride.toLong(), input)
                            }
                            require(status == 0) { "Meshopt decode failed" }
                            when (filter) {
                                "OCTAHEDRAL" -> MeshOptimizer.meshopt_decodeFilterOct(output, count.toLong(), stride.toLong())
                                "QUATERNION" -> MeshOptimizer.meshopt_decodeFilterQuat(output, count.toLong(), stride.toLong())
                                "EXPONENTIAL" -> MeshOptimizer.meshopt_decodeFilterExp(output, count.toLong(), stride.toLong())
                            }
                            output.get(out.array(), at, count * stride)
                        } finally {
                            MemoryUtil.memFree(output)
                        }
                    } finally {
                        MemoryUtil.memFree(input)
                    }
                }
            }
            JsonObject(view.toMutableMap().apply {
                put("buffer", JsonPrimitive(0))
                put("byteOffset", JsonPrimitive(at))
                remove("extensions")
            })
        }.toMutableList()
        val integerAccessors = mutableSetOf<Int>()
        for (mesh in document.array("meshes")) for (primitive in mesh.jsonObject.array("primitives")) {
            primitive.jsonObject["indices"]?.let { integerAccessors += it.jsonPrimitive.int }
            primitive.jsonObject["attributes"]?.jsonObject?.forEach { (name, accessor) ->
                if (name.startsWith("JOINTS_")) integerAccessors += accessor.jsonPrimitive.int
            }
        }
        val accessors = document.array("accessors").mapIndexed { index, element ->
            currentCoroutineContext().ensureActive()
            val accessor = element.jsonObject
            if (accessor.number("componentType") == 5126 || index in integerAccessors) accessor else {
                require("sparse" !in accessor) { "Sparse quantized accessor" }
                views += floats(accessor, views, 1, ::place)
                JsonObject(accessor.toMutableMap().apply {
                    put("bufferView", JsonPrimitive(views.lastIndex)); put("byteOffset", JsonPrimitive(0)); put("componentType", JsonPrimitive(5126))
                    remove("normalized")
                    for (bound in listOf("min", "max")) accessor[bound]?.jsonArray?.let { numbers ->
                        put(bound, JsonArray(numbers.map { JsonPrimitive(component(it.jsonPrimitive.double, accessor)) }))
                    }
                })
            }
        }.toMutableList()
        val durations = clips.associate { it.id to it.durationMillis / 1000f }
        val animations = document.array("animations").map { element ->
            val animation = element.jsonObject
            val duration = durations[animation["name"]?.jsonPrimitive?.content] ?: return@map element
            val samplers = animation.array("samplers")
            val end = samplers.maxOfOrNull { sampler -> accessors[sampler.jsonObject.number("input")]["max"]!!.jsonArray[0].jsonPrimitive.float } ?: 0f
            if (end > 0f || duration <= 0f) return@map element
            JsonObject(animation + ("samplers" to JsonArray(samplers.map { samplerElement ->
                val sampler = samplerElement.jsonObject
                val input = accessors[sampler.number("input")]
                val output = accessors[sampler.number("output")]
                require(input.number("count") == 1) { "Invalid constant animation input" }
                val timeOffset = place(8) { out, at, _ -> out.putFloat(at, 0f).putFloat(at + 4, duration) }
                views += buildJsonObject { put("buffer", 0); put("byteOffset", timeOffset); put("byteLength", 8) }
                accessors += buildJsonObject {
                    put("bufferView", views.lastIndex); put("componentType", 5126); put("count", 2); put("type", "SCALAR")
                    put("min", JsonArray(listOf(JsonPrimitive(0f)))); put("max", JsonArray(listOf(JsonPrimitive(duration))))
                }
                val newInput = accessors.lastIndex
                views += floats(output, views, 2, ::place)
                accessors += JsonObject(output.toMutableMap().apply {
                    put("bufferView", JsonPrimitive(views.lastIndex)); put("byteOffset", JsonPrimitive(0))
                    put("componentType", JsonPrimitive(5126)); put("count", JsonPrimitive(output.number("count") * 2)); remove("normalized")
                })
                JsonObject(sampler + mapOf("input" to JsonPrimitive(newInput), "output" to JsonPrimitive(accessors.lastIndex)))
            })))
        }
        val result = document.toMutableMap().apply {
            put("bufferViews", JsonArray(views)); put("accessors", JsonArray(accessors)); put("animations", JsonArray(animations))
            if (binarySize > 0) put("buffers", JsonArray(listOf(buildJsonObject { put("byteLength", binarySize) }))) else remove("buffers")
            if ("images" in this) put("images", JsonArray(document.array("images").map { JsonObject(it.jsonObject - "bufferView") }))
            for (key in listOf("extensionsUsed", "extensionsRequired")) {
                if (key in this) put(key, JsonArray(document.array(key).filterNot { it.jsonPrimitive.content in extensions }))
            }
        }
        val json = JsonObject(result).toString().encodeToByteArray()
        val jsonSize = (json.size + 3) and -4
        val paddedBinary = (binarySize.toInt() + 3) and -4
        val out = ByteBuffer.allocate(20 + jsonSize + if (binarySize > 0) 8 + paddedBinary else 0).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(GlbMagic); putInt(2); putInt(capacity()); putInt(jsonSize); putInt(JsonChunk); put(json)
            while (position() < 20 + jsonSize) put(32.toByte())
            if (binarySize > 0) { putInt(paddedBinary); putInt(BinChunk) }
        }
        for (write in writes) {
            currentCoroutineContext().ensureActive()
            write(out, 28 + jsonSize)
        }
        return out.array()
    }

    private fun floats(accessor: JsonObject, views: List<JsonObject>, copies: Int, place: (Long, (ByteBuffer, Int, Int) -> Unit) -> Int): JsonObject {
        val type = accessor.number("componentType")
        val size = when (type) { 5120, 5121 -> 1; 5122, 5123 -> 2; 5125, 5126 -> 4; else -> error("Invalid component type") }
        val components = when (accessor["type"]!!.jsonPrimitive.content) {
            "SCALAR" -> 1; "VEC2" -> 2; "VEC3" -> 3; "VEC4", "MAT2" -> 4; "MAT3" -> 9; "MAT4" -> 16; else -> error("Invalid accessor type")
        }
        val count = accessor.number("count")
        val view = views[accessor.number("bufferView")]
        val stride = view.number("byteStride", components * size)
        val offset = accessor.number("byteOffset", 0)
        val source = view.number("byteOffset") + offset
        val length = count.toLong() * components * 4
        val at = place(length * copies) { out, at, binary ->
            repeat(count) { row -> repeat(components) { column ->
                val from = binary + source + row * stride + column * size
                val value = when (type) {
                    5120 -> out.get(from).toDouble(); 5121 -> (out.get(from).toInt() and 255).toDouble()
                    5122 -> out.getShort(from).toDouble(); 5123 -> (out.getShort(from).toInt() and 65535).toDouble()
                    5125 -> out.getInt(from).toUInt().toDouble(); else -> out.getFloat(from).toDouble()
                }
                out.putFloat(at + (row * components + column) * 4, component(value, accessor))
            } }
            repeat(copies - 1) { System.arraycopy(out.array(), at, out.array(), at + (it + 1) * length.toInt(), length.toInt()) }
        }
        val end = offset.toLong() + (count - 1L) * stride + components * size
        require(count > 0 && stride >= components * size && end <= view.number("byteLength")) { "Accessor outside its buffer" }
        return buildJsonObject { put("buffer", 0); put("byteOffset", at); put("byteLength", length * copies) }
    }

    private fun component(value: Double, accessor: JsonObject): Float {
        if (accessor["normalized"]?.jsonPrimitive?.booleanOrNull != true) return value.toFloat()
        return when (accessor.number("componentType")) {
            5120 -> (value / 127).coerceAtLeast(-1.0); 5121 -> value / 255
            5122 -> (value / 32767).coerceAtLeast(-1.0); 5123 -> value / 65535
            else -> value
        }.toFloat()
    }

    private fun InputStream.exactly(count: Int): ByteArray = ByteArray(count).also {
        if (readNBytes(it, 0, count) != count) throw EOFException("The model ends early")
    }

    private fun JsonObject.array(key: String) = get(key)?.jsonArray.orEmpty()
    private fun JsonObject.number(key: String, default: Int? = null): Int {
        val value = get(key)?.jsonPrimitive?.intOrNull ?: default ?: error("Missing $key")
        require(value >= 0) { "Negative $key" }
        return value
    }
}
