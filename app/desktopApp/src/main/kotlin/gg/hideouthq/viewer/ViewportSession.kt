package gg.hideouthq.viewer

import gg.hideouthq.core.analytics.*
import androidx.compose.runtime.mutableLongStateOf
import gg.hideouthq.data.preview.PreviewCache
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.ViewportStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

sealed interface SessionState {
    val status: ViewportStatus

    data class Loading(val progress: SceneProgress?) : SessionState {
        override val status get() = ViewportStatus.Loading(progress?.fraction, progress?.doneBytes ?: 0, progress?.totalBytes ?: 0)
    }

    data class FirstFrame(val progress: SceneProgress?) : SessionState {
        override val status get() = Loading(progress).status
    }

    data object Showing : SessionState {
        override val status get() = ViewportStatus.Showing
    }

    class Unavailable(val reason: String, val still: ByteArray?) : SessionState {
        override val status get() = ViewportStatus.Unavailable(still != null)
    }
}

class ViewportSession(
    val model: PreviewModel,
    private val cache: PreviewCache,
    private val gl: Lazy<GlThread>,
    private val analytics: UsageAnalytics? = null,
) {
    private val current = MutableStateFlow<SessionState>(SessionState.Loading(null))
    val state: StateFlow<SessionState> = current.asStateFlow()
    val stats = FrameStats()

    @Volatile private var usage: UsageAttempt? = null
    private var renderer: SceneRenderer? = null
    @Volatile private var closed = false
    private val requests = Channel<FrameRequest>(Channel.CONFLATED)
    private val waiting = AtomicReference<RenderedFrame?>()

    private val arrivals = mutableLongStateOf(0L)

    @Volatile var lastRequest: FrameRequest? = null
        private set
    @Volatile var lastRendered: FrameRequest? = null
        private set

    var shown: RenderedFrame? = null
        private set

    suspend fun load() = withContext(Dispatchers.Default) {
        if (closed) return@withContext
        usage = analytics?.begin(UsageOperation.PreviewLoad)
        if (closed) { usage?.finish(UsageOutcome.Cancelled, UsageStage.Preview); return@withContext }
        try {
            val files = cache.sceneFiles(model, ::preflight) { current.value = SessionState.Loading(it) }
            val thread = gl.value
            val info = thread.info()
            val content = files.content(info)
            thread.run { if (closed) null else SceneRenderer.load(content, info, thread.liveScenes, thread.openFrames).also { renderer = it } } ?: return@withContext
            current.value = SessionState.FirstFrame((current.value as? SessionState.Loading)?.progress)
        } catch (e: CancellationException) {
            usage?.finish(UsageOutcome.Cancelled, UsageStage.Preview)
            throw e
        } catch (e: Throwable) {
            unavailable(e)
        }
    }

    private suspend fun preflight() {
        val info = gl.value.info()
        model.manifest.scene?.let { scene -> SceneLimits.capabilityProblem(scene, info)?.let { throw UnsupportedScene(it) } }
    }

    fun request(request: FrameRequest) {
        if (request == lastRequest) return
        lastRequest = request
        requests.trySend(request)
    }

    suspend fun renderFrames() = withContext(Dispatchers.Default) {
        val thread = gl.value
        for (request in requests) {
            val frame = try {
                thread.run(discard = { it?.let(::drop) }) {
                    if (closed) null else renderer?.render(request)?.also { stats.rendered.incrementAndGet() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                stats.errors.incrementAndGet()
                waiting.getAndSet(null)?.let(::drop)
                release()
                unavailable(e)
                return@withContext
            } ?: return@withContext
            lastRendered = request
            waiting.getAndSet(frame)?.let(::drop)
            if (closed) waiting.getAndSet(null)?.let(::drop)
            arrivals.longValue++
            if (current.value is SessionState.FirstFrame) {
                current.value = SessionState.Showing
                usage?.finish(UsageOutcome.Completed, UsageStage.Preview)
            }
        }
    }

    private fun drop(frame: RenderedFrame) {
        frame.close()
        stats.dropped.incrementAndGet()
    }

    fun take(): RenderedFrame? {
        arrivals.longValue
        val next = waiting.getAndSet(null) ?: return shown
        shown?.close()
        shown = next
        return next
    }

    val settled: Boolean get() = lastRequest != null && lastRendered == lastRequest && waiting.get() == null

    suspend fun capture(request: FrameRequest): RenderedFrame? = withContext(Dispatchers.Default) {
        gl.value.run(discard = { it?.close() }) { if (closed) null else renderer?.render(request) }
    }

    fun close() {
        closed = true
        usage?.finish(UsageOutcome.Cancelled, UsageStage.Preview)
        requests.close()
        waiting.getAndSet(null)?.let(::drop)
        shown?.close()
        shown = null
        release()
    }

    private fun release() {
        if (gl.isInitialized()) gl.value.post {
            val released = renderer
            renderer = null
            released?.dispose()
        }
    }

    private suspend fun unavailable(problem: Throwable) {
        usage?.finish(UsageOutcome.Failed, UsageStage.Preview, UsageFailure.Other)
        val reason = problem.message ?: problem.javaClass.simpleName
        val still = try {
            model.fallbackPicture?.let { picture -> cache.asset(model.entry.bundleId, model.manifest.assets.first { it.path == picture.asset }) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        current.value = SessionState.Unavailable(reason, still)
    }
}

class FrameStats {
    val rendered = AtomicLong()
    val dropped = AtomicLong()
    val drawn = AtomicLong()
    val errors = AtomicLong()
    private val recent = ArrayDeque<FrameRequest>()

    fun drawn(frame: RenderedFrame) {
        drawn.incrementAndGet()
        synchronized(recent) {
            if (recent.size == Kept) recent.removeFirst()
            recent.addLast(frame.request)
        }
    }

    fun recent(): List<FrameRequest> = synchronized(recent) { recent.toList() }

    private companion object {
        const val Kept = 4096
    }
}
