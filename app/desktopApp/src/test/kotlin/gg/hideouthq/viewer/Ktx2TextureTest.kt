package gg.hideouthq.viewer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.utils.BufferUtils
import gg.hideouthq.preview.PreviewJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.mgsx.gltf.data.texture.GLTFTextureInfo
import net.mgsx.gltf.loaders.glb.BinaryDataFileResolver
import org.junit.Rule
import org.junit.rules.Timeout
import org.lwjgl.opengl.GL11
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Ktx2TextureTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val listing = PreviewJson.parseToJsonElement(Files.readString(TestKtx2.cases.resolve("cases.json"))).jsonObject

    private fun names(key: String) = listing.getValue(key).jsonArray.map { it.jsonPrimitive.content }

    private fun case(name: String) = Ktx2Texture.parse("$name.ktx2", TestKtx2.case(name))

    @Test
    fun theReaderAcceptsExactlyTheCasesThePipelinesReaderAccepts() {
        assertEquals(Accepted.keys, names("accept").toSet())
        for ((name, expected) in Accepted) {
            val texture = case(name)
            assertEquals(name, "${texture.width}x${texture.height}")
            assertEquals(expected.gpuBytes, texture.gpuBytes(), name)
        }
        for (name in names("reject")) {
            val failure = assertFailsWith<UnsupportedScene>(name) { case(name) }
            assertTrue("$name.ktx2" in failure.message.orEmpty(), failure.message)
        }
    }

    @Test
    fun aTextureIsReadInPlaceInTheModelsPackedBytes() {
        val stored = TestKtx2.solid(8, 4, TestKtx2.Blue)
        val packed = ByteArray(7) { 1 } + stored + ByteArray(5) { 2 }
        val texture = Ktx2Texture.parse("image view 3", packed, 7, stored.size)
        assertEquals(Ktx2Texture.parse("alone", stored).gpuBytes(), texture.gpuBytes())
        assertEquals(8 to 4, texture.width to texture.height)
        val failure = assertFailsWith<UnsupportedScene> { Ktx2Texture.parse("image view 3", packed, 7, stored.size + 1) }
        assertTrue("image view 3 is not a viewer texture: bytes follow the largest level" in failure.message.orEmpty(), failure.message)
        assertFailsWith<UnsupportedScene> { Ktx2Texture.parse("image view 3", packed, 6, stored.size) }
        assertFailsWith<IllegalArgumentException> { Ktx2Texture.parse("image view 3", packed, 7, packed.size) }
    }

    @Test
    fun aSceneTakesThePipelinesWholeBudgetButNotMoreThanTheCap() {
        val gl = GlInfo("test", "test", 4, 6, 32, 16384, 16384, bc7 = true)
        val largest = Ktx2Texture.parse("body.ktx2", TestKtx2.solid(4096, 4096, TestKtx2.Red))
        assertNull(SceneLimits.texturesProblem(List(4) { largest }, gl), "The pipeline's budget of 64 Mi texels")
        assertNull(SceneLimits.texturesProblem(List(5) { largest }, gl))
        assertNotNull(SceneLimits.texturesProblem(List(6) { largest }, gl), "Six 4096x4096 chains are 160 bytes over 128 MiB")
    }

    @Test
    fun everyAcceptedCaseUploadsWithItsChainWhereTheSamplerMipmapsAndSamplesAsStored() = runBlocking {
        TestGl.info()
        for ((name, expected) in Accepted) {
            val texture = case(name)
            val (mipmapped, simple, error) = TestGl.thread.run {
                val textures = SceneTextures(model(), listOf(texture, texture))
                try {
                    Triple(levels(textures.texture(0)), levels(textures.texture(1)), Gdx.gl.glGetError())
                } finally {
                    textures.dispose()
                }
            }
            val chain = (0 until 32 - Integer.numberOfLeadingZeros(maxOf(texture.width, texture.height))).map { "${maxOf(1, texture.width shr it)}x${maxOf(1, texture.height shr it)}" }
            assertEquals(chain, mipmapped.map { it.size }, "$name: a mipmapped texture holds every level")
            assertEquals(chain.take(1), simple.map { it.size }, "$name: a texture without mipmaps holds its largest level alone")
            for (level in mipmapped + simple) assertEquals(setOf(expected.colour), level.texels, "$name ${level.size}")
            assertEquals(GL20.GL_NO_ERROR, error, name)
        }
    }

    @Test
    fun anImageWithoutAViewerTextureOrALevelThatDoesNotExpandFailsTheLoad() = runBlocking {
        TestGl.info()
        val good = case("5x3")
        val stored = TestKtx2.solid(8, 8, TestKtx2.Red)
        val index = ByteBuffer.wrap(stored).order(ByteOrder.LITTLE_ENDIAN)
        val end = index.getLong(80).toInt() + index.getLong(88).toInt()
        val cut = stored.copyOf(end - 2).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putLong(88, index.getLong(88) - 2) }
        val truncated = Ktx2Texture.parse("cut.ktx2", cut)
        for ((failing, textures) in listOf("image 1" to listOf(good), "cut.ktx2" to listOf(good, truncated))) {
            val failure = assertFailsWith<UnsupportedScene>(failing) { TestGl.thread.run { SceneTextures(model(), textures) } }
            assertTrue(failing in failure.message.orEmpty(), failure.message)
        }
        assertEquals(GL20.GL_NO_ERROR, TestGl.thread.run { Gdx.gl.glGetError() })
    }

    private class Level(val size: String, val texels: Set<TestKtx2.Rgba>)

    private fun model() = BinaryDataFileResolver().apply {
        load(glbOf("""{"asset":{"version":"2.0"},"images":[{"mimeType":"image/ktx2"},{"mimeType":"image/ktx2"}],"samplers":[{"minFilter":9987}],"textures":[{"source":0,"sampler":0},{"source":1}]}""", ByteArray(0)))
    }.root

    private fun SceneTextures.texture(index: Int): Texture = getTexture(GLTFTextureInfo().also { it.index = index }).texture

    private fun levels(texture: Texture): List<Level> {
        texture.bind()
        return generateSequence(0) { it + 1 }.map { level ->
            val width = GL11.glGetTexLevelParameteri(GL20.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH)
            val height = GL11.glGetTexLevelParameteri(GL20.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT)
            if (width == 0) return@map null
            val pixels = BufferUtils.newByteBuffer(width * height * 4)
            GL11.glGetTexImage(GL20.GL_TEXTURE_2D, level, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels)
            val texels = (0 until width * height).map { i -> IntArray(4) { pixels.get(i * 4 + it).toInt() and 255 }.let { TestKtx2.Rgba(it[0], it[1], it[2], it[3]) } }
            Level("${width}x$height", texels.toSet())
        }.takeWhile { it != null }.filterNotNull().toList()
    }

    private class Case(val colour: TestKtx2.Rgba, val gpuBytes: Long)

    private companion object {
        val Accepted = mapOf(
            "1x1" to Case(TestKtx2.Rgba(0, 255, 0, 255), 16),
            "4x4" to Case(TestKtx2.Rgba(255, 255, 255, 0), 3 * 16),
            "5x3" to Case(TestKtx2.Rgba(255, 0, 0, 255), (2 + 1 + 1) * 16),
            "8x1" to Case(TestKtx2.Rgba(0, 0, 255, 128), (2 + 1 + 1 + 1) * 16),
            "64x32" to Case(TestKtx2.Rgba(255, 0, 255, 255), (128 + 32 + 8 + 2 + 1 + 1 + 1) * 16),
        )
    }
}
