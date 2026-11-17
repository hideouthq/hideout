package gg.hideouthq.feature.onboarding

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.ManagerData
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeManagerData
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.ForeignSeed
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.SampleFolders
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.library.DmmGameInfo
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.resourceBytes
import gg.hideouthq.feature.imports.ImportItem
import gg.hideouthq.feature.imports.SourceOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private fun onboarding(
        config: FakeConfig = FakeConfig(setupDone = false, seed = LibrarySeed.Empty),
        folderPicker: FolderPicker = FolderPicker { _, _ -> null },
        files: InMemoryGameFiles = sampleGameFiles(config),
        managers: ManagerData = FakeManagerData(config.foreign),
        test: suspend (OnboardingViewModel, AppContainer) -> Unit,
    ) = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val container = AppContainer.fake(config, folderPicker = folderPicker, files = files, managerData = managers)
        val viewModels = ViewModelStore()
        val viewModel = OnboardingViewModel(container.modLibrary, container.migration, container.preferences, container.folderPicker)
        viewModels.put("onboarding", viewModel)
        val work = viewModel.viewModelScope.coroutineContext.job
        val watching = launch { viewModel.uiState.collect {} }
        try {
            withTimeout(10_000) { test(viewModel, container) }
        } finally {
            watching.cancelAndJoin()
            viewModels.clear()
            work.join()
            Dispatchers.resetMain()
        }
    }

    private suspend fun OnboardingViewModel.awaitState(predicate: (OnboardingUiState) -> Boolean) = uiState.first(predicate)

    @Test
    fun withTheGameFoundAndNothingToBringSetupAsksNothing() = onboarding { viewModel, container ->
        container.preferences.setupDone.first { it == true }
        assertNull(viewModel.uiState.value.step, "no question was ever shown")
        assertTrue(container.modLibrary.state.value.game is GameStatus.Ready)
    }

    @Test
    fun aMissingGameIsAskedForAndChoosingItsFolderOpensHideout() = onboarding(
        config = FakeConfig(setupDone = false, game = SampleGame.NotFound),
        folderPicker = FolderPicker { _, _ -> SampleFolders.Elsewhere },
    ) { viewModel, container ->
        viewModel.awaitState { it.step == SetupStep.Game }
        assertNull(withTimeoutOrNull(500) { container.preferences.setupDone.first { it == true } }, "setup waits for the folder")
        viewModel.chooseFolder("Choose the Deadlock folder")
        container.preferences.setupDone.first { it == true }
        assertIs<GameStatus.Ready>(container.modLibrary.state.value.game)
    }

    @Test
    fun skippingTheGameQuestionOpensHideoutWithoutTheGame() = onboarding(config = FakeConfig(setupDone = false, game = SampleGame.NotFound)) { viewModel, container ->
        viewModel.awaitState { it.step == SetupStep.Game }
        viewModel.skip()
        container.preferences.setupDone.first { it == true }
        assertIs<GameStatus.Missing>(container.modLibrary.state.value.game)
    }

    @Test
    fun aManagerFoundIsOfferedOnOnePageAndItsImportOpensHideout() = onboarding(
        config = FakeConfig(setupDone = false, seed = LibrarySeed.Empty, foreign = ForeignSeed.Dmm),
    ) { viewModel, container ->
        val offered = viewModel.awaitState { it.step == SetupStep.Mods }
        assertEquals(SourceOption.Installed(Manager.DeadlockModManager), offered.mods.from, "the manager found needs no picking")
        assertEquals(listOf(ImportItem.Mods, ImportItem.Profiles, ImportItem.Crosshair), offered.mods.rows.map { it.item })
        assertTrue(offered.mods.canImport)
        viewModel.imports.toggleItem(ImportItem.Crosshair, false)
        viewModel.awaitState { state -> state.mods.rows.any { it.item == ImportItem.Crosshair && !it.ticked } }
        viewModel.imports.startImport()
        container.preferences.setupDone.first { it == true }
        val records = container.modLibrary.state.first { (it.library as? LibraryStatus.Ready)?.records?.size == 3 }
        assertEquals(3, (records.library as LibraryStatus.Ready).records.size, "the mods are Hideout's now")
        assertNull(container.migration.state.first { it.run == null }.run, "the finished run is put away, since Library shows the mods")
    }

    @Test
    fun setupsImportFromAManagerWhoseLiveProfileIsNotItsDefaultKeepsWhatWasOffOff() {
        val (files, managers) = rankedLive()
        val before = files.gameInfo(SampleGameRoot)
        onboarding(files = files, managers = managers) { viewModel, container ->
            viewModel.awaitState { it.step == SetupStep.Mods && it.mods.canImport }
            assertContentEquals(before, files.gameInfo(SampleGameRoot), "offering the import leaves gameinfo.gi as the manager left it")
            viewModel.imports.startImport()
            container.preferences.setupDone.first { it == true }
            container.modLibrary.state.first { (it.library as? LibraryStatus.Ready)?.records?.size == 2 }
            val on = container.modLibrary.state.first { it.profiles?.activeProfile?.enabled?.isNotEmpty() == true }.profiles!!.activeProfile.enabled
            assertEquals(
                listOf("Classic Investments"), (container.modLibrary.state.value.library as LibraryStatus.Ready).records.filter { it.installId in on }.map { it.title },
                "only what the live profile loads comes in on",
            )
        }
    }

    @Test
    fun startingFreshOverAnotherManagerChangesNothingTheGameLoads() {
        val (files, managers) = rankedLive()
        val before = files.gameInfo(SampleGameRoot)
        onboarding(files = files, managers = managers) { viewModel, container ->
            viewModel.awaitState { it.step == SetupStep.Mods }
            viewModel.skip()
            container.preferences.setupDone.first { it == true }
            assertContentEquals(before, files.gameInfo(SampleGameRoot), "Hideout has no mods yet, so it adds nothing that would make the manager's others load")
            assertEquals(0, (container.modLibrary.state.value.library as LibraryStatus.Ready).records.size)
        }
    }

    private fun rankedLive(): Pair<InMemoryGameFiles, ManagerData> {
        val config = FakeConfig(setupDone = false, seed = LibrarySeed.Empty)
        val ranked = "addons/profile_1793707836444_4d9mh1w48_ranked"
        val classic = FakeVpk.directory(seed = 140)
        val files = sampleGameFiles(config).apply {
            putBytes(SampleGameRoot, "game/citadel/gameinfo.gi", DmmGameInfo.decodeToString().replace("citadel/addons", "citadel/$ranked").encodeToByteArray())
            putBytes(SampleGameRoot, "game/citadel/addons/pak01_dir.vpk", classic)
            putBytes(SampleGameRoot, "game/citadel/addons/pak02_dir.vpk", FakeVpk.directory(seed = 141))
            putBytes(SampleGameRoot, "game/citadel/addons/pak03_dir.vpk", FakeVpk.directory(seed = 142))
            putBytes(SampleGameRoot, "game/citadel/addons/.dmm.json", resourceBytes("dmm/addons-manifest-v42.json"))
            putBytes(SampleGameRoot, "game/citadel/$ranked/pak01_dir.vpk", classic)
            putBytes(SampleGameRoot, "game/citadel/$ranked/.dmm.json", resourceBytes("dmm/ranked-manifest-v42.json"))
        }
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.None) {
            override suspend fun dmmState(): ByteArray = resourceBytes("dmm/state-v42.json")
        }
        return files to managers
    }
}
