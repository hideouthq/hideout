package gg.hideouthq.feature.browse

import gg.hideouthq.AppContainer
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseSearchTest {
    @Test
    fun aSearchLooksThroughEveryCategoryAndClearingItReturnsToTheOneShownBefore() = withViewModels { viewModels ->
        val browse = viewModels.keep(BrowseViewModel(AppContainer.fake().catalog, BrowseStart(category = ModCategory.Skins), emptyMap()))
        backgroundScope.launch { browse.uiState.collect {} }
        runCurrent()
        fun shown() = browse.uiState.value.filters.let { it.category to it.query }

        browse.setQuery("m")
        browse.setQuery("map")
        runCurrent()
        assertEquals(null to "map", shown(), "typing looks through every category")
        browse.setQuery("")
        runCurrent()
        assertEquals(ModCategory.Skins to "", shown(), "clearing the search goes back to Skins")

        browse.setQuery("war")
        browse.setCategory(ModCategory.Hud)
        browse.setQuery("")
        runCurrent()
        assertEquals(ModCategory.Hud to "", shown(), "a category chosen while searching stays")
    }
}
