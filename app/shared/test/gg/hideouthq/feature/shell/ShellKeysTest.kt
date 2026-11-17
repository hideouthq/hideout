@file:OptIn(InternalComposeUiApi::class)

package gg.hideouthq.feature.shell

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.EscapeClaim
import gg.hideouthq.core.ui.PlaybackShortcut
import gg.hideouthq.core.ui.SearchFocus
import gg.hideouthq.core.ui.SidePanelOverlay
import gg.hideouthq.core.ui.WindowModal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellKeysTest {
    private fun down(key: Key, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) =
        KeyEvent(key, KeyEventType.KeyDown, isAltPressed = alt, isCtrlPressed = ctrl, isShiftPressed = shift)

    private fun up(key: Key, ctrl: Boolean = false) = KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl)

    /** The character a key press types, which AWT sends as an event of its own right after the press. */
    private fun typed(char: Char, alt: Boolean = false, ctrl: Boolean = false) =
        KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = char.code, isAltPressed = alt, isCtrlPressed = ctrl)

    private val shortcuts = listOf(down(Key.DirectionLeft, alt = true), down(Key.Comma, ctrl = true), down(Key.K, ctrl = true), down(Key.B, ctrl = true))

    @Test
    fun whileAModalCoversTheWindowItsShortcutsActOnNothing() {
        val navigator = HideoutNavigator(Route.Discover).apply { navigate(Route.Library) }
        val playback = PlaybackShortcut()
        var toggles = 0
        playback.register { toggles++ }
        val overlay = SidePanelOverlay().apply { open() }
        val modal = WindowModal()
        var sidebarToggles = 0
        val keys = ShellKeys(navigator, SearchFocus(), playback, overlay, modal).apply { toggleSidebar = { sidebarToggles++ } }

        modal.hold("dialog")
        shortcuts.forEach { assertFalse(keys.onPreviewKeyEvent(it), "$it is left to the modal") }
        listOf(down(Key.Escape), down(Key.Spacebar)).forEach { assertFalse(keys.onKeyEvent(it), "$it is left to the modal") }
        assertFalse(keys.onPointerButton(PointerButton.Back), "the Back side button is left to the modal")
        assertEquals(Route.Library, navigator.current.route)
        assertTrue(overlay.isOpen, "Esc reaches the modal, not the side panel behind it")
        assertEquals(0, toggles)
        assertEquals(0, sidebarToggles)

        modal.release("dialog")
        assertTrue(keys.onKeyEvent(down(Key.Spacebar)))
        assertEquals(1, toggles)
        assertTrue(keys.onKeyEvent(down(Key.Escape)))
        assertFalse(overlay.isOpen)
        assertTrue(keys.onPreviewKeyEvent(down(Key.DirectionLeft, alt = true)))
        assertEquals(Route.Discover, navigator.current.route)
    }

    @Test
    fun theKeysActOnlyWhileTheShellShows() {
        val navigator = HideoutNavigator(Route.Discover).apply { navigate(Route.Library) }
        val playback = PlaybackShortcut()
        var toggles = 0
        playback.register { toggles++ }
        val keys = ShellKeys(navigator, SearchFocus(), playback, SidePanelOverlay(), WindowModal())

        shortcuts.forEach { assertFalse(keys.onPreviewKeyEvent(it), "$it before the shell shows") }
        listOf(down(Key.Escape), down(Key.Spacebar)).forEach { assertFalse(keys.onKeyEvent(it), "$it before the shell shows") }
        assertFalse(keys.onPointerButton(PointerButton.Back), "the Back side button before the shell shows")
        assertEquals(Route.Library, navigator.current.route)
        assertEquals(0, toggles)

        var sidebarToggles = 0
        keys.toggleSidebar = { sidebarToggles++ }
        assertTrue(keys.onPreviewKeyEvent(down(Key.B, ctrl = true)))
        assertEquals(1, sidebarToggles)
        assertTrue(keys.onKeyEvent(down(Key.Escape)))
        assertEquals(Route.Discover, navigator.current.route)
        assertTrue(keys.onPreviewKeyEvent(down(Key.Comma, ctrl = true)))
        assertEquals(Route.Settings, navigator.current.route)

        keys.toggleSidebar = null
        shortcuts.forEach { assertFalse(keys.onPreviewKeyEvent(it), "$it once the shell has gone") }
        assertFalse(keys.onPointerButton(PointerButton.Back), "the Back side button once the shell has gone")
        assertEquals(Route.Settings, navigator.current.route)
        assertEquals(1, sidebarToggles)
    }

    @Test
    fun escLetsGoOfWhatThePageClaimsBeforeItGoesBack() {
        val navigator = HideoutNavigator(Route.Discover).apply { navigate(Route.Library) }
        val escape = EscapeClaim()
        val keys = ShellKeys(navigator, SearchFocus(), PlaybackShortcut(), SidePanelOverlay(), WindowModal(), escape).apply { toggleSidebar = {} }
        var cleared = 0
        escape.claim = { cleared++ }

        assertTrue(keys.onKeyEvent(down(Key.Escape)))
        assertEquals(1, cleared, "a multi-selection goes first")
        assertEquals(Route.Library, navigator.current.route, "and the page stays")

        escape.claim = null
        assertTrue(keys.onKeyEvent(down(Key.Escape)))
        assertEquals(Route.Discover, navigator.current.route, "with nothing claimed, Esc goes back")
    }

    @Test
    fun ctrlBTogglesTheSidebarOnlyWithCtrlAlone() {
        var sidebarToggles = 0
        val keys = ShellKeys(HideoutNavigator(Route.Discover), SearchFocus(), PlaybackShortcut(), SidePanelOverlay(), WindowModal())
            .apply { toggleSidebar = { sidebarToggles++ } }

        assertFalse(keys.onPreviewKeyEvent(down(Key.B, ctrl = true, alt = true)))
        assertFalse(keys.onPreviewKeyEvent(down(Key.B, ctrl = true, shift = true)))
        assertFalse(keys.onPreviewKeyEvent(down(Key.B)))
        assertEquals(0, sidebarToggles)
        assertTrue(keys.onPreviewKeyEvent(down(Key.B, ctrl = true)))
        assertTrue(keys.onPreviewKeyEvent(down(Key.B, ctrl = true)))
        assertEquals(2, sidebarToggles)
    }

    @Test
    fun theCharacterAShortcutTypesNeverReachesTheFocusedField() {
        val navigator = HideoutNavigator(Route.Library)
        val keys = ShellKeys(navigator, SearchFocus(), PlaybackShortcut(), SidePanelOverlay(), WindowModal())

        assertFalse(keys.onPreviewKeyEvent(down(Key.Comma, ctrl = true)), "no shortcut before the shell shows")
        assertFalse(keys.onPreviewKeyEvent(typed(',', ctrl = true)), "so the field takes the comma")

        keys.toggleSidebar = {}
        assertFalse(keys.onPreviewKeyEvent(down(Key.Comma)))
        assertFalse(keys.onPreviewKeyEvent(typed(',')), "a comma typed on its own")
        assertTrue(keys.onPreviewKeyEvent(down(Key.Comma, ctrl = true)))
        assertEquals(Route.Settings, navigator.current.route)
        assertTrue(keys.onPreviewKeyEvent(typed(',', ctrl = true)), "Ctrl+Comma's comma goes with the shortcut")
        assertFalse(keys.onPreviewKeyEvent(up(Key.Comma, ctrl = true)))
        assertFalse(keys.onPreviewKeyEvent(down(Key.Comma)))
        assertFalse(keys.onPreviewKeyEvent(typed(',')), "the next comma typed")

        keys.onPreviewKeyEvent(down(Key.Comma, alt = true, ctrl = true))
        assertFalse(keys.onPreviewKeyEvent(typed(';', alt = true, ctrl = true)), "AltGr, which Windows sends as Ctrl+Alt, types what the layout says")
    }

    @Test
    fun theSideButtonsStepThroughHistoryAsAltLeftAndAltRightDo() {
        val navigator = HideoutNavigator(Route.Discover).apply { navigate(Route.Library); navigate(Route.Settings) }
        val keys = ShellKeys(navigator, SearchFocus(), PlaybackShortcut(), SidePanelOverlay(), WindowModal()).apply { toggleSidebar = {} }

        assertTrue(keys.onPointerButton(PointerButton.Back))
        assertEquals(Route.Library, navigator.current.route)
        assertTrue(keys.onPreviewKeyEvent(down(Key.DirectionLeft, alt = true)))
        assertEquals(Route.Discover, navigator.current.route)
        assertFalse(keys.onPointerButton(PointerButton.Back), "nothing is further back")
        assertFalse(keys.onPreviewKeyEvent(down(Key.DirectionLeft, alt = true)), "nothing is further back")

        assertTrue(keys.onPointerButton(PointerButton.Forward))
        assertEquals(Route.Library, navigator.current.route)
        assertTrue(keys.onPreviewKeyEvent(down(Key.DirectionRight, alt = true)))
        assertEquals(Route.Settings, navigator.current.route)
        assertFalse(keys.onPointerButton(PointerButton.Forward), "nothing is further forward")

        listOf(PointerButton.Primary, PointerButton.Secondary, PointerButton.Tertiary).forEach {
            assertFalse(keys.onPointerButton(it), "$it is not a history button")
        }
        assertEquals(Route.Settings, navigator.current.route)
    }
}
