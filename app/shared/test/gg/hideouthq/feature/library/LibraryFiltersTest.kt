package gg.hideouthq.feature.library

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileSeed
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFiltersTest {
    private fun mod(id: String, category: ModCategory, author: String, heroId: String? = null) = Mod(
        id = id, title = id, category = category, heroId = heroId, author = author, downloads = 0, likes = 0, sizeBytes = null,
        publishedAt = CalendarDate(2026, 1, 1).epochSeconds, updated = CalendarDate(2026, 1, 1), trendingRank = 1,
    )

    private val catalogued = listOf(mod("mod-551201", ModCategory.Skins, "Cobizal", heroId = "silver"), mod("mod-551202", ModCategory.Hud, "UserUnknownVR"))

    private fun library(
        catalogued: List<Mod> = this.catalogued,
        preferences: Preferences? = null,
        test: suspend TestScope.(LibraryViewModel, () -> LibraryViewModel) -> Unit,
    ) = withViewModels { viewModels ->
        val base = AppContainer.fake()
        val catalog = object : CatalogRepository by base.catalog {
            override fun cachedMod(modId: String): Mod? = catalogued.firstOrNull { it.id == modId }
        }
        val labels = mapOf(ModCategory.Skins to "Skin", ModCategory.Hud to "HUD")
        val open = {
            LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, catalog, preferences, categories = labels).also { vm ->
                viewModels.keep(vm)
                backgroundScope.launch { vm.uiState.collect {} }
            }
        }
        val vm = open()
        runCurrent()
        test(vm, open)
    }

    private fun library(catalogued: List<Mod> = this.catalogued, test: suspend TestScope.(LibraryViewModel) -> Unit) = library(catalogued) { vm, _ -> test(vm) }

    private fun LibraryViewModel.visible() = uiState.value.rows.filter { it.visible }.map { it.installId }

    private fun LibraryViewModel.pills() = uiState.value.filters.map { it.filter to it.count }

    @Test
    fun everyModHasACategoryPillAndAllCountsTheModsOnAndOff() = library { vm ->
        assertEquals(
            listOf(
                LibraryFilter.All to 4,
                LibraryFilter.Category(ModCategory.Skins) to 1,
                LibraryFilter.Category(ModCategory.Hud) to 1,
                LibraryFilter.Category(ModCategory.Other) to 2,
            ),
            vm.pills(),
        )

        vm.setFilter(LibraryFilter.Category(ModCategory.Other))
        runCurrent()
        assertEquals(listOf("faun-celeste", "hudlock"), vm.visible(), "Other holds the mods the catalogue gives no category")
    }

    @Test
    fun aPillThatWouldListJustWhatAllListsIsLeftOutUnlessItIsTheOneChosen() = library(catalogued = emptyList()) { vm ->
        assertEquals(listOf(LibraryFilter.All to 4), vm.pills(), "every mod is Other")
        vm.setFilter(LibraryFilter.Category(ModCategory.Other))
        runCurrent()
        assertEquals(listOf(LibraryFilter.All to 4, LibraryFilter.Category(ModCategory.Other) to 4), vm.pills(), "the chosen pill keeps its place")
    }

    @Test
    fun theProfilesOwnViewListsTheModsItTurnsOnAndACategoryNarrowsItFurther() = library { vm ->
        assertEquals(LibraryScope.All, vm.uiState.value.scope, "every mod is listed until the user chooses otherwise")
        vm.setScope(LibraryScope.Profile)
        runCurrent()
        assertEquals(listOf("lilith", "werecat", "hudlock"), vm.visible(), "Faun Celeste is off in Main")
        assertEquals(3, vm.uiState.value.scopedCount)
        assertEquals(
            listOf(
                LibraryFilter.All to 3,
                LibraryFilter.Category(ModCategory.Skins) to 1,
                LibraryFilter.Category(ModCategory.Hud) to 1,
                LibraryFilter.Category(ModCategory.Other) to 1,
            ),
            vm.pills(),
            "the pills count what the profile turns on",
        )
        vm.setFilter(LibraryFilter.Category(ModCategory.Other))
        runCurrent()
        assertEquals(listOf("hudlock"), vm.visible(), "Other in Main leaves out Faun Celeste, which is Other but off")
        vm.setScope(LibraryScope.All)
        runCurrent()
        assertEquals(listOf("faun-celeste", "hudlock"), vm.visible(), "the category stays chosen across the switch")
    }

    @Test
    fun aModTurnedOffInTheProfilesOwnViewStaysListedUntilTheScopeChangesOrLibraryLeaves() = library { vm ->
        vm.setScope(LibraryScope.Profile)
        vm.setEnabled("lilith", false)
        runCurrent()
        assertEquals(listOf("lilith", "werecat", "hudlock"), vm.visible(), "the row stays under the pointer")
        assertFalse(vm.uiState.value.rows.single { it.installId == "lilith" }.enabled, "and shows off")
        vm.forgetTurnedOff()
        runCurrent()
        assertEquals(listOf("werecat", "hudlock"), vm.visible(), "coming back to Library lists what is on")

        vm.setSelectedEnabled(false)
        runCurrent()
        assertEquals(listOf("werecat", "hudlock"), vm.visible(), "turning the picked row off keeps it too")
        vm.setScope(LibraryScope.All)
        vm.setScope(LibraryScope.Profile)
        runCurrent()
        assertEquals(listOf("hudlock"), vm.visible(), "switching back lists what is on now")
    }

    @Test
    fun switchingProfileInTheProfilesOwnViewListsWhatTheNewProfileTurnsOn() = withViewModels { viewModels ->
        val base = AppContainer.fake()
        val vm = LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, base.catalog, null).also { viewModels.keep(it) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.setScope(LibraryScope.Profile)
        vm.setEnabled("lilith", false)
        runCurrent()
        assertTrue("lilith" in vm.visible(), "turned off in Main, it stays listed")

        val empty = assertIs<MutationResult.Done<ProfileId>>(
            base.modLibrary.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Empty, activate = true)),
        )
        runCurrent()
        assertEquals(empty.value, base.modLibrary.state.value.shownProfiles?.active)
        assertEquals(emptyList(), vm.visible(), "Main's turned-off row is not Ranked's to list")
    }

    @Test
    fun showingAModTheProfileLeavesOffListsItInTheProfilesOwnView() = library { vm ->
        vm.setScope(LibraryScope.Profile)
        runCurrent()
        vm.show("faun-celeste")
        runCurrent()
        assertEquals(listOf("lilith", "werecat", "faun-celeste", "hudlock"), vm.visible())
        assertEquals("faun-celeste", vm.uiState.value.selected?.row?.installId)
        assertEquals(LibraryScope.Profile, vm.uiState.value.scope, "the user's choice of view stands")
    }

    @Test
    fun theScopeSwitchIsOfferedWhileTheProfileLeavesAModOffOrItsOwnViewIsChosen() = library { vm ->
        assertTrue(vm.uiState.value.showScope, "Faun Celeste is off in Main")
        vm.setEnabled("faun-celeste", true)
        runCurrent()
        assertFalse(vm.uiState.value.showScope, "both views would list the same mods")
        vm.setScope(LibraryScope.Profile)
        runCurrent()
        assertTrue(vm.uiState.value.showScope, "the way back stays")
    }

    @Test
    fun theChosenScopeIsKeptForTheNextVisit() {
        val preferences = Preferences(MemoryStorage())
        library(preferences = preferences) { vm, open ->
            vm.setScope(LibraryScope.Profile)
            runCurrent()
            val next = open()
            runCurrent()
            assertEquals(LibraryScope.Profile, next.uiState.value.scope)
            assertEquals(listOf("lilith", "werecat", "hudlock"), next.visible())
            next.setScope(LibraryScope.All)
            runCurrent()
            assertEquals(LibraryScope.All, open().also { runCurrent() }.uiState.value.scope)
        }
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
