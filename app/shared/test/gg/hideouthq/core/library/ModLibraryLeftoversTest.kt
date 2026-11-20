package gg.hideouthq.core.library

import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.ports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryLeftoversTest {
    private val root = GameRoot("/games/Deadlock")
    private val citadel = "game/citadel"
    private val now = Instant.parse("2026-11-20T12:00:00Z")
    private val day = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(now.toEpochMilliseconds()), ZoneId.systemDefault()).toString()

    private val own = FakeVpk.directory(seed = 1)
    private val start = mapOf(
        "addons/pak01_dir.vpk" to own,
        "addons/pak05_dir.vpk" to FakeVpk.directory(chunkReads = mapOf(0 to 48L), seed = 5),
        "addons/pak05_000.vpk" to ByteArray(48) { (it * 3).toByte() },
        "addons/631628_pak07_dir.vpk" to FakeVpk.directory(seed = 7),
        "hideout_addons2/pak03_dir.vpk" to FakeVpk.directory(seed = 3),
    )
    private val leftoverPaths = listOf("addons/pak05_dir.vpk", "addons/pak05_000.vpk", "hideout_addons2/pak03_dir.vpk")
    private val sets = listOf(
        Leftover(Slot(5), listOf(LeftoverFile(VpkPart.Directory, "pak05_dir.vpk", 0), LeftoverFile(VpkPart.Chunk(0), "pak05_000.vpk", 48))),
        Leftover(Slot.of(SetFolder.Loading[1], PakNumber(3)), listOf(LeftoverFile(VpkPart.Directory, "pak03_dir.vpk", 0))),
    )

    private inner class World(game: Boolean = false, extra: Map<String, ByteArray> = emptyMap()) : CrashWorld {
        override val files = InMemoryGameFiles().apply {
            putBytes(root, "$citadel/gameinfo.gi", HideoutGameInfo)
            (start + extra).forEach { (path, bytes) -> putBytes(root, "$citadel/$path", bytes) }
        }
        val reads = mutableListOf<String>()
        private val inventory = object : GameInventory by files {
            override suspend fun file(root: GameRoot, relativePath: String) = files.file(root, relativePath).also { reads += relativePath.removePrefix("$citadel/") }
        }
        override val store = InMemoryLibraryStore(LibraryManifest(records = listOf(heldMod("h", root, own))))
        val processes = FakeProcesses(ProcessScan.Seen(game = game))
        val log = mutableListOf<String>()
        val identities = start.keys.associateWith { files.identity(root, "$citadel/$it") }
        private var next = 0

        override fun TestScope.open(scope: CoroutineScope): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, inventory, files.ports(processes = processes, log = log::add), scope,
                ids = { "id${++next}" }, watch = testWatchTiming, clock = object : Clock { override fun now() = this@ModLibraryLeftoversTest.now },
            ),
        )

        fun TestScope.library(): ModLibrary = open(backgroundScope)

        fun untouched() = identities.forEach { (path, identity) -> assertEquals(identity, files.identity(root, "$citadel/$path"), "$path is where it was") }
    }

    private fun ModLibrary.leftovers(): List<Leftover> = assertIs<LibraryStatus.Ready>(state.value.library).leftovers

    private fun ModLibrary.records(): List<ManifestRecord> = assertIs<LibraryStatus.Ready>(state.value.library).records

    @Test
    fun theLibraryListsTheSetsThatLoadAndAreNotItsOwn() = runTest {
        val library = with(World()) { library() }

        assertEquals(listOf(listOf("addons/pak05_dir.vpk", "addons/pak05_000.vpk"), listOf("hideout_addons2/pak03_dir.vpk")), library.leftovers().map { set -> set.paths.map { it.value } })
    }

    @Test
    fun aSetWithTheBytesOfALibraryModIsItsCopyAndOnlyMovesOutWhileOneOfTheSameSizesStillComesIn() = runTest {
        val sameSize = FakeVpk.directory(seed = 2)
        assertEquals(own.size, sameSize.size)
        val world = World(extra = mapOf("addons/pak09_dir.vpk" to own, "addons/pak10_dir.vpk" to sameSize))
        val library = with(world) { library() }

        val found = library.leftovers().associateBy { it.name }
        assertEquals(mapOf("pak05_dir.vpk" to null, "pak09_dir.vpk" to "h", "pak10_dir.vpk" to null, "pak03_dir.vpk" to null), found.mapValues { it.value.copyOf })
        assertEquals(setOf("addons/pak09_dir.vpk", "addons/pak10_dir.vpk", "hideout_addons2/pak03_dir.vpk"), world.reads.filter { it in found.values.flatMap { set -> set.paths.map(CitadelPath::value) } }.toSet(), "a set of other sizes than every held set's is not read")

        assertEquals(LeftoverOutcome.Refused(LibraryProblem.SameFileInstalled("Hideout's h", "h")), library.addLeftovers(listOf(found.getValue("pak09_dir.vpk"))))
        assertEquals(listOf("h"), library.records().map { it.installId })
        assertEquals(LeftoverOutcome.Added, library.addLeftovers(listOf(found.getValue("pak10_dir.vpk"))))
        assertEquals(listOf("Hideout's h", "pak10_dir"), library.records().map { it.title })

        val readsBefore = world.reads.count { it == "addons/pak09_dir.vpk" }
        assertIs<LeftoverOutcome.Moved>(library.moveOut(listOf(found.getValue("pak05_dir.vpk"))))
        assertEquals(readsBefore, world.reads.count { it == "addons/pak09_dir.vpk" }, "a copy that stays is not read again")
        assertEquals("h", library.leftovers().single { it.name == "pak09_dir.vpk" }.copyOf)
        assertIs<LeftoverOutcome.Moved>(library.moveOut(listOf(found.getValue("pak09_dir.vpk"))))
        assertContentEquals(own, world.files.bytes(root, "$citadel/addons/pak01_dir.vpk"))
    }

    @Test
    fun ofTwoSetsWithTheSameBytesAddedTogetherTheFirstComesInAndTheOtherIsItsCopy() = runTest {
        val twin = FakeVpk.directory(seed = 11)
        val world = World(extra = mapOf("addons/pak11_dir.vpk" to twin, "hideout_addons2/pak04_dir.vpk" to twin))
        val library = with(world) { library() }

        assertEquals(LeftoverOutcome.Added, library.addLeftovers(library.leftovers().filter { it.name == "pak11_dir.vpk" || it.name == "pak04_dir.vpk" }))

        val added = library.records().single { it.title == "pak11_dir" }
        assertEquals(listOf("h", added.installId), library.records().map { it.installId })
        assertEquals(added.installId, library.leftovers().single { it.name == "pak04_dir.vpk" }.copyOf)
    }

    @Test
    fun movingOutKeepsEveryFileInADatedFolderAndTheLoadingFoldersClean() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.files.putBytes(root, "$citadel/${CitadelPath.HideoutMoved.value}/$day/addons/pak05_dir.vpk", byteArrayOf(1))

        val moved = assertIs<LeftoverOutcome.Moved>(library.moveOut(library.leftovers()))

        assertEquals(CitadelPath.HideoutMoved.resolve("$day (2)"), moved.folder, "a name taken that day sends the move to the next folder")
        for (path in leftoverPaths) {
            assertNull(world.files.identity(root, "$citadel/$path"), "$path left the game")
            assertEquals(world.identities[path], world.files.identity(root, "$citadel/${moved.folder.value}/$path"))
            assertContentEquals(start.getValue(path), world.files.bytes(root, "$citadel/${moved.folder.value}/$path"))
        }
        assertEquals(emptyList(), library.leftovers(), "the note has nothing left to say")
        assertContentEquals(own, world.files.bytes(root, "$citadel/addons/pak01_dir.vpk"))
        assertContentEquals(start.getValue("addons/631628_pak07_dir.vpk"), world.files.bytes(root, "$citadel/addons/631628_pak07_dir.vpk"), "a name the game does not mount stays")
    }

    @Test
    fun whileDeadlockRunsNothingMovesOrJoinsTheLibrary() = runTest {
        val world = World(game = true)
        val library = with(world) { library() }
        val sets = library.leftovers()

        assertEquals(LeftoverOutcome.Refused(LibraryProblem.GameRunning), library.moveOut(sets))
        assertEquals(LeftoverOutcome.Refused(LibraryProblem.GameRunning), library.addLeftovers(sets))

        world.untouched()
        assertEquals(sets, library.leftovers())
        assertEquals(listOf("h"), library.records().map { it.installId })
    }

    @Test
    fun addingClaimsASetInAddonsWhereItLoadsAndBringsOneFromPastAddonsInThroughItsOffFolder() = runTest {
        val world = World()
        val library = with(world) { library() }

        assertEquals(LeftoverOutcome.Added, library.addLeftovers(library.leftovers()))
        assertTrue(library.records().filter { it.installId != "h" }.all { it.unasked }, "GameBanana is asked later who they are")
        runCurrent()

        val added = library.records().filter { it.installId != "h" }
        assertEquals(listOf("pak05_dir", "pak03_dir"), added.map { it.title })
        assertTrue(added.all { it.sets.single().source is InstalledSource.Local })
        val claimed = added.first()
        assertEquals(listOf(Slot(5)), claimed.numbers, "on where it loads")
        assertEquals(listOf("game/citadel/addons/pak05_dir.vpk", "game/citadel/addons/pak05_000.vpk"), claimed.files.map { it.relativePath(claimed) })
        for (path in listOf("addons/pak05_dir.vpk", "addons/pak05_000.vpk")) assertEquals(world.identities[path], world.files.identity(root, "$citadel/$path"), "$path was not touched")
        val second = added.last()
        assertTrue(second.enabled, "the saved choices turn on what loaded")
        assertContentEquals(start.getValue("hideout_addons2/pak03_dir.vpk"), world.files.bytes(root, "$citadel/${second.files.single().relativePath(second).removePrefix("$citadel/")}"))
        assertEquals(emptyList(), library.leftovers())
    }

    @Test
    fun theLogSaysWhichSetsWereAddedAndFromWhereWithNoImportBehindThem() = runTest {
        val world = World()
        val library = with(world) { library() }

        library.addLeftovers(library.leftovers())
        runCurrent()

        val added = world.log.filter { it.startsWith("Added ") }
        assertEquals(2, added.size, "${world.log}")
        assertTrue(added[0].startsWith("Added pak05_dir.vpk from addons to the library as pak05_dir (local-"), added[0])
        assertTrue(added[1].startsWith("Added pak03_dir.vpk from hideout_addons2 to the library as pak03_dir (local-"), added[1])
        assertTrue(world.log.none { "import" in it }, "${world.log}")
    }

    @Test
    fun aSetMissingAChunkItsDirectoryReadsIsNotAddedAndStaysWhereItIs() = runTest {
        val world = World()
        world.files.remove(root, "$citadel/addons/pak05_000.vpk")
        val library = with(world) { library() }

        val refused = assertIs<LeftoverOutcome.Refused>(library.addLeftovers(library.leftovers().take(1)))

        assertIs<LibraryProblem.NoCompleteSet>(refused.problem)
        assertEquals(listOf("h"), library.records().map { it.installId })
        assertContentEquals(start.getValue("addons/pak05_dir.vpk"), world.files.bytes(root, "$citadel/addons/pak05_dir.vpk"))
    }

    @Test
    fun aCrashAnywhereInAMoveOutRestartsWithEverySetInPlaceOrMovedWhole() = runTest {
        val act: CoroutineScope.(ModLibrary) -> Unit = { library -> launch { library.moveOut(sets) } }
        val trace = traced(World(), act)
        assertTrue(trace.points.count { it is FaultPoint.Link && CitadelPath.HideoutMoved.value in it.path } == 3 && trace.points.count { it is FaultPoint.Remove } == 3, "${trace.points}")
        crashAtEveryStep(trace, ::World, act) { world, library ->
            val folder = "$citadel/${CitadelPath.HideoutMoved.value}/$day"
            val moved = world.files.identity(root, "$citadel/addons/pak05_dir.vpk") == null
            for (path in leftoverPaths) {
                val at = if (moved) "$folder/$path" else "$citadel/$path"
                assertEquals(world.identities[path], world.files.identity(root, at), "$path is at $at")
                assertNull(world.files.identity(root, if (moved) "$citadel/$path" else "$folder/$path"), "$path has one name")
            }
            assertEquals(if (moved) 0 else 2, library.leftovers().size)
        }
    }
}
