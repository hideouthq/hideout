package gg.hideouthq.feature.library

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryArtworkTest {
    private val lilithModId = "mod-551201"
    private val thumbnail = "https://fixture.invalid/library_thumb.jpg"
    private val full = "https://fixture.invalid/library_full.jpg"

    private val lilithMod = Mod(
        id = lilithModId,
        title = "Lilith, the Hell Warden",
        category = ModCategory.Skins,
        downloads = 0,
        likes = 0,
        sizeBytes = null,
        publishedAt = CalendarDate(2026, 1, 1).epochSeconds,
        updated = CalendarDate(2026, 1, 1),
        trendingRank = 1,
        cover = CoverImage(url = thumbnail, fullUrl = full),
    )

    @Test
    fun artworkArrivingAfterTheLibraryOpensUpdatesTheRows() = withViewModels { viewModels ->
        val base = AppContainer.fake()
        val available = kotlinx.coroutines.flow.MutableStateFlow<List<Mod>>(emptyList())
        val catalog = object : CatalogRepository by base.catalog {
            override val mods = available
            override fun cachedMod(modId: String): Mod? = available.value.firstOrNull { it.id == modId }
        }
        val installed = LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, catalog).also { viewModels.keep(it) }
        backgroundScope.launch { installed.uiState.collect {} }
        runCurrent()
        assertNull(installed.uiState.value.rows.single { it.installId == "lilith" }.coverFullUrl, "a mod the catalogue does not know has no artwork")
        available.value = listOf(lilithMod)
        runCurrent()
        val row = installed.uiState.value.rows.single { it.installId == "lilith" }
        assertEquals(thumbnail to full, row.coverUrl to row.coverFullUrl)
    }
}
