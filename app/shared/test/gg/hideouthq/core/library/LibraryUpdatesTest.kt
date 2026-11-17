package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.Withdrawal
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SimulatedCrash
import gg.hideouthq.core.data.gamebanana.FactsAnswer
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryUpdatesTest {
    private val root = GameRoot("/games/Deadlock")
    private val lilith = SubmissionRef(SubmissionKind.Mod, 551_201)
    private val main = ProfileId("main")

    private val old = FakeVpk.directory(seed = 1)
    private val new = FakeVpk.directory(seed = 2)
    private val other = FakeVpk.directory(seed = 3)

    private fun remote(id: Long, md5: String?, name: String = "lilith_$id.zip", downloads: Long = 1_000) =
        RemoteFile(id, name, 1_000, md5, null, downloads, addedAt = 1_777_000_000, downloadUrl = "https://gamebanana.com/dl/$id")

    private inner class World(seed: List<ManifestRecord> = emptyList()) {
        val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo) }
        val store = InMemoryLibraryStore(seed.takeIf { it.isNotEmpty() }?.let { LibraryManifest(records = it) })
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        val processes = FakeProcesses()
        var client: GameBananaClient = banana
        private var nextId = 0

        init {
            seed.forEach { record -> record.files.forEach { files.putFile(root, it.relativePath(record), it.sizeBytes, it.sha256) } }
        }

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(
                FakeGameLocator(listOf(root.path), mapOf(root.path to null)),
                Preferences(MemoryStorage()),
                store,
                files,
                InstallPorts(client, downloader, files, files, processes),
                scope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
            ),
        )

        fun list(vararg files: RemoteFile, complete: Boolean = true, submission: SubmissionRef = lilith) {
            banana.listings = banana.listings + (submission to FileListing.Available("Lilith, the Hell Warden", files.toList(), complete))
        }

        fun manifest(): LibraryManifest = assertNotNull(store.manifest)

        fun record(submission: SubmissionRef = lilith): ManifestRecord = manifest().records.single { it.modId == submission.modId }

        fun updates(submission: SubmissionRef = lilith): InstallationUpdates {
            val listing = assertIs<FileListing.Available>(banana.listings.getValue(submission))
            val record = record(submission)
            val facts = FileFacts(0, null, null, files = listing.files.map { SubmissionFileOption(it.id, it.md5, it.fileName, it.description, it.sizeBytes) }, filesComplete = listing.complete)
            val targets = record.sets.mapNotNull { (it.source as? InstalledSource.Remote)?.identity }.toSet()
            return InstalledUpdates.of(manifest().records, mapOf(submission to FilesObservation(FactsAnswer.Read(facts), targets))).getValue(record.installId)
        }

        fun plan(submission: SubmissionRef = lilith): UpdatePlan = assertNotNull(updates(submission).plan, "an update Update all may carry out")
    }

    private fun TestScope.install(library: ModLibrary, vararg fileIds: Long, submission: SubmissionRef = lilith) {
        library.install(InstallRequest(InstallInput.Remote(submission, fileIds.toSet()), setOf(main)))
        runCurrent()
    }

    private fun ModLibrary.stage(submission: SubmissionRef = lilith): OperationStage? = state.value.operations[submission.modId]?.stage

    private fun ModLibrary.failure(): LibraryProblem = assertIs<OperationStage.Failed>(stage()).problem

    private fun pak(number: Int) = "pak${number.toString().padStart(2, '0')}_dir.vpk"

    private fun TestScope.installedThen(world: World, vararg now: RemoteFile, scope: CoroutineScope = backgroundScope): ModLibrary {
        world.list(remote(101, Md5A))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to old))
        val library = with(world) { library(scope) }
        install(library, 101)
        world.list(*now)
        world.downloader.requested.clear()
        return library
    }

    private fun neighbour(id: String, number: Int) = oneSetRecord(
        id, root, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 9_000L + number, 9_000L + number, null)), "Neighbour $id", null,
        ArchiveIdentity("/downloads/$id.zip", 1, "a".repeat(64)), number,
        listOf(directory(FileImage(64, "b".repeat(64)))),
    )

    @Test
    fun aFileUnderANewMd5IsReplacedInItsPlaceKeepingNumberMembershipAndOrder() = runTest {
        val world = World(seed = listOf(neighbour("x", 2)))
        val library = installedThen(world, remote(101, Md5B))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to new))
        val before = world.record()
        val updates = world.updates()
        assertEquals(SourceState.Changed(SubmissionFileOption(101, Md5B, "lilith_101.zip", null, 1_000)), updates.sources.single().state)

        assertTrue(library.update(updates.plan!!))
        assertEquals(before.installId, library.state.value.operations[lilith.modId]?.installId, "the operation belongs to the installation it changes")
        runCurrent()

        val after = world.record()
        assertEquals(SubmissionIdentity(SubmissionKind.Mod, lilith.id, 101, Md5B), after.remoteIdentity)
        assertEquals(before.installId, after.installId)
        assertEquals(before.numbers, after.numbers)
        assertTrue(after.enabled)
        assertEquals(setOf(before.installId, "x"), world.manifest().profiles.activeProfile.enabled)
        assertEquals(listOf(before.installId, "x"), onLoadOrder(world.manifest().records))
        assertEquals(Sha256.image(new), world.files.addonsFiles(root)[pak(1)])
        assertEquals(listOf(101L), world.downloader.requested)
        assertNull(world.manifest().pending)
        assertNull(library.stage())
    }

    @Test
    fun aSoleSuccessorReplacesTheFileGameBananaNoLongerOffers() = runTest {
        val world = World()
        val library = installedThen(world, remote(201, Md5B, name = "lilith_v2.zip"))
        world.downloader.serve(201, listOf("pak01_dir.vpk" to new))
        val plan = world.plan()

        assertTrue(library.update(plan))
        runCurrent()
        val set = world.record().sets.single()
        assertEquals(SubmissionIdentity(SubmissionKind.Mod, lilith.id, 201, Md5B), world.record().remoteIdentity)
        assertEquals(VersionPosition(1, 1), set.version)
        assertEquals("lilith_v2.zip", set.originalName)
        assertEquals(Sha256.image(new), world.files.addonsFiles(root)[pak(1)])
    }

    @Test
    fun aSetInstalledFromAnIncompleteListingIsNotTakenAsASoleSuccessorsProof() = runTest {
        val world = World()
        world.list(remote(101, Md5A), complete = false)
        world.downloader.serve(101, listOf("pak01_dir.vpk" to old))
        val library = with(world) { library() }
        install(library, 101)
        val installed = world.record()
        assertNull(installed.sets.single().version, "the listing was incomplete, so the position it implied is not trusted")

        world.list(remote(201, Md5B))
        val updates = world.updates()
        assertEquals(SourceState.Review, updates.sources.single().state, "a sole successor is not assumed from an incomplete listing")
        assertNull(updates.plan, "nothing to update automatically")
    }

    @Test
    fun anUpdateIsRefusedWithNothingChangedWhenTheFreshListingNoLongerSaysWhatItWasConfirmedFor() = runTest {
        val cases = mapOf(
            "the old file is offered again" to (listOf(remote(101, Md5A), remote(201, Md5B))) to LibraryProblem.UpdateChanged,
            "another file came beside the successor" to listOf(remote(201, Md5B), remote(202, Md5C)) to LibraryProblem.UpdateChanged,
            "the successor's MD5 changed" to listOf(remote(201, Md5C)) to LibraryProblem.UpdateChanged,
            "the successor was replaced by another" to listOf(remote(202, Md5B)) to LibraryProblem.UpdateChanged,
        )
        for ((case, problem) in cases) {
            val (name, listedNow) = case
            val world = World()
            val library = installedThen(world, remote(201, Md5B))
            world.downloader.serve(201, listOf("pak01_dir.vpk" to new))
            val before = world.record()
            val plan = world.plan()
            world.list(*listedNow.toTypedArray())

            assertTrue(library.update(plan), name)
            runCurrent()
            assertEquals(problem, library.failure(), name)
            assertEquals(before, world.record(), name)
            assertEquals(emptyList(), world.downloader.requested, "$name: nothing is downloaded")
            assertEquals(Sha256.image(old), world.files.addonsFiles(root)[pak(1)], name)
        }
    }

    @Test
    fun aListingWithAFileHideoutCannotReadOrAWithdrawnModIsRefusedWithTheModKept() = runTest {
        val world = World()
        val library = installedThen(world, remote(201, Md5B))
        val plan = world.plan()
        val before = world.record()

        world.list(remote(201, Md5B), complete = false)
        assertTrue(library.update(plan))
        runCurrent()
        assertEquals(LibraryProblem.ListingIncomplete, library.failure())
        assertEquals(before, world.record())

        library.dismiss(lilith.modId)
        world.banana.listings = mapOf(lilith to FileListing.Withdrawn(Withdrawal.Trashed))
        assertTrue(library.update(plan))
        runCurrent()
        assertEquals(LibraryProblem.Withdrawn(Withdrawal.Trashed), library.failure())
        assertEquals(before, world.record())
        assertEquals(emptyList(), world.downloader.requested)
    }

    @Test
    fun aConfirmationMadeBeforeTheModsFilesChangedIsRefusedButTurningItOffIsNoChange() = runTest {
        val world = World()
        val library = installedThen(world, remote(101, Md5B))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to new))
        val made = world.plan()

        library.setEnabled(world.record().installId, false)
        runCurrent()
        assertFalse(world.record().enabled)
        assertTrue(library.update(made), "turning the mod off does not make a confirmation stale")
        runCurrent()
        assertNull(library.stage())
        assertEquals(Md5B, world.record().remoteIdentity.fileMd5)
        assertEquals(Sha256.image(new), world.files.filesIn(root, SetFolder.Off(lilith))[world.record().files.single().name], "updated in its off folder")

        world.list(remote(101, Md5C), remote(102, Md5A))
        world.downloader.serve(102, listOf("pak01_dir.vpk" to other))
        val stale = world.plan()
        assertTrue(changeFilesTo(library, world.record().installId, 102))
        val changed = world.record()
        assertTrue(library.update(stale))
        runCurrent()
        assertEquals(LibraryProblem.UpdateChanged, library.failure(), "the mod holds another file than the plan was made for")
        assertEquals(changed, world.record())

        library.dismiss(lilith.modId)
        world.list(remote(102, Md5B))
        val elsewhere = world.plan().copy(gameRoot = GameRoot("/games/Other"))
        assertTrue(library.update(elsewhere))
        runCurrent()
        assertEquals(LibraryProblem.UpdateChanged, library.failure(), "a plan for another game")
        assertEquals(changed, world.record())
    }

    @Test
    fun onlyTheFilesThePlanNamesAreReplacedEachInItsPlaceAndAnotherFilesChangeSinceIsLeftAlone() = runTest {
        val world = World()
        world.list(remote(101, Md5A, downloads = 2_000), remote(103, Md5A, downloads = 500))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to old))
        world.downloader.serve(103, listOf("pak01_dir.vpk" to other))
        val library = with(world) { library() }
        install(library, 101, 103)
        val before = world.record()
        assertEquals(listOf(101L, 103L), before.sets.map { it.fileId })

        world.list(remote(101, Md5B, downloads = 2_000), remote(103, Md5A, downloads = 500))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to new))
        val updates = world.updates()
        assertEquals(listOf(SourceState.Changed::class, SourceState.Current::class), updates.sources.map { it.state::class })
        val plan = assertNotNull(updates.plan)
        world.list(remote(101, Md5B, downloads = 2_000), remote(103, Md5C, downloads = 500))
        world.downloader.requested.clear()

        assertTrue(library.update(plan))
        runCurrent()
        val after = world.record()
        assertEquals(listOf(101L, 103L), after.sets.map { it.fileId }, "the replaced file keeps its place as the winner")
        assertEquals(listOf(Md5B, Md5A), after.sets.map { (it.source as InstalledSource.Remote).identity.fileMd5 }, "103 changed since, but the plan did not name it")
        assertEquals(before.sets[1], after.sets[1], "the set the plan did not name is untouched")
        assertEquals(listOf(101L), world.downloader.requested)
    }

    @Test
    fun changingFilesKeepsAFileDownloadedAgainUnderANewMd5InItsPlace() = runTest {
        val world = World()
        world.list(remote(101, Md5A, downloads = 2_000), remote(103, Md5A, downloads = 500))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to old))
        world.downloader.serve(103, listOf("pak01_dir.vpk" to other))
        val library = with(world) { library() }
        install(library, 101, 103)
        world.list(remote(101, Md5B, downloads = 2_000), remote(103, Md5A, downloads = 500))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to new))

        assertTrue(changeFilesTo(library, world.record().installId, 101, 103))
        val after = world.record()
        assertEquals(listOf(101L, 103L), after.sets.map { it.fileId }, "the winner stays the winner, not moved after the file kept")
        assertEquals(listOf(Md5B, Md5A), after.sets.map { (it.source as InstalledSource.Remote).identity.fileMd5 })
    }

    private fun model(seed: Int) = FakeVpk.of(mapOf("models/lilith.vmdl_c" to byteArrayOf(seed.toByte())))

    @Test
    fun aNewArchiveWhosePartsTheModLacksAllShareFilesWithOnesItHasKeepsExactlyThoseInTheirOrderWithoutAsking() = runTest {
        val world = World()
        val (a, b, c, d) = (10..13).map(::model)
        world.list(remote(104, Md5A))
        world.downloader.serve(104, listOf("A/pak01_dir.vpk" to a, "B/pak01_dir.vpk" to b, "C/pak01_dir.vpk" to c))
        val library = with(world) { library() }
        install(library, 104)
        val choice = assertIs<OperationStage.ChoosingSet>(library.stage())
        assertTrue(library.answer(choice.id, ChoiceAnswer.Sets(listOf("C/pak01_dir.vpk", "A/pak01_dir.vpk"), setOf(main))))
        runCurrent()
        val before = world.record()

        val (a2, c2) = model(20) to model(22)
        world.list(remote(104, Md5B))
        world.downloader.serve(104, listOf("A/pak01_dir.vpk" to a2, "B/pak01_dir.vpk" to b, "C/pak01_dir.vpk" to c2, "D/pak01_dir.vpk" to d))
        assertTrue(library.update(world.plan()))
        runCurrent()

        val after = world.record()
        assertNull(library.stage(), "nothing is asked: every installed component is still there, and the others would not start ticked")
        assertEquals(listOf("C/pak01_dir.vpk", "A/pak01_dir.vpk"), after.sets.map { it.componentKey }, "the same components in the same order, the new one left out")
        assertEquals(before.numbers, after.numbers)
        assertEquals(listOf(Sha256.image(c2), Sha256.image(a2)), after.numbers.map { world.files.addonsFiles(root)[pak(it.value)] })
    }

    @Test
    fun aNewArchiveWithAnAddOnTheModLacksAsksWithItsOldPartsAndTheAddOnTicked() = runTest {
        val world = World()
        world.list(remote(104, Md5A))
        world.downloader.serve(104, listOf("pak05_dir.vpk" to old))
        val library = with(world) { library() }
        install(library, 104)
        assertNull(library.stage(), "one part installs without a question")

        world.list(remote(104, Md5B))
        world.downloader.serve(104, listOf("pak05_dir.vpk" to new, "sounds/pak06_dir.vpk" to other))
        assertTrue(library.update(world.plan()))
        runCurrent()
        val asking = assertIs<OperationStage.ChoosingSet>(library.stage(), "the new part is not dropped unseen")
        assertEquals(listOf("pak05_dir.vpk", "sounds/pak06_dir.vpk"), asking.preselected)
        assertTrue(library.answer(asking.id, ChoiceAnswer.Sets(asking.preselected, setOf(main))))
        runCurrent()
        assertEquals(listOf("pak05_dir.vpk", "sounds/pak06_dir.vpk"), world.record().sets.map { it.componentKey })
        assertEquals(listOf(Sha256.image(new), Sha256.image(other)), world.record().numbers.map { world.files.addonsFiles(root)[pak(it.value)] })
    }

    private fun TestScope.installedWithoutSounds(world: World): ModLibrary {
        world.list(remote(104, Md5A))
        world.downloader.serve(104, listOf("pak05_dir.vpk" to old, "sounds/pak06_dir.vpk" to other))
        val library = with(world) { library() }
        install(library, 104)
        val first = assertIs<OperationStage.ChoosingSet>(library.stage())
        assertEquals(listOf("pak05_dir.vpk", "sounds/pak06_dir.vpk"), first.preselected, "add-ons start ticked")
        assertTrue(library.answer(first.id, ChoiceAnswer.Sets(listOf("pak05_dir.vpk"), setOf(main))))
        runCurrent()
        return library
    }

    @Test
    fun aPartLeftOutIsNotAskedAboutAgainByUpdatesThatBringNothingNew() = runTest {
        val world = World()
        val library = installedWithoutSounds(world)
        assertEquals(mapOf(104L to setOf("sounds/pak06_dir.vpk")), world.record().declined, "the mod remembers the part left out")

        world.list(remote(104, Md5B))
        world.downloader.serve(104, listOf("pak05_dir.vpk" to new, "sounds/pak06_dir.vpk" to other))
        assertTrue(library.update(world.plan()))
        runCurrent()
        assertNull(library.stage(), "nothing is asked: the only part it lacks was left out before")
        assertEquals(listOf("pak05_dir.vpk"), world.record().sets.map { it.componentKey })
        assertEquals(listOf(Sha256.image(new)), world.record().numbers.map { world.files.addonsFiles(root)[pak(it.value)] }, "the part it has is updated")
        assertEquals(mapOf(104L to setOf("sounds/pak06_dir.vpk")), world.record().declined)

        val music = FakeVpk.directory(seed = 4)
        world.list(remote(105, Md5C))
        world.downloader.serve(105, listOf("pak05_dir.vpk" to new, "sounds/pak06_dir.vpk" to other, "music/pak07_dir.vpk" to music))
        assertTrue(library.update(world.plan()))
        runCurrent()
        val asking = assertIs<OperationStage.ChoosingSet>(library.stage(), "a part new since the last answer is asked about, even under a successor's id")
        assertEquals(listOf("pak05_dir.vpk", "music/pak07_dir.vpk"), asking.preselected, "the sounds start unticked, as last chosen")
        assertTrue(library.answer(asking.id, ChoiceAnswer.Sets(asking.preselected, setOf(main))))
        runCurrent()
        assertEquals(listOf("pak05_dir.vpk", "music/pak07_dir.vpk"), world.record().sets.map { it.componentKey })
        assertEquals(mapOf(105L to setOf("sounds/pak06_dir.vpk")), world.record().declined, "the successor's file carries what was left out")
    }

    @Test
    fun changeFilesAsksAgainAboutTheFileItLeftPartsOutOfAndATickedPartLeavesThem() = runTest {
        val world = World()
        val library = installedWithoutSounds(world)
        val before = world.record()
        world.downloader.requested.clear()

        assertTrue(changeFilesTo(library, before.installId, 104))
        val unchanged = assertIs<OperationStage.ChoosingSet>(library.stage(), "keeping the same file asks about its parts again")
        assertEquals(listOf("pak05_dir.vpk"), unchanged.preselected, "with the part left out unticked")
        assertTrue(library.answer(unchanged.id, ChoiceAnswer.Sets(unchanged.preselected, setOf(main))))
        runCurrent()
        assertNull(library.stage())
        assertEquals(before, world.record(), "the same answer changes nothing")

        assertTrue(changeFilesTo(library, before.installId, 104))
        val asking = assertIs<OperationStage.ChoosingSet>(library.stage())
        assertEquals(listOf("pak05_dir.vpk", "sounds/pak06_dir.vpk"), asking.options.map { it.key }, "the parts it has come first")
        assertTrue(library.answer(asking.id, ChoiceAnswer.Sets(listOf("pak05_dir.vpk", "sounds/pak06_dir.vpk"), setOf(main))))
        runCurrent()
        val after = world.record()
        assertEquals(listOf("pak05_dir.vpk", "sounds/pak06_dir.vpk"), after.sets.map { it.componentKey }, "the sounds are added after the part it had")
        assertEquals(before.sets.first(), after.sets.first(), "the part it had is kept as it was")
        assertEquals(emptyMap(), after.declined, "a part ticked later is no longer left out")
        assertEquals(emptyList(), world.downloader.requested, "the kept download is unpacked again, nothing is downloaded")
        assertEquals(Sha256.image(other), world.files.addonsFiles(root)[pak(after.numbers[1].value)])
    }

    @Test
    fun whenAnInstalledComponentIsNotInTheNewArchiveTheChoiceKeepsWhatIsLeftOfEachGroupAndCancellingKeepsTheMod() = runTest {
        data class Case(val paths: List<String>, val chosen: List<String>?, val next: List<String>, val preselected: List<String>)
        val cases = listOf(
            Case(listOf("A/", "B/", "C/"), listOf("C/", "A/"), listOf("A/", "B/", "D/"), listOf("A/")),
            Case(listOf(""), null, listOf("X/", "Y/"), listOf("X/")),
            Case(listOf("A/", "B/"), listOf("B/"), listOf("C/"), listOf("C/")),
            Case(listOf("A/", "B/"), listOf("A/", "B/"), listOf("A/"), listOf("A/")),
        )
        fun keys(folders: List<String>) = folders.map { "${it}pak01_dir.vpk" }
        for ((paths, chosen, next, preselected) in cases) {
            val world = World()
            world.list(remote(104, Md5A))
            world.downloader.serve(104, keys(paths).mapIndexed { index, key -> key to model(10 + index) })
            val library = with(world) { library() }
            install(library, 104)
            if (chosen != null) {
                val first = assertIs<OperationStage.ChoosingSet>(library.stage())
                assertTrue(library.answer(first.id, ChoiceAnswer.Sets(keys(chosen), setOf(main))))
                runCurrent()
            }
            val before = world.record()
            val case = "$paths, chose $chosen, now $next"

            world.list(remote(104, Md5B))
            world.downloader.serve(104, keys(next).mapIndexed { index, key -> key to model(20 + index) })
            assertTrue(library.update(world.plan()))
            runCurrent()
            val asking = assertIs<OperationStage.ChoosingSet>(library.stage(), "$case: the components are chosen again")
            assertEquals(keys(preselected), asking.preselected, case)
            assertEquals(before, world.record(), "$case: nothing is replaced before the choice")
            assertTrue(library.answer(asking.id, ChoiceAnswer.Cancel))
            runCurrent()
            assertNull(library.stage(), case)
            assertEquals(before, world.record(), "$case: cancelling keeps the mod as it was")
        }
    }

    @Test
    fun anUnknownOldComponentTicksTheNewArchivesPartsThatShareNoFiles() = runTest {
        val installed = oneSetRecord(
            "existing", root, InstalledSource.Remote(SubmissionIdentity(lilith.kind, lilith.id, 101, Md5A)), "Lilith", null,
            ArchiveIdentity("/downloads/old.zip", 1_000, "a".repeat(64)), 1,
            listOf(directory(Sha256.image(old))), version = VersionPosition(1, 1),
        )
        val world = World(listOf(installed))
        world.list(remote(101, Md5B))
        world.downloader.serve(101, listOf("A/pak01_dir.vpk" to new, "B/pak01_dir.vpk" to other))
        val library = with(world) { library() }

        assertTrue(library.update(world.plan()))
        runCurrent()
        val choice = assertIs<OperationStage.ChoosingSet>(library.stage())
        assertEquals(listOf("A/pak01_dir.vpk", "B/pak01_dir.vpk"), choice.preselected, "add-ons start ticked")
        assertEquals(installed, world.record())
        assertTrue(library.answer(choice.id, ChoiceAnswer.Cancel))
        runCurrent()
        assertEquals(installed, world.record())
    }

    @Test
    fun changingFilesToTheSameFileUnderANewMd5KeepsItsComponentsWithoutAsking() = runTest {
        val world = World()
        val (a, b, c) = (10..12).map(::model)
        world.list(remote(104, Md5A))
        world.downloader.serve(104, listOf("A/pak01_dir.vpk" to a, "B/pak01_dir.vpk" to b, "C/pak01_dir.vpk" to c))
        val library = with(world) { library() }
        install(library, 104)
        val first = assertIs<OperationStage.ChoosingSet>(library.stage())
        assertTrue(library.answer(first.id, ChoiceAnswer.Sets(listOf("C/pak01_dir.vpk", "A/pak01_dir.vpk"), setOf(main))))
        runCurrent()

        world.list(remote(104, Md5B))
        world.downloader.serve(104, listOf("A/pak01_dir.vpk" to b, "B/pak01_dir.vpk" to c, "C/pak01_dir.vpk" to a))
        assertTrue(changeFilesTo(library, world.record().installId, 104))
        assertNull(library.stage(), "the components it had are all still there and B, which shares their files, would not start ticked")
        assertEquals(listOf("C/pak01_dir.vpk", "A/pak01_dir.vpk"), world.record().sets.map { it.componentKey })
        assertEquals(Md5B, (world.record().sets.first().source as InstalledSource.Remote).identity.fileMd5)
    }

    @Test
    fun anOnModWaitsForDeadlockToCloseAndAnOffModUpdatesWhileItRuns() = runTest {
        for (enabled in listOf(true, false)) {
            val world = World()
            val library = installedThen(world, remote(101, Md5B))
            world.downloader.serve(101, listOf("pak01_dir.vpk" to new))
            if (!enabled) {
                library.setEnabled(world.record().installId, false)
                runCurrent()
            }
            val plan = world.plan()
            world.processes.scan = ProcessScan.Seen(game = true)
            advanceTimeBy(3.seconds)
            assertTrue(library.update(plan))
            runCurrent()
            if (enabled) {
                assertIs<OperationStage.WaitingForGame>(library.stage(), "its files are ones the game loads")
                assertEquals(Md5A, world.record().remoteIdentity.fileMd5)
                world.processes.scan = ProcessScan.Seen()
                advanceTimeBy(3.seconds)
                runCurrent()
            }
            assertNull(library.stage(), "enabled=$enabled")
            assertEquals(Md5B, world.record().remoteIdentity.fileMd5, "enabled=$enabled")
        }
    }

    @Test
    fun cancellingAnUpdateKeepsTheInstallationAsItWas() = runTest {
        val world = World()
        val library = installedThen(world, remote(101, Md5B))
        world.downloader.serve(101, listOf("pak01_dir.vpk" to new))
        val gate = CompletableDeferred<Unit>()
        world.downloader.gate = gate
        val before = world.record()
        assertTrue(library.update(world.plan()))
        runCurrent()
        assertIs<OperationStage.Downloading>(library.stage())
        assertTrue(library.cancel(lilith.modId))
        gate.complete(Unit)
        runCurrent()
        assertNull(library.stage())
        assertEquals(before, world.record())
        assertEquals(Sha256.image(old), world.files.addonsFiles(root)[pak(1)])
    }

    @Test
    fun anUpdateInterruptedMidReplacementIsPutRightOnRestartAndCanBeAskedAgain() = runTest {
        val world = World()
        val doomed = with(world) { crashableScope() }
        val first = installedThen(world, remote(101, Md5B), scope = doomed)
        val before = world.record()
        world.downloader.serve(101, listOf("pak01_dir.vpk" to new))
        val plan = world.plan()
        world.files.around = { point -> if (point is FaultPoint.Stage) throw SimulatedCrash(point) }
        assertTrue(first.update(plan))
        runCurrent()
        doomed.cancel()
        assertIs<PendingChange.Replace>(world.manifest().pending)

        world.files.around = null
        val restarted = with(world) { library() }
        assertNull(world.manifest().pending)
        assertEquals(before, world.record(), "recovery puts the old mod back whole")
        assertEquals(Sha256.image(old), world.files.addonsFiles(root)[pak(1)])
        assertTrue(restarted.update(world.plan()))
        runCurrent()
        assertEquals(Md5B, world.record().remoteIdentity.fileMd5)
    }

    @Test
    fun anUpdateStoppedOnceItIsCommittedIsFinishedOnRestartWithNothingLeftBesideTheNewFile() = runTest {
        val world = World()
        val doomed = with(world) { crashableScope() }
        val first = installedThen(world, remote(101, Md5B), scope = doomed)
        world.downloader.serve(101, listOf("pak01_dir.vpk" to new))
        world.store.afterStore = { if ((it.pending as? PendingChange.Replace)?.step == ReplaceStep.Cleanup) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        assertTrue(first.update(world.plan()))
        runCurrent()
        doomed.cancel()
        world.store.afterStore = {}
        assertEquals(ReplaceStep.Cleanup, assertIs<PendingChange.Replace>(world.manifest().pending).step)

        with(world) { library() }
        assertNull(world.manifest().pending)
        assertEquals(Md5B, world.record().remoteIdentity.fileMd5)
        assertEquals(mapOf(pak(1) to Sha256.image(new)), world.files.addonsFiles(root), "the new file alone, with neither the old one's anchor nor a copy beside it")
    }

    private companion object {
        const val Md5A = "ab965746d197e296c253fb552dc1fd47"
        const val Md5B = "8797b6cdb331371ef015e78a9f79bad9"
        const val Md5C = "77b555b9ce0e99b7f30483be456432d4"
    }
}
