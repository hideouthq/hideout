package gg.hideouthq.viewer

import com.badlogic.gdx.graphics.Cubemap
import com.badlogic.gdx.graphics.Mesh
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.glutils.GLFrameBuffer
import com.badlogic.gdx.graphics.glutils.ShaderProgram
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The test JVM's one GL thread, which every test that draws shares: libGDX keeps its context in globals. Without an
 * OpenGL context those tests are skipped, except where one is required: when `hideout.test.requireGl` is true, or
 * when a display is set. There, a GL thread that cannot start fails them.
 */
internal object TestGl {
    val reported: MutableList<Throwable> = CopyOnWriteArrayList()

    val thread: GlThread by lazy { GlThread { reported += it } }

    private val started: Result<GlInfo> by lazy {
        runCatching { runBlocking { thread.info() } }.onSuccess { println("GL tests draw with ${it.renderer}, OpenGL ${it.version}") }
    }

    private val required: String? = when {
        System.getProperty("hideout.test.requireGl").toBoolean() -> "hideout.test.requireGl is true"
        else -> listOf("DISPLAY", "WAYLAND_DISPLAY").firstNotNullOfOrNull { name -> System.getenv(name)?.takeIf { it.isNotEmpty() }?.let { "$name=$it" } }
    }

    fun info(): GlInfo {
        started.exceptionOrNull()?.let { problem ->
            if (required != null) throw AssertionError("An OpenGL context is required ($required), but the GL thread did not start", problem)
            assumeTrue("No OpenGL context here: ${problem.message}", false)
        }
        return started.getOrThrow()
    }

    fun managedObjects(): Map<String, Int> = listOf(
        Mesh.getManagedStatus(),
        GLFrameBuffer.getManagedStatus(),
        ShaderProgram.getManagedStatus(),
        Texture.getManagedStatus(),
        Cubemap.getManagedStatus(),
    ).associate { status -> status.substringBefore(':') to Regex("\\d+").findAll(status.substringAfter('{')).sumOf { it.value.toInt() } }
}
