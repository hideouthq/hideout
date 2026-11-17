@file:OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)

package gg.hideouthq.feature.customgames

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.WindowModal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenshotsTest {
    private val shots = listOf("hook", "hook-tight", "shop").map { "https://example.test/bebop-wars-shot-$it.jpg" }
    private val modal = WindowModal()
    private var clock = 0L
    private val scene = ImageComposeScene(1200, 800, Density(1f)) {
        HideoutTheme(reducedMotion = true) { Column(Modifier.fillMaxSize()) { Screenshots("bebop-wars", shots, modal) } }
    }

    @AfterTest
    fun close() = scene.close()

    @Test
    fun clickingAScreenshotOpensItLargeAndTheArrowsAndEscapeWorkTheViewer() {
        settle()
        val second = node("Screenshot 2 of 3")
        assertEquals(Role.Button, second.config.getOrNull(SemanticsProperties.Role), "a screenshot is a button to keyboards and screen readers")
        assertNull(position(), "the viewer starts closed")

        click(second)
        assertEquals("2 of 3", position(), "the viewer opens on the screenshot clicked")
        assertTrue(modal.isOpen)

        key(Key.DirectionRight)
        assertEquals("3 of 3", position())
        key(Key.DirectionRight)
        assertEquals("3 of 3", position(), "the last stays the last")
        key(Key.DirectionLeft)
        key(Key.DirectionLeft)
        assertEquals("1 of 3", position())

        key(Key.Escape)
        assertNull(position(), "Escape closes the viewer")
        assertFalse(modal.isOpen)
    }

    private fun settle() = repeat(4) {
        scene.render(clock * 1_000_000L).close()
        clock += 16
    }

    private fun nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }

    private fun node(description: String): SemanticsNode =
        nodes().singleOrNull { description in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            ?: throw AssertionError("no single \"$description\" on screen")

    private fun position(): String? =
        nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }.singleOrNull { Position.matches(it) }

    private fun click(node: SemanticsNode) {
        val at = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Move, at)
        scene.sendPointerEvent(PointerEventType.Press, at, button = PointerButton.Primary)
        settle()
        scene.sendPointerEvent(PointerEventType.Release, at, button = PointerButton.Primary)
        settle()
    }

    private fun key(key: Key) {
        for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) scene.sendKeyEvent(KeyEvent(key, type))
        settle()
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private companion object {
        val Position = Regex("""\d of 3""")
    }
}
