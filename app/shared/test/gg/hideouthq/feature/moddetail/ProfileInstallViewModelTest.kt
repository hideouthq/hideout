package gg.hideouthq.feature.moddetail

import androidx.lifecycle.ViewModelStore
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.UncataloguedSampleManifest
import gg.hideouthq.core.library.*
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.*
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileInstallViewModelTest {
    private val source = SubmissionRef(SubmissionKind.Mod, 551210)
    private val files = listOf(
        RemoteFile(101, "default.zip", 1000, null, "Default", 20, 1, "https://gamebanana.com/dl/101"),
        RemoteFile(102, "werewolf.zip", 2000, null, "Werewolf", 10, 1, "https://gamebanana.com/dl/102"),
    )

    private inner class World(records: List<ManifestRecord>) {
        lateinit var download: FakeDownloader
        val busy = MutableStateFlow<Instant?>(null)

        var holding: CompletableDeferred<Unit>? = null
        private val banana = FakeGameBanana(mapOf(source to FileListing.Available("Lilith", files)))
        val config = FakeConfig(seed = LibrarySeed.Empty)
        val store = InMemoryLibraryStore(LibraryManifest(records = records))
        val app = AppContainer.fake(
            config,
            libraryStore = store,
            files = UncataloguedSampleManifest.files(config).apply { records.forEach { record -> record.files.forEach { putFile(record.gameRoot, it.relativePath(record), it.sizeBytes, it.sha256) } } },
            gameBanana = object : GameBananaClient by banana {
                override suspend fun files(submission: SubmissionRef): FileListing {
                    holding?.await()
                    return banana.files(submission)
                }
            },
            gameBananaBusy = busy,
            downloader = { gameFiles -> FakeDownloader(gameFiles).also { downloader ->
                download = downloader
                files.forEach { downloader.serve(it.id, listOf("skin.vpk" to FakeVpk.directory(seed = it.id.toInt()))) }
            } },
        )
        val library = app.modLibrary
        val catalog = object : CatalogRepository by app.catalog {
            override val mods = app.catalog.mods.map { mods -> mods.map { mod ->
                if (mod.id == "lilith") mod.copy(id = source.modId, previewVariants = files.reversed().map { PreviewVariant(it.id, null, it.fileName, it.description, sizeBytes = it.sizeBytes, downloads = it.downloads, addedAt = it.addedAt) }) else mod
            } }
        }
        val vm = ModDetailViewModel(source.modId, catalog, library, app.soundPlayer, app.previews)
        fun records() = (library.state.value.library as LibraryStatus.Ready).records
        fun chooseVersion(fileId: Long, profiles: Set<ProfileId>? = null) {
            val question = assertIs<OperationStage.ChoosingFile>(library.state.value.operations[source.modId]?.stage)
            assertTrue(library.answer(question.id, ChoiceAnswer.Files(setOf(fileId), profiles ?: question.profiles)))
        }
        suspend fun profile(name: String): ProfileId = assertIs<MutationResult.Done<ProfileId>>(
            library.createProfile(NewProfile(name, ProfileColour.Blue, ProfileSeed.Empty, activate = false)),
        ).value
    }

    private fun checkPage(records: List<ManifestRecord> = emptyList(), block: suspend TestScope.(World) -> Unit) = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val world = World(records)
            store.put("page", world.vm)
            backgroundScope.launch { world.vm.uiState.collect() }
            runCurrent()
            assertTrue(world.library.state.value.canModify)
            block(world)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun selectedVersionShowsItsOwnSizeAndNeverBorrowsAnotherFilesSize() = checkPage { world ->
        world.vm.pickVariant(FileSelection(101))
        runCurrent()
        assertEquals(1000L, world.vm.uiState.value.selectedVersion?.sizeBytes)
        world.vm.pickVariant(FileSelection(102))
        runCurrent()
        val state = world.vm.uiState.value
        assertEquals(2000L, state.selectedVersion?.sizeBytes)
        val header = state.header!!
        val unknown = state.copy(header = header.copy(previewVariants = header.previewVariants.map {
            if (it.fileId == 102L) it.copy(sizeBytes = null) else it
        }))
        assertNull(unknown.selectedVersion?.sizeBytes)
        assertNull(state.copy(selectedFile = FileSelection(999)).selectedVersion)
        assertNull(state.copy(selectedFile = null).selectedVersion)
    }

    @Test
    fun theSizeFactIsTheFileInstallOffersFirstWhicheverFileTheViewerShows() = checkPage { world ->
        assertEquals(1000L, world.vm.uiState.value.installSizeBytes, "the most downloaded file, though GameBanana lists it second")
        world.vm.pickVariant(FileSelection(102))
        runCurrent()
        assertEquals(2000L, world.vm.uiState.value.selectedVersion?.sizeBytes)
        assertEquals(1000L, world.vm.uiState.value.installSizeBytes)
    }

    @Test
    fun anInstallGameBananaHoldsSaysUntilWhenOnThePageWhichCanCancelIt() = checkPage { world ->
        world.holding = CompletableDeferred()
        world.vm.install()
        runCurrent()
        assertEquals(InstallStatus.Preparing, world.vm.uiState.value.installation.status)
        val until = Instant.parse("2026-11-11T18:05:00Z")
        world.busy.value = until
        runCurrent()
        val page = world.vm.uiState.value.installation
        assertEquals(InstallStatus.Busy(until), page.status)
        assertTrue(page.canCancel, "Cancel sits beside the button while it waits")

        world.vm.cancelInstall()
        runCurrent()
        assertNull(world.library.state.value.operations[source.modId])
        assertEquals(InstallStatus.NotInstalled, world.vm.uiState.value.installation.status)
        assertTrue(world.download.requested.isEmpty())
    }

    @Test
    fun pageInstallOpensTheVersionDialogInsteadOfInstallingTheViewerSelection() = checkPage { world ->
        val main = world.library.state.value.profiles!!.active
        val ranked = world.profile("Ranked")
        world.vm.pickVariant(FileSelection(102))
        world.vm.setProfile(ranked, true)
        world.vm.install()
        runCurrent()

        val question = assertIs<OperationStage.ChoosingFile>(world.library.state.value.operations[source.modId]?.stage)
        assertEquals(listOf(101L, 102L), question.files.map { it.id })
        assertEquals(setOf(main), question.profiles, "a new install starts in the active profile; its dialog, not the page, changes that")
        assertTrue(world.download.requested.isEmpty())
        assertEquals(102L, world.vm.uiState.value.selectedFile?.fileId)
        world.vm.cancelInstall()
        runCurrent()
        assertNull(world.library.state.value.operations[source.modId])
        assertTrue(world.records().isEmpty())
    }

    @Test
    fun reopeningThePageDuringDownloadShowsTheAcceptedProfilesInsteadOfTheActiveProfile() = checkPage { world ->
        val main = world.library.state.value.profiles!!.active
        val ranked = world.profile("Ranked")
        world.vm.pickVariant(FileSelection(102))
        world.download.gate = CompletableDeferred()
        world.vm.install()
        runCurrent()
        world.chooseVersion(102, setOf(ranked))
        runCurrent()
        val reopened = ModDetailViewModel(source.modId, world.catalog, world.library, world.app.soundPlayer, world.app.previews)
        val store = ViewModelStore()
        store.put("reopened", reopened)
        val collection = backgroundScope.launch { reopened.uiState.collect() }
        try {
            runCurrent()
            val page = reopened.uiState.value.installation
            assertIs<OperationStage.Downloading>(page.operation?.stage)
            assertEquals(setOf(ranked), page.selectedProfiles)
            assertEquals(listOf("Ranked"), page.profiles.filter { it.id in page.selectedProfiles }.map { it.name })
            assertIs<MutationResult.Done<SwitchSummary>>(world.library.switchProfile(ranked))
            assertIs<MutationResult.Done<SwitchSummary>>(world.library.switchProfile(main))
            runCurrent()
            assertEquals(setOf(ranked), reopened.uiState.value.installation.selectedProfiles)
        } finally {
            reopened.cancelInstall()
            collection.cancel()
            store.clear()
        }
    }

    @Test
    fun aLocalModLinkedToThePageChangesThroughChangeVersionToTheFileChosenFromIt() = checkPage(records = listOf(
        ManifestRecord("mugetsu", UncataloguedSampleManifest.root, "Mugetsu", Placement.Off, listOf(
            InstalledSet(InstalledSource.Local("mugetsu", "pak09_dir.vpk", source), null, null, SetOrigin.Imported(FileImage(900, "9".repeat(64))), null, "pak09_dir.vpk", listOf(SetFile(VpkPart.Directory, FileImage(900, "9".repeat(64))))),
        )),
    )) { world ->
        val linked = world.records().single()
        assertEquals(linked to true, world.vm.uiState.value.installation.let { it.record to it.canInstall }, "the page shows the local mod linked to it as its install")
        world.vm.changeFiles()
        runCurrent()
        world.chooseVersion(102)
        runCurrent()
        assertEquals(listOf(102L) to linked.installId, world.records().single().let { it.sets.map { set -> set.fileId } to it.installId })
    }

    @Test
    fun cancellingFromThePageStopsTheTransferAndCommitsNothing() = checkPage { world ->
        world.download.gate = CompletableDeferred()
        world.vm.pickVariant(FileSelection(102))
        world.vm.install()
        runCurrent()
        world.chooseVersion(102)
        runCurrent()
        assertIs<OperationStage.Downloading>(world.vm.uiState.value.installation.operation?.stage)
        assertTrue(world.vm.uiState.value.installation.canCancel)
        world.vm.cancelInstall()
        runCurrent()
        assertNull(world.library.state.value.operations[source.modId])
        assertTrue(world.records().isEmpty())
        assertTrue(world.library.state.value.profiles!!.profiles.all { it.enabled.isEmpty() })
    }

    @Test
    fun anUncataloguedSlugReportsTheDomainRefusalInsteadOfIgnoringTheClick() = withViewModels { viewModels ->
        val app = AppContainer.fake()
        val vm = viewModels.keep(ModDetailViewModel("werecat", app.catalog, app.modLibrary, app.soundPlayer, app.previews))
        backgroundScope.launch { vm.uiState.collect() }
        runCurrent()
        vm.install()
        runCurrent()
        assertEquals(InstallStatus.Failed(LibraryProblem.NotAGameBananaMod), vm.uiState.value.installation.status)
    }

    @Test
    fun anUnreadableLibraryIsNeitherInstalledNorInstallableOnThePage() = withViewModels { viewModels ->
        val store = InMemoryLibraryStore(forceUnreadable = "a newer Hideout wrote it")
        val app = AppContainer.fake(FakeConfig(seed = LibrarySeed.Empty), libraryStore = store)
        val vm = viewModels.keep(ModDetailViewModel("lilith", app.catalog, app.modLibrary, app.soundPlayer, app.previews))
        backgroundScope.launch { vm.uiState.collect() }
        runCurrent()
        assertIs<LibraryStatus.Blocked>(app.modLibrary.state.value.library)
        val page = vm.uiState.value.installation
        assertEquals(InstallStatus.Unknown, page.status, "not \"not installed\"")
        assertFalse(page.canInstall)
        assertNull(page.record)
        vm.install()
        runCurrent()
        assertTrue(app.modLibrary.state.value.operations.isEmpty())
        assertTrue(store.saved.isEmpty(), "nothing is written over the unreadable manifest")
    }
}
