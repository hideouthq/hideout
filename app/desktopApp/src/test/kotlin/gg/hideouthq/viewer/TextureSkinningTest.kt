package gg.hideouthq.viewer

import gg.hideouthq.core.model.SceneLighting
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.g3d.Environment
import com.badlogic.gdx.graphics.g3d.ModelBatch
import com.badlogic.gdx.graphics.g3d.ModelInstance
import com.badlogic.gdx.graphics.g3d.Renderable
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.math.Matrix4
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Pool
import gg.hideouthq.data.preview.ServedStore
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.glbProblems
import kotlinx.coroutines.runBlocking
import net.mgsx.gltf.scene3d.animation.AnimationControllerHack
import net.mgsx.gltf.scene3d.scene.Scene
import net.mgsx.gltf.scene3d.scene.SceneAsset
import net.mgsx.gltf.scene3d.shaders.PBRDepthShaderProvider
import net.mgsx.gltf.scene3d.shaders.PBRShaderConfig
import net.mgsx.gltf.scene3d.shaders.PBRShaderProvider
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.Timeout
import org.lwjgl.opengl.GL11
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class TextureSkinningTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    @Test
    fun aSkinOf512JointsWithEightWeightedInfluencesDraws(): Unit = runBlocking {
        val frame = frames(WideSkin().sceneFiles(entry, manifest), listOf("flex" to FrameRequest(Side, Side, WideSkin.Flex, 0.5f, Camera(-70f), SceneLighting.Studio))).single()
        assertTrue(ProofFrames.covered(frame.pixels) > Side * Side / 50, "The 512-joint model drew ${ProofFrames.covered(frame.pixels)} pixels")
        assertEquals(0, gl.liveScenes.get())
    }

    @Test
    fun aWideSkinDrawsExactlyAsTheOldUniformShaderDrawsItsCompactTwin(): Unit = runBlocking {
        val twin = WideSkin(compact = true).sceneFiles(entry, manifest)
        val requests = ProofFrames.requests(twin.model, Side)
        val reference = frames(twin, requests, UniformReferenceShaders.factory)
        val compact = frames(twin, requests)
        val wide = frames(WideSkin().sceneFiles(entry, manifest), requests)
        assertEquals(emptyList(), mismatches(reference, compact), "The palette drew the compact twin unlike the uniform shader")
        assertEquals(emptyList(), mismatches(reference, wide), "The 512-joint palette drew unlike the uniform shader drew its compact twin")
    }

    @Test
    fun aZeroWeightInfluencePastThePaletteChangesNothing(): Unit = runBlocking {
        val requests = ProofFrames.requests(WideSkin(compact = true).sceneFiles(entry, manifest).model, Side)
        val reference = frames(WideSkin(compact = true, zeroSlots = 0).sceneFiles(entry, manifest), requests, UniformReferenceShaders.factory)
        val within = frames(WideSkin(zeroSlots = 0).sceneFiles(entry, manifest), requests)
        val beyond = frames(WideSkin(zeroSlots = 4000).sceneFiles(entry, manifest), requests)
        assertEquals(emptyList(), mismatches(reference, within), "Four weighted influences drew unlike the uniform shader")
        assertEquals(emptyList(), mismatches(within, beyond), "Zero-weight influences naming joint 4000 of 512 changed the drawing")
    }

    @Test
    fun aPaletteHoldsEveryJointsMatrixBitForBitAndIdentityPastIt(): Unit = runBlocking {
        val rows = WideSkin.Palette + 8
        val failures = gl.run {
            val asset = asset(WideSkin())
            try {
                val instance = Scene(asset.scene).modelInstance
                val parts = parts(instance)
                assertEquals(4, parts.size)
                parts.forEachIndexed { p, bones -> bones.forEachIndexed { j, matrix -> matrix.set(FloatArray(16) { c -> pattern(p, j, c) }) } }
                java.util.Arrays.fill(parts[0], 7, 8, null)
                val palettes = BonePalettes(instance, rows)
                val read = BufferUtils.newFloatBuffer(rows * 16)
                buildList {
                    try {
                        palettes.upload()
                        parts.forEachIndexed { p, bones ->
                            palettes.bind(bones)
                            read.clear()
                            GL11.glGetTexImage(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, GL20.GL_FLOAT, read)
                            for (row in 0 until rows) for (c in 0 until 16) {
                                val expected = if (row >= bones.size || (p == 0 && row == 7)) Matrix4().`val`[c] else pattern(p, row, c)
                                val actual = read.get(row * 16 + c)
                                if (expected.toRawBits() != actual.toRawBits()) add("part $p row $row value $c: ${actual.toRawBits().toString(16)}, not ${expected.toRawBits().toString(16)}")
                            }
                        }
                    } finally {
                        palettes.dispose()
                    }
                }
            } finally {
                asset.dispose()
            }
        }
        assertEquals(emptyList(), failures.take(20), "${failures.size} palette values differ from the uploaded matrices")
    }

    @Test
    fun palettesAreReleasedWithTheirRendererAndWhenALoadFails(): Unit = runBlocking {
        val files = WideSkin().sceneFiles(entry, manifest)
        val before = gl.run { TestGl.managedObjects() }
        var handles = emptyList<Int>()
        fun keeping(make: (PBRShaderConfig, BonePalettes) -> PBRShaderProvider): (PBRShaderConfig, BonePalettes) -> PBRShaderProvider = { config, palettes ->
            handles = palettes.handles
            make(config, palettes)
        }
        suspend fun released(what: String) {
            assertEquals(4, handles.size, "$what: the palettes of the model's four skinned parts were not made")
            assertEquals(emptyList(), gl.run { handles.filter { Gdx.gl.glIsTexture(it) } }, "$what: palette textures outlived it")
            assertEquals(before, gl.run { TestGl.managedObjects() }, "$what: GL objects outlived it")
            assertEquals(0, gl.liveScenes.get(), what)
        }

        val renderer = gl.run { SceneRenderer.load(files.content(info), info, gl.liveScenes, gl.openFrames, keeping(::PaletteShaders)) }
        assertTrue(gl.run { handles.all { Gdx.gl.glIsTexture(it) } }, "The palettes are not live textures while the renderer draws")
        gl.run { renderer.dispose() }
        gl.run { renderer.dispose() }
        released("a disposed renderer")

        assertFailsWith<IllegalStateException> {
            gl.run { SceneRenderer.load(files.content(info), info, gl.liveScenes, gl.openFrames, keeping { _, _ -> throw IllegalStateException("no shaders") }) }
        }
        released("a load that failed before its first frame")

        val failure = assertFailsWith<UnsupportedScene> {
            gl.run { SceneRenderer.load(files.content(info), info, gl.liveScenes, gl.openFrames, keeping { config, palettes -> PaletteShaders(config.apply { fragmentShader = "not a shader" }, palettes) }) }
        }
        assertTrue("did not compile or link" in failure.message.orEmpty(), failure.message)
        released("a load whose first frame failed")
        val next = frames(files, listOf("after" to FrameRequest(Side, Side, WideSkin.Flex, 0.5f, Camera(-70f), SceneLighting.Studio))).single()
        assertTrue(ProofFrames.covered(next.pixels) > 0, "The next scene after a failed frame drew nothing")
    }

    @Test
    fun theDepthPassReadsThePaletteAsTheOldUniformDepthShaderReadTheCompactTwin(): Unit = runBlocking {
        val weights = 8
        fun depth(skin: WideSkin, joints: Int, palette: Boolean): ByteArray {
            val asset = asset(skin)
            val instance = Scene(asset.scene).modelInstance
            val palettes = BonePalettes(instance, joints)
            val config = PBRShaderProvider.createDefaultDepthConfig().apply { numBones = joints; numBoneWeights = weights }
            val batch = ModelBatch(if (palette) PaletteDepthShaders(config, palettes) else PBRDepthShaderProvider(config))
            val target = FrameBuffer(Pixmap.Format.RGBA8888, Side, Side, true)
            try {
                object : AnimationControllerHack(instance) {
                    fun at(clip: String, seconds: Float) = applyAnimation(instance.getAnimation(clip), seconds)
                }.at(WideSkin.Flex, 0.6f)
                palettes.upload()
                val camera = OrthographicCamera(3f, 3f).apply {
                    position.set(0.2f, 1f, 5f)
                    near = 0.1f
                    far = 20f
                    update()
                }
                target.begin()
                Gdx.gl.glClearColor(1f, 1f, 1f, 1f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT or GL20.GL_DEPTH_BUFFER_BIT)
                batch.begin(camera)
                batch.render(instance)
                batch.end()
                val pixels = BufferUtils.newByteBuffer(Side * Side * 4)
                Gdx.gl.glReadPixels(0, 0, Side, Side, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels)
                target.end()
                assertEquals(GL20.GL_NO_ERROR, Gdx.gl.glGetError())
                return ByteArray(Side * Side * 4).also { pixels.get(it) }
            } finally {
                listOf(target, batch, palettes, asset).forEach { it.dispose() }
            }
        }
        val (reference, palette) = gl.run { depth(WideSkin(compact = true), WideSkin.Used.size, palette = false) to depth(WideSkin(), WideSkin.Palette, palette = true) }
        assertTrue((reference.indices step 4).count { reference[it].toInt() != -1 } > Side * Side / 50, "The reference depth pass drew nothing")
        assertEquals(0 to 0, ProofFrames.difference(reference, palette), "The palette's depth pass differs from the uniform depth shader's")
    }

    @Test
    fun aSkinnedShaderNeverDrawsAnUnskinnedPartNorTheReverse(): Unit = runBlocking {
        gl.run {
            val asset = asset(WideSkin())
            val instance = Scene(asset.scene).modelInstance
            val palettes = BonePalettes(instance, WideSkin.Palette)
            val shaders = PaletteShaders(PBRShaderProvider.createDefaultConfig().apply { numBones = WideSkin.Palette; numBoneWeights = 8 }, palettes)
            try {
                val all = com.badlogic.gdx.utils.Array<Renderable>()
                instance.getRenderables(all, object : Pool<Renderable>() {
                    override fun newObject() = Renderable()
                })
                val skinned = all.first { it.bones != null }.apply { environment = Environment() }
                val unskinned = Renderable().set(skinned).apply { bones = null }
                val forSkinned = shaders.getShader(skinned)
                val forUnskinned = shaders.getShader(unskinned)
                assertNotSame(forSkinned, forUnskinned, "One shader serves a skinned part and the same part unskinned")
                assertFalse(forSkinned.canRender(unskinned), "The skinned shader would draw an unskinned part")
                assertFalse(forUnskinned.canRender(skinned), "The unskinned shader would draw a skinned part")
                assertTrue(forSkinned.canRender(skinned) && forUnskinned.canRender(unskinned))
            } finally {
                listOf(shaders, palettes, asset).forEach { it.dispose() }
            }
        }
    }

    private suspend fun frames(files: SceneFiles, requests: List<Pair<String, FrameRequest>>, shaders: (PBRShaderConfig, BonePalettes) -> PBRShaderProvider = ::PaletteShaders): List<Frame> {
        val renderer = gl.run { SceneRenderer.load(files.content(info), info, gl.liveScenes, gl.openFrames, shaders) }
        try {
            return ProofFrames.render(gl, renderer, requests)
        } finally {
            gl.run { renderer.dispose() }
        }
    }

    private fun mismatches(expected: List<Frame>, actual: List<Frame>): List<String> {
        assertEquals(expected.map { it.name }, actual.map { it.name })
        return expected.zip(actual).mapNotNull { (e, a) ->
            val (pixels, largest) = ProofFrames.difference(e.pixels, a.pixels)
            if (pixels == 0) null else "${e.name}: $pixels pixels differ, by up to $largest"
        }
    }

    private fun parts(instance: ModelInstance): List<Array<Matrix4>> = buildList {
        fun collect(node: com.badlogic.gdx.graphics.g3d.model.Node) {
            node.parts.forEach { part -> part.bones?.let(::add) }
            node.children.forEach(::collect)
        }
        instance.nodes.forEach(::collect)
    }

    private fun asset(skin: WideSkin): SceneAsset = SceneRenderer.asset(skin.sceneFiles(entry, manifest).content(info))

    private fun pattern(part: Int, row: Int, column: Int): Float {
        val seed = (part * 7919 + row * 131 + column * 17 + 1) * -0x61c88647
        if (seed % 11 == 0) return -0f
        val exponent = 1 + Math.floorMod(seed ushr 8, 253)
        return Float.fromBits((seed and Int.MIN_VALUE) or (exponent shl 23) or (seed and 0x7FFFFF))
    }

    companion object {
        private const val Side = 256
        private lateinit var gl: GlThread
        private lateinit var info: GlInfo
        private lateinit var entry: IndexEntry
        private lateinit var manifest: BundleManifest

        @JvmStatic
        @BeforeClass
        fun start() {
            info = TestGl.info()
            gl = TestGl.thread
            ServedStore().use { store ->
                entry = store.entry()
                manifest = store.manifest()
            }
            for (skin in listOf(WideSkin(), WideSkin(compact = true), WideSkin(zeroSlots = 0), WideSkin(zeroSlots = 4000), WideSkin(compact = true, zeroSlots = 0))) {
                val files = skin.sceneFiles(entry, manifest)
                val chunks = glbChunks(skin.glb)
                assertEquals(emptyList(), glbProblems(manifest.scene!!.model, chunks.json, chunks.binaryBytes, files.model.manifest), "The ${skin.joints}-joint fixture disagrees with its manifest")
            }
        }
    }
}
