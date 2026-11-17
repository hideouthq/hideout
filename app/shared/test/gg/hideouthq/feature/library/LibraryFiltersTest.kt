package gg.hideouthq.feature.library

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFiltersTest {
    private fun mod(id: String, category: ModCategory, author: String, heroId: String? = null) = Mod(
        id = id, title = id, category = category, heroId = heroId, author = author, downloads = 0, likes = 0, sizeBytes = null,
        publishedAt = CalendarDate(2026, 1, 1).epochSeconds, updated = CalendarDate(2026, 1, 1), trendingRank = 1,
    )

    private val catalogued = listOf(mod("mod-551201", ModCategory.Skins, "Cobizal", heroId = "silver"), mod("mod-551202", ModCategory.Hud, "UserUnknownVR"))

    private fun library(catalogued: List<Mod> = this.catalogued, test: suspend TestScope.(LibraryViewModel) -> Unit) = withViewModels { viewModels ->
        val base = AppContainer.fake()
        val catalog = object : CatalogRepository by base.catalog {
            override fun cachedMod(modId: String): Mod? = catalogued.firstOrNull { it.id == modId }
        }
        val labels = mapOf(ModCategory.Skins to "Skin", ModCategory.Hud to "HUD")
        val vm = LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, catalog, categories = labels).also { viewModels.keep(it) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        test(vm)
    }

    private fun LibraryViewModel.visible() = uiState.value.rows.filter { it.visible }.map { it.installId }

    @Test
    fun everyModHasACategoryPillAndThePillsCountTheModsOnAndOff() = library { vm ->
        assertEquals(
            listOf(
                LibraryFilter.All to 4,
                LibraryFilter.Category(ModCategory.Skins) to 1,
                LibraryFilter.Category(ModCategory.Hud) to 1,
                LibraryFilter.Category(ModCategory.Other) to 2,
                LibraryFilter.On to 3,
                LibraryFilter.Off to 1,
            ),
            vm.uiState.value.filters.map { it.filter to it.count },
        )

        vm.setFilter(LibraryFilter.Category(ModCategory.Other))
        runCurrent()
        assertEquals(listOf("faun-celeste", "hudlock"), vm.visible(), "Other holds the mods the catalogue gives no category")
        vm.setFilter(LibraryFilter.On)
        runCurrent()
        assertEquals(listOf("lilith", "werecat", "hudlock"), vm.visible(), "On lists the mods the active profile loads")
    }

    @Test
    fun aPillThatWouldListJustWhatAllListsIsLeftOutUnlessItIsTheOneChosen() = library(catalogued = emptyList()) { vm ->
        assertEquals(listOf(LibraryFilter.All to 4, LibraryFilter.On to 3, LibraryFilter.Off to 1), vm.uiState.value.filters.map { it.filter to it.count }, "every mod is Other")
        vm.setFilter(LibraryFilter.Category(ModCategory.Other))
        runCurrent()
        assertEquals(
            listOf(LibraryFilter.All to 4, LibraryFilter.Category(ModCategory.Other) to 4, LibraryFilter.On to 3, LibraryFilter.Off to 1),
            vm.uiState.value.filters.map { it.filter to it.count },
            "the chosen pill keeps its place",
        )
    }

    @Test
    fun thePillsCountWhatTheSearchFinds() = library { vm ->
        vm.setQuery("hud")
        runCurrent()
        val pills = vm.uiState.value.filters.associate { it.filter to it.count }
        assertEquals(listOf("werecat", "hudlock"), vm.visible())
        assertEquals(2, pills[LibraryFilter.All])
        assertTrue(pills.values.all { it <= 2 }, "no pill counts a mod the search hides: $pills")
    }

    @Test
    fun searchMatchesTheAuthorHeroAndCategoryTheRowShows() = library { vm ->
        listOf("cobizal" to listOf("lilith"), "silver" to listOf("lilith"), "hud" to listOf("werecat", "hudlock"), "werecat" to listOf("werecat")).forEach { (query, shown) ->
            vm.setQuery(query)
            runCurrent()
            assertEquals(shown, vm.visible(), "searching '$query'")
        }
    }
}
