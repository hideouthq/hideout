package gg.hideouthq.feature.moddetail

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.ViewerFiles
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PictureRevealTest {
    private val saved = PictureNotice.Saved("/Pictures/日本語 image.png")

    @Test fun aFailedRevealKeepsTheSavedPathAndRetryOpensThatPicture() {
        var fail = true
        val opened = mutableListOf<String>()
        val files = object : ViewerFiles by ViewerFiles.Unavailable {
            override suspend fun reveal(path: String) {
                opened += path
                if (fail) throw IOException("No file manager")
            }
        }
        withViewer(files) { vm ->
            vm.showNotice(saved)
            vm.revealPicture()
            runCurrent()
            assertEquals(saved.copy(reveal = PictureReveal.Failed), vm.uiState.value.picture)
            fail = false
            vm.revealPicture()
            runCurrent()
            assertEquals(saved, vm.uiState.value.picture)
            assertEquals(listOf(saved.path, saved.path), opened)
        }
    }

    @Test fun aSlowRevealDoesNotExpireOrStartDuplicateHandoffs() {
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        val files = object : ViewerFiles by ViewerFiles.Unavailable {
            override suspend fun reveal(path: String) { attempts++; release.await() }
        }
        withViewer(files) { vm ->
            vm.showNotice(saved)
            vm.revealPicture()
            vm.revealPicture()
            runCurrent()
            advanceTimeBy(6_001)
            runCurrent()
            assertEquals(1, attempts)
            assertEquals(saved.copy(reveal = PictureReveal.Opening), vm.uiState.value.picture)
            release.complete(Unit)
            runCurrent()
            assertEquals(saved, vm.uiState.value.picture)
            advanceTimeBy(6_001)
            runCurrent()
            assertNull(vm.uiState.value.picture)
        }
    }

    @Test fun aLateFailureDoesNotResurrectADismissedNoticeOrReplaceANewerPicture() {
        var release = CompletableDeferred<Unit>()
        val files = object : ViewerFiles by ViewerFiles.Unavailable {
            override suspend fun reveal(path: String) { release.await(); throw IOException("No file manager") }
        }
        withViewer(files) { vm ->
            vm.showNotice(saved)
            vm.revealPicture()
            runCurrent()
            vm.dismissPicture()
            release.complete(Unit)
            runCurrent()
            assertNull(vm.uiState.value.picture)

            release = CompletableDeferred()
            vm.showNotice(saved)
            vm.revealPicture()
            runCurrent()
            val newer = PictureNotice.Saved("/Pictures/new.png")
            vm.showNotice(newer)
            release.complete(Unit)
            runCurrent()
            assertEquals(newer, vm.uiState.value.picture)
        }
    }

    private fun withViewer(files: ViewerFiles, test: suspend TestScope.(ModDetailViewModel) -> Unit) = withViewModels { viewModels ->
        val app = AppContainer.fake()
        val vm = viewModels.keep(ModDetailViewModel("lilith", app.catalog, app.modLibrary, app.soundPlayer, app.previews, files = files))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        test(vm)
    }
}
