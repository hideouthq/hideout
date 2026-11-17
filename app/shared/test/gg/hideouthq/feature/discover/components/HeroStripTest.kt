package gg.hideouthq.feature.discover.components

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.discover.HeroItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeroStripTest {
    private val roster = (1..40).map { HeroItem("hero_$it", "Hero ${it.toString().padStart(2, '0')}", featured = false) }

    @Test
    fun aWiderStripShowsMoreOfTheRosterInItsOrderAtTheSameSpacing() {
        val normal = strip(roster, width = 748)
        val wide = strip(roster, width = 1204)

        assertEquals(1, normal.pitches.size, "the test widths leave no slack to share out")
        assertEquals(roster.take(10).map { it.name }, normal.names)
        assertEquals(roster.take(16).map { it.name }, wide.names)
        assertEquals(normal.pitches, wide.pitches, "the room between heroes stays the same")
        assertTrue(normal.allHeroes && wide.allHeroes, "the All entry stays")
        listOf(normal to 748, wide to 1204).forEach { (shown, width) -> assertTrue(shown.bounds.last().right <= width, "no hero is cut off at $width") }
    }

    @Test
    fun aStripWiderThanTheRosterShowsEveryHeroOnceAtTheSameSpacing() {
        val few = roster.take(12)
        val full = strip(roster, width = 748)
        val beyond = strip(few, width = 1964)

        assertEquals(few.map { it.name }, beyond.names)
        assertEquals(full.pitches, beyond.pitches, "heroes do not spread apart to fill the row")
    }

    private class Shown(val names: List<String>, val bounds: List<Rect>, val allHeroes: Boolean) {
        val pitches: Set<Float> get() = bounds.zipWithNext { a, b -> b.left - a.left }.toSet()
    }

    private fun strip(heroes: List<HeroItem>, width: Int): Shown {
        val scene = ImageComposeScene(width, 240, Density(1f)) {
            HideoutTheme(reducedMotion = true) { HeroStrip(heroes, heroes.size, onHero = {}, onAllHeroes = {}) }
        }
        try {
            repeat(3) { scene.render(it * 16_000_000L).close() }
            val nodes = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }.filter { SemanticsActions.OnClick in it.config }
            val names = heroes.map { it.name }.toSet()
            val shown = nodes.mapNotNull { node -> node.texts().firstOrNull { it in names }?.let { it to node.boundsInRoot } }.sortedBy { it.second.left }
            return Shown(shown.map { it.first }, shown.map { it.second }, nodes.any { "All ${heroes.size}" in it.texts() })
        } finally {
            scene.close()
        }
    }

    private fun SemanticsNode.texts(): List<String> = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
}
