package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.awt.EventQueue
import java.awt.Toolkit
import java.io.File
import javax.swing.Timer
import kotlin.coroutines.CoroutineContext

private const val DefaultScale = 2f
private const val FrameMillis = 16L
private const val TabLimit = 60

internal class WindowKeys(val preview: (KeyEvent) -> Boolean, val after: (KeyEvent) -> Boolean)

internal class Script(
    val scene: ImageComposeScene, val dir: File, val scale: Float,
    private val captureBeforeCoordinates: Boolean = false,
    private val windowKeys: WindowKeys? = null,
) {
    var time = 0L
    private var frame = 0
    private var pointer = Offset.Zero

    fun advance(millis: Long) {
        val end = time + millis
        while (time < end) {
            time += FrameMillis
            scene.render(time * 1_000_000).close()
            runUiQueue()
        }
    }

    fun moveTo(node: SemanticsNode) {
        val centre = node.boundsInRoot.center
        moveToDp(centre.x / scale, centre.y / scale)
    }

    fun moveToDp(x: Float, y: Float) {
        if (captureBeforeCoordinates) capture("before_pointer")
        pointer = Offset(x * scale, y * scale)
        scene.sendPointerEvent(PointerEventType.Move, pointer, timeMillis = time)
        advance(FrameMillis)
    }

    fun dragByDp(dx: Float, dy: Float, steps: Int = 12, shift: Boolean = false, button: PointerButton = PointerButton.Primary) {
        press(shift, button)
        advance(FrameMillis * 2)
        moveByDp(dx, dy, steps, shift, button)
    }

    fun moveByDp(dx: Float, dy: Float, steps: Int = 12, shift: Boolean = false, button: PointerButton? = null) {
        repeat(steps) {
            pointer += Offset(dx * scale / steps, dy * scale / steps)
            scene.sendPointerEvent(
                PointerEventType.Move, pointer, timeMillis = time,
                buttons = buttons(button), keyboardModifiers = modifiers(shift),
            )
            advance(FrameMillis)
        }
    }

    fun scroll(delta: Float) {
        scene.sendPointerEvent(PointerEventType.Scroll, pointer, scrollDelta = Offset(0f, delta), timeMillis = time)
        advance(FrameMillis)
    }

    @OptIn(InternalComposeUiApi::class)
    fun type(text: String) {
        text.forEach { char ->
            listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
                deliver(KeyEvent(Key.Unknown, type, codePoint = char.code))
            }
            val typed = java.awt.event.KeyEvent(
                java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, time, 0,
                java.awt.event.KeyEvent.VK_UNDEFINED, char,
            )
            deliver(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = typed))
            advance(FrameMillis * 3)
        }
    }

    @OptIn(InternalComposeUiApi::class)
    fun key(key: Key, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        listOf(KeyEventType.KeyDown, KeyEventType.KeyUp).forEach { type ->
            deliver(KeyEvent(key, type, isAltPressed = alt, isCtrlPressed = ctrl, isShiftPressed = shift))
        }
        advance(FrameMillis * 3)
    }

    fun focused(): SemanticsNode? = nodes().lastOrNull { it.config.getOrElseNullable(SemanticsProperties.Focused) { null } == true }

    fun tabTo(description: String, matches: (SemanticsNode) -> Boolean): SemanticsNode {
        repeat(TabLimit) {
            focused()?.takeIf(matches)?.let { return it }
            key(Key.Tab)
            checkNotNull(focused()) { "Tab left nothing focused" }
        }
        error("Tab never reached $description")
    }

    private fun deliver(event: KeyEvent): Boolean {
        val window = windowKeys ?: return scene.sendKeyEvent(event)
        return window.preview(event) || scene.sendKeyEvent(event) || window.after(event)
    }

    fun click(button: PointerButton = PointerButton.Primary, ctrl: Boolean = false, shift: Boolean = false) {
        press(shift, button, ctrl)
        advance(FrameMillis * 4)
        release(shift, button, ctrl)
    }

    fun rightClick() = click(PointerButton.Secondary)

    fun press(shift: Boolean = false, button: PointerButton = PointerButton.Primary, ctrl: Boolean = false) {
        scene.sendPointerEvent(
            PointerEventType.Press, pointer, timeMillis = time,
            buttons = buttons(button), keyboardModifiers = modifiers(shift, ctrl), button = button,
        )
    }

    fun release(shift: Boolean = false, button: PointerButton = PointerButton.Primary, ctrl: Boolean = false) {
        scene.sendPointerEvent(
            PointerEventType.Release, pointer, timeMillis = time,
            buttons = PointerButtons(), keyboardModifiers = modifiers(shift, ctrl), button = button,
        )
    }

    private fun buttons(button: PointerButton?) = PointerButtons(
        isPrimaryPressed = button == PointerButton.Primary,
        isSecondaryPressed = button == PointerButton.Secondary,
        isTertiaryPressed = button == PointerButton.Tertiary,
    )

    private fun modifiers(shift: Boolean, ctrl: Boolean = false) = PointerKeyboardModifiers(isCtrlPressed = ctrl, isShiftPressed = shift)

    fun capture(label: String) {
        val png = scene.render(time * 1_000_000).use { requireNotNull(it.encodeToData(EncodedImageFormat.PNG)) }
        File(dir, "%02d_%s.png".format(frame++, label)).writeBytes(png.bytes)
    }
}

/**
 * Compose for desktop posts the layout bookkeeping it defers, such as RectManager's dispatch, to the AWT event thread,
 * so a scene driven from any other thread has that work run beside its own layout passes on the same unsynchronised
 * structures.
 */
internal fun <T> onUiThread(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    var result: Result<T>? = null
    EventQueue.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}

internal fun runUiQueue(millis: Long = 0) {
    val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
    Timer(millis.toInt()) { loop.exit() }.apply { isRepeats = false }.start()
    loop.enter()
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun runScript(
    outDir: File, name: String, widthDp: Int, heightDp: Int, content: @Composable () -> Unit,
    scale: Float = DefaultScale,
    outputDirectory: File = File(outDir, "film/$name"),
    captureBeforeCoordinates: Boolean = false,
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    windowKeys: WindowKeys? = null,
    script: Script.() -> Unit,
) = onUiThread {
    val dir = outputDirectory.apply { mkdirs() }
    val scene = ImageComposeScene((widthDp * scale).toInt(), (heightDp * scale).toInt(), Density(scale), coroutineContext, content)
    try {
        Script(scene, dir, scale, captureBeforeCoordinates, windowKeys).apply {
            advance(600)
            try {
                script()
            } catch (failure: Throwable) {
                runCatching { capture("failed") }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    } catch (failure: Throwable) {
        runCatching { scene.close() }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
    scene.close()
    println("Filmed $name -> ${dir.absolutePath}")
}
