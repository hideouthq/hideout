package gg.hideouthq.core.library

import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.FileRelease
import gg.hideouthq.core.customgames.JournalEntry
import gg.hideouthq.core.customgames.LibrarySessionFiles
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.SessionEvent
import gg.hideouthq.core.customgames.ProcessIdentity
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SessionJournal
import gg.hideouthq.core.customgames.SessionOwned
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibrarySessionTest {
    private val root = GameRoot("/games/Deadlock")
    private val other = GameRoot("/other/Deadlock")
    private val addons = SetFolder.Addons.path
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
    private val onPath = "$addons/pak03_dir.vpk"
    private val client = ProcessScan.Seen(game = true)
    private val closed = ProcessScan.Seen()
    private val modlockBlock = listOf("// MODLOCK DEV BEGIN", "Game\t\t\tcitadel/modlock_dev/741294efb7c63a17", "Mod\t\t\t\"citadel\"", "Write\t\t\t\"citadel\"", "// MODLOCK DEV END")

    private fun mounted(gameInfo: ByteArray): ByteArray {
        val file = gameInfo.decodeToString()
        val at = file.lastIndexOf('\n', file.indexOf("// Hideout start").takeIf { it >= 0 } ?: file.indexOf("\t\t\tGame"))
        return (file.substring(0, at) + modlockBlock.joinToString("") { "\n\t\t\t$it" } + file.substring(at)).encodeToByteArray()
    }

    private inner class World(gameInfo: ByteArray = HideoutGameInfo) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, gameInfo)
            putBytes(other, InMemoryGameFiles.GameInfo, VanillaGameInfo)
            putBytes(root, onPath, vpk)
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(record), profiles = ProfileBook.main(listOf(record))))
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
        fun onFiles() = files.addonsFiles(root).keys
        fun configEdits() = store.saved.count { it.pending is PendingChange.ConfigEdit }
    }

    private fun TestScope.opened(gameInfo: ByteArray = HideoutGameInfo) = World(gameInfo).let { it to with(it) { library() } }

    private suspend fun ModLibrary.held() = assertIs<SessionAdmission.Held>(admitSession()).root

    private fun TestScope.seen(world: World, scan: ProcessScan) {
        world.processes.scan = scan
        advanceTimeBy(testWatchTiming.processPoll * 2)
        runCurrent()
    }

    @Test
    fun whatACrashLeftIsHeldAgainAndReleasedWithTheClientStillRunning() = runTest {
        val (world, library) = opened()
        runCurrent()
        assertNull(library.state.value.sessionHold, "the hold died with the crash")

        library.resumeSession(root)
        assertEquals(root, library.state.value.sessionHold?.root)

        seen(world, client)
        assertNull(library.releaseSession())
        assertNull(library.state.value.sessionHold)
        assertNull(library.releaseSession(), "asked again, nothing changes")
        assertContentEquals(HideoutGameInfo, world.gameInfo())
    }

    @Test
    fun aBlockAModlockSessionLeftStaysThroughPlayAdmissionAndRelease() = runTest {
        for (modded in listOf(true, false)) {
            val (world, library) = opened(mounted(HideoutGameInfo))
            assertNull(library.launch(modded) {})
            assertContentEquals(mounted(if (modded) HideoutGameInfo else VanillaGameInfo), world.gameInfo(), "modded=$modded")
            library.held()
            assertNull(library.releaseSession())
            assertContentEquals(mounted(if (modded) HideoutGameInfo else VanillaGameInfo), world.gameInfo(), "modded=$modded")
        }
    }
    private val crashedOwn = JournalEntry(root, ProcessIdentity(77, 5), SessionOwned(appIdFile = true, compileCache = true))

    private suspend fun platformAfterCrash(left: JournalEntry) = FakeCustomGamesPlatform(SessionJournal(MemoryStorage())).also { it.journal.write(left) }

    private fun TestScope.customGames(library: ModLibrary, platform: FakeCustomGamesPlatform) =
        CustomGames(platform, LibrarySessionFiles(library, FakeGameLauncher()), null, backgroundScope, hostFolders = flowOf(false)).also {
            it.start()
            runCurrent()
        }

    @Test
    fun aCrashedSessionsLeftoversGoAndItsJournalClears() = runTest {
        val (world, library) = opened()
        runCurrent()
        val platform = platformAfterCrash(crashedOwn)

        val games = customGames(library, platform)

        assertEquals(listOf(crashedOwn), platform.leftoversEnded)
        assertEquals(listOf(root to assertNotNull(crashedOwn.owned)), platform.removed)
        assertNull(platform.journal.read())
        assertEquals(Session.Ended(EndReason.Interrupted), games.live.value.session)
        assertContentEquals(HideoutGameInfo, world.gameInfo())
    }
    @Test
    fun aGameFolderThatIsGoneHasNothingLeftToRelease() = runTest {
        val (world, library) = opened()
        runCurrent()
        library.resumeSession(GameRoot("/gone/Deadlock"))
        assertNull(library.state.value.sessionHold)
        assertNull(library.releaseSession())
        assertContentEquals(HideoutGameInfo, world.gameInfo())
    }

    @Test
    fun aHoldTurnsPlayAndChangesOffWithoutSayingDeadlockRuns() = runTest {
        val (world, library) = opened()
        assertTrue(library.state.value.canPlay)
        assertEquals(root, library.held())

        val state = library.state.value
        assertEquals(root, state.sessionHold?.root)
        assertEquals(GameActivity.Closed, state.deadlock, "the server is not the client")
        assertFalse(state.canPlay)
        assertFalse(state.canModify)
        assertTrue(state.canEditChoices, "choices save and wait, as they do while the game runs")
        assertTrue(state.canInstall, "downloads go on into off folders")
        assertEquals(LibraryProblem.CustomGameRunning, library.launch(modded = true) {})
        assertEquals(LibraryProblem.CustomGameRunning, library.reapply())

        assertNull(library.releaseSession())
        assertTrue(library.state.value.canPlay)
        assertNull(library.state.value.sessionHold)
    }

    @Test
    fun aSessionIsAdmittedBesideARunningGameButASecondIsRefusedAndAdmissionEditsNothing() = runTest {
        val (world, library) = opened()
        world.processes.scan = client
        library.held()
        assertContentEquals(HideoutGameInfo, world.gameInfo())
        val edits = world.configEdits()
        assertEquals(SessionAdmission.Refused(LibraryProblem.CustomGameRunning), library.admitSession())
        assertContentEquals(HideoutGameInfo, world.gameInfo())
        assertEquals(edits, world.configEdits())
        assertNull(library.releaseSession())
    }

    @Test
    fun aSessionLeavesAGameThatLoadsNoModsAsItFoundIt() = runTest {
        val (world, library) = opened(gameInfo = VanillaGameInfo)
        val edits = world.configEdits()
        library.held()
        assertContentEquals(VanillaGameInfo, world.gameInfo())
        assertNull(library.releaseSession())
        assertContentEquals(VanillaGameInfo, world.gameInfo())
        assertEquals(edits, world.configEdits())
    }

    @Test
    fun aSessionAskedForWhileAChoiceAppliesWaitsForItsStepThenHolds() = runTest {
        val (world, library) = opened()
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        library.setEnabled("ivy", false)
        runCurrent()
        val admitted = async { library.admitSession() }
        runCurrent()
        assertFalse(admitted.isCompleted)
        world.processes.gate = null
        gate.complete(Unit)
        runCurrent()
        assertEquals(SessionAdmission.Held(root), admitted.await())
        assertNull(library.releaseSession())
    }

    @Test
    fun aChoiceSavedDuringTheHoldWaitsForItsReleaseThenApplies() = runTest {
        val (world, library) = opened()
        library.held()

        library.setEnabled("ivy", false)
        advanceTimeBy(testWatchTiming.processPoll * 3)
        runCurrent()
        assertEquals(ChoiceSync.Waiting(GameActivity.Closed), library.state.value.choiceSync, "waiting, not applying")
        assertEquals(setOf("pak03_dir.vpk"), world.onFiles(), "nothing the game loads moves")
        assertEquals(GameActivity.Closed, library.state.value.deadlock)
        assertEquals(false, library.state.value.applying)

        assertNull(library.releaseSession())
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(emptySet(), world.onFiles(), "the change goes on by itself")
    }

    @Test
    fun theClientStartsUnderTheHoldFromTheHeldGameWithNoFileEdited() = runTest {
        val (world, library) = opened()
        library.held()
        library.useGameFolder(other.path)
        assertEquals(other, (library.state.value.game as GameStatus.Ready).root)

        val before = world.gameInfo()
        val edits = world.configEdits()
        val started = mutableListOf<GameRoot>()
        assertNull(library.launchSessionClient { started += it })
        assertEquals(listOf(root), started, "the game the hold was admitted on, not the folder chosen since")
        assertContentEquals(before, world.gameInfo())
        assertEquals(edits, world.configEdits())
    }

    @Test
    fun theClientStartsWithChoicesWaiting() = runTest {
        val (world, library) = opened()
        library.held()
        library.setEnabled("ivy", false)
        runCurrent()
        var started = 0
        assertNull(library.launchSessionClient { started++ }, "a choice deferred during the hold does not stop it")
        assertEquals(1, started)

        seen(world, client)
        assertEquals(GameActivity.Running, library.state.value.deadlock, "the client shows as the client")
    }

    @Test
    fun aClientLaunchIsRefusedWithoutAHoldAndWhileAnotherIsUnderWayAndHoldsOffTheRelease() = runTest {
        val (world, library) = opened()
        assertIs<LibraryProblem.Unexpected>(library.launchSessionClient { error("no hold") })

        library.held()
        val steam = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { library.launchSessionClient { steam.await() } }
        runCurrent()
        assertEquals(SessionStep.LaunchingClient, library.state.value.sessionHold?.step)
        assertEquals(LibraryProblem.Busy, library.launchSessionClient { error("a second client") })
        assertEquals(LibraryProblem.Busy, library.releaseSession())
        assertNotNull(library.state.value.sessionHold)
        steam.complete(Unit)
        first.join()
        assertEquals(SessionStep.Steady, library.state.value.sessionHold?.step)
    }

    @Test
    fun aHandoffThatFailsIsAnsweredAndTheHoldStands() = runTest {
        val (world, library) = opened()
        library.held()
        assertEquals(LibraryProblem.Unexpected("steam is gone"), library.launchSessionClient { throw java.io.IOException("steam is gone") })
        assertEquals(SessionHold(root), library.state.value.sessionHold)
    }

    @Test
    fun theReleaseDoesNotWaitForTheClientAndAChoiceSavedDuringItWaitsForTheClientInstead() = runTest {
        val (world, library) = opened()
        library.held()
        library.setEnabled("ivy", false)

        seen(world, client)
        assertNull(library.releaseSession())
        assertNull(library.state.value.sessionHold)
        assertNull(library.releaseSession(), "a second release has nothing to do")
        assertEquals(setOf("pak03_dir.vpk"), world.onFiles(), "nothing the running game loads moves")
        assertEquals(LibraryProblem.GameRunning, library.reapply())

        seen(world, closed)
        assertEquals(emptySet(), world.onFiles(), "the change goes on once the game closes")
        assertContentEquals(HideoutGameInfo, world.gameInfo())
    }
    @Test
    fun aRestartThatHasBegunKeepsASessionOutAndOneBeginningDuringAdmissionUndoesIt() = runTest {
        val (world, library) = opened()
        var begun = true
        library.refuseSessionsWhen { begun }
        assertEquals(SessionAdmission.Refused(LibraryProblem.Busy), library.admitSession())
        assertNull(library.state.value.sessionHold)

        begun = false
        var reads = 0
        library.refuseSessionsWhen { reads++ > 0 }
        assertEquals(SessionAdmission.Refused(LibraryProblem.Busy), library.admitSession(), "it began after the first look")
        assertNull(library.state.value.sessionHold)
        assertTrue(library.state.value.canPlay)
    }

    @Test
    fun aHoldBusiesRestartUntilItsRelease() = runTest {
        val (world, library) = opened()
        assertFalse(library.state.value.modsBusy)
        library.held()
        assertTrue(library.state.value.modsBusy)
        assertNull(library.releaseSession())
        assertFalse(library.state.value.modsBusy)
    }
}
