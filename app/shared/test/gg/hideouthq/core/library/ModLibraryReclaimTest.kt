package gg.hideouthq.core.library

import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.ports
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryReclaimTest {
    private val root = GameRoot("/games/Deadlock")

    private inner class World(gameInfo: ByteArray) {
        val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, gameInfo) }
        val store = InMemoryLibraryStore(LibraryManifest())
        val processes = FakeProcesses()
        private var next = 0

        fun TestScope.library(): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, files, files.ports(processes = processes), backgroundScope,
                ids = { "id${++next}" }, watch = testWatchTiming,
            ),
        )

        fun gameInfo() = assertNotNull(files.gameInfo(root))

        fun TestScope.rewrite(bytes: ByteArray) {
            files.writeGameInfo(root, bytes)
            settle()
        }
    }

    private fun TestScope.settle() {
        repeat(3) {
            advanceTimeBy(1.seconds)
            runCurrent()
        }
    }

    private fun TestScope.opened(gameInfo: ByteArray, importedFrom: Manager? = null) = World(gameInfo).let { world ->
        val library = with(world) { library() }
        importedFrom?.let { library.importedFrom(root, it) }
        settle()
        Triple(world, library, world.gameInfo())
    }

    @Test
    fun grimoiresFixNowIsPutBackAsTheImportLeftItAndSaidOnce() = runTest {
        val (world, library, imported) = opened(GrimoireImported, Manager.Grimoire)
        assertContentEquals(GrimoireImported, imported, "a file as the import left it is not written")
        assertNull(library.state.value.rewrite)

        with(world) { rewrite(grimoireFixed(imported)) }

        assertEquals(imported.decodeToString(), world.gameInfo().decodeToString(), "Grimoire's mounts go, Hideout's block is back where it was, and every other byte and line ending stays")
        assertEquals(GameInfoRewrite(RewrittenBy.AnotherProgram(Manager.Grimoire), RewriteOutcome.PutBack), library.state.value.rewrite)
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)
        library.dismissRewrite()
        settle()
        assertEquals(true, library.state.value.rewrite?.seen, "the same rewrite is told once")

        with(world) { rewrite(grimoireFixed(imported)) }
        assertContentEquals(imported, world.gameInfo())
        assertEquals(false, library.state.value.rewrite?.seen, "a second rewrite is told again")
    }

    @Test
    fun theCustomGamesLinesComeBackWithTheBlockWhileCustomGamesAreInUse() = runTest {
        val (world, library) = opened(GrimoireImported, Manager.Grimoire)
        assertNull(library.useCustomGames(true))
        val left = world.gameInfo()
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = true), GameInfoPatch.check(left))

        with(world) { rewrite(grimoireFixed(left)) }

        assertContentEquals(left, world.gameInfo())
        assertEquals(GamesLines.Mounted, library.gamesLines())
    }

    @Test
    fun dmmsMarkedSearchPathsArePutBackAsTheImportLeftThem() = runTest {
        val (world, library, imported) = opened(DmmImported, Manager.DeadlockModManager)
        assertContentEquals(DmmImported, imported)

        with(world) { rewrite(DmmMarked) }

        assertEquals(DmmImported.decodeToString(), world.gameInfo().decodeToString(), "DMM's markers and its addons line go, and Hideout's block is back")
        assertEquals(RewrittenBy.AnotherProgram(Manager.DeadlockModManager), library.state.value.rewrite?.by)
    }

    @Test
    fun aManagerNeverImportedFromIsLeftAloneAsBefore() = runTest {
        val (world, library) = opened(GrimoireImported)
        with(world) { rewrite(grimoireFixed(GrimoireImported)) }
        assertContentEquals(grimoireFixed(GrimoireImported), world.gameInfo(), "no import, so Grimoire's lines stay")
        assertNull(library.state.value.rewrite)
        assertEquals(ModLoading.Loads(LoadingOwner.AnotherTool), library.state.value.loading)
    }

    @Test
    fun anotherManagersLinesStayWhenOnlyGrimoireWasImportedFrom() = runTest {
        val (world, library, imported) = opened(GrimoireImported, Manager.Grimoire)
        val dmm = "\t\t\tGame\t\tcitadel/addons/profile_1726_ab\r\n\t\t\tGame\t\tcitadel/dmm_localization_default\r\n"
        fun withDmm(bytes: ByteArray) = bytes.decodeToString().replaceFirst(SearchPathsOpen, SearchPathsOpen + dmm).encodeToByteArray()

        with(world) { rewrite(grimoireFixed(withDmm(imported))) }

        assertEquals(withDmm(imported).decodeToString(), world.gameInfo().decodeToString(), "only Grimoire's mounts go")
        assertEquals(GameInfoRewrite(RewrittenBy.AnotherProgram(Manager.Grimoire), RewriteOutcome.PutBack), library.state.value.rewrite)
    }

    @Test
    fun whileDeadlockRunsTheRewriteWaitsForItToClose() = runTest {
        val (world, library, imported) = opened(GrimoireImported, Manager.Grimoire)
        world.processes.scan = ProcessScan.Seen(game = true)
        settle()
        assertEquals(GameActivity.Running, library.state.value.deadlock)

        with(world) { rewrite(grimoireFixed(imported)) }
        repeat(4) { settle() }
        assertContentEquals(grimoireFixed(imported), world.gameInfo(), "a running game holds the file")
        assertNull(library.state.value.rewrite)

        world.processes.scan = ProcessScan.Seen()
        settle()
        assertContentEquals(imported, world.gameInfo(), "put back as Deadlock closes")
        assertEquals(GameInfoRewrite(RewrittenBy.AnotherProgram(Manager.Grimoire), RewriteOutcome.PutBack), library.state.value.rewrite)
    }

    @Test
    fun playPutsTheLinesBackBeforeTheGameStarts() = runTest {
        val (world, library, imported) = opened(GrimoireImported, Manager.Grimoire)
        world.processes.scan = ProcessScan.Seen(game = true)
        settle()
        with(world) { rewrite(grimoireFixed(imported)) }
        world.processes.scan = ProcessScan.Seen()

        var started: ByteArray? = null
        assertNull(library.launch(modded = true) { started = world.gameInfo() })

        assertContentEquals(imported, started, "the game starts with the file as the import left it")
        assertEquals(GameInfoRewrite(RewrittenBy.AnotherProgram(Manager.Grimoire), RewriteOutcome.PutBack), library.state.value.rewrite)
    }
}

internal val GrimoireImported = resourceBytes("grimoire/gameinfo-imported.gi")

internal val DmmMarked = resourceBytes("dmm/gameinfo-marked.gi")

internal val DmmImported = resourceBytes("dmm/gameinfo-imported.gi")

private const val SearchPathsOpen = "\t\tSearchPaths\r\n\t\t{\r\n"

internal fun grimoireFixed(bytes: ByteArray): ByteArray = bytes.decodeToString()
    .replace(Regex("(// Hideout start\r\n)(?:\t+Game\t+\\S+\r\n)+"), "$1")
    .replaceFirst(SearchPathsOpen, SearchPathsOpen + "\t\t\tGame\t\tcitadel/grimoire\r\n\t\t\tGame\t\tcitadel/addons\r\n")
    .encodeToByteArray()
