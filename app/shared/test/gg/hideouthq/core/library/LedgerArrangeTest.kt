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

class LedgerArrangeTest {
    private val root = GameRoot("/games/Deadlock")

    private fun bytes(tag: String) = "$tag-${tag.length}".encodeToByteArray() + ByteArray(24 + tag.length) { (it * 7 + tag[0].code).toByte() }

    private fun mod(id: String, mod: Long, placement: Placement, parts: List<String> = listOf("dir")) = ManifestRecord(
        id, root, "Mod $id", placement,
        listOf(
            InstalledSet(
                InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, mod, mod * 10, null)), null, null, SetOrigin.Imported(FileImage(1, "a".repeat(64))), null,
                "pak01_dir.vpk", parts.map { SetFile(checkNotNull(VpkPart.parse(it)), Sha256.image(bytes("$id-$it"))) },
            ),
        ),
    )

    private val a = mod("a", 1, Placement.On(Slot(5)), listOf("dir", "000"))
    private val b = mod("b", 2, Placement.On(Slot(6)))
    private val c = mod("c", 3, Placement.Off)
    private val start = listOf(a, b, c)
    private val moves = listOf(Move(a, a.placed(Placement.On(Slot(104)))), Move(c, c.placed(Placement.On(Slot(105)))))
    private val arranged = listOf(moves[0].target, b, moves[1].target)
    private val after = ProfileBook.main(arranged)

    private inner class World(private val foreign: Map<String, ByteArray> = emptyMap(), gameInfo: ByteArray = HideoutGameInfo, receipts: List<GameConfigReceipt> = emptyList()) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, gameInfo)
            for (record in start) for (file in record.files) putBytes(root, file.relativePath(record), bytes("${record.installId}-${file.part.label}"))
            foreign.forEach { (path, content) -> putBytes(root, path, content) }
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = start, gameConfigs = receipts))
        private val identities = start.associate { record -> record.installId to record.files.map { files.identity(root, it.relativePath(record)) } }

        fun ledger() = LibraryLedger(store, files, files.ports(), ids = { "t" }, preserve = {})

        fun overflow() = assertIs<SearchPathsCheck.Read>(GameInfoPatch.check(checkNotNull(files.gameInfo(root)))).overflow

        fun holds(records: List<ManifestRecord>) {
            val manifest = checkNotNull(store.manifest)
            assertNull(manifest.pending)
            assertEquals(records, manifest.records)
            for (record in records) {
                assertEquals(identities.getValue(record.installId), record.files.map { files.identity(root, it.relativePath(record)) }, "${record.installId} keeps its very files")
                record.files.forEach { assertContentEquals(bytes("${record.installId}-${it.part.label}"), files.bytes(root, it.relativePath(record))) }
            }
            val expected = records.flatMap { record -> record.files.map { it.relativePath(record) } } + foreign.keys + InMemoryGameFiles.GameInfo
            assertEquals(expected.toSet(), files.everyFile(root).keys.filterNot { it.startsWith(InMemoryGameFiles.Stored) }.toSet(), "no anchor, copy or other name is left")
        }
    }

    @Test
    fun aSetMovesToTheSameNamesInTheNextFolderOnceTheBlockMountsIt() = runTest {
        val world = World()
        val reordered = listOf(moves[0].target, b, c)
        var mounted: Int? = null
        world.store.afterStore = { if (mounted == null && it.pending is PendingChange.Arrange) mounted = world.overflow() }
        assertEquals(ChangeOutcome.Done, world.ledger().also { it.reload() }.arrange(moves.take(1), ProfileBook.main(reordered)))
        assertEquals(1, mounted, "hideout_addons2 is mounted before the journal that moves files into it is saved")
        world.holds(reordered)
        assertEquals(listOf("game/citadel/hideout_addons2/pak05_dir.vpk", "game/citadel/hideout_addons2/pak05_000.vpk"), reordered[0].files.map { it.relativePath(reordered[0]) })
    }

    @Test
    fun whileTheUserPlaysWithoutModsAMoveIntoTheNextFolderLeavesGameinfoAlone() = runTest {
        val world = World(gameInfo = VanillaGameInfo, receipts = listOf(GameConfigReceipt(root, written = null, lines = HideoutLines(mods = false, customGames = false))))
        val reordered = listOf(moves[0].target, b, c)
        assertEquals(ChangeOutcome.Done, world.ledger().also { it.reload() }.arrange(moves.take(1), ProfileBook.main(reordered)))
        world.holds(reordered)
        assertContentEquals(VanillaGameInfo, world.files.gameInfo(root))
    }

    @Test
    fun anEntryAnotherProgramPutInTheNextFolderKeepsItsSlotAndNothingChanges() = runTest {
        val world = World(foreign = mapOf("game/citadel/hideout_addons2/pak05_readme.txt" to bytes("theirs")))
        val refused = assertIs<ChangeOutcome.Refused>(world.ledger().also { it.reload() }.arrange(moves, after))
        assertEquals(LibraryProblem.NumberTaken(Slot(104), listOf("hideout_addons2/pak05_readme.txt")), refused.problem)
        world.holds(start)
        assertEquals(0, world.overflow())
    }

    @Test
    fun aCrashAtEveryStepOfAMoveIntoTheNextFolderRecoversToOneWholeArrangement() = runTest {
        val dry = World()
        val points = mutableListOf<FaultPoint>()
        dry.files.around = { points += it }
        assertEquals(ChangeOutcome.Done, dry.ledger().also { it.reload() }.arrange(moves, after))
        assertTrue(FaultPoint.ReplaceConfig in points && points.count { it is FaultPoint.Link } >= 6 && points.count { it is FaultPoint.Remove } >= 3, "the trace covers the block, every link and every removal: $points")
        val cuts = points.indices.map { index ->
            "at ${points[index]}" to { world: World ->
                var seen = 0
                world.files.around = { if (seen++ == index) throw SimulatedCrash(it) }
            }
        } + dry.store.saved.indices.flatMap { index ->
            listOf(
                "before save $index" to { world: World -> var seen = 0; world.store.refuse = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig); null } },
                "after save $index" to { world: World -> var seen = 0; world.store.afterStore = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig) } },
            )
        }
        for ((name, cut) in cuts) {
            val world = World()
            cut(world)
            try {
                world.ledger().also { it.reload() }.arrange(moves, after)
            } catch (crash: SimulatedCrash) {
            }
            world.files.around = null
            world.store.refuse = { null }
            world.store.afterStore = {}
            try {
                val ledger = world.ledger().also { it.reload() }
                assertEquals(Recovery.Settled, ledger.recover())
                if (world.store.manifest?.records != arranged) {
                    world.holds(start)
                    assertEquals(ChangeOutcome.Done, ledger.arrange(moves, after), "arranging again finishes it")
                }
                world.holds(arranged)
                assertEquals(1, world.overflow(), "the block mounts hideout_addons2")
            } catch (failure: AssertionError) {
                throw AssertionError("after a crash $name: ${failure.message}", failure)
            }
        }
    }
}
