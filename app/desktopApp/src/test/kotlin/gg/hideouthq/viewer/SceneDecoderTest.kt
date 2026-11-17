package gg.hideouthq.viewer

import gg.hideouthq.data.preview.PreviewFileException
import gg.hideouthq.preview.AssetEncoding
import gg.hideouthq.preview.MAX_EXPANDED_MODEL_BYTES
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.sha256Hex
import com.github.luben.zstd.Zstd
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.*

class SceneDecoderTest {
    @Test
    fun aModelMustBeZstdThatMatchesItsDecodedIdentity(): Unit = runBlocking {
        val glb = texturedGlb()
        val packed = Zstd.compress(glb, 6)
        val asset = PreviewAsset("model.glb.zst", packed.size.toLong(), sha256Hex(packed), "model/gltf-binary",
            AssetEncoding("zstd", glb.size.toLong(), sha256Hex(glb)))
        suspend fun decode(stored: ByteArray, model: PreviewAsset = asset) = SceneDecoder.decode(stored.inputStream(), model, emptyList())
        assertContentEquals(readModel(glb).glb, decode(packed).glb)
        assertFailsWith<IllegalArgumentException> { decode(packed, asset.copy(encoding = asset.encoding!!.copy(decodedSha256 = "0".repeat(64)))) }
        assertFailsWith<IllegalArgumentException> { decode(packed, asset.copy(encoding = asset.encoding!!.copy(decodedBytes = Long.MAX_VALUE))) }
        assertFailsWith<PreviewFileException> { decode(packed, asset.copy(encoding = asset.encoding!!.copy(decodedBytes = glb.size + 4L))) }
        assertFails { decode(packed.copyOf(packed.size / 2)) }
        assertFails { decode(Zstd.compress(glb + ByteArray(4), 6)) }
        val plain = PreviewAsset("model.glb", glb.size.toLong(), sha256Hex(glb), "model/gltf-binary")
        assertEquals("The model is not zstd-encoded", assertFailsWith<IllegalArgumentException> { decode(glb, plain) }.message)
    }

    @Test
    fun packedTexturesAreReadInPlaceAfterTheMeshesAndTheirViewsDropped() {
        val red = TestKtx2.solid(8, 8, TestKtx2.Red)
        val blue = TestKtx2.solid(4, 2, TestKtx2.Blue)
        val model = readModel(texturedGlb(red, blue, shared = true))
        assertEquals(listOf(2, 3, 2), model.textures.images.map { it.view })
        for ((image, bytes) in model.textures.images.zip(listOf(red, blue, red))) {
            assertContentEquals(bytes, model.textures.bytes.copyOfRange(image.offset, image.offset + image.length))
        }
        assertEquals(((red.size + 3) and -4) + ((blue.size + 3) and -4), model.textures.bytes.size, "the textures are the BIN chunk after the meshes")
        val document = gg.hideouthq.preview.PreviewJson.parseToJsonElement(glbChunks(model.glb).json).jsonObject
        assertEquals(2, document["bufferViews"]!!.jsonArray.size)
        assertEquals(List(3) { JsonObject(mapOf("mimeType" to JsonPrimitive("image/ktx2"))) }, document["images"]!!.jsonArray.toList())
        assertEquals(48L, glbChunks(model.glb).binaryBytes, "the expanded binary holds the meshes alone")
        val texture = Ktx2Texture.parse("blue", model.textures.bytes, model.textures.images[1].offset, model.textures.images[1].length)
        assertEquals(4 to 2, texture.width to texture.height)
    }

    @Test
    fun aModelWhoseImagesAreNotPackedIsRefusedBeforeItsBinaryIsRead() {
        val red = TestKtx2.solid(4, 4, TestKtx2.Red)
        for (images in listOf("""[{"uri":"textures/body.ktx2","mimeType":"image/ktx2"}]""", """[{"bufferView":0,"mimeType":"image/ktx2"}]""")) {
            val failure = assertFailsWith<PreviewFileException>(images) { readModel(texturedGlb(red, images = images)) }
            assertTrue("not packed" in failure.message.orEmpty(), failure.message)
        }
        assertFails { readModel(texturedGlb(red).let { it.copyOf(it.size - 4) }) }
    }

    private fun texturedGlb(vararg textures: ByteArray, shared: Boolean = false, images: String? = null): ByteArray {
        val buffer = GlbBuffer()
        val positions = buffer.view(floats(0f, 0f, 0f, 1f, 0f, 0f))
        buffer.view(floats(0f, 0f, 1f, 0f, 0f, 1f))
        val views = textures.map(buffer::view)
        return buffer.glb("""{"asset":{"version":"2.0"},
            "accessors":[{"bufferView":$positions,"componentType":5126,"count":2,"type":"VEC3","min":[0,0,0],"max":[1,0,0]}],
            "images":${images ?: packedImages(views + listOfNotNull(views.firstOrNull()?.takeIf { shared }))}}""")
    }

    private fun compressed(offset: Int = 0, count: Int = 3, stride: Int = 4, filter: String = "NONE"): ByteArray {
        val binary = Base64.getDecoder().decode("oAEYAAAAATgAAAD+AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==")
        val json = """{"asset":{"version":"2.0"},"extensionsRequired":["EXT_meshopt_compression","KHR_mesh_quantization"],
            "buffers":[{"byteLength":${binary.size}},{"byteLength":12}],
            "bufferViews":[{"buffer":1,"byteLength":12,"byteStride":4,"extensions":{"EXT_meshopt_compression":{"buffer":0,"byteOffset":$offset,"byteLength":${binary.size},"count":$count,"byteStride":$stride,"mode":"ATTRIBUTES","filter":"$filter"}}}],
            "accessors":[{"bufferView":0,"componentType":5122,"normalized":true,"count":3,"type":"SCALAR","min":[-32768],"max":[32767]}]}""".trimIndent()
        return glbOf(json, binary)
    }

    @Test
    fun meshoptViewsExpandAndSignedNormalizedAccessorsBecomeFloats() = runBlocking {
        val decoded = readModel(compressed()).glb
        val document = gg.hideouthq.preview.PreviewJson.parseToJsonElement(glbChunks(decoded).json).jsonObject
        assertEquals(emptyList(), document["extensionsRequired"]!!.jsonArray.toList())
        val accessor = document["accessors"]!!.jsonArray[0].jsonObject
        assertEquals(5126, accessor["componentType"]!!.jsonPrimitive.int)
        assertEquals(-1f, accessor["min"]!!.jsonArray[0].jsonPrimitive.float)
        assertEquals(1f, accessor["max"]!!.jsonArray[0].jsonPrimitive.float)
        val view = document["bufferViews"]!!.jsonArray[accessor["bufferView"]!!.jsonPrimitive.int].jsonObject
        val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        val at = glbChunks(decoded).binaryAt + view["byteOffset"]!!.jsonPrimitive.int
        assertEquals(listOf(0f, 1f, -1f), List(3) { buffer.getFloat(at + it * 4) })
        assertEquals(1, document["buffers"]!!.jsonArray.size)
    }

    @Test
    fun constantAnimationsKeepTheirAuthoredDurationAndValues() = runBlocking {
        val json = """{"asset":{"version":"2.0"},"extensionsUsed":["KHR_mesh_quantization"],
            "buffers":[{"byteLength":16}],
            "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":4},{"buffer":0,"byteOffset":4,"byteLength":12}],
            "accessors":[{"bufferView":0,"componentType":5126,"count":1,"type":"SCALAR","min":[0],"max":[0]},
                         {"bufferView":1,"componentType":5126,"count":1,"type":"VEC3"}],
            "animations":[{"name":"idle","samplers":[{"input":0,"output":1,"interpolation":"LINEAR"}],
                           "channels":[{"sampler":0,"target":{"node":0,"path":"translation"}}]}],
            "nodes":[{}]}""".trimIndent()
        val binary = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply {
            listOf(0f, 1f, 2f, 3f).forEach(::putFloat)
        }.array()
        val decoded = readModel(glbOf(json, binary), listOf(gg.hideouthq.preview.PreviewClip("idle", 750))).glb
        val document = gg.hideouthq.preview.PreviewJson.parseToJsonElement(glbChunks(decoded).json).jsonObject
        val sampler = document["animations"]!!.jsonArray.single().jsonObject["samplers"]!!.jsonArray.single().jsonObject
        fun values(key: String): List<Float> {
            val accessor = document["accessors"]!!.jsonArray[sampler[key]!!.jsonPrimitive.int].jsonObject
            assertEquals(2, accessor["count"]!!.jsonPrimitive.int)
            val view = document["bufferViews"]!!.jsonArray[accessor["bufferView"]!!.jsonPrimitive.int].jsonObject
            val buffer = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
            val start = glbChunks(decoded).binaryAt + view["byteOffset"]!!.jsonPrimitive.int
            return List(view["byteLength"]!!.jsonPrimitive.int / 4) { buffer.getFloat(start + it * 4) }
        }
        assertEquals(listOf(0f, 0.75f), values("input"))
        assertEquals(listOf(1f, 2f, 3f, 1f, 2f, 3f), values("output"))
    }

    @Test
    fun theExpandedModelLimitIsCheckedBeforeAnyValueIsRead() = runBlocking {
        suspend fun refusal(count: Long): String? {
            val json = """{"asset":{"version":"2.0"},"extensionsRequired":["KHR_mesh_quantization"],
                "buffers":[{"byteLength":4}],"bufferViews":[{"buffer":0,"byteLength":4}],
                "accessors":[{"bufferView":0,"componentType":5121,"count":$count,"type":"SCALAR"}]}"""
            return runCatching { SceneDecoder.read(glbOf(json, ByteArray(4)).inputStream(), "model.glb.zst", emptyList()) }.exceptionOrNull().let { assertIs<IllegalArgumentException>(it).message }
        }
        val fits = MAX_EXPANDED_MODEL_BYTES / 4 - 1
        assertEquals("Accessor outside its buffer", refusal(fits))
        assertEquals("Expanded model is too large", refusal(fits + 1))
    }

    @Test
    fun invalidNativeRangesAndFiltersAreRefusedBeforeDecoding() = runBlocking {
        for (bytes in listOf(compressed(offset = Int.MAX_VALUE), compressed(count = Int.MAX_VALUE), compressed(stride = 3), compressed(filter = "QUATERNION"))) {
            assertFailsWith<IllegalArgumentException> { SceneDecoder.read(bytes.inputStream(), "model.glb.zst", emptyList()) }
        }
    }
}
