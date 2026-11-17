package gg.hideouthq.feature.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.EscapeClaim
import gg.hideouthq.core.ui.PlaybackShortcut
import gg.hideouthq.core.ui.SearchFocus
import gg.hideouthq.core.ui.SidePanelOverlay
import gg.hideouthq.core.ui.WindowModal

/**
 * Window-wide keys and the mouse's side buttons. The platform window forwards its key events here: [onPreviewKeyEvent]
 * before the focused element sees them (shortcuts), [onKeyEvent] after it declines them (Esc, which a search field uses
 * first to clear its text or leave it, then an open side panel, then the page's [escape] claim, and only then Back; Space, which a focused button uses first), and its mouse button presses to [onPointerButton].
 * While a [modal] covers the window none of them acts, so the page behind it cannot move, search or play.
 */
class ShellKeys(
    private val navigator: HideoutNavigator,
    private val searchFocus: SearchFocus,
    private val playback: PlaybackShortcut,
    private val sidePanelOverlay: SidePanelOverlay,
    private val modal: WindowModal,
    private val escape: EscapeClaim = EscapeClaim(),
) {
    internal var toggleSidebar: (() -> Unit)? = null

    private val acting: Boolean get() = toggleSidebar != null && !modal.isOpen

    /**
     * Whether the last event was a shortcut's key press. AWT follows a press with the character it types, if any, as an
     * event of its own: Ctrl+Comma types ",", which a focused field would take.
     */
    private var shortcutPressed = false

    fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        val typedByShortcut = shortcutPressed && event.type == KeyEventType.Unknown
        shortcutPressed = false
        if (typedByShortcut) return true
        if (event.type != KeyEventType.KeyDown || !acting) return false
        val handled = when {
            event.isAltPressed && event.key == Key.DirectionLeft -> navigator.back()
            event.isAltPressed && event.key == Key.DirectionRight -> navigator.forward()
            (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.K -> searchFocus.request()
            (event.isCtrlPressed || event.isMetaPressed) && event.key == Key.Comma -> {
                navigator.navigate(Route.Settings)
                true
            }
            event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed && event.key == Key.B -> {
                toggleSidebar?.invoke()
                true
            }
            else -> false
        }
        // AltGr reads as Alt, and what it types belongs to the keyboard layout.
        shortcutPressed = handled && !event.isAltPressed
        return handled
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown || !acting) return false
        return when (event.key) {
            Key.Escape -> sidePanelOverlay.close() || escape.take() || navigator.back()
            Key.Spacebar -> playback.toggle()
            else -> false
        }
    }

    /** The mouse's Back and Forward side buttons step through history as alt+Left and alt+Right do. */
    fun onPointerButton(button: PointerButton): Boolean {
        if (!acting) return false
        return when (button) {
            PointerButton.Back -> navigator.back()
            PointerButton.Forward -> navigator.forward()
            else -> false
        }
    }
}

/** Hands [keys] the shell's sidebar toggle while this is composed. */
@Composable
fun ShellKeysEffect(keys: ShellKeys, toggleSidebar: () -> Unit) {
    val latest = rememberUpdatedState(toggleSidebar)
    DisposableEffect(keys) {
        keys.toggleSidebar = { latest.value() }
        onDispose { keys.toggleSidebar = null }
    }
}
