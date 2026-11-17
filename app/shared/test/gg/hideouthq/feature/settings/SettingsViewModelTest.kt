package gg.hideouthq.feature.settings

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.InstallDefaults
import gg.hideouthq.core.data.ReportExport
import gg.hideouthq.core.data.preview.PreviewFiles
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.StatsPreferences
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SampleFolders
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.UncataloguedSampleManifest
import gg.hideouthq.core.data.fake.ports
import gg.hideouthq.core.library.BackupStage
import gg.hideouthq.core.library.BackupWork
import gg.hideouthq.core.library.ClearStage
import gg.hideouthq.core.library.DownloadSpace
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.FolderProblem
import gg.hideouthq.core.library.FoundVia
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.withoutHideoutBlock
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.Maintenance
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.RestoreStage
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.SetDerivation
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.archive
import gg.hideouthq.core.model.AppAbout
import gg.hideouthq.feature.ViewModels
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val picks = ArrayDeque<String?>()
    private val starts = mutableListOf<String?>()
    private val picker = FolderPicker { _, start -> starts += start; picks.removeFirst() }
    private val sample = UncataloguedSampleManifest.manifest(FakeConfig())
    private val unused = sample.downloads.single { it.archive.sizeBytes == 212_000_000L }
    private val unwritable = Preferences(object : CacheStorage {
        override suspend fun read(name: String): String? = null
        override suspend fun write(name: String, text: String) = throw IllegalStateException("read-only disk")
    })

    private fun settings(game: SampleGame, test: suspend TestScope.(SettingsViewModel) -> Unit) = withViewModels { viewModels ->
        val container = AppContainer.fake(FakeConfig(game = game), folderPicker = picker)
        val viewModel = opened(viewModels, SettingsViewModel(container.modLibrary, container.preferences, picker, container.folderOpener, container.about))
        test(viewModel)
    }

    private fun withViewModel(
        library: (Preferences, CoroutineScope) -> ModLibrary = sampleLibrary(),
        preferences: Preferences = Preferences(MemoryStorage()),
        statsAvailable: Boolean = false,
        test: suspend TestScope.(SettingsViewModel, ModLibrary) -> Unit,
    ) = withViewModels { viewModels ->
        val owner = library(preferences, backgroundScope).apply { start() }
        val viewModel = opened(viewModels, SettingsViewModel(owner, preferences, picker, { _, _ -> true }, AppAbout("2.3.4"), statsAvailable))
        test(viewModel, owner)
    }

    private fun TestScope.opened(viewModels: ViewModels, viewModel: SettingsViewModel) = viewModels.keep(viewModel).also {
        backgroundScope.launch { it.uiState.collect {} }
        runCurrent()
    }

    private fun sampleLibrary(files: InMemoryGameFiles = UncataloguedSampleManifest.files(FakeConfig())): (Preferences, CoroutineScope) -> ModLibrary = { preferences, scope ->
        ModLibrary(FakeGameLocator(listOf(SampleFolders.Steam)), preferences, InMemoryLibraryStore(UncataloguedSampleManifest.manifest(FakeConfig())), files, files.ports(), scope)
    }

    private val SettingsViewModel.measured: DownloadSpace get() = assertIs<SpaceUsed.Measured>(uiState.value.space).space

    @Test
    fun aRememberedWrongFolderIsShownWithItsProblem() = settings(SampleGame.WrongFolder) { viewModel ->
        val state = viewModel.uiState.value
        assertIs<GameStatus.Missing>(state.game)
        assertEquals(FolderCheck.Invalid(SampleFolders.SteamLibrary, FolderProblem.NotDeadlock), state.rejected)
    }

    @Test
    fun choosingTheWrongFolderThenTheRightOne() = settings(SampleGame.NotFound) { viewModel ->
        picks += listOf(SampleFolders.SteamLibrary, SampleFolders.Citadel)

        viewModel.chooseFolder("Choose the Deadlock folder")
        runCurrent()
        assertEquals(FolderCheck.Invalid(SampleFolders.SteamLibrary, FolderProblem.NotDeadlock), viewModel.uiState.value.rejected)
        assertEquals(GameStatus.Missing(), viewModel.uiState.value.game)

        viewModel.chooseFolder("Choose the Deadlock folder")
        runCurrent()
        val state = viewModel.uiState.value
        assertEquals(GameStatus.Ready(GameRoot(SampleFolders.Elsewhere), FoundVia.Chosen), state.game)
        assertNull(state.rejected)
        assertEquals(listOf(null, SampleFolders.SteamLibrary), starts, "the second pick starts where the wrong one was")
    }

    @Test
    fun cancellingThePickerChangesNothing() = settings(SampleGame.Found) { viewModel ->
        val before = viewModel.uiState.value
        picks += null
        viewModel.chooseFolder("Choose the Deadlock folder")
        runCurrent()
        assertEquals(before, viewModel.uiState.value)
        assertEquals(listOf<String?>(SampleFolders.Steam), starts)
    }

    @Test
    fun aWrongPickWhileReadyKeepsTheGameAndDetectingAgainClearsTheMessage() = settings(SampleGame.Found) { viewModel ->
        picks += SampleFolders.SteamLibrary
        viewModel.chooseFolder("Choose the Deadlock folder")
        runCurrent()
        assertEquals(FolderCheck.Invalid(SampleFolders.SteamLibrary, FolderProblem.NotDeadlock), viewModel.uiState.value.rejected)
        assertEquals(GameStatus.Ready(GameRoot(SampleFolders.Steam), FoundVia.Steam), viewModel.uiState.value.game)

        viewModel.detectAgain()
        runCurrent()
        assertNull(viewModel.uiState.value.rejected)
        assertFalse(viewModel.uiState.value.busy)
    }

    @Test
    fun aToggleIsSavedThroughThePreferencesTheLibraryReadsAndShowsOnceSaved() {
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        withViewModel(preferences = preferences) { viewModel, _ ->
            assertEquals(InstallDefaults(), viewModel.uiState.value.defaults)
            viewModel.toggle(SettingId.KeepDownloads, false)
            viewModel.toggle(SettingId.AskWhichVersion, false)
            runCurrent()
            assertEquals(InstallDefaults(askWhichVersion = false, keepDownloads = false), viewModel.uiState.value.defaults)
            assertEquals(InstallDefaults(askWhichVersion = false, keepDownloads = false), preferences.installDefaults(), "the library reads the same choices")
            assertEquals(InstallDefaults(askWhichVersion = false, keepDownloads = false), Preferences(storage).installDefaults(), "and a restart does")
        }
    }

    @Test
    fun aSwitchIsOnUntilSwitchedOffAndARestartKeepsIt() {
        val switches = listOf<Triple<SettingId, (SettingsUiState) -> Boolean?, suspend (Preferences) -> Boolean?>>(
            Triple(SettingId.AutoRepair, { it.autoRepair }, { p -> p.readAutoRepair(); p.autoRepair.value }),
            Triple(SettingId.Load3dPreviews, { it.load3dPreviews }, { p -> p.readLoad3dPreviews(); p.load3dPreviews.value }),
            Triple(SettingId.DownloadAppUpdates, { it.downloadAppUpdates }, { p -> p.readDownloadAppUpdates(); p.downloadAppUpdates.value }),
        )
        for ((id, shown, afterRestart) in switches) {
            val storage = MemoryStorage()
            withViewModel(preferences = Preferences(storage)) { viewModel, _ ->
                assertEquals(true, shown(viewModel.uiState.value), "$id is on with nothing saved")
                viewModel.toggle(id, false)
                runCurrent()
                assertEquals(false, shown(viewModel.uiState.value), "$id")
                assertEquals(false, afterRestart(Preferences(storage)), "a restart keeps $id off")
            }
        }
    }

    @Test
    fun matureModsShowUntilHiddenAndARestartKeepsThemHidden() {
        val storage = MemoryStorage()
        withViewModel(preferences = Preferences(storage)) { viewModel, _ ->
            assertEquals(false, viewModel.uiState.value.hideMature)
            viewModel.toggle(SettingId.HideMature, true)
            runCurrent()
            assertEquals(true, viewModel.uiState.value.hideMature)
            assertEquals(true, Preferences(storage).run { readHideMature(); hideMature.value })
        }
    }

    @Test
    fun customGamesAreOnUntilSwitchedOffAndARestartKeepsIt() {
        val storage = MemoryStorage()
        withViewModel(preferences = Preferences(storage)) { viewModel, _ ->
            assertEquals(true, viewModel.uiState.value.customGames)
            viewModel.toggle(SettingId.CustomGames, false)
            runCurrent()
            assertEquals(false, viewModel.uiState.value.customGames)
            assertEquals(false, Preferences(storage).run { readCustomGames(); customGames.value })
        }
    }

    @Test
    fun aGameFolderTheDiskRefusesIsReportedAndNotUsed() = withViewModel(preferences = unwritable) { viewModel, _ ->
        picks += SampleFolders.Elsewhere
        viewModel.chooseFolder("Choose the Deadlock folder")
        runCurrent()
        assertTrue(viewModel.uiState.value.failed)
        assertFalse(viewModel.uiState.value.busy)
        assertEquals(GameStatus.Ready(GameRoot(SampleFolders.Steam), FoundVia.Steam), viewModel.uiState.value.game)
    }

    @Test
    fun aToggleTheDiskRefusesKeepsTheSavedValueAndSaysSo() = withViewModel(preferences = unwritable) { viewModel, _ ->
        viewModel.toggle(SettingId.KeepDownloads, false)
        runCurrent()
        assertEquals(InstallDefaults(), viewModel.uiState.value.defaults)
        assertEquals(SettingId.KeepDownloads, viewModel.uiState.value.unsaved)
    }

    @Test
    fun statsCanBeSwitchedOffAndItsAccountForgottenAndBothSurviveARestart() {
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        withViewModel(preferences = preferences, statsAvailable = true) { viewModel, _ ->
            runCurrent()
            assertEquals(StatsPreferences(), viewModel.uiState.value.stats, "on by default with no account chosen")
            assertTrue(SettingsRegistry.filter { it.visibility == Visibility.WithStats }.all { it.isShown(viewModel.uiState.value) })
            preferences.setStatsAccount(41_273_508)
            runCurrent()
            assertEquals(41_273_508L, viewModel.uiState.value.stats?.accountId)

            viewModel.changeStatsAccount()
            runCurrent()
            assertNull(viewModel.uiState.value.stats?.accountId, "changing the account asks again instead of keeping the old one")
            viewModel.toggle(SettingId.Stats, false)
            runCurrent()
            assertEquals(false, viewModel.uiState.value.stats?.enabled)
            assertEquals(StatsPreferences(enabled = false), Preferences(storage).statsPreferences(), "a restart keeps stats off")
        }
    }

    @Test
    fun sharingMatchIdsStartsOffAndItsSwitchTurnsItOnAndOff() {
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        withViewModel(preferences = preferences, statsAvailable = true) { viewModel, _ ->
            runCurrent()
            assertEquals(false, viewModel.uiState.value.stats?.shareMatchIds, "off until the player turns it on")

            viewModel.toggle(SettingId.ShareMatchIds, true)
            runCurrent()
            assertEquals(true, viewModel.uiState.value.stats?.shareMatchIds)
            assertTrue(Preferences(storage).statsPreferences().shareMatchIds, "a restart keeps it on")

            viewModel.toggle(SettingId.ShareMatchIds, false)
            runCurrent()
            assertEquals(false, viewModel.uiState.value.stats?.shareMatchIds)
            preferences.setShareMatchIds(true)
            runCurrent()
            assertEquals(true, viewModel.uiState.value.stats?.shareMatchIds, "setup's answer shows here too")
        }
    }

    @Test
    fun statsRowsAreHiddenWhenTheAppHasNoStats() = withViewModel { viewModel, _ ->
        runCurrent()
        assertFalse(viewModel.uiState.value.statsAvailable)
        assertTrue(SettingsRegistry.filter { it.visibility == Visibility.WithStats }.none { it.isShown(viewModel.uiState.value) })
    }

    @Test
    fun aChosenDownloadFolderIsCheckedSavedOrRefusedAndTheDefaultIsRememberedAsNone() {
        val preferences = Preferences(MemoryStorage())
        withViewModel(preferences = preferences) { viewModel, library ->
            assertEquals(library.defaultDownloadFolder.path, viewModel.uiState.value.downloadFolder, "Hideout's own folder while none is chosen")
            picks += """D:\Downloads\Mods"""
            viewModel.changeDownloadFolder("Choose a folder for new downloads")
            runCurrent()
            assertEquals(DownloadFolder("""D:\Downloads\Mods"""), preferences.installDefaults().downloadFolder)
            assertEquals("""D:\Downloads\Mods""", viewModel.uiState.value.downloadFolder)

            picks += SampleFolders.Steam + "\\game"
            viewModel.changeDownloadFolder("Choose a folder for new downloads")
            runCurrent()
            assertEquals(FolderRefusal.InsideGame, viewModel.uiState.value.folderRefused)
            assertEquals(DownloadFolder("""D:\Downloads\Mods"""), preferences.installDefaults().downloadFolder, "a refused folder changes nothing")

            picks += library.defaultDownloadFolder.path
            viewModel.changeDownloadFolder("Choose a folder for new downloads")
            runCurrent()
            assertNull(viewModel.uiState.value.folderRefused)
            assertNull(preferences.installDefaults().downloadFolder, "Hideout's own folder is remembered as no choice")
        }
    }

    @Test
    fun theGameRowsCountTheFoundGameOnly() {
        val elsewhere = GameRoot(SampleFolders.Elsewhere)
        val files = UncataloguedSampleManifest.files(FakeConfig()).apply {
            addAddonsEntry(elsewhere, "someone_else.vpk")
            addAddonsEntry(elsewhere, "and_another.vpk")
        }
        val sample = UncataloguedSampleManifest.manifest(FakeConfig())
        val moved = sample.records.first { it.enabled }.copy(installId = "elsewhere", gameRoot = elsewhere)
        val records = sample.records + moved
        val manifest = sample.copy(records = records, profiles = gg.hideouthq.core.library.ProfileBook.main(records))
        val library: (Preferences, CoroutineScope) -> ModLibrary = { preferences, scope ->
            ModLibrary(FakeGameLocator(listOf(SampleFolders.Steam)), preferences, InMemoryLibraryStore(manifest), files, files.ports(), scope)
        }
        withViewModel(library) { viewModel, owner ->
            val on = sample.records.filter { it.enabled }
            assertEquals(ModsOn(on.size, on.sumOf { it.files.size }), viewModel.uiState.value.modsOn)
            owner.useGameFolder(SampleFolders.Elsewhere)
            runCurrent()
            assertEquals(ModsOn(1, moved.files.size), viewModel.uiState.value.modsOn)
        }
    }

    @Test
    fun downloadsAreMeasuredOnlyWhileSettingsIsShownAndAgainWhenTheLibraryChanges() = withViewModels { viewModels ->
        val files = UncataloguedSampleManifest.files(FakeConfig())
        val downloads = FakeDownloader(files)
        var inspections = 0
        val downloader = object : Downloader by downloads {
            override suspend fun inspect(entry: DownloadEntry, witness: DownloadEntry): EntryState {
                inspections++
                return downloads.inspect(entry, witness)
            }
        }
        val preferences = Preferences(MemoryStorage())
        val library = ModLibrary(
            FakeGameLocator(listOf(SampleFolders.Steam)), preferences, InMemoryLibraryStore(sample), files,
            InstallPorts(FakeGameBanana(), downloader, files, files, FakeProcesses()), backgroundScope,
        ).apply { start() }
        val viewModel = viewModels.keep(SettingsViewModel(library, preferences, picker, { _, _ -> true }, AppAbout("2.3.4")))
        runCurrent()
        assertEquals(0, inspections, "nothing is measured before Settings shows")

        val shown = backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        assertEquals(410_000_000L, viewModel.measured.matchingBytes)
        library.uninstall(assertNotNull(library.uninstallConfirmation("lilith")), deleteDownload = false)
        runCurrent()
        assertEquals(listOf(sample.downloads.first(), unused), viewModel.measured.clear?.downloads, "the kept download is named by nothing now, so Clear offers it")

        shown.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        inspections = 0
        library.uninstall(assertNotNull(library.uninstallConfirmation("faun-celeste")), deleteDownload = false)
        runCurrent()
        assertEquals(0, inspections, "nor once Settings is gone")
    }

    @Test
    fun spaceUsedCountsOnlyRegisteredDownloadsAndClearGoesOnAfterSettingsIsGone() = withViewModels { viewModels ->
        val files = UncataloguedSampleManifest.files(FakeConfig())
        val preferences = Preferences(MemoryStorage())
        val library = sampleLibrary(files)(preferences, backgroundScope).apply { start() }
        val viewModel = opened(viewModels, SettingsViewModel(library, preferences, picker, { _, _ -> true }, AppAbout("2.3.4")))
        val space = viewModel.measured
        assertEquals(3, space.matching)
        assertEquals(sample.downloads.sumOf { it.archive.sizeBytes }, space.matchingBytes)
        assertEquals(listOf(unused), space.clear?.downloads)

        viewModel.clearDownloads()
        assertEquals(ClearStage.Running, assertIs<Maintenance.Clear>(library.state.value.maintenance).stage)
        viewModels.store.clear()
        runCurrent()
        assertEquals(ClearStage.Cleared(removed = 1, freedBytes = 212_000_000L, kept = 0), assertIs<Maintenance.Clear>(library.state.value.maintenance).stage)
        assertEquals(sample.downloads.filter { it != unused }.map { it.archive.path }.toSet(), files.archivePaths())
    }

    @Test
    fun spaceUsedCountsTheOriginalFilesKeptForRepairedModsAndClearLeavesThem() = withViewModels { viewModels ->
        val files = UncataloguedSampleManifest.files(FakeConfig())
        val kept = FileImage(3_800_000_000L, "e".repeat(64))
        val repaired = sample.records.first()
        val set = repaired.sets.first()
        val manifest = sample.copy(
            records = sample.records.map { record ->
                if (record != repaired) record
                else record.copy(sets = listOf(set.copy(derivation = SetDerivation("kept1", listOf(kept), emptyList(), "codec-1", "build:1", 1, 4))) + record.sets.drop(1))
            },
            originals = listOf(RetainedOriginal("kept1", repaired.installId, set.source, set.componentKey, listOf(SetFile(VpkPart.Directory, kept)))),
        )
        val store = InMemoryLibraryStore(manifest)
        val preferences = Preferences(MemoryStorage())
        val library = ModLibrary(FakeGameLocator(listOf(SampleFolders.Steam)), preferences, store, files, files.ports(), backgroundScope).apply { start() }
        val viewModel = opened(viewModels, SettingsViewModel(library, preferences, picker, { _, _ -> true }, AppAbout("2.3.4")))
        assertEquals(3_800_000_000L, viewModel.uiState.value.keptOriginals)
        assertEquals(listOf(unused), viewModel.measured.clear?.downloads, "Clear offers downloads only")

        viewModel.clearDownloads()
        runCurrent()
        assertEquals(ClearStage.Cleared(removed = 1, freedBytes = 212_000_000L, kept = 0), assertIs<Maintenance.Clear>(library.state.value.maintenance).stage)
        assertEquals(manifest.originals, assertNotNull(store.manifest).originals, "clearing downloads leaves the kept originals")
        assertEquals(3_800_000_000L, viewModel.uiState.value.keptOriginals)
    }

    @Test
    fun restoringFromSettingsNeverStartsTheGameAndAsksFirst() = withViewModels { viewModels ->
        val files = UncataloguedSampleManifest.files(FakeConfig())
        val launcher = FakeGameLauncher()
        val container = AppContainer.fake(files = files, launcher = launcher)
        val viewModel = opened(viewModels, SettingsViewModel(container.modLibrary, container.preferences, picker, container.folderOpener, container.about))
        val root = GameRoot(SampleFolders.Steam)
        val before = files.gameInfo(root)!!.decodeToString()
        viewModel.askRestore()
        runCurrent()
        assertEquals(container.modLibrary.restoreConfirmation(), viewModel.uiState.value.restore)
        viewModel.cancelRestore()
        runCurrent()
        assertEquals(before, files.gameInfo(root)!!.decodeToString(), "cancelling changes nothing")

        viewModel.askRestore()
        viewModel.confirmRestore()
        runCurrent()
        assertNull(viewModel.uiState.value.restore)
        assertEquals(RestoreStage.Restored, assertIs<Maintenance.Restore>(viewModel.uiState.value.maintenance).stage)
        assertEquals(withoutHideoutBlock(before.encodeToByteArray()).decodeToString(), files.gameInfo(root)!!.decodeToString())
        assertEquals(emptyList(), launcher.launches, "the game was never started")
    }

    @Test
    fun aConfirmedRestoreClosesTheBackupsAndAConfirmedDeleteGoesBackToWhatIsLeft() = settings(SampleGame.Found) { viewModel ->
        repeat(2) {
            viewModel.backUp()
            runCurrent()
        }
        val (newer, older) = viewModel.uiState.value.backups
        viewModel.showBackups(BackupsPane.ConfirmDelete(newer))
        viewModel.confirmBackup()
        runCurrent()
        assertEquals(BackupsPane.List, viewModel.uiState.value.backupsPane, "the list shows the one left")
        assertEquals(listOf(older), viewModel.uiState.value.backups)

        viewModel.showBackups(BackupsPane.ConfirmDelete(older))
        viewModel.confirmBackup()
        runCurrent()
        assertNull(viewModel.uiState.value.backupsPane, "with none left the dialog closes")

        viewModel.backUp()
        runCurrent()
        val only = viewModel.uiState.value.backups.single()
        viewModel.showBackups(BackupsPane.ConfirmRestore(only))
        viewModel.confirmBackup()
        runCurrent()
        assertNull(viewModel.uiState.value.backupsPane)
        assertEquals(Maintenance.Backups(BackupWork.Revert(only), BackupStage.Done), viewModel.uiState.value.maintenance)
    }

    @Test
    fun savingABugReportNotesWhereItWentOrThatItFailed() = withViewModels { viewModels ->
        val answers = ArrayDeque<() -> String?>(listOf({ "hideout-report.txt" }, { null }, { throw IOException("read-only") }))
        val container = AppContainer.fake(FakeConfig())
        val reportExport = ReportExport { _, _, _, _ -> answers.removeFirst()() }
        val viewModel = opened(viewModels, SettingsViewModel(container.modLibrary, container.preferences, picker, container.folderOpener, container.about, reportExport = reportExport))

        viewModel.saveReport("Save a bug report")
        runCurrent()
        assertEquals(ReportSaved.Saved("hideout-report.txt"), viewModel.uiState.value.report)

        viewModel.saveReport("Save a bug report")
        runCurrent()
        assertEquals(ReportSaved.Saved("hideout-report.txt"), viewModel.uiState.value.report, "a cancelled save keeps the last note")

        viewModel.saveReport("Save a bug report")
        runCurrent()
        assertEquals(ReportSaved.Failed, viewModel.uiState.value.report)
    }

    @Test
    fun previewsShowWhatIsKeptAndClearingMeasuresAgain() = withViewModels { viewModels ->
        val kept = object : PreviewFiles {
            var bytes = 3_000_000L
            override suspend fun bytes() = bytes
            override suspend fun clear() { bytes = 0 }
        }
        val container = AppContainer.fake(FakeConfig())
        val viewModel = opened(viewModels, SettingsViewModel(container.modLibrary, container.preferences, picker, container.folderOpener, container.about, previewFiles = kept))
        assertEquals(PreviewSpace.Measured(3_000_000L), viewModel.uiState.value.previews)

        viewModel.clearPreviews()
        runCurrent()
        assertEquals(PreviewSpace.Measured(0), viewModel.uiState.value.previews)
    }

    @Test
    fun aBuildWithoutPreviewsHasNoPreviewsRow() = settings(SampleGame.Found) { viewModel ->
        assertNull(viewModel.uiState.value.previews)
        assertFalse(SettingsRegistry.single { it.id == SettingId.Previews }.isShown(viewModel.uiState.value))
    }
}
