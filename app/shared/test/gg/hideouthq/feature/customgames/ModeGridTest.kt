package gg.hideouthq.feature.customgames

import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModeGridTest {
    private val cards = libraryView(ModeList(feed = FakeCustomGamesFeed.Placeholder), ModeFilter(), LocalDate.of(2026, 11, 6)).shown

    @Test
    fun everyCardInALibraryRowIsAsTallAsTheTallestWhateverItsTaglineLength() {
        assertRowsLineUp(width = 1100)
        assertRowsLineUp(width = 540)
    }

    @Test
    fun everyCardInDiscoversRowIsAsTallAsTheTallestWhateverItsTaglineLength() {
        assertRowsLineUp(width = 1100, minCell = HideoutDimens.Card.MinColumnDiscover, maxColumns = 4)
        assertRowsLineUp(width = 1300, minCell = HideoutDimens.Card.MinColumnDiscover, maxColumns = 4)
    }

    private fun assertRowsLineUp(width: Int, minCell: Dp = 250.dp, maxColumns: Int = Int.MAX_VALUE) {
        val shown = grid(width, minCell, maxColumns)
        assertEquals(cards.size, shown.size, "every card shows at $width")
        val rows = shown.groupBy { it.name.top }.values
        assertTrue(cards.map { it.listing.tagline.length }.distinct().size > 1, "the taglines differ in length")
        for (row in rows) {
            assertEquals(1, row.map { it.taglineHeight }.distinct().size, "the taglines at $width keep the same room: ${row.map { it.label to it.taglineHeight }}")
            assertEquals(1, row.map { it.byLine.bottom }.distinct().size, "the by-lines at $width end on one line: ${row.map { it.label to it.byLine.bottom }}")
        }
        val bebop = shown.single { it.card != null }
        assertEquals(rows.single { bebop in it }.maxOf { it.byLine.bottom }, bebop.card!!.bottom, "Bebop Wars's card at $width ends with the row's by-lines")
    }

    private class Shown(val label: String, val name: Rect, val tagline: Rect, val byLine: Rect, val card: Rect?) {
        val taglineHeight get() = tagline.height
    }

    private fun grid(width: Int, minCell: Dp, maxColumns: Int): List<Shown> {
        val scene = ImageComposeScene(width, 2000, Density(1f)) {
            HideoutTheme(reducedMotion = true) {
                CompositionLocalProvider(LocalModCardStyle provides ModCardStyle.UnderImage) { ModeGrid(cards, onOpen = {}, minCell = minCell, maxColumns = maxColumns) }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L).close() }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
            val clickable = nodes.filter { SemanticsActions.OnClick in it.config }.map { it.boundsInRoot }
            return cards.map { card ->
                val listing = card.listing
                val name = nodes.single { listing.name in it.texts() }.boundsInRoot
                val inCard = nodes.filter { it.boundsInRoot.left == name.left && it.boundsInRoot.top >= name.top }
                Shown(
                    listing.name,
                    name,
                    inCard.single { listing.tagline in it.texts() }.boundsInRoot,
                    nodes.filter { node -> node.boundsInRoot.left >= name.left && node.boundsInRoot.top >= name.top && node.texts().any { it.startsWith("by ") } }
                        .minBy { it.boundsInRoot.top * 10_000 + it.boundsInRoot.left }.boundsInRoot,
                    clickable.singleOrNull { it.contains(name.center) },
                )
            }
        } finally {
            scene.close()
        }
    }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
