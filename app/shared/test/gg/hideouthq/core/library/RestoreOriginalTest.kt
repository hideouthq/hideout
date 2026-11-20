package gg.hideouthq.core.library

import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreOriginalTest {
    private val root = GameRoot("/games/Deadlock")
    private val other = GameRoot("/other/Deadlock")
    private val addons = SetFolder.Addons.path

    private val edited = (
        "\"GameInfo\"\r\n{\r\n\t// the player's own note, kept byte for byte\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n" +
            "\t\t\tGame\t\tcitadel\r\n\t\t\tGame\t\tcitadel_tweaks\r\n\t\t\tGame\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n"
        ).encodeToByteArray()
    private val patched = (GameInfoPatch.withBlock(edited, HideoutLines(mods = true, customGames = false), 0) as GameInfoEdit.Patched).bytes
    private val anotherTool = String(edited).replace("\t\t\tGame\t\tcitadel\r\n", "\t\t\tGame\t\tcitadel/addons\r\n\t\t\tGame\t\tcitadel\r\n").encodeToByteArray()

    private val vpk = ByteArray(12) { 3 }
    private val record = oneSetRecord(
        installId = "ivy",
        gameRoot = root,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 628_739, 9, null)),
        title = "Bubblegum ivy",
        variant = null,
        archive = ArchiveIdentity("/downloads/mod-628739/9/ivy.zip", 1_000, "9".repeat(64)),
        number = 3,
        files = listOf(SetFile(VpkPart.Directory, FileImage(vpk.size.toLong(), sha256Hex(vpk)))),
    )

    private val laterVpk = ByteArray(14) { 5 }
    private val later = oneSetRecord(
        installId = "later",
        gameRoot = root,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 700_001, 1, null)),
        title = "Later",
        variant = null,
        archive = ArchiveIdentity("/downloads/mod-700001/1/later.zip", 2_000, "8".repeat(64)),
        number = null,
        files = listOf(SetFile(VpkPart.Directory, FileImage(laterVpk.size.toLong(), sha256Hex(laterVpk)))),
    )

    private inner class World(gameInfo: ByteArray = patched, pending: PendingChange? = null) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, gameInfo)
            putBytes(other, InMemoryGameFiles.GameInfo, edited)
            putBytes(root, "$addons/pak03_dir.vpk", vpk)
            if (pending != null) putBytes(root, later.files.single().relativePath(later), laterVpk)
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(record), pending = pending))
        val processes = FakeProcesses()
        private var nextId = 0

        fun TestScope.library(): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root, other)), Preferences(MemoryStorage()), store, files,
                InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, processes), backgroundScope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
            ),
        )

        fun gameInfo(game: GameRoot = root) = assertNotNull(files.gameInfo(game))

        fun assertSetsAsTheyWere() {
            assertEquals(listOf(record), store.manifest?.records)
            assertEquals(mapOf("pak03_dir.vpk" to Sha256.image(vpk)), files.addonsFiles(root))
        }
    }

    private fun TestScope.restore(library: ModLibrary, confirmation: RestoreConfirmation = assertNotNull(library.restoreConfirmation())): RestoreStage {
        library.restoreOriginal(confirmation)
        runCurrent()
        val maintenance = assertIs<Maintenance.Restore>(library.state.value.maintenance)
        assertEquals(confirmation, maintenance.confirmation)
        return maintenance.stage
    }

    @Test
    fun restoreRemovesOnlyHideoutsLinesAndEveryInstalledSetStaysAsItWas() = runTest {
        val world = World()
        val library = with(world) { library() }
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)
        assertEquals(RestoreConfirmation(root, enabledMods = 1), library.restoreConfirmation())

        assertEquals(RestoreStage.Restored, restore(library))
        assertContentEquals(edited, world.gameInfo(), "the player's note and search path stay byte for byte")
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = true), library.state.value.loading)
        world.assertSetsAsTheyWere()
        assertContentEquals(edited, world.gameInfo(other))
    }

    private fun withStaleBlock(gameInfo: ByteArray): ByteArray {
        val file = String(gameInfo)
        val at = file.lastIndexOf("\r\n", file.indexOf("\t\t\t// Hideout start").takeIf { it >= 0 } ?: file.indexOf("\t\t\tGame"))
        val block = listOf("// MODLOCK DEV BEGIN", "Game\t\t\tcitadel/modlock_dev/741294efb7c63a17", "Mod\t\t\t\"citadel\"", "Write\t\t\t\"citadel\"", "// MODLOCK DEV END").joinToString("") { "\r\n\t\t\t$it" }
        return (file.substring(0, at) + block + file.substring(at)).encodeToByteArray()
    }

    @Test
    fun playingKeepsTheBlockAModlockSessionLeftInEitherForm() = runTest {
        for ((start, modded, expected) in listOf(Triple(edited, false, edited), Triple(patched, false, edited), Triple(edited, true, patched), Triple(patched, true, patched))) {
            val world = World(gameInfo = withStaleBlock(start))
            val library = with(world) { library() }
            var dispatched = 0
            assertNull(library.launch(modded) { dispatched++ }, "modded=$modded")
            assertEquals(1, dispatched)
            assertContentEquals(withStaleBlock(expected), world.gameInfo(), "modded=$modded")
            world.assertSetsAsTheyWere()
        }
    }

    @Test
    fun restoringKeepsTheBlockAModlockSessionLeft() = runTest {
        for (start in listOf(edited, patched)) {
            val world = World(gameInfo = withStaleBlock(start))
            val library = with(world) { library() }
            assertEquals(RestoreStage.Restored, restore(library))
            assertContentEquals(withStaleBlock(edited), world.gameInfo())
        }
    }

    @Test
    fun aBlockModlockLeftStaysWhileAnotherToolRefusesPlayingVanilla() = runTest {
        val start = withStaleBlock(anotherTool)
        val world = World(gameInfo = start)
        val library = with(world) { library() }
        assertEquals(LibraryProblem.AnotherToolLoadsMods, library.launch(modded = false) {})
        assertContentEquals(start, world.gameInfo())
    }

    @Test
    fun restoringAgainChangesNothing() = runTest {
        val world = World()
        val library = with(world) { library() }
        assertEquals(RestoreStage.Restored, restore(library))
        val edits = world.store.saved.count { it.pending is PendingChange.ConfigEdit }
        assertEquals(RestoreStage.Restored, restore(library))
        assertContentEquals(edited, world.gameInfo())
        assertEquals(edits, world.store.saved.count { it.pending is PendingChange.ConfigEdit }, "nothing is edited the second time")
        world.assertSetsAsTheyWere()
    }

    @Test
    fun anotherToolsLoadingEntryIsRefusedAndLeftExactlyAsItIs() = runTest {
        val world = World(gameInfo = anotherTool)
        val library = with(world) { library() }
        assertEquals(ModLoading.Loads(LoadingOwner.AnotherTool), library.state.value.loading)
        assertEquals(RestoreStage.Refused(LibraryProblem.AnotherToolLoadsMods), restore(library))
        assertContentEquals(anotherTool, world.gameInfo())
        world.assertSetsAsTheyWere()
    }

    @Test
    fun aRunningOrUnknownGameRefusesTheRestoreWithoutTouchingGameinfo() = runTest {
        for ((scan, problem) in listOf(ProcessScan.Seen(game = true) to LibraryProblem.GameRunning, ProcessScan.Unknown("no /proc") to LibraryProblem.ProcessesUnknown("no /proc"))) {
            val world = World()
            val library = with(world) { library() }
            world.processes.scan = scan
            assertEquals(RestoreStage.Refused(problem), restore(library), "$scan")
            assertContentEquals(patched, world.gameInfo(), "$scan")
            world.assertSetsAsTheyWere()
        }
    }

    @Test
    fun gameinfoIsStillWatchedAfterARestore() = runTest {
        val world = World()
        val library = with(world) { library() }
        assertEquals(RestoreStage.Restored, restore(library))
        world.files.writeGameInfo(root, patched)
        advanceTimeBy(200.milliseconds)
        runCurrent()
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading, "another program put the lines back and Hideout noticed")
    }

    @Test
    fun aRestoreConfirmedWhileAnInterruptedInstallWaitsOffForDeadlockToCloseTurnsItOnAsAsked() = runTest {
        val world = World(pending = PendingChange.Install(later, InstallStep.Releasing, setOf(ProfileId("main"))))
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        assertEquals(listOf(record, later), world.store.manifest?.records, "the install is finished off while Deadlock runs")
        assertNull(world.store.manifest?.pending)
        val confirmed = assertNotNull(library.restoreConfirmation())
        assertEquals(RestoreConfirmation(root, enabledMods = 1), confirmed)

        world.processes.scan = ProcessScan.Seen()
        assertEquals(RestoreStage.Restored, restore(library, confirmed))
        assertEquals(listOf(record, later.placed(Placement.On(Slot(1)))), world.store.manifest?.records, "the installed mod is turned on as it was asked")
        assertContentEquals(patched, world.gameInfo(), "turning it on loads mods again")
    }

    @Test
    fun aRestoreWhileAWaitingInstallStillCannotBeFinishedIsRefusedAndChangesNothing() = runTest {
        val waiting = PendingChange.Install(later, InstallStep.Releasing, setOf(ProfileId("main")))
        val world = World(pending = waiting)
        world.files.failFile(root, later.files.single().relativePath(later), "permission denied")
        val library = with(world) { library() }
        val confirmed = assertNotNull(library.restoreConfirmation())

        assertEquals(RestoreStage.Refused(LibraryProblem.Interrupted("the files of an interrupted install could not be read")), restore(library, confirmed))
        assertContentEquals(patched, world.gameInfo())
        assertEquals(waiting, world.store.manifest?.pending)
        assertEquals(listOf(record), world.store.manifest?.records)
    }
}
