package gg.hideouthq.viewer

import com.badlogic.gdx.Application
import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.utils.BufferUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.resumeWithException

data class GlInfo(
    val version: String,
    val renderer: String,
    /** The context's version as libGDX parses it, which is what gdx-gltf reads to write its shaders in GLSL 1.30. */
    val major: Int,
    val minor: Int,
    val maxVertexTextureImageUnits: Int,
    val maxRenderbufferSize: Int,
    val maxTextureSize: Int,
    val bc7: Boolean,
)

class GlThread(private val report: (Throwable) -> Unit = ::printFailure) {
    private val started = CompletableDeferred<GlInfo>()
    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val jobs = ArrayDeque<Job>()
    private var stopped: Throwable? = null
    private val thread = Thread(::loop, "hideout-gl").apply { isDaemon = true }

    val liveScenes = AtomicInteger()

    val openFrames = AtomicInteger()

    val queued: Int get() = lock.withLock { jobs.size }

    init {
        thread.start()
    }

    suspend fun info(): GlInfo = started.await()

    suspend fun <T> run(discard: (T) -> Unit = {}, job: () -> T): T {
        info()
        return suspendCancellableCoroutine { caller ->
            enqueue(
                Job(
                    work = {
                        runCatching(job).fold(
                            onSuccess = { caller.resume(it) { _, unclaimed, _ -> discard(unclaimed) } },
                            onFailure = { caller.resumeWithException(it) },
                        )
                    },
                    abandon = { caller.resumeWithException(it) },
                ),
            )
        }
    }

    fun post(job: () -> Unit) = enqueue(Job(work = job, abandon = {}))

    private fun enqueue(job: Job) {
        val cause = lock.withLock {
            if (stopped == null) {
                jobs.addLast(job)
                available.signal()
            }
            stopped
        }
        if (cause != null) job.abandon(IllegalStateException("The GL thread has stopped", cause))
    }

    private fun drain() {
        while (true) {
            val job = lock.withLock { jobs.removeFirstOrNull() } ?: return
            try {
                job.work()
            } catch (t: Throwable) {
                report(t)
            }
        }
    }

    private fun stop(cause: Throwable) {
        val abandoned = lock.withLock {
            if (stopped != null) return
            stopped = cause
            jobs.toList().also { jobs.clear() }
        }
        started.completeExceptionally(cause)
        val failure = IllegalStateException("The GL thread has stopped", cause)
        abandoned.forEach { it.abandon(failure) }
    }

    private fun loop() {
        try {
            object : Lwjgl3Application(Listener(), configuration()) {
                override fun cleanup() = Unit
            }
        } catch (t: Throwable) {
            stop(t)
        } finally {
            stop(IllegalStateException("The libGDX loop ended"))
        }
    }

    private inner class Listener : ApplicationAdapter() {
        override fun create() {
            Gdx.app.logLevel = Application.LOG_ERROR
            started.complete(readInfo())
        }

        override fun render() {
            lock.withLock {
                try {
                    if (jobs.isEmpty()) available.await(IdleWaitMillis, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                }
            }
            drain()
        }
    }

    private fun configuration() = Lwjgl3ApplicationConfiguration().apply {
        setTitle("Hideout renderer")
        setWindowedMode(1, 1)
        setInitialVisible(false)
        setDecorated(false)
        disableAudio(true)
        useVsync(false)
        setForegroundFPS(0)
        setPauseWhenLostFocus(false)
        setPauseWhenMinimized(false)
        setWindowListener(object : Lwjgl3WindowAdapter() {
            override fun closeRequested() = false
        })
    }

    private fun readInfo() = GlInfo(
        version = Gdx.gl.glGetString(GL20.GL_VERSION),
        renderer = Gdx.gl.glGetString(GL20.GL_RENDERER),
        major = Gdx.graphics.glVersion.majorVersion,
        minor = Gdx.graphics.glVersion.minorVersion,
        maxVertexTextureImageUnits = glInteger(GL20.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS),
        maxRenderbufferSize = glInteger(GL20.GL_MAX_RENDERBUFFER_SIZE),
        maxTextureSize = glInteger(GL20.GL_MAX_TEXTURE_SIZE),
        bc7 = Gdx.graphics.glVersion.isVersionEqualToOrHigher(4, 2) || Gdx.graphics.supportsExtension("GL_ARB_texture_compression_bptc"),
    )

    private class Job(val work: () -> Unit, val abandon: (Throwable) -> Unit)

    private companion object {
        const val IdleWaitMillis = 250L
    }
}

internal fun glInteger(name: Int): Int {
    val value = BufferUtils.newIntBuffer(16)
    Gdx.gl.glGetIntegerv(name, value)
    return value[0]
}

private fun printFailure(failure: Throwable) {
    System.err.println("hideout-gl: a queued job failed")
    failure.printStackTrace()
}
