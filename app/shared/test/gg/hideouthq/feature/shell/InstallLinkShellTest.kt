package gg.hideouthq.feature.shell

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.installStatus
import gg.hideouthq.core.sharing.InstallLink
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InstallLinkShellTest {
    private val shop = SubmissionRef(SubmissionKind.Mod, 551_230)
    private val files = listOf(301L, 302L).map { RemoteFile(it, "shop_$it.zip", 1_000, null, "Variant $it", 5, 1, "https://gamebanana.com/dl/$it") }

    @Test
    fun aLinkInstallsExactlyItsFileIntoTheActiveProfileAnInstalledModIsOnlyShownAndOneWithNothingToInstallSaysSo() = withViewModels { viewModels ->
        val container = AppContainer.fake(
            gameBanana = FakeGameBanana(mapOf(shop to FileListing.Available("Compact item shop", files))),
            downloader = { gameFiles -> FakeDownloader(gameFiles).apply { files.forEach { serve(it.id, listOf("shop.vpk" to FakeVpk.directory(seed = it.id.toInt()))) } } },
        )
        val library = container.modLibrary
        val viewModel = viewModels.keep(ShellViewModel(library, FakeGameLauncher()))
        backgroundScope.launch { viewModel.uiState.collect {} }

        assertEquals(shop.modId, viewModel.openInstallLink(InstallLink.File(shop, 302)), "the link opens the mod's page")
        runCurrent()
        val record = (library.state.value.library as LibraryStatus.Ready).records.single { it.modId == shop.modId }
        assertEquals(listOf(302L), record.sets.map { it.fileId }, "the file the link names, with no question")
        assertEquals(InstallStatus.Installed(enabled = true), library.state.value.installStatus(shop.modId), "on in the active profile")

        val installed = SubmissionRef(SubmissionKind.Mod, 551_201)
        assertEquals(installed.modId, viewModel.openInstallLink(InstallLink.File(installed, 999)))
        runCurrent()
        assertNull(library.state.value.operations[installed.modId], "an installed mod's page opens and nothing starts")

        InstallLink.Refused.entries.forEach { refused ->
            assertNull(viewModel.openInstallLink(refused), "$refused opens no page")
            runCurrent()
            assertEquals(SnackbarMessage.LinkRefused(refused), viewModel.uiState.value.snackbar?.message)
        }
    }
}
