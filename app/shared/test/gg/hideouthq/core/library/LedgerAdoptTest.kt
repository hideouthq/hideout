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

class LedgerAdoptTest {
    private val root = GameRoot("/games/Deadlock")
    private val migration = MigrationId("m-1")
    private val citadel = "game/citadel"

    private fun bytes(tag: String) = "$tag-${tag.length}".encodeToByteArray() + ByteArray(24 + tag.length) { (it * 7 + tag[0].code).toByte() }

    private val start = mapOf(
        "addons/pak02_dir.vpk" to bytes("hideout"),
        "addons/pak01_dir.vpk" to bytes("claimed"),
        "addons/profile_x/pak01_dir.vpk" to bytes("profile-dir"),
        "addons/profile_x/pak01_000.vpk" to bytes("profile-chunk"),
        "addons/637275_Percentage Rename.vpk" to bytes("parked"),
        "grimoire/pak05_dir.vpk" to bytes("global-5"),
        "grimoire/pak06_dir.vpk" to bytes("global-6"),
        "addons/my_skin.vpk" to bytes("unclaimed"),
        "addons/.dmm-clear/pak03_dir.vpk" to bytes("staged"),
        "addons-backups/snapshot/pak09_dir.vpk" to bytes("backup"),
    )

    private fun file(part: VpkPart, from: String) = SetFile(part, Sha256.image(start.getValue(from)))

    private val held = ManifestRecord(
        "h", root, "Hideout's own", placedAt(2),
        listOf(InstalledSet(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 1, 10, null)), null, null, SetOrigin.Imported(FileImage(1, "a".repeat(64))), null, "pak02_dir.vpk", listOf(file(VpkPart.Directory, "addons/pak02_dir.vpk")))),
    )

    private fun adopted(installId: String, source: InstalledSource, placement: Placement, sets: List<Pair<String, List<SetFile>>>) = ManifestRecord(
        installId, root, "Adopted $installId", placement,
        sets.map { (key, files) -> InstalledSet(source, null, null, SetOrigin.Adopted(migration), null, key, files) },
    )

    private val claimed = Adoption(
        adopted("c", InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 696_085, 1_500_000, null)), placedAt(1), listOf("pak51_dir.vpk" to listOf(file(VpkPart.Directory, "addons/pak01_dir.vpk")))),
        listOf(CitadelPath("addons/pak01_dir.vpk")),
    )
    private val profile = Adoption(
        adopted("m", InstalledSource.Local("m1", "pak01_dir.vpk"), Placement.Off, listOf("pak01_dir.vpk" to listOf(file(VpkPart.Directory, "addons/profile_x/pak01_dir.vpk"), file(VpkPart.Chunk(0), "addons/profile_x/pak01_000.vpk")))),
        listOf(CitadelPath("addons/profile_x/pak01_dir.vpk"), CitadelPath("addons/profile_x/pak01_000.vpk")),
    )
    private val parked = Adoption(
        adopted("p", InstalledSource.Local("p1", "Percentage Rename.vpk"), Placement.Off, listOf("Percentage Rename.vpk" to listOf(file(VpkPart.Directory, "addons/637275_Percentage Rename.vpk")))),
        listOf(CitadelPath("addons/637275_Percentage Rename.vpk")),
    )
    private val global = Adoption(
        adopted("g", InstalledSource.Local("g1", "pak05_dir.vpk"), Placement.Off, listOf("pak05_dir.vpk" to listOf(file(VpkPart.Directory, "grimoire/pak05_dir.vpk")), "pak06_dir.vpk" to listOf(file(VpkPart.Directory, "grimoire/pak06_dir.vpk")))),
        listOf(CitadelPath("grimoire/pak05_dir.vpk"), CitadelPath("grimoire/pak06_dir.vpk")),
    )
    private val adoptions = listOf(claimed, profile, parked, global)
    private val book = ProfileBook.main(listOf(held) + adoptions.map { it.record })
    private val plan = AdoptionPlan(root, migration, adoptions, book, book.withOrder(root, listOf("c", "h", "m", "p", "g")))

    private val ends = adoptions.flatMap { adoption -> adoption.sources.zip(adoption.record.files.map { it.citadelPath(adoption.record) }) }.associate { (from, to) -> from.value to to.value }

    private inner class World {
        val files = InMemoryGameFiles().apply {
            start.forEach { (path, content) -> putBytes(root, "$citadel/$path", content) }
            addLink(root, "$citadel/grimoire/replays")
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(held)))
        val identities: Map<String, Int?> = start.keys.associateWith { files.identity(root, "$citadel/$it") }
        val images: List<FileImage> = vpkImages()
        val problems = mutableListOf<String>()
        val preserved = mutableListOf<Preserved>()
        private var removing: Int? = null

        fun ledger() = LibraryLedger(store, files, files.ports(), ids = { "t" }, preserve = { preserved += it })

        fun vpkImages(): List<FileImage> = files.everyFile(root).filterKeys { it.endsWith(".vpk") }.entries.distinctBy { it.value }
            .map { (path, _) -> Sha256.image(checkNotNull(files.bytes(root, path))) }
            .sortedBy { it.sha256 }

        fun watch(point: FaultPoint) {
            when (point) {
                is FaultPoint.Remove -> removing = files.identity(root, point.path)
                is FaultPoint.RemoveDone -> if (removing != null && removing !in files.everyFile(root).values) problems += "${point.path} went without another name of its file"
                else -> Unit
            }
            val (offDir, offChunk) = profile.record.files.map { it.citadelPath(profile.record).value }
            for ((dir, chunk) in listOf(offDir to offChunk, "addons/profile_x/pak01_dir.vpk" to "addons/profile_x/pak01_000.vpk")) {
                if (files.identity(root, "$citadel/$dir") != null && files.identity(root, "$citadel/$chunk") == null) problems += "at $point $dir was there without its chunk"
            }
        }

        fun adoptedWhole() {
            assertEquals(emptyList(), problems)
            val manifest = assertIs<LibraryManifest>(store.manifest)
            assertNull(manifest.pending)
            assertEquals(listOf(held) + adoptions.map { it.record }, manifest.records)
            assertEquals(book, manifest.profiles)
            assertNull(manifest.savedChoices, "the numbers already load in the saved order")
            for ((path, identity) in identities) {
                val now = ends[path]
                if (now != null && now != path) assertNull(files.identity(root, "$citadel/$path"), "$path went")
                assertEquals(identity, files.identity(root, "$citadel/${now ?: path}"), "${now ?: path} is the file that was at $path")
                assertContentEquals(start.getValue(path), files.bytes(root, "$citadel/${now ?: path}"))
            }
            assertTrue(files.linkedPaths.all { linked -> ends.values.any { linked == "$citadel/$it" } }, "only Hideout names were made: ${files.linkedPaths}")
            assertEquals(images, vpkImages(), "every VPK file is still there once")
        }
    }

    @Test
    fun anAdoptionClaimsInPlaceMovesTheRestToVacantNamesAndCommitsOnce() = runTest {
        val world = World()
        world.files.around = world::watch
        assertEquals(ChangeOutcome.Done, world.ledger().also { it.reload() }.adopt(plan))
        world.adoptedWhole()
        assertEquals(3, world.store.saved.size, "Linking, Releasing, and the commit")
        assertIs<PendingChange.Adopt>(world.store.saved[0].pending).also { assertEquals(AdoptStep.Linking, it.step) }
        assertTrue(world.store.saved.dropLast(1).all { it.records == listOf(held) }, "nothing is committed before the journal clears")
    }

    @Test
    fun claimsAloneCommitWithoutAJournalOrAnyFileTouched() = runTest {
        val world = World()
        val claims = plan.copy(adopted = listOf(claimed), book = ProfileBook.main(listOf(held, claimed.record)), target = ProfileBook.main(listOf(held, claimed.record)).withOrder(root, listOf("c", "h")))
        assertEquals(ChangeOutcome.Done, world.ledger().also { it.reload() }.adopt(claims))
        assertEquals(1, world.store.saved.size)
        assertEquals(emptyList(), world.files.linkedPaths + world.files.removedPaths)
    }

    @Test
    fun aClaimedFileThatChangedOrATakenNameRefusesWithNothingSavedOrTouched() = runTest {
        val changed = World().apply { files.rewrite(root, "$citadel/addons/pak01_dir.vpk", bytes("other")) }
        assertEquals(LibraryProblem.FileChanged("$citadel/addons/pak01_dir.vpk"), assertIs<ChangeOutcome.Refused>(changed.ledger().also { it.reload() }.adopt(plan)).problem)
        val parkedAt = "$citadel/${parked.record.files.single().citadelPath(parked.record).value}"
        val taken = World().apply { files.putBytes(root, parkedAt, bytes("someone")) }
        assertEquals(LibraryProblem.NameTaken(parkedAt), assertIs<ChangeOutcome.Refused>(taken.ledger().also { it.reload() }.adopt(plan)).problem)
        val number = World().apply { files.putBytes(root, "$citadel/addons/pak01_extra.vpk", bytes("someone")) }
        assertEquals(LibraryProblem.NumberTaken(1, listOf("pak01_extra.vpk")), assertIs<ChangeOutcome.Refused>(number.ledger().also { it.reload() }.adopt(plan)).problem, "another entry on the claimed number")
        for (world in listOf(changed, taken, number)) {
            assertEquals(emptyList(), world.store.saved)
            assertEquals(emptyList(), world.files.linkedPaths + world.files.removedPaths)
        }
    }

    @Test
    fun aNameTakenWhileLinkingUndoesEveryLinkAndKeepsTheOtherManagersFiles() = runTest {
        val world = World()
        val raced = global.record.files.first().citadelPath(global.record).value
        world.files.around = { point ->
            world.watch(point)
            if (point == FaultPoint.Link("$citadel/$raced")) world.files.putBytes(root, "$citadel/$raced", bytes("raced"))
        }
        val refused = assertIs<ChangeOutcome.Refused>(world.ledger().also { it.reload() }.adopt(plan))
        assertIs<LibraryProblem.PlacementFailed>(refused.problem)
        assertEquals(emptyList(), world.problems)
        assertEquals(listOf(held), world.store.manifest?.records)
        assertNull(world.store.manifest?.pending)
        world.identities.forEach { (path, identity) -> assertEquals(identity, world.files.identity(root, "$citadel/$path"), "$path is where it was") }
        assertContentEquals(bytes("raced"), world.files.bytes(root, "$citadel/$raced"), "someone else's file stays")
        for (target in ends.values.filter { it != "addons/pak01_dir.vpk" && it != raced }) assertNull(world.files.identity(root, "$citadel/$target"), "$target was undone")
    }

    @Test
    fun aCrashAtEveryLinkInspectAndRemoveAndAtEverySaveRecoversToOneWholeAdoption() = runTest {
        val dry = World()
        val points = mutableListOf<FaultPoint>()
        dry.files.around = { points += it }
        dry.ledger().also { it.reload() }.adopt(plan)
        dry.adoptedWhole()
        assertTrue(points.count { it is FaultPoint.Link } == 5 && points.count { it is FaultPoint.Remove } == 5 && points.count { it is FaultPoint.Look } > 5, "the trace covers every link, inspect and remove: $points")
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
            for (again in listOf(null, 0, 2, 5)) {
                val world = World()
                cut(world)
                crashing { world.ledger().also { it.reload() }.adopt(plan) }
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
                    val committed = world.store.manifest?.records.orEmpty().size > 1
                    if (!committed) {
                        world.identities.forEach { (path, identity) -> assertEquals(identity, world.files.identity(root, "$citadel/$path"), "$path is where it was before") }
                        ends.filter { (from, to) -> from != to }.values.forEach { assertNull(world.files.identity(root, "$citadel/$it"), "$it was undone") }
                        assertEquals(world.images, world.vpkImages(), "an undone adoption leaves every VPK file once")
                        assertEquals(ChangeOutcome.Done, ledger.adopt(plan), "running the import again finishes it")
                    }
                    world.adoptedWhole()
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
