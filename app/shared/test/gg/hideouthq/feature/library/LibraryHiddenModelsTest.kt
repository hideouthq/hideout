package gg.hideouthq.feature.library

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.ConflictReport
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModelLoss
import gg.hideouthq.core.library.Mount
import gg.hideouthq.core.library.Mounted
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.SetKey
import gg.hideouthq.feature.withViewModels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryHiddenModelsTest {
    @Test
    fun aRowCountsTheModelsItLosesAndNamesModsPartsAndFilesThatShowThemFirstLoadedFirst() = withViewModels { viewModels ->
        val sample = sampleLibraryManifest()
        val twoParts = sample.records.map { record -> if (record.installId == "hudlock") record.copy(sets = record.sets + record.sets.single().copy(componentKey = "later/hud_dir.vpk")) else record }
        val base = AppContainer.fake(libraryStore = InMemoryLibraryStore(sample.copy(records = twoParts)))
        val records = (base.modLibrary.state.value.library as LibraryStatus.Ready).records.associateBy { it.installId }
        val lilith = Mounted.Managed("lilith", records.getValue("lilith").sets.single().key)
        val werecat = Mounted.Managed("werecat", records.getValue("werecat").sets.single().key)
        val hudlock = Mounted.Managed("hudlock", records.getValue("hudlock").sets.first().key)
        val pak03 = Mounted.Foreign("pak03_dir.vpk", FileStamp(1, 1))
        val laterHudlockPart = Mounted.Managed("hudlock", records.getValue("hudlock").sets.last().key)
        val report = ConflictReport(
            listOf(
                ModelLoss("models/ivy.vmdl_c", Mount(Slot(2), werecat), Mount(Slot(1), lilith)),
                ModelLoss("models/haze.vmdl_c", Mount(Slot(2), werecat), Mount(Slot(3), pak03)),
                ModelLoss("models/hud.vmdl_c", Mount(Slot(10), laterHudlockPart), Mount(Slot(9), hudlock)),
            ),
        )
        val vm = LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, conflicts = flowOf(report)).also { viewModels.keep(it) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val rows = vm.uiState.value.rows.associateBy { it.installId }
        assertEquals(2, rows.getValue("werecat").hiddenModels)
        assertEquals(listOf("Lilith, the Hell Warden", "pak03_dir.vpk"), rows.getValue("werecat").hiddenBy.map { it.name })
        assertEquals(listOf<HiddenBy>(HiddenBy.Part("hudlock_2.zip")), rows.getValue("hudlock").hiddenBy, "its own earlier part, by the file it came from")
        assertEquals(0, rows.getValue("lilith").hiddenModels)
        assertTrue(rows.getValue("hudlock").hiddenMeant, "a mod's own parts are meant to overlap")
        assertFalse(rows.getValue("werecat").hiddenMeant, "a mod and a file nobody kept beside it are not")
    }
    @Test
    fun aReportFromBeforeAModsPartsChangedShowsNoCountForItUntilItCatchesUpAndTheRowsKeepComing() = withViewModels { viewModels ->
        val base = AppContainer.fake()
        val records = (base.modLibrary.state.value.library as LibraryStatus.Ready).records.associateBy { it.installId }
        val lilith = Mounted.Managed("lilith", records.getValue("lilith").sets.single().key)
        val werecat = Mounted.Managed("werecat", records.getValue("werecat").sets.single().key)
        val hudlock = records.getValue("hudlock").sets.single().key
        val before = InstalledSource.Local("hudlock-before", "hudlock_1.zip")
        val oldFirst = Mounted.Managed("hudlock", SetKey.of(before, "first/hud_dir.vpk"))
        val oldSecond = Mounted.Managed("hudlock", SetKey.of(before, "second/hud_dir.vpk"))
        val werecatLoss = ModelLoss("models/ivy.vmdl_c", Mount(Slot(2), werecat), Mount(Slot(1), lilith))
        val reports = MutableStateFlow(ConflictReport(listOf(werecatLoss, ModelLoss("models/hud.vmdl_c", Mount(Slot(10), oldSecond), Mount(Slot(9), oldFirst)))))
        val vm = LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, conflicts = reports).also { viewModels.keep(it) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        val rows = vm.uiState.value.rows.associateBy { it.installId }
        assertEquals(0, rows.getValue("hudlock").hiddenModels, "the report still names the parts hudlock had before")
        assertEquals(1, rows.getValue("werecat").hiddenModels, "a mod the report is current for keeps its count")
        vm.setQuery("hud")
        runCurrent()
        assertEquals("hud", vm.uiState.value.query, "the rows keep coming after a report that lags")
        val now = Mounted.Managed("hudlock", hudlock)
        reports.value = ConflictReport(listOf(werecatLoss, ModelLoss("models/hud.vmdl_c", Mount(Slot(10), now), Mount(Slot(9), lilith))))
        runCurrent()
        assertEquals(1, vm.uiState.value.rows.single { it.installId == "hudlock" }.hiddenModels, "the count shows once the report catches up")
    }
    @Test
    fun keepBothInFilePriorityMakesTheHiddenModelsMeantAndUndoForgetsThePairOnEitherRecord() = withViewModels { viewModels ->
        val sample = sampleLibraryManifest()
        val keptAtInstall = sample.records.map { record -> if (record.installId == "lilith") record.copy(keptBeside = setOf("hudlock")) else record }
        val store = InMemoryLibraryStore(sample.copy(records = keptAtInstall))
        val base = AppContainer.fake(libraryStore = store)
        val records = (base.modLibrary.state.value.library as LibraryStatus.Ready).records.associateBy { it.installId }
        val lilith = Mounted.Managed("lilith", records.getValue("lilith").sets.single().key)
        val werecat = Mounted.Managed("werecat", records.getValue("werecat").sets.single().key)
        val hudlock = Mounted.Managed("hudlock", records.getValue("hudlock").sets.single().key)
        val report = ConflictReport(listOf(
            ModelLoss("models/ivy.vmdl_c", Mount(Slot(2), werecat), Mount(Slot(1), lilith)),
            ModelLoss("models/hud.vmdl_c", Mount(Slot(9), hudlock), Mount(Slot(1), lilith)),
        ))
        val vm = LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, conflicts = flowOf(report)).also { viewModels.keep(it) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        fun meant(installId: String) = vm.uiState.value.rows.single { it.installId == installId }.hiddenMeant
        fun keptBeside(installId: String) = store.manifest!!.records.single { it.installId == installId }.keptBeside
        assertFalse(meant("werecat"))

        base.modLibrary.keepBeside("werecat", "lilith", keep = true)
        runCurrent()
        assertTrue(meant("werecat"), "its chip turns quiet")
        assertEquals(setOf("lilith"), keptBeside("werecat"), "saved with the mod, as the install question saves it")
        assertEquals(setOf("hudlock"), keptBeside("lilith"))

        base.modLibrary.keepBeside("werecat", "lilith", keep = false)
        runCurrent()
        assertFalse(meant("werecat"))
        assertEquals(emptySet(), keptBeside("werecat"))

        assertTrue(meant("hudlock"), "a pair kept at the install question")
        base.modLibrary.keepBeside("hudlock", "lilith", keep = false)
        runCurrent()
        assertFalse(meant("hudlock"), "undone from the other mod's dialog")
        assertEquals(emptySet(), keptBeside("lilith"))
    }

    @Test
    fun aModKeptBesideAnotherAtTheSwapQuestionHidesItsModelsAsMeantUntilAnythingElseDoes() = withViewModels { viewModels ->
        val sample = sampleLibraryManifest()
        val kept = sample.records.map { record -> if (record.installId == "lilith") record.copy(keptBeside = setOf("werecat")) else record }
        val base = AppContainer.fake(libraryStore = InMemoryLibraryStore(sample.copy(records = kept)))
        val records = (base.modLibrary.state.value.library as LibraryStatus.Ready).records.associateBy { it.installId }
        val lilith = Mounted.Managed("lilith", records.getValue("lilith").sets.single().key)
        val werecat = Mounted.Managed("werecat", records.getValue("werecat").sets.single().key)
        val ivy = ModelLoss("models/ivy.vmdl_c", Mount(Slot(2), werecat), Mount(Slot(1), lilith))
        val reports = MutableStateFlow(ConflictReport(listOf(ivy)))
        val vm = LibraryViewModel(base.modLibrary, FolderOpener { _, _ -> true }, conflicts = reports).also { viewModels.keep(it) }
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertEquals(listOf<HiddenBy>(HiddenBy.Mod("lilith", "Lilith, the Hell Warden", kept = true)), vm.uiState.value.rows.single { it.installId == "werecat" }.hiddenBy)
        assertTrue(vm.uiState.value.rows.single { it.installId == "werecat" }.hiddenMeant, "the user kept Lilith on beside it")
        reports.value = ConflictReport(listOf(ivy, ModelLoss("models/haze.vmdl_c", Mount(Slot(2), werecat), Mount(Slot(1), Mounted.Foreign("pak01_dir.vpk", FileStamp(1, 1))))))
        runCurrent()
        assertFalse(vm.uiState.value.rows.single { it.installId == "werecat" }.hiddenMeant, "a file nobody kept beside it hides a model too")
    }
}
