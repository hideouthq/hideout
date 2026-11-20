package gg.hideouthq.feature.library

import androidx.lifecycle.ViewModelStore
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FilesDamage
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LoadsAddonsGameInfo
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.MigrationId
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.OwnedDownload
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.library.ReinstallFrom
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.Undoable
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.VersionPosition
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.archive
import gg.hideouthq.core.library.number
import gg.hideouthq.core.library.oneSetRecord
import gg.hideouthq.core.library.only
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.core.library.source
import gg.hideouthq.core.library.variant
import gg.hideouthq.core.library.version
import gg.hideouthq.core.library.withOnly
import gg.hideouthq.feature.shell.ShellNotice
import gg.hideouthq.feature.shell.SnackbarMessage
import gg.hideouthq.feature.shell.ShellViewModel
import gg.hideouthq.feature.withViewModels
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledActionsTest {
    private val root = GameRoot("/games/Deadlock")
    private val bytes = (1..5).associateWith { number -> ByteArray(10 + 20 * number) { number.toByte() } }

    private fun bytesOf(record: ManifestRecord) = bytes.getValue(record.installId.removePrefix("set").toInt())

    private fun offPath(record: ManifestRecord) = record.placed(Placement.Off).let { off -> off.files.single().relativePath(off) }

    private fun record(number: Int, enabled: Boolean, version: VersionPosition? = null, variant: String? = "Default", gameRoot: GameRoot = root) = oneSetRecord(
        installId = "set$number",
        gameRoot = gameRoot,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 100L + number, 10L + number, null)),
        title = "Mod $number",
        variant = variant,
        archive = ArchiveIdentity("/data/downloads/mod-${100 + number}/${10 + number}/m$number.zip", 900, "$number".repeat(64)),
        number = number.takeIf { enabled },
        files = listOf(SetFile(VpkPart.Directory, FileImage(bytes.getValue(number).size.toLong(), sha256Hex(bytes.getValue(number))))),
        version = version,
    )

    private class GatedStorage(private var text: String?) : CacheStorage {
        val gate = CompletableDeferred<Unit>()
        override suspend fun read(name: String): String? {
            gate.await()
            return text
        }
        override suspend fun write(name: String, text: String) {
            this.text = text
        }
    }

    private inner class Screen(records: List<ManifestRecord>) {
        val files = InMemoryGameFiles()
        val banana = FakeGameBanana()
        val processes = FakeProcesses()
        val opened = mutableListOf<Pair<GameRoot, SetFolder>>()
        val store = InMemoryLibraryStore(LibraryManifest(records = records, profiles = ProfileBook.main(records), downloads = records.mapNotNull { record ->
            (record.sets.first().source as? InstalledSource.Remote)?.let { OwnedDownload(it.identity, checkNotNull(record.sets.first().download)) }
        }))
        lateinit var library: ModLibrary
        lateinit var installed: LibraryViewModel
        lateinit var shell: ShellViewModel
        lateinit var viewModels: ViewModelStore

        init {
            records.forEach { record ->
                val file = record.files.first()
                files.putBytes(record.gameRoot, file.relativePath(record), bytesOf(record))
                record.sets.first().download?.let { files.putArchive(it.path, emptyList(), FileImage(it.sizeBytes, it.sha256)) }
            }
        }

        fun TestScope.open(viewModels: ViewModelStore) {
            this@Screen.viewModels = viewModels
            library = ModLibrary(
                FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), store, files,
                InstallPorts(banana, FakeDownloader(files), files, files, processes), backgroundScope,
            ).also { it.start() }
            installed = LibraryViewModel(library, FolderOpener { root, folder -> opened += root to folder; true }).also { viewModels.put("installed", it) }
            shell = ShellViewModel(library, FakeGameLauncher()).also { viewModels.put("shell", it) }
            backgroundScope.launch { installed.uiState.collect {} }
            backgroundScope.launch { shell.uiState.collect {} }
            runCurrent()
        }

        val ui get() = installed.uiState.value

        fun row(number: Int) = ui.rows.single { it.installId == "set$number" }

        fun onNumbers() = store.manifest!!.records.filter { it.enabled }.associate { it.installId to it.number }

        fun drop(installId: String, toIndex: Int) = installed.moveSet(ui.orderView!!, installId, toIndex)
    }

    private fun TestScope.seesDeadlock(screen: Screen, game: Boolean) {
        screen.processes.scan = ProcessScan.Seen(game = game)
        advanceTimeBy(2_001)
        runCurrent()
    }

    private fun screen(records: List<ManifestRecord>, before: Screen.() -> Unit = {}, test: suspend TestScope.(Screen) -> Unit) = withViewModels { viewModels ->
        val screen = Screen(records).apply(before)
        with(screen) { open(viewModels.store) }
        test(screen)
    }

    @Test
    fun theSwitchShowsThePositionAskedForWhileTheChangeRunsAndThenTheManifests() = screen(listOf(record(1, enabled = true))) { screen ->
        screen.processes.gate = CompletableDeferred()
        screen.installed.setEnabled("set1", false)
        runCurrent()
        assertEquals(Triple(false, false, true), screen.row(1).let { Triple(it.enabled, it.busy, it.canToggle) })
        assertEquals(true, screen.store.manifest!!.records.single().enabled, "the manifest still holds the set on")
        assertEquals(emptySet(), screen.library.state.value.profiles!!.activeProfile.enabled)
        assertEquals(emptySet(), screen.store.manifest!!.savedChoices!!.target.activeProfile.enabled)
        assertEquals(FilesPlace.InGame, screen.ui.selected!!.files, "its files have not moved yet")

        screen.processes.gate!!.complete(Unit)
        runCurrent()
        assertEquals(Triple(false, false, true), screen.row(1).let { Triple(it.enabled, it.busy, it.canToggle) })
        assertEquals(FilesPlace.Out, screen.ui.selected!!.files)
        assertEquals(listOf(0, 1), screen.ui.let { listOf(it.enabledCount, it.totalCount) })
        assertEquals(0L, screen.ui.enabledSizeBytes)
    }

    @Test
    fun anUninstallLeavesTheListAtOnceAndItsFilesGoOnceTheUndoWindowPasses() = screen(listOf(record(1, enabled = true), record(2, enabled = false))) { screen ->
        screen.installed.uninstall("set2")
        runCurrent()
        assertEquals(listOf("set1"), screen.ui.rows.filter { it.visible }.map { it.installId }, "the row leaves at once, with no question")
        assertEquals(1, screen.ui.totalCount)
        assertEquals(SnackbarMessage.Edit(Undoable.Uninstalled(setOf("set2"), record(2, enabled = false).title)), screen.shell.uiState.value.snackbar?.message)
        assertEquals(2, screen.store.manifest!!.records.size, "nothing is removed while Undo may bring it back")

        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(listOf("set1"), screen.store.manifest!!.records.map { it.installId })
        assertEquals(listOf("set1"), screen.ui.rows.map { it.installId })
        assertEquals(2, screen.files.archivePaths().size, "Settings keeps downloads, so the download stays")
    }

    @Test
    fun undoWithinTheWindowBringsAnUninstalledModBackAsItWas() = screen(listOf(record(1, enabled = true), record(2, enabled = false))) { screen ->
        screen.files.putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo)
        screen.installed.uninstall("set1")
        runCurrent()
        assertEquals(listOf("set2"), screen.ui.rows.filter { it.visible }.map { it.installId })
        screen.shell.undo()
        runCurrent()
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(listOf("set1", "set2"), screen.ui.rows.filter { it.visible }.map { it.installId })
        assertEquals(listOf(true, false), screen.store.manifest!!.records.map { it.enabled }, "back on, in the game again")
    }

    @Test
    fun aModWhoseFileWasEditedOutsideHideoutSaysWhereItReallyIsAndOffersNothingThatWouldReplaceIt() = screen(
        listOf(record(1, enabled = true), record(2, enabled = true)),
        before = { files.putBytes(root, record(1, enabled = true).files.single().relativePath(record(1, enabled = true)), "the owner's own build".encodeToByteArray()) },
    ) { screen ->
        screen.library.setEnabled("set1", false)
        runCurrent()
        assertTrue(screen.row(1).held)
        assertEquals(true, screen.row(1).enabled, "the switch shows where the file is, not the choice it could not follow")
        assertFalse(screen.row(1).canSwitch, "and stays put until the edit is kept or undone")
        assertNull(screen.row(1).updatePlan, "no update is offered, so Update all leaves it")
        screen.installed.select("set1")
        runCurrent()
        val detail = assertNotNull(screen.ui.selected)
        assertEquals(HeldFiles(listOf("pak01_dir.vpk"), listOf("pak01")), detail.held, "the file the user edited, and that the mod is still in the game as pak01")
        assertEquals(FilesPlace.InGame, detail.files, "its files did not leave the game, whatever the switch says")
        assertEquals(mapOf("set1" to 1, "set2" to 2), screen.onNumbers())
    }

    @Test
    fun anUninstallLeavesTheChunksOfAnEditedDirectoryWithIt() {
        val chunk = ByteArray(15) { 9 }
        val plain = record(1, enabled = true)
        val chunked = plain.withOnly { copy(files = files + SetFile(VpkPart.Chunk(0), FileImage(chunk.size.toLong(), sha256Hex(chunk)))) }
        val (dir, chunkAt) = chunked.files.map { it.relativePath(chunked) }
        screen(listOf(chunked, record(2, enabled = true)), before = {
            files.putBytes(root, dir, "the owner's own build".encodeToByteArray())
            files.putBytes(root, chunkAt, chunk)
        }) { screen ->
            screen.installed.uninstall("set1")
            runCurrent()
            advanceTimeBy(7_000)
            runCurrent()
            assertEquals(listOf("set2"), screen.ui.rows.map { it.installId })
            assertContentEquals("the owner's own build".encodeToByteArray(), screen.files.bytes(root, dir))
            assertContentEquals(chunk, screen.files.bytes(root, chunkAt), "the uninstall left the chunk with the edited directory")
        }
    }

    @Test
    fun aModWhoseEditedFileIsHeldStaysHeldWhenAnotherOfItsFilesGoesMissing() {
        val edited = record(1, enabled = true)
        val twoSets = edited.copy(sets = edited.sets + edited.only.copy(componentKey = "pak02_dir.vpk", files = listOf(SetFile(VpkPart.Directory, FileImage(7, "7".repeat(64))))))
        screen(listOf(twoSets), before = { files.putBytes(root, edited.files.single().relativePath(edited), "the owner's own build".encodeToByteArray()) }) { screen ->
            assertEquals(FilesDamage.Missing, screen.row(1).damage, "the worst of its files is the missing one")
            assertTrue(screen.row(1).held, "but the mod is held for the edit all the same")
            assertNull(screen.row(1).updatePlan)
            screen.installed.select("set1")
            runCurrent()
            val detail = assertNotNull(screen.ui.selected)
            assertEquals(HeldFiles(listOf("pak01_dir.vpk"), listOf("pak01", "pak02")), detail.held, "Details asks about the edit and says where the mod is")
            assertEquals(FilesPlace.InGame, detail.files)
        }
    }

    @Test
    fun showFilesOpensTheFolderTheSetIsInNow() = screen(listOf(record(1, enabled = true), record(2, enabled = false))) { screen ->
        screen.installed.showFiles("set1")
        screen.installed.showFiles("set2")
        runCurrent()
        assertEquals(listOf(root to SetFolder.Addons, root to SetFolder.Off(SubmissionRef(SubmissionKind.Mod, 102))), screen.opened)
    }

    @Test
    fun aModPastAddonsShowsItsNumberInTheFolderItIsIn() = screen(listOf(record(1, enabled = true), record(3, enabled = true).placed(Placement.On(Slot(102))))) { screen ->
        screen.installed.select("set3")
        runCurrent()
        val detail = assertNotNull(screen.ui.selected)
        assertEquals(listOf(3), detail.downloads.map { it.number }, "pak03 of hideout_addons2")
        assertTrue(detail.folderPath.endsWith("hideout_addons2"), detail.folderPath)
        screen.installed.showFiles("set3")
        runCurrent()
        assertEquals<List<Pair<GameRoot, SetFolder>>>(listOf(root to SetFolder.On(2)), screen.opened)
    }

    @Test
    fun whenTheSelectedSetIsUninstalledTheRowThatTakesItsPlaceIsSelected() = screen(listOf(record(1, true), record(2, true), record(3, true))) { screen ->
        screen.installed.select("set2")
        runCurrent()
        screen.installed.uninstall("set2")
        runCurrent()
        assertEquals("set3", screen.ui.selected?.row?.installId)
        screen.installed.setLayout(LibraryLayout.Cards)
        runCurrent()
        assertEquals("set3", screen.ui.selected?.row?.installId)
        screen.installed.uninstall("set3")
        runCurrent()
        assertEquals("set1", screen.ui.selected?.row?.installId)
    }

    @Test
    fun aModThatIsOffKeepsItsPlaceAndRankInTheOrderAndADragOfItMovesNoFile() = screen(listOf(record(1, true), record(2, false), record(3, true))) { screen ->
        fun rows() = screen.ui.rows.let { rows -> rows.map { it.installId } to rows.map { it.rank } }
        assertEquals(listOf("set1", "set3", "set2") to listOf(1, 2, 3), rows())
        screen.drop("set2", 0)
        runCurrent()
        assertEquals(listOf("set2", "set1", "set3") to listOf(1, 2, 3), rows(), "rank is the place in the active profile's order, on or off")
        assertEquals(mapOf("set1" to 1, "set3" to 3), screen.onNumbers(), "no number moves")
        assertTrue(screen.files.linkedPaths.isEmpty() && screen.files.removedPaths.isEmpty())

        screen.installed.select("set2")
        runCurrent()
        assertEquals(listOf(null), screen.ui.selected!!.downloads.map { it.number }, "an off mod's file holds no number")
        screen.installed.select("set3")
        runCurrent()
        assertEquals(listOf(3), screen.ui.selected!!.downloads.map { it.number })
    }

    @Test
    fun aPendingDropShowsApplyingWithoutUnlockingMutationsOrChangingConfirmedFiles() = screen(listOf(record(1, true), record(2, true), record(3, false))) { screen ->
        val gate = CompletableDeferred<Unit>()
        screen.processes.gate = gate
        screen.drop("set2", 0)
        runCurrent()

        var state = screen.ui
        assertEquals(ChoiceSync.Applying, screen.library.state.value.choiceSync)
        assertEquals(listOf("set2", "set1", "set3"), state.rows.map { it.installId }, "the order asked for shows at once")
        assertEquals(listOf(1, 2, 3), state.rows.map { it.rank })
        assertEquals(mapOf("set1" to 1, "set2" to 2), screen.onNumbers(), "the files keep their numbers while the drop waits")
        assertEquals(listOf(true, true, true), state.rows.map { it.applying })
        assertEquals(true, screen.shell.uiState.value.applying)
        assertEquals(listOf(true, true, false), state.rows.map { it.enabled })
        assertEquals(listOf(true, true, true), state.rows.map { it.canToggle })
        assertEquals(true, screen.shell.uiState.value.canPlay, "Play takes the files as they are")
        assertEquals(listOf(true, true, true), state.rows.map { it.draggable }, "another row may be dragged while the drop applies")

        screen.installed.setEnabled("set1", false)
        screen.drop("set3", 0)
        runCurrent()
        state = screen.ui
        assertEquals(listOf(false, true, false), state.rows.map { it.enabled }, "toggles may save while order files settle")
        assertTrue(screen.library.state.value.changes.isEmpty())
        assertEquals(listOf("set3", "set2", "set1"), state.rows.map { it.installId }, "the later drop builds on the order shown")
        assertEquals(mapOf("set1" to 1, "set2" to 2), screen.onNumbers())
        val shown = state.orderView

        gate.complete(Unit)
        runCurrent()
        state = screen.ui
        assertEquals(listOf("set3", "set2", "set1"), state.rows.map { it.installId })
        assertEquals(mapOf("set2" to 2), screen.onNumbers(), "set2, dropped on top, keeps its number, and set1 is off")
        assertEquals(listOf(false, true, false), state.rows.map { it.enabled })
        assertEquals(listOf(false, false, false), state.rows.map { it.applying })
        assertEquals(listOf(true, true, true), state.rows.map { it.canToggle && it.draggable })
        assertEquals(false, screen.shell.uiState.value.applying)
        assertEquals(ChoiceSync.Settled, screen.library.state.value.choiceSync)
        assertSame(shown, state.orderView, "a drag begun before the files settled may still drop")
    }

    @Test
    fun aFailedSaveRestoresTheConfirmedOrderAndInvalidatesDependentDrags() = screen(listOf(record(1, true), record(2, true), record(3, true))) { screen ->
        val before = screen.store.manifest
        val shown = screen.ui.orderView
        screen.store.refuse = { "disk full" }
        screen.drop("set3", 0)
        runCurrent()
        val state = screen.ui
        assertEquals(LibraryProblem.ManifestSave("disk full"), screen.shell.uiState.value.notices.filterIsInstance<ShellNotice.ChoiceSaveFailed>().single().problem)
        assertEquals(false, screen.library.state.value.applying)
        assertEquals(listOf("set1", "set2", "set3"), state.rows.map { it.installId })
        assertEquals(listOf(1, 2, 3), state.rows.map { it.rank })
        assertEquals(listOf("set1", "set2", "set3"), state.orderView?.sets)
        assertNotSame(shown, state.orderView, "a drag begun before the refusal may not drop")
        assertEquals(before, screen.store.manifest)
        assertTrue(screen.files.linkedPaths.isEmpty() && screen.files.removedPaths.isEmpty())
    }

    @Test
    fun whileDeadlockRunsAModThatIsOnUninstallsAtOnceAndItsFilesGoWhenItCloses() = screen(listOf(record(1, enabled = true), record(2, enabled = false))) { screen ->
        seesDeadlock(screen, true)
        assertEquals(true, screen.ui.canInstall)
        assertEquals(false, screen.shell.uiState.value.canPlay, "Deadlock runs")
        val addons = screen.files.addonsFiles(root)
        screen.installed.uninstall("set1")
        runCurrent()
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(listOf("set2"), screen.ui.rows.filter { it.visible }.map { it.installId }, "the row has left")
        assertEquals(GameNote.SavedRunning, screen.ui.gameNote, "saved like any other change")
        assertEquals(addons, screen.files.addonsFiles(root), "nothing in the game moves while it runs")

        seesDeadlock(screen, false)
        runCurrent()
        assertEquals(listOf("set2"), screen.store.manifest!!.records.map { it.installId })
        assertEquals(emptyMap(), screen.files.addonsFiles(root))
    }

    @Test
    fun anOffModUninstallsWhileDeadlockRunsWithoutTouchingAddons() = screen(listOf(record(1, enabled = true), record(2, enabled = false))) { screen ->
        seesDeadlock(screen, true)
        val addons = screen.files.addonsFiles(root)
        val off = SetFolder.Off(SubmissionRef(SubmissionKind.Mod, 102))
        assertEquals(record(2, enabled = false).files.map { it.name }.toSet(), screen.files.filesIn(root, off).keys)

        screen.installed.uninstall("set2")
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(GameActivity.Running, screen.library.state.value.deadlock)
        assertEquals(listOf("set1"), screen.store.manifest!!.records.map { it.installId }, "the off mod is uninstalled")
        assertEquals(emptyMap(), screen.files.filesIn(root, off), "its off folder is emptied")
        assertEquals(addons, screen.files.addonsFiles(root), "addons holds exactly what it did")
        assertTrue(screen.files.removedPaths.none { "addons" in it } && screen.files.linkedPaths.none { "addons" in it }, "nothing in addons was linked or removed, got ${screen.files.removedPaths} and ${screen.files.linkedPaths}")
        assertEquals(setOf("set1"), screen.store.manifest!!.profiles.activeProfile.enabled)
        assertEquals(emptyMap(), screen.library.state.value.changes, "nothing is left waiting")
    }

    @Test
    fun detailsSaysThatAModTurnedOnOrOffWhileDeadlockRunsMovesWhenItCloses() = screen(listOf(record(1, enabled = false), record(2, enabled = true))) { screen ->
        fun files(installId: String): FilesPlace {
            screen.installed.select(installId)
            runCurrent()
            return screen.ui.selected!!.files
        }
        screen.files.putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo)
        seesDeadlock(screen, true)
        screen.installed.setEnabled("set1", true)
        screen.installed.setEnabled("set2", false)
        runCurrent()
        assertEquals(listOf(true, false), listOf(screen.row(1).enabled, screen.row(2).enabled), "the switches show the choices at once")
        assertEquals(FilesPlace.GoingIn, files("set1"), "the mod turned on goes into the game when Deadlock closes")
        assertEquals(FilesPlace.GoingOut, files("set2"), "the mod turned off stays in the game until then")

        seesDeadlock(screen, false)
        assertEquals(ChoiceSync.Settled, screen.library.state.value.choiceSync)
        assertEquals(FilesPlace.InGame, files("set1"))
        assertEquals(FilesPlace.Out, files("set2"))
    }

    @Test
    fun severalPickedModsUninstallTogetherWithOneUndo() = screen(listOf(record(1, true), record(2, false), record(3, true))) { screen ->
        screen.installed.select("set1")
        screen.installed.select("set2", SelectGesture.Toggle)
        runCurrent()
        assertEquals(true, screen.ui.several!!.canUninstall)
        screen.installed.uninstallSelected()
        runCurrent()
        assertEquals(SnackbarMessage.Edit(Undoable.Uninstalled(setOf("set1", "set2"), null)), screen.shell.uiState.value.snackbar?.message)
        assertEquals(listOf("set3"), screen.ui.rows.filter { it.visible }.map { it.installId })
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(listOf("set3"), screen.store.manifest!!.records.map { it.installId })
        assertEquals(listOf("set3"), screen.ui.rows.map { it.installId })
    }

    @Test
    fun theNoteThatEditsSaveWhileDeadlockRunsShowsForTheFirstRunLibrarySeesOnly() = screen(listOf(record(1, true))) { screen ->
        val preferences = Preferences(MemoryStorage())
        fun opened(key: String) = LibraryViewModel(screen.library, FolderOpener { _, _ -> true }, preferences = preferences).also { vm ->
            screen.viewModels.put(key, vm)
            backgroundScope.launch { vm.uiState.collect {} }
            runCurrent()
        }
        val first = opened("first")
        assertNull(first.uiState.value.gameNote)
        seesDeadlock(screen, true)
        assertEquals(GameNote.Running, first.uiState.value.gameNote)
        seesDeadlock(screen, false)
        assertNull(first.uiState.value.gameNote)
        seesDeadlock(screen, true)
        assertNull(first.uiState.value.gameNote, "the next run says nothing")
        assertNull(opened("later").uiState.value.gameNote, "nor does Library opened again")
    }

    @Test
    fun theNoteThatDeadlockMayRunWaitsForTheFirstLookAtTheRunningPrograms() = screen(listOf(record(1, true)), before = {
        processes.scan = ProcessScan.Unknown("no process list")
        processes.gate = CompletableDeferred()
    }) { screen ->
        assertNull(screen.ui.gameNote, "nothing is said before the running programs are read")
        screen.processes.gate!!.complete(Unit)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(GameNote.Unknown, screen.ui.gameNote, "a first look that cannot tell says so")
    }

    @Test
    fun aFailedApplyStaysWithItsTryAgainAndIsTriedAgainOnEveryChoiceAndEachTimeDeadlockCloses() = screen(listOf(record(1, true), record(2, false))) { screen ->
        fun notices() = screen.shell.uiState.value.notices.filterIsInstance<ShellNotice.SavedChoicesFailed>()
        fun attempts() = screen.store.saved.zipWithNext().count { (before, after) -> before.pending == null && after.pending != null }
        screen.files.failing += FaultPoint.Link(offPath(record(1, true)))
        screen.installed.setEnabled("set1", false)
        runCurrent()
        val failed = assertIs<ChoiceSync.Failed>(screen.library.state.value.choiceSync)
        assertEquals(1, attempts())
        assertEquals(listOf(ShellNotice.SavedChoicesFailed(failed.problem)), notices())
        assertEquals(failed, screen.shell.uiState.value.choiceSync, "the launch card says the changes are not applied yet")

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, attempts(), "polls alone try nothing")
        seesDeadlock(screen, true)
        seesDeadlock(screen, false)
        assertEquals(2, attempts(), "Deadlock closing tries again")
        assertEquals(failed, screen.library.state.value.choiceSync, "and the file still cannot move")
        assertEquals(1, notices().size, "the notice stays")

        screen.shell.retrySavedChoices()
        runCurrent()
        assertEquals(3, attempts(), "Try again tries at once")
        assertEquals(1, notices().size)

        screen.files.failing.clear()
        screen.files.putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo)
        screen.installed.setEnabled("set2", true)
        runCurrent()
        assertEquals(ChoiceSync.Settled, screen.library.state.value.choiceSync, "the next choice tries again, and it applies")
        assertEquals(emptyList(), notices())
        assertEquals(listOf(false, true), screen.store.manifest!!.records.map { it.enabled })
    }

    @Test
    fun membershipChangesInactiveProfilesWithoutSwitchingAndActiveCountsAreExact() = screen(listOf(record(1, true), record(2, false))) { screen ->
        screen.files.putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo)
        val created = screen.library.createProfile(NewProfile("Ranked-safe", ProfileColour.Blue, ProfileSeed.Empty, activate = false))
        val id = (created as MutationResult.Done).value
        runCurrent()
        screen.installed.setEnabled("set2", true, id)
        runCurrent()
        var state = screen.ui
        assertEquals("Main", state.profiles!!.activeProfile.name)
        assertEquals(setOf("set2"), state.profiles.profiles.single { it.id == id }.enabled)
        assertEquals(1, state.enabledCount)
        assertEquals(30L, state.enabledSizeBytes)
        assertEquals(false, screen.row(2).enabled)
        screen.installed.setEnabled("set2", true, ProfileId("main"))
        runCurrent()
        state = screen.ui
        assertNull(screen.library.state.value.choiceProblem)
        assertEquals(2, state.enabledCount)
        assertEquals(80L, state.enabledSizeBytes)
        assertEquals(true, screen.row(2).enabled)
        assertEquals(setOf("set2"), state.profiles!!.profiles.single { it.id == id }.enabled)
    }

    @Test
    fun aRefusedMembershipChangeIsTheOneChoiceNoticeAndDismissible() = screen(listOf(record(1, true))) { screen ->
        screen.installed.setEnabled("set1", false, ProfileId("missing"))
        runCurrent()
        assertEquals(LibraryProblem.ProfileNotFound, screen.library.state.value.choiceProblem)
        assertEquals(listOf(ShellNotice.ChoiceSaveFailed(LibraryProblem.ProfileNotFound)), screen.shell.uiState.value.notices.filterIsInstance<ShellNotice.ChoiceSaveFailed>())
        assertEquals(true, screen.row(1).enabled)
        screen.shell.dismissChoiceSaveFailure()
        runCurrent()
        assertNull(screen.library.state.value.choiceProblem)
        assertEquals(emptyList(), screen.shell.uiState.value.notices.filterIsInstance<ShellNotice.ChoiceSaveFailed>())
    }

    @Test
    fun filteredNoResultsIsNotAnEmptyLibrary() = screen(listOf(record(1, true))) { screen ->
        screen.installed.setQuery("nothing matches")
        runCurrent()
        assertEquals(0, screen.ui.visibleCount)
        assertEquals(1, screen.ui.totalCount)
        assertEquals(false, screen.ui.isEmpty)
    }

    @Test
    fun aPickTheSearchHidesLeavesDetailsForTheFirstMatchAndComesBackWhenTheSearchClears() = screen(listOf(record(1, true), record(2, true), record(3, true))) { screen ->
        screen.installed.select("set3", SelectGesture.Only)
        runCurrent()
        assertEquals("set3", screen.ui.selected?.row?.installId)
        screen.installed.setQuery("Mod 2")
        runCurrent()
        assertEquals("set2", screen.ui.selected?.row?.installId, "Details never shows a mod the search hides")
        screen.installed.setQuery("nothing matches")
        runCurrent()
        assertNull(screen.ui.selected)
        screen.installed.setQuery("")
        runCurrent()
        assertEquals("set3", screen.ui.selected?.row?.installId, "the pick returns once it shows again")
    }

    @Test
    fun showingAModPicksItAloneAndLetsGoOfASearchThatHidesIt() = screen(listOf(record(1, true), record(2, true), record(3, true))) { screen ->
        screen.installed.select("set1", SelectGesture.Only)
        screen.installed.select("set2", SelectGesture.Toggle)
        screen.installed.setQuery("Mod 2")
        runCurrent()
        screen.installed.show("set3")
        runCurrent()
        assertEquals("", screen.ui.query, "the search that hid it lets go")
        assertEquals(setOf("set3"), screen.ui.selectedIds)
        assertEquals("set3", screen.ui.selected?.row?.installId)

        screen.installed.setQuery("Mod")
        runCurrent()
        screen.installed.show("set1")
        runCurrent()
        assertEquals("Mod", screen.ui.query, "a search that shows it stays")
        assertEquals("set1", screen.ui.selected?.row?.installId)
    }

    @Test
    fun searchingOrFilteringLeavesReorderingOn() =screen(listOf(record(1, true), record(2, true), record(3, true))) { screen ->
        screen.installed.setQuery("Mod 1")
        runCurrent()
        assertEquals(listOf(true, true, true), screen.ui.rows.map { it.draggable }, "a search must not disable ordering")

        screen.installed.setQuery("")
        screen.installed.setFilter(LibraryFilter.Off)
        runCurrent()
        assertEquals(listOf(true, true, true), screen.ui.rows.map { it.draggable }, "a category filter must not disable ordering either")
    }

    @Test
    fun aReadyLibraryWithNoRecordsIsEmpty() = screen(emptyList()) { screen ->
        assertEquals(true, screen.ui.isEmpty)
        assertNull(screen.ui.selected)
        assertEquals(false, LibraryUiState().isEmpty)
        assertEquals(false, LibraryUiState(loading = false, blocked = "Unreadable").isEmpty)
        val download = DownloadRow("mod-1", "First mod", null, null, null, DownloadPhase.Preparing, canCancel = true)
        assertEquals(false, LibraryUiState(loading = false, downloads = listOf(download)).isEmpty, "a download keeps it visible")
    }

    @Test
    fun localRecordsHaveDetailsAndOnlyAKnownSubmissionsPage() {
        val local = record(1, true).withOnly { copy(source = InstalledSource.Local("local1", "skin.vpk"), origin = SetOrigin.Imported(FileImage(900, "1".repeat(64)))) }
        val known = record(2, true, variant = null).withOnly { copy(source = InstalledSource.Local("local2", "pak02_dir.vpk", SubmissionRef(SubmissionKind.Mod, 626_469)), origin = SetOrigin.Imported(FileImage(900, "2".repeat(64)))) }
        screen(listOf(local, known, record(3, true))) { screen ->
            assertNull(screen.row(1).page)
            assertEquals("local-local1", screen.row(1).modId)
            assertEquals("set1", screen.ui.selected?.row?.installId)
            assertEquals("mod-626469", screen.row(2).page, "a local file known to be from a submission has its page")
            assertEquals("local-local2", screen.row(2).modId, "and stays local")
            assertEquals("mod-103", screen.row(3).page, "a GameBanana file has its own page")
            screen.banana.listings = mapOf(SubmissionRef(SubmissionKind.Mod, 626_469) to FileListing.Available("Ace", listOf(RemoteFile(1_648_840, "ace.rar", 1, null, null, 1, 1, "https://gamebanana.com/dl/1648840"))))
            screen.installed.changeFiles("set2")
            runCurrent()
            assertIs<OperationStage.ChoosingFile>(screen.library.state.value.operations["mod-626469"]?.stage, "and Change version offers that page's files")
        }
    }

    @Test
    fun aDamagedModOffersReinstallWhereItsFilesCanBeGotAgainAndKeepOnlyWhenNoneIsMissing() {
        fun adopted(number: Int) = record(number, true).withOnly { copy(source = InstalledSource.Local("local$number", "skin.vpk"), origin = SetOrigin.Adopted(MigrationId("m-1"))) }
        val imported = record(3, true).withOnly { copy(source = InstalledSource.Local("local3", "hud.zip"), origin = SetOrigin.Imported(FileImage(900, "3".repeat(64)))) }
        val records = listOf(record(1, true), adopted(2), imported, adopted(4), record(5, true))
        fun path(index: Int) = records[index].files.single().relativePath(records[index])
        screen(records, before = {
            files.rewrite(root, path(1), ByteArray(5))
            files.rewrite(root, path(2), ByteArray(5))
            files.remove(root, path(3))
            files.remove(root, path(4))
        }) { screen ->
            assertEquals(
                listOf(
                    null to ReinstallFrom.Source,
                    FilesDamage.Changed to null,
                    FilesDamage.Changed to ReinstallFrom.File,
                    FilesDamage.Missing to null,
                    FilesDamage.Missing to ReinstallFrom.Source,
                ),
                (1..5).map { screen.row(it).let { row -> row.damage to row.reinstall } },
            )
        }
    }

    @Test
    fun dragIsBlockedWhenRecordsSpanDifferentGameRoots() {
        val otherRoot = GameRoot("/games/OldDeadlock")
        screen(listOf(record(1, true), record(2, true, gameRoot = otherRoot))) { screen ->
            assertEquals(listOf(false, false), screen.ui.rows.map { it.draggable })
        }
    }

    @Test
    fun aFreshInstallOfAModRecordedInAnotherGameFolderShowsItsDownloadingRow() {
        screen(listOf(record(1, true, gameRoot = GameRoot("/games/OldDeadlock")))) { screen ->
            val files = (1L..2L).map { RemoteFile(it, "ivy-$it.zip", 1, null, null, 1, it, "https://gamebanana.com/dl/$it") }
            screen.banana.listings = mapOf(SubmissionRef(SubmissionKind.Mod, 101) to FileListing.Available("Ivy", files))
            screen.library.install("mod-101")
            runCurrent()
            assertEquals(listOf("mod-101"), screen.ui.downloads.map { it.modId }, "it waits for its file to be chosen")
        }
    }

    @Test
    fun aFailedDownloadLeavesNoRowAndDoesNotBlockDragging() = screen(listOf(record(1, true))) { screen ->
        screen.library.install("not-a-mod")
        runCurrent()
        assertIs<OperationStage.Failed>(screen.library.state.value.operations["not-a-mod"]?.stage, "the shell's notice says why")
        assertEquals(emptyList(), screen.ui.downloads)
        assertEquals(listOf(true), screen.ui.rows.map { it.draggable }, "a failed download must not count as busy")
    }

    @Test
    fun theStoredCardsLayoutIsAppliedBeforeAnyRowsShowSoListNeverFlashes() = screen(listOf(record(1, true))) { screen ->
        val storage = GatedStorage("""{"libraryLayout":"cards"}""")
        val gated = LibraryViewModel(screen.library, FolderOpener { _, _ -> true }, preferences = Preferences(storage)).also { screen.viewModels.put("gated", it) }
        val seen = mutableListOf<LibraryUiState>()
        backgroundScope.launch { gated.uiState.collect { seen += it } }
        runCurrent()
        assertTrue(seen.none { !it.loading }, "nothing meaningful is shown while the persisted layout is still being read")

        storage.gate.complete(Unit)
        runCurrent()
        assertEquals(LibraryLayout.Cards, gated.uiState.value.layout)
        assertEquals(false, gated.uiState.value.loading)
        assertTrue(seen.none { !it.loading && it.layout == LibraryLayout.List }, "List must never flash before the persisted Cards choice is read")
    }

    private suspend fun Screen.profile(name: String, seed: ProfileSeed = ProfileSeed.Empty): ProfileId =
        (library.createProfile(NewProfile(name, ProfileColour.Blue, seed, activate = false)) as MutationResult.Done).value

    @Test
    fun ctrlAndShiftClicksPickSeveralModsThatTurnOffTogetherWithOneUndo() = screen(listOf(record(1, true), record(2, true), record(3, true))) { screen ->
        assertEquals(setOf("set1"), screen.ui.selectedIds, "the first row is picked at first")
        screen.installed.select("set3", SelectGesture.Toggle)
        runCurrent()
        assertEquals(setOf("set1", "set3"), screen.ui.selectedIds)
        assertNull(screen.ui.selected, "one mod's details make way for the selection")
        assertEquals(SelectedMods(count = 2, on = 2, sizeBytes = 100, canToggle = true, canUninstall = true, coverage = mapOf(ProfileId("main") to Coverage.All)), screen.ui.several)

        screen.installed.select("set2")
        screen.installed.select("set3", SelectGesture.Range)
        runCurrent()
        assertEquals(setOf("set2", "set3"), screen.ui.selectedIds)
        screen.installed.select("set2", SelectGesture.Toggle)
        screen.installed.select("set3", SelectGesture.Toggle)
        runCurrent()
        assertEquals(setOf("set3"), screen.ui.selectedIds, "the last picked mod stays picked")
        assertEquals("set3", screen.ui.selected?.row?.installId)
        assertNull(screen.ui.several)

        screen.installed.selectAll()
        runCurrent()
        assertEquals(setOf("set1", "set2", "set3"), screen.ui.selectedIds)
        screen.installed.setQuery("Mod 2")
        runCurrent()
        assertEquals(setOf("set2"), screen.ui.selectedIds, "a hidden mod is never part of the selection")
        screen.installed.setQuery("")
        screen.installed.selectAll()
        runCurrent()

        screen.installed.setSelectedEnabled(false)
        runCurrent()
        assertEquals(listOf(false, false, false), screen.ui.rows.map { it.enabled })
        assertEquals(setOf("set1", "set2", "set3"), screen.ui.selectedIds, "the selection stays, to turn back on")
        assertEquals(SnackbarMessage.Edit(Undoable.Turned(false, 3, profile = null)), screen.shell.uiState.value.snackbar?.message)
        screen.shell.undo()
        runCurrent()
        assertEquals(listOf(true, true, true), screen.ui.rows.map { it.enabled })

        screen.installed.clearSelection()
        runCurrent()
        assertEquals(1, screen.ui.selectedIds.size)
        screen.installed.turnAllOff()
        runCurrent()
        assertEquals(listOf(false, false, false), screen.ui.rows.map { it.enabled })
        assertEquals(SnackbarMessage.Edit(Undoable.Turned(false, 3, profile = null)), screen.shell.uiState.value.snackbar?.message)
        assertEquals(listOf(false, false, false), screen.store.manifest!!.records.map { it.enabled }, "the files followed")
    }

    @Test
    fun severalPickedModsTurnOnOrOffInAnotherProfileTogetherWithOneUndo() = screen(listOf(record(1, true), record(2, true), record(3, false))) { screen ->
        val main = ProfileId("main")
        val ranked = screen.profile("Ranked")
        screen.library.setMembership(ranked, "set1", true)
        runCurrent()
        fun ranked() = screen.library.state.value.profiles!!.profiles.single { it.id == ranked }.enabled
        screen.installed.selectAll()
        runCurrent()
        assertEquals(mapOf(main to Coverage.Some, ranked to Coverage.Some), screen.ui.several!!.coverage)
        screen.installed.select("set2")
        screen.installed.select("set3", SelectGesture.Toggle)
        runCurrent()
        assertEquals(mapOf(main to Coverage.Some, ranked to Coverage.None), screen.ui.several!!.coverage)
        screen.installed.select("set1", SelectGesture.Toggle)
        runCurrent()
        val files = screen.files.addonsFiles(root).keys

        screen.installed.setSelectedEnabled(true, ranked)
        runCurrent()
        assertEquals(setOf("set1", "set2", "set3"), ranked())
        assertEquals(Coverage.All, screen.ui.several!!.coverage[ranked])
        assertEquals(SnackbarMessage.Edit(Undoable.Turned(true, 2, profile = "Ranked")), screen.shell.uiState.value.snackbar?.message)
        assertEquals(listOf(true, true, false), screen.ui.rows.map { it.enabled }, "Main's switches stay as they were")
        assertEquals(files, screen.files.addonsFiles(root).keys, "the files follow Main, which did not change")

        screen.installed.setSelectedEnabled(false, ranked)
        runCurrent()
        assertEquals(emptySet(), ranked())
        assertEquals(SnackbarMessage.Edit(Undoable.Turned(false, 3, profile = "Ranked")), screen.shell.uiState.value.snackbar?.message)
        screen.shell.undo()
        runCurrent()
        assertEquals(setOf("set1", "set2", "set3"), ranked(), "one Undo puts back every mod the click turned")
        assertEquals(files, screen.files.addonsFiles(root).keys)
    }

    @Test
    fun aSwitchShowsTheRequestedProfileEverywhereWhileItsFilesSettle() = screen(listOf(record(1, true), record(2, false))) { screen ->
        val empty = screen.profile("Empty")
        runCurrent()
        screen.processes.gate = CompletableDeferred()
        screen.shell.switchProfile(empty)
        runCurrent()

        val shell = screen.shell.uiState.value
        assertEquals("Empty", shell.profiles!!.activeProfile.name)
        assertEquals(0, shell.modsOn)
        assertEquals(true, shell.applying)
        assertEquals(true, shell.canPlay, "Play takes the files as they are")
        assertEquals(false, shell.saving, "the profile choice has already saved")
        assertEquals(true, shell.canEditChoices, "edits stay enabled while the files settle")
        val installed = screen.ui
        assertEquals("Empty", installed.profiles!!.activeProfile.name)
        assertEquals(listOf(false, false), installed.rows.map { it.enabled })
        assertEquals(listOf(true, true), installed.rows.map { it.canToggle }, "saved choices remain editable while files apply")
        assertEquals(listOf(true, true), installed.rows.map { it.applying }, "switches that only wait keep their full look")
        assertEquals(0, installed.enabledCount)
        assertEquals(listOf(LibraryFilter.All to 2), installed.filters.map { it.filter to it.count }, "both are off in Empty, so no pill would list more than All")
        assertEquals(false, installed.selected!!.row.enabled)
        assertEquals(FilesPlace.InGame, installed.selected.files)

        screen.processes.gate!!.complete(Unit)
        runCurrent()
        val settled = screen.shell.uiState.value
        assertEquals(false, settled.applying)
        assertEquals(false, settled.saving)
        assertEquals(true, screen.library.state.value.canModify)
        assertEquals(FilesPlace.Out, screen.ui.selected!!.files)
    }

    @Test
    fun aToggleShowsAtOnceAndKeepsOtherSwitchesUsableWithoutReplayingAnOldUndo() = screen(listOf(record(1, true), record(2, false))) { screen ->
        screen.files.putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo)
        val other = screen.profile("Other", ProfileSeed.Copy(ProfileId("main")))
        val spare = screen.profile("Spare")
        screen.shell.switchProfile(other)
        screen.shell.deleteProfile(spare)
        runCurrent()
        assertTrue(screen.shell.uiState.value.snackbar?.undo != null)

        screen.processes.gate = CompletableDeferred()
        screen.installed.setEnabled("set1", false)
        runCurrent()
        assertEquals(Triple(false, false, true), screen.row(1).let { Triple(it.enabled, it.busy, it.canToggle) })
        assertEquals(Triple(false, false, true), screen.row(2).let { Triple(it.enabled, it.busy, it.canToggle) })
        assertEquals(0, screen.shell.uiState.value.modsOn)
        assertEquals(true, screen.shell.uiState.value.applying)
        assertEquals(true, screen.shell.uiState.value.canPlay)

        screen.installed.setEnabled("set2", true)
        runCurrent()
        assertEquals(listOf(false, true), screen.ui.rows.map { it.enabled })
        assertEquals(1, screen.shell.uiState.value.modsOn)
        assertEquals(1, screen.ui.enabledCount)
        assertEquals(1, screen.ui.filters.single { it.filter == LibraryFilter.Off }.count)
        screen.shell.undo()
        runCurrent()
        assertEquals(LibraryProblem.UndoExpired, screen.shell.uiState.value.profileProblem?.problem, "a newer saved edit invalidates the old Undo")
        assertEquals(other, screen.library.state.value.profiles!!.active)
        assertEquals(listOf(true, false), screen.store.manifest!!.records.map { it.enabled })

        screen.processes.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(false, true), screen.store.manifest!!.records.map { it.enabled })
        assertEquals(emptyMap(), screen.library.state.value.changes)
        assertEquals(false, screen.shell.uiState.value.applying)
        assertEquals(true, screen.library.state.value.canModify)
        assertEquals(1, screen.shell.uiState.value.modsOn)
    }
}
