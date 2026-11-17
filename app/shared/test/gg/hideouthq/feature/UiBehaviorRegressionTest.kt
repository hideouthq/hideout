package gg.hideouthq.feature

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeCatalogRepository
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeSoundSamplePlayer
import gg.hideouthq.core.data.fake.FakeStore
import gg.hideouthq.core.data.fake.FixedInstallations
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.ports
import gg.hideouthq.core.data.preview.noPreviews
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.archive
import gg.hideouthq.core.library.number
import gg.hideouthq.core.library.oneSetRecord
import gg.hideouthq.core.library.source
import gg.hideouthq.core.library.variant
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.feature.discover.DiscoverViewModel
import gg.hideouthq.feature.library.LibraryViewModel
import gg.hideouthq.feature.moddetail.ModDetailViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UiBehaviorRegressionTest {
    @Test
    fun installedRowsShowIndependentlyOfWhetherTheCatalogueHasTheMod() = withViewModels { viewModels ->
        val record = oneSetRecord(
            installId = "install-1",
            gameRoot = GameRoot("/games/Deadlock"),
            source = gg.hideouthq.core.library.InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 999_001, 1, "0".repeat(32))),
            title = "A mod the catalogue no longer lists",
            variant = "Default",
            archive = ArchiveIdentity("/downloads/a.zip", 1_000L, "a".repeat(64)),
            number = 7,
            files = listOf(SetFile(VpkPart.Directory, FileImage(1_000L, "a".repeat(64)))),
        )
        val library = testModLibrary(LibraryManifest(records = listOf(record)))
        library.start()
        val vm = viewModels.keep(LibraryViewModel(library, FolderOpener { _, _ -> true }))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        assertFalse(vm.uiState.value.loading)
        assertEquals("A mod the catalogue no longer lists", vm.uiState.value.rows.single().title)
    }

    @Test
    fun anInstalledRowOpensAPageWhoseInstallStateComesFromTheManifest() = withViewModels { viewModels ->
        val container = AppContainer.fake()
        val catalog = object : CatalogRepository by container.catalog {
            override val mods = container.catalog.mods.map { mods -> mods.map { if (it.id in Listed) it.copy(id = Listed.getValue(it.id)) else it } }
        }
        val installed = viewModels.keep(LibraryViewModel(container.modLibrary, container.folderOpener))
        backgroundScope.launch { installed.uiState.collect {} }
        runCurrent()
        val rows = installed.uiState.value.rows.associateBy { it.title }
        assertEquals(listOf("mod-551201", "mod-551202", "mod-551203", "mod-551204"), installed.uiState.value.rows.map { it.modId })

        fun page(modId: String) = viewModels.keep(
            ModDetailViewModel(modId, catalog, container.modLibrary, FakeSoundSamplePlayer(backgroundScope), noPreviews()),
        ).also { vm -> backgroundScope.launch { vm.uiState.collect {} } }

        val lilith = page(rows.getValue("Lilith, the Hell Warden").modId)
        val faun = page(rows.getValue("Faun Celeste").modId)
        val notInManifest = page("werecat")
        runCurrent()
        assertEquals("Lilith, the Hell Warden", lilith.uiState.value.header?.title)
        assertEquals(InstallStatus.Installed(enabled = true), lilith.uiState.value.installation.status)
        assertEquals("Faun Celeste", faun.uiState.value.header?.title)
        assertEquals(InstallStatus.Installed(enabled = false), faun.uiState.value.installation.status)
        assertEquals("Night of the Werecat", notInManifest.uiState.value.header?.title)
        assertEquals(InstallStatus.NotInstalled, notInManifest.uiState.value.installation.status, "the old simulated library had it installed")
    }

    private val Listed = mapOf("lilith" to "mod-551201", "faun-celeste" to "mod-551203")

    private fun TestScope.testModLibrary(manifest: LibraryManifest): ModLibrary = ModLibrary(
        FakeGameLocator(emptyList()),
        Preferences(MemoryStorage()),
        InMemoryLibraryStore(manifest),
        InMemoryGameFiles(),
        InMemoryGameFiles().ports(),
        backgroundScope,
    )

    @Test
    fun discoverShowsCardsWithoutFetchingModPages() = withViewModels { viewModels ->
        val fake = FakeCatalogRepository(FakeStore(FakeConfig()))
        var detailReads = 0
        val catalog = object : CatalogRepository by fake {
            override fun detail(modId: String): Flow<Loadable<ModDetail>> {
                detailReads++
                return fake.detail(modId)
            }
        }
        val installations = FixedInstallations(flowOf(InstallStatus.NotInstalled))
        val vm = viewModels.keep(DiscoverViewModel(catalog, installations, FakeSoundSamplePlayer(backgroundScope)))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        assertEquals(listOf("lilith", "garden-goddess", "werecat", "grey-talon-upgrade"), vm.uiState.value.featured.map { it.item.id })
        assertEquals(0, detailReads)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun discoverLeadsWithThePreferredHeroesOnceTheyArePicked() = withViewModels { viewModels ->
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig()))
        val preferred = MutableStateFlow(emptyList<String>())
        val vm = viewModels.keep(
            DiscoverViewModel(catalog, FixedInstallations(flowOf(InstallStatus.NotInstalled)), FakeSoundSamplePlayer(backgroundScope), preferredHeroes = preferred),
        )
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals(listOf("abrams", "bebop", "celeste"), vm.uiState.value.heroes.take(3).map { it.id })

        preferred.value = listOf("vindicta", "pocket")
        runCurrent()
        assertEquals(listOf("vindicta", "pocket", "abrams"), vm.uiState.value.heroes.take(3).map { it.id })
    }

    @Test
    fun discoverInstalledActionOpensThePageInsteadOfInstallingAgain() = withViewModels { viewModels ->
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig()))
        val status = MutableStateFlow<InstallStatus>(InstallStatus.Installed(enabled = false))
        val installations = FixedInstallations(status)
        val vm = viewModels.keep(DiscoverViewModel(catalog, installations, FakeSoundSamplePlayer(backgroundScope)))
        val opened = mutableListOf<String>()

        vm.install("lilith") { opened += it }
        runCurrent()
        assertEquals(listOf("lilith"), opened)
        assertEquals(emptyList(), installations.installs)

        status.value = InstallStatus.NotInstalled
        vm.install("werecat") { opened += it }
        runCurrent()
        assertEquals(listOf("lilith"), opened)
        assertEquals(listOf("werecat"), installations.installs)
    }

    @Test
    fun discoverPreviewDoesNotInheritTheDetailPagesLoopSetting() = withViewModels { viewModels ->
        val store = FakeStore(FakeConfig())
        val catalog = FakeCatalogRepository(store)
        val installations = FixedInstallations(flowOf(InstallStatus.NotInstalled))
        val player = FakeSoundSamplePlayer(backgroundScope)
        val detail = viewModels.keep(ModDetailViewModel("announcer-pack", catalog, gg.hideouthq.AppContainer.fake().modLibrary, player, noPreviews()))
        val discover = viewModels.keep(DiscoverViewModel(catalog, installations, player))
        backgroundScope.launch { detail.uiState.collect {} }
        backgroundScope.launch { discover.uiState.collect {} }
        runCurrent()
        detail.setSampleLooping(true)
        detail.toggleSample()
        assertTrue(player.nowPlaying.value!!.looping)
        detail.stopSample()
        runCurrent()

        discover.toggleSample("announcer-pack")
        assertEquals("announcer-pack", player.nowPlaying.value?.modId)
        assertFalse(player.nowPlaying.value!!.looping)
        advanceTimeBy(player.nowPlaying.value!!.lengthMillis!! + 100L)
        runCurrent()
        assertNull(player.nowPlaying.value)

        detail.toggleSample()
        assertTrue(player.nowPlaying.value!!.looping)
    }

    @Test
    fun discoverDoesNotAnimateAudioBeforeItsDurationIsKnown() = withViewModels { viewModels ->
        val catalog = FakeCatalogRepository(FakeStore(FakeConfig()))
        val player = object : gg.hideouthq.core.data.SoundSamplePlayer by FakeSoundSamplePlayer(backgroundScope) {
            override val nowPlaying = MutableStateFlow<gg.hideouthq.core.data.PlayingSample?>(null)
        }
        val vm = viewModels.keep(DiscoverViewModel(catalog, FixedInstallations(flowOf(InstallStatus.NotInstalled)), player))
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        player.nowPlaying.value = gg.hideouthq.core.data.PlayingSample("announcer-pack", 0, null)
        runCurrent()
        assertNull(vm.uiState.value.sounds.first { it.item.id == "announcer-pack" }.playingSeconds)
        player.nowPlaying.value = gg.hideouthq.core.data.PlayingSample("announcer-pack", 5_000, 20_000)
        runCurrent()
        val quarter = vm.uiState.value.sounds.first { it.item.id == "announcer-pack" }
        assertEquals(5, quarter.playingSeconds)
        assertEquals(0.25f, quarter.progress)
        assertTrue(quarter.playing)
        player.nowPlaying.value = gg.hideouthq.core.data.PlayingSample("announcer-pack", 10_000, 20_000, paused = true)
        runCurrent()
        val paused = vm.uiState.value.sounds.first { it.item.id == "announcer-pack" }
        assertEquals(0.5f, paused.progress)
        assertFalse(paused.playing)
        player.nowPlaying.value = null
        runCurrent()
        assertEquals(0f, vm.uiState.value.sounds.first { it.item.id == "announcer-pack" }.progress)
    }
}
