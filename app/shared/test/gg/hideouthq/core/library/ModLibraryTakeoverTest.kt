package gg.hideouthq.core.library

import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.autoexec.SectionMarkers
import gg.hideouthq.core.data.FileObservation
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryTakeoverTest {
    private val root = GameRoot("/games/Deadlock")
    private val citadel = "game/citadel"
    private val migration = MigrationId("m-1")

    private val crosshairSection = "// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===\r\ncitadel_crosshair_pip_gap \"2\"\r\n// === End Crosshair Settings ===\r\n"
    private val autoexec = "bind F5 kill\r\n$crosshairSection// === Deadlock Mod Manager - Map Command (DO NOT EDIT) ===\r\nmap street_test\r\n// === End Map Command ===\r\n".encodeToByteArray()
    private val manifest = "{\"version\":3,\"mods\":{}}".encodeToByteArray()
    private val claimed = FakeVpk.directory(seed = 1)
    private val parked = FakeVpk.directory(seed = 2)

    private class Hashing(private val files: InMemoryGameFiles) : GameInventory by files {
        val hashed = mutableListOf<String>()

        override suspend fun file(root: GameRoot, relativePath: String): FileObservation =
            files.file(root, relativePath).also { if (it is FileObservation.Present) hashed += relativePath }
    }

    private inner class World : CrashWorld {
        override val files = InMemoryGameFiles().apply {
            putBytes(root, "$citadel/gameinfo.gi", DmmGameInfo)
            putBytes(root, "$citadel/cfg/autoexec.cfg", autoexec)
            putBytes(root, "$citadel/addons/pak01_dir.vpk", claimed)
            putBytes(root, "$citadel/addons/700001_skin_dir.vpk", parked)
            putBytes(root, "$citadel/addons/.dmm.json", manifest)
        }
        val inventory = Hashing(files)
        override val store = InMemoryLibraryStore(LibraryManifest())
        val processes = FakeProcesses()
        private var next = 0

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, inventory, files.ports(processes = processes), scope, ids = { "id${++next}" }, watch = testWatchTiming),
        )

        override fun TestScope.open(scope: CoroutineScope) = library(scope)
    }

    private fun incoming(title: String, path: String, bytes: ByteArray, position: LoadPosition?) =
        Incoming(title, Matched.Unpinned(null), path.substringAfterLast('/'), position, listOf(IncomingSet(path.substringAfterLast('/'), listOf(IncomingFile(CitadelPath(path), VpkPart.Directory, Sha256.image(bytes))))))

    private fun takeover(manager: Manager? = Manager.DeadlockModManager) = Takeover(
        migration, root, manager, addonsMount = 0,
        incoming = listOf(incoming("Claimed", "addons/pak01_dir.vpk", claimed, LoadPosition(0, 1)), incoming("Parked", "addons/700001_skin_dir.vpk", parked, null)),
        copies = emptyList(), profiles = emptyList(), unmount = ForeignRoot.keptBy(Manager.DeadlockModManager).toSet(),
        autoexec = AutoexecTakeover.Sections(listOf(SectionMarkers.DmmCrosshair, SectionMarkers.DmmMap)),
        duplicates = emptyList(),
        manifests = listOf(KeptFile(CitadelPath("addons/.dmm.json"), Sha256.image(manifest))),
    )

    @Test
    fun aDuplicateGoesWithItsBytesCheckedAndANameHideoutHoldsNowStays() = runTest {
        val world = World()
        world.files.putBytes(root, "$citadel/addons/profile_x/pak01_dir.vpk", claimed)
        world.files.putBytes(root, "$citadel/addons/profile_y/pak01_dir.vpk", parked)
        val library = with(world) { library() }
        val duplicates = listOf("addons/pak01_dir.vpk", "addons/profile_x/pak01_dir.vpk", "addons/profile_y/pak01_dir.vpk").map { KeptFile(CitadelPath(it), Sha256.image(claimed)) }

        assertIs<TakeoverOutcome.Done>(library.takeOver(takeover().copy(duplicates = duplicates)))

        assertContentEquals(claimed, world.files.bytes(root, "$citadel/addons/pak01_dir.vpk"), "a plan read before this name became the mod's own never deletes it")
        assertNull(world.files.bytes(root, "$citadel/addons/profile_x/pak01_dir.vpk"))
        assertContentEquals(parked, world.files.bytes(root, "$citadel/addons/profile_y/pak01_dir.vpk"), "a file that holds other bytes now is not the copy that was read")
    }

    @Test
    fun aTakeoverAdoptsTheModsThenTakesTheConfigsAndManifestsOver() = runTest {
        val world = World()
        val library = with(world) { library() }
        var configuring = false
        val outcome = library.takeOver(takeover()) { configuring = true }

        assertEquals(TakeoverOutcome.Done(on = 1, off = 1, profiles = emptyList(), commands = 1), outcome)
        assertTrue(configuring)
        val records = assertIs<LibraryStatus.Ready>(library.state.value.library).records
        assertEquals(listOf("Claimed" to listOf(1), "Parked" to emptyList()), records.map { it.title to it.numbers.map { number -> number.value } }, "the mod that loads is claimed where it is, the parked one comes in off")
        assertContentEquals(claimed, world.files.bytes(root, "$citadel/addons/pak01_dir.vpk"))
        assertContentEquals(parked, world.files.bytes(root, "$citadel/${records[1].files.single().citadelPath(records[1]).value}"))
        assertNull(world.files.bytes(root, "$citadel/addons/700001_skin_dir.vpk"))
        assertNull(world.files.bytes(root, "$citadel/addons/.dmm.json"), "the manager's manifest goes once everything is Hideout's")

        val info = checkNotNull(world.files.gameInfo(root))
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(info))
        assertEquals(MountsRead.Read(listOf(CitadelPath.Addons)), GameInfoPatch.mounts(info))
        assertTrue("Deadlock Mod Manager" !in info.decodeToString() && "\r\n" in info.decodeToString())
        assertEquals(
            "${AutoexecDocument.Header}\r\nbind F5 kill\r\n${crosshairSection}map street_test\r\n",
            world.files.bytes(root, "$citadel/cfg/autoexec.cfg")?.decodeToString(),
            "DMM's commands are plain lines where they were, and its crosshair section stays as it is",
        )

        val configWrites = world.store.saved.count { it.pending is PendingChange.ConfigEdit }
        assertEquals(TakeoverOutcome.Done(1, 1, emptyList(), commands = 0), library.takeOver(takeover().copy(incoming = emptyList())), "taking over a taken-over game again changes nothing")
        assertEquals(configWrites, world.store.saved.count { it.pending is PendingChange.ConfigEdit }, "nor writes anything")
        assertEquals(records, assertIs<LibraryStatus.Ready>(library.state.value.library).records)
    }

    @Test
    fun aCrashAtAnyStepOfAnImportThatClaimsOneModAndBringsTwoInOffAndThenOfTurningOneOnEndsWithEachModWholeInOnePlace() = runTest {
        val split = listOf(FakeVpk.directory(seed = 3), FakeVpk.directory(seed = 4))
        val splitPaths = listOf("addons/pak05_dir.vpk", "addons/pak07_dir.vpk")
        val loose = Incoming(
            "Split", Matched.Unpinned(null), "pak05_dir.vpk", LoadPosition(0, 5),
            splitPaths.zip(split).map { (path, bytes) -> IncomingSet(path.substringAfterLast('/'), listOf(IncomingFile(CitadelPath(path), VpkPart.Directory, Sha256.image(bytes)))) },
        )
        val importing = takeover().let { it.copy(incoming = it.incoming + loose) }
        val sources = mapOf("Claimed" to listOf("addons/pak01_dir.vpk" to claimed), "Parked" to listOf("addons/700001_skin_dir.vpk" to parked), "Split" to splitPaths.zip(split))
        fun world() = World().apply { splitPaths.zip(split).forEach { (path, bytes) -> files.putBytes(root, "$citadel/$path", bytes) } }

        val dry = world()
        var outcome: TakeoverOutcome? = null
        val trace = traced(dry) { launch { outcome = it.takeOver(importing) {} } }
        assertEquals(TakeoverOutcome.Done(on = 2, off = 1, profiles = emptyList(), commands = 1), outcome)
        val adopted = dry.store.manifest!!.records.associate { it.title to it.numbers.map { number -> number.value } }
        assertEquals(mapOf("Claimed" to listOf(1), "Parked" to emptyList(), "Split" to listOf(2, 3)), adopted, "the claimed one where it loads, the split one on after it once the worker turns it on, the parked one off")
        assertTrue(trace.points.size > 20, "the trace has ${trace.points}")

        crashAtEveryStep(trace, ::world, { launch { it.takeOver(importing) {} } }) { world, _ ->
            val byTitle = checkNotNull(world.store.manifest).records.associateBy { it.title }
            assertTrue(byTitle.isEmpty() || byTitle.mapValues { it.value.numbers.map { number -> number.value } } == adopted, "every mod adopted and placed as the import and the worker place them, or none: $byTitle")
            for ((title, files) in sources) {
                val record = byTitle[title]
                val at = record?.let { it.files.map { file -> file.citadelPath(it).value } } ?: files.map { it.first }
                at.zip(files).forEach { (path, file) -> assertContentEquals(file.second, world.files.bytes(root, "$citadel/$path"), "$title at $path") }
                files.map { it.first }.filter { it !in at }.forEach { path -> assertNull(world.files.bytes(root, "$citadel/$path"), "$title left $path") }
            }
        }
    }

    @Test
    fun aGameLastPlayedVanillaStaysVanillaOnceTheManagerThatLoadsItsModsSinceIsTakenOver() = runTest {
        val world = World()
        world.files.putBytes(root, "$citadel/gameinfo.gi", VanillaGameInfo)
        val library = with(world) { library() }
        assertNull(library.launch(modded = false) {})
        assertFalse(checkNotNull(world.store.manifest?.receipt(root)).lines.mods)
        world.files.putBytes(root, "$citadel/gameinfo.gi", DmmGameInfo)
        assertIs<TakeoverOutcome.Done>(library.takeOver(takeover()))
        assertEquals(SearchPathsCheck.Read(coveredBy = null, customGames = false), GameInfoPatch.check(checkNotNull(world.files.gameInfo(root))), "Hideout's block is not written")
    }

    @Test
    fun aTakeoverHashesWhatItClaimsOnceAndWhatItMovesOnlyThroughTheMove() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.inventory.hashed.clear()
        assertIs<TakeoverOutcome.Done>(library.takeOver(takeover()))
        assertEquals(listOf("$citadel/addons/pak01_dir.vpk"), world.inventory.hashed.filter { it.endsWith(".vpk") })
    }

    @Test
    fun aTakeoverWaitsForTheGameAndTheManagerItImportsFromAndAnUnknownScanBlocks() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.processes.scan = ProcessScan.Seen(game = true)
        assertEquals(TakeoverOutcome.Waiting(ImportWait.GameOpen), library.takeOver(takeover()))
        world.processes.scan = ProcessScan.Seen(managers = setOf(Manager.DeadlockModManager))
        assertEquals(TakeoverOutcome.Waiting(ImportWait.ManagerOpen(Manager.DeadlockModManager)), library.takeOver(takeover()))
        assertEquals(ManagerActivity.Seen(setOf(Manager.DeadlockModManager)), library.state.value.managers)
        world.processes.scan = ProcessScan.Unknown("no /proc")
        assertEquals(TakeoverOutcome.Waiting(ImportWait.GameOpen), library.takeOver(takeover()))
        assertEquals(ManagerActivity.Unknown, library.state.value.managers)
        assertEquals(emptyList(), world.store.saved)
        assertContentEquals(DmmGameInfo, world.files.gameInfo(root))

        world.processes.scan = ProcessScan.Seen(managers = setOf(Manager.Grimoire))
        assertIs<TakeoverOutcome.Done>(library.takeOver(takeover()), "only the manager imported from holds the import")
    }

    @Test
    fun aManifestThatChangedSinceItWasCopiedStays() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.files.putBytes(root, "$citadel/addons/.dmm.json", "{}".encodeToByteArray())
        assertIs<TakeoverOutcome.Done>(library.takeOver(takeover()))
        assertContentEquals("{}".encodeToByteArray(), world.files.bytes(root, "$citadel/addons/.dmm.json"))
    }

    @Test
    fun anAutoexecThatCannotBeWrittenIsReportedAsAutoexec() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.files.failing += FaultPoint.ReplaceConfig
        val outcome = library.takeOver(takeover().copy(unmount = emptySet()))
        assertEquals(TakeoverOutcome.Refused(ImportProblem.Library(LibraryProblem.AutoexecWriteFailed("failed at ReplaceConfig"))), outcome)
        assertContentEquals(autoexec, world.files.bytes(root, "$citadel/cfg/autoexec.cfg"))
    }
}
