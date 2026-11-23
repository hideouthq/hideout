package gg.hideouthq.feature.library.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoFillGridTest {
    @Test
    fun threeCardsShareARowAtEveryWidthThatFitsThree() {
        for (width in 714..955) assertEquals(listOf(3, 1), rows(width, cards = 4), "cards per row at $width px")
    }

    @Test
    fun aCardAloneKeepsItsColumnWidth() {
        val card = shown(816, cards = 1).single()
        assertEquals(264f, card.width, "one card at 816 px takes one of three columns")
    }

    private fun rows(width: Int, cards: Int): List<Int> = shown(width, cards).groupBy { it.top }.toSortedMap().values.map { it.size }

    private fun shown(width: Int, cards: Int): List<Rect> {
        val scene = ImageComposeScene(width, 1000, Density(1f)) {
            HideoutTheme(reducedMotion = true) {
                AutoFillGrid {
                    repeat(cards) { Box(Modifier.height(40.dp).semantics { contentDescription = "card $it" }) }
                }
            }
        }
        try {
            scene.render().close()
            return scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }
                .filter { node -> node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.startsWith("card ") } }
                .map { it.boundsInRoot }
        } finally {
            scene.close()
        }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
