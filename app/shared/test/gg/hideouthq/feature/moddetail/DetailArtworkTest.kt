package gg.hideouthq.feature.moddetail

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.fake.FakeCatalogRepository
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeSoundSamplePlayer
import gg.hideouthq.core.data.fake.FakeStore
import gg.hideouthq.core.data.preview.noPreviews
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

class DetailArtworkTest {
    @Test
    fun detailPreservesArtworkPairsAndUsesGalleryWhenAvailable() = withViewModels { viewModels ->
        val base = FakeCatalogRepository(FakeStore(FakeConfig()))
        val cover = CoverImage(url = "thumbnail.jpg", fullUrl = "original.jpg")
        val detail = MutableStateFlow<Loadable<ModDetail>>(Loadable.Loading)
        val catalog = object : CatalogRepository by base {
            override val mods = base.mods.map { mods -> mods.map { it.copy(cover = cover) } }
            override fun cachedMod(modId: String) = base.cachedMod(modId)?.copy(cover = cover)
            override fun detail(modId: String) = detail
        }
        val vm = viewModels.keep(ModDetailViewModel("hudlock", catalog, AppContainer.fake().modLibrary, FakeSoundSamplePlayer(backgroundScope), noPreviews()))
        fun shown() = stageImages(vm.uiState.value, checkNotNull(vm.uiState.value.header))
        assertEquals(listOf(cover), shown(), "the cached mod's cover, before the page loads")
        backgroundScope.launch { vm.uiState.collect {} }
        val empty = ModDetail("hudlock", authors = emptyList(), about = "", version = null, images = emptyList())
        for (loaded in listOf(Loadable.Loading, Loadable.Failed(DataError.Unavailable), Loadable.Ready(empty))) {
            detail.value = loaded
            runCurrent()
            assertEquals(listOf(cover), shown(), "$loaded")
        }
        val gallery = listOf(CoverImage("first-thumb.jpg", fullUrl = "first-full.jpg"), CoverImage("second-thumb.jpg", fullUrl = "second-full.jpg"))
        detail.value = Loadable.Ready(empty.copy(images = gallery))
        runCurrent()
        assertEquals(gallery, shown())
    }
}
