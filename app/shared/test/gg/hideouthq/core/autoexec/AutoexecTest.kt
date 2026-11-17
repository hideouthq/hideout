package gg.hideouthq.core.autoexec

import gg.hideouthq.core.data.HeldFile
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.ports
import gg.hideouthq.core.library.BackupId
import gg.hideouthq.core.library.BackupInfo
import gg.hideouthq.core.library.BackupReason
import gg.hideouthq.core.library.BackupStage
import gg.hideouthq.core.library.BackupWork
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.FixedGameLocator
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.HideoutGameInfo
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.Maintenance
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.crashableScope
import gg.hideouthq.core.library.testWatchTiming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class AutoexecTest {
    private val root = GameRoot("/games/Deadlock")
    private val header = AutoexecDocument.Header

    private inner class World(autoexec: String? = "fps_max 240\n", private val roots: List<GameRoot> = listOf(root)) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, HideoutGameInfo)
            autoexec?.let { putBytes(root, InMemoryGameFiles.Autoexec, it.encodeToByteArray()) }
        }
        val store = InMemoryLibraryStore(LibraryManifest())
        val processes = FakeProcesses()
        private var next = 0
        lateinit var library: ModLibrary
        lateinit var owner: Autoexec

        fun TestScope.start(scope: CoroutineScope = backgroundScope): Autoexec {
            library = ModLibrary(FixedGameLocator(roots), Preferences(MemoryStorage()), store, files, files.ports(processes = processes), scope, ids = { "id${++next}" }, watch = testWatchTiming)
            library.start()
            owner = Autoexec(library, files, scope).apply { start() }
            runCurrent()
            return owner
        }

        fun file(at: GameRoot = root): String? = files.bytes(at, InMemoryGameFiles.Autoexec)?.decodeToString()

        fun writes(): Int = store.saved.count { (it.pending as? PendingChange.ConfigEdit)?.target is ConfigTarget.Autoexec }

        fun backups(): List<BackupInfo> = runBlocking { files.backups.list(root) }

        fun shown(): String = assertIs<FileState.Read>(owner.state.value.file).document.render().decodeToString()
    }

    private fun TestScope.after(milliseconds: Long) {
        advanceTimeBy(milliseconds.milliseconds)
        runCurrent()
    }

    @Test
    fun anEditShowsAtOnceAndIsWrittenThroughTheJournalHalfASecondAfterTheLastOne() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.SetValue(CommandRef("fps_max", 0, "fps_max 240"), "300"))
        assertEquals("fps_max 300\n", world.shown(), "in the state before edit returns, with nothing else run")
        after(300)
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(450)
        assertEquals("fps_max 240\n", world.file(), "nothing is written while edits keep coming")
        after(100)
        assertEquals("$header\nfps_max 300\ncl_showfps 1\n", world.file())
        assertEquals(1, world.writes(), "one journaled write")
        assertEquals("$header\nfps_max 300\ncl_showfps 1\n", world.shown())
        assertNull(autoexec.state.value.refused)
    }

    @Test
    fun anEditMadeFromACoroutineRunningInPlaceIsInTheStateBeforeEditReturns() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        var shown: String? = null
        launch(Dispatchers.Unconfined) {
            autoexec.edit(AutoexecEdit.SetValue(CommandRef("fps_max", 0, "fps_max 240"), "300"))
            shown = world.shown()
        }
        assertEquals("fps_max 300\n", shown)
    }

    @Test
    fun beforeExitTheFileIsSavedWithoutWaitingForTheTimer() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.SetValue(CommandRef("fps_max", 0, "fps_max 240"), "300"))
        autoexec.saveBeforeExit().join()
        assertEquals("$header\nfps_max 300\n", world.file(), "the file holds the edit before the half second is up")
        after(600)
        assertEquals(1, world.writes(), "the timer finds nothing left to write")
    }

    @Test
    fun typingMadeBeforeTheFirstSaveAddedTheHeaderStillLands() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        fun typed(text: String) = assertNotNull(TextHunk.between(assertIs<FileState.Read>(autoexec.state.value.file).document.editorText, text))
        autoexec.edit(typed("fps_max 240\ncl_showfps 1"))
        val whileSaving = typed("fps_max 240\ncl_showfps 1\nvolume 0.5")
        after(600)
        assertEquals("$header\nfps_max 240\ncl_showfps 1\n", world.file())
        autoexec.edit(whileSaving)
        after(600)
        assertEquals("$header\nfps_max 240\ncl_showfps 1\nvolume 0.5\n", world.file())
    }

    @Test
    fun saveWritesThePendingEditsAtOnceAndAMissingFileAsTheHeaderAlone() = runTest {
        val world = World(autoexec = null)
        val autoexec = with(world) { start() }
        assertNull(autoexec.save())
        assertEquals("$header\n", world.file(), "an editor opened on it finds a file that is never empty")
        assertEquals(1, world.writes())

        assertNull(autoexec.save())
        assertEquals(1, world.writes(), "nothing to save writes nothing")

        autoexec.edit(AutoexecEdit.Add("fps_max", "240"))
        assertNull(autoexec.save())
        assertEquals("$header\nfps_max 240\n", world.file(), "saved now, not half a second later")
        after(600)
        assertEquals(2, world.writes(), "the timer finds nothing left to save")
    }

    @Test
    fun editsAreWrittenWhileDeadlockRunsAndSayTheyApplyNextTime() = runTest {
        val world = World()
        world.processes.scan = ProcessScan.Seen(game = true)
        val autoexec = with(world) { start() }
        after(5_000)
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        assertEquals("$header\nfps_max 240\ncl_showfps 1\n", world.file())
        assertEquals(SavedNote.NextStart, autoexec.state.value.note)
        assertEquals(LibraryProblem.GameRunning, world.library.reapply(), "gameinfo.gi still waits for the game")
    }

    @Test
    fun theFirstWriteOfASessionIsBackedUpOnceWithTheFileAndOnlyTheOldestOfEachReasonArePruned() = runTest {
        val world = World()
        world.files.backups.publish(root, BackupInfo(BackupId("restore"), createdAt = -1, BackupReason.BeforeRestore, mods = 0), LibraryManifest(), held = emptyMap())
        repeat(6) { index -> world.files.backups.publish(root, BackupInfo(BackupId("old$index"), createdAt = index.toLong(), BackupReason.BeforeUpdates, mods = 0), LibraryManifest(), held = emptyMap()) }
        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        autoexec.edit(AutoexecEdit.Add("volume", "0.5"))
        after(600)
        assertEquals(2, world.writes())
        val session = world.backups().filter { it.reason == BackupReason.BeforeGameSettings }
        assertEquals(1, session.size, "one backup a session")
        assertEquals("fps_max 240\n", world.files.backups.held(root, session.single().id)[HeldFile.Autoexec]?.decodeToString(), "it holds the file as it was")
        assertEquals((1..5).map { "old$it" }, world.backups().filter { it.reason == BackupReason.BeforeUpdates }.sortedBy { it.createdAt }.map { it.id.value }, "the oldest of six before updates goes")
        assertEquals(listOf("restore"), world.backups().filter { it.reason == BackupReason.BeforeRestore }.map { it.id.value }, "the newest five of each reason stay, so the one before a restore does too")
    }

    @Test
    fun aSaveThatWritesNothingTakesNoBackup() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        assertNull(autoexec.save())
        assertEquals(0, world.writes())
        assertEquals(emptyList(), world.backups(), "Open in editor on a file as it was takes no backup")
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        assertEquals(listOf(BackupReason.BeforeGameSettings), world.backups().map { it.reason }, "the first write that changes the file takes it")
    }

    @Test
    fun aLineAddedInAnotherEditorAndAnEditMadeHereBothStay() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        world.files.writeConfig(root, InMemoryGameFiles.Autoexec, "fps_max 240\nvolume 0.5\n".encodeToByteArray())
        after(200)
        assertEquals("fps_max 240\nvolume 0.5\ncl_showfps 1\n", world.shown(), "the file on disk, the pending edit on top")
        after(600)
        assertEquals("$header\nfps_max 240\nvolume 0.5\ncl_showfps 1\n", world.file())

        world.files.writeConfig(root, InMemoryGameFiles.Autoexec, "$header\nfps_max 120\n".encodeToByteArray())
        after(200)
        assertEquals(listOf(CommandRow(CommandRef("fps_max", 0, "fps_max 120"), "120", on = true, canTurnOff = true)), assertIs<FileState.Read>(autoexec.state.value.file).document.commands, "a change on disk shows once it settles")
    }

    @Test
    fun aWatchEventWhileASaveIsInFlightNeverShowsTheFileGone() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        world.files.duringConfigReplace = { at ->
            val was = assertNotNull(world.files.bytes(at, InMemoryGameFiles.Autoexec))
            world.files.deleteConfig(at, InMemoryGameFiles.Autoexec)
            delay(1_000)
            world.files.putBytes(at, InMemoryGameFiles.Autoexec, was)
        }
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        after(500)
        assertEquals("fps_max 240\ncl_showfps 1\n", world.shown(), "while the file is moved aside, the page shows it as it was with the edit on top")
        after(1_000)
        assertEquals("$header\nfps_max 240\ncl_showfps 1\n", world.file())
        assertEquals(world.file(), world.shown())
    }

    @Test
    fun aFileWrittenWhileTheWatchWasDownIsReadWhenTheWatchStartsAgain() = runTest {
        val world = World(autoexec = null)
        world.files.configWatchFails = true
        val autoexec = with(world) { start() }
        assertEquals("", world.shown())
        world.files.putBytes(root, InMemoryGameFiles.Autoexec, "fps_max 240\n".encodeToByteArray())
        world.files.configWatchFails = false
        after(testWatchTiming.period.inWholeMilliseconds + 100)
        assertEquals("fps_max 240\n", world.shown(), "the watch reads the file again as it starts again")
    }

    @Test
    fun aWriteThatStoppedMidwayIsSettledAndTheNextOneGoesOnWhileDeadlockRuns() = runTest {
        val world = World()
        world.files.crashAt = FaultPoint.ReplaceConfig
        val doomed = crashableScope()
        with(world) { start(doomed) }.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        doomed.cancel()
        assertNotNull(world.store.manifest?.pending as? PendingChange.ConfigEdit, "the crash left the edit's journal")
        world.files.crashAt = null
        world.processes.scan = ProcessScan.Seen(game = true)

        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.Add("volume", "0.5"))
        after(600)
        assertNull(world.store.manifest?.pending)
        assertEquals("$header\nfps_max 240\nvolume 0.5\n", world.file())
    }

    @Test
    fun aWriteThatLandedIsSavedThoughItsJournalCouldNotBeClearedAndIsNeverMadeAgain() = runTest {
        val world = World(autoexec = "volume 1\nfps_max 240\nfps_max 144\n")
        val autoexec = with(world) { start() }
        world.store.refuse = { if (it.pending == null) "the disk is full" else null }
        autoexec.edit(AutoexecEdit.Remove(assertIs<FileState.Read>(autoexec.state.value.file).document.commands.first { it.ref.name == "fps_max" }.ref))
        after(600)
        world.store.refuse = { null }
        assertNull(autoexec.state.value.refused, "the file was replaced, so the edit is saved")
        assertEquals("$header\nvolume 1\nfps_max 144\n", world.file())
        autoexec.retry()
        runCurrent()
        autoexec.edit(AutoexecEdit.Add("rate", "1"))
        after(600)
        assertEquals("$header\nvolume 1\nfps_max 144\nrate 1\n", world.file(), "the next write settles the journal left behind and removes nothing again")
        assertNull(world.store.manifest?.pending)
    }

    @Test
    fun anAutoexecOrCfgThatIsALinkShowsAsUnreadableSayingSoAndIsNeverWritten() = runTest {
        for (linked in listOf("game/citadel/cfg", InMemoryGameFiles.Autoexec)) {
            val world = World(autoexec = null)
            world.files.addLink(root, linked)
            val autoexec = with(world) { start() }
            val unreadable = assertIs<FileState.Unreadable>(autoexec.state.value.file, linked)
            assertEquals("$linked is a link. Hideout never reads or changes the game's files through one.", unreadable.reason)
            autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
            assertIs<LibraryProblem.FileUnreadable>(autoexec.save(), "Open in editor writes nothing")
            after(600)
            assertEquals(0, world.writes(), "no journal was saved")
        }
    }

    @Test
    fun editsWaitingOnARefusedSaveAreDroppedWhenTheGameFolderChanges() = runTest {
        val other = GameRoot("/games/Other")
        val world = World(roots = listOf(root, other))
        world.files.putBytes(other, InMemoryGameFiles.GameInfo, HideoutGameInfo)
        world.files.putBytes(other, InMemoryGameFiles.Autoexec, "volume 1\n".encodeToByteArray())
        val autoexec = with(world) { start() }
        world.files.failing += FaultPoint.ReplaceConfig
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        assertIs<SavedNote.Failed>(autoexec.state.value.note)
        world.files.failing -= FaultPoint.ReplaceConfig

        world.library.useGameFolder(other.path)
        after(600)
        assertNull(autoexec.state.value.refused, "a save refused for one game's file is not the other's")
        assertEquals("volume 1\n", world.shown(), "the edit made on the first game's file is not made on this one")
        autoexec.edit(AutoexecEdit.Add("rate", "1"))
        after(600)
        assertEquals("$header\nvolume 1\nrate 1\n", world.file(other))
        assertEquals("fps_max 240\n", world.file())
    }

    @Test
    fun aWriteThatStoppedMidwayIsSettledInTheBackgroundWhileDeadlockRuns() = runTest {
        val world = World()
        world.files.crashAt = FaultPoint.ReplaceConfig
        val doomed = crashableScope()
        with(world) { start(doomed) }.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        doomed.cancel()
        world.files.crashAt = null
        world.processes.scan = ProcessScan.Seen(game = true)

        with(world) { start() }
        after(5_000)
        assertNull(world.store.manifest?.pending, "settled with no write asked for")
        assertNull(assertIs<LibraryStatus.Ready>(world.library.state.value.library).interrupted)
    }

    @Test
    fun restoringABackupPutsTheFileBackAfterTheModsByteForByte() = runTest {
        val world = World(autoexec = "fps_max 240")
        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.Add("volume", "0.5"))
        after(600)
        val before = world.backups().single { it.reason == BackupReason.BeforeGameSettings }
        assertEquals("$header\nfps_max 240\nvolume 0.5", world.file())

        world.library.restoreBackup(before)
        after(1_000)
        assertEquals(Maintenance.Backups(BackupWork.Revert(before), BackupStage.Done), world.library.state.value.maintenance)
        assertEquals("fps_max 240", world.file(), "the file exactly as it was, with no header put first")
        assertEquals("fps_max 240", world.shown())
        val safety = world.backups().single { it.reason == BackupReason.BeforeRestore }
        assertEquals("$header\nfps_max 240\nvolume 0.5", world.files.backups.held(root, safety.id)[HeldFile.Autoexec]?.decodeToString(), "what the restore replaced was backed up first")
    }

    @Test
    fun restoringABackupTakenWithNoAutoexecWritesTheHeaderAlone() = runTest {
        val world = World(autoexec = null)
        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.Add("volume", "0.5"))
        after(600)
        val before = world.backups().single { it.reason == BackupReason.BeforeGameSettings }

        world.library.restoreBackup(before)
        after(1_000)
        assertEquals(Maintenance.Backups(BackupWork.Revert(before), BackupStage.Done), world.library.state.value.maintenance)
        assertEquals("$header\n", world.file(), "no commands run, as when there was no file, and the file is never empty")
    }

    @Test
    fun aBackupWhoseAutoexecCannotBeReadIsRefusedBeforeAnythingChanges() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        autoexec.edit(AutoexecEdit.Add("volume", "0.5"))
        after(600)
        val before = world.backups().single { it.reason == BackupReason.BeforeGameSettings }
        world.files.failing += FaultPoint.Look("game/citadel/${CitadelPath.HideoutBackups.value}/${before.id.value}/autoexec.cfg")

        world.library.restoreBackup(before)
        after(1_000)
        assertIs<LibraryProblem.BackupUnreadable>(assertIs<BackupStage.Refused>(assertIs<Maintenance.Backups>(world.library.state.value.maintenance).stage).problem)
        assertEquals(listOf(BackupReason.BeforeGameSettings), world.backups().map { it.reason }, "a restore that never started takes no backup")
        assertEquals("$header\nfps_max 240\nvolume 0.5\n", world.file())
    }

    @Test
    fun aRefusedSaveKeepsItsEditsShownAndRetrySavesThem() = runTest {
        val world = World()
        val autoexec = with(world) { start() }
        world.files.failing += FaultPoint.ReplaceConfig
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(600)
        assertIs<SavedNote.Failed>(autoexec.state.value.note)
        assertEquals("fps_max 240\ncl_showfps 1\n", world.shown())
        assertEquals("fps_max 240\n", world.file())
        world.files.failing -= FaultPoint.ReplaceConfig
        autoexec.retry()
        runCurrent()
        assertNull(autoexec.state.value.refused)
        assertEquals("$header\nfps_max 240\ncl_showfps 1\n", world.file())
    }

    @Test
    fun aChangeBeforeTheFileIsReadOrWithoutAGameIsIgnored() = runTest {
        val world = World()
        world.files.failFile(root, InMemoryGameFiles.Autoexec, "permission denied")
        val autoexec = with(world) { start() }
        assertEquals(FileState.Unreadable("permission denied"), autoexec.state.value.file)
        autoexec.edit(AutoexecEdit.Add("cl_showfps", "1"))
        after(1_000)
        assertEquals(0, world.writes())
    }
}
