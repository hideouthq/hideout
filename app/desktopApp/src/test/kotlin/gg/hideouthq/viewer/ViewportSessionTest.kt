package gg.hideouthq.viewer

import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.data.preview.ScriptedHost
import gg.hideouthq.data.preview.ServedStore
import gg.hideouthq.data.preview.missing
import gg.hideouthq.data.preview.send
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.ViewportStatus
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.decodeBundleManifest
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.Timeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ViewportSessionTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun aLoadedModelStaysLoadingToTheStageUntilItsFirstFrameArrives() = withSession(strip) { session ->
        val loaded = assertIs<SessionState.FirstFrame>(session.state.value)
        assertIs<ViewportStatus.Loading>(loaded.status)
        val loop = launch(Dispatchers.Default) { session.renderFrames() }
        session.render(Request)
        withTimeout(10_000) { session.state.first { it is SessionState.Showing } }
        session.close()
        loop.join()
    }

    @Test
    fun aFrameRenderedAfterItsLoopWasCancelledIsClosedAndCountedDropped() = withSession(strip) { session ->
        assertIs<SessionState.FirstFrame>(session.state.value)
        val loop = launch(Dispatchers.Default) { session.renderFrames() }
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        gl.post {
            blocked.countDown()
            release.await()
        }
        try {
            blocked.await()
            session.request(Request)
            withTimeout(10_000) { while (gl.queued == 0) delay(1) }
            loop.cancelAndJoin()
        } finally {
            release.countDown()
        }
        gl.run { }
        assertEquals(1, session.stats.rendered.get())
        assertEquals(1, session.stats.dropped.get())
        assertEquals(0, gl.openFrames.get())
        assertNull(session.take())
    }

    @Test
    fun atMostOneFrameWaitsAndOneIsShownAndClosingClosesBoth() = withSession(strip) { session ->
        assertIs<SessionState.FirstFrame>(session.state.value)
        val loop = launch(Dispatchers.Default) { session.renderFrames() }
        session.render(Request)
        val first = checkNotNull(session.take())
        assertSame(first, session.take(), "Drawing again without a new frame shows the same one")
        session.render(Request.copy(timeSeconds = 0.6f))
        session.render(Request.copy(timeSeconds = 0.7f))
        assertEquals(1, session.stats.dropped.get(), "The frame replaced while waiting was not dropped")
        assertEquals(2, gl.openFrames.get(), "Expected one shown and one waiting frame")

        session.close()
        loop.join()
        assertTrue(first.image.isClosed)
        assertEquals(3, session.stats.rendered.get())
        assertEquals(2, session.stats.dropped.get())
        assertEquals(0, gl.openFrames.get())
    }

    @Test
    fun aCaptureDrawsTheShownViewAgainAtTheSizeAskedForAndNothingOnceClosed() = withSession(strip) { session ->
        val loop = launch(Dispatchers.Default) { session.renderFrames() }
        session.render(Request)
        val rendered = session.stats.rendered.get()
        val larger = checkNotNull(session.capture(Request.copy(width = 128, height = 96)))
        larger.use { assertEquals(128 to 96, it.image.width to it.image.height) }
        assertEquals(Request, session.lastRendered, "the screen's own frames are untouched")
        assertEquals(rendered, session.stats.rendered.get())
        session.close()
        loop.join()
        assertNull(session.capture(Request))
        assertEquals(0, gl.openFrames.get())
    }

    @Test
    fun aModelTheRendererCannotLoadIsUnavailableWithItsVerifiedStillAsBytes() = withSession(fixture) { session ->
        val state = assertIs<SessionState.Unavailable>(session.state.value)
        assertTrue("The glTF loader cannot read this model" in state.reason, state.reason)
        val detail = fixture.manifest().pictures.single { it.role == PictureRole.Detail }.asset
        assertContentEquals(Files.readAllBytes(fixture.bundle().resolve(detail)), state.still)
        assertEquals(sha256Hex(checkNotNull(state.still)), fixture.manifest().assets.single { it.path == detail }.sha256)
    }

    @Test
    fun aFrameThatFailsMakesTheSessionUnavailableAndReleasesItsSceneAtOnce() = withSession(strip) { session ->
        assertIs<SessionState.FirstFrame>(session.state.value)
        assertEquals(1, gl.liveScenes.get())
        val loop = launch(Dispatchers.Default) { session.renderFrames() }
        session.request(Request.copy(clip = "missing"))
        withTimeout(10_000) { loop.join() }
        gl.run { }
        val state = assertIs<SessionState.Unavailable>(session.state.value)
        assertTrue("missing" in state.reason, state.reason)
        assertEquals(1, session.stats.errors.get())
        assertEquals(0, gl.liveScenes.get(), "The scene that failed to draw is held until its page closes")
        assertEquals(0, gl.openFrames.get())
        assertNull(session.take())
    }

    @Test
    fun aSceneBeyondThisGpuIsRefusedBeforeItsFilesAreFetched() = runBlocking {
        val info = gl.info()
        val bundle = fixture.bundle()
        val entry = fixture.entry()
        val manifest = fixture.manifest()
        val heavy = manifest.copy(scene = manifest.scene!!.copy(maxJointsPerSkin = info.maxTextureSize + 1))
        ScriptedHost { exchange, _ ->
            val file = bundle.resolve(exchange.request.path.removePrefix("/v1/bundles/${entry.bundleId}/"))
            if (Files.isRegularFile(file)) Files.readAllBytes(file).let { exchange.send(it, etag = "\"${sha256Hex(it)}\"") } else exchange.missing()
        }.use { host ->
            val session = ViewportSession(PreviewModel(entry, heavy), host.cache(Files.createTempDirectory("session-cache")), lazyOf(gl))
            session.load()
            val state = assertIs<SessionState.Unavailable>(session.state.value)
            val detail = manifest.pictures.single { it.role == PictureRole.Detail }.asset
            assertTrue("bone palette" in state.reason, state.reason)
            assertEquals(listOf("/v1/bundles/${entry.bundleId}/$detail"), host.requests.map { it.path })
            session.close()
            gl.run { }
            assertEquals(0, gl.liveScenes.get())
        }
    }

    private suspend fun ViewportSession.render(request: FrameRequest) {
        request(request)
        withTimeout(10_000) { while (lastRendered != request) delay(1) }
    }

    private fun withSession(store: ServedStore, block: suspend CoroutineScope.(ViewportSession) -> Unit) = runBlocking {
        val cache = store.cache(Files.createTempDirectory("session-cache"))
        val entry = store.entry()
        val session = ViewportSession(PreviewModel(entry, decodeBundleManifest(cache.manifestText(entry))), cache, lazyOf(gl))
        session.load()
        try {
            block(session)
        } finally {
            session.close()
            gl.run { }
        }
        assertEquals(0, gl.liveScenes.get())
        assertEquals(0, gl.openFrames.get())
    }

    companion object {
        private val Request = FrameRequest(64, 64, BentStrip.Bend, 0.5f, Camera(30f), SceneLighting.Studio)
        private lateinit var gl: GlThread

        /** Core's fixture store, whose model gdx-gltf 2.3.0 cannot load. */
        private lateinit var fixture: ServedStore

        private lateinit var strip: ServedStore

        @JvmStatic
        @BeforeClass
        fun start() {
            TestGl.info()
            gl = TestGl.thread
            fixture = ServedStore()
            strip = ServedStore()
            val (encoded, stored) = encodedModel(strip.manifest().assets.single { it.path == strip.manifest().scene!!.model }, BentStrip.glb)
            strip.republish(mapOf(encoded.path to stored)) { manifest ->
                val model = BentStrip.manifest(manifest)
                manifest.copy(
                    assets = manifest.assets.map { if (it.path == encoded.path) encoded else it },
                    scene = model.scene,
                    clips = model.clips,
                    pictures = manifest.pictures.map { if (it.clip == null) it else it.copy(clip = BentStrip.Bend) },
                )
            }
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            if (::fixture.isInitialized) fixture.close()
            if (::strip.isInitialized) strip.close()
        }
    }
}
