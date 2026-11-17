package gg.hideouthq.feature.browse

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseMatureTest {
    private val catalog = AppContainer.fake(FakeConfig(mature = setOf("lilith"))).catalog

    @Test
    fun aMatureModsCoverIsBlurredUntilTheVisitSwitchesItOffAndClearBlursItAgain() = withViewModels { viewModels ->
        val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(category = ModCategory.Skins), emptyMap(), preferences = Preferences(MemoryStorage())))
        backgroundScope.launch { browse.uiState.collect {} }
        runCurrent()
        fun blurred() = browse.uiState.value.results.filter { it.blurred }.map { it.id }

        assertEquals(listOf("lilith"), blurred(), "only the mature mod is blurred, and it is listed")
        assertEquals(MatureMods.Blurred, browse.uiState.value.mature)

        browse.setBlurMature(false)
        runCurrent()
        assertEquals(emptyList(), blurred())
        assertEquals(true, browse.uiState.value.results.any { it.id == "lilith" }, "still listed, now as it is")

        browse.clearPanel()
        runCurrent()
        assertEquals(listOf("lilith"), blurred())
    }

    @Test
    fun hidingMatureModsInSettingsLeavesThemOutOfBrowse() = withViewModels { viewModels ->
        val preferences = Preferences(MemoryStorage()).apply { setHideMature(true) }
        val browse = viewModels.keep(BrowseViewModel(catalog, BrowseStart(query = "war"), emptyMap(), preferences = preferences))
        backgroundScope.launch { browse.uiState.collect {} }
        runCurrent()
        assertEquals(listOf("sadistic-warden"), browse.uiState.value.results.map { it.id }, "a search leaves Lilith, the Hell Warden out too")
        assertEquals(MatureMods.Hidden, browse.uiState.value.mature)

        preferences.setHideMature(false)
        runCurrent()
        assertEquals(listOf("lilith", "sadistic-warden"), browse.uiState.value.results.map { it.id })
    }
}
