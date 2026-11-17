package gg.hideouthq.viewer

import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.data.preview.ServedStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlThreadTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun aQueuedJobThatThrowsIsReportedAndTheJobsBehindItStillRunInOrder() = runBlocking {
        val reported = TestGl.reported.size
        val exception = IllegalStateException("thrown by a queued job")
        val error = AssertionError("an Error thrown by a queued job")
        val order = mutableListOf<Int>()
        gl.post { throw exception }
        gl.post { order += 1 }
        gl.post { throw error }
        assertEquals(42, gl.run { order += 2; 42 })
        assertEquals(listOf(1, 2), order)
        assertEquals(listOf<Throwable>(exception, error), TestGl.reported.drop(reported))
        val failure = runCatching { gl.run { throw exception } }.exceptionOrNull()
        assertIs<IllegalStateException>(failure)
        assertEquals(exception.message, failure.message)
        assertEquals(2, TestGl.reported.size - reported, "A failure that reached its caller was reported as well")
    }

    @Test
    fun aFrameWhoseCallerWasCancelledWhileItRenderedIsClosed() = withRenderer { renderer ->
        val rendering = CountDownLatch(1)
        val release = CountDownLatch(1)
        val discarded = CompletableDeferred<RenderedFrame>()
        val caller = launch(Dispatchers.Default) {
            gl.run(discard = { frame: RenderedFrame -> frame.close(); discarded.complete(frame) }) {
                rendering.countDown()
                release.await()
                renderer.render(Request)
            }
        }
        rendering.await()
        caller.cancelAndJoin()
        assertEquals(0, gl.openFrames.get(), "A frame exists before its job rendered it")
        release.countDown()
        val frame = withTimeout(10_000) { discarded.await() }
        assertTrue(frame.image.isClosed)
        assertEquals(0, gl.openFrames.get())
    }

    @Test
    fun aFrameItsCallerTakesIsNotDiscarded() = withRenderer { renderer ->
        var discarded = false
        val frame = async(Dispatchers.Default) { gl.run(discard = { _: RenderedFrame -> discarded = true }) { renderer.render(Request) } }.await()
        assertFalse(frame.image.isClosed)
        assertEquals(1, gl.openFrames.get())
        frame.close()
        frame.close()
        assertFalse(discarded)
        assertEquals(0, gl.openFrames.get())
    }

    private fun withRenderer(block: suspend kotlinx.coroutines.CoroutineScope.(SceneRenderer) -> Unit) = runBlocking {
        val renderer = gl.run { SceneRenderer.load(strip.content(info), info, gl.liveScenes, gl.openFrames) }
        try {
            block(renderer)
        } finally {
            gl.run { renderer.dispose() }
        }
        assertEquals(0, gl.liveScenes.get())
    }

    companion object {
        private val Request = FrameRequest(64, 64, BentStrip.Bend, 0.5f, Camera(30f), SceneLighting.Studio)
        private lateinit var gl: GlThread
        private lateinit var info: GlInfo
        private lateinit var strip: SceneFiles

        @JvmStatic
        @BeforeClass
        fun start() = runBlocking {
            info = TestGl.info()
            gl = TestGl.thread
            ServedStore().use { store -> strip = BentStrip.sceneFiles(store.entry(), store.manifest()) }
        }
    }
}
