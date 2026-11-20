package gg.hideouthq.feature.moddetail

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeSoundSamplePlayer
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.preview.noPreviews
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.model.Requirement
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NeedsTest {
    private val shop = SubmissionRef(SubmissionKind.Mod, 551_230)
    private val shopFile = RemoteFile(301, "compact_shop.zip", 1_000, null, null, 5, 1, "https://gamebanana.com/dl/301")

    /** The sample catalogue's mods under the GameBanana ids the sample library installed them from. */
    private val installedAs = mapOf("werecat" to "mod-551202", "faun-celeste" to "mod-551203", "compact-shop" to shop.modId)

    @Test
    fun eachNeedTheCatalogueListsSaysWhetherItIsInstalledAndOnAndInstallsIntoTheActiveProfile() = withViewModels { viewModels ->
        val app = AppContainer.fake(
            gameBanana = FakeGameBanana(mapOf(shop to FileListing.Available("Compact item shop", listOf(shopFile)))),
            downloader = { files -> FakeDownloader(files).apply { serve(shopFile.id, listOf("shop.vpk" to FakeVpk.directory(seed = 5))) } },
        )
        val requirements = listOf(
            Requirement("Werecat", "https://gamebanana.com/mods/551202", "mod-551202"),
            Requirement("Faun", "https://gamebanana.com/mods/551203", "mod-551203"),
            Requirement("Shop", "https://gamebanana.com/mods/551230", shop.modId),
            Requirement("Not in the catalogue", "https://gamebanana.com/mods/999", "mod-999"),
            Requirement("Voice lines", "https://modding.example/voice"),
            Requirement("Itself", "https://gamebanana.com/mods/1", "lilith"),
            Requirement("Patience"),
        )
        val catalog = object : CatalogRepository by app.catalog {
            override val mods = app.catalog.mods.map { mods -> mods.map { it.copy(id = installedAs[it.id] ?: it.id) } }

            override fun detail(modId: String): Flow<Loadable<ModDetail>> =
                app.catalog.detail(modId).map { loaded -> if (loaded is Loadable.Ready) Loadable.Ready(loaded.value.copy(requirements = requirements)) else loaded }
        }
        val vm = viewModels.keep(ModDetailViewModel("lilith", catalog, app.modLibrary, FakeSoundSamplePlayer(backgroundScope), noPreviews()))
        backgroundScope.launch { vm.uiState.collect() }
        runCurrent()

        assertEquals(
            listOf(
                Need.Listed("Night of the Werecat", "mod-551202", InstallStatus.Installed(enabled = true)),
                Need.Listed("Faun Celeste", "mod-551203", InstallStatus.Installed(enabled = false)),
                Need.Listed("Compact item shop", shop.modId, InstallStatus.NotInstalled),
                Need.Elsewhere("Not in the catalogue", "https://gamebanana.com/mods/999"),
                Need.Elsewhere("Voice lines", "https://modding.example/voice"),
                Need.Named("Patience"),
            ),
            vm.uiState.value.needs,
        )

        vm.installNeed(shop.modId)
        runCurrent()
        assertEquals(Need.Listed("Compact item shop", shop.modId, InstallStatus.Installed(enabled = true)), vm.uiState.value.needs[2])
    }
}
