package gg.hideouthq.feature.stats.components

import kotlin.test.Test
import kotlin.test.assertEquals

class StatsMenuSearchTest {
    @Test
    fun searchingOptionsIgnoresCaseSpacesAndPunctuationAndPutsWordStartsFirst() {
        val options = listOf("All heroes", "Seven", "Ivy", "Vindicta", "Mo & Krill", "The Doorman", "Lady Geist").map { name ->
            MenuOption(name, selected = false, searchable = name != "All heroes") {}
        }
        fun search(query: String) = searchOptions(options, query).map { it.label }
        assertEquals(options.map { it.label }, search(""), "with nothing typed every option shows, All heroes first")
        assertEquals(listOf("Vindicta", "Seven", "Ivy"), search("v"), "names starting with it before names containing it")
        assertEquals(listOf("Mo & Krill"), search("mo krill"))
        assertEquals(listOf("Mo & Krill"), search("KRILL"))
        assertEquals(listOf("The Doorman"), search("he"), "All heroes is not a hero to find")
        assertEquals(listOf("Lady Geist"), search("geist"))
        assertEquals(emptyList(), search("es"))
    }
}
