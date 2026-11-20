package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeCompatibility
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryCompatibilityTest {
    private val root = GameRoot("/games/Deadlock")
    private val addons = SetFolder.Addons.path
    private val gameInfo = (GameInfoPatch.withBlock(
        "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame\t\tcitadel\r\n\t\t\tGame\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n".encodeToByteArray(),
        HideoutLines(mods = true, customGames = false),
        overflow = 0,
    ) as GameInfoEdit.Patched).bytes

    private val dirA = ByteArray(20) { 1 }
    private val chunkA = ByteArray(30) { 2 }
    private val dirB = ByteArray(10) { 3 }
    private val dirC = ByteArray(12) { 4 }

    private fun source(id: Long) = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, id, id + 1, null))

    private val a = ManifestRecord("a", root, "Werewolf", placedAt(5), sets = listOf(
        InstalledSet(source(100), null, null, SetOrigin.Downloaded(ArchiveIdentity("/downloads/a.zip", 10, "a".repeat(64))), null, "skin/pak01_dir.vpk", listOf(owned(VpkPart.Directory, dirA), owned(VpkPart.Chunk(0), chunkA))),
    ))
    private val b = ManifestRecord("b", root, "Ghost", placedAt(6), sets = listOf(
        InstalledSet(source(200), null, null, SetOrigin.Downloaded(ArchiveIdentity("/downloads/b.zip", 10, "b".repeat(64))), null, "pak06_dir.vpk", listOf(owned(VpkPart.Directory, dirB))),
    ))
    private val c = ManifestRecord("c", root, "Frank", Placement.Off, sets = listOf(
        InstalledSet(source(300), null, null, SetOrigin.Downloaded(ArchiveIdentity("/downloads/c.zip", 10, "c".repeat(64))), null, "pak07_dir.vpk", listOf(owned(VpkPart.Directory, dirC))),
    ))

    private val placed = mapOf(
        "$addons/pak05_dir.vpk" to dirA,
        "$addons/pak05_000.vpk" to chunkA,
        "$addons/pak06_dir.vpk" to dirB,
        c.files.single().relativePath(c) to dirC,
    )

    private inner class World(
        records: List<ManifestRecord> = listOf(a, b, c),
        files: Map<String, ByteArray> = placed,
        autoRepair: Boolean? = false,
        choices: Map<String, RepairChoice> = emptyMap(),
        repairable: List<ByteArray> = emptyList(),
    ) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, gameInfo)
            files.forEach { (path, bytes) -> putBytes(root, path, bytes) }
        }
        val store = InMemoryLibraryStore(LibraryManifest(records = records, repairChoices = choices))
        val preferences = Preferences(MemoryStorage(autoRepair?.let { mapOf("preferences.json" to "{\"autoRepair\":$it}") }.orEmpty()))
        val processes = FakeProcesses()
        val port = FakeCompatibility(this.files).apply { repairable.forEach { findings[sha256Hex(it)] = listOf(FakeCompatibility.repairable()) } }
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(this.files)
        private var nextId = 0

        fun TestScope.library(scope: CoroutineScope = backgroundScope, store: LibraryStore = this@World.store): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root)), preferences, store, files,
                InstallPorts(banana, downloader, files, files, processes), scope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
                compatibility = port,
            ),
        )

        fun manifest(): LibraryManifest = assertNotNull(store.manifest)

        fun record(id: String) = manifest().records.single { it.installId == id }

        fun bytes(name: String) = assertNotNull(files.bytes(root, "$addons/$name"))
    }

    private suspend fun TestScope.repair(library: ModLibrary, vararg ids: String) {
        library.useRepairedFiles(ids.toSet())
        runCurrent()
    }

    private fun ModLibrary.compat(id: String): InstallCompatibility = assertNotNull(state.value.compatibility.installs[id])

    private fun InstallCompatibility.findingsOf(resource: String): List<FindingKind> = check?.findings.orEmpty().filter { it.resource == resource }.map { it.kind }

    @Test
    fun everyModIsCheckedAtStartAndNoKnownProblemIsNoLabel() = runTest {
        val world = World()
        val library = with(world) { library() }
        assertEquals(setOf("a", "b", "c"), world.port.checked.toSet())
        for (id in listOf("a", "b", "c")) {
            val install = library.compat(id)
            assertNotNull(install.check)
            assertNull(install.label(enabled = true), "a mod with no known problem has no label, never a healthy one")
        }
    }

    @Test
    fun withAutomaticRepairOffARepairableModIsLabelledAndNothingChangesUntilTheUserAsks() = runTest {
        val world = World(autoRepair = false, repairable = listOf(dirA))
        val library = with(world) { library() }
        assertEquals(CompatLabel.RepairAvailable, library.compat("a").label(enabled = true))
        assertEquals(listOf(a, b, c), world.manifest().records, "with automatic repair off, nothing repairs on its own")
        assertEquals(emptyList(), world.manifest().originals)
        assertContentEquals(dirA, world.bytes("pak05_dir.vpk"))
        assertEquals(emptyList(), world.port.derived)
    }

    @Test
    fun withNothingSavedEveryRepairableModIsRepairedWithoutAChoiceOnOrOff() = runTest {
        val world = World(autoRepair = null, repairable = listOf(dirA, dirC))
        val library = with(world) { library() }
        assertTrue(library.state.value.compatibility.automatic, "automatic repair is on by default")
        assertNotNull(world.record("a").sets.single().derivation, "the repairable mod is repaired without being asked")
        assertNotNull(world.record("c").sets.single().derivation, "and so is one that is off, so turning it on is instant")
        assertEquals(b, world.record("b"), "a mod with nothing to repair is untouched")
        assertEquals(emptyMap(), world.manifest().repairChoices, "no choice is saved for the user")
        assertEquals(2, world.manifest().originals.size, "each repair keeps its original files")
        assertEquals(CompatLabel.RepairApplied, library.compat("a").label(enabled = true))
    }

    @Test
    fun aModSetToOriginalIsNeverRepairedOnItsOwnAndGoesBackToItsOriginals() = runTest {
        val chosen = World(autoRepair = true, choices = mapOf("a" to RepairChoice.Original), repairable = listOf(dirA))
        with(chosen) { library() }
        assertEquals(a, chosen.record("a"), "a saved Original is never repaired automatically")
        assertEquals(emptyList(), chosen.port.derived)

        val world = World(autoRepair = true, repairable = listOf(dirA))
        val library = with(world) { library() }
        assertNotNull(world.record("a").sets.single().derivation)
        library.useOriginalFiles("a")
        runCurrent()
        assertEquals(a, world.record("a"), "Use original files puts an automatic repair back")
        assertContentEquals(dirA, world.bytes("pak05_dir.vpk"))
        library.checkCompatibility()
        poll()
        assertEquals(a, world.record("a"), "and it is not repaired again on its own")
        assertEquals(RepairChoice.Original, world.manifest().repairChoices["a"])
    }

    @Test
    fun turningAutomaticRepairOffKeepsWhatIsRepairedAndDropsTheRepairUnderWay() = runTest {
        val world = World(autoRepair = true, repairable = listOf(dirA))
        world.port.findings[sha256Hex(dirC)] = listOf(FakeCompatibility.repairable())
        var derives = 0
        world.port.beforeDerive = { if (++derives == 2) world.preferences.setAutoRepair(false) }
        val library = with(world) { library() }
        assertNotNull(world.record("a").sets.single().derivation, "a repair placed before it went off stays")
        assertEquals(c, world.record("c"), "the repair under way when it went off is not placed")
        assertNull(library.compat("c").pending, "nor queued again")
        assertNull(library.compat("c").problem, "nor called a failure")
        assertEquals(CompatLabel.RepairAvailable, library.compat("c").label(enabled = true))
        assertEquals(listOf("a"), world.manifest().originals.map { it.installId }, "the original kept for it is forgotten again")
        library.checkCompatibility()
        poll()
        assertNotNull(world.record("a").sets.single().derivation, "turning it off puts nothing back")
        assertEquals(c, world.record("c"))
        assertEquals(2, world.port.derived.size)
    }

    @Test
    fun turningAutomaticRepairOnRepairsWhatIsRepairableAtOnce() = runTest {
        val world = World(autoRepair = false, repairable = listOf(dirA))
        val library = with(world) { library() }
        assertEquals(a, world.record("a"))
        world.preferences.setAutoRepair(true)
        runCurrent()
        assertNotNull(world.record("a").sets.single().derivation, "the mod already found repairable is repaired without waiting for a poll")
        assertTrue(library.state.value.compatibility.automatic)
        assertNull(world.manifest().repairChoices["a"])
    }

    @Test
    fun aRepairComesFromADurableOriginalAndIsRecordedWithTheFilesItMade() = runTest {
        val world = World()
        world.port.findings[sha256Hex(dirA)] = listOf(FakeCompatibility.repairable(edits = 6))
        val library = with(world) { library() }
        repair(library, "a")

        val repaired = world.record("a")
        val set = repaired.sets.single()
        val derivation = assertNotNull(set.derivation)
        val original = world.manifest().originals.single()
        assertEquals(derivation.original, original.id)
        assertEquals("a", original.installId)
        assertEquals(listOf(Sha256.image(dirA), Sha256.image(chunkA)), derivation.originalFiles)
        assertEquals(listOf(FakeCompatibility.Frame), derivation.rules)
        assertEquals("build:fake", derivation.context)
        assertEquals(RepairChoice.Repaired, world.manifest().repairChoices["a"])
        assertEquals(a.sets.single().copy(files = set.files, derivation = derivation), set)
        assertEquals(a.copy(sets = listOf(set)), repaired)
        assertContentEquals(dirA + "+repaired".encodeToByteArray(), world.bytes("pak05_dir.vpk"))
        assertContentEquals(chunkA, world.bytes("pak05_000.vpk"))
        assertEquals(b, world.record("b"), "another mod is untouched")
        assertEquals(c, world.record("c"))
        assertEquals(listOf(dirA, chunkA).map { it.toList() }, world.files.originals.getValue(original.id).map { it.toList() })
        assertEquals(CompatLabel.RepairApplied, library.compat("a").label(enabled = true))
        assertEquals(emptyMap(), (library.state.value.library as LibraryStatus.Ready).damaged)

        val saves = world.store.saved
        val registered = saves.indexOfFirst { it.originals.isNotEmpty() }
        val committed = saves.indexOfFirst { manifest -> manifest.records.any { it.sets.any { set -> set.derivation != null } } }
        assertTrue(registered in 0 until committed, "the original is kept and registered before any repair is placed")
        assertTrue(saves.take(committed).all { manifest -> manifest.records.none { it.sets.any { set -> set.derivation != null } } }, "nothing is called repaired before its files are committed")
        assertEquals(original, saves[committed].originals.single(), "the lineage is saved with the files it describes")
    }

    @Test
    fun theOriginalFilesComeBackAndStayChosen() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        repair(library, "a")
        val id = world.manifest().originals.single().id

        library.useOriginalFiles("a")
        runCurrent()
        assertEquals(a, world.record("a"), "the set is exactly as installed again")
        assertContentEquals(dirA, world.bytes("pak05_dir.vpk"))
        assertEquals(RepairChoice.Original, world.manifest().repairChoices["a"])
        assertEquals(emptyList(), world.manifest().originals, "an original nothing was made from is forgotten")
        assertEquals(listOf(id), world.port.released)

        library.checkCompatibility()
        runCurrent()
        assertEquals(a, world.record("a"), "choosing the original files is remembered: no repair is applied again")
        assertNull(library.compat("a").label(enabled = true), "the choice answers what was found, so no chip asks again")
        assertEquals(1, library.compat("a").repairable.size, "though it is still known")
    }

    @Test
    fun aPlanMadeForAGameThatChangedIsRemadeNotPlaced() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        world.port.beforeDerive = {
            world.port.game = "build:updated"
            world.port.beforeDerive = null
        }
        repair(library, "a")
        val derivation = assertNotNull(world.record("a").sets.single().derivation)
        assertEquals("build:updated", derivation.context, "the repair was made again for the game as it is now")
        assertTrue(world.store.saved.none { manifest -> manifest.records.any { it.sets.any { set -> set.derivation?.context == "build:fake" } } })
    }

    @Test
    fun aFileChangedOutsideHideoutWhileARepairIsMadeIsNeverReplaced() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        val theirs = ByteArray(25) { 9 }
        world.port.beforeDerive = { world.files.rewrite(root, "$addons/pak05_dir.vpk", theirs) }
        repair(library, "a")
        assertEquals(a, world.record("a"))
        assertContentEquals(theirs, world.bytes("pak05_dir.vpk"), "their file stays")
        assertEquals(null, world.manifest().pending)
        val install = library.compat("a")
        assertNotNull(install.check?.sets?.single()?.incomplete, "what the plan rested on read differently, so the mod was checked again as it is now")
        assertEquals(emptyList(), install.repairable)
    }

    @Test
    fun aFileChangedOutsideHideoutAtTheLastMomentIsRefusedByTheReplacement() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        val theirs = ByteArray(25) { 9 }
        world.port.beforeDerive = {
            world.port.onGame = {
                world.files.rewrite(root, "$addons/pak05_dir.vpk", theirs)
                world.port.onGame = null
            }
        }
        repair(library, "a")
        assertEquals(a.withOnly { copy(edits = mapOf(VpkPart.Directory to Sha256.image(theirs))) }, world.record("a"), "their file is noted as their edit")
        assertContentEquals(theirs, world.bytes("pak05_dir.vpk"), "their file stays")
        assertEquals("its files changed after Hideout placed them", library.compat("a").failed, "the mod is checked no more until the user decides on the edit")
        assertEquals(null, world.manifest().pending)
    }

    @Test
    fun aRepairTheHelperRefusesIsReportedAndNotRetriedOnItsOwn() = runTest {
        val world = World(repairable = listOf(dirA))
        world.port.refuseDerive = "the repaired model did not read back"
        val library = with(world) { library() }
        repair(library, "a")
        assertEquals(a, world.record("a"))
        assertEquals("the repaired model did not read back", library.compat("a").problem)
        assertEquals(emptyList(), world.manifest().originals, "the original kept for it is forgotten again")
        val attempts = world.port.derived.size
        library.retryChoices()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(attempts, world.port.derived.size)
    }

    @Test
    fun aModdedLaunchStartsWhateverTheChecksFound() = runTest {
        val world = World(repairable = listOf(dirA))
        world.port.findings[sha256Hex(dirB)] = listOf(FakeCompatibility.needsAuthor())
        val library = with(world) { library() }
        assertEquals(CompatLabel.NeedsAuthorUpdate, library.compat("b").label(enabled = true))
        var dispatched = 0
        assertNull(library.launch(modded = true) { dispatched++ }, "what checks found is shown in Library, never asked about at Play")
        assertEquals(1, dispatched)
        assertEquals(listOf(a, b, c), world.manifest().records, "nothing was repaired or turned off")
    }

    @Test
    fun withoutAHelperEveryModStaysUncheckedAndNothingIsRepaired() = runTest {
        val world = World()
        world.port.unavailable = "no codec on this platform"
        world.port.findings[sha256Hex(dirA)] = listOf(FakeCompatibility.repairable())
        val library = with(world) { library() }
        assertEquals("no codec on this platform", library.state.value.compatibility.unavailable)
        assertEquals(emptyList(), world.port.checked)
        assertNull(library.compat("a").label(enabled = true))
        repair(library, "a")
        assertEquals(a, world.record("a"))
    }

    @Test
    fun originalFilesAreNotPutBackOverAFileChangedOutsideHideout() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        repair(library, "a")
        val repaired = world.record("a")
        val theirs = ByteArray(33) { 6 }
        world.files.rewrite(root, "$addons/pak05_dir.vpk", theirs)
        library.useOriginalFiles("a")
        runCurrent()
        assertEquals(repaired.withOnly { copy(edits = mapOf(VpkPart.Directory to Sha256.image(theirs))) }, world.record("a"), "the record still says what Hideout placed, and notes their edit")
        assertContentEquals(theirs, world.bytes("pak05_dir.vpk"), "their file stays")
        assertTrue(library.compat("a").held)
        assertNull(library.compat("a").problem, "held for their edit from then on, it is tried no more, and no failure speaks for it")
        assertEquals(1, world.manifest().originals.size, "the original stays kept while a set comes from it")
    }

    @Test
    fun aRepairChoiceForAModHeldForAnEditWaitsForTheUsersDecisionAndNeverFails() = runTest {
        val world = World(repairable = listOf(dirA))
        val running = crashableScope()
        val library = with(world) { library(running) }
        repair(library, "a")
        val theirs = ByteArray(33) { 6 }
        world.files.rewrite(root, "$addons/pak05_dir.vpk", theirs)
        running.cancel()
        val restarted = with(world) { library() }
        runCurrent()
        val held = world.record("a")
        assertTrue(held.edited && held.only.derivation != null, "a repaired mod, held for their edit")

        restarted.useOriginalFiles("a")
        runCurrent()
        assertEquals(RepairChoice.Original, world.manifest().repairChoices["a"], "the choice is saved")
        assertEquals(held, world.record("a"), "and waits: nothing is put back while the mod is held")
        assertContentEquals(theirs, world.bytes("pak05_dir.vpk"))
        assertNull(restarted.compat("a").pending, "nothing shows queued")
        assertNull(restarted.compat("a").problem, "nor failed")
        assertTrue(restarted.compat("a").held)
    }

    @Test
    fun uninstallingForgetsTheChoiceAndTheOriginal() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        repair(library, "a")
        val id = world.manifest().originals.single().id
        library.uninstall(assertNotNull(library.uninstallConfirmation("a")), deleteDownload = false)
        runCurrent()
        assertTrue(world.manifest().records.none { it.installId == "a" })
        assertNull(world.manifest().repairChoices["a"])
        assertEquals(emptyList(), world.manifest().originals)
        assertTrue(id in world.port.released)
    }

    @Test
    fun aRepairCutShortByACrashIsFinishedOrUndoneNeverHalfRecorded() = runTest {
        val fresh = "pak05_dir.vpk${TemporaryInfix}id2-new"
        for (point in listOf(FaultPoint.Stage(fresh), FaultPoint.Link("$addons/pak05_dir.vpk"), FaultPoint.Remove("$addons/$fresh"))) {
            val world = World(repairable = listOf(dirA))
            val crashing = crashableScope()
            val library = with(world) { library(crashing) }
            world.files.crashAt = point
            repair(library, "a")
            crashing.cancel()
            world.files.crashAt = null
            val restarted = with(world) { library() }
            runCurrent()
            val manifest = world.manifest()
            assertNull(manifest.pending, "$point: recovery settles the journal")
            val set = manifest.records.single { it.installId == "a" }.sets.single()
            val expected = if (set.derivation == null) dirA else dirA + "+repaired".encodeToByteArray()
            assertContentEquals(expected, world.bytes("pak05_dir.vpk"), "$point: the files are what the record says")
            if (set.derivation != null) assertTrue(manifest.originals.any { it.id == set.derivation.original }, "$point: a recorded repair keeps its original")
            assertEquals(emptyMap(), (restarted.state.value.library as LibraryStatus.Ready).damaged)
        }
    }

    private fun TestScope.poll() {
        advanceTimeBy(testWatchTiming.compatibilityPoll + 1.seconds)
        runCurrent()
    }

    private val model = "models/heroes/test/test.vmdl_c"

    @Test
    fun anInstallationsEarlierSetWinsWhatItSuppliesOverItsLaterSets() = runTest {
        val body = ByteArray(14) { 5 }
        val voice = ByteArray(16) { 6 }
        val archive = ArchiveIdentity("/downloads/d.zip", 10, "d".repeat(64))
        val d = ManifestRecord("d", root, "Two parts", placedAt(8), sets = listOf(
            InstalledSet(source(400), null, null, SetOrigin.Downloaded(archive), null, "body/pak01_dir.vpk", listOf(owned(VpkPart.Directory, body))),
            InstalledSet(source(400), null, null, SetOrigin.Downloaded(archive), null, "voice/pak01_dir.vpk", listOf(owned(VpkPart.Directory, voice))),
        ))
        val world = World(listOf(d), mapOf("$addons/pak08_dir.vpk" to body, "$addons/pak09_dir.vpk" to voice))
        world.port.findings[sha256Hex(voice)] = listOf(FakeCompatibility.repairable(resource = model))
        world.port.supplies[sha256Hex(body)] = setOf(model)
        val library = with(world) { library() }
        assertEquals(Provider(8, "$addons/pak08_dir.vpk", "d", Sha256.image(body)), world.port.above.getValue("d").single { it.rank == 8 }, "the installation's own first set is mounted above its second")
        assertEquals(emptyList(), library.compat("d").repairable, "the first set's copy is what loads, so the second's is not repaired")
        assertNull(library.compat("d").label(enabled = true))
    }

    @Test
    fun anOffInstallationRanksItsSetsByTheirPlaceInItAndTurningItOnChecksItAgainByNumber() = runTest {
        val body = ByteArray(14) { 5 }
        val voice = ByteArray(16) { 6 }
        val archive = ArchiveIdentity("/downloads/d.zip", 10, "d".repeat(64))
        val d = ManifestRecord("d", root, "Two parts", Placement.Off, sets = listOf(
            InstalledSet(source(400), null, null, SetOrigin.Downloaded(archive), null, "body/pak01_dir.vpk", listOf(owned(VpkPart.Directory, body))),
            InstalledSet(source(400), null, null, SetOrigin.Downloaded(archive), null, "voice/pak01_dir.vpk", listOf(owned(VpkPart.Directory, voice))),
        ))
        val world = World(listOf(d), d.files.zip(listOf(body, voice)).associate { (file, bytes) -> file.relativePath(d) to bytes })
        val library = with(world) { library() }
        assertEquals(listOf(Provider(1, d.files.first().relativePath(d), "d", Sha256.image(body))), world.port.above.getValue("d"), "off, its first set is ranked by its place, ahead of its second")
        val checks = world.port.checked.count { it == "d" }

        library.setEnabled("d", true)
        runCurrent()
        val on = world.record("d")
        assertEquals(listOf(1, 2), on.numbers.map { it.value })
        assertTrue(world.port.checked.count { it == "d" } > checks, "the files it is checked against changed, so it is checked again")
        assertEquals(Provider(1, "$addons/pak01_dir.vpk", "d", Sha256.image(body)), world.port.above.getValue("d").single { it.installId == "d" }, "on, by its number")
    }

    private fun remote(id: Long, md5: String) = RemoteFile(id, "lilith_$id.zip", 1_000, md5, null, 1_000, addedAt = 1_777_000_000, downloadUrl = "https://gamebanana.com/dl/$id")

    private fun TestScope.installFromGameBanana(world: World, library: ModLibrary, submission: SubmissionRef, bytes: ByteArray): ManifestRecord {
        world.banana.listings = mapOf(submission to FileListing.Available("Lilith", listOf(remote(101, "ab965746d197e296c253fb552dc1fd47")), true))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to bytes))
        library.install(InstallRequest(InstallInput.Remote(submission, setOf(101L)), setOf(ProfileId("main"))))
        runCurrent()
        return world.manifest().records.single { it.modId == submission.modId }
    }

    private fun TestScope.updateFromGameBanana(world: World, library: ModLibrary, submission: SubmissionRef, installed: ManifestRecord, bytes: ByteArray) {
        world.banana.listings = mapOf(submission to FileListing.Available("Lilith", listOf(remote(101, "8797b6cdb331371ef015e78a9f79bad9")), true))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to bytes))
        val listing = world.banana.listings.getValue(submission) as FileListing.Available
        val facts = FileFacts(0, null, null, files = listing.files.map { SubmissionFileOption(it.id, it.md5, it.fileName, it.description, it.sizeBytes) }, filesComplete = true)
        val targets = installed.sets.mapNotNull { (it.source as? InstalledSource.Remote)?.identity }.toSet()
        val plan = assertNotNull(InstalledUpdates.of(world.manifest().records, mapOf(submission to FilesObservation(FactsAnswer.Read(facts), targets))).getValue(installed.installId).plan)
        assertTrue(library.update(plan))
        runCurrent()
    }

    @Test
    fun anUpdatedSourceDropsTheRepairAndFreesItsOriginal() = runTest {
        val lilith = SubmissionRef(SubmissionKind.Mod, 551_201)
        val old = FakeVpk.directory(seed = 1)
        val world = World(emptyList(), emptyMap(), repairable = listOf(old))
        val library = with(world) { library() }
        val id = installFromGameBanana(world, library, lilith, old).installId
        repair(library, id)
        val repaired = world.record(id)
        val original = world.manifest().originals.single()

        updateFromGameBanana(world, library, lilith, repaired, FakeVpk.directory(seed = 2))
        assertNull(world.record(id).only.derivation, "the new source's files are its own, not a repair")
        assertEquals(repaired.numbers, world.record(id).numbers)
        assertEquals(emptyList(), world.manifest().originals, "an original no set comes from any more is freed")
        assertTrue(original.id in world.port.released)
    }

    @Test
    fun aModUpdatedUnderItsOwnNumbersChecksTheModsBelowItAgain() = runTest {
        val lilith = SubmissionRef(SubmissionKind.Mod, 551_201)
        val old = FakeVpk.directory(seed = 1)
        val new = FakeVpk.directory(seed = 2)
        val below = ManifestRecord("x", root, "Below", placedAt(2), sets = listOf(
            InstalledSet(source(900), null, null, SetOrigin.Downloaded(ArchiveIdentity("/downloads/x.zip", 10, "f".repeat(64))), null, "pak02_dir.vpk", listOf(owned(VpkPart.Directory, dirB))),
        ))
        val world = World(listOf(below), mapOf("$addons/pak02_dir.vpk" to dirB))
        world.port.findings[sha256Hex(dirB)] = listOf(FakeCompatibility.repairable(resource = model))
        world.port.supplies[sha256Hex(old)] = setOf(model)
        val library = with(world) { library() }
        val installed = installFromGameBanana(world, library, lilith, old)
        assertEquals(listOf(1), installed.numbers.map { it.value }, "the new mod loads first")
        assertEquals(emptyList(), library.compat("x").repairable, "the mod above supplies the model, so this one's copy does not load")

        updateFromGameBanana(world, library, lilith, installed, new)
        val updated = world.manifest().records.single { it.modId == lilith.modId }
        assertEquals(installed.numbers, updated.numbers, "the same numbers, other files")
        assertEquals(Sha256.image(new), world.files.addonsFiles(root)["pak01_dir.vpk"])
        assertEquals(listOf(model), library.compat("x").repairable.map { it.resource }, "what loads above changed, so the mod below was checked again")
    }

    @Test
    fun aSplitChunkChangedInPlaceIsNoticedWithoutAnyRecordChanging() = runTest {
        val world = World()
        val library = with(world) { library() }
        assertNull(library.compat("a").label(enabled = true))
        val theirs = ByteArray(chunkA.size) { 7 }
        world.port.findings[sha256Hex(theirs)] = listOf(FakeCompatibility.repairable())
        world.files.rewrite(root, "$addons/pak05_000.vpk", theirs)
        poll()
        assertEquals(CompatLabel.RepairAvailable, library.compat("a").label(enabled = true), "a chunk that no longer reads as it did makes the check stale")
        assertEquals(a, world.record("a"), "with automatic repair off, nothing is repaired or recorded on its own")
    }

    @Test
    fun aFileHideoutDoesNotOwnChangedInPlaceIsNoticed() = runTest {
        val foreign = ByteArray(9) { 1 }
        val world = World(files = placed + ("$addons/pak03_dir.vpk" to foreign))
        world.port.findings[sha256Hex(dirA)] = listOf(FakeCompatibility.repairable(resource = model))
        val library = with(world) { library() }
        assertEquals(CompatLabel.RepairAvailable, library.compat("a").label(enabled = true))
        val supplying = ByteArray(9) { 2 }
        world.port.supplies[sha256Hex(supplying)] = setOf(model)
        world.files.rewrite(root, "$addons/pak03_dir.vpk", supplying)
        poll()
        assertEquals(listOf(FindingKind.Unknown), library.compat("a").findingsOf(model), "a file nobody vouches for now supplies the model first")
        assertNull(library.compat("a").label(enabled = true))
    }

    @Test
    fun aFileMountedFirstWhileARepairIsMadeStopsItsPlacement() = runTest {
        val world = World()
        world.port.findings[sha256Hex(dirA)] = listOf(FakeCompatibility.repairable(resource = model))
        val library = with(world) { library() }
        val foreign = ByteArray(9) { 3 }
        world.port.supplies[sha256Hex(foreign)] = setOf(model)
        world.port.beforeDerive = {
            world.files.putBytes(root, "$addons/pak02_dir.vpk", foreign)
            world.port.beforeDerive = null
        }
        repair(library, "a")
        assertEquals(1, world.port.derived.size)
        assertEquals(a, world.record("a"), "the repair was made for what was mounted before, so it is not placed")
        assertEquals(listOf(FindingKind.Unknown), library.compat("a").findingsOf(model))
        assertNull(library.compat("a").problem)
    }

    @Test
    fun aGameStartedWhileARepairIsMadeWaitsAndTheRepairResumesWhenItCloses() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        world.port.beforeDerive = {
            world.processes.scan = ProcessScan.Seen(game = true)
            world.port.beforeDerive = null
        }
        repair(library, "a")
        assertEquals(a, world.record("a"))
        assertEquals(RepairWork.WaitingForGame(restoring = false), library.compat("a").pending)
        assertNull(library.compat("a").problem, "Deadlock running is not a failure")
        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertNotNull(world.record("a").sets.single().derivation, "the repair resumes once Deadlock is known closed")
        assertNull(library.compat("a").pending)
    }

    @Test
    fun aGameStartedWhileOriginalsAreStagedWaitsAndTheRestoreResumesWhenItCloses() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        repair(library, "a")
        world.port.beforeStage = {
            world.processes.scan = ProcessScan.Seen(game = true)
            world.port.beforeStage = null
        }
        library.useOriginalFiles("a")
        runCurrent()
        assertNotNull(world.record("a").sets.single().derivation)
        assertEquals(RepairWork.WaitingForGame(restoring = true), library.compat("a").pending)
        assertNull(library.compat("a").problem)
        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertEquals(a, world.record("a"), "the originals are put back once Deadlock is known closed")
    }

    @Test
    fun aFailureBelongsToTheFilesItWasForAndAnUpdateClearsIt() = runTest {
        val lilith = SubmissionRef(SubmissionKind.Mod, 551_201)
        val old = FakeVpk.directory(seed = 1)
        val new = FakeVpk.directory(seed = 2)
        val world = World(emptyList(), emptyMap())
        world.port.findings[sha256Hex(old)] = listOf(FakeCompatibility.repairable())
        world.port.refuseDerive = "the repaired model did not read back"
        val library = with(world) { library() }
        val installed = installFromGameBanana(world, library, lilith, old)
        repair(library, installed.installId)
        assertEquals("the repaired model did not read back", library.compat(installed.installId).problem)

        updateFromGameBanana(world, library, lilith, installed, new)
        assertNull(library.compat(installed.installId).problem, "the failure was for files the mod no longer has")
    }

    @Test
    fun aFailureEndsWhenTheModsFilesChange() = runTest {
        val world = World(repairable = listOf(dirA))
        world.port.refuseDerive = "the repaired model did not read back"
        val library = with(world) { library() }
        repair(library, "a")
        assertNotNull(library.compat("a").problem)
        world.port.refuseDerive = null
        library.setEnabled("b", false)
        runCurrent()
        assertNotNull(library.compat("a").problem, "another mod turning off changes what loads above nothing of this one's: the failure stands")
        val attempts = world.port.derived.size
        world.files.putBytes(root, "$addons/pak02_dir.vpk", ByteArray(5) { 4 })
        poll()
        assertTrue(world.port.derived.size > attempts, "a file mounted above it changes what its check is of, so it is tried again")
        assertNull(library.compat("a").problem)
        assertNotNull(world.record("a").sets.single().derivation)
    }

    @Test
    fun anOriginalNamedOnlyByAChangeInFlightIsNeverForgotten() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        repair(library, "a")
        val repaired = world.record("a")
        val original = world.manifest().originals.single()
        val updatedSet = a.only.copy(source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 100, 555, null)), files = listOf(owned(VpkPart.Directory, ByteArray(21) { 4 })))
        val updated = a.copy(sets = listOf(updatedSet))
        val token = ChangeToken("tok7")
        val cases = mapOf(
            "a repair in flight names it only as its target" to (listOf(a, b, c) to PendingChange.Replace(a, repaired, emptySet(), token, ReplaceStep.Publishing)),
            "a restore in flight names it only as its source" to (listOf(repaired, b, c) to PendingChange.Replace(repaired, a, emptySet(), token, ReplaceStep.Anchoring)),
            "a restore being cleaned up names it only as its source" to (listOf(a, b, c) to PendingChange.Replace(repaired, a, emptySet(), token, ReplaceStep.Cleanup)),
            "an update being cleaned up names it only as its source" to (listOf(updated, b, c) to PendingChange.Replace(repaired, updated, emptySet(), token, ReplaceStep.Cleanup)),
            "a move of the repaired mod" to (listOf(repaired, b, c) to PendingChange.Arrange(token, listOf(Move(repaired, repaired.placed(Placement.Off))), ProfileBook.main(listOf(repaired.placed(Placement.Off), b, c)), MoveStep.Anchoring)),
            "a committed repair" to (listOf(repaired, b, c) to null),
        )
        for ((case, setup) in cases) {
            val (records, pending) = setup
            val store = InMemoryLibraryStore(LibraryManifest(records = records, profiles = world.manifest().profiles.let { book ->
                book.copy(profiles = book.profiles.map { it.copy(enabled = records.filter { r -> r.enabled }.map { r -> r.installId }.toSet()) })
            }, originals = listOf(original), pending = pending))
            val ledger = LibraryLedger(store, world.files, InstallPorts(FakeGameBanana(), FakeDownloader(world.files), world.files, world.files, world.processes), ids = { "f1" }, preserve = {})
            ledger.reload()
            assertEquals(SaveResult.Saved to emptySet(), ledger.forgetOriginals(setOf(original.id)), case)
            assertEquals(listOf(original), assertNotNull(store.manifest).originals, case)
        }
    }

    @Test
    fun aRegistrationThatCannotBeConfirmedLeavesTheCopyItMayName() = runTest {
        val world = World(repairable = listOf(dirA))
        val flaky = FlakyStore(world.store)
        val library = with(world) { library(store = flaky) }
        world.store.afterStore = { saved ->
            if (saved.originals.isNotEmpty()) {
                world.store.afterStore = {}
                flaky.unreadableLoads = 1
                throw IllegalStateException("the disk went away after writing")
            }
        }
        repair(library, "a")
        val named = world.manifest().originals.single()
        assertTrue(named.id in world.files.originals, "the save landed, so the copy it names is kept")
        assertTrue(named.id !in world.port.released)
        assertEquals(a, world.record("a"), "nothing was placed while the list was in doubt")

        repair(library, "a")
        library.checkCompatibility()
        runCurrent()
        val set = world.record("a").sets.single()
        assertNotNull(set.derivation, "asked again once the list is read again, the repair goes on")
        assertEquals(world.manifest().originals.map { it.id }.toSet(), world.files.originals.keys, "every kept copy is named, and every named one kept")
    }

    @Test
    fun anOriginalIsForgottenBeforeItIsDeletedAndALeftoverCopyIsSwept() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        repair(library, "a")
        val id = world.manifest().originals.single().id

        world.store.refuse = { manifest -> "the disk is full".takeIf { manifest.originals.isEmpty() } }
        library.useOriginalFiles("a")
        runCurrent()
        assertEquals(a, world.record("a"))
        assertEquals(listOf(id), world.manifest().originals.map { it.id }, "forgetting it was not saved, so it stays named")
        assertTrue(id in world.files.originals, "and its copy stays")

        world.store.refuse = { null }
        world.port.releaseFails = "the copy is in use"
        library.checkCompatibility()
        runCurrent()
        assertEquals(emptyList(), world.manifest().originals, "forgotten first")
        assertTrue(id !in world.port.released, "its deletion failed")
        assertTrue(id !in world.files.originals, "and the copy nothing names is swept")
    }

    @Test
    fun noKeptOriginalIsSweptWhenTheInstallListIsMissing() = runTest {
        val world = World(records = emptyList())
        world.files.originals["kept"] = listOf(dirA)
        val library = with(world) { library(store = InMemoryLibraryStore()) }
        library.checkCompatibility()
        runCurrent()
        assertEquals(setOf("kept"), world.files.originals.keys, "it may be all that is left of a mod the lost list named")
    }

    @Test
    fun everyAcceptedRepairIsQueuedAtOnceAndOnlyTheOneUnderWayIsRepairing() = runTest {
        val world = World(repairable = listOf(dirA, dirB))
        val library = with(world) { library() }
        val derive = CompletableDeferred<Unit>()
        world.port.beforeDerive = { derive.await() }
        library.useRepairedFiles(setOf("a", "b"))
        assertEquals(RepairWork.Queued(restoring = false), library.compat("a").pending, "accepted, before the worker has run")
        assertEquals(RepairWork.Queued(restoring = false), library.compat("b").pending)
        runCurrent()
        assertEquals(RepairWork.Repairing, library.compat("a").pending)
        assertEquals(RepairWork.Queued(restoring = false), library.compat("b").pending, "the next one waits its turn, visibly")
        assertEquals(CompatLabel.RepairAvailable, library.compat("b").label(enabled = true), "what its check found stays shown")
        derive.complete(Unit)
        runCurrent()
        for (id in listOf("a", "b")) {
            assertNull(library.compat(id).pending, id)
            assertEquals(CompatLabel.RepairApplied, library.compat(id).label(enabled = true), id)
        }
        assertEquals(listOf("a", "b"), world.port.derived.distinct(), "repaired in load order, as before")
    }

    @Test
    fun aPlacedRepairIsVerifyingUntilACheckOfItsOwnFilesLands() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        val check = CompletableDeferred<Unit>()
        world.port.beforeCheck = { record -> if (record.sets.any { it.derivation != null }) check.await() }
        repair(library, "a")
        assertNotNull(world.record("a").sets.single().derivation, "the repaired files are placed")
        val install = library.compat("a")
        assertEquals(RepairWork.Verifying(restoring = false), install.pending, "and checked before anything is said of them")
        assertNull(install.check, "no check of the new files has landed")
        assertNull(install.label(enabled = true), "nothing is called applied from a check of other files")
        check.complete(Unit)
        runCurrent()
        assertNull(library.compat("a").pending)
        assertEquals(CompatLabel.RepairApplied, library.compat("a").label(enabled = true))
    }

    @Test
    fun aRepairWhosePlanAnEarlierRepairMadeStaleStaysQueuedWhileItIsCheckedAgain() = runTest {
        val world = World(repairable = listOf(dirA, dirB))
        val library = with(world) { library() }
        val recheck = CompletableDeferred<Unit>()
        world.port.beforeCheck = { record -> if (record.installId == "b" && world.record("a").sets.single().derivation != null) recheck.await() }
        repair(library, "a", "b")
        assertEquals(listOf("a", "b"), world.port.derived, "b's plan was made while a still had its old files")
        assertNull(world.record("b").sets.single().derivation, "so it was not placed")
        assertEquals(RepairWork.Queued(restoring = false), library.compat("b").pending, "it stays queued while it is checked again")
        assertNull(library.compat("b").problem, "a stale plan is not a failure")
        assertEquals(CompatLabel.RepairApplied, library.compat("a").label(enabled = true))
        recheck.complete(Unit)
        runCurrent()
        assertNotNull(world.record("b").sets.single().derivation)
        assertNull(library.compat("b").pending)
        assertEquals(CompatLabel.RepairApplied, library.compat("b").label(enabled = true))
    }

    @Test
    fun aRepairThatEndsAfterTheUserChoseTheOriginalFilesLeavesTheNewerChoiceAlone() = runTest {
        val world = World(repairable = listOf(dirA))
        world.port.refuseDerive = "the repaired model did not read back"
        val library = with(world) { library() }
        val derive = CompletableDeferred<Unit>()
        world.port.beforeDerive = { derive.await() }
        repair(library, "a")
        assertEquals(RepairWork.Repairing, library.compat("a").pending)
        library.useOriginalFiles("a")
        derive.complete(Unit)
        runCurrent()
        val install = library.compat("a")
        assertEquals(RepairChoice.Original, install.choice)
        assertNull(install.problem, "the repair it replaced failing is not this choice's failure")
        assertNull(install.pending)
        assertEquals(a, world.record("a"))
    }

    @Test
    fun repairsAcceptedWhileDeadlockRunsAreSavedAndQueuedThenWaitForItToClose() = runTest {
        val world = World(repairable = listOf(dirA, dirB))
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        library.useRepairedFiles(setOf("a", "b"))
        assertEquals(RepairWork.Queued(restoring = false), library.compat("a").pending)
        assertEquals(RepairWork.Queued(restoring = false), library.compat("b").pending)
        runCurrent()
        assertEquals(RepairChoice.Repaired, world.manifest().repairChoices["a"], "what was asked for is saved")
        assertEquals(RepairWork.WaitingForGame(restoring = false), library.compat("a").pending)
        assertEquals(RepairWork.WaitingForGame(restoring = false), library.compat("b").pending)
        assertEquals(listOf(a, b, c), world.manifest().records, "no file the game loads changes")
        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(3.seconds)
        runCurrent()
        for (id in listOf("a", "b")) {
            assertNotNull(world.record(id).sets.single().derivation, id)
            assertNull(library.compat(id).pending, id)
        }
    }

    @Test
    fun aCheckOfTheRepairedFilesThatFailsEndsTheVerificationAndSaysWhy() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        world.port.beforeCheck = { record -> if (record.sets.single().derivation != null) world.port.failing["a"] = "the repaired model could not be read" }
        repair(library, "a")
        val install = library.compat("a")
        assertNull(install.pending, "a failed check is an answer too")
        assertEquals("the repaired model could not be read", install.failed)
        assertNull(install.label(enabled = true), "and nothing is called applied")
        assertNotNull(world.record("a").sets.single().derivation)
    }

    @Test
    fun workForAModUninstalledWhileItWasRepairedEndsAndHoldsNothingUp() = runTest {
        val world = World(repairable = listOf(dirA))
        val library = with(world) { library() }
        val derive = CompletableDeferred<Unit>()
        world.port.beforeDerive = { derive.await() }
        repair(library, "a")
        assertEquals(RepairWork.Repairing, library.compat("a").pending)
        library.uninstall(assertNotNull(library.uninstallConfirmation("a")), deleteDownload = false)
        runCurrent()
        derive.complete(Unit)
        runCurrent()
        assertTrue(world.manifest().records.none { it.installId == "a" })
        assertNull(library.state.value.compatibility.installs["a"])
    }

    @Test
    fun repairsAskedForWhileAnUnfinishedChangeWaitsWaitWithIt() = runTest {
        val change = PendingChange.Arrange(ChangeToken("tok9"), listOf(Move(a, a.placed(Placement.Off))), ProfileBook.main(listOf(a.placed(Placement.Off), b, c)), MoveStep.Anchoring)
        val world = World()
        world.store.save(LibraryManifest(records = listOf(a, b, c), pending = change))
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        repair(library, "a")
        assertEquals(RepairWork.WaitingForGame(restoring = false), library.compat("a").pending, "the request is kept and shown waiting")
        assertEquals(RepairChoice.Repaired, world.manifest().repairChoices["a"])
    }

    private suspend fun TestScope.repairedPair(world: World, scope: CoroutineScope = backgroundScope): ModLibrary {
        world.port.findings[sha256Hex(dirA)] = listOf(FakeCompatibility.repairable())
        world.port.findings[sha256Hex(dirB)] = listOf(FakeCompatibility.repairable())
        val library = with(world) { library(scope) }
        repair(library, "a", "b")
        for (id in listOf("a", "b")) assertEquals(CompatStatus.Found(CompatLabel.RepairApplied), library.compat(id).status(enabled = true), id)
        return library
    }

    private fun TestScope.deadlock(world: World, scan: ProcessScan) {
        world.processes.scan = scan
        advanceTimeBy(testWatchTiming.processPoll + 1.seconds)
        runCurrent()
    }

    @Test
    fun aGameUpdateWhileDeadlockRunsShowsEachChosenRepairWaitingAsItsCheckLandsThenRemakesIt() = runTest {
        val world = World()
        val library = repairedPair(world)
        val derived = world.port.derived.size
        world.port.game = "build:updated"
        deadlock(world, ProcessScan.Seen(game = true))
        val held = CompletableDeferred<Unit>()
        world.port.beforeCheck = { record -> if (record.installId == "b") held.await() }
        poll()
        val first = library.compat("a")
        assertEquals("build:updated", first.check?.basis?.game, "a's check of the updated game landed")
        assertTrue(library.compat("b").checking, "b's check is still held")
        assertEquals(CompatStatus.Changing(RepairWork.WaitingForGame(restoring = false)), first.status(enabled = true), "a repair the user chose waits for Deadlock; it is not offered again")
        assertEquals(derived, world.port.derived.size, "nothing is made while Deadlock runs")

        held.complete(Unit)
        runCurrent()
        for (id in listOf("a", "b")) assertEquals(CompatStatus.Changing(RepairWork.WaitingForGame(restoring = false)), library.compat(id).status(enabled = true), id)
        assertEquals(derived, world.port.derived.size)

        deadlock(world, ProcessScan.Seen())
        for (id in listOf("a", "b")) {
            assertEquals(CompatStatus.Found(CompatLabel.RepairApplied), library.compat(id).status(enabled = true), id)
            assertEquals("build:updated", world.record(id).sets.single().derivation?.context, "$id is revalidated for the game as it is now")
        }
        assertEquals(derived + 2, world.port.derived.size, "both repairs were made again")
    }

    @Test
    fun aGameUpdateWhileDeadlockIsClosedQueuesEachChosenRepairAsItsCheckLands() = runTest {
        val world = World()
        val library = repairedPair(world)
        val derived = world.port.derived.size
        world.port.game = "build:updated"
        val held = CompletableDeferred<Unit>()
        world.port.beforeCheck = { record -> if (record.installId == "b") held.await() }
        poll()
        assertEquals("build:updated", library.compat("a").check?.basis?.game)
        assertEquals(CompatStatus.Changing(RepairWork.Queued(restoring = false)), library.compat("a").status(enabled = true), "a repair the user chose is queued, not offered again")
        assertEquals(derived, world.port.derived.size, "repairs start once every check landed, as before")
        held.complete(Unit)
        runCurrent()
        for (id in listOf("a", "b")) {
            assertEquals(CompatStatus.Found(CompatLabel.RepairApplied), library.compat(id).status(enabled = true), id)
            assertEquals("build:updated", world.record(id).sets.single().derivation?.context, id)
        }
        assertEquals(derived + 2, world.port.derived.size)
    }

    @Test
    fun anUnchangedGameStartingAndClosingOrHideoutRestartingNeitherOffersNorRemakesARepair() = runTest {
        val world = World()
        val running = crashableScope()
        val library = repairedPair(world, running)
        val derived = world.port.derived.size
        val records = world.manifest().records
        deadlock(world, ProcessScan.Seen(game = true))
        poll()
        deadlock(world, ProcessScan.Seen())
        poll()
        for (id in listOf("a", "b")) assertEquals(CompatStatus.Found(CompatLabel.RepairApplied), library.compat(id).status(enabled = true), id)
        running.cancel()
        val restarted = with(world) { library() }
        runCurrent()
        for (id in listOf("a", "b")) assertEquals(CompatStatus.Found(CompatLabel.RepairApplied), restarted.compat(id).status(enabled = true), "$id after a restart")
        assertEquals(derived, world.port.derived.size, "nothing was made again")
        assertEquals(records, world.manifest().records, "and no file changed")
    }

    @Test
    fun aFailureForTheGameBeforeAnUpdateEndsAsTheUpdatedGamesCheckLands() = runTest {
        val world = World(repairable = listOf(dirA, dirB))
        world.port.refuseDerive = "the repaired model did not read back"
        val library = with(world) { library() }
        repair(library, "a", "b")
        assertEquals(CompatStatus.Unfinished(restoring = false), library.compat("a").status(enabled = true))
        world.port.refuseDerive = null
        world.port.game = "build:updated"
        val held = CompletableDeferred<Unit>()
        world.port.beforeCheck = { record -> if (record.installId == "b") held.await() }
        poll()
        assertEquals("build:updated", library.compat("a").check?.basis?.game)
        assertEquals(CompatStatus.Changing(RepairWork.Queued(restoring = false)), library.compat("a").status(enabled = true), "the failure was for the game as it was; the repair asked for is queued for the updated one")
        assertEquals(CompatStatus.Unfinished(restoring = false), library.compat("b").status(enabled = true), "b's failure stands until its own check lands")
        held.complete(Unit)
        runCurrent()
        for (id in listOf("a", "b")) assertEquals(CompatStatus.Found(CompatLabel.RepairApplied), library.compat(id).status(enabled = true), id)
    }

    @Test
    fun aRepairThatCannotBeRemadeAfterAGameUpdateSaysSoAndIsNotHiddenByQueuedWork() = runTest {
        val world = World()
        val library = repairedPair(world)
        world.port.game = "build:updated"
        world.port.refuseDerive = "the repaired model did not read back"
        poll()
        for (id in listOf("a", "b")) {
            val install = library.compat(id)
            assertEquals(CompatStatus.Unfinished(restoring = false), install.status(enabled = true), id)
            assertEquals("the repaired model did not read back", install.problem, id)
            assertEquals("build:fake", world.record(id).sets.single().derivation?.context, "$id keeps the repair it had")
        }
        val tries = world.port.derived.size
        poll()
        assertEquals(tries, world.port.derived.size, "a failure is not retried on its own")
        assertEquals(CompatStatus.Unfinished(restoring = false), library.compat("a").status(enabled = true))
    }

    @Test
    fun checkingAgainKeepsWhatWasFoundShownUntilTheNewChecksLand() = runTest {
        val world = World(repairable = listOf(dirA))
        world.port.findings[sha256Hex(dirB)] = listOf(FakeCompatibility.needsAuthor())
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.port.beforeCheck = { gate.await() }
        library.checkCompatibility()
        runCurrent()
        assertEquals(CompatLabel.RepairAvailable, library.compat("a").label(enabled = true), "what was found stays shown while it is checked again")
        assertEquals(CompatLabel.NeedsAuthorUpdate, library.compat("b").label(enabled = true))
        assertTrue(library.compat("a").checking)
        world.port.findings[sha256Hex(dirB)] = emptyList()
        gate.complete(Unit)
        runCurrent()
        assertNull(library.compat("b").label(enabled = true))
    }
}
