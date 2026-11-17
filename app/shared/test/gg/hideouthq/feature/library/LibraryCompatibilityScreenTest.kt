package gg.hideouthq.feature.library

import androidx.lifecycle.ViewModelStore
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeCompatibility
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.CompatLabel
import gg.hideouthq.core.library.CompatStatus
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameInfoEdit
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.RepairChoice
import gg.hideouthq.core.library.RepairWork
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.oneSetRecord
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.feature.moddetail.pageInstallation
import gg.hideouthq.feature.withViewModels
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.advanceTimeBy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryCompatibilityScreenTest {
    private val root = GameRoot("/games/Deadlock")
    private val bytes = (1..4).associateWith { n -> ByteArray(20 + n) { n.toByte() } }
    private val gameInfo = (GameInfoPatch.withBlock("\"GameInfo\"\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n".encodeToByteArray(), HideoutLines(mods = true, customGames = false)) as GameInfoEdit.Patched).bytes

    private fun record(number: Int, enabled: Boolean = true): ManifestRecord = oneSetRecord(
        installId = "set$number",
        gameRoot = root,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 100L + number, 10L + number, null)),
        title = "Mod $number",
        variant = null,
        archive = ArchiveIdentity("/downloads/m$number.zip", 900, "$number".repeat(64)),
        number = number.takeIf { enabled },
        files = listOf(SetFile(VpkPart.Directory, FileImage(bytes.getValue(number).size.toLong(), sha256Hex(bytes.getValue(number))))),
    )

    private inner class Screen(records: List<ManifestRecord>, autoRepair: Boolean) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, gameInfo)
            records.forEach { record -> putBytes(root, record.files.single().relativePath(record), bytes.getValue(record.installId.removePrefix("set").toInt())) }
        }
        val processes = FakeProcesses()
        val port = FakeCompatibility(files)
        val store = InMemoryLibraryStore(LibraryManifest(records = records))
        val preferences = Preferences(MemoryStorage(mapOf("preferences.json" to "{\"autoRepair\":$autoRepair}")))
        lateinit var library: ModLibrary
        lateinit var installed: LibraryViewModel

        fun TestScope.open(viewModels: ViewModelStore) {
            library = ModLibrary(
                FakeGameLocator(listOf(root.path), mapOf(root.path to null)), preferences, store, files,
                InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, processes), backgroundScope, compatibility = port,
            ).also { it.start() }
            installed = LibraryViewModel(library, FolderOpener { _, _ -> true }).also { viewModels.put("installed", it) }
            backgroundScope.launch { installed.uiState.collect {} }
            runCurrent()
        }

        fun row(number: Int) = installed.uiState.value.rows.single { it.installId == "set$number" }

        fun status(number: Int) = row(number).compatStatus

        val compat: CompatUi get() = installed.uiState.value.compat
    }

    private fun found(label: CompatLabel) = CompatStatus.Found(label)

    private fun changing(work: RepairWork) = CompatStatus.Changing(work)

    private fun screen(
        records: List<ManifestRecord>,
        findings: Map<Int, List<gg.hideouthq.core.library.CompatFinding>> = emptyMap(),
        autoRepair: Boolean = false,
        test: suspend TestScope.(Screen) -> Unit,
    ) = withViewModels { viewModels ->
        val screen = Screen(records, autoRepair)
        findings.forEach { (number, found) -> screen.port.findings[sha256Hex(bytes.getValue(number))] = found }
        with(screen) { open(viewModels.store) }
        test(screen)
    }

    @Test
    fun rowsCarryOnlyConfirmedLabelsAndTheSummaryGroupsThem() = screen(
        listOf(record(1), record(2), record(3), record(4)),
        mapOf(1 to listOf(FakeCompatibility.repairable()), 2 to listOf(FakeCompatibility.needsAuthor()), 3 to listOf(FakeCompatibility.unknown())),
    ) { screen ->
        assertEquals(found(CompatLabel.RepairAvailable), screen.status(1))
        assertEquals(found(CompatLabel.NeedsAuthorUpdate), screen.status(2))
        assertNull(screen.status(3), "unknown is not labelled as anything")
        assertNull(screen.status(4), "there is no healthy label")
        assertEquals(listOf("set1"), screen.compat.repairable)
        assertNull(screen.compat.open, "checks open nothing on their own")
    }

    @Test
    fun aProblemOnlyItsAuthorCanFixIsOnItsChipAndNeverInTheSummary() = screen(
        listOf(record(1), record(2, enabled = false)),
        mapOf(1 to listOf(FakeCompatibility.needsAuthor()), 2 to listOf(FakeCompatibility.needsAuthor())),
        autoRepair = true,
    ) { screen ->
        assertEquals(found(CompatLabel.NeedsAuthorUpdate), screen.status(1))
        assertEquals(found(CompatLabel.NeedsAuthorUpdate), screen.status(2), "a mod that is off still says so on its chip")
        assertFalse(screen.compat.shown, "nothing can be done about it, so the summary has nothing to say")
    }

    @Test
    fun aModsDetailsSayWhatIsFoundAndRepairingFromThemAppliesIt() = screen(listOf(record(1)), mapOf(1 to listOf(FakeCompatibility.repairable(edits = 9)))) { screen ->
        screen.installed.openCompat("set1")
        runCurrent()
        val open = assertIs<CompatOpen.Mod>(screen.compat.open)
        assertEquals("Mod 1", open.title)
        assertEquals(9, assertNotNull(open.install).repairable.single().edits)
        assertEquals(found(CompatLabel.RepairAvailable), screen.installed.uiState.value.selected?.row?.compatStatus)

        screen.installed.applyRepair("set1")
        runCurrent()
        assertEquals(found(CompatLabel.RepairApplied), screen.status(1))
        val applied = assertIs<CompatOpen.Mod>(screen.compat.open)
        assertEquals(1, assertNotNull(applied.install).repaired.size, "the dialog now says what was repaired")
        assertEquals(emptyList(), screen.compat.repairable)

        screen.installed.useOriginal("set1")
        runCurrent()
        assertNull(screen.status(1), "the original files are the user's answer, so no chip asks again")
        assertEquals(RepairChoice.Original, screen.library.state.value.compatibility.installs.getValue("set1").choice)
        assertEquals(emptyList(), screen.compat.repairable, "a mod whose original files were chosen is not offered in Repair all")
        screen.installed.closeCompat()
        runCurrent()
        assertNull(screen.compat.open)
    }

    @Test
    fun whileDeadlockRunsWhatWasAskedIsShownApartFromWhatIsApplied() = screen(listOf(record(1)), mapOf(1 to listOf(FakeCompatibility.repairable()))) { screen ->
        screen.processes.scan = ProcessScan.Seen(game = true)
        runCurrent()
        screen.installed.applyRepair("set1")
        runCurrent()
        val install = screen.library.state.value.compatibility.installs.getValue("set1")
        assertEquals(RepairChoice.Repaired, install.choice, "asked for")
        assertEquals(emptyList(), install.applied, "not applied")
        assertEquals(RepairWork.WaitingForGame(restoring = false), install.pending)
        assertEquals(changing(RepairWork.WaitingForGame(restoring = false)), screen.status(1), "shown waiting, never called applied before its files are")
        assertEquals(CompatLabel.RepairAvailable, install.label(enabled = true))
        assertEquals(1, screen.compat.waiting)
        assertTrue(screen.compat.shown)
        assertNull(screen.compat.finished, "nothing has ended yet")
    }

    @Test
    fun repairAllKeepsReviewOpenAndEveryModShowsItsOwnProgressUntilItEnds() = screen(
        listOf(record(1), record(2), record(3)),
        mapOf(1 to listOf(FakeCompatibility.repairable()), 2 to listOf(FakeCompatibility.repairable())),
    ) { screen ->
        val derive = CompletableDeferred<Unit>()
        screen.port.beforeDerive = { derive.await() }
        screen.installed.reviewCompat()
        runCurrent()
        screen.installed.repairAll()
        runCurrent()
        val review = assertIs<CompatOpen.Review>(screen.compat.open, "Review stays open after Repair all")
        assertEquals(listOf("set1", "set2"), review.items.map { it.installId }, "the same mods, in the same order")
        assertEquals(listOf(changing(RepairWork.Repairing), changing(RepairWork.Queued(restoring = false))), review.items.map { it.status })
        assertEquals(emptyList(), screen.compat.repairable, "nothing is offered twice")
        assertEquals(2, screen.compat.repairing, "both count until each is checked")
        assertTrue(screen.compat.shown && screen.compat.working, "the summary stays")

        screen.installed.closeCompat()
        runCurrent()
        assertNull(screen.compat.open)
        assertEquals(changing(RepairWork.Repairing), screen.status(1), "closing Review stops nothing")
        assertEquals(changing(RepairWork.Queued(restoring = false)), screen.status(2))
        assertNull(screen.status(3), "a mod nobody asked about says nothing")
        assertEquals(changing(RepairWork.Repairing), screen.installed.uiState.value.selected?.row?.compatStatus, "Details shows the same")
        val page = screen.library.state.value.pageInstallation(screen.row(2).modId)
        assertEquals(changing(RepairWork.Queued(restoring = false)), page.compatStatus, "and so does the mod's page")

        derive.complete(Unit)
        runCurrent()
        assertEquals(listOf(found(CompatLabel.RepairApplied), found(CompatLabel.RepairApplied)), listOf(screen.status(1), screen.status(2)))
        assertEquals(0, screen.compat.repairing)
        assertEquals(CompatFinished(repaired = 2, restored = 0), screen.compat.finished, "how it ended stays until dismissed")
        assertTrue(screen.compat.shown)
        screen.installed.dismissCompat()
        runCurrent()
        assertNull(screen.compat.finished)
        assertFalse(screen.compat.shown, "nothing is left to say, and nothing claims the mods are fine")
    }

    @Test
    fun aRepairedModThatIsOffKeepsItsChipsPlaceThoughItSaysNothing() = screen(listOf(record(1), record(2)), mapOf(1 to listOf(FakeCompatibility.repairable()))) { screen ->
        assertFalse(screen.row(2).compatPlace, "a mod with nothing to say has no place for a chip")
        screen.installed.applyRepair("set1")
        runCurrent()
        assertEquals(found(CompatLabel.RepairApplied), screen.status(1))
        screen.library.setEnabled("set1", false)
        runCurrent()
        assertNull(screen.status(1), "a repair of a mod that is off is not called applied")
        assertTrue(screen.row(1).compatPlace, "but its chip keeps its place")
    }

    @Test
    fun theSummaryAndEachRowStayThroughVerificationAndNeverClaimAnUncheckedRepair() = screen(
        listOf(record(1), record(2)),
        mapOf(1 to listOf(FakeCompatibility.repairable()), 2 to listOf(FakeCompatibility.repairable())),
    ) { screen ->
        val check = CompletableDeferred<Unit>()
        screen.port.beforeCheck = { record -> if (record.sets.any { it.derivation != null }) check.await() }
        screen.installed.reviewCompat()
        runCurrent()
        screen.installed.repairAll()
        runCurrent()
        assertNotNull(screen.store.manifest!!.records.single { it.installId == "set1" }.sets.single().derivation, "the first repair is placed")
        assertEquals(changing(RepairWork.Verifying(restoring = false)), screen.status(1), "and verifying, not applied, until its own check lands")
        assertEquals(changing(RepairWork.Queued(restoring = false)), screen.status(2), "the second still waits its turn")
        assertEquals(2, screen.compat.repairing)
        assertTrue(screen.compat.shown, "the summary never leaves mid-repair")
        assertEquals(listOf("set1", "set2"), assertIs<CompatOpen.Review>(screen.compat.open).items.map { it.installId })
        check.complete(Unit)
        runCurrent()
        assertEquals(listOf(found(CompatLabel.RepairApplied), found(CompatLabel.RepairApplied)), listOf(screen.status(1), screen.status(2)))
        assertEquals(CompatFinished(repaired = 2, restored = 0), screen.compat.finished)
    }

    @Test
    fun aRepairThatFailsShowsItOnItsRowAndInTheSummaryAndCanBeAskedForAgain() = screen(listOf(record(1)), mapOf(1 to listOf(FakeCompatibility.repairable()))) { screen ->
        screen.port.refuseDerive = "the repaired model did not read back"
        screen.installed.reviewCompat()
        runCurrent()
        screen.installed.repairAll()
        runCurrent()
        assertEquals(CompatStatus.Unfinished(restoring = false), screen.status(1))
        assertEquals(1, screen.compat.unfinished)
        assertTrue(screen.compat.shown)
        assertEquals(emptyList(), screen.compat.repairable, "it counts once, as didn’t finish, not also as one to repair")
        assertEquals(listOf("set1"), screen.compat.retry, "it can be asked for again")
        assertNull(screen.compat.finished, "nothing was repaired, so nothing is reported repaired")
        screen.port.refuseDerive = null
        screen.installed.repairAll()
        runCurrent()
        assertEquals(found(CompatLabel.RepairApplied), screen.status(1))
        assertEquals(0, screen.compat.unfinished)
        assertEquals(CompatFinished(repaired = 1, restored = 0), screen.compat.finished)
    }

    @Test
    fun aRestoreShowsQueuedRestoringAndVerifyingOnItsRowThenTheOriginals() = screen(listOf(record(1)), mapOf(1 to listOf(FakeCompatibility.repairable()))) { screen ->
        screen.installed.applyRepair("set1")
        runCurrent()
        screen.installed.dismissCompat()
        val check = CompletableDeferred<Unit>()
        screen.port.beforeCheck = { record -> if (record.sets.single().derivation == null) check.await() }
        screen.installed.useOriginal("set1")
        runCurrent()
        assertNull(screen.store.manifest!!.records.single().sets.single().derivation, "the original files are back")
        assertEquals(changing(RepairWork.Verifying(restoring = true)), screen.status(1))
        assertEquals(1, screen.compat.restoring)
        check.complete(Unit)
        runCurrent()
        assertNull(screen.status(1), "the original files are back, as the user chose")
        assertEquals(CompatFinished(repaired = 0, restored = 1), screen.compat.finished)
        assertEquals(emptyList(), screen.compat.repairable, "the originals were chosen, so Repair all leaves it alone")
    }

    @Test
    fun reviewKeepsTheModsItOpenedWithEvenWhenOneHasNothingLeftToSay() = screen(
        listOf(record(1), record(2), record(3)),
        mapOf(1 to listOf(FakeCompatibility.repairable()), 2 to listOf(FakeCompatibility.needsAuthor()), 3 to listOf(FakeCompatibility.unknown())),
    ) { screen ->
        screen.installed.reviewCompat()
        runCurrent()
        assertEquals(listOf("set1", "set2"), assertIs<CompatOpen.Review>(screen.compat.open).items.map { it.installId }, "only confirmed problems: unknown is not one")
        screen.port.findings[sha256Hex(bytes.getValue(2))] = emptyList()
        screen.library.checkCompatibility()
        runCurrent()
        val review = assertIs<CompatOpen.Review>(screen.compat.open)
        assertEquals(listOf("set1", "set2"), review.items.map { it.installId }, "no row moves while Review is open")
        assertNull(review.items.single { it.installId == "set2" }.status)
        screen.installed.closeCompat()
        screen.installed.reviewCompat()
        runCurrent()
        assertEquals(listOf("set1"), assertIs<CompatOpen.Review>(screen.compat.open).items.map { it.installId }, "opened again, it lists what holds now")
    }

    @Test
    fun withAutomaticRepairARepairHideoutMadeOnItsOwnIsOnItsRowAloneAndReviewLeavesItOut() = screen(
        listOf(record(1), record(2)),
        mapOf(1 to listOf(FakeCompatibility.repairable()), 2 to listOf(FakeCompatibility.repairable())),
        autoRepair = true,
    ) { screen ->
        assertEquals(listOf(found(CompatLabel.RepairApplied), found(CompatLabel.RepairApplied)), listOf(screen.status(1), screen.status(2)), "both are repaired without asking")
        assertTrue(screen.compat.automatic)
        assertFalse(screen.compat.shown, "nobody asked here, so the summary has nothing to say")
        screen.installed.reviewCompat()
        runCurrent()
        assertEquals(emptyList(), assertIs<CompatOpen.Review>(screen.compat.open).items, "a repair in use is not a problem")
    }

    @Test
    fun underAutomaticRepairOneThatDidNotFinishTriesOnceMoreWhenDeadlockClosesAndTheSummaryOffersTryAgain() = screen(
        listOf(record(1)),
        mapOf(1 to listOf(FakeCompatibility.repairable())),
        autoRepair = true,
    ) { screen ->
        screen.port.refuseDerive = "the repaired model did not read back"
        screen.port.game = "build:updated"
        screen.library.checkCompatibility()
        runCurrent()
        assertEquals(listOf("set1"), screen.compat.retry, "the summary offers it again")
        val attempts = screen.port.derived.size
        screen.port.refuseDerive = null
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals(attempts, screen.port.derived.size, "nothing tries it again while nothing changes")
        screen.processes.scan = ProcessScan.Seen(game = true)
        advanceTimeBy(3.seconds)
        runCurrent()
        screen.processes.scan = ProcessScan.Seen()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(found(CompatLabel.RepairApplied), screen.status(1), "Deadlock closing tried it once more")
        assertEquals(emptyList(), screen.compat.retry)
    }

    @Test
    fun withAutomaticRepairWorkNobodyAskedForIsNotCountedInTheSummary() = screen(
        listOf(record(1)),
        mapOf(1 to listOf(FakeCompatibility.repairable())),
        autoRepair = true,
    ) { screen ->
        val derive = CompletableDeferred<Unit>()
        screen.port.beforeDerive = { derive.await() }
        screen.port.game = "build:updated"
        screen.library.checkCompatibility()
        runCurrent()
        assertEquals(changing(RepairWork.Repairing), screen.status(1), "the row shows the repair Hideout makes on its own")
        assertEquals(0, screen.compat.repairing)
        assertFalse(screen.compat.shown, "the summary stays quiet")
        derive.complete(Unit)
        runCurrent()
        assertEquals(found(CompatLabel.RepairApplied), screen.status(1))
    }

    @Test
    fun withAutomaticRepairOnlyWhatDidNotFinishIsOfferedAndReviewTriesItAgain() = screen(
        listOf(record(1), record(2)),
        mapOf(1 to listOf(FakeCompatibility.repairable()), 2 to listOf(FakeCompatibility.repairable())),
        autoRepair = true,
    ) { screen ->
        screen.port.refuseDerive = "the repaired model did not read back"
        screen.port.game = "build:updated"
        screen.library.checkCompatibility()
        runCurrent()
        assertEquals(listOf(CompatStatus.Unfinished(restoring = false), CompatStatus.Unfinished(restoring = false)), listOf(screen.status(1), screen.status(2)))
        assertEquals(2, screen.compat.unfinished)
        assertEquals(emptyList(), screen.compat.repairable, "nothing is offered that Hideout repairs by itself")
        assertEquals(listOf("set1", "set2"), screen.compat.offered, "only the repairs that didn’t finish")
        screen.installed.reviewCompat()
        runCurrent()
        screen.installed.openCompat("set1")
        runCurrent()
        assertIs<CompatOpen.Mod>(screen.compat.open)
        screen.installed.closeCompat()
        runCurrent()
        assertEquals(listOf("set1", "set2"), assertIs<CompatOpen.Review>(screen.compat.open, "closing a mod's details opened from Review goes back to it").items.map { it.installId })
        screen.port.refuseDerive = null
        screen.installed.repairAll()
        runCurrent()
        assertEquals(listOf(found(CompatLabel.RepairApplied), found(CompatLabel.RepairApplied)), listOf(screen.status(1), screen.status(2)))
        assertEquals(CompatFinished(repaired = 2, restored = 0), screen.compat.finished, "what Review asked for is reported")
        screen.installed.closeCompat()
        runCurrent()
        assertNull(screen.compat.open)
    }

    @Test
    fun aPartlyRepairedModSaysSoOnItsChipAndTheSummaryOnlyHowItsRepairEnded() = screen(
        listOf(record(1)),
        mapOf(1 to listOf(FakeCompatibility.repairable(rule = FakeCompatibility.Sound), FakeCompatibility.needsAuthor(rule = FakeCompatibility.Frame))),
    ) { screen ->
        screen.installed.applyRepair("set1")
        runCurrent()
        assertEquals(found(CompatLabel.PartlyRepaired), screen.status(1), "the camera is still the author's to fix")
        assertEquals(CompatFinished(repaired = 1, restored = 0), screen.compat.finished)
        screen.installed.dismissCompat()
        runCurrent()
        assertFalse(screen.compat.shown, "once dismissed, what is left for the author stays on the chip alone")
    }
}
