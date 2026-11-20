package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.ports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class EditedFilesTest {
    private val root = GameRoot("/games/Deadlock")
    private val addons = SetFolder.Addons.path
    private val stored = InMemoryGameFiles.Stored
    private val lockin = ProfileId("lockin")
    private val blackoutAt06 = "$addons/pak06_dir.vpk"
    private val clownCopy = "$addons/pak89_dir.vpk"

    private class Mod(val id: String, val number: Int?, val sets: Int = 1)

    private val mods = listOf(
        Mod("clown", null), Mod("numeric", null, sets = 2), Mod("pumpkin", 1), Mod("lizard", 2, sets = 3),
        Mod("blackout", 6), Mod("speedometer", 7), Mod("witch", null), Mod("fov", 14), Mod("mario", 46, sets = 2),
    )
    private val order = mods.map { it.id }

    private fun bytes(id: String, set: Int) = FakeVpk.directory(seed = order.indexOf(id) * 10 + set)

    private val rebuilt = bytes("blackout", 0) + "tweaked config".encodeToByteArray()

    private val sameSize = bytes("blackout", 0).copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }

    private fun placed(mod: Mod): ManifestRecord {
        val source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, order.indexOf(mod.id) + 1L, order.indexOf(mod.id) * 10L + 10, null))
        val sets = (0 until mod.sets).map { set ->
            InstalledSet(source, null, null, SetOrigin.Imported(Sha256.image(bytes(mod.id, set))), null, "part$set", listOf(directory(Sha256.image(bytes(mod.id, set)))))
        }
        return ManifestRecord(mod.id, root, mod.id, placedAt(mod.number), sets)
    }

    private fun book(enabled: Set<String>) = ProfileBook(lockin, listOf(Profile(lockin, "lockin", ProfileColour.Mauve, enabled, mapOf(root to order))))

    private val on = mods.filter { it.number != null }.map { it.id }.toSet()

    private inner class World(edited: Boolean, paused: Boolean) {
        val records = mods.map(::placed)
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, HideoutGameInfo)
            for (record in records) for ((set, owned) in record.sets.withIndex()) putBytes(root, record.filesOf(owned).single().relativePath(record), bytes(record.installId, set))
            putBytes(root, "$addons/623518_pak01_dir.vpk", ByteArray(9) { 7 })
            putBytes(root, clownCopy, bytes("clown", 0))
            putBytes(root, "$stored/${Sha256.image(bytes("blackout", 0)).sha256}", bytes("blackout", 0))
            if (edited) putBytes(root, blackoutAt06, rebuilt)
        }
        val store = InMemoryLibraryStore(
            LibraryManifest(
                records = records,
                profiles = book(on),
                savedChoices = if (paused) SavedChoices(book(on + "clown" + "numeric")) else null,
            ),
        )
        val processes = FakeProcesses()
        val reads = mutableListOf<String>()
        private val inventory = object : GameInventory by files {
            override suspend fun file(root: GameRoot, relativePath: String): FileObservation = files.file(root, relativePath).also { reads += relativePath }
        }
        private var session: CoroutineScope? = null
        private var nextId = 0

        fun TestScope.library(): ModLibrary {
            session?.cancel()
            val owned = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            session = owned
            return started(ModLibrary(FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, inventory, files.ports(processes = processes), owned, ids = { "id${++nextId}" }, watch = testWatchTiming))
        }

        fun manifest() = assertNotNull(store.manifest)

        fun record(id: String) = manifest().records.single { it.installId == id }

        fun path(id: String) = record(id).let { record -> record.files.single().relativePath(record) }

        fun twin(bytes: ByteArray) = files.identity(root, "$stored/${Sha256.image(bytes).sha256}")
    }

    private fun ModLibrary.ready(): LibraryStatus.Ready = assertIs<LibraryStatus.Ready>(state.value.library)

    @Test
    fun theOwnersLibraryPutsTheOtherModsInTheGameOnTheNextStartAndLeavesTheEditedOneWhereItIs() = runTest {
        val world = World(edited = true, paused = true)
        val edit = world.files.identity(root, blackoutAt06)
        val library = with(world) { library() }

        assertEquals(mapOf<VpkPart, FileImage>(VpkPart.Directory to Sha256.image(rebuilt)), world.record("blackout").sets.single().edits, "the edit is noted")
        assertEquals(mapOf("blackout" to FilesDamage.Changed), library.ready().damaged)
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "nothing is left to apply or to show as failed")
        assertEquals(order - "witch", world.manifest().records.filter { it.enabled }.map { it.installId }.sortedBy(order::indexOf), "clown and numeric are in the game")
        assertEquals(order - "witch" - "blackout", onLoadOrder(world.manifest().records) - "blackout", "the others load in the saved order")
        assertEquals(Placement.On(Slot(6)), world.record("blackout").placement, "Blackout keeps its number")
        assertEquals(edit, world.files.identity(root, blackoutAt06), "and its very file")
        assertContentEquals(rebuilt, world.files.bytes(root, blackoutAt06))
        assertEquals(edit, world.twin(rebuilt), "the edit has its twin in the backups")
        assertEquals(ByteArray(9) { 7 }.toList(), world.files.bytes(root, "$addons/623518_pak01_dir.vpk")?.toList())
        assertContentEquals(bytes("clown", 0), world.files.bytes(root, "$addons/pak89_dir.vpk"), "the other tool's copy stays")
        assertNull(world.manifest().pending)
    }

    @Test
    fun keepingTheEditRecordsItsBytesAndTheModThenTakesItsSavedPlaceWithItsFile() = runTest {
        val world = World(edited = true, paused = true)
        val edit = world.files.identity(root, blackoutAt06)
        val library = with(world) { library() }

        library.keepEdits("blackout")
        runCurrent()
        val kept = world.record("blackout")
        assertEquals(listOf(directory(Sha256.image(rebuilt))), kept.sets.single().files)
        assertEquals(emptyMap(), kept.sets.single().edits)
        assertEquals(order - "witch", onLoadOrder(world.manifest().records), "Blackout loads where the owner put it")
        assertEquals(edit, world.files.identity(root, world.path("blackout")), "as the very file the owner made")
        assertContentEquals(rebuilt, world.files.bytes(root, world.path("blackout")))
        assertEquals(edit, world.twin(rebuilt))
        assertNull(world.manifest().savedChoices, "the files are at the saved choices")
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(emptyMap(), library.ready().damaged)
    }

    @Test
    fun restoringTheOriginalPutsBackWhatTheBackupsKeptMovesTheEditAsideAndTheModThenTakesItsSavedPlace() = runTest {
        val world = World(edited = true, paused = true)
        val edit = world.files.identity(root, blackoutAt06)
        val original = world.twin(bytes("blackout", 0))
        val library = with(world) { library() }
        assertEquals(setOf("blackout"), library.ready().stored)

        library.reinstall("blackout")
        runCurrent()
        assertEquals(emptyMap(), world.record("blackout").sets.single().edits)
        assertEquals(order - "witch", onLoadOrder(world.manifest().records), "Blackout loads where the owner put it")
        assertEquals(original, world.files.identity(root, world.path("blackout")), "as the very file the backups kept: nothing downloaded or unpacked")
        assertContentEquals(bytes("blackout", 0), world.files.bytes(root, world.path("blackout")))
        val aside = "${InMemoryGameFiles.Edits}/blackout ${LocalDate.now()}/pak06_dir.vpk"
        assertEquals(edit, world.files.identity(root, aside), "the owner's edit is the very file, moved aside")
        assertContentEquals(rebuilt, world.files.bytes(root, aside))
        assertEquals(emptyMap(), library.ready().damaged)
        assertNull(world.manifest().pending)
        assertNull(world.manifest().savedChoices, "the files are at the saved choices")
    }

    @Test
    fun restoringTheOriginalMovesAsideAChunkTheEditAddedThatTheRecordDoesNotName() = runTest {
        val world = World(edited = true, paused = false)
        val chunk = "a chunk the rebuild added".encodeToByteArray()
        val chunkAt06 = "$addons/pak06_000.vpk"
        world.files.putBytes(root, chunkAt06, chunk)
        val added = world.files.identity(root, chunkAt06)
        val library = with(world) { library() }

        library.reinstall("blackout")
        runCurrent()
        val aside = "${InMemoryGameFiles.Edits}/blackout ${LocalDate.now()}"
        assertEquals(added, world.files.identity(root, "$aside/pak06_000.vpk"), "the added chunk moves aside as the very file, with the edited directory")
        assertContentEquals(chunk, world.files.bytes(root, "$aside/pak06_000.vpk"))
        assertContentEquals(rebuilt, world.files.bytes(root, "$aside/pak06_dir.vpk"))
        assertNull(world.files.bytes(root, chunkAt06), "nothing of the edit is left in addons to hold 06")
        assertContentEquals(bytes("blackout", 0), world.files.bytes(root, blackoutAt06))
        assertEquals(Placement.On(Slot(6)), world.record("blackout").placement)
        assertEquals(emptyMap(), library.ready().damaged)
        assertNull(world.manifest().pending)
    }

    @Test
    fun anEditMadeAgainInPlaceAfterItWasNotedIsWhatMovesAsideOnRestore() = runTest {
        val world = World(edited = true, paused = false)
        val library = with(world) { library() }
        val edit = world.files.identity(root, blackoutAt06)
        val again = rebuilt + "and tweaked once more".encodeToByteArray()
        world.files.rewrite(root, blackoutAt06, again)

        library.reinstall("blackout")
        runCurrent()
        val aside = "${InMemoryGameFiles.Edits}/blackout ${LocalDate.now()}/pak06_dir.vpk"
        assertEquals(edit, world.files.identity(root, aside))
        assertContentEquals(again, world.files.bytes(root, aside), "the bytes there now, not the ones noted")
        assertContentEquals(bytes("blackout", 0), world.files.bytes(root, world.path("blackout")))
        assertEquals(emptyMap(), world.record("blackout").sets.single().edits)
    }

    @Test
    fun aFileStillOneWithItsTwinIsNotReadAgainOnTheNextStart() = runTest {
        val world = World(edited = true, paused = false)
        with(world) { library() }
        assertEquals(world.records.flatMap { record -> record.files.map { it.relativePath(record) } }.toSet() + clownCopy, world.reads.toSet(), "the first start reads each file once")
        world.reads.clear()

        val library = with(world) { library() }
        assertEquals(listOf(clownCopy), world.reads, "the next one reads only the other tool's copy, to tell it is Clown's")
        assertEquals(mapOf("blackout" to FilesDamage.Changed), library.ready().damaged, "and still sees the edit")
    }

    @Test
    fun anEditInPlaceIsSeenAtTheNextStartByItsSizeOrModifiedTimeAndTheBackupsKeepEachContentUnderItsOwnSha() = runTest {
        for (edit in listOf(rebuilt, sameSize)) {
            val case = if (edit === sameSize) "same size" else "new size"
            val world = World(edited = false, paused = false)
            with(world) { library() }
            val placed = world.files.identity(root, blackoutAt06)
            assertEquals(placed, world.twin(bytes("blackout", 0)), case)
            world.files.rewrite(root, blackoutAt06, edit)
            world.reads.clear()

            val library = with(world) { library() }
            assertEquals(listOf(blackoutAt06, clownCopy), world.reads, "$case: only the file whose twin no longer has its size or time is read")
            assertEquals(mapOf<VpkPart, FileImage>(VpkPart.Directory to Sha256.image(edit)), world.record("blackout").sets.single().edits, case)
            assertEquals(mapOf("blackout" to FilesDamage.Changed), library.ready().damaged, case)
            assertNull(world.twin(bytes("blackout", 0)), "$case: the name of the old bytes, which the edit changed too, goes")
            assertEquals(placed, world.twin(edit), "$case: and the file is kept under the SHA-256 it has")
        }
    }

    @Test
    fun anEditInPlaceAtTheSameSizeWhileHideoutIsOpenStillShowsAfterABackupThatReadNothing() = runTest {
        val world = World(edited = false, paused = false)
        val library = with(world) { library() }
        world.files.rewrite(root, blackoutAt06, sameSize)
        library.backUp()
        runCurrent()
        assertEquals(Maintenance.Backups(BackupWork.BackUp, BackupStage.Done), library.state.value.maintenance)
        world.reads.clear()

        val restarted = with(world) { library() }
        assertEquals(listOf(blackoutAt06, clownCopy), world.reads, "the backup vouched for nothing, so the next check reads the edited file")
        assertEquals(mapOf<VpkPart, FileImage>(VpkPart.Directory to Sha256.image(sameSize)), world.record("blackout").sets.single().edits)
        assertEquals(mapOf("blackout" to FilesDamage.Changed), restarted.ready().damaged)
    }

    @Test
    fun anEditInPlaceAtTheSameSizeShowsWhenAMoveReadsItAndHoldsTheModWithoutPausingTheRest() = runTest {
        val world = World(edited = false, paused = false)
        val library = with(world) { library() }
        val placed = world.files.identity(root, blackoutAt06)
        world.files.rewrite(root, blackoutAt06, sameSize)

        library.setEnabledTogether(setOf("blackout", "clown"), true)
        library.setEnabled("blackout", false)
        runCurrent()
        assertEquals(mapOf<VpkPart, FileImage>(VpkPart.Directory to Sha256.image(sameSize)), world.record("blackout").sets.single().edits, "the move read it and noted the edit")
        assertEquals(Placement.On(Slot(6)), world.record("blackout").placement, "the mod stays where it is")
        assertEquals(placed, world.files.identity(root, blackoutAt06))
        assertEquals(true, world.record("clown").enabled, "the rest of the choice went through")
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertNull(world.twin(bytes("blackout", 0)), "the stored name of the old bytes goes")
        assertEquals(placed, world.twin(sameSize))
    }
}
