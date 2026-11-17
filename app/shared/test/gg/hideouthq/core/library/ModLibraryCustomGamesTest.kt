package gg.hideouthq.core.library

import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryCustomGamesTest {
    private val root = GameRoot("/games/Deadlock")
    private val withGames = (GameInfoPatch.withBlock(VanillaGameInfo, HideoutLines(mods = true, customGames = true)) as GameInfoEdit.Patched).bytes
    private val gamesOnly = (GameInfoPatch.withBlock(VanillaGameInfo, HideoutLines(mods = false, customGames = true)) as GameInfoEdit.Patched).bytes

    private inner class World(gameInfo: ByteArray = HideoutGameInfo) {
        val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, gameInfo) }
        val store = InMemoryLibraryStore(LibraryManifest())
        val processes = FakeProcesses()
        private var nextId = 0

        fun TestScope.library(): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, files,
                InstallPorts(FakeGameBanana(), FakeDownloader(files), files, files, processes), backgroundScope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
            ),
        )

        fun gameInfo() = assertNotNull(files.gameInfo(root))
    }

    private fun TestScope.opened(gameInfo: ByteArray = HideoutGameInfo) = World(gameInfo).let { it to with(it) { library() } }

    @Test
    fun theLinesGoInAtOnceWhileDeadlockIsClosedAndOutAgainWhenTurnedOff() = runTest {
        val (world, library) = opened()
        assertEquals(GamesLines.Off, library.gamesLines())

        assertNull(library.useCustomGames(true))
        assertContentEquals(withGames, world.gameInfo())
        assertEquals(GamesLines.Mounted, library.gamesLines())
        assertNull(library.useCustomGames(true), "asked again, nothing changes")

        assertNull(library.useCustomGames(false))
        assertContentEquals(HideoutGameInfo, world.gameInfo(), "mods keep loading")
        assertEquals(GamesLines.Off, library.gamesLines())
    }

    @Test
    fun whileDeadlockRunsTheUseIsRecordedAndTheLinesWaitForTheNextClose() = runTest {
        val (world, library) = opened()
        world.processes.scan = ProcessScan.Seen(game = true)
        assertEquals(LibraryProblem.GameRunning, library.useCustomGames(true))
        assertContentEquals(HideoutGameInfo, world.gameInfo(), "a running game holds the file")
        assertEquals(GamesLines.Pending, library.gamesLines())

        world.processes.scan = ProcessScan.Seen()
        assertNull(library.useCustomGames(true))
        assertEquals(GamesLines.Mounted, library.gamesLines())

        world.processes.scan = ProcessScan.Seen(game = true)
        assertEquals(LibraryProblem.GameRunning, library.useCustomGames(false))
        assertContentEquals(withGames, world.gameInfo())
        assertEquals(GamesLines.Leaving, library.gamesLines(), "they go at the next close")
        world.processes.scan = ProcessScan.Seen()
        assertNull(library.useCustomGames(false))
        assertEquals(GamesLines.Off, library.gamesLines())
    }

    @Test
    fun aDeadlockUpdateThatTookTheLinesOutReadsAsRemoved() = runTest {
        val (world, library) = opened()
        assertNull(library.useCustomGames(true))
        world.files.putBytes(root, InMemoryGameFiles.GameInfo, VanillaGameInfo)
        assertEquals(GamesLines.Removed, library.gamesLines())
        assertNull(library.useCustomGames(true))
        assertContentEquals(withGames, world.gameInfo())
    }

    @Test
    fun playKeepsTheLinesAndVanillaLeavesTheFileUntouchedUntilCustomGamesAreUsedAgain() = runTest {
        val (world, library) = opened()
        assertNull(library.useCustomGames(true))
        assertNull(library.launch(modded = true) {})
        assertContentEquals(withGames, world.gameInfo())

        assertNull(library.launch(modded = false) {})
        assertContentEquals(VanillaGameInfo, world.gameInfo())
        assertEquals(GamesLines.Off, library.gamesLines())
        assertNull(library.launch(modded = true) {})
        assertContentEquals(HideoutGameInfo, world.gameInfo(), "Play brings mods back and leaves custom games off")

        assertNull(library.launch(modded = false) {})
        assertNull(library.useCustomGames(true))
        assertContentEquals(gamesOnly, world.gameInfo(), "custom games come back without the mods Vanilla turned off")
    }

    @Test
    fun restoringTheOriginalTakesTheLinesOutToo() = runTest {
        val (world, library) = opened()
        assertNull(library.useCustomGames(true))
        library.restoreOriginal(assertNotNull(library.restoreConfirmation()))
        runCurrent()
        assertEquals(RestoreStage.Restored, assertIs<Maintenance.Restore>(library.state.value.maintenance).stage)
        assertContentEquals(VanillaGameInfo, world.gameInfo())
        assertEquals(GamesLines.Off, library.gamesLines())
    }
}
