package gg.hideouthq.core.library

import gg.hideouthq.core.data.*
import gg.hideouthq.core.data.fake.*
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesTest {
    private val root = GameRoot("/games/Deadlock")
    private val main = ProfileId("main")
    private val bytes = FakeVpk.directory(seed = 3)

    private inner class World(val roots: List<GameRoot> = listOf(root)) {
        val files = InMemoryGameFiles().apply { roots.forEach { putBytes(it, InMemoryGameFiles.GameInfo, VanillaGameInfo) } }
        val store = InMemoryLibraryStore()
        val processes = FakeProcesses()
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        val preferences = Preferences(MemoryStorage())
        var next = 0
        var local: LocalImport = LocalImport.Unavailable
        var released = false
        var disk: LibraryStore = store
        var installer: ModInstaller = files
        val reads = mutableListOf<String>()
        var fingerprints = 0
        private val inventory = object : GameInventory by files {
            override suspend fun file(root: GameRoot, relativePath: String): FileObservation = files.file(root, relativePath).also {
                reads += relativePath
                if (it is FileObservation.Present) fingerprints++
            }
        }
        private var session: CoroutineScope? = null

        fun TestScope.library(scope: CoroutineScope? = null): ModLibrary {
            session?.cancel()
            val owned = scope ?: CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            session = owned
            return ModLibrary(
                FakeGameLocator(roots.map { it.path }, roots.associate { it.path to null }), preferences, disk, inventory,
                InstallPorts(banana, downloader, installer, files, processes, localImport = local), owned,
                ids = { "id${++next}" },
            ).also { it.start(); runCurrent() }
        }

        fun offer(id: Long = 1, file: Long = id * 10, content: ByteArray = bytes) {
            val ref = SubmissionRef(SubmissionKind.Mod, id)
            val remote = RemoteFile(file, "skin-$file.zip", 100, null, null, 1, 1, "https://gamebanana.com/dl/$file")
            banana.listings = banana.listings + (ref to FileListing.Available("Skin $id", listOf(remote)))
            downloader.serve(file, listOf("skin.vpk" to content))
        }

        fun importing(vararg entries: Pair<String, ByteArray>) {
            val archive = ArchiveIdentity("/imports/copy.zip", 100, "a".repeat(64))
            files.putArchive(archive.path, entries.toList(), FileImage(100, "a".repeat(64)))
            local = object : LocalImport {
                override suspend fun snapshot(file: LocalFile) = LocalImportResult.Ready(LocalArchiveSnapshot(archive, file.name))
                override suspend fun release(snapshot: LocalArchiveSnapshot) { released = true }
            }
        }

        fun record(): ManifestRecord = store.manifest!!.records.single()

        fun record(modId: String): ManifestRecord = store.manifest!!.records.single { it.modId == modId }

        fun offName(record: ManifestRecord = record()): String = record.offPath().substringAfterLast('/')

        fun readPaks(): Set<String> = reads.map { it.substringAfterLast('/').substringBefore('_') }.toSet()

        fun reset() = reads.clear()
    }

    private fun ModLibrary.installLocal() = install(InstallRequest(InstallInput.Local(LocalFile("/picked/skin.zip", "skin.zip", 100)), setOf(main)))

    private fun TestScope.installing(mods: Int): Pair<World, ModLibrary> {
        val world = World().apply { (1L..mods).forEach { offer(it) } }
        val library = with(world) { library() }
        (1..mods).forEach { library.install("mod-$it") }
        runCurrent()
        return world to library
    }

    private suspend fun ModLibrary.emptyProfile(): ProfileId = createProfile(NewProfile("Empty", ProfileColour.Blue, ProfileSeed.Empty, false)).done()

    private fun ModLibrary.ready(): LibraryStatus.Ready = assertIs<LibraryStatus.Ready>(state.value.library)

    private fun <T> MutationResult<T>.done(): T = assertIs<MutationResult.Done<T>>(this).value

    private fun ModLibrary.shown(): ProfileBook = assertNotNull(state.value.shownProfiles)

    private fun ModLibrary.failed(): LibraryProblem? = (state.value.choiceSync as? ChoiceSync.Failed)?.problem

    private fun World.applied(): ProfileBook = store.manifest!!.profiles

    @Test fun profileCrudSwitchUndoAndRestartKeepEachProfilesOwnOrder() = runTest {
        val (world, library) = installing(2)
        val ids = world.applied().activeProfile.orders.getValue(root)
        val empty = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Empty, false)).done()
        library.setMembership(empty, ids.last(), true).done()
        val result = assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(empty))
        assertNull(library.state.value.undo, "the profile left is one click away")
        assertEquals(SwitchSummary(empty, 1, 0), result.value)
        runCurrent()
        assertEquals(setOf("pak02_dir.vpk"), world.files.addonsFiles(root).keys)
        library.switchProfile(main).done()
        runCurrent()
        assertEquals(main, library.state.value.profiles!!.active)
        assertEquals(setOf("pak01_dir.vpk", "pak02_dir.vpk"), world.files.addonsFiles(root).keys)
        library.renameProfile(empty, "Casual").done()
        library.recolourProfile(empty, ProfileColour.Rose).done()
        val duplicate = library.duplicateProfile(empty).done()
        assertEquals("Casual copy", library.state.value.profiles!!.profiles.single { it.id == duplicate }.name)
        library.deleteProfile(empty).done()
        library.undo(assertNotNull(library.state.value.undo)).done()
        library.reorder(library.state.value.orderView!!, ids.reversed()); runCurrent()
        assertEquals(ids.reversed(), onLoadOrder(world.store.manifest!!.records))
        library.switchProfile(empty).done(); runCurrent()
        assertEquals(setOf("pak02_dir.vpk"), world.files.addonsFiles(root).keys)
        val restarted = with(world) { library() }
        assertEquals(empty, restarted.state.value.profiles!!.active)
        assertEquals(ProfileColour.Rose, restarted.state.value.profiles!!.activeProfile.colour)
        assertEquals(ids, restarted.state.value.orderView!!.sets, "the drag was Main's")
        assertEquals(listOf(ids.last()), onLoadOrder(world.store.manifest!!.records))
        assertEquals(ids.reversed(), restarted.state.value.profiles!!.profiles.single { it.id == main }.orders[root])
    }

    @Test fun aNewProfileTakesTheActiveOrderEmptyAndTheSeedsOrderAndMembersAsACopyOrDuplicate() = runTest {
        val (world, library) = installing(3)
        val ids = world.applied().activeProfile.orders.getValue(root)
        val ranked = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Copy(main))).done()
        library.setMembership(ranked, ids[0], false).done()
        library.reorder(library.state.value.orderView!!, ids.reversed()); runCurrent()
        val seed = library.state.value.profiles!!.activeProfile
        assertEquals(ids.reversed(), seed.orders[root])

        val empty = library.createProfile(NewProfile("Empty", ProfileColour.Clay, ProfileSeed.Empty, false)).done()
        val copy = library.createProfile(NewProfile("Copy", ProfileColour.Rose, ProfileSeed.Copy(ranked), false)).done()
        val duplicate = library.duplicateProfile(ranked).done()
        library.switchProfile(main).done(); runCurrent()
        val book = library.state.value.profiles!!
        fun profile(id: ProfileId) = book.profiles.single { it.id == id }
        assertEquals(emptySet(), profile(empty).enabled)
        assertEquals(seed.orders, profile(empty).orders, "an empty profile loads in the order of the profile active when it was made")
        for (made in listOf(copy, duplicate)) assertEquals(seed.enabled to seed.orders, profile(made).enabled to profile(made).orders)
        assertEquals(mapOf(root to ids), profile(main).orders)
        assertEquals(ids, onLoadOrder(world.store.manifest!!.records))
    }

    @Test fun anInstallGoesFirstInEveryProfilesOrderAndAnUninstallLeavesEveryOrder() = runTest {
        val world = World().apply { offer(); offer(2); offer(3); offer(4) }
        val library = with(world) { library() }
        library.install("mod-1"); library.install("mod-2"); library.install("mod-3"); runCurrent()
        val (first, second, third) = world.applied().activeProfile.orders.getValue(root)
        val ranked = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Copy(main))).done()
        library.reorder(library.state.value.orderView!!, listOf(third, second, first)); runCurrent()
        library.switchProfile(main).done(); runCurrent()
        fun orders() = world.applied().profiles.associate { it.id to it.orders.getValue(root) }

        library.uninstall(assertNotNull(library.uninstallConfirmation(second)), deleteDownload = false); runCurrent()
        assertEquals(mapOf(main to listOf(first, third), ranked to listOf(third, first)), orders())
        library.install("mod-4"); runCurrent()
        val fourth = world.record("mod-4").installId
        assertEquals(mapOf(main to listOf(fourth, first, third), ranked to listOf(fourth, third, first)), orders(), "first in every profile")
        assertEquals(listOf(fourth, first, third), onLoadOrder(world.store.manifest!!.records), "and first to load in the profile it joins")
        assertEquals(setOf(first, fourth, third), world.applied().activeProfile.enabled)
        assertEquals(setOf(first, third), world.applied().profiles.single { it.id == ranked }.enabled, "the install joins only the profile it was asked for")
        assertNull(world.store.manifest!!.savedChoices)
    }

    private fun held(id: String, mod: Long, number: Int?) = oneSetRecord(
        id, root, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, mod, mod, null)), id, null, ArchiveIdentity("/downloads/$id.zip", 1, "a".repeat(64)),
        number, listOf(directory(FileImage(1, "a".repeat(64)))),
    )

    @Test fun theFilesFollowABookWhateverItsPlacesForTheModsThatAreOff() {
        val records = listOf(held("a", 1, 3), held("b", 2, null), held("c", 3, 5))
        val book = ProfileBook.main(records)
        assertEquals(listOf("a", "c", "b"), book.activeProfile.orders[root], "Main lists the mods that are on by number, then the ones that are off")
        assertEquals(setOf("a", "c"), book.activeProfile.enabled)
        assertTrue(book.follows(records))
        assertTrue(book.withOrder(root, listOf("b", "a", "c")).follows(records), "where the ones that are off go is the book's own")
        assertFalse(book.withOrder(root, listOf("c", "a", "b")).follows(records), "the ones that are on load by number")
        assertFalse(book.membership(main, setOf("b"), enabled = true).follows(records), "and only they are on")
        assertFalse(ProfileBook.main(records.dropLast(1)).follows(records), "every mod is ordered")
    }

    @Test fun aNewModGoesFirstInEveryProfilesOrderAndOnOnlyInItsMembers() {
        val records = listOf(held("a", 1, 3), held("b", 2, null))
        val ranked = ProfileId("ranked")
        val book = ProfileBook.main(records).let { it.copy(profiles = it.profiles + Profile(ranked, "Ranked", ProfileColour.Blue, setOf("b"), mapOf(root to listOf("b", "a")))) }
        val added = held("n", 9, null)
        val placed = book.withInstalled(added, setOf(ranked))
        assertEquals(listOf(listOf("n", "a", "b"), listOf("n", "b", "a")), placed.profiles.map { it.orders.getValue(root) })
        assertEquals(listOf(setOf("a"), setOf("b", "n")), placed.profiles.map { it.enabled })
        assertEquals(placed, placed.withInstalled(added, setOf(ranked)), "once")
    }

    @Test fun everyProfileOrdersTheSameModsEachOnceAndAGameWithNothingLeftHasNoOrder() {
        val other = GameRoot("/other/Deadlock")
        val book = ProfileBook(main, listOf(
            Profile(main, "Main", ProfileColour.Sage, setOf("a", "e"), mapOf(root to listOf("a", "b"), other to listOf("e"))),
            Profile(ProfileId("ranked"), "Ranked", ProfileColour.Blue, setOf("b"), mapOf(root to listOf("b", "a"), other to listOf("e"))),
        ))
        assertEquals(listOf(mapOf(root to listOf("a", "b")), mapOf(root to listOf("b", "a"))), book.without(setOf("e")).profiles.map { it.orders }, "a game with nothing left has no order")

        val ranked = book.profiles.last()
        val refusals = listOf(
            "an empty order" to { ranked.copy(orders = ranked.orders + (other to emptyList())) },
            "a mod twice" to { ranked.copy(orders = ranked.orders + (root to listOf("b", "b"))) },
            "a mod in two games" to { ranked.copy(orders = ranked.orders + (other to listOf("e", "a"))) },
            "a member it does not order" to { ranked.copy(enabled = setOf("z")) },
            "profiles that order other mods" to { book.copy(profiles = book.profiles.dropLast(1) + ranked.copy(enabled = emptySet(), orders = mapOf(root to listOf("a", "b")))) },
        )
        for ((name, make) in refusals) assertFailsWith<IllegalArgumentException>(name) { make() }
    }

    @Test fun switchingAndTogglingFingerprintAtMostSixTimesPerChangedFile() = runTest {
        val world = World().apply { offer() }
        var inspections = 0
        var removals = 0
        world.installer = object : ModInstaller by world.files {
            override suspend fun inspect(entry: GameEntry, witness: GameEntry): EntryState {
                inspections++
                return world.files.inspect(entry, witness)
            }
            override suspend fun removeTwin(entry: GameEntry, witness: GameEntry, expected: FileImage): Removal {
                removals++
                return world.files.removeTwin(entry, witness, expected)
            }
            override suspend fun removeExact(entry: GameEntry, expected: FileImage): Removal {
                removals++
                return world.files.removeExact(entry, expected)
            }
        }
        val library = with(world) { library() }
        library.install("mod-1"); runCurrent()
        val id = world.record().installId
        val empty = library.emptyProfile()
        suspend fun checkReads(action: suspend () -> Unit) {
            world.reset()
            world.fingerprints = 0
            inspections = 0
            removals = 0
            action()
            runCurrent()
            (world.fingerprints + inspections + removals).let { assertTrue(it <= 6, "full-file fingerprint budget: $it") }
            assertNull(world.store.manifest!!.pending)
            assertNull(library.ready().interrupted)
        }
        checkReads { library.switchProfile(empty).done() }
        assertEquals(emptySet(), world.files.addonsFiles(root).keys)
        checkReads { library.switchProfile(main).done() }
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)
        checkReads { library.setEnabled(id, false) }
        assertFalse(world.record().enabled)
        assertEquals(emptySet(), world.files.addonsFiles(root).keys)
        checkReads { library.setEnabled(id, true) }
        assertTrue(world.record().enabled)
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)
    }

    @Test fun replacingTheTargetAtReleaseAdmissionPreservesTheSourceAndForeignTarget() = runTest {
        val world = World().apply { offer() }
        val foreign = FakeVpk.directory(seed = 91)
        var armed = false
        var replaced = false
        world.disk = object : LibraryStore by world.store {
            override suspend fun save(manifest: LibraryManifest): ManifestSave {
                if (armed && !replaced && (manifest.pending as? PendingChange.Arrange)?.step == MoveStep.Releasing) {
                    replaced = true
                    world.files.putBytes(root, world.record().offPath(), foreign)
                }
                return world.store.save(manifest)
            }
        }
        val library = with(world) { library() }
        library.install("mod-1"); runCurrent()
        armed = true
        val empty = library.emptyProfile()
        library.switchProfile(empty).done(); runCurrent()
        assertTrue(replaced)
        assertEquals(LibraryProblem.NameTaken(world.record().offPath()), library.failed(), "the switch rolls back")
        assertEquals(empty, library.state.value.profiles!!.active, "the choice stays saved")
        assertEquals(main, world.store.manifest!!.profiles.active)
        assertEquals(Sha256.image(bytes), world.files.addonsFiles(root).getValue("pak01_dir.vpk"))
        assertEquals(FileObservation.Present(foreign.size.toLong(), sha256Hex(foreign)), world.files.file(root, world.record().offPath()))
        assertNull(world.store.manifest!!.pending)
        assertNull(library.ready().interrupted)
    }

    @Test fun anUnreadableTargetWhileLinkingWaitsUntilARetryFinishesTheSwitch() = runTest {
        val world = World().apply { offer(); offer(2) }
        var armed = false
        var released = false
        world.disk = object : LibraryStore by world.store {
            override suspend fun save(manifest: LibraryManifest): ManifestSave {
                if (armed && !released && (manifest.pending as? PendingChange.Arrange)?.step == MoveStep.Releasing) {
                    released = true
                    world.files.failing += FaultPoint.Look(world.record("mod-2").offPath())
                }
                return world.store.save(manifest)
            }
        }
        val library = with(world) { library() }
        library.install("mod-1"); library.install("mod-2"); runCurrent()
        val (one, two) = listOf("mod-1", "mod-2").map(world::record)
        val target = two.offPath()
        armed = true
        val empty = library.emptyProfile()
        world.files.removedPaths.clear()
        library.switchProfile(empty).done(); runCurrent()
        assertEquals(LibraryProblem.Interrupted("failed at ${FaultPoint.Look(target)}"), library.failed())
        assertTrue(released)
        assertEquals(listOf("game/citadel/addons/pak02_dir.vpk", "game/citadel/addons/pak01_dir.vpk"), world.files.removedPaths, "the sources went before any target was proved")
        assertEquals(setOf(world.offName(one), world.offName(two)), world.files.filesIn(root, SetFolder.Off("mod-1")).keys + world.files.filesIn(root, SetFolder.Off("mod-2")).keys)
        assertEquals(main, world.store.manifest!!.profiles.active)
        assertEquals(MoveStep.Linking, assertIs<PendingChange.Arrange>(world.store.manifest!!.pending).step)
        assertNotNull(library.ready().interrupted)

        world.files.failing.clear()
        val restarted = with(world) { library() }
        runCurrent()
        assertNull(world.store.manifest!!.pending)
        assertEquals(empty, restarted.state.value.profiles!!.active)
        assertEquals(empty, world.store.manifest!!.profiles.active)
        assertTrue(world.store.manifest!!.records.none { it.enabled })
        assertEquals(emptyMap(), world.files.addonsFiles(root))
        assertEquals(mapOf(world.offName(one) to Sha256.image(bytes)), world.files.filesIn(root, SetFolder.Off("mod-1")))
        assertEquals(mapOf(world.offName(two) to Sha256.image(bytes)), world.files.filesIn(root, SetFolder.Off("mod-2")))
        assertNull(restarted.ready().interrupted)
    }

    @Test fun invalidNamesAndTheLastDeleteAreRefused() = runTest {
        val world = World()
        val library = with(world) { library() }
        assertEquals(MutationResult.Refused(LibraryProblem.InvalidProfileName), library.createProfile(NewProfile(" ", ProfileColour.Sage, ProfileSeed.Empty)))
        assertEquals(MutationResult.Refused(LibraryProblem.ProfileNameTaken), library.createProfile(NewProfile("MAIN", ProfileColour.Sage, ProfileSeed.Empty)))
        assertEquals(MutationResult.Refused(LibraryProblem.LastProfile), library.deleteProfile(main))
    }

    @Test fun deletingTheActiveProfileSwitchesToTheOneAboveOrElseBelowAndOneUndoPutsBothBack() = runTest {
        val (world, library) = installing(2)
        val ids = world.applied().activeProfile.orders.getValue(root)
        val ranked = library.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Empty)).done(); runCurrent()
        assertEquals(emptySet(), world.files.addonsFiles(root).keys)
        library.setMembership(ranked, ids.last(), true).done(); runCurrent()
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys, "with every mod off, the one turned on takes the lowest number")

        library.deleteProfile(ranked).done(); runCurrent()
        assertEquals(main, library.state.value.profiles!!.active, "Main is above Ranked")
        assertEquals(listOf("Main"), library.state.value.profiles!!.profiles.map { it.name })
        assertEquals(setOf("pak01_dir.vpk", "pak02_dir.vpk"), world.files.addonsFiles(root).keys, "Main's mods are on")
        val undo = assertNotNull(library.state.value.undo)
        assertEquals(Undoable.Deleted("Ranked", switchedTo = "Main"), undo.undoes)

        library.undo(undo).done(); runCurrent()
        assertEquals(ranked, library.state.value.profiles!!.active)
        assertEquals(listOf("Main", "Ranked"), library.state.value.profiles!!.profiles.map { it.name })
        assertEquals(setOf("pak02_dir.vpk"), world.files.addonsFiles(root).keys)

        library.switchProfile(main).done()
        library.deleteProfile(main).done(); runCurrent()
        assertEquals(ranked, library.state.value.profiles!!.active, "Main is first, so the one below it")
        assertEquals(setOf("pak02_dir.vpk"), world.files.addonsFiles(root).keys)
    }

    @Test fun heldDownloadKeepsChosenProfilesAfterActiveSwitch() = runTest {
        val world = World().apply { offer() }
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[10] = gate
        val library = with(world) { library() }
        val other = library.createProfile(NewProfile("Other", ProfileColour.Blue, ProfileSeed.Empty, false)).done()
        library.install(InstallRequest(InstallInput.Remote(SubmissionRef(SubmissionKind.Mod, 1)), setOf(main)))
        assertEquals(setOf(main), library.state.value.operations.getValue("mod-1").profiles)
        runCurrent()
        library.switchProfile(other).done()
        assertEquals(setOf(main), library.state.value.operations.getValue("mod-1").profiles)
        gate.complete(Unit); runCurrent()
        assertFalse(world.record().enabled)
        assertEquals(setOf(world.record().installId), library.state.value.profiles!!.profiles.first().enabled)
        assertEquals(emptySet(), library.state.value.profiles!!.activeProfile.enabled)
        assertEquals(setOf(world.offName()), world.files.filesIn(root, SetFolder.Off("mod-1")).keys)
    }

    @Test fun aProfileDeletedDuringTheDownloadIsLeftOutAndTheModInstallsIntoTheRest() = runTest {
        val world = World().apply { offer() }
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[10] = gate
        val library = with(world) { library() }
        val other = library.createProfile(NewProfile("Other", ProfileColour.Blue, ProfileSeed.Empty, false)).done()
        library.install(InstallRequest(InstallInput.Remote(SubmissionRef(SubmissionKind.Mod, 1)), setOf(main, other))); runCurrent()
        library.deleteProfile(other).done()
        gate.complete(Unit); runCurrent()
        assertNull(library.state.value.operations["mod-1"])
        assertTrue(world.record().enabled)
        assertEquals(listOf(main), world.store.manifest!!.profiles.profiles.map { it.id })
        assertEquals(setOf(world.record().installId), library.state.value.profiles!!.activeProfile.enabled)
    }

    @Test fun gameStartingDuringDownloadPlacesTheSetOffAndTurnsItOnOnceClosed() = runTest {
        val world = World().apply { offer() }
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[10] = gate
        val library = with(world) { library() }
        library.install("mod-1"); runCurrent()
        world.processes.scan = ProcessScan.Seen(game = true)
        gate.complete(Unit); runCurrent()
        assertNull(library.state.value.operations["mod-1"], "the install finished")
        assertEquals(false, world.record().enabled)
        assertEquals(emptyMap(), world.files.addonsFiles(root))
        assertEquals(setOf(world.offName()), world.files.filesIn(root, SetFolder.Off("mod-1")).keys)
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync)
        assertEquals(setOf(world.record().installId), library.state.value.profiles!!.activeProfile.enabled)
        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2.seconds); runCurrent()
        assertEquals(true, world.record().enabled)
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)
    }

    @Test fun pageInstallAsksForVersionRegardlessOfPreferenceButNotForOneFile() = runTest {
        for (fileCount in 1..2) {
            val world = World()
            val submission = SubmissionRef(SubmissionKind.Mod, 1)
            val offered = (10L until 10L + fileCount).map { id ->
                world.offer(file = id)
                (world.banana.listings.getValue(submission) as FileListing.Available).files.single()
            }
            world.banana.listings = mapOf(submission to FileListing.Available("Skin", offered))
            world.preferences.setAskWhichVersion(false)
            val library = with(world) { library() }
            library.install(InstallRequest(InstallInput.Remote(submission), setOf(main), chooseVersion = true))
            runCurrent()
            if (fileCount == 1) {
                assertEquals(offered.single().id, world.record().remoteIdentity.fileId, "one file installs without a question")
                continue
            }
            val question = assertIs<OperationStage.ChoosingFile>(library.state.value.operations.getValue("mod-1").stage)
            assertEquals(offered.map { it.id }.toSet(), question.files.map { it.id }.toSet())
            assertEquals(setOf(main), question.profiles)
            assertEquals(emptyList(), world.store.manifest?.records.orEmpty())
            assertTrue(library.answer(question.id, ChoiceAnswer.Files(setOf(offered.last().id), question.profiles)))
            runCurrent()
            assertEquals(offered.last().id, world.record().remoteIdentity.fileId)
        }
    }

    @Test fun explicitFileStillBypassesRequestedVersionChooser() = runTest {
        val world = World().apply { offer() }
        val library = with(world) { library() }
        library.install(InstallRequest(InstallInput.Remote(SubmissionRef(SubmissionKind.Mod, 1), setOf(10)), setOf(main), chooseVersion = true))
        runCurrent()
        assertEquals(10L, world.record().remoteIdentity.fileId)
        assertTrue(library.state.value.operations.isEmpty())
    }

    @Test fun replacementChooserCanCancelLeavingTheModAsItWas() = runTest {
        val (world, library) = installing(1)
        val old = world.record()
        world.preferences.setAskWhichVersion(false)
        world.offer(file = 11, content = FakeVpk.directory(seed = 8))
        val before = world.store.manifest
        library.changeFiles(old.installId)
        runCurrent()
        val cancelled = assertIs<OperationStage.ChoosingFile>(library.state.value.operations.getValue("mod-1").stage)
        assertEquals(setOf(main), cancelled.profiles)
        assertEquals(setOf(10L), cancelled.preselected, "it starts from the file the mod has")
        assertEquals(listOf(11L), cancelled.files.map { it.id })
        assertEquals(old.sets, cancelled.delisted, "which GameBanana no longer lists, so it is offered after")
        assertTrue(library.answer(cancelled.id, ChoiceAnswer.Cancel))
        runCurrent()
        assertEquals(before, world.store.manifest)
    }

    @Test fun replacementPreservesCurrentMembershipAndSlot() = runTest {
        val (world, library) = installing(1)
        val old = world.record()
        val other = library.createProfile(NewProfile("Other", ProfileColour.Blue, ProfileSeed.Empty, false)).done()
        world.offer(file = 11, content = FakeVpk.directory(seed = 8))
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[11] = gate
        assertTrue(changeFilesTo(library, old.installId, 11))
        library.setMembership(other, old.installId, true).done()
        library.setMembership(main, old.installId, false).done()
        gate.complete(Unit); runCurrent()
        assertEquals(11L, world.record().remoteIdentity.fileId)
        assertEquals(old.installId, world.record().installId)
        assertEquals(Placement.Off, world.record().placement)
        assertEquals(setOf(old.installId), library.state.value.profiles!!.profiles.last().enabled)
        assertEquals(Sha256.image(FakeVpk.directory(seed = 8)), world.files.filesIn(root, SetFolder.Off("mod-1"))[world.offName()])
    }

    @Test fun localImportHasNoRemoteIdentityAndDoesNotRegisterTheOriginal() = runTest {
        val world = World()
        world.importing("skin.vpk" to bytes)
        val library = with(world) { library() }
        library.installLocal(); runCurrent()
        assertEquals(emptyMap(), library.state.value.operations, "one complete part is no question")
        val source = assertIs<InstalledSource.Local>(world.record().source)
        assertEquals("skin.zip", source.fileName)
        assertEquals("local-id1", source.modId)
        assertEquals(emptyList(), world.store.manifest!!.downloads)
        assertTrue(world.released)
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)
    }

    @Test fun theSameFileAddedAgainSaysWhichModHasItAndAddsNothingWhileAPartNotTakenStillInstalls() = runTest {
        val world = World()
        world.importing("skin.vpk" to bytes)
        val library = with(world) { library() }
        library.installLocal(); runCurrent()
        val installed = world.record()

        library.installLocal(); runCurrent()
        val failed = assertIs<OperationStage.Failed>(library.state.value.operations.values.single().stage)
        assertEquals(LibraryProblem.SameFileInstalled(installed.title, installed.installId), failed.problem)
        assertEquals(listOf(installed), world.store.manifest!!.records, "nothing was added")

        val other = World()
        other.importing("A/skin.vpk" to FakeVpk.directory(seed = 12), "B/skin.vpk" to FakeVpk.directory(seed = 13))
        val parts = with(other) { library() }
        parts.installLocal(); runCurrent()
        val first = assertIs<OperationStage.ChoosingSet>(parts.state.value.operations.values.single().stage)
        assertTrue(parts.answer(first.id, ChoiceAnswer.Sets(listOf("A/skin.vpk"), first.profiles))); runCurrent()
        parts.installLocal(); runCurrent()
        assertIs<OperationStage.ChoosingSet>(parts.state.value.operations.values.single().stage, "B was left out, so the same file still offers it")
    }

    @Test fun aLocalArchiveHoldingSeveralSetsInstallsEveryChosenComponentAsOneModInTheChosenOrder() = runTest {
        val world = World()
        val a = FakeVpk.directory(seed = 12)
        val b = FakeVpk.directory(seed = 13)
        world.importing("A/skin.vpk" to a, "B/skin.vpk" to b)
        val library = with(world) { library() }
        library.installLocal(); runCurrent()
        val question = assertIs<OperationStage.ChoosingSet>(library.state.value.operations.values.single().stage)
        assertEquals(listOf("A/skin.vpk", "B/skin.vpk"), question.options.map { it.key }, "two same-basename standalone sets stay distinct by their archive path")
        assertEquals(listOf("A/skin.vpk", "B/skin.vpk"), question.preselected, "every component of the one archive starts ticked")
        assertTrue(library.answer(question.id, ChoiceAnswer.Sets(listOf("B/skin.vpk", "A/skin.vpk"), question.profiles)))
        runCurrent()
        val record = world.record()
        assertEquals(listOf("B/skin.vpk", "A/skin.vpk"), record.sets.map { it.componentKey }, "B wins, as the chosen order lists it first")
        assertEquals(listOf(1, 2), record.numbers.map { it.value })
        assertTrue(record.sets.all { it.source == record.sets[0].source }, "both components share the one local source")
        assertEquals(setOf("pak01_dir.vpk", "pak02_dir.vpk"), world.files.addonsFiles(root).keys)
        assertEquals(Sha256.image(b), world.files.addonsFiles(root)["pak01_dir.vpk"])
    }

    @Test fun localRetryKeepsTheChosenComponentsAndTheirOrder() = runTest {
        val world = World()
        world.importing("A/skin.vpk" to bytes, "B/skin.vpk" to FakeVpk.directory(seed = 4))
        val library = with(world) { library() }
        library.installLocal()
        runCurrent()
        val operation = library.state.value.operations.values.single()
        val question = assertIs<OperationStage.ChoosingSet>(operation.stage)
        world.files.around = { point ->
            if (point is FaultPoint.Publish) {
                world.files.around = null
                world.files.failing += point
            }
        }
        assertTrue(library.answer(question.id, ChoiceAnswer.Sets(listOf("B/skin.vpk", "A/skin.vpk"), question.profiles)))
        runCurrent()
        assertIs<OperationStage.Failed>(library.state.value.operations.getValue(operation.modId).stage)
        world.files.failing.clear()
        library.retry(operation.modId)
        runCurrent()
        val retry = assertIs<OperationStage.ChoosingSet>(library.state.value.operations.getValue(operation.modId).stage)
        assertEquals(listOf("B/skin.vpk", "A/skin.vpk"), retry.preselected)
        assertTrue(library.answer(retry.id, ChoiceAnswer.Sets(retry.preselected, retry.profiles)))
        runCurrent()
        assertEquals(retry.preselected, world.record().sets.map { it.componentKey })
    }

    @Test fun equalByteForeignDestinationBlocksWholeSwitch() = runTest {
        val (world, library) = installing(2)
        val other = library.emptyProfile()
        val two = world.record("mod-2")
        world.files.putBytes(root, two.offPath(), bytes)
        library.switchProfile(other).done(); runCurrent()
        assertEquals(LibraryProblem.NameTaken(two.offPath()), library.failed())
        assertEquals(main, world.applied().active)
        assertEquals(setOf("pak01_dir.vpk", "pak02_dir.vpk"), world.files.addonsFiles(root).keys)
        assertEquals(Sha256.image(bytes), world.files.filesIn(root, SetFolder.Off("mod-2"))[world.offName(two)])
    }

    @Test fun sourceReplacedAfterPreflightNeverReportsSuccessfulDisable() = runTest {
        val (world, library) = installing(1)
        val other = library.emptyProfile()
        world.files.around = { point ->
            if (point == FaultPoint.Remove("game/citadel/addons/pak01_dir.vpk")) {
                world.files.around = null
                world.files.putBytes(root, "game/citadel/addons/pak01_dir.vpk", bytes)
            }
        }
        library.switchProfile(other).done(); runCurrent()
        assertEquals(LibraryProblem.PlacementFailed(ChangedWhileMoved), library.failed())
        assertEquals(main, world.applied().active)
        assertTrue(world.record().enabled)
        assertEquals(Sha256.image(bytes), world.files.addonsFiles(root)["pak01_dir.vpk"])
        assertEquals(1, world.files.addonsFiles(root).keys.count { ".hideout-" in it }, "the mod's own file stays under its anchor")
        assertNull(world.store.manifest!!.pending)
    }

    @Test fun interruptedBatchPhasesRecoverWithOneCommittedProfileAndThenReachTheSavedOne() = runTest {
        for (step in listOf(MoveStep.Anchoring, MoveStep.Releasing, MoveStep.Linking, MoveStep.Cleanup)) {
            val world = World().apply { offer(); offer(2) }
            val doomed = with(world) { crashableScope() }
            val library = with(world) { library(doomed) }
            library.install("mod-1"); library.install("mod-2"); runCurrent()
            val other = library.emptyProfile()
            world.store.afterStore = { manifest ->
                if ((manifest.pending as? PendingChange.Arrange)?.step == step) throw SimulatedCrash(FaultPoint.ReplaceConfig)
            }
            library.switchProfile(other).done(); runCurrent()
            doomed.cancel()
            world.store.afterStore = {}
            assertEquals(step, assertIs<PendingChange.Arrange>(world.store.manifest!!.pending).step)
            val saves = world.store.saved.size
            val restarted = with(world) { library() }
            assertNull(world.store.manifest!!.pending, step.name)
            val settled = world.store.saved.drop(saves).first { it.pending == null }
            val rolledBack = step == MoveStep.Anchoring
            assertEquals(if (rolledBack) main else other, settled.profiles.active, "${step.name}: one whole profile is committed first")
            assertEquals(other, restarted.state.value.profiles!!.active)
            assertEquals(other, world.applied().active, "${step.name}: then the saved one is reached")
            assertEquals(emptySet(), world.files.addonsFiles(root).keys, step.name)
            assertEquals(0, world.store.manifest!!.records.count { it.enabled })
        }
    }

    @Test fun interruptedReplacementRetainsNewCopiesOutsideExtractionStaging() = runTest {
        for (step in listOf(ReplaceStep.Anchoring, ReplaceStep.Releasing, ReplaceStep.Publishing, ReplaceStep.Cleanup)) {
            val (world, library) = installing(1)
            val old = world.record()
            world.offer(file = 11, content = FakeVpk.directory(seed = 8))
            world.store.refuse = { manifest -> "injected save refusal".takeIf { (manifest.pending as? PendingChange.Replace)?.step == step } }
            assertTrue(changeFilesTo(library, old.installId, 11))
            assertIs<OperationStage.Failed>(library.state.value.operations["mod-1"]!!.stage)
            assertNotNull(world.store.manifest!!.pending)
            world.store.refuse = { null }
            val restarted = with(world) { library() }
            assertNull(world.store.manifest!!.pending)
            val expectedFile = if (step == ReplaceStep.Anchoring) 10L else 11L
            assertEquals(expectedFile, world.record().remoteIdentity.fileId, step.name)
            assertEquals(setOf(old.installId), restarted.state.value.profiles!!.activeProfile.enabled)
            assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys.filter { it.endsWith(".vpk") }.toSet())
        }
    }

    @Test fun replacementPublishFailureRestoresTheOldVersionAndMemberships() = runTest {
        val (world, library) = installing(1)
        val old = world.record()
        world.offer(file = 11, content = FakeVpk.directory(seed = 8))
        world.files.failing += FaultPoint.Link("game/citadel/addons/pak01_dir.vpk")
        assertTrue(changeFilesTo(library, old.installId, 11))
        assertIs<OperationStage.Failed>(library.state.value.operations["mod-1"]!!.stage)
        assertEquals(old, world.record())
        assertNotNull(world.store.manifest!!.pending, "the injected link failure also prevents rollback")
        world.files.failing.clear()
        val restarted = with(world) { library() }
        assertEquals(old, world.record())
        assertNull(world.store.manifest!!.pending)
        assertEquals(Sha256.image(bytes), world.files.addonsFiles(root)["pak01_dir.vpk"])
        assertEquals(setOf(old.installId), restarted.state.value.profiles!!.activeProfile.enabled)
    }

    @Test fun replacementRecoveryRemovesItsCopiesKeepsChangedOnesAndReleasesTheJournal() = runTest {
        for (step in listOf(ReplaceStep.Staging, ReplaceStep.RollingBack)) {
            for (complete in listOf(false, true)) {
                val (world, library) = installing(1)
                val old = world.record()
                val replacement = FakeVpk.directory(seed = 8)
                val image = Sha256.image(replacement)
                val target = old.withOnly { copy(files = files.map { it.copy(image = image) }) }
                val pending = PendingChange.Replace(old, target, emptySet(), ChangeToken("crash"), step)
                val temporary = "game/citadel/addons/${pending.token.temporaryName(target.files.single())}"
                val foreign = if (complete) replacement else replacement.copyOf(7)
                world.files.putBytes(root, temporary, foreign)
                if (step == ReplaceStep.RollingBack) {
                    val file = old.files.single()
                    val original = GameEntry(root, old.setFolder(), file.name)
                    val anchor = GameEntry(root, old.setFolder(), pending.token.anchorName(file))
                    assertEquals(LinkResult.Linked, world.files.link(original, anchor))
                    assertEquals(Removal.Removed, world.files.removeTwin(original, anchor, file.image))
                }
                world.store.save(world.store.manifest!!.copy(pending = pending))

                val restarted = with(world) { library() }
                assertNull(world.store.manifest!!.pending, "$step complete=$complete")
                assertEquals(old, world.record())
                assertEquals(Sha256.image(bytes), world.files.addonsFiles(root)["pak01_dir.vpk"])
                assertEquals(Sha256.image(foreign).takeIf { !complete }, world.files.addonsFiles(root)[pending.token.temporaryName(target.files.single())], "$step complete=$complete: a copy holding its bytes goes, a changed one stays")
                assertTrue(restarted.state.value.canModify)
                restarted.renameProfile(main, "Recovered").done()
            }
        }
    }

    @Test fun terminalMaintenanceAllowsProfileAndLibraryActions() = runTest {
        val (world, library) = installing(1)
        library.restoreOriginal(assertNotNull(library.restoreConfirmation()))
        assertEquals(true, library.state.value.maintenance?.running)
        runCurrent()
        assertEquals(RestoreStage.Restored, assertIs<Maintenance.Restore>(library.state.value.maintenance).stage)
        assertTrue(library.state.value.canModify)
        library.renameProfile(main, "Available").done()
        library.setEnabled(world.record().installId, false); runCurrent()
        assertFalse(world.record().enabled)
        assertTrue(library.state.value.canModify)
    }

    @Test fun cancellingTheCallerAfterAdmissionStillSavesAndAppliesTheWholeProfile() = runTest {
        val world = World().apply { offer() }
        val gate = CompletableDeferred<Unit>()
        var admitted = false
        world.disk = object : LibraryStore by world.store {
            override suspend fun save(manifest: LibraryManifest): ManifestSave {
                if (!admitted && manifest.savedChoices?.target?.active?.value?.startsWith("id") == true) {
                    admitted = true
                    gate.await()
                }
                return world.store.save(manifest)
            }
        }
        val library = with(world) { library() }
        library.install("mod-1"); runCurrent()
        val other = library.emptyProfile()
        runCurrent()
        val caller = launch { library.switchProfile(other) }
        runCurrent()
        assertTrue(admitted)
        assertTrue(library.state.value.saving)
        assertTrue(library.state.value.canPlay, "Play takes the files as they are")
        caller.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue(caller.isCancelled)
        assertTrue(caller.isCompleted)
        assertNull(world.store.manifest!!.pending)
        assertEquals(other, library.state.value.profiles!!.active)
        assertFalse(world.record().enabled)
        assertEquals(emptyMap(), world.files.addonsFiles(root))
        assertEquals(Sha256.image(bytes), world.files.filesIn(root, SetFolder.Off("mod-1"))[world.offName()])
        assertTrue(library.state.value.canModify)
    }

    @Test fun aCrashAfterEachIndividualMoveAcrossRootsFinishesTheWholeSwitch() = runTest {
        val second = GameRoot("/games/OtherDeadlock")
        for (move in 1..2) {
            val world = World(listOf(root, second)).apply { offer(); offer(2) }
            val doomed = with(world) { crashableScope() }
            val library = with(world) { library(doomed) }
            library.install("mod-1"); runCurrent()
            assertEquals(FolderCheck.Valid(second), library.useGameFolder(second.path))
            library.install("mod-2"); runCurrent()
            val before = world.store.manifest!!.records
            assertEquals(setOf(root, second), before.map { it.gameRoot }.toSet())
            val other = library.emptyProfile()
            var linked = 0
            world.files.around = { point ->
                if (point is FaultPoint.LinkMade && "/hideout_off/" in point.path && ++linked == move) throw SimulatedCrash(point)
            }
            library.switchProfile(other).done(); runCurrent()
            doomed.cancel()
            assertNotNull(world.store.manifest!!.pending)
            world.files.around = null
            val saves = world.store.saved.size
            val restarted = with(world) { library() }
            assertNull(world.store.manifest!!.pending)
            assertEquals(before.map { it.placed(Placement.Off) }, world.store.saved.drop(saves).first { it.pending == null }.records, "the interrupted switch is finished as a whole")
            assertEquals(other, restarted.state.value.profiles!!.active)
            assertEquals(emptyMap(), world.files.addonsFiles(root))
            assertEquals(emptyMap(), world.files.addonsFiles(second))
            assertTrue(world.store.manifest!!.records.none { it.enabled })
        }
    }

    @Test fun aTargetReplacedInTheSecondGameFolderRollsTheWholeSwitchBackAndLeavesTheForeignFile() = runTest {
        val second = GameRoot("/games/OtherDeadlock")
        val world = World(listOf(root, second)).apply { offer(); offer(2) }
        val library = with(world) { library() }
        library.install("mod-1"); runCurrent()
        assertEquals(FolderCheck.Valid(second), library.useGameFolder(second.path))
        library.install("mod-2"); runCurrent()
        val before = world.store.manifest!!.records
        val other = library.emptyProfile()
        val foreign = FakeVpk.directory(seed = 93)
        val target = world.record("mod-2").offPath()
        world.files.around = { point ->
            if (point == FaultPoint.LinkMade(target)) {
                world.files.around = null
                world.files.putBytes(second, target, foreign)
            }
        }
        library.switchProfile(other).done(); runCurrent()
        assertEquals(LibraryProblem.PlacementFailed(ChangedWhileMoved), library.failed())
        for (game in listOf(root, second)) assertEquals(Sha256.image(bytes), world.files.addonsFiles(game)["pak01_dir.vpk"], "${game.path}: its source is back")
        assertEquals(FileObservation.Present(foreign.size.toLong(), sha256Hex(foreign)), world.files.file(second, target))
        assertEquals(main, world.store.manifest!!.profiles.active)
        assertEquals(before, world.store.manifest!!.records)
        assertNull(world.store.manifest!!.pending)

        world.files.remove(second, target)
        val restarted = with(world) { library() }
        runCurrent()
        assertNull(world.store.manifest!!.pending)
        assertEquals(other, restarted.state.value.profiles!!.active)
        assertEquals(emptyMap(), world.files.addonsFiles(root))
        assertEquals(emptyMap(), world.files.addonsFiles(second))
        assertTrue(world.store.manifest!!.records.none { it.enabled })
    }

    @Test fun failedBatchSaveLeavesEveryMembershipAndFileAtTheOldProfile() = runTest {
        val (world, library) = installing(1)
        val other = library.emptyProfile()
        world.store.refuse = { "disk full" }
        assertEquals(MutationResult.Refused(LibraryProblem.ManifestSave("disk full")), library.switchProfile(other))
        runCurrent()
        assertEquals(main, library.state.value.profiles!!.active)
        assertEquals(main, library.state.value.shownProfiles!!.active)
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)
    }

    @Test fun profileEditsAndSameMembershipSwitchesReadNoSetsAndKeepHealthFlags() = runTest {
        val world = World().apply { offer(); offer(2); offer(3) }
        with(world) { library() }.let { first -> first.install("mod-1"); first.install("mod-2"); first.install("mod-3"); runCurrent() }
        val ids = world.applied().activeProfile.orders.getValue(root)
        world.files.putBytes(root, "game/citadel/addons/pak01_dir.vpk", FakeVpk.directory(seed = 9))
        world.files.failFile(root, "game/citadel/addons/pak02_dir.vpk", "permission denied")
        val library = with(world) { library() }
        assertEquals(mapOf(ids[0] to FilesDamage.Changed), library.ready().damaged)
        assertEquals(setOf(ids[1]), library.ready().unchecked)
        val addons = world.files.addonsFiles(root)
        world.reset()

        val copy = library.createProfile(NewProfile("Copy", ProfileColour.Blue, ProfileSeed.Copy(main), false)).done()
        library.renameProfile(copy, "Ranked").done()
        library.recolourProfile(copy, ProfileColour.Rose).done()
        assertEquals(MutationResult.Refused(LibraryProblem.ProfileNameTaken), library.renameProfile(copy, "main"))
        val duplicate = library.duplicateProfile(copy).done()
        library.setMembership(duplicate, ids[2], false).done()
        library.deleteProfile(duplicate).done()
        val switched = assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(copy))
        assertEquals(SwitchSummary(copy, 0, 0), switched.value)
        library.switchProfile(main).done()
        library.switchProfile(copy).done()

        assertEquals(emptyList(), world.reads)
        assertEquals(copy, library.state.value.profiles!!.active)
        assertEquals(ids.toSet(), library.state.value.profiles!!.activeProfile.enabled)
        assertEquals(listOf("Main", "Ranked"), library.state.value.profiles!!.profiles.map { it.name })
        assertEquals(addons, world.files.addonsFiles(root))
        assertNull(world.store.manifest!!.pending)
        assertEquals(mapOf(ids[0] to FilesDamage.Changed), library.ready().damaged)
        assertEquals(setOf(ids[1]), library.ready().unchecked)
        assertNull(library.ready().interrupted)

        runCurrent()
        assertEquals(emptyList(), world.reads)
        world.files.putBytes(root, "game/citadel/addons/pak03_dir.vpk", FakeVpk.directory(seed = 10))
        val restarted = with(world) { library() }
        assertEquals(mapOf(ids[0] to FilesDamage.Changed, ids[2] to FilesDamage.Changed), restarted.ready().damaged)
        assertEquals(setOf(ids[1]), restarted.ready().unchecked)
        assertEquals(copy, restarted.state.value.profiles!!.active)
    }

    @Test fun turningOneSetReadsOnlyThatSet() = runTest {
        val (world, library) = installing(3)
        val ids = world.store.manifest!!.records.map { it.installId }
        val copy = library.createProfile(NewProfile("Copy", ProfileColour.Blue, ProfileSeed.Copy(main), true)).done()
        world.reset()

        val two = world.record("mod-2")
        library.setMembership(copy, ids[1], false).done(); runCurrent()
        assertEquals(setOf("pak02", world.offName(two).substringBefore('_')), world.readPaks(), "its file on at 02 and its name while off")
        assertEquals(setOf("pak01_dir.vpk", "pak03_dir.vpk"), world.files.addonsFiles(root).keys)
        assertEquals(setOf(world.offName(two)), world.files.filesIn(root, SetFolder.Off("mod-2")).keys)
        assertEquals(setOf(ids[0], ids[2]), library.state.value.profiles!!.activeProfile.enabled)
        assertEquals(listOf(true, false, true), world.store.manifest!!.records.map { it.enabled })

        world.reset()
        library.switchProfile(main).done(); runCurrent()
        assertEquals(setOf("pak02", world.offName(two).substringBefore('_')), world.readPaks())
        assertEquals(main, library.state.value.profiles!!.active)
        assertEquals(setOf("pak01_dir.vpk", "pak02_dir.vpk", "pak03_dir.vpk"), world.files.addonsFiles(root).keys)
        assertTrue(world.store.manifest!!.records.all { it.enabled })
        assertNull(world.store.manifest!!.pending)
        assertEquals(emptyMap(), library.ready().damaged)
        assertEquals(emptySet(), library.ready().unchecked)
    }

    @Test fun aSwitchOverAFileChangedMeanwhileHoldsThatModWhereItIsAndSwitchesTheRest() = runTest {
        val damaged = FakeVpk.directory(seed = 9)
        for (duringRelease in listOf(false, true)) {
            val (world, library) = installing(3)
            val ids = world.store.manifest!!.records.map { it.installId }
            val other = library.createProfile(NewProfile("Other", ProfileColour.Blue, ProfileSeed.Copy(main), false)).done()
            library.setMembership(other, ids[1], false).done()
            val path = "game/citadel/addons/pak02_dir.vpk"
            if (duringRelease) world.files.around = { point ->
                if (point == FaultPoint.Remove(path)) {
                    world.files.around = null
                    world.files.putBytes(root, path, damaged)
                }
            } else world.files.putBytes(root, path, damaged)
            world.reset()

            library.switchProfile(other).done(); runCurrent()
            assertNull(library.failed(), "duringRelease=$duringRelease: nothing waits on the edit")
            assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "duringRelease=$duringRelease")
            assertTrue("pak02" in world.readPaks(), "duringRelease=$duringRelease: the changed file was read")
            assertEquals(other, world.applied().active, "duringRelease=$duringRelease: the switch is made")
            assertEquals(ids.toSet(), world.applied().activeProfile.enabled, "duringRelease=$duringRelease: the edited mod stays on where it is")
            assertTrue(world.store.manifest!!.records.all { it.enabled })
            assertEquals(Placement.On(PakNumber(2)), world.record("mod-2").placement)
            assertTrue(world.record("mod-2").edited)
            assertEquals(Sha256.image(damaged), world.files.addonsFiles(root)["pak02_dir.vpk"])
            assertEquals(mapOf(ids[1] to FilesDamage.Changed), library.ready().damaged)
            assertNull(world.store.manifest!!.pending, "duringRelease=$duringRelease")
            assertNull(library.ready().interrupted, "duringRelease=$duringRelease")
        }
    }

    @Test fun aSwitchIsSavedAtOnceAndItsFilesFollowOnlyOnceDeadlockIsSeenClosed() = runTest {
        val (world, library) = installing(1)
        val empty = library.emptyProfile()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        val result = assertIs<MutationResult.Done<SwitchSummary>>(library.switchProfile(empty))
        assertEquals(SwitchSummary(empty, 1, 0), result.value)
        assertEquals(empty, library.state.value.profiles!!.active, "saved before the running programs are read")
        assertEquals(empty, world.store.manifest!!.savedChoices!!.target.active)
        assertEquals(main, world.applied().active, "the files have not followed yet")
        runCurrent()
        assertTrue(library.state.value.applying)
        assertTrue(library.state.value.canPlay, "Play takes the files as they are")
        assertTrue(library.state.value.canEditChoices)
        val renamed = launch { library.renameProfile(empty, "Later").done() }
        runCurrent()
        assertTrue(library.state.value.saving, "an edit waits for the files' lock to save")
        assertEquals("Later", library.shown().profiles.last().name)
        assertEquals("Empty", library.state.value.profiles!!.profiles.last().name)
        assertTrue(world.record().enabled)
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)

        gate.complete(Unit); runCurrent()
        renamed.join()
        assertFalse(library.state.value.applying)
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(empty, library.state.value.profiles!!.active)
        assertEquals(library.state.value.profiles, library.state.value.shownProfiles)
        assertEquals("Later", world.applied().profiles.last().name)
        assertFalse(world.record().enabled)
        assertEquals(emptySet(), world.files.addonsFiles(root).keys)
    }

    @Test fun aSwitchSavedAsDeadlockStartsWaitsAndAppliesOnceItCloses() = runTest {
        val (world, library) = installing(1)
        val empty = library.emptyProfile()
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        library.switchProfile(empty).done()
        world.processes.scan = ProcessScan.Seen(game = true)
        gate.complete(Unit); runCurrent()
        assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync)
        assertFalse(library.state.value.applying)
        assertEquals(empty, library.state.value.profiles!!.active)
        assertTrue(world.record().enabled)
        assertNull(world.store.manifest!!.pending)

        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2.seconds); runCurrent()
        assertEquals(empty, world.applied().active)
        assertFalse(world.record().enabled)
    }

    @Test fun aToggleSavedWhileRunningKeepsTheProfileItWasAskedForThroughALaterSwitch() = runTest {
        val (world, library) = installing(1)
        val id = world.record().installId
        val other = library.createProfile(NewProfile("Other", ProfileColour.Blue, ProfileSeed.Copy(main), false)).done()
        runCurrent()
        world.processes.scan = ProcessScan.Seen(game = true)
        library.setEnabled(id, false); runCurrent()
        assertEquals(listOf(emptySet(), setOf(id)), library.state.value.profiles!!.profiles.map { it.enabled }, "saved for the profile active when asked")
        library.switchProfile(other).done()
        assertTrue(world.record().enabled)

        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2.seconds); runCurrent()
        assertEquals(listOf(emptySet(), setOf(id)), world.applied().profiles.map { it.enabled })
        assertTrue(world.record().enabled, "the other profile, active now, has it on")
        library.switchProfile(main).done(); runCurrent()
        assertFalse(world.record().enabled)
    }

    @Test fun rapidTogglesSettleOnTheLatestWhileAnotherSetTurnsAlongside() = runTest {
        val (world, library) = installing(2)
        val (first, second) = world.store.manifest!!.records.map { it.installId }
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        world.files.linkedPaths.clear()
        library.setEnabled(first, false); runCurrent()
        assertTrue(library.state.value.canEditChoices, "other sets can be turned while one turns")
        assertTrue(library.state.value.canPlay, "Play takes the files as they are")
        library.setEnabled(first, true)
        library.setEnabled(first, false)
        library.setEnabled(first, true)
        library.setEnabled(second, false)
        assertEquals(setOf(first), library.shown().activeProfile.enabled)
        assertEquals(setOf(second), library.state.value.profiles!!.activeProfile.enabled, "only the first turn is saved while the files are held")
        assertTrue(world.store.manifest!!.records.all { it.enabled })

        world.processes.gate = null
        gate.complete(Unit); runCurrent()
        assertEquals(emptyMap(), library.state.value.changes)
        assertEquals(listOf(true, false), world.store.manifest!!.records.map { it.enabled })
        assertEquals(setOf(first), library.state.value.profiles!!.activeProfile.enabled)
        assertEquals(library.state.value.profiles, library.state.value.shownProfiles)
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)
        assertEquals(1, world.files.linkedPaths.count { it == world.record("mod-1").offPath() }, "the running turn finished once")
        assertEquals(1, world.files.linkedPaths.count { it == "game/citadel/addons/pak01_dir.vpk" }, "only the latest position followed it")
        assertFalse(library.state.value.applying)
    }

    @Test fun aToggleAskedForDuringAnUninstallIsSavedAndAppliedBeforeItsFilesAreTouched() = runTest {
        val (world, library) = installing(2)
        val (first, second) = world.store.manifest!!.records.map { it.installId }
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        library.uninstall(assertNotNull(library.uninstallConfirmation(first)), deleteDownload = false)
        runCurrent()
        assertTrue(library.state.value.canEditChoices)
        library.setEnabled(second, false)
        assertNull(library.state.value.choiceProblem)
        assertEquals(setOf(first), library.shown().activeProfile.enabled)
        assertEquals(setOf("pak01_dir.vpk", "pak02_dir.vpk"), world.files.addonsFiles(root).keys)

        world.processes.gate = null
        gate.complete(Unit); runCurrent()
        assertNull(library.state.value.changes[first])
        assertEquals(second, world.store.manifest!!.records.single().installId)
        assertFalse(world.store.manifest!!.records.single().enabled)
        assertEquals(emptySet(), world.files.addonsFiles(root).keys)
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
    }

    @Test fun damageJustAfterATurnSettlesIsFlaggedThoughTheTurnIsDone() = runTest {
        for (removed in listOf(false, true)) for (bySwitch in listOf(false, true)) {
            val case = "removed=$removed bySwitch=$bySwitch"
            val world = World().apply { offer() }
            var armed = false
            var cleaning = false
            var damaged = false
            world.disk = object : LibraryStore by world.store {
                override suspend fun save(manifest: LibraryManifest): ManifestSave {
                    val target = manifest.records.singleOrNull()?.offPath()
                    val saved = world.store.save(manifest)
                    if (!armed || target == null) return saved
                    if ((manifest.pending as? PendingChange.Arrange)?.step == MoveStep.Cleanup) cleaning = true
                    else if (cleaning && manifest.pending == null && !damaged) {
                        damaged = true
                        if (removed) world.files.remove(root, target) else world.files.putBytes(root, target, FakeVpk.directory(seed = 94))
                    }
                    return saved
                }
            }
            val library = with(world) { library() }
            library.install("mod-1"); runCurrent()
            armed = true
            val id = world.record().installId
            if (bySwitch) {
                val empty = library.emptyProfile()
                library.switchProfile(empty).done(); runCurrent()
            } else {
                library.setEnabled(id, false); runCurrent()
            }
            assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, case)
            assertTrue(damaged, case)
            assertFalse(world.record().enabled, case)
            assertNull(world.store.manifest!!.pending, case)
            assertEquals(mapOf(id to if (removed) FilesDamage.Missing else FilesDamage.Changed), library.ready().damaged, case)
        }
    }
}
