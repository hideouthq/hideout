package gg.hideouthq.feature.shell

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SampleFolders
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.SampleGameInfo
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.UncataloguedSampleManifest
import gg.hideouthq.core.data.fake.ports
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.CompatibilityPort
import gg.hideouthq.core.library.GameConfigReceipt
import gg.hideouthq.core.library.GameInfoRewrite
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LoadingOwner
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.RewriteOutcome
import gg.hideouthq.core.library.RewrittenBy
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.grimoireFixed
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ModLoading
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.testWatchTiming
import gg.hideouthq.core.library.withoutHideoutBlock
import gg.hideouthq.feature.library.DownloadPhase
import gg.hideouthq.feature.library.LibraryViewModel
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ShellViewModelTest {
    private val launcher = FakeGameLauncher()

    private fun shell(
        game: SampleGame,
        gameInfo: SampleGameInfo = SampleGameInfo.HideoutBlock,
        files: InMemoryGameFiles = UncataloguedSampleManifest.files(FakeConfig(game = game, gameInfo = gameInfo)),
        test: suspend TestScope.(ShellViewModel, AppContainer) -> Unit,
    ) = withViewModels { viewModels ->
        val container = AppContainer.fake(FakeConfig(game = game, gameInfo = gameInfo), files = files)
        val viewModel = viewModels.keep(ShellViewModel(container.modLibrary, launcher))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        test(viewModel, container)
    }

    @Test
    fun aFoundGameCanBePlayedAfterItsFolderIsCheckedAgain() = shell(SampleGame.Found) { viewModel, _ ->
        val state = viewModel.uiState.value
        assertIs<GameStatus.Ready>(state.game)
        assertTrue(state.canPlay)
        assertEquals(3, state.modsOn)
        viewModel.play(modded = true)
        viewModel.play(modded = false)
        runCurrent()
        assertEquals(listOf(GameRoot(SampleFolders.Steam), GameRoot(SampleFolders.Steam)), launcher.launches, "both start the game that was found")
    }

    private class GameBuild(var id: String) : CompatibilityPort by CompatibilityPort.Unavailable {
        override suspend fun game(root: GameRoot): String = id
    }

    /** A library whose `gameinfo.gi` holds the block Hideout last wrote, on build `build:1`, with Deadlock closed. */
    private inner class Rewrites(private val scope: TestScope) {
        val root = SampleGameRoot
        val files = sampleGameFiles()
        val processes = FakeProcesses()
        private val build = GameBuild("build:1")
        private val receipt = GameConfigReceipt(root, Sha256.image(gameInfo()), HideoutLines(mods = true, customGames = false), game = "build:1")
        val library = ModLibrary(
            FakeGameLocator(listOf(root.path)), Preferences(MemoryStorage()), InMemoryLibraryStore(sampleLibraryManifest().copy(gameConfigs = listOf(receipt))), files,
            files.ports(processes = processes), scope.backgroundScope, watch = testWatchTiming, compatibility = build,
        ).apply { start() }

        fun gameInfo() = checkNotNull(files.gameInfo(root))

        fun settle() = repeat(3) {
            scope.advanceTimeBy(1.seconds)
            scope.runCurrent()
        }

        /** Steam updates Deadlock to [build], shipping a `gameinfo.gi` without Hideout's lines. */
        fun update(build: String = "build:2") {
            this.build.id = build
            rewrite()
        }

        /** Something outside Hideout writes `gameinfo.gi` without Hideout's lines, in bytes Hideout never wrote. */
        fun rewrite() {
            files.writeGameInfo(root, withoutHideoutBlock(gameInfo()).decodeToString().replace("\r\n", "\n").encodeToByteArray())
            settle()
        }
    }

    private fun rewrites(test: suspend TestScope.(ShellViewModel, Rewrites) -> Unit) = withViewModels { viewModels ->
        val world = Rewrites(this)
        val viewModel = viewModels.keep(ShellViewModel(world.library, launcher))
        backgroundScope.launch { viewModel.uiState.collect {} }
        world.settle()
        test(viewModel, world)
    }

    @Test
    fun aMissingGameCannotBePlayed() = shell(SampleGame.NotFound) { viewModel, _ ->
        assertIs<GameStatus.Missing>(viewModel.uiState.value.game)
        assertFalse(viewModel.uiState.value.canPlay)
        viewModel.play(modded = true)
        runCurrent()
        assertEquals(emptyList(), launcher.launches)
    }

    @Test
    fun aGameInfoHideoutNeverWroteGetsItsLinesWithoutTheChip() = shell(SampleGame.Found, gameInfo = SampleGameInfo.Rewritten) { viewModel, container ->
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), container.modLibrary.state.value.loading)
        assertNull(viewModel.uiState.value.rewrite)
        assertEquals(emptyList(), viewModel.uiState.value.notices)
    }

    @Test
    fun aDeadlockUpdateThatTookOutTheModsIsPutBackAndSaidInTheChip() = rewrites { viewModel, world ->
        assertNull(viewModel.uiState.value.rewrite)
        world.update()

        assertEquals(GameInfoRewrite(RewrittenBy.GameUpdate, RewriteOutcome.PutBack), viewModel.uiState.value.rewrite)
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), world.library.state.value.loading)
        assertEquals(emptyList(), viewModel.uiState.value.notices)
        assertNull(viewModel.uiState.value.snackbar)
    }

    @Test
    fun aRewriteOnTheBuildHideoutLastWroteForIsNotCalledAnUpdate() = rewrites { viewModel, world ->
        world.update()
        viewModel.dismissRewrite()
        world.rewrite()
        assertEquals(GameInfoRewrite(RewrittenBy.AnotherProgram(null), RewriteOutcome.PutBack), viewModel.uiState.value.rewrite, "the put-back noted the new build")
    }

    @Test
    fun aRewriteWhileAnotherManagerIsOpenNamesIt() = rewrites { viewModel, world ->
        world.processes.scan = ProcessScan.Seen(managers = setOf(Manager.DeadlockModManager))
        world.settle()
        world.rewrite()
        assertEquals(GameInfoRewrite(RewrittenBy.AnotherProgram(Manager.DeadlockModManager), RewriteOutcome.PutBack), viewModel.uiState.value.rewrite)
    }

    @Test
    fun whileDeadlockRunsTheChipSaysTheModsWaitForItToCloseThenThatTheyAreBack() = rewrites { viewModel, world ->
        world.processes.scan = ProcessScan.Seen(game = true)
        world.settle()
        world.update()
        assertEquals(GameInfoRewrite(RewrittenBy.GameUpdate, RewriteOutcome.WaitingForGame), viewModel.uiState.value.rewrite)
        assertIs<ModLoading.Off>(world.library.state.value.loading, "a running game holds the file")

        world.processes.scan = ProcessScan.Seen()
        world.settle()
        assertEquals(GameInfoRewrite(RewrittenBy.GameUpdate, RewriteOutcome.PutBack), viewModel.uiState.value.rewrite)
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), world.library.state.value.loading)
        assertEquals(emptyList(), viewModel.uiState.value.notices)
    }

    @Test
    fun deadlockStartingAsTheLinesGoBackLeavesTheChipWaitingWithoutAFailure() = rewrites { viewModel, world ->
        world.processes.scan = ProcessScan.Seen(game = true)
        world.update()
        assertEquals(GameInfoRewrite(RewrittenBy.GameUpdate, RewriteOutcome.WaitingForGame), viewModel.uiState.value.rewrite)
        assertEquals(emptyList(), viewModel.uiState.value.notices)
    }

    @Test
    fun linesThatCannotGoBackOnceDeadlockClosesSayWhyInsteadOfWaiting() = rewrites { viewModel, world ->
        world.processes.scan = ProcessScan.Seen(game = true)
        world.settle()
        world.update()
        world.files.failing += FaultPoint.ReplaceConfig
        world.processes.scan = ProcessScan.Seen()
        world.settle()
        assertIs<ShellNotice.ReapplyFailed>(viewModel.uiState.value.notices.single())
        assertNull(viewModel.uiState.value.rewrite)
    }

    @Test
    fun linesMissingFromAFileHideoutWroteGoBackWithoutTheChip() = rewrites { viewModel, world ->
        viewModel.play(modded = false)
        world.settle()
        assertNull(world.library.chooseLoading())
        world.settle()
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), world.library.state.value.loading, "the file Hideout wrote to play without mods gets the lines back")
        assertNull(world.library.state.value.rewrite)
    }

    @Test
    fun afterPlayingWithoutModsARewriteRaisesNothing() = rewrites { viewModel, world ->
        viewModel.play(modded = false)
        world.settle()
        world.update()
        assertNull(world.library.state.value.rewrite)
        assertEquals(true, (world.library.state.value.loading as? ModLoading.Off)?.vanilla)
    }

    @Test
    fun theChipGoesOnceReadAndComesBackForTheNextRewrite() = rewrites { viewModel, world ->
        world.update()
        viewModel.dismissRewrite()
        runCurrent()
        assertNull(viewModel.uiState.value.rewrite)
        world.update(build = "build:3")
        assertEquals(GameInfoRewrite(RewrittenBy.GameUpdate, RewriteOutcome.PutBack), viewModel.uiState.value.rewrite, "a later update is told again")
    }

    @Test
    fun aChipReadWhileDeadlockRunsSaysNothingMoreWhenTheLinesGoBack() = rewrites { viewModel, world ->
        world.processes.scan = ProcessScan.Seen(game = true)
        world.settle()
        world.update()
        viewModel.dismissRewrite()
        world.processes.scan = ProcessScan.Seen()
        world.settle()
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), world.library.state.value.loading)
        assertNull(viewModel.uiState.value.rewrite)
    }

    @Test
    fun aGameInfoThatCannotBePutBackSaysWhyAndCanBeTriedAgain() {
        val files = UncataloguedSampleManifest.files(FakeConfig(gameInfo = SampleGameInfo.Rewritten)).apply { failing += FaultPoint.ReplaceConfig }
        shell(SampleGame.Found, SampleGameInfo.Rewritten, files) { viewModel, container ->
            assertIs<ShellNotice.ReapplyFailed>(viewModel.uiState.value.notices.single())
            files.failing.clear()
            viewModel.reapply()
            runCurrent()
            assertEquals(ModLoading.Loads(LoadingOwner.Hideout), container.modLibrary.state.value.loading)
            assertEquals(emptyList(), viewModel.uiState.value.notices)
        }
    }

    @Test
    fun aRewriteByAManagerImportedFromIsPutBackAndNamedInTheChip() {
        val files = UncataloguedSampleManifest.files(FakeConfig(game = SampleGame.Found))
        shell(SampleGame.Found, files = files) { viewModel, container ->
            val root = GameRoot(SampleFolders.Steam)
            files.writeGameInfo(root, grimoireFixed(checkNotNull(files.gameInfo(root))))
            container.modLibrary.importedFrom(root, Manager.Grimoire)
            runCurrent()

            val putBack = checkNotNull(files.gameInfo(root))
            assertEquals(ModLoading.Loads(LoadingOwner.Hideout), container.modLibrary.state.value.loading)
            assertFalse("citadel/grimoire" in putBack.decodeToString())
            assertEquals(GameInfoRewrite(RewrittenBy.AnotherProgram(Manager.Grimoire), RewriteOutcome.PutBack), viewModel.uiState.value.rewrite)
            assertNull(viewModel.uiState.value.snackbar)
            assertEquals(emptyList(), viewModel.uiState.value.notices)
            viewModel.dismissRewrite()
            viewModel.play(modded = true)
            runCurrent()
            assertEquals(1, launcher.launches.size)
            assertNull(viewModel.uiState.value.rewrite, "the same rewrite is not told again")
        }
    }

    @Test
    fun aFailedInstallSaysWhyUntilDismissed() = shell(SampleGame.Found) { viewModel, container ->
        container.modLibrary.install("lilith")
        runCurrent()
        assertEquals(listOf(ShellNotice.InstallFailed("lilith", null, LibraryProblem.NotAGameBananaMod)), viewModel.uiState.value.notices)
        viewModel.dismissInstall("lilith")
        runCurrent()
        assertEquals(emptyList(), viewModel.uiState.value.notices)
    }

    @Test
    fun playingVanillaWhileAnotherToolLoadsModsIsRefusedWithTheReason() = shell(SampleGame.Found, gameInfo = SampleGameInfo.AnotherTool) { viewModel, _ ->
        viewModel.play(modded = false)
        runCurrent()
        assertEquals(emptyList(), launcher.launches)
        assertEquals(listOf(ShellNotice.LaunchFailed(modded = false, LibraryProblem.AnotherToolLoadsMods)), viewModel.uiState.value.notices)
        viewModel.dismissFailure()
        runCurrent()
        assertEquals(emptyList(), viewModel.uiState.value.notices)
    }

    @Test
    fun theSidebarShowsTheRunningDownloadsProgress() = withViewModels { viewModels ->
        val root = GameRoot("/games/Deadlock")
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, "GameInfo { FileSystem { SearchPaths {\n Game citadel/addons\n Game citadel\n } } }\n".encodeToByteArray())
        }
        val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
        val banana = FakeGameBanana(mapOf(ivy to FileListing.Available("Ivy", listOf(RemoteFile(1, "bg_ivy.zip", 1_000, null, null, 1, 1, "https://gamebanana.com/dl/1")))))
        val downloader = FakeDownloader(files).apply {
            serve(1, listOf("pak01_dir.vpk" to FakeVpk.directory()))
            gate = CompletableDeferred()
        }
        val library = ModLibrary(
            FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), InMemoryLibraryStore(), files,
            InstallPorts(banana, downloader, files, files, FakeProcesses()), backgroundScope,
        ).apply { start() }
        val viewModel = viewModels.keep(ShellViewModel(library, launcher))
        val libraryViewModel = viewModels.keep(LibraryViewModel(library, FolderOpener { _, _ -> true }))
        backgroundScope.launch { viewModel.uiState.collect {} }
        backgroundScope.launch { libraryViewModel.uiState.collect {} }
        runCurrent()

        library.install(ivy.modId)
        runCurrent()
        assertEquals(50, viewModel.uiState.value.transfers?.percent)
        val downloading = libraryViewModel.uiState.value.downloads.single()
        assertEquals(ivy.modId, downloading.modId)
        assertEquals(50, (downloading.phase as DownloadPhase.Transferring).percent)
        downloader.gate!!.complete(Unit)
        runCurrent()
        assertEquals(null, viewModel.uiState.value.transfers)
        assertEquals(emptyList(), libraryViewModel.uiState.value.downloads, "hidden once the record exists")
    }

    @Test
    fun whileGameBananaHoldsEveryDownloadTheSidebarSaysSoInsteadOfAPercentThatStandsStill() = withViewModels { viewModels ->
        val root = GameRoot("/games/Deadlock")
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, "GameInfo { FileSystem { SearchPaths {\n Game citadel/addons\n Game citadel\n } } }\n".encodeToByteArray())
        }
        val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
        val listing = CompletableDeferred<FileListing>()
        val banana = GameBananaClient { listing.await() }
        val busy = MutableStateFlow<Instant?>(null)
        val library = ModLibrary(
            FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), InMemoryLibraryStore(), files,
            InstallPorts(banana, FakeDownloader(files), files, files, FakeProcesses()), backgroundScope, gameBananaBusy = busy,
        ).apply { start() }
        val viewModel = viewModels.keep(ShellViewModel(library, launcher))
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()

        library.install(ivy.modId)
        runCurrent()
        assertEquals(false, viewModel.uiState.value.transfersHeld)
        busy.value = Instant.parse("2026-11-11T18:05:00Z")
        runCurrent()
        assertEquals(1, viewModel.uiState.value.transfers?.count)
        assertEquals(true, viewModel.uiState.value.transfersHeld)
        busy.value = null
        runCurrent()
        assertEquals(false, viewModel.uiState.value.transfersHeld)
    }
}
