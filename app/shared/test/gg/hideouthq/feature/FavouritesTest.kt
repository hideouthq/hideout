package gg.hideouthq.feature

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeCatalogRepository
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeSoundSamplePlayer
import gg.hideouthq.core.data.fake.FakeStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.preview.noPreviews
import gg.hideouthq.feature.browse.BrowseStart
import gg.hideouthq.feature.browse.BrowseViewModel
import gg.hideouthq.feature.library.FilterPill
import gg.hideouthq.feature.library.LibraryFilter
import gg.hideouthq.feature.library.LibraryViewModel
import gg.hideouthq.feature.moddetail.ModDetailViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FavouritesTest {
    @Test
    fun favouritesAreKeptByModIdAcrossRestarts() = runTest {
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        preferences.setFavourite("mod-551201", true)
        preferences.setFavourite("mod-551202", true)
        preferences.setFavourite("mod-551201", false)
        assertEquals(setOf("mod-551202"), preferences.favourites.value)
        assertEquals(setOf("mod-551202"), Preferences(storage).apply { readSetup() }.favourites.value)
        assertEquals(emptySet(), Preferences(MemoryStorage(mapOf("preferences.json" to """{"libraryLayout":"cards"}"""))).apply { readSetup() }.favourites.value, "a file from before favourites has none")
    }

    @Test
    fun aStarOnAModPageShowsInLibraryWithItsOwnPillAndOutlivesTheUninstall() = withViewModels { viewModels ->
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        val container = AppContainer.fake(preferences = preferences)
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig()))
        val page = viewModels.keep(ModDetailViewModel("mod-551201", catalog, container.modLibrary, FakeSoundSamplePlayer(backgroundScope), noPreviews(), preferences = preferences))
        val library = viewModels.keep(LibraryViewModel(container.modLibrary, FolderOpener { _, _ -> true }, preferences = preferences))
        backgroundScope.launch { page.uiState.collect {} }
        backgroundScope.launch { library.uiState.collect {} }
        runCurrent()
        assertFalse(page.uiState.value.favourite)
        assertFalse(library.uiState.value.showFilters, "four mods and no favourite need no pills")

        page.setFavourite(true)
        runCurrent()
        assertTrue(page.uiState.value.favourite)
        assertEquals(listOf("lilith"), library.uiState.value.rows.filter { it.favourite }.map { it.installId })
        assertTrue(FilterPill(LibraryFilter.Favourites, 1) in library.uiState.value.filters)
        assertTrue(library.uiState.value.showFilters, "the pill is there to pick")
        library.setFilter(LibraryFilter.Favourites)
        runCurrent()
        assertEquals(listOf("lilith"), library.uiState.value.rows.filter { it.visible }.map { it.installId })

        library.uninstall("lilith")
        runCurrent()
        assertTrue(library.uiState.value.rows.none { it.installId == "lilith" })
        assertTrue(page.uiState.value.favourite, "the page keeps its star")
        assertEquals(setOf("mod-551201"), Preferences(storage).apply { readSetup() }.favourites.value, "kept with the user's choices, apart from the install list")
    }

    @Test
    fun theRowMenuStarsAModByItsPageId() = withViewModels { viewModels ->
        val preferences = Preferences(MemoryStorage())
        val library = viewModels.keep(LibraryViewModel(AppContainer.fake().modLibrary, FolderOpener { _, _ -> true }, preferences = preferences))
        backgroundScope.launch { library.uiState.collect {} }
        runCurrent()
        library.setFavourite("werecat", true)
        runCurrent()
        assertEquals(setOf("mod-551202"), preferences.favourites.value)
        assertTrue(library.uiState.value.rows.single { it.installId == "werecat" }.favourite)
        library.setFavourite("werecat", false)
        runCurrent()
        assertEquals(emptySet(), preferences.favourites.value)
    }

    @Test
    fun browseCanListOnlyTheStarredModsAndItsCardsKnowTheirStar() = withViewModels { viewModels ->
        val preferences = Preferences(MemoryStorage())
        val browse = viewModels.keep(BrowseViewModel(AppContainer.fake().catalog, BrowseStart(), emptyMap(), preferences = preferences))
        backgroundScope.launch { browse.uiState.collect {} }
        runCurrent()
        assertFalse(browse.uiState.value.hasFavourites, "the panel has no favourites to offer yet")

        browse.setFavourite("lilith", true)
        runCurrent()
        assertTrue(browse.uiState.value.hasFavourites)
        assertEquals(listOf("lilith"), browse.uiState.value.results.filter { it.favourite }.map { it.id })
        val every = browse.uiState.value.results.size
        browse.setFavouritesOnly(true)
        runCurrent()
        assertEquals(listOf("lilith"), browse.uiState.value.results.map { it.id })
        browse.clearPanel()
        runCurrent()
        assertEquals(every, browse.uiState.value.results.size, "Clear lists every mod again")
    }
}
