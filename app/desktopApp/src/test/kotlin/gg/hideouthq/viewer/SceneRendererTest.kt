package gg.hideouthq.viewer

import gg.hideouthq.core.model.SceneLighting
import com.badlogic.gdx.graphics.TextureData
import gg.hideouthq.data.preview.ServedStore
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.decodeBundleManifest
import gg.hideouthq.preview.glbProblems
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.Timeout
import java.lang.ref.WeakReference
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneRendererTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private suspend fun load(files: SceneFiles, limits: GlInfo = info): SceneRenderer {
        val content = files.content(limits)
        return gl.run { SceneRenderer.load(content, limits, gl.liveScenes, gl.openFrames) }
    }

    private fun render(renderer: SceneRenderer, request: FrameRequest): ByteArray = runBlocking {
        gl.run { renderer.render(request) }.use { frame -> Bitmap.makeFromImage(frame.image).use { checkNotNull(it.readPixels()) } }
    }

    private fun render(renderer: SceneRenderer, clip: String?, seconds: Float, azimuth: Float = 30f) =
        render(renderer, FrameRequest(Side, Side, clip, seconds, Camera(azimuth), SceneLighting.Studio))

    @Test
    fun orbitingDoesNotResizeAnInteractiveModelsHorizontalFeatures() = runBlocking {
        val renderer = load(strip)
        try {
            fun request(pitch: Float) = FrameRequest(
                Side, Side, null, 0f, Camera(-90f, pitchDegrees = pitch), SceneLighting.Studio, CameraFraming.Reference(-90f),
            )
            val frames = ProofFrames.render(gl, renderer, listOf("front" to request(Camera.StartPitchDegrees), "above" to request(60f)))
            val widths = frames.map { frame ->
                val columns = (frame.pixels.indices step 4)
                    .filter { (frame.pixels[it + 3].toInt() and 255) >= 128 }
                    .map { it / 4 % Side }
                columns.max() - columns.min() + 1
            }
            assertTrue(abs(widths[0] - widths[1]) <= 2, "Pitch changed the strip's horizontal scale: $widths")
            val first = request(Camera.StartPitchDegrees)
            val checks = ProofFrames.render(gl, renderer, listOf(
                "capture" to first.copy(framing = CameraFraming.FitView),
                "panned" to first.copy(camera = first.camera.copy(panX = 10f / Side, panY = 5f / Side)),
                "reset" to first,
            ))
            assertContentEquals(frames[0].pixels, checks[0].pixels, "Interactive framing changed the initial capture view")
            assertContentEquals(frames[0].pixels, checks[2].pixels, "Reset did not restore the original frame")
            fun bounds(frame: Frame): Pair<Int, Int> {
                val xs = (frame.pixels.indices step 4).filter { (frame.pixels[it + 3].toInt() and 255) >= 128 }.map { it / 4 % Side }
                return xs.min() to xs.max()
            }
            val before = bounds(frames[0])
            val after = bounds(checks[1])
            assertEquals(10, after.first - before.first, "Pan did not follow the pointer")
            assertEquals(before.second - before.first, after.second - after.first, "Pan resized the model")
        } finally {
            gl.run { renderer.dispose() }
        }
    }

    @Test
    fun aPoseIsAFunctionOfItsTimeAloneAndDisposingReleasesTheScene() = runBlocking {
        val renderer = load(strip)
        assertEquals(1, gl.liveScenes.get())
        val early = render(renderer, BentStrip.Bend, 0.2f)
        val late = render(renderer, BentStrip.Bend, 0.9f)
        render(renderer, BentStrip.Sway, 0.4f)
        val again = render(renderer, BentStrip.Bend, 0.2f)
        val rest = render(renderer, null, 0f)
        assertTrue(ProofFrames.covered(early) > Side * Side / 100, "The model drew ${ProofFrames.covered(early)} pixels")
        assertFalse(early.contentEquals(late), "Two times of a clip drew the same pose")
        assertContentEquals(early, again, "The same time drew different pixels after other clips")
        assertFalse(rest.contentEquals(early), "The rest pose matched an animated pose")
        assertFailsWith<IllegalArgumentException> { render(renderer, "run", 0f) }
        gl.run { renderer.dispose() }
        assertEquals(0, gl.liveScenes.get())
        assertEquals(0, gl.openFrames.get())
    }

    @Test
    fun aCompressedEightInfluenceSkinMovesWhenOnlyItsFifthJointAnimates() = runBlocking {
        val content = FifthJoint.content(info)
        val renderer = gl.run { SceneRenderer.load(content, info, gl.liveScenes, gl.openFrames) }
        try {
            val side = 256
            val requests = listOf(0f, 0.5f, 1f, 0.5f).mapIndexed { index, seconds ->
                "pose-$index" to FrameRequest(side, side, FifthJoint.Move, seconds, Camera(-90f, pitchDegrees = 0f), SceneLighting.Studio)
            }
            val frames = ProofFrames.render(gl, renderer, requests)
            val columns = frames.map { frame ->
                val covered = (frame.pixels.indices step 4).filter { (frame.pixels[it + 3].toInt() and 255) >= 128 }
                assertTrue(covered.size > side * side / 100, "${frame.name} drew ${covered.size} opaque pixels")
                assertTrue(covered.all { (it / 4 % side) in 1 until side - 1 && (it / 4 / side) in 1 until side - 1 }, "${frame.name} touches the frame edge")
                covered.map { it / 4 % side }
            }
            val width = (columns[0].max() - columns[0].min()).toDouble()
            val centers = columns.map { it.average() }
            val halfDelta = (centers[1] - centers[0]) / width
            val fullDelta = (centers[2] - centers[0]) / width
            assertEquals(0.35, halfDelta, 0.03, "The fifth joint must move the triangle halfway at 0.5s")
            assertEquals(0.7, fullDelta, 0.03, "A 0.07 weight on the fifth joint translated by 10 must move the triangle by 0.7")
            columns.forEach { assertTrue(abs((it.max() - it.min()) - width) <= 2, "Translation changed the triangle's width") }
            assertContentEquals(frames[1].pixels, frames[3].pixels, "Seeking back to 0.5s must reproduce the same pose")
        } finally {
            gl.run { renderer.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
        assertEquals(0, gl.openFrames.get())
    }

    /** The lowest covered row in screen orientation; the frame's rows run from the bottom up. */
    private fun screenBottom(pixels: ByteArray): Int {
        val lowest = (0 until Side).first { row -> (0 until Side).any { pixels[(row * Side + it) * 4 + 3].toInt() != 0 } }
        return Side - 1 - lowest
    }

    @Test
    fun theModelStandsOnTheDiscLineHoweverFarBelowItsDrawnFeetTheBindPoseReaches() = runBlocking {
        for (drop in listOf(0.5f, 0.05f, 0.01f)) {
            val renderer = load(HangingAttachment(drop = drop).sceneFiles(strip.model.entry, fixture.model.manifest))
            try {
                for (seconds in listOf(0f, 0.2f, 0.75f)) {
                    val bottom = screenBottom(render(renderer, HangingAttachment.Hold, seconds, azimuth = 90f))
                    assertTrue(abs(bottom - FeetLine * Side) < FeetTolerance * Side, "A bind pose $drop below: at ${seconds}s the feet are on row $bottom, not on the disc line at ${FeetLine * Side}")
                }
            } finally {
                gl.run { renderer.dispose() }
            }
        }
        assertEquals(0, gl.liveScenes.get())
    }

    private fun screenTop(pixels: ByteArray): Int {
        val highest = (Side - 1 downTo 0).first { row -> (0 until Side).any { pixels[(row * Side + it) * 4 + 3].toInt() != 0 } }
        return Side - 1 - highest
    }

    @Test
    fun aBroadBindPoseThatFoldsWhenDrawnDoesNotShrinkTheModel() = runBlocking {
        val folding = HangingAttachment(span = 3f, fold = 0.1f).sceneFiles(strip.model.entry, fixture.model.manifest)
        val renderer = load(folding)
        try {
            for (seconds in listOf(0f, 0.2f, 0.75f)) {
                val pixels = render(renderer, HangingAttachment.Hold, seconds, azimuth = 90f)
                val top = screenTop(pixels)
                val bottom = screenBottom(pixels)
                assertTrue(abs(bottom - FeetLine * Side) < FeetTolerance * Side, "At ${seconds}s the feet are on row $bottom, not on the disc line at ${FeetLine * Side}")
                assertTrue(abs(top - HeadLine * Side) < FeetTolerance * Side, "At ${seconds}s the head is on row $top, not at the top of the fitted view, ${HeadLine * Side}")
            }
        } finally {
            gl.run { renderer.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
    }

    @Test
    fun remoteIslandsDoNotShrinkTheBodyAndRemainDrawable() = runBlocking {
        val files = HangingAttachment(remote = -2713f).sceneFiles(strip.model.entry, fixture.model.manifest)
        val renderer = load(files)
        try {
            for (clip in listOf(null, HangingAttachment.Hold)) {
                assertTrue(ProofFrames.covered(render(renderer, clip, 0f, 90f)) > Side * Side / 10, "The body was shrunk by a remote island")
            }
            val request = FrameRequest(Side, Side, HangingAttachment.Hold, 0f, Camera(90f, pitchDegrees = 0f, panY = -2713f / (2f / 0.83f)), SceneLighting.Studio)
            assertTrue(ProofFrames.covered(render(renderer, request)) > 100, "Panning to the authored island did not draw it")
        } finally {
            gl.run { renderer.dispose() }
        }
    }

    @Test
    fun largeAttachmentsRemainWholeEvenWithRemoteIslands() = runBlocking {
        val files = HangingAttachment(span = 8f, remote = -2713f).sceneFiles(strip.model.entry, fixture.model.manifest)
        val renderer = load(files)
        try {
            val columns = columns(render(renderer, HangingAttachment.Hold, 0f, 90f))
            assertTrue(columns.first > 0 && columns.last < Side - 1, "The attachment touches the border")
            assertTrue(columns.last - columns.first > Side * 0.8f, "The attachment no longer spans the frame")
        } finally {
            gl.run { renderer.dispose() }
        }
    }

    @Test
    fun anAttachmentAlongTheViewDoesNotShrinkTheBodyButFitsWhenViewedSideways() = runBlocking {
        val files = HangingAttachment(span = 8f, bodyDepth = 1f).sceneFiles(strip.model.entry, fixture.model.manifest)
        val renderer = load(files)
        try {
            val request = FrameRequest(Side, Side, HangingAttachment.Hold, 0f, Camera(0f, pitchDegrees = 0f), SceneLighting.Studio)
            val pixels = render(renderer, request)
            val height = screenBottom(pixels) - screenTop(pixels)
            assertTrue(height > Side * 0.75f, "The body spans only $height rows when the attachment points into the view")
            val side = columns(render(renderer, HangingAttachment.Hold, 0f, 90f))
            assertTrue(side.first > 0 && side.last < Side - 1, "The side view clips the attachment")
            assertTrue(side.last - side.first > Side * 0.8f, "The side view lost the attachment")
            for (pitch in listOf(-80f, -60f, -30f, 0f, Camera.StartPitchDegrees, 30f, 60f, 80f)) {
                val tilted = render(renderer, request.copy(camera = Camera(0f, pitchDegrees = pitch)))
                assertTrue(screenTop(tilted) > 0 && screenBottom(tilted) < Side - 1, "Pitch $pitch clips the attachment")
            }
        } finally {
            gl.run { renderer.dispose() }
        }
    }

    @Test
    fun aWideAttachmentDoesNotHideAnIncreaseInAnimatedHeight() = runBlocking {
        val files = HangingAttachment(drop = -0.5f, span = 8f, bodyDepth = 1f).sceneFiles(strip.model.entry, fixture.model.manifest)
        val renderer = load(files)
        try {
            val pixels = render(renderer, FrameRequest(Side, Side, HangingAttachment.Hold, 0f, Camera(5f, pitchDegrees = 0f), SceneLighting.Studio))
            assertTrue(screenTop(pixels) > 0, "The raised attachment is clipped")
            assertTrue(screenBottom(pixels) - screenTop(pixels) > Side * 0.75f, "The raised attachment shrank the body")
        } finally {
            gl.run { renderer.dispose() }
        }
    }

    private fun leaping() = BentStrip.leapingFiles(strip.model.entry, fixture.model.manifest)

    @Test
    fun aClipThatCarriesTheModelFarAwayDoesNotReframeTheOtherClipsOrTheRestPose() = runBlocking {
        val plain = load(strip)
        val leaping = load(leaping())
        try {
            for ((clip, seconds) in listOf(BentStrip.Bend to 0.2f, BentStrip.Bend to 0.9f, BentStrip.Sway to 0.4f, null to 0f)) {
                assertContentEquals(render(plain, clip, seconds), render(leaping, clip, seconds), "$clip at ${seconds}s drew differently once the model also had a clip that leaps ${BentStrip.LeapDistance} away")
            }
        } finally {
            gl.run { plain.dispose() }
            gl.run { leaping.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
    }

    private fun columns(pixels: ByteArray): IntRange {
        val covered = (0 until Side).filter { column -> (0 until Side).any { pixels[(it * Side + column) * 4 + 3].toInt() != 0 } }
        return covered.first()..covered.last()
    }

    @Test
    fun aClipThatTravelsFarIsFramedOnceForItsWholeJourney() = runBlocking {
        val renderer = load(leaping())
        try {
            val bend = render(renderer, BentStrip.Bend, 0.2f, azimuth = 90f)
            val journey = listOf(0f, 0.25f, 0.5f, 0.75f, 1f).map { seconds ->
                val pixels = render(renderer, BentStrip.Leap, seconds, azimuth = 90f)
                val columns = columns(pixels)
                val top = screenTop(pixels)
                val bottom = screenBottom(pixels)
                assertTrue(columns.first > 0 && columns.last < Side - 1 && top > 0, "At ${seconds}s the leap reaches the frame's edge: columns $columns, top row $top")
                assertTrue(abs(bottom - FeetLine * Side) < FeetTolerance * Side, "At ${seconds}s the feet are on row $bottom, not on the disc line at ${FeetLine * Side}")
                Triple(columns, top, bottom)
            }
            assertEquals(1, journey.map { (_, top, bottom) -> top to bottom }.distinct().size, "The leap's rows changed on the way, so it was refitted: $journey")
            assertTrue(journey.all { (columns) -> abs((columns.last - columns.first) - (journey[0].first.last - journey[0].first.first)) <= 1 }, "The leap changed width on the way: $journey")
            val steps = journey.map { it.first.first }.zipWithNext { from, to -> to - from }
            assertTrue(steps.all { it > 0 } || steps.all { it < 0 }, "The leap did not travel one way across the frame: $journey")
            assertTrue(abs(journey.last().first.first - journey.first().first.first) > Side / 2, "The leap crossed less than half the frame: $journey")
            assertContentEquals(bend, render(renderer, BentStrip.Bend, 0.2f, azimuth = 90f), "The bend drew differently after the leap")
        } finally {
            gl.run { renderer.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
    }

    @Test
    fun aUintPrimitiveSplitAtLoadMatchesItsFileLevelUshortPartitionWhileMorphWeightsAnimateBothHalves() = runBlocking {
        val source = load(LargeIndexPartition.sourceFiles(strip.model.entry, fixture.model.manifest))
        try {
            val partitioned = load(LargeIndexPartition.partitionedFiles(strip.model.entry, fixture.model.manifest))
            try {
                val times = listOf(0f, 0.5f, 1f)
                val sourceFrames = times.map { render(source, LargeIndexPartition.Move, it) }
                val partitionedFrames = times.map { render(partitioned, LargeIndexPartition.Move, it) }
                times.indices.forEach { i ->
                    assertContentEquals(sourceFrames[i], partitionedFrames[i], "At ${times[i]}s the UINT mesh and its USHORT partition drew differently")
                }
                assertContentEquals(render(source, LargeIndexPartition.Move, 0.5f), sourceFrames[1], "Re-seeking the same time drew different pixels")
                assertContentEquals(render(partitioned, LargeIndexPartition.Move, 0.5f), partitionedFrames[1], "Re-seeking the same time drew different pixels")
                assertFalse(sourceFrames[0].contentEquals(sourceFrames[2]), "The rest pose matched the fully morphed pose")
                assertFalse(partitionedFrames[0].contentEquals(partitionedFrames[2]), "The rest pose matched the fully morphed pose")
                val half = Side / 2
                fun column(pixels: ByteArray, columns: IntRange) = (0 until Side).flatMap { row -> columns.map { col -> pixels[(row * Side + col) * 4 + 3] } }
                fun left(pixels: ByteArray) = column(pixels, 0 until half)
                fun right(pixels: ByteArray) = column(pixels, half until Side)
                assertNotEquals(left(sourceFrames[0]), left(sourceFrames[1]), "The left half never moved")
                assertNotEquals(right(sourceFrames[0]), right(sourceFrames[1]), "The right half never moved")
            } finally {
                gl.run { partitioned.dispose() }
            }
        } finally {
            gl.run { source.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
        assertEquals(0, gl.openFrames.get())
    }

    @Test
    fun aPaletteOrFeatureTheRendererCannotHoldIsUnsupportedBeforeAnythingLoads() = runBlocking {
        val scene = strip.model.manifest.scene!!
        val cases = listOf(
            strip to info.copy(bc7 = false),
            strip to info.copy(major = 2, minor = 1),
            strip to info.copy(maxVertexTextureImageUnits = 0),
            strip to info.copy(maxTextureSize = scene.maxJointsPerSkin - 1),
            strip.withScene(scene.copy(influenceSets = 3)) to info,
            strip.withScene(scene.copy(morphTargets = 9)) to info,
            strip.withScene(scene.copy(extensionsRequired = listOf("KHR_draco_mesh_compression"))) to info,
            fixture to info,
        )
        for ((candidate, limits) in cases) {
            assertFailsWith<UnsupportedScene> { load(candidate, limits) }
        }
        assertEquals(0, gl.liveScenes.get())
        assertEquals(0, gl.openFrames.get())
    }

    private suspend fun refusal(files: SceneFiles, limits: GlInfo = info): Throwable? {
        val loaded = runCatching { load(files, limits) }
        loaded.getOrNull()?.let { renderer -> gl.run { renderer.dispose() } }
        return loaded.exceptionOrNull()
    }

    private fun textured(images: List<ByteArray>) = BentStrip.texturedFiles(strip.model.entry, fixture.model.manifest, images)

    private fun meanColour(pixels: ByteArray): List<Int> {
        val covered = (pixels.indices step 4).filter { pixels[it + 3].toInt() != 0 }
        return (0..2).map { channel -> covered.sumOf { pixels[it + channel].toInt() and 0xff } / covered.size }
    }

    @Test
    fun aViewerTextureIsDrawnInItsOwnColour() = runBlocking {
        val (red, blue) = listOf(TestKtx2.Red, TestKtx2.Blue).map { colour ->
            val renderer = load(textured(listOf(TestKtx2.solid(16, 16, colour))))
            try {
                meanColour(render(renderer, BentStrip.Bend, 0.2f))
            } finally {
                gl.run { renderer.dispose() }
            }
        }
        assertTrue(red[0] > 2 * red[2] && blue[2] > 2 * blue[0], "The red texture drew $red and the blue one $blue")
        assertEquals(0, gl.liveScenes.get())
    }

    private suspend fun loadForgettingTheFiles(): Pair<SceneRenderer, WeakReference<ByteArray>> {
        val files = textured(listOf(TestKtx2.solid(64, 64, TestKtx2.Red)))
        return load(files) to WeakReference(files.textures.bytes)
    }

    @Test
    fun aLoadedSceneKeepsNoCopyOfItsTexturesOnTheCpu() = runBlocking {
        val content = textured(listOf(TestKtx2.solid(16, 16, TestKtx2.Red))).content(info)
        val asset = gl.run { SceneRenderer.asset(content) }
        try {
            assertNull(asset.pixmaps, "gdx-gltf kept decoded images")
            assertEquals(1, asset.textures.size)
            assertTrue(asset.textures.none { it.textureData.type == TextureData.TextureDataType.Pixmap }, "A texture is backed by a Pixmap")
        } finally {
            gl.run { asset.dispose() }
        }

        val (renderer, stored) = loadForgettingTheFiles()
        try {
            repeat(50) {
                if (stored.get() != null) {
                    System.gc()
                    delay(20)
                }
            }
            assertNull(stored.get(), "The drawn scene still holds its packed textures")
            assertTrue(ProofFrames.covered(render(renderer, BentStrip.Bend, 0.2f)) > Side * Side / 100, "The scene stopped drawing once its files were let go")
        } finally {
            gl.run { renderer.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
    }

    @Test
    fun aTextureWiderThanThisGpuTakesIsRefusedBeforeAnythingUploads() = runBlocking {
        val before = gl.run { TestGl.managedObjects() }
        val failure = refusal(textured(listOf(TestKtx2.solid(16, 1, TestKtx2.Red))), info.copy(maxTextureSize = 8))
        assertIs<UnsupportedScene>(failure, "$failure")
        assertTrue(Regex("Image view \\d+ is 16x1").containsMatchIn(failure.message.orEmpty()), failure.message)
        assertEquals(0, gl.liveScenes.get())
        assertEquals(before, gl.run { TestGl.managedObjects() }, "A refused texture left GL objects behind")
    }

    @Test
    fun aSceneAtThePipelinesTextureBudgetDraws() = runBlocking {
        val largest = TestKtx2.solid(4096, 4096, TestKtx2.Red)
        val renderer = load(textured(List(4) { largest }))
        try {
            assertTrue(ProofFrames.covered(render(renderer, BentStrip.Bend, 0.2f)) > Side * Side / 100, "The scene at the budget did not draw")
        } finally {
            gl.run { renderer.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
    }

    private fun SceneFiles.withScene(scene: gg.hideouthq.preview.PreviewScene) =
        SceneFiles(PreviewModel(model.entry, model.manifest.copy(scene = scene)), glb, textures)

    companion object {
        private const val Side = 128

        private const val FeetLine = 0.88f
        private const val FeetTolerance = 0.03f
        private const val HeadLine = 0.05f
        private lateinit var gl: GlThread
        private lateinit var info: GlInfo
        /** Core's fixture: valid glTF whose skin leaves out its inverse bind matrices, which gdx-gltf 2.3.0 cannot load. */
        private lateinit var fixture: SceneFiles
        private lateinit var strip: SceneFiles

        @JvmStatic
        @BeforeClass
        fun start() = runBlocking {
            info = TestGl.info()
            gl = TestGl.thread
            ServedStore().use { store ->
                val cache = store.cache(Files.createTempDirectory("renderer-cache"))
                fixture = cache.sceneFiles(PreviewModel(store.entry(), decodeBundleManifest(cache.manifestText(store.entry())))) {}
            }
            val manifest = fixture.model.manifest
            strip = BentStrip.sceneFiles(fixture.model.entry, manifest)
            val chunks = glbChunks(BentStrip.glb)
            assertEquals(emptyList(), glbProblems(manifest.scene!!.model, chunks.json, chunks.binaryBytes, strip.model.manifest))
        }
    }
}
