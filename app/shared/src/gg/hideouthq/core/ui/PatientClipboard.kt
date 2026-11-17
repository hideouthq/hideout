package gg.hideouthq.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.awtClipboard
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.key.KeyEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.awt.datatransfer.ClipboardOwner
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.Clipboard as AwtClipboard

private val WaitsAfterEachTryMillis = listOf(50L, 100L, 200L, 400L, 0L)

/**
 * The clipboard the whole app and Compose's text fields use. Windows lets one program at a time open the clipboard and AWT
 * throws while another has it, usually for milliseconds, so this tries again for a moment. While it is still held, a read
 * finds nothing and a write does nothing. Only IllegalStateException means held, so a NoSuchMethodError from missing native
 * metadata stays loud.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PatientClipboard(private val system: Clipboard) : Clipboard {
    override suspend fun getClipEntry(): ClipEntry? = tryUntilLetGo(whenHeld = null) { system.getClipEntry() }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        write(clipEntry)
    }

    /** Says whether [clipEntry] got there, which is what [setClipEntry] can't tell its caller. */
    suspend fun write(clipEntry: ClipEntry?): Boolean = tryUntilLetGo(whenHeld = false) {
        system.setClipEntry(clipEntry)
        true
    }

    /** Compose asks this directly what is on the clipboard before it shows a text field's menu. */
    override val nativeClipboard: NativeClipboard by lazy { system.awtClipboard?.let(::PatientAwtClipboard) ?: system.nativeClipboard }
}

fun Clipboard.patient(): PatientClipboard = this as? PatientClipboard ?: PatientClipboard(this)

@Composable
fun rememberPatientClipboard(): PatientClipboard {
    val current = LocalClipboard.current
    return remember(current) { current.patient() }
}

/**
 * The only Popup the app uses. Compose gives each popup a layer that provides a bare LocalClipboard of its own, so without
 * this a text field in a dialog would miss the [PatientClipboard] at the root.
 */
@Composable
fun AppPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: (() -> Unit)? = null,
    properties: PopupProperties = PopupProperties(),
    onPreviewKeyEvent: (KeyEvent) -> Boolean = { false },
    content: @Composable () -> Unit,
) {
    val clipboard = LocalClipboard.current
    Popup(popupPositionProvider, onDismissRequest, properties, onPreviewKeyEvent, onKeyEvent = { false }) {
        CompositionLocalProvider(LocalClipboard provides clipboard, content)
    }
}

/** Puts [text] on the system clipboard and says whether it got there. */
@OptIn(ExperimentalComposeUiApi::class)
suspend fun Clipboard.copyText(text: String): Boolean = patient().write(ClipEntry(StringSelection(text)))

private suspend fun <T> tryUntilLetGo(whenHeld: T, action: suspend () -> T): T {
    for (wait in WaitsAfterEachTryMillis) {
        try {
            return action()
        } catch (cancelled: CancellationException) {
            // Java's CancellationException is an IllegalStateException, and a cancelled action is not a held clipboard.
            throw cancelled
        } catch (_: IllegalStateException) {
            delay(wait)
        }
    }
    return whenHeld
}

/** These calls can't suspend, so they answer once, as if the clipboard were empty while it is held. */
private class PatientAwtClipboard(private val system: AwtClipboard) : AwtClipboard(system.name) {
    override fun getContents(requestor: Any?): Transferable? = unlessHeld(null) { system.getContents(requestor) }

    override fun setContents(contents: Transferable, owner: ClipboardOwner?) = unlessHeld(Unit) { system.setContents(contents, owner) }

    override fun getAvailableDataFlavors(): Array<DataFlavor> = unlessHeld(emptyArray()) { system.availableDataFlavors }

    override fun isDataFlavorAvailable(flavor: DataFlavor): Boolean = unlessHeld(false) { system.isDataFlavorAvailable(flavor) }
}

private inline fun <T> unlessHeld(whenHeld: T, read: () -> T): T = try {
    read()
} catch (_: IllegalStateException) {
    whenHeld
}
