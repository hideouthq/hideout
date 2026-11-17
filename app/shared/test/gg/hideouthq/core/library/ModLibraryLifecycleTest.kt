package gg.hideouthq.core.library

import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SimulatedCrash
import gg.hideouthq.core.data.BackupStore
import gg.hideouthq.core.data.GameEntry
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryLifecycleTest {
    private val root = GameRoot("/games/Deadlock")
    private val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)

    private val dir = FakeVpk.directory(embeddedBytes = 4, chunkReads = mapOf(0 to 30L, 2 to 20L), seed = 2)
    private val chunk0 = ByteArray(30) { 3 }
    private val chunk2 = ByteArray(20) { 4 }
    private val content = mapOf(VpkPart.Directory to dir, VpkPart.Chunk(0) to chunk0, VpkPart.Chunk(2) to chunk2)
    private val foreign = mapOf("pak01_dir.vpk" to ByteArray(9) { 7 }, "my_own_skin.vpk" to ByteArray(5) { 8 })

    private val archive = ArchiveIdentity("/data/hideout/downloads/mod-628739/9/ivy.zip", 1_000, "9".repeat(64))
    private val addons = SetFolder.Addons
    private val parked = SetFolder.Off(ivy)

    private fun record(number: Int?) = oneSetRecord(
        installId = "ivy",
        gameRoot = root,
        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, ivy.id, 9, null)),
        title = "Bubblegum ivy",
        variant = "Pink",
        archive = archive,
        number = number,
        files = content.map { (part, bytes) -> SetFile(part, Sha256.image(bytes)) },
    )

    private fun ManifestRecord.edited(part: VpkPart, bytes: ByteArray) = withOnly { copy(edits = mapOf(part to Sha256.image(bytes))) }

    private val bytes = record(3).files.associate { it.name to content.getValue(it.part) }

    private fun off(part: VpkPart) = record(null).files.single { it.part == part }.name

    private val chunkAt03 = "${addons.path}/pak03_000.vpk"
    private val chunkAnchor = "pak03_000.vpk.hideout-id1"
    private val otherChunk = ByteArray(30) { 6 }

    private inner class World(enabled: Boolean = true, registered: Boolean = true, extra: List<ManifestRecord> = emptyList(), private val backedUp: Boolean = false) : CrashWorld {
        private val start = record(if (enabled) 3 else null)
        override val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, VanillaGameInfo)
            start.files.forEach { putBytes(root, it.relativePath(start), content.getValue(it.part)) }
            foreign.forEach { (name, content) -> putBytes(root, "${addons.path}/$name", content) }
            putArchive(archive.path, listOf("ivy.vpk" to dir), FileImage(archive.sizeBytes, archive.sha256))
        }
        override val store = InMemoryLibraryStore(
            LibraryManifest(
                records = listOf(start) + extra,
                downloads = listOfNotNull(OwnedDownload(start.remoteIdentity, archive).takeIf { registered }),
            ),
        )
        val disk = FlakyStore(store)
        val processes = FakeProcesses()
        val locator = FixedGameLocator(listOf(root))
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        var archives: Downloader = downloader
        val logged = mutableListOf<String>()
        private var nextId = 0

        val identities: Map<VpkPart, Int?> = start.files.associate { it.part to files.identity(root, it.relativePath(start)) }
        val foreignBefore = foreign.keys.associateWith { files.identity(root, "${addons.path}/$it") }

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(
                locator, Preferences(MemoryStorage()), disk, files,
                InstallPorts(banana, archives, files, files, processes, backups = if (backedUp) files.backups else BackupStore.Unavailable, log = { logged += it }), scope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
            ),
        )

        override fun TestScope.open(scope: CoroutineScope) = library(scope)

        fun inFolder(folder: SetFolder): Map<String, FileImage> = files.filesIn(root, folder)

        fun ours(folder: SetFolder): Set<String> = inFolder(folder).keys.filter { it !in foreign && TemporaryInfix !in it }.toSet()

        fun anchors(): Set<String> = (inFolder(addons).keys + inFolder(parked).keys).filter { TemporaryInfix in it }.toSet()

        fun identity(folder: SetFolder, name: String) = files.identity(root, "${folder.path}/$name")

        fun manifest() = assertNotNull(store.manifest)

        fun stored() = manifest().records.singleOrNull { it.installId == "ivy" }

        fun assertChunkAnchor(replaced: Boolean, case: String) {
            if (replaced) {
                assertEquals(mapOf(chunkAnchor to Sha256.image(chunk0)), inFolder(addons).filterKeys { TemporaryInfix in it }, "$case: the only copy of the chunk stays under its anchor")
                assertEquals(identities[VpkPart.Chunk(0)], identity(addons, chunkAnchor), "$case: as the very file Hideout took over")
                assertTrue(logged.any { "${addons.path}/$chunkAnchor" in it }, "$case: and is reported left in place")
            } else {
                assertEquals(emptySet(), anchors(), "$case: an anchor one file with the chunk goes, whatever the chunk holds now")
                assertTrue(logged.none { TemporaryInfix in it }, "$case: and no anchor is reported")
            }
        }

        fun assertForeignUntouched() {
            foreign.forEach { (name, content) ->
                assertEquals(Sha256.image(content), inFolder(addons)[name], name)
                assertEquals(foreignBefore[name], identity(addons, name), "$name is the same file")
            }
        }

        fun assertWhole(number: Int?) {
            val placed = record(number)
            val (here, there) = if (placed.enabled) addons to parked else parked to addons
            assertNull(manifest().pending)
            assertEquals(placed, stored())
            assertEquals(placed.files.map { it.name }.toSet(), ours(here))
            placed.files.forEach { file ->
                assertEquals(Sha256.image(content.getValue(file.part)), inFolder(here)[file.name], file.name)
                assertEquals(identities[file.part], identity(here, file.name), "${file.name} keeps its identity")
            }
            assertEquals(emptySet(), ours(there))
            assertEquals(emptySet(), anchors())
            assertForeignUntouched()
        }

        override fun check(point: FaultPoint) {
            val mounted = ours(addons)
            for (directory in mounted.filter { it.endsWith("_dir.vpk") }) {
                val stem = directory.removeSuffix("_dir.vpk")
                assertTrue(mounted.containsAll(listOf("${stem}_000.vpk", "${stem}_002.vpk")), "at $point addons had $mounted")
            }
            assertTrue(mounted.none { it.startsWith("set-") }, "at $point addons had $mounted")
            assertTrue(ours(parked).none { it.startsWith("pak") }, "at $point ${parked.path} had ${ours(parked)}")
        }

        fun watchMounts(also: (FaultPoint) -> Unit) {
            files.around = { point ->
                check(point)
                also(point)
            }
        }
    }

    private fun ModLibrary.change(): SetChange? = state.value.changes["ivy"]

    private fun ModLibrary.failed(): LibraryProblem? = (state.value.choiceSync as? ChoiceSync.Failed)?.problem

    private fun ModLibrary.settled(): Boolean = state.value.choiceSync == ChoiceSync.Settled

    private fun ModLibrary.ready(): LibraryStatus.Ready = assertIs<LibraryStatus.Ready>(state.value.library)

    private fun TestScope.switch(library: ModLibrary, enabled: Boolean) {
        library.setEnabled("ivy", enabled)
        runCurrent()
    }

    private fun TestScope.uninstall(library: ModLibrary, deleteDownload: Boolean = false) {
        library.uninstall(assertNotNull(library.uninstallConfirmation("ivy")), deleteDownload)
        runCurrent()
    }

    @Test
    fun turningOffMovesTheWholeSetDirectoryFirstWithoutCopyingAndFreesItsNumber() = runTest {
        val world = World(enabled = true)
        val library = with(world) { library() }
        switch(library, false)

        world.assertWhole(null)
        assertEquals(
            listOf("pak03_dir.vpk", "pak03_000.vpk", "pak03_002.vpk").map { "${addons.path}/$it" },
            world.files.removedPaths.filter { it.startsWith(addons.path) && TemporaryInfix !in it },
            "the directory leaves addons first",
        )
        assertTrue(library.settled())
        assertNull(world.manifest().savedChoices)
        assertEquals(listOf(false), library.ready().records.filter { it.installId == "ivy" }.map { it.enabled })
        assertEquals(emptyMap(), library.ready().damaged)
    }

    @Test
    fun turningOnLinksChunksFirstAndTheDirectoryLastUnderTheLowestFreeNumberAndLoadsMods() = runTest {
        val world = World(enabled = false)
        val library = with(world) { library() }
        switch(library, true)

        world.assertWhole(2)
        assertEquals(
            listOf("pak02_000.vpk", "pak02_002.vpk", "pak02_dir.vpk").map { "${addons.path}/$it" },
            world.files.linkedPaths.filter { it.startsWith(addons.path) && TemporaryInfix !in it },
            "pak01 is foreign",
        )
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(world.files.gameInfo(root)!!))
    }

    @Test
    fun anOffModHoldsNoNumberSoANewInstallMayTakeTheOneItHeld() = runTest {
        val world = World(enabled = true)
        world.files.putBytes(root, "${addons.path}/pak02_dir.vpk", ByteArray(6))
        val library = with(world) { library() }
        switch(library, false)
        world.banana.listings = mapOf(SubmissionRef(SubmissionKind.Mod, 5) to FileListing.Available("Other", listOf(RemoteFile(77, "o.zip", 10, null, null, 1, 1, "https://x/77"))))
        world.downloader.serve(77, listOf("o.vpk" to FakeVpk.directory(seed = 5)))
        library.install("mod-5")
        runCurrent()
        assertEquals(listOf(null, 3), world.manifest().records.map { it.numbers.firstOrNull()?.value }, "pak01 and pak02 are foreign")
    }

    @Test
    fun turningOnPlansAroundAnythingElseAtANumberInAddonsAndLeavesItThere() = runTest {
        for ((name, content) in listOf("pak02_dir.vpk" to dir, "pak02_000.vpk" to ByteArray(1), "pak02_001.vpk" to ByteArray(1), "PAK02_extra.txt" to ByteArray(1))) {
            val world = World(enabled = false)
            world.files.putBytes(root, "${addons.path}/$name", content)
            val placed = world.identity(addons, name)
            val library = with(world) { library() }
            switch(library, true)

            assertTrue(library.settled(), name)
            assertEquals(record(3), world.stored(), "$name: 02 is taken, so ivy takes 03")
            assertEquals(placed, world.identity(addons, name), "$name: the occupant, same bytes or not, stays")
            assertEquals(foreign.keys + name + record(3).files.map { it.name }, world.inFolder(addons).keys, name)
            assertEquals(emptyMap(), world.inFolder(parked), name)
        }
    }

    @Test
    fun turningOffAModWhoseFileChangedHoldsItWhereItIsAndAMissingFileIsRefusedWithNothingMoved() = runTest {
        val changed = World(enabled = true)
        changed.files.rewrite(root, "${addons.path}/pak03_000.vpk", ByteArray(30) { 9 })
        val library = with(changed) { library() }
        switch(library, false)
        assertNull(library.failed(), "the edit holds the mod where it is, and nothing waits on it")
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(mapOf<VpkPart, FileImage>(VpkPart.Chunk(0) to Sha256.image(ByteArray(30) { 9 })), changed.store.manifest!!.records.single().sets.single().edits)
        assertEquals(emptySet(), changed.store.manifest!!.savedChoices?.target?.activeProfile?.enabled, "the choice is saved, for when the user decides")
        assertEquals(emptyList(), changed.files.linkedPaths)
        assertEquals(setOf("pak03_dir.vpk", "pak03_000.vpk", "pak03_002.vpk"), changed.ours(addons))

        val missing = World(enabled = true)
        missing.files.remove(root, "${addons.path}/pak03_002.vpk")
        val other = with(missing) { library() }
        switch(other, false)
        assertEquals(LibraryProblem.FileMissing("${addons.path}/pak03_002.vpk"), other.failed())
        assertEquals(emptyList(), missing.files.linkedPaths)
    }

    @Test
    fun aRunningOrUnknownGameSavesTheSwitchButRefusesUninstallAndTouchesNoFileUntilItIsKnownClosed() = runTest {
        for (scan in listOf(ProcessScan.Seen(game = true), ProcessScan.Unknown("no /proc"))) {
            val world = World(enabled = true)
            world.processes.scan = scan
            val library = with(world) { library() }
            switch(library, false)
            assertEquals(ChoiceSync.Waiting(if (scan is ProcessScan.Seen) GameActivity.Running else GameActivity.Unknown), library.state.value.choiceSync)
            assertEquals(emptySet(), world.store.manifest!!.savedChoices?.target?.activeProfile?.enabled, "the choice is saved")
            assertEquals(setOf("ivy"), world.store.manifest!!.profiles.activeProfile.enabled, "the files are not")
            val saves = world.store.saved.size
            uninstall(library)
            assertEquals(ChangeStage.Refused(if (scan is ProcessScan.Seen) LibraryProblem.GameRunning else LibraryProblem.ProcessesUnknown("no /proc")), library.change()?.stage)
            assertEquals(saves, world.store.saved.size)
            assertEquals(emptyList(), world.files.linkedPaths)
            assertEquals(emptyList(), world.files.removedPaths)
            world.processes.scan = ProcessScan.Seen()
            world.assertWhole(3)
            advanceTimeBy(2.seconds)
            runCurrent()
            world.assertWhole(null)
            assertTrue(library.settled())
        }
    }

    @Test
    fun theGameStartingMidMoveStopsAtTheNextFileAndTheMoveFinishesOnceItStops() = runTest {
        val world = World(enabled = false)
        world.files.around = { point -> if (point == FaultPoint.LinkMade("${addons.path}/pak02_000.vpk")) world.processes.scan = ProcessScan.Seen(game = true) }
        val library = with(world) { library() }
        switch(library, true)

        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync, "a fence is not a failure")
        val pending = assertIs<PendingChange.Arrange>(world.manifest().pending)
        assertEquals(MoveStep.Linking, pending.step)
        assertEquals(setOf("pak02_000.vpk"), world.ours(addons), "only the first chunk was linked; the directory never was")
        assertEquals(record(null).files.map { pending.token.anchorName(it) }.toSet(), world.inFolder(parked).keys, "every source went, its anchor stays")

        world.files.around = null
        world.processes.scan = ProcessScan.Seen()
        val restarted = with(world) { library() }
        world.assertWhole(2)
        assertTrue(restarted.settled(), "the move was finished once the game stopped")
    }

    @Test
    fun theGameStartingWhileSourcesGoLeavesTheMoveToBeFinished() = runTest {
        val world = World(enabled = true)
        world.files.around = { point -> if (point == FaultPoint.RemoveDone("${addons.path}/pak03_dir.vpk")) world.processes.scan = ProcessScan.Seen(game = true) }
        val library = with(world) { library() }
        switch(library, false)

        assertEquals(MoveStep.Releasing, assertIs<PendingChange.Arrange>(world.manifest().pending).step)
        assertEquals(setOf("pak03_000.vpk", "pak03_002.vpk"), world.ours(addons), "the directory went first")
        assertEquals("Deadlock is running", library.ready().interrupted)

        world.files.around = null
        world.processes.scan = ProcessScan.Seen()
        with(world) { library() }
        world.assertWhole(null)
    }

    @Test
    fun anUninstallLeavesNoBackedUpCopyThatNothingNeeds() = runTest {
        val world = World(enabled = true, backedUp = true)
        val library = with(world) { library() }
        runCurrent()
        val ivy = assertNotNull(world.stored())
        ivy.files.forEach { world.files.backups.keep(GameEntry(root, addons, it.name), it.image) }
        val images = ivy.files.map { it.image }
        assertTrue(images.all { world.files.backups.has(root, it) })
        uninstall(library)
        assertNull(world.stored())
        assertEquals(emptyList(), library.state.value.backups.map { it.id }, "no backup names them")
        assertTrue(images.none { world.files.backups.has(root, it) }, "the twins of an uninstalled mod's files go with it")
    }

    @Test
    fun aGameFolderRenamedAwayShowsAsNotFoundAndHealsWithoutAClickWhenItComesBack() = runTest {
        val world = World(enabled = true)
        val library = with(world) { library() }
        world.locator.valid = false
        advanceTimeBy(testWatchTiming.processPoll * 2)
        runCurrent()
        assertIs<GameStatus.Missing>(library.state.value.game, "Settings no longer says the game is ready")

        switch(library, false)
        assertEquals(LibraryProblem.GameNotFound, library.failed())

        world.locator.valid = true
        advanceTimeBy(testWatchTiming.processPoll * 2)
        runCurrent()
        assertEquals(GameStatus.Ready(root, FoundVia.Steam), library.state.value.game)
        assertTrue(library.settled(), "the choice made meanwhile applies once the folder is back")
        world.assertWhole(null)
    }

    @Test
    fun theGameFolderDisappearingMidMoveStopsBeforeTheNextFile() = runTest {
        val world = World(enabled = true)
        val chunk = off(VpkPart.Chunk(0))
        world.files.around = { point -> if (point == FaultPoint.LinkMade("${parked.path}/$chunk")) world.locator.valid = false }
        val library = with(world) { library() }
        switch(library, false)
        assertEquals(LibraryProblem.Interrupted("the game folder /games/Deadlock is not available"), library.failed())
        assertEquals(setOf(chunk), world.ours(parked))

        world.files.around = null
        world.locator.valid = true
        val restarted = with(world) { library() }
        runCurrent()
        world.assertWhole(null)
        assertTrue(restarted.settled(), "a start tries a failed apply again")
    }

    private fun TestScope.crashAtEveryStep(enabled: Boolean, running: Boolean = false, act: CoroutineScope.(ModLibrary) -> Unit, converged: (World) -> Unit) {
        fun world() = World(enabled = enabled).apply { if (running) processes.scan = ProcessScan.Seen(game = true) }
        val trace = traced(world(), act)
        assertTrue(trace.points.size > 20 && trace.saves >= 4, "the trace has ${trace.points} and ${trace.saves} saves")
        crashAtEveryStep(trace, ::world, act) { world, _ -> converged(world) }
    }

    @Test
    fun aCrashAtAnyStepOfTurningOffConvergesWithTheSetWholeInOneFolder() = runTest {
        crashAtEveryStep(enabled = true, act = { switch(it, false) }) { world ->
            world.assertWhole(if (world.stored()!!.enabled) 3 else null)
        }
    }

    @Test
    fun aCrashAtAnyStepOfTurningOnConvergesWithTheSetWholeInOneFolder() = runTest {
        crashAtEveryStep(enabled = false, act = { switch(it, true) }) { world ->
            world.assertWhole(if (world.stored()!!.enabled) 2 else null)
        }
    }

    @Test
    fun aRefusedSaveOfReleasingUndoesTheLinksAndARefusedTargetSaveWaitsWithEveryAnchor() = runTest {
        val world = World(enabled = true)
        world.store.refuse = { if ((it.pending as? PendingChange.Arrange)?.step == MoveStep.Releasing) "disk full" else null }
        val library = with(world) { library() }
        switch(library, false)
        assertEquals(LibraryProblem.ManifestSave("disk full"), library.failed())
        world.assertWhole(3)

        val later = World(enabled = true)
        later.store.refuse = { if ((it.pending as? PendingChange.Arrange)?.step == MoveStep.Cleanup) "disk full" else null }
        val other = with(later) { library() }
        switch(other, false)
        assertEquals(LibraryProblem.ManifestSave("disk full"), other.failed())
        assertEquals(MoveStep.Linking, assertIs<PendingChange.Arrange>(later.manifest().pending).step)
        assertEquals(bytes.keys.map { "$it.hideout-id1" }.toSet(), later.anchors(), "every anchor stays until the target record is saved")
        assertEquals(emptySet(), later.ours(addons))

        later.store.refuse = { null }
        other.retryChoices()
        runCurrent()
        later.assertWhole(null)
        assertTrue(other.settled())
    }

    @Test
    fun aTargetChangedBeforeItsAnchorGoesKeepsTheAnchorAndTheSwitchStillSettles() = runTest {
        val target = "${parked.path}/${off(VpkPart.Directory)}"
        val stranger = ByteArray(dir.size) { 1 }
        for (replaced in listOf(false, true)) for (crashed in listOf(false, true)) {
            val case = "replaced=$replaced crashed=$crashed"
            val world = World(enabled = true)
            fun damage() = if (replaced) world.files.putBytes(root, target, stranger) else world.files.remove(root, target)
            val library = if (crashed) {
                world.store.afterStore = { if ((it.pending as? PendingChange.Arrange)?.step == MoveStep.Cleanup) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
                val doomed = with(world) { crashableScope() }
                switch(with(world) { library(doomed) }, false)
                doomed.cancel()
                world.store.afterStore = {}
                damage()
                with(world) { library() }
            } else {
                world.store.afterStore = { if ((it.pending as? PendingChange.Arrange)?.step == MoveStep.Cleanup) damage() }
                with(world) { library() }.also { switch(it, false); world.store.afterStore = {} }
            }

            assertNull(world.manifest().pending, case)
            assertEquals(if (replaced) record(null).edited(VpkPart.Directory, stranger) else record(null), world.stored(), case)
            assertEquals(setOf("pak03_dir.vpk.hideout-id1"), world.anchors(), case)
            assertEquals(world.identities[VpkPart.Directory], world.identity(addons, "pak03_dir.vpk.hideout-id1"), "$case: the mod's own directory stays under its anchor, its only copy")
            assertTrue(world.logged.any { "${addons.path}/pak03_dir.vpk.hideout-id1" in it }, "$case: and is reported left in place")
            assertEquals(mapOf("ivy" to if (replaced) FilesDamage.Changed else FilesDamage.Missing), library.ready().damaged, case)
            assertTrue(library.settled(), case)

            switch(library, true)
            library.retryChoices()
            runCurrent()
            if (replaced) {
                assertNull(library.failed(), "$case: turning on again waits for the user to decide on the edit")
                assertEquals(record(null).edited(VpkPart.Directory, stranger), world.stored(), case)
            } else {
                assertEquals(LibraryProblem.FileMissing(target), library.failed(), "$case: turning on again is refused, not stuck")
            }
            assertNull(world.manifest().pending, case)
            if (replaced) assertContentEquals(stranger, world.files.bytes(root, target), case)
            world.assertForeignUntouched()
        }
    }

    @Test
    fun aDriveWithoutHardLinksTurnsAModOffAndOnAgainByCopyingIt() = runTest {
        val world = World(enabled = true)
        world.files.hardLinks = false
        val library = with(world) { library() }
        fun assertIn(here: SetFolder, there: SetFolder) {
            assertNull(library.failed())
            assertTrue(library.settled())
            assertNull(world.manifest().pending)
            val placed = assertNotNull(world.stored())
            assertEquals(placed.files.map { it.name }.toSet(), world.ours(here))
            placed.files.forEach { file -> assertEquals(Sha256.image(content.getValue(file.part)), world.inFolder(here)[file.name], file.name) }
            assertEquals(emptySet(), world.ours(there))
            assertEquals(emptySet(), world.anchors(), "no copy is left beside its file")
            world.assertForeignUntouched()
        }
        switch(library, false)
        assertIn(parked, addons)
        switch(library, true)
        assertIn(addons, parked)
    }

    @Test
    fun uninstallRemovesExactlyTheListedFilesDirectoryFirstAndKeepsTheDownloadWhenNotAsked() = runTest {
        val world = World(enabled = true)
        val library = with(world) { library() }
        val confirmation = assertNotNull(library.uninstallConfirmation("ivy"))
        assertEquals(record(3), confirmation.record)
        assertEquals(listOf(archive), confirmation.downloads)
        library.uninstall(confirmation, deleteDownload = false)
        runCurrent()

        assertNull(library.change())
        assertEquals(foreign.keys, world.inFolder(addons).keys)
        world.assertForeignUntouched()
        assertEquals(
            listOf("pak03_dir.vpk", "pak03_000.vpk", "pak03_002.vpk").map { "${addons.path}/$it" },
            world.files.removedPaths.filter { TemporaryInfix !in it },
        )
        assertNull(world.stored())
        assertNull(world.manifest().pending)
        assertEquals(listOf(archive), world.manifest().downloads.map { it.archive }, "an unticked download stays registered")
        assertEquals(setOf(archive.path), world.files.archivePaths())
        library.dismissChange("ivy")
        assertNull(library.change())
    }

    @Test
    fun uninstallKeepsAChangedChunkAndEveryChunkOfAChangedDirectory() = runTest {
        val chunk = World(enabled = true)
        chunk.files.rewrite(root, "${addons.path}/pak03_002.vpk", ByteArray(20) { 5 })
        val library = with(chunk) { library() }
        uninstall(library)
        assertEquals(foreign.keys + "pak03_002.vpk", chunk.inFolder(addons).keys)
        assertEquals(Sha256.image(ByteArray(20) { 5 }), chunk.inFolder(addons)["pak03_002.vpk"])
        assertTrue("pak03_002.vpk" in chunk.logged.single { it.startsWith("Left in place") }, "the log names what was left in place: ${chunk.logged}")
        assertNull(chunk.stored())

        val directory = World(enabled = true)
        directory.files.rewrite(root, "${addons.path}/pak03_dir.vpk", ByteArray(dir.size) { 5 })
        val other = with(directory) { library() }
        uninstall(other)
        assertEquals(foreign.keys + bytes.keys, directory.inFolder(addons).keys, "a directory that stays keeps every chunk, unchanged ones too")
        assertEquals(Sha256.image(chunk0), directory.inFolder(addons)["pak03_000.vpk"])
        assertEquals(emptySet(), directory.anchors())
        assertNull(directory.stored())
    }

    @Test
    fun whatTheLibraryShowsGoingWrongIsWrittenToTheLogOnce() = runTest {
        val world = World()
        val library = with(world) { library() }

        library.install("werecat")
        runCurrent()
        library.install("werecat")
        runCurrent()

        assertEquals(listOf("Installing werecat failed: NotAGameBananaMod"), world.logged.filter { "failed" in it })
    }

    @Test
    fun uninstallDeletesACreatedUnusedDownloadOnlyWhenAsked() = runTest {
        val world = World(enabled = true)
        val library = with(world) { library() }
        uninstall(library, deleteDownload = true)
        assertEquals(emptySet(), world.files.archivePaths())
        assertEquals(emptyList(), world.manifest().downloads)
        assertNull(world.stored())
    }

    @Test
    fun aDownloadIsOfferedOnlyWhenRegisteredAndNoOtherInstallInAnyGameUsesIt() = runTest {
        val unregistered = World(enabled = true, registered = false)
        assertEquals(emptyList(), with(unregistered) { library() }.uninstallConfirmation("ivy")!!.downloads)

        val elsewhere = record(3).copy(installId = "elsewhere", gameRoot = GameRoot("/other/Deadlock")).withOnly { copy(origin = SetOrigin.Downloaded(download!!.copy(path = download!!.path.uppercase()))) }
        val shared = World(enabled = true, extra = listOf(elsewhere))
        val library = with(shared) { library() }
        assertEquals(emptyList(), library.uninstallConfirmation("ivy")!!.downloads, "another game's install refers to the same file")
        uninstall(library, deleteDownload = true)
        assertEquals(setOf(archive.path), shared.files.archivePaths())
        assertEquals(listOf(archive), shared.manifest().downloads.map { it.archive })
    }

    @Test
    fun aDownloadThatChangedStaysRegistered() = runTest {
        val world = World(enabled = true)
        world.files.rewriteArchive(archive.path, FileImage(archive.sizeBytes, "8".repeat(64)))
        val library = with(world) { library() }
        uninstall(library, deleteDownload = true)
        assertEquals(setOf(archive.path), world.files.archivePaths())
        assertEquals(listOf(archive), world.manifest().downloads.map { it.archive })
        assertNull(world.stored())
    }

    @Test
    fun aSetTurnedOffSinceConfirmingIsStillUninstalledAndADownloadNoLongerDeletableStays() = runTest {
        val world = World(enabled = true)
        val library = with(world) { library() }
        val confirmed = assertNotNull(library.uninstallConfirmation("ivy"))
        switch(library, false)
        library.uninstall(confirmed, deleteDownload = false)
        runCurrent()
        assertNull(library.change(), "the same set, only in its off folder now")
        assertNull(world.stored())
        assertEquals(emptyMap(), world.inFolder(parked))
        world.assertForeignUntouched()

        val download = World(enabled = true)
        val other = with(download) { library() }
        val offered = assertNotNull(other.uninstallConfirmation("ivy"))
        download.store.save(download.manifest().copy(downloads = emptyList()))
        val restarted = with(download) { library() }
        restarted.uninstall(offered, deleteDownload = true)
        runCurrent()
        assertNull(restarted.change(), "the mod the user asked to remove goes")
        assertNull(download.stored())
        assertEquals(setOf(archive.path), download.files.archivePaths(), "a download no longer registered stays")
    }

    @Test
    fun afterARestartAnAnchorWithNoTwinLeftGoesOnItsBytesAndAFilePutBackAtTheOriginalStays() = runTest {
        val world = World(enabled = true)
        world.store.refuse = { if ((it.pending as? PendingChange.Uninstall)?.step is UninstallStep.Settled) "disk full" else null }
        val doomed = with(world) { crashableScope() }
        with(world) { library(doomed) }.let { uninstall(it) }
        doomed.cancel()
        world.store.refuse = { null }
        assertEquals(bytes.keys.map { "$it.hideout-id1" }.toSet(), world.anchors(), "every original went, so each anchor is its file's last name")
        world.files.putBytes(root, "${addons.path}/pak03_dir.vpk", dir)
        val recreated = world.identity(addons, "pak03_dir.vpk")

        with(world) { library() }
        assertNull(world.stored())
        assertNull(world.manifest().pending)
        assertEquals(recreated, world.identity(addons, "pak03_dir.vpk"), "a file put back after the original went is not Hideout's")
        assertEquals(emptySet(), world.anchors(), "each anchor still holding its file's bytes goes")
        assertTrue(world.logged.any { "${addons.path}/pak03_dir.vpk" in it }, "the file put back is reported left in place")
        assertTrue(world.logged.none { TemporaryInfix in it }, "no anchor is")
    }

    @Test
    fun theGameStartingMidUninstallStopsItAndTheUninstallFinishesOnceItStops() = runTest {
        val world = World(enabled = true)
        world.files.around = { point -> if (point == FaultPoint.RemoveDone("${addons.path}/pak03_dir.vpk")) world.processes.scan = ProcessScan.Unknown("no /proc") }
        val library = with(world) { library() }
        uninstall(library)
        assertEquals(UninstallStep.Releasing, assertIs<PendingChange.Uninstall>(world.manifest().pending).step)
        assertEquals(setOf("pak03_000.vpk", "pak03_002.vpk"), world.ours(addons))
        assertEquals(record(3), world.stored())

        world.files.around = null
        world.processes.scan = ProcessScan.Seen()
        uninstall(library)
        assertNull(library.change())
        assertEquals(foreign.keys, world.inFolder(addons).keys)
        assertNull(world.stored())
    }

    @Test
    fun aCrashAtAnyStepOfAnUninstallEndsWithTheSetWholeOrGoneAndOnlyProvenFilesDeleted() = runTest {
        crashAtEveryStep(enabled = true, act = { uninstall(it, deleteDownload = true) }) { world ->
            assertNull(world.manifest().pending)
            world.assertForeignUntouched()
            val stored = world.stored()
            if (stored != null) {
                world.assertWhole(3)
                assertEquals(setOf(archive.path), world.files.archivePaths())
            } else {
                assertEquals(emptySet(), world.ours(addons))
                assertEquals(emptySet(), world.anchors(), "no anchor is left")
                assertEquals(emptySet(), world.files.archivePaths(), "neither the download nor its anchor is left")
            }
        }
    }

    private fun TestScope.uninstallStoppedOnceSettled(world: World) {
        world.store.afterStore = { if ((it.pending as? PendingChange.Uninstall)?.step is UninstallStep.Settled) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        val doomed = crashableScope()
        uninstall(with(world) { library(doomed) }, deleteDownload = true)
        doomed.cancel()
        world.store.afterStore = {}
        assertEquals(bytes.keys.map { "$it.hideout-id1" }.toSet(), world.anchors(), "every original went, so each anchor is its file's last name")
        assertEquals(setOf("${archive.path}.hideout-id1"), world.files.archivePaths(), "and so is the download's")
    }

    @Test
    fun anUninstallStoppedOnceItsFilesWentFreesTheirNumberForTheNextInstall() = runTest {
        val world = World(enabled = true)
        world.files.putBytes(root, "${addons.path}/pak02_dir.vpk", ByteArray(6))
        uninstallStoppedOnceSettled(world)

        val library = with(world) { library() }
        assertNull(world.manifest().pending)
        world.banana.listings = mapOf(SubmissionRef(SubmissionKind.Mod, 5) to FileListing.Available("Other", listOf(RemoteFile(77, "o.zip", 10, null, null, 1, 1, "https://x/77"))))
        world.downloader.serve(77, listOf("o.vpk" to FakeVpk.directory(seed = 5)))
        library.install("mod-5")
        runCurrent()
        assertEquals(listOf(listOf(3)), world.manifest().records.map { record -> record.numbers.map { it.value } }, "pak01 and pak02 are foreign, and 03 is free again")
    }

    @Test
    fun anAnchorWhoseBytesChangedStaysAndIsReportedWhileTheOthersGo() = runTest {
        val world = World(enabled = true)
        uninstallStoppedOnceSettled(world)
        val changed = ByteArray(30) { 9 }
        world.files.rewrite(root, "${addons.path}/pak03_000.vpk.hideout-id1", changed)

        with(world) { library() }
        assertNull(world.stored())
        assertNull(world.manifest().pending)
        assertEquals(mapOf("pak03_000.vpk.hideout-id1" to Sha256.image(changed)), world.inFolder(addons).filterKeys { TemporaryInfix in it })
        assertEquals(emptySet(), world.files.archivePaths(), "the download's anchor goes")
        assertTrue(world.logged.any { "${addons.path}/pak03_000.vpk.hideout-id1" in it }, "the changed anchor is reported left in place")
    }

    @Test
    fun anOriginalChangedWhileItIsUninstalledStaysAndItsAnchorGoes() = runTest {
        val world = World(enabled = true)
        val changed = ByteArray(30) { 9 }
        val chunk = "${addons.path}/pak03_000.vpk"
        world.files.around = { point -> if (point == FaultPoint.Remove(chunk)) world.files.rewrite(root, chunk, changed) }
        val library = with(world) { library() }
        uninstall(library)

        assertNull(world.stored())
        assertNull(world.manifest().pending)
        assertEquals(mapOf("pak03_000.vpk" to Sha256.image(changed)), world.inFolder(addons) - foreign.keys, "the changed chunk stays, without its anchor")
        assertTrue(world.logged.any { chunk in it }, "the changed chunk is reported left in place")
        assertTrue(world.logged.none { TemporaryInfix in it }, "no anchor is")
    }

    @Test
    fun anUninstallUndoneWhileAnchoringKeepsAnAnchorLeftAloneWithItsBytes() = runTest {
        val world = World(enabled = true)
        val directory = "${addons.path}/pak03_dir.vpk"
        world.files.around = { point -> if (point == FaultPoint.LinkMade("$directory.hideout-id1")) world.files.putBytes(root, directory, dir) }
        world.files.failing += FaultPoint.Link("${addons.path}/pak03_000.vpk.hideout-id1")
        val library = with(world) { library() }
        uninstall(library)

        assertEquals(record(3), world.stored(), "the uninstall is undone")
        assertNull(world.manifest().pending)
        assertEquals(Sha256.image(dir), world.inFolder(addons)["pak03_dir.vpk"], "the copy put back at the directory's name stays")
        assertEquals(setOf("pak03_dir.vpk.hideout-id1"), world.anchors(), "equal bytes do not make the copy the mod's own file")
        assertEquals(world.identities[VpkPart.Directory], world.identity(addons, "pak03_dir.vpk.hideout-id1"), "so the directory stays under its anchor")
        assertTrue(world.logged.any { "$directory.hideout-id1" in it }, "and is reported left in place")
    }

    @Test
    fun anUninstallOfAnAdoptedModUndoneOnRestartKeepsTheOnlyCopyOfAReplacedChunkAndDropsTheAnchorOfAnEditedOne() = runTest {
        val adopted = record(3).withOnly { copy(source = InstalledSource.Local("ivy", "pak03_dir.vpk"), origin = SetOrigin.Adopted(MigrationId("m-1"))) }
        for (replaced in listOf(true, false)) {
            val case = if (replaced) "replaced" else "edited in place"
            val world = World(enabled = true, registered = false)
            world.store.save(world.manifest().copy(records = listOf(adopted)))
            world.files.crashAt = FaultPoint.LinkMade("${addons.path}/$chunkAnchor")
            val doomed = crashableScope()
            uninstall(with(world) { library(doomed) })
            doomed.cancel()
            world.files.crashAt = null
            assertEquals(UninstallStep.Anchoring, assertIs<PendingChange.Uninstall>(world.manifest().pending).step, case)
            if (replaced) world.files.putBytes(root, chunkAt03, otherChunk) else world.files.rewrite(root, chunkAt03, otherChunk)
            world.files.failing += FaultPoint.Link("${addons.path}/pak03_002.vpk.hideout-id1")

            val library = with(world) { library() }
            assertEquals(adopted.edited(VpkPart.Chunk(0), otherChunk), world.stored(), "$case: the uninstall is undone")
            assertNull(world.manifest().pending, case)
            assertNull(library.ready().interrupted, "$case: and nothing is shown as waiting")
            assertEquals(Sha256.image(otherChunk), world.inFolder(addons)["pak03_000.vpk"], "$case: what the other program wrote stays")
            assertEquals(mapOf("ivy" to FilesDamage.Changed), library.ready().damaged, case)
            world.assertChunkAnchor(replaced, case)
        }
    }

    @Test
    fun turningOffUndoneWhileAnchoringDropsTheAnchorOfAnEditedSourceAndKeepsTheOnlyCopyOfAReplacedOne() = runTest {
        for (replaced in listOf(false, true)) {
            val case = if (replaced) "replaced" else "edited in place"
            val world = World(enabled = true)
            world.files.around = { point ->
                if (point == FaultPoint.LinkMade("${addons.path}/$chunkAnchor")) {
                    world.files.around = null
                    if (replaced) world.files.putBytes(root, chunkAt03, otherChunk) else world.files.rewrite(root, chunkAt03, otherChunk)
                }
            }
            val library = with(world) { library() }
            switch(library, false)

            assertEquals(record(3).edited(VpkPart.Chunk(0), otherChunk), world.stored(), "$case: the mod stays where it was, held for the edit")
            assertNull(world.manifest().pending, case)
            assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, case)
            assertEquals(Sha256.image(otherChunk), world.inFolder(addons)["pak03_000.vpk"], case)
            world.assertChunkAnchor(replaced, case)
        }
    }

    @Test
    fun aFileChangedOnceTurningOffIsCommittedKeepsItsBytesAndItsAnchorGoes() = runTest {
        val world = World(enabled = true)
        world.store.afterStore = { if ((it.pending as? PendingChange.Arrange)?.step == MoveStep.Cleanup) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        val doomed = crashableScope()
        switch(with(world) { library(doomed) }, false)
        doomed.cancel()
        world.store.afterStore = {}
        assertEquals(3, world.anchors().size, "each moved file's anchor is still beside it")
        val changed = ByteArray(30) { 9 }
        world.files.rewrite(root, "${parked.path}/${off(VpkPart.Chunk(0))}", changed)

        with(world) { library() }
        assertNull(world.manifest().pending)
        assertEquals(Sha256.image(changed), world.inFolder(parked)[off(VpkPart.Chunk(0))])
        assertEquals(emptySet(), world.anchors(), "an anchor one file with its target goes, whatever the target holds now")
    }

    @Test
    fun aCrashAtAnyStepOfUninstallingAnOffModWhileDeadlockRunsIsSettledBeforeItClosesWithAddonsUntouched() = runTest {
        crashAtEveryStep(enabled = false, running = true, act = { uninstall(it) }) { world ->
            assertNull(world.manifest().pending)
            world.assertForeignUntouched()
            assertEquals(emptySet(), world.ours(addons), "addons is never touched")
            if (world.stored() != null) world.assertWhole(null) else assertEquals(emptySet(), world.ours(parked))
        }
    }

    @Test
    fun theSwitchShowsTheRequestedStateUntilTheRecordsChangeInTheSameUpdate() = runTest {
        val world = World(enabled = true)
        val library = with(world) { library() }
        val seen = mutableListOf<Pair<Boolean?, Boolean?>>()
        backgroundScope.launch {
            library.state.collect { state ->
                val record = (state.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == "ivy" }?.enabled
                seen += record to state.shownProfiles?.activeProfile?.enabled?.contains("ivy")
            }
        }
        runCurrent()
        switch(library, false)
        val shown = seen.mapNotNull { (_, requested) -> requested }
        assertEquals(listOf(true, false), shown.distinct(), "the switch never shows on again after it was turned off: $seen")
        assertEquals(false to false, seen.last())
        assertTrue(library.settled())
    }

    @Test
    fun aLaunchIsRefusedRatherThanTrustingAConfigAlreadyAsAskedWhenTheStartupManifestWasUnreadable() = runTest {
        for (modded in listOf(false, true)) {
            val world = World(enabled = true)
            if (modded) world.files.putBytes(root, InMemoryGameFiles.GameInfo, HideoutGameInfo)
            world.disk.unreadable = "library.json could not be read"
            val library = with(world) { library() }
            assertIs<LibraryStatus.Blocked>(library.state.value.library)

            val asked = world.disk.asked.size
            val info = world.files.gameInfo(root)
            assertNotNull(library.launch(modded) {}, "modded=$modded")
            assertEquals(asked, world.disk.asked.size, "no save is attempted while the install list is unknown")
            assertContentEquals(info, world.files.gameInfo(root))
        }
    }

    @Test
    fun undoingAMoveKeepsEveryChunkBesideATargetDirectoryThatChanged() = runTest {
        val world = World(enabled = false)
        val changed = ByteArray(dir.size) { 1 }
        world.watchMounts { point -> if (point == FaultPoint.LinkMade("${addons.path}/pak02_dir.vpk")) world.files.rewrite(root, "${addons.path}/pak02_dir.vpk", changed) }
        val library = with(world) { library() }
        switch(library, true)

        assertNull(library.failed(), "the mod whose directory changed is held where it is, and nothing waits on it")
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(emptySet(), world.ours(addons), "the changed directory left addons with both chunks")
        assertEquals(record(null).files.map { it.name }.toSet(), world.inFolder(parked).keys, "every source is back, and the changed directory's anchor, one file with it, goes")
        assertTrue(world.logged.none { TemporaryInfix in it }, "so no anchor is reported")
        assertEquals(Sha256.image(changed), world.inFolder(parked)[off(VpkPart.Directory)])
        record(null).files.forEach { assertEquals(world.identities[it.part], world.identity(parked, it.name), "${it.name} is the very file Hideout linked") }
        assertEquals(record(null).edited(VpkPart.Directory, changed), world.stored())
        assertNull(world.manifest().pending)
        assertNull(library.ready().interrupted)
        assertEquals(mapOf("ivy" to FilesDamage.Changed), library.ready().damaged)
        world.assertForeignUntouched()
    }

    @Test
    fun aMoveFinishedAfterARestartIsCheckedAgainAndCountedAsNotLoading() = runTest {
        val world = World(enabled = false)
        world.files.crashAt = FaultPoint.RemoveDone("${parked.path}/${off(VpkPart.Directory)}")
        val doomed = with(world) { crashableScope() }
        with(world) { library(doomed) }.let { switch(it, true) }
        doomed.cancel()
        world.files.crashAt = null
        world.files.writeGameInfo(root, VanillaGameInfo)

        val restarted = with(world) { library() }
        world.assertWhole(2)
        assertEquals(emptyMap(), restarted.ready().damaged, "the record the move committed is checked, not the half-released one")
        assertEquals(emptySet(), restarted.ready().unchecked)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = false), restarted.state.value.loading)
    }

    @Test
    fun aMoveFinishedByAnotherInstallIsCheckedAgain() = runTest {
        val world = World(enabled = false)
        world.files.crashAt = FaultPoint.RemoveDone("${parked.path}/${off(VpkPart.Directory)}")
        val doomed = with(world) { crashableScope() }
        with(world) { library(doomed) }.let { switch(it, true) }
        doomed.cancel()
        world.files.crashAt = null
        world.processes.scan = ProcessScan.Seen(game = true)
        val restarted = with(world) { library() }
        assertEquals("Deadlock is running", restarted.ready().interrupted)
        assertEquals(mapOf("ivy" to FilesDamage.Missing), restarted.ready().damaged, "the half-released source set is missing its directory")

        world.processes.scan = ProcessScan.Seen()
        val other = SubmissionRef(SubmissionKind.Mod, 5)
        world.banana.listings = mapOf(other to FileListing.Available("Other", listOf(RemoteFile(77, "o.zip", 10, null, null, 1, 1, "https://x/77"))))
        world.downloader.serve(77, listOf("o.vpk" to FakeVpk.directory(seed = 5)))
        restarted.install(other.modId)
        runCurrent()
        assertNull(restarted.state.value.operations[other.modId], "the other mod installs")
        assertEquals(listOf(true, true), world.manifest().records.map { it.enabled })
        assertEquals(emptyMap(), restarted.ready().damaged)
        assertEquals(emptySet(), restarted.ready().unchecked)
        assertNull(restarted.ready().interrupted)
    }

    @Test
    fun aDownloadWhoseDeletionIsUnprovenStaysRegisteredWithoutHoldingBackTheUninstall() = runTest {
        val faults = listOf<Pair<String, (World) -> Unit>>(
            "unreadable" to { it.files.failing += FaultPoint.Look(archive.path) },
            "cannot be anchored" to { it.files.failing += FaultPoint.Link("${archive.path}.hideout-id1") },
            "its removal fails" to { it.files.failing += FaultPoint.Remove(archive.path) },
            "outside the download folder" to { it.archives = OutsideDownloads(it.downloader) },
        )
        for ((name, fault) in faults) {
            val world = World(enabled = true)
            fault(world)
            val library = with(world) { library() }
            uninstall(library, deleteDownload = true)
            assertNull(library.change(), name)
            assertNull(world.stored(), name)
            assertNull(world.manifest().pending, name)
            assertEquals(foreign.keys, world.inFolder(addons).keys, name)
            assertEquals(FileImage(archive.sizeBytes, archive.sha256), world.files.archiveFile(archive.path), name)
            assertEquals(setOf(archive.path), world.files.archivePaths(), name)
            assertEquals(listOf(archive), world.manifest().downloads.map { it.archive }, name)
            assertIs<RemovalOutcome.Preserved>(world.store.saved.firstNotNullOf { ((it.pending as? PendingChange.Uninstall)?.step as? UninstallStep.Settled)?.archives }[archive.path], name)
        }
    }

    @Test
    fun aDownloadAlreadyGoneIsAbsentAndItsRegistrationGoes() = runTest {
        val world = World(enabled = true)
        world.files.removeArchive(archive.path)
        val library = with(world) { library() }
        uninstall(library, deleteDownload = true)
        assertNull(library.change())
        assertEquals(emptyList(), world.manifest().downloads)
        assertEquals(emptySet(), world.files.archivePaths())
        assertNull(world.stored())
    }

    private class OutsideDownloads(private val inner: FakeDownloader) : Downloader by inner {
        override suspend fun link(existing: DownloadEntry, created: DownloadEntry) = LinkResult.Failed(Outside)
        override suspend fun inspect(entry: DownloadEntry, witness: DownloadEntry) = EntryState.Unreadable(Outside)
        override suspend fun removeTwin(entry: DownloadEntry, witness: DownloadEntry, expected: FileImage) = Removal.Failed(Outside)
        override suspend fun removeExact(entry: DownloadEntry, expected: FileImage) = Removal.Failed(Outside)
    }
}

private const val Outside = "the archive is not in Hideout's download folder"
