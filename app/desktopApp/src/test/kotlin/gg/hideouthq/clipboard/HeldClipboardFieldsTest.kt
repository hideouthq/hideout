@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class, ExperimentalCoroutinesApi::class)

package gg.hideouthq.clipboard

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import gg.hideouthq.core.ui.AppPopup
import gg.hideouthq.core.ui.PatientClipboard
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeldClipboardFieldsTest {
    @Test
    fun copyDuringAHoldThrowsNothing() = forEveryField { field ->
        field.key(Key.A, ctrl = true)
        field.key(Key.C, ctrl = true)
        field.waitOutTheRetries()
        field.assertNothingThrown()
        assertEquals("abc", field.text())
    }

    @Test
    fun pasteDuringAHoldLeavesTheFieldAndTheNextCharacterStillTypes() = forEveryField { field ->
        field.key(Key.V, ctrl = true)
        field.waitOutTheRetries()
        field.assertNothingThrown()
        assertEquals("abc", field.text())
        field.type('x')
        assertEquals("abcx", field.text())
    }

    @Test
    fun aCharacterTypedWhileAPasteWaitsStillLands() = forEveryField { field ->
        field.key(Key.V, ctrl = true)
        field.type('x')
        field.waitOutTheRetries()
        field.assertNothingThrown()
        assertEquals("abcx", field.text())
    }

    @Test
    fun cutDuringAHoldThrowsNothing() = forEveryField { field ->
        field.key(Key.A, ctrl = true)
        field.key(Key.X, ctrl = true)
        field.waitOutTheRetries()
        field.assertNothingThrown()
    }

    @Test
    fun openingTheContextMenuDuringAHoldThrowsNothing() = forEveryField { field ->
        field.rightClick()
        field.waitOutTheRetries()
        field.assertNothingThrown()
    }

    @Test
    fun pasteLandsOnceTheOtherProgramLetsGo() = forEveryField { field ->
        field.board.text = "!"
        field.board.heldFor = 2
        field.key(Key.V, ctrl = true)
        field.waitOutTheRetries()
        field.until("the paste to land") { field.text() == "abc!" }
        field.assertNothingThrown()
    }

    private fun forEveryField(check: (FieldScene) -> Unit) {
        for (popup in listOf(false, true)) {
            for (kind in FieldKind.entries) {
                val scene = FieldScene(kind, popup)
                try {
                    check(scene)
                } catch (failure: AssertionError) {
                    throw AssertionError("$kind, ${if (popup) "in a popup" else "in the window"}: ${failure.message}", failure)
                } finally {
                    scene.close()
                }
            }
        }
    }
}

private enum class FieldKind { Value, State }

private val TopLeft = object : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset.Zero
}

private class FieldScene(kind: FieldKind, popup: Boolean) {
    val board = HeldBoard()
    private val thrown = mutableListOf<Throwable>()
    private val scheduler = TestCoroutineScheduler()
    private var clock = 0L
    private var value by mutableStateOf(TextFieldValue("abc", TextRange(3)))
    private val state = TextFieldState("abc", TextRange(3))
    private val isValue = kind == FieldKind.Value

    private val scene = ImageComposeScene(
        400, 200, Density(1f), StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, failure -> thrown += failure },
    ) {
        val clipboard = remember { PatientClipboard(board) }
        CompositionLocalProvider(LocalClipboard provides clipboard) {
            if (popup) {
                AppPopup(TopLeft, properties = PopupProperties(focusable = true)) { Field() }
            } else {
                Field()
            }
        }
    }

    init {
        settle(100)
    }

    @Composable
    private fun Field() {
        val focus = remember { FocusRequester() }
        val modifier = Modifier.size(300.dp, 60.dp).focusRequester(focus)
        if (isValue) BasicTextField(value, { value = it }, modifier) else BasicTextField(state, modifier)
        LaunchedEffect(Unit) { focus.requestFocus() }
    }

    fun text(): String = if (isValue) value.text else state.text.toString()

    fun key(key: Key, ctrl: Boolean = false) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
            scene.sendKeyEvent(KeyEvent(key, type, isCtrlPressed = ctrl))
        }
        settle(16)
    }

    fun type(char: Char) {
        val typed = java.awt.event.KeyEvent(java.awt.Canvas(), java.awt.event.KeyEvent.KEY_TYPED, clock, 0, java.awt.event.KeyEvent.VK_UNDEFINED, char)
        scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, nativeEvent = typed))
        settle(16)
    }

    fun rightClick() {
        val at = Offset(20f, 10f)
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Secondary)
        settle(16)
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Secondary)
        settle(16)
    }

    fun waitOutTheRetries() = settle(1_000)

    fun until(what: String, met: () -> Boolean) {
        repeat(200) {
            if (met()) return
            Thread.sleep(10)
            settle(16)
        }
        throw AssertionError("gave up waiting for $what, the field holds ${text()}")
    }

    fun assertNothingThrown() = assertTrue(thrown.isEmpty(), "thrown: $thrown")

    private fun settle(millis: Long) {
        val end = clock + millis
        while (clock < end) {
            clock += 16
            scheduler.advanceTimeBy(16)
            scheduler.runCurrent()
            scene.render(clock * 1_000_000).close()
            scheduler.runCurrent()
        }
    }

    fun close() = scene.close()
}

/** Refuses every read and write the way AWT does while another program has the Windows clipboard, until [heldFor] runs out. */
private class HeldBoard(var heldFor: Int = Int.MAX_VALUE, var text: String? = null) : Clipboard {
    private fun refuseWhileHeld() = check(heldFor-- <= 0) { "cannot open system clipboard" }

    override suspend fun getClipEntry(): ClipEntry? {
        refuseWhileHeld()
        return text?.let { ClipEntry(StringSelection(it)) }
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = refuseWhileHeld()

    override val nativeClipboard: NativeClipboard = object : java.awt.datatransfer.Clipboard("held") {
        override fun getContents(requestor: Any?): Transferable? {
            refuseWhileHeld()
            return text?.let(::StringSelection)
        }

        override fun setContents(contents: Transferable, owner: ClipboardOwner?) = refuseWhileHeld()

        override fun getAvailableDataFlavors(): Array<DataFlavor> {
            refuseWhileHeld()
            return getContents(null)?.transferDataFlavors ?: emptyArray()
        }

        override fun isDataFlavorAvailable(flavor: DataFlavor): Boolean {
            refuseWhileHeld()
            return getContents(null)?.isDataFlavorSupported(flavor) == true
        }
    }
}
