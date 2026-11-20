package gg.hideouthq.core.library

import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.SimulatedCrash
import gg.hideouthq.core.data.fake.ports
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LedgerMoveOutTest {
    private val root = GameRoot("/games/Deadlock")
    private val citadel = "game/citadel"
    private val folder = CitadelPath.HideoutMoved.resolve("2026-11-20")

    private fun bytes(tag: String) = "$tag-${tag.length}".encodeToByteArray() + ByteArray(24 + tag.length) { (it * 7 + tag[0].code).toByte() }

    private val start = mapOf(
        "addons/pak05_dir.vpk" to bytes("dir"),
        "addons/pak05_000.vpk" to bytes("chunk-0"),
        "addons/pak05_001.vpk" to bytes("chunk-1"),
        "hideout_addons2/pak03_dir.vpk" to bytes("second"),
        "addons/pak01_dir.vpk" to bytes("hideout"),
    )
    private val leaving = start.keys - "addons/pak01_dir.vpk"

    private fun out(path: String) = OutFile(CitadelPath(path), Sha256.image(start.getValue(path)), path.endsWith("_dir.vpk"))

    private val change = PendingChange.MoveOut(
        root, folder,
        listOf(listOf("addons/pak05_dir.vpk", "addons/pak05_000.vpk", "addons/pak05_001.vpk").map(::out), listOf(out("hideout_addons2/pak03_dir.vpk"))),
        MoveOutStep.Linking,
    )

    private inner class World(fence: () -> String? = { null }) {
        val files = InMemoryGameFiles().apply { start.forEach { (path, content) -> putBytes(root, "$citadel/$path", content) } }
        val store = InMemoryLibraryStore(LibraryManifest())
        val identities: Map<String, Int?> = start.keys.associateWith { files.identity(root, "$citadel/$it") }
        val problems = mutableListOf<String>()
        val preserved = mutableListOf<Preserved>()
        private val gate = fence

        fun ledger() = LibraryLedger(store, files, files.ports(), ids = { "t" }, preserve = { preserved += it }, fence = { _, _ -> gate() })

        fun watch(point: FaultPoint) {
            if (files.identity(root, "$citadel/addons/pak05_dir.vpk") != null && listOf("000", "001").any { files.identity(root, "$citadel/addons/pak05_$it.vpk") == null }) {
                problems += "at $point the game could mount pak05_dir.vpk without a chunk"
            }
        }

        fun movedWhole() {
            assertEquals(emptyList(), problems)
            assertNull(store.manifest?.pending)
            for (path in leaving) {
                assertNull(files.identity(root, "$citadel/$path"), "$path left the loading folder")
                assertEquals(identities[path], files.identity(root, "$citadel/${folder.value}/$path"), "$path is the very file it was")
                assertContentEquals(start.getValue(path), files.bytes(root, "$citadel/${folder.value}/$path"))
            }
            assertEquals(identities["addons/pak01_dir.vpk"], files.identity(root, "$citadel/addons/pak01_dir.vpk"), "a file not asked for stays")
        }

        fun untouched() {
            for ((path, identity) in identities) assertEquals(identity, files.identity(root, "$citadel/$path"), "$path is where it was")
            assertTrue(files.everyFile(root).keys.none { it.startsWith("$citadel/${CitadelPath.HideoutMoved.value}") }, "no name is left under moved: ${files.everyFile(root).keys}")
        }
    }

    @Test
    fun aMoveOutKeepsEveryFileUnderItsOwnPathInTheMovedFolder() = runTest {
        val world = World()
        world.files.around = world::watch

        assertEquals(ChangeOutcome.Done, world.ledger().also { it.reload() }.moveOut(change))

        world.movedWhole()
        assertEquals(listOf(MoveOutStep.Linking, MoveOutStep.Releasing), world.store.saved.mapNotNull { (it.pending as? PendingChange.MoveOut)?.step })
        assertTrue(world.store.saved.all { it.records.isEmpty() }, "no record is made")
    }

    @Test
    fun aTakenNameRefusesWithNothingSavedOrTouched() = runTest {
        val world = World()
        world.files.putBytes(root, "$citadel/${folder.value}/addons/pak05_000.vpk", bytes("someone"))

        val refused = assertIs<ChangeOutcome.Refused>(world.ledger().also { it.reload() }.moveOut(change))

        assertEquals(LibraryProblem.NameTaken("$citadel/${folder.value}/addons/pak05_000.vpk"), refused.problem)
        assertEquals(emptyList(), world.store.saved)
        assertEquals(emptyList(), world.files.linkedPaths + world.files.removedPaths)
    }

    @Test
    fun theGameStartingMidMoveHoldsTheJournalUntilItCloses() = runTest {
        var running = false
        val world = World { "the game may be running".takeIf { running } }
        world.files.around = { point ->
            world.watch(point)
            if (point == FaultPoint.Remove("$citadel/addons/pak05_dir.vpk")) running = true
        }

        val waiting = assertIs<ChangeOutcome.Refused>(world.ledger().also { it.reload() }.moveOut(change))
        assertEquals("the game may be running", waiting.interrupted)
        assertIs<PendingChange.MoveOut>(world.store.manifest?.pending)
        assertEquals(Recovery.Waiting("the game may be running"), world.ledger().also { it.reload() }.recover())
        assertEquals(start.getValue("hideout_addons2/pak03_dir.vpk").toList(), world.files.bytes(root, "$citadel/hideout_addons2/pak03_dir.vpk")?.toList(), "nothing more moves while it may run")

        world.files.around = world::watch
        running = false
        assertEquals(Recovery.Settled, world.ledger().also { it.reload() }.recover())
        world.movedWhole()
    }

    @Test
    fun aCrashAtEveryStepRecoversToEveryFileInPlaceOrEveryFileMoved() = runTest {
        val dry = World()
        val points = mutableListOf<FaultPoint>()
        dry.files.around = { points += it }
        dry.ledger().also { it.reload() }.moveOut(change)
        dry.movedWhole()
        assertTrue(points.count { it is FaultPoint.Link } == 4 && points.count { it is FaultPoint.Remove } == 4, "the trace covers every link and remove: $points")
        val saves = dry.store.saved.size

        val cuts = points.indices.map { index ->
            "at ${points[index]}" to { world: World ->
                var seen = 0
                world.files.around = { point ->
                    world.watch(point)
                    if (seen++ == index) throw SimulatedCrash(point)
                }
            }
        } + (0 until saves).flatMap { index ->
            listOf(
                "before save $index" to { world: World -> var seen = 0; world.store.refuse = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig); null } },
                "after save $index" to { world: World -> var seen = 0; world.store.afterStore = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig) } },
            )
        }
        for ((name, cut) in cuts) {
            for (again in listOf(null, 0, 3)) {
                val world = World()
                cut(world)
                crashing { world.ledger().also { it.reload() }.moveOut(change) }
                world.store.refuse = { null }
                world.store.afterStore = {}
                if (again != null) {
                    var seen = 0
                    world.files.around = { point ->
                        world.watch(point)
                        if (seen++ == again) throw SimulatedCrash(point)
                    }
                    crashing { world.ledger().also { it.reload() }.recover() }
                }
                world.files.around = world::watch
                try {
                    val ledger = world.ledger().also { it.reload() }
                    assertEquals(Recovery.Settled, ledger.recover())
                    if (world.files.identity(root, "$citadel/addons/pak05_dir.vpk") != null) {
                        world.untouched()
                        assertEquals(ChangeOutcome.Done, ledger.moveOut(change), "moving out again finishes it")
                    }
                    world.movedWhole()
                } catch (failure: AssertionError) {
                    throw AssertionError("after a crash $name, then ${again?.let { "again at recovery step $it" } ?: "a clean restart"}: ${failure.message}", failure)
                }
            }
        }
    }

    private suspend fun crashing(block: suspend () -> Unit) {
        try {
            block()
        } catch (crash: SimulatedCrash) {
        }
    }
}
