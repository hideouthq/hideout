package gg.hideouthq.core.library

import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MultiFileInstallTest {
    private val root = GameRoot("/games/Deadlock")
    private val lilith = SubmissionRef(SubmissionKind.Mod, 551_201)
    private val main = ProfileId("main")
    private val addons = InMemoryGameFiles.Addons

    private val defaultDir = FakeVpk.directory(embeddedBytes = 4, chunkReads = mapOf(0 to 30L, 1 to 20L), seed = 1)
    private val chunk0 = ByteArray(30) { 7 }
    private val chunk1 = ByteArray(20) { 8 }
    private val werewolf = FakeVpk.directory(seed = 2)
    private val cabezas = FakeVpk.directory(seed = 3)

    private fun remote(id: Long, name: String, downloads: Long) =
        RemoteFile(id, name, 1_000, null, null, downloads, addedAt = 1_777_000_000, downloadUrl = "https://gamebanana.com/dl/$id")

    private val default = remote(101, "lilith_hell_warden.zip", 2_600)
    private val transform = remote(102, "lilith_werewolf_transform.zip", 1_100)
    private val cabezasFile = remote(103, "lilith_werewolf_cabezas.zip", 412)

    private inner class World(seed: List<ManifestRecord> = emptyList()) : CrashWorld {
        override val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo) }
        override val store = InMemoryLibraryStore(seed.takeIf { it.isNotEmpty() }?.let { LibraryManifest(records = it) })
        val banana = FakeGameBanana(mapOf(lilith to FileListing.Available("Lilith, the Hell Warden", listOf(cabezasFile, default, transform))))
        val downloader = FakeDownloader(files)
        val processes = FakeProcesses()
        val preferences = Preferences(MemoryStorage())
        val logged = mutableListOf<String>()
        private var nextId = 0

        init {
            seed.forEach { record -> record.files.forEach { putFile(record, it) } }
            downloader.serve(101, listOf("Default/lilith_dir.vpk" to defaultDir, "Default/lilith_000.vpk" to chunk0, "Default/lilith_001.vpk" to chunk1, "readme.txt" to ByteArray(2)))
            downloader.serve(102, listOf("werewolf.vpk" to werewolf))
            downloader.serve(103, listOf("Cabezas/cabezas_dir.vpk" to cabezas))
        }

        private fun putFile(record: ManifestRecord, file: OwnedFile) {
            files.putFile(root, file.relativePath(record), file.sizeBytes, file.sha256)
        }

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(
                FakeGameLocator(listOf(root.path), mapOf(root.path to null)),
                preferences,
                store,
                files,
                InstallPorts(banana, downloader, files, files, processes, log = { logged += it }),
                scope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
            ),
        )

        override fun TestScope.open(scope: CoroutineScope) = library(scope)

        fun manifest(): LibraryManifest = assertNotNull(store.manifest)

        fun record(): ManifestRecord = manifest().records.single { it.modId == lilith.modId }

        fun addons(): Map<String, FileImage> = files.addonsFiles(root)

        fun identities(names: Collection<String>, folder: String = addons): Map<String, Int?> = names.associateWith { files.identity(root, "$folder/$it") }

        fun list(vararg listed: RemoteFile) {
            banana.listings = banana.listings + (lilith to FileListing.Available("Lilith, the Hell Warden", listed.toList()))
        }
    }

    private fun neighbour(id: String, number: Int, mod: Long = 9_000L + number) = oneSetRecord(
        id, root, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, mod, mod, null)), "Neighbour $id", null,
        ArchiveIdentity("/downloads/$id.zip", 1, "a".repeat(64)), number,
        listOf(SetFile(VpkPart.Directory, FileImage(64, "b".repeat(64)))),
    )

    private fun TestScope.install(library: ModLibrary, vararg fileIds: Long, profiles: Set<ProfileId> = setOf(main)) {
        library.install(InstallRequest(InstallInput.Remote(lilith, fileIds.toSet()), profiles))
        runCurrent()
    }

    private fun TestScope.installed(vararg fileIds: Long, seed: List<ManifestRecord> = emptyList()): Pair<World, ModLibrary> {
        val world = World(seed)
        val library = with(world) { library() }
        install(library, *fileIds)
        return world to library
    }

    private fun failure(library: ModLibrary): OperationStage.Failed = assertIs<OperationStage.Failed>(library.state.value.operations[lilith.modId]?.stage)

    private fun pak(number: Int, suffix: String) = "pak${number.toString().padStart(2, '0')}_$suffix.vpk"

    private fun off(fileId: Long, componentKey: String, part: String = "dir") =
        VpkPart.parse(part)!!.fileName(SetKey.of(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, lilith.id, fileId, null)), componentKey).offStem("id1"))

    private val cabezasOff = off(103, "Cabezas/cabezas_dir.vpk")

    @Test
    fun severalFilesInstallAsOneModUnderConsecutiveNumbersWithTheTopFileWinning() = runTest {
        val world = World()
        world.files.addAddonsEntry(root, "pak01_dir.vpk")
        world.files.addAddonsEntry(root, "pak03_dir.vpk")
        val library = with(world) { library() }
        install(library, 103, 101)

        val record = world.record()
        assertEquals(listOf(101L, 103L), record.sets.map { it.fileId }, "no author numbers set them apart, so the order the dialog lists them, most downloaded first, whatever order they were picked in")
        assertEquals(listOf(4, 5), record.numbers.map { it.value }, "the lowest two free numbers in a row: 2 is free but 3 is not")
        assertEquals(listOf("lilith_hell_warden.zip", "lilith_werewolf_cabezas.zip"), record.sets.map { it.originalName })
        assertEquals(listOf(VersionPosition(1, 3), VersionPosition(3, 3)), record.sets.map { it.version })
        assertEquals(listOf(listOf(pak(4, "dir"), pak(4, "000"), pak(4, "001")), listOf(pak(5, "dir"))), record.sets.map { set -> record.filesOf(set).map { it.name } })
        val default = "Default/lilith_dir.vpk"
        assertEquals(listOf(off(101, default, "000"), off(101, default, "001"), off(101, default), cabezasOff), world.files.publishedNames, "into its off folder, every chunk before any directory")
        assertEquals(
            mapOf(pak(4, "dir") to Sha256.image(defaultDir), pak(4, "000") to Sha256.image(chunk0), pak(4, "001") to Sha256.image(chunk1), pak(5, "dir") to Sha256.image(cabezas)),
            world.addons() - "pak01_dir.vpk" - "pak03_dir.vpk",
        )
        assertEquals(record.sets.map { it.download }, world.manifest().downloads.map { it.archive }, "each file's download is registered")
        assertEquals(listOf(101L, 103L), world.manifest().downloads.map { it.source.fileId })
        assertEquals(setOf(record.installId), world.manifest().profiles.activeProfile.enabled, "one membership for the whole mod")
        assertEquals(listOf(record.installId), onLoadOrder(world.manifest().records), "one place in the load order")
        assertEquals(InstallStatus.Installed(enabled = true), library.status(lilith.modId).first())
        assertNull(world.manifest().pending)
        assertEquals(emptySet(), world.files.stagingFolders)
    }

    @Test
    fun aDownloadOrUnpackThatFailsNamesItsFileAndInstallsNothing() = runTest {
        val world = World()
        world.downloader.serve(103, emptyList())
        world.downloader.results[103] = DownloadResult.Failed(DownloadProblem.Http(404))
        val library = with(world) { library() }
        install(library, 101, 103)
        val failed = failure(library)
        assertEquals(LibraryProblem.Download(DownloadProblem.Http(404)), failed.problem)
        assertEquals("lilith_werewolf_cabezas.zip", failed.file)
        assertEquals(listOf(101L, 103L), world.downloader.requested)
        assertEquals(emptyList(), world.manifest().records)
        assertEquals(emptyMap(), world.addons())
        assertEquals(emptySet(), world.files.stagingFolders, "the first file's unpacked set is put away")
        assertEquals(listOf(101L), world.manifest().downloads.map { it.source.fileId }, "what was downloaded stays registered for a retry")

        world.downloader.results.remove(103)
        world.downloader.serve(103, listOf("readme.txt" to ByteArray(3)))
        assertTrue(library.retry(lilith.modId))
        runCurrent()
        val refused = failure(library)
        assertEquals(LibraryProblem.ArchiveRefused(ArchiveRejection(RejectionReason.NoVpk, null)), refused.problem)
        assertEquals("lilith_werewolf_cabezas.zip", refused.file, "an unpacking failure names its file too")
        assertEquals(emptyList(), world.manifest().records)

        world.downloader.serve(103, listOf("Cabezas/cabezas_dir.vpk" to cabezas))
        assertTrue(library.retry(lilith.modId))
        runCurrent()
        assertEquals(listOf(101L, 103L), world.record().sets.map { it.fileId }, "a retry asks nothing it was told")
    }

    @Test
    fun aDiscardedDownloadOfAFailedInstallGoesAndASuccessfulOnesGoesToo() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        world.downloader.results[103] = DownloadResult.Failed(DownloadProblem.Http(500))
        val library = with(world) { library() }
        install(library, 101, 103)
        assertEquals("lilith_werewolf_cabezas.zip", failure(library).file)
        assertEquals(emptyList(), world.manifest().downloads, "nothing installed it, so the kept-by-nobody download goes")
        assertEquals(emptySet(), world.files.archivePaths())

        world.downloader.results.remove(103)
        assertTrue(library.retry(lilith.modId))
        runCurrent()
        assertEquals(listOf(101L, 103L), world.record().sets.map { it.fileId })
        assertEquals(emptyList(), world.manifest().downloads, "every set's download is discarded once installed")
        assertEquals(emptySet(), world.files.archivePaths())
    }

    @Test
    fun aPlacementThatFailsUndoesEverySetAndNamesTheFile() = runTest {
        val world = World()
        world.files.failing += FaultPoint.Publish(cabezasOff)
        val library = with(world) { library() }
        install(library, 101, 103)
        val failed = failure(library)
        assertIs<LibraryProblem.PlacementFailed>(failed.problem)
        assertEquals("lilith_werewolf_cabezas.zip", failed.file)
        assertEquals(emptyMap(), world.files.filesIn(root, SetFolder.Off(lilith)), "the first set's published files went with the second's")
        assertEquals(emptyMap(), world.addons())
        assertEquals(emptyList(), world.manifest().records)
        assertNull(world.manifest().pending)
    }

    @Test
    fun aCrashAtAnyInstallStepLeavesAllOfTheModOrNoneOfItAfterTheNextStart() = runTest {
        val all = setOf(pak(1, "dir"), pak(1, "000"), pak(1, "001"), pak(2, "dir"))
        val crashes = mapOf<String, (FaultPoint) -> Boolean>(
            "copying the second set" to { it is FaultPoint.Stage && it.temporary.startsWith("$cabezasOff$TemporaryInfix") },
            "before the last directory" to { it == FaultPoint.Publish(cabezasOff) },
            "after the last directory" to { it == FaultPoint.Linked(cabezasOff) },
            "releasing the copies" to { it is FaultPoint.Remove && TemporaryInfix in it.path },
        )
        for ((name, at) in crashes) {
            val world = World()
            var crashed = false
            world.files.around = { point -> if (!crashed && at(point)) { crashed = true; throw SimulatedCrash(point) } }
            val doomed = with(world) { crashableScope() }
            install(with(world) { library(doomed) }, 101, 103)
            doomed.cancel()
            assertTrue(crashed, name)
            assertNotNull(world.manifest().pending, name)

            world.files.around = null
            with(world) { library() }
            assertNull(world.manifest().pending, name)
            val installed = world.manifest().records.singleOrNull()
            if (installed == null) {
                assertEquals(emptyMap(), world.addons(), "$name: undone whole")
                assertEquals(emptyMap(), world.files.filesIn(root, SetFolder.Off(lilith)), "$name: undone whole")
            } else {
                assertEquals(listOf(101L, 103L), installed.sets.map { it.fileId }, name)
                assertEquals(all, world.addons().keys, "$name: committed whole")
            }
            assertEquals(name in setOf("after the last directory", "releasing the copies"), installed != null, name)
        }
    }

    @Test
    fun addingFilesDownloadsOnlyNewOnesAndKeepsExistingComponentsInTheirOrder() = runTest {
        val (world, library) = installed(101, 103)
        val before = world.record()
        val untouched = world.identities(listOf(pak(1, "dir"), pak(1, "000"), pak(1, "001")))
        val moved = world.files.identity(root, "$addons/${pak(2, "dir")}")
        before.sets.forEach { world.files.removeArchive(it.download!!.path) }
        world.downloader.requested.clear()
        world.files.linkedPaths.clear()
        world.files.removedPaths.clear()

        assertTrue(changeFilesTo(library, before.installId, 101, 102, 103))

        val after = world.record()
        assertEquals(listOf(102L), world.downloader.requested, "the files it keeps are not downloaded again, though their downloads are gone")
        assertEquals(before.installId, after.installId)
        assertEquals(listOf(101L, 103L, 102L), after.sets.map { it.fileId }, "new files follow the retained components, regardless of download popularity")
        assertEquals(listOf(1, 2, 3), after.numbers.map { it.value })
        assertEquals(before.sets, after.sets.take(2), "both retained components keep their numbers and receipts")
        assertEquals(untouched, world.identities(untouched.keys), "unmoved files are the very same files")
        assertTrue(world.files.linkedPaths.none { "pak01_" in it || "pak02_" in it } && world.files.removedPaths.none { "pak01_" in it || "pak02_" in it }, "nothing touched either retained component")
        assertEquals(moved, world.files.identity(root, "$addons/${pak(2, "dir")}"), "the second component keeps its file identity")
        assertEquals(Sha256.image(werewolf), world.addons()[pak(3, "dir")])
        assertEquals(setOf(pak(1, "dir"), pak(1, "000"), pak(1, "001"), pak(2, "dir"), pak(3, "dir")), world.addons().keys, "no anchor or copy is left")
        assertEquals(setOf(before.installId), world.manifest().profiles.activeProfile.enabled)
        assertNull(world.manifest().pending)
        assertNull(library.state.value.operations[lilith.modId])
    }

    @Test
    fun changingFilesKeepsEveryComponentOfAnUnchangedMultiComponentFileInItsExistingOrder() = runTest {
        val world = World()
        val a = FakeVpk.directory(seed = 10)
        val b = FakeVpk.directory(seed = 11)
        val multi = remote(104, "lilith_multi.zip", downloads = 2_000)
        world.list(multi, default, transform, cabezasFile)
        world.downloader.serve(104, listOf("A/pak01_dir.vpk" to a, "B/pak01_dir.vpk" to b))
        val library = with(world) { library() }
        install(library, 104)
        val choice = assertIs<OperationStage.ChoosingSet>(library.state.value.operations[lilith.modId]?.stage)
        assertEquals(listOf("A/pak01_dir.vpk", "B/pak01_dir.vpk"), choice.preselected, "every component of the one archive starts ticked")
        assertTrue(library.answer(choice.id, ChoiceAnswer.Sets(listOf("A/pak01_dir.vpk", "B/pak01_dir.vpk"), setOf(main))))
        runCurrent()

        val before = world.record()
        assertEquals(listOf("A/pak01_dir.vpk", "B/pak01_dir.vpk"), before.sets.map { it.componentKey })
        assertEquals(listOf(104L, 104L), before.sets.map { it.fileId }, "both components share the one GameBanana file")
        assertEquals(listOf(1, 2), before.numbers.map { it.value })
        assertEquals(1, world.manifest().downloads.size, "the shared archive is registered once for both components")
        world.downloader.requested.clear()

        assertTrue(changeFilesTo(library, before.installId, 104))
        assertEquals(before, world.record(), "reselecting the one unchanged file retains every component it has, not only the first")
        assertEquals(emptyList(), world.downloader.requested, "nothing is downloaded again")
        assertNull(world.manifest().pending)
        assertNull(library.state.value.operations[lilith.modId])
    }

    @Test
    fun changingFilesRemovesOnlyTheFilesUnticked() = runTest {
        val (world, library) = installed(101, 102, 103)
        val before = world.record()
        val winner = world.identities(listOf(pak(1, "dir"), pak(1, "000"), pak(1, "001")))
        val last = world.files.identity(root, "$addons/${pak(3, "dir")}")
        world.downloader.requested.clear()

        assertTrue(changeFilesTo(library, before.installId, 101, 103))
        val after = world.record()
        assertEquals(emptyList(), world.downloader.requested)
        assertEquals(listOf(101L, 103L), after.sets.map { it.fileId })
        assertEquals(listOf(1, 2), after.numbers.map { it.value }, "the numbers stay consecutive")
        assertEquals(winner, world.identities(winner.keys))
        assertEquals(last, world.files.identity(root, "$addons/${pak(2, "dir")}"), "Cabezas moved up into the number Werewolf had")
        assertEquals(setOf(pak(1, "dir"), pak(1, "000"), pak(1, "001"), pak(2, "dir")), world.addons().keys)
        assertEquals(listOf(101L, 102L, 103L), world.manifest().downloads.map { it.source.fileId }, "the unticked file's download stays, as Settings keeps it")

        assertTrue(changeFilesTo(library, before.installId, 103))
        assertEquals(listOf(103L), world.record().sets.map { it.fileId })
        assertEquals(listOf(1), world.record().numbers.map { it.value }, "the mod keeps its first number")
        assertEquals(mapOf(pak(1, "dir") to Sha256.image(cabezas)), world.addons())
    }

    @Test
    fun allDelistedFilesCanStillBeUntickedWithoutDownloading() = runTest {
        val (world, library) = installed(101, 103)
        val installed = world.record()
        world.list()
        world.downloader.requested.clear()

        library.changeFiles(installed.installId)
        runCurrent()
        val question = assertIs<OperationStage.ChoosingFile>(library.state.value.operations.getValue(lilith.modId).stage)
        assertEquals(emptyList(), question.files)
        assertEquals(installed.sets, question.delisted)
        assertEquals(setOf(101L, 103L), question.preselected)
        assertTrue(library.answer(question.id, ChoiceAnswer.Files(setOf(101), question.profiles)))
        runCurrent()

        assertEquals(emptyList(), world.downloader.requested)
        assertEquals(listOf(installed.sets.first()), world.record().sets)
        assertEquals(setOf(pak(1, "dir"), pak(1, "000"), pak(1, "001")), world.addons().keys)
    }

    @Test
    fun aModInstalledAgainIsOfferedTheFilesItHadFromTheDownloadsHideoutKept() = runTest {
        val world = World()
        val library = with(world) { library() }
        install(library)
        val first = assertIs<OperationStage.ChoosingFile>(library.state.value.operations.getValue(lilith.modId).stage)
        assertEquals(setOf(101L), first.preselected, "a mod never installed starts on the most downloaded file")
        assertEquals(emptySet(), first.kept)
        assertTrue(library.answer(first.id, ChoiceAnswer.Files(setOf(102, 103), first.profiles)))
        runCurrent()
        library.uninstall(assertNotNull(library.uninstallConfirmation(world.record().installId)), deleteDownload = false)
        runCurrent()
        world.downloader.requested.clear()

        install(library)
        val again = assertIs<OperationStage.ChoosingFile>(library.state.value.operations.getValue(lilith.modId).stage)
        assertEquals(setOf(102L, 103L), again.preselected, "the files it had when it was uninstalled")
        assertEquals(setOf(102L, 103L), again.kept, "and nothing of them is downloaded again")
        assertTrue(library.answer(again.id, ChoiceAnswer.Files(again.preselected, again.profiles)))
        runCurrent()
        assertEquals(emptyList(), world.downloader.requested)
        assertEquals(listOf(102L, 103L), world.record().sets.map { it.fileId })
    }

    @Test
    fun aChangeOfFilesRefusedBecauseTheModWasUninstalledMeanwhileInstallsTheChosenFilesOnTryAgain() = runTest {
        val (world, library) = installed(101)
        library.changeFiles(world.record().installId)
        runCurrent()
        val question = assertIs<OperationStage.ChoosingFile>(library.state.value.operations.getValue(lilith.modId).stage)
        library.uninstall(assertNotNull(library.uninstallConfirmation(world.record().installId)), deleteDownload = false)
        runCurrent()
        assertTrue(library.answer(question.id, ChoiceAnswer.Files(setOf(102), question.profiles)))
        runCurrent()
        assertEquals(LibraryProblem.VersionChanged, failure(library).problem)

        assertTrue(library.retry(lilith.modId))
        runCurrent()
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(listOf(102L), world.record().sets.map { it.fileId })
    }

    @Test
    fun aFileGameBananaNoLongerListsIsKeptAfterTheListedOnesWithoutDownloading() = runTest {
        val (world, library) = installed(101, 103)
        val installed = world.record()
        val cabezasIdentity = world.files.identity(root, "$addons/${pak(2, "dir")}")
        world.list(default, transform)
        world.downloader.requested.clear()

        library.changeFiles(installed.installId)
        runCurrent()
        val question = assertIs<OperationStage.ChoosingFile>(library.state.value.operations.getValue(lilith.modId).stage)
        assertEquals(listOf(101L, 102L), question.files.map { it.id })
        assertEquals(listOf(installed.sets[1]), question.delisted)
        assertEquals(setOf(101L, 103L), question.preselected)
        assertFalse(library.answer(question.id, ChoiceAnswer.Files(emptySet(), question.profiles)), "an empty answer is no answer")
        assertTrue(library.answer(question.id, ChoiceAnswer.Files(setOf(103, 102, 101), question.profiles)))
        runCurrent()

        assertEquals(listOf(102L), world.downloader.requested)
        assertEquals(listOf(101L, 103L, 102L), world.record().sets.map { it.fileId })
        assertEquals(cabezasIdentity, world.files.identity(root, "$addons/${pak(2, "dir")}"))
    }

    @Test
    fun changingTheFilesOfAModThatIsOffNeverTouchesTheSetsItKeeps() = runTest {
        val world = World()
        val library = with(world) { library() }
        install(library, 101, 103, profiles = emptySet())
        val before = world.record()
        val off = SetFolder.Off(lilith)
        val kept = before.files.map { it.name }
        val identities = world.identities(kept, off.path)
        world.files.linkedPaths.clear()
        world.files.removedPaths.clear()

        assertTrue(changeFilesTo(library, before.installId, 101, 102, 103))
        val after = world.record()
        assertEquals(listOf(101L, 103L, 102L), after.sets.map { it.fileId })
        assertEquals(Placement.Off, after.placement)
        assertEquals(before.sets, after.sets.take(2))
        assertEquals(identities, world.identities(kept, off.path), "the kept sets are the very same files")
        assertTrue((world.files.linkedPaths + world.files.removedPaths).none { path -> kept.any { path.endsWith("/$it") } }, "nothing touched them")
        assertEquals(after.files.map { it.name }.toSet(), world.files.filesIn(root, off).keys)
        assertEquals(emptyMap(), world.addons())
    }

    @Test
    fun growingAModSpacesItsNeighboursWithoutChangingPrecedence() = runTest {
        val (world, library) = installed(101, seed = listOf(neighbour("x", 2)))
        val original = world.record()
        val neighbourIdentity = world.files.identity(root, "$addons/${pak(2, "dir")}")
        world.downloader.requested.clear()
        val saves = world.store.saved.size

        assertTrue(changeFilesTo(library, original.installId, 101, 102))
        val journals = world.store.saved.drop(saves).mapNotNull { it.pending }.map { it::class }.distinct()
        assertEquals(listOf(PendingChange.Arrange::class, PendingChange.Replace::class), journals, "one arrangement makes room, then the replacement")
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(listOf(102L), world.downloader.requested)
        assertEquals(listOf(1, 2), world.record().numbers.map { it.value })
        assertEquals(original.sets.first(), world.record().sets.first())
        assertEquals(listOf(original.installId, "x"), onLoadOrder(world.manifest().records))
        assertEquals(listOf(3), world.manifest().records.single { it.installId == "x" }.numbers.map { it.value })
        assertEquals(neighbourIdentity, world.files.identity(root, "$addons/${pak(3, "dir")}"))
        assertNull(world.manifest().pending)
    }

    @Test
    fun growingAModNeverReusesANeighboursNumberAlsoOccupiedByAnUnmanagedVpk() = runTest {
        val world = World(seed = listOf(neighbour("x", 2)))
        val foreignPath = "$addons/pak02.vpk"
        val foreignBytes = FakeVpk.directory(seed = 25)
        world.files.putBytes(root, foreignPath, foreignBytes)
        val foreignIdentity = world.files.identity(root, foreignPath)
        val library = with(world) { library() }
        install(library, 101)
        val original = world.record()

        assertTrue(changeFilesTo(library, original.installId, 101, 102))
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(listOf(3, 4), world.record().numbers.map { it.value }, "number 2 remains unavailable after the neighbour moves away")
        assertEquals(listOf(original.installId, "x"), onLoadOrder(world.manifest().records))
        assertEquals(foreignIdentity, world.files.identity(root, foreignPath))
        assertEquals(Sha256.image(foreignBytes), world.addons()["pak02.vpk"])
        assertNull(world.manifest().pending)
    }

    @Test
    fun aCrashAtAnyStepOfGrowingAModAndDuringItsRecoveryLeavesItWholeOldOrGrownAndAheadOfItsNeighbour() = runTest {
        fun TestScope.prepared(): World = World(seed = listOf(neighbour("x", 2))).also { install(with(it) { library() }, 101) }
        val dry = prepared()
        val old = dry.record()
        val act: CoroutineScope.(ModLibrary) -> Unit = { changeFilesTo(it, old.installId, 101, 102) }
        val trace = traced(dry, act)
        val grown = dry.record()
        assertEquals(listOf(101L, 102L), grown.sets.map { it.fileId })
        assertTrue(trace.points.size > 10, "the trace has ${trace.points}")

        crashAtEveryStep(trace, { prepared() }, act) { world, _ ->
            val now = world.record()
            assertTrue(now.sets == old.sets || now.sets == grown.sets, "the old sets or the grown ones: ${now.sets.map { it.fileId }}")
            assertEquals(listOf(now.installId, "x"), onLoadOrder(world.manifest().records), "it still loads before x")
            val x = world.manifest().records.single { it.installId == "x" }
            assertEquals((now.files + x.files).associate { it.name to it.image }, world.addons(), "every set whole at its own names, with nothing beside them")
        }
    }

    @Test
    fun aCrashAtAnyStepOfChangingAnOffModsFilesWhileDeadlockRunsIsSettledBeforeItCloses() = runTest {
        fun TestScope.prepared(): World = World().also { world ->
            install(with(world) { library() }, 101, profiles = emptySet())
            world.processes.scan = ProcessScan.Seen(game = true)
        }
        val dry = prepared()
        val old = dry.record()
        val act: CoroutineScope.(ModLibrary) -> Unit = { changeFilesTo(it, old.installId, 102) }
        val trace = traced(dry, act)
        val changed = dry.record()
        assertEquals(listOf(102L), changed.sets.map { it.fileId })
        assertTrue(trace.points.size > 10, "the trace has ${trace.points}")

        crashAtEveryStep(trace, { prepared() }, act) { world, _ ->
            val now = world.record()
            assertTrue(now.sets == old.sets || now.sets == changed.sets, "the old sets or the new ones: ${now.sets.map { it.fileId }}")
            assertEquals(now.files.associate { it.name to it.image }, world.files.filesIn(root, SetFolder.Off(lilith)), "every set whole in its off folder, with nothing beside them")
            assertEquals(emptyMap(), world.addons())
        }
    }

    @Test
    fun aReplacementFailureAfterSpacingLeavesTheOldModAndLogicalOrderIntact() = runTest {
        val (world, library) = installed(101, seed = listOf(neighbour("x", 2)))
        val original = world.record()
        world.files.failing += FaultPoint.Link("$addons/${pak(2, "dir")}")
        assertTrue(changeFilesTo(library, original.installId, 101, 102))
        assertIs<OperationStage.Failed>(library.state.value.operations[lilith.modId]?.stage)
        assertEquals(original, world.record())
        assertEquals(listOf(original.installId, "x"), onLoadOrder(world.manifest().records))
        assertNull(world.manifest().pending)
        assertTrue(world.addons().keys.none { TemporaryInfix in it }, "the undone change's copy goes with it: ${world.addons().keys}")
        world.files.failing.clear()
        library.retry(lilith.modId)
        runCurrent()
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(listOf(original.installId, "x"), onLoadOrder(world.manifest().records), "retry keeps precedence")
        assertEquals(listOf(101L, 102L), world.record().sets.map { it.fileId })
        assertEquals(listOf(1, 2), world.record().numbers.map { it.value }, "nothing is left at 02, so the mod takes the gap it made")
        assertEquals(Sha256.image(defaultDir), world.addons()[pak(1, "dir")])
        assertEquals(Sha256.image(werewolf), world.addons()[pak(2, "dir")])
    }

    @Test
    fun restartingBetweenSpacingAndReplacementKeepsTheOldModWhole() = runTest {
        val world = World(seed = listOf(neighbour("x", 2)))
        val doomed = with(world) { crashableScope() }
        val first = with(world) { library(doomed) }
        install(first, 101)
        val original = world.record()
        world.files.around = { point -> if (point is FaultPoint.Stage) throw SimulatedCrash(point) }
        changeFilesTo(first, original.installId, 101, 102)
        doomed.cancel()
        assertIs<PendingChange.Replace>(world.manifest().pending)
        assertEquals(listOf(3), world.manifest().records.single { it.installId == "x" }.numbers.map { it.value })
        world.files.around = null
        val restarted = with(world) { library() }
        assertEquals(original, world.record())
        assertNull(world.manifest().pending)
        assertEquals(listOf(original.installId, "x"), onLoadOrder(world.manifest().records))
        assertTrue(changeFilesTo(restarted, original.installId, 101, 102))
        assertNull(restarted.state.value.operations[lilith.modId])
        assertEquals(listOf(1, 2), world.record().numbers.map { it.value })
    }

    @Test
    fun changingFilesTakesTheNearestRoomBetweenItsNeighboursAndNeverAForeignFilesNumber() = runTest {
        val world = World(seed = listOf(neighbour("x", 5)))
        world.files.putBytes(root, "$addons/${pak(2, "dir")}", ByteArray(9) { 2 })
        val foreign = world.files.identity(root, "$addons/${pak(2, "dir")}")
        val library = with(world) { library() }
        install(library, 101)
        val before = world.record()
        assertEquals(listOf(1), before.numbers.map { it.value })
        val winner = world.identities(listOf(pak(1, "dir"), pak(1, "000"), pak(1, "001")))

        assertTrue(changeFilesTo(library, before.installId, 101, 102))
        val after = world.record()
        assertEquals(listOf(3, 4), after.numbers.map { it.value }, "1 and 2 would load beside the foreign pak02; 3 and 4 are free before the next mod")
        assertEquals(foreign, world.files.identity(root, "$addons/${pak(2, "dir")}"), "the foreign file is never touched")
        assertEquals(winner.values.toList(), world.identities(listOf(pak(3, "dir"), pak(3, "000"), pak(3, "001"))).values.toList(), "the kept set was renamed, not copied")
        assertEquals(listOf(after.installId, "x"), onLoadOrder(world.manifest().records), "it keeps its place before x")
        assertFalse(world.addons().keys.any { it.startsWith("pak01_") })
    }

    @Test
    fun turningTheModOffAndOnMovesEverySetAndUninstallRemovesEveryFileAndOffersEveryDownload() = runTest {
        val (world, library) = installed(101, 103)
        val record = world.record()
        val names = record.files.map { it.name }.toSet()

        library.setEnabled(record.installId, false)
        runCurrent()
        assertFalse(world.record().enabled)
        assertEquals(emptyMap(), world.addons())
        assertEquals(record.placed(Placement.Off).files.map { it.name }.toSet(), world.files.filesIn(root, SetFolder.Off(lilith)).keys, "every set of the mod moved, under names that hold no number")
        library.setEnabled(record.installId, true)
        runCurrent()
        assertEquals(names, world.addons().keys)

        val confirmation = assertNotNull(library.uninstallConfirmation(record.installId))
        assertEquals(record.sets.map { it.download }, confirmation.downloads, "every file's download may go with it")
        library.uninstall(confirmation, deleteDownload = true)
        runCurrent()
        assertEquals(emptyList(), world.manifest().records)
        assertEquals(emptyMap(), world.addons())
        assertEquals(emptyList(), world.manifest().downloads)
        assertEquals(emptySet(), world.files.archivePaths())
        assertEquals(emptySet(), world.manifest().profiles.activeProfile.enabled)
    }

    @Test
    fun reorderingAModOfSeveralSetsMovesItsWholeBlock() = runTest {
        val (world, library) = installed(101, 103, seed = listOf(neighbour("x", 3)))
        val record = world.record()
        assertEquals(listOf(1, 2), record.numbers.map { it.value })
        val before = world.identities(listOf(pak(1, "dir"), pak(1, "000"), pak(1, "001"), pak(2, "dir"), pak(3, "dir")))

        val view = assertNotNull(library.state.value.orderView)
        assertEquals(listOf(record.installId, "x"), view.sets)
        library.reorder(view, listOf("x", record.installId))
        runCurrent()

        assertEquals(listOf(4, 5), world.record().numbers.map { it.value }, "the whole mod moved past x, still consecutive, rather than x and both its sets")
        assertEquals(listOf(3), world.manifest().records.single { it.installId == "x" }.numbers.map { it.value })
        val after = world.identities(listOf(pak(3, "dir"), pak(4, "dir"), pak(4, "000"), pak(4, "001"), pak(5, "dir")))
        assertEquals(
            listOf(before[pak(3, "dir")], before[pak(1, "dir")], before[pak(1, "000")], before[pak(1, "001")], before[pak(2, "dir")]),
            after.values.toList(),
            "every file is the same file under its new number, chunks with their directory",
        )
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertNull(world.manifest().pending)
    }

    @Test
    fun aCrashAtAnyStepOfChangingFilesLeavesTheOldFilesOrTheNewOnesWhole() = runTest {
        val crashes = mapOf<String, (FaultPoint) -> Boolean>(
            "copying the new file" to { it is FaultPoint.Stage },
            "keeping the moving file beside itself" to { it is FaultPoint.LinkMade && it.path.startsWith("$addons/${pak(2, "dir")}.hideout-") },
            "releasing the moving file's old name" to { it == FaultPoint.Remove("$addons/${pak(2, "dir")}") },
            "giving the moving file its new name" to { it == FaultPoint.Link("$addons/${pak(1, "dir")}") },
            "putting the new file's copy away" to { it is FaultPoint.Remove && it.path.endsWith("-new") },
        )
        for ((name, at) in crashes) {
            val world = World()
            val doomed = with(world) { crashableScope() }
            val first = with(world) { library(doomed) }
            install(first, 101, 103)
            val old = world.record()
            var crashed = false
            world.files.around = { point -> if (!crashed && at(point)) { crashed = true; throw SimulatedCrash(point) } }
            changeFilesTo(first, old.installId, 103, 102)
            doomed.cancel()
            assertTrue(crashed, name)
            assertIs<PendingChange.Replace>(world.manifest().pending, name)

            world.files.around = null
            val restarted = with(world) { library() }
            assertNull(world.manifest().pending, name)
            val now = world.record()
            if (now == old) {
                assertEquals(setOf(pak(1, "dir"), pak(1, "000"), pak(1, "001"), pak(2, "dir")), world.addons().keys, "$name: the old files whole")
                assertEquals(Sha256.image(cabezas), world.addons()[pak(2, "dir")], name)
            } else {
                assertEquals(listOf(103L, 102L), now.sets.map { it.fileId }, name)
                assertEquals(setOf(pak(1, "dir"), pak(2, "dir")), world.addons().keys, "$name: the new files whole, with no anchor left")
                assertEquals(Sha256.image(werewolf), world.addons()[pak(2, "dir")], name)
                assertEquals(Sha256.image(cabezas), world.addons()[pak(1, "dir")], name)
            }
            assertEquals(name == "copying the new file", now == old, "$name: only a crash before anything is renamed is undone")
        }
    }

    private fun TestScope.changeStoppedOnceCommitted(world: World): PendingChange.Replace {
        val doomed = crashableScope()
        val first = with(world) { library(doomed) }
        install(first, 101, 103)
        world.store.afterStore = { if ((it.pending as? PendingChange.Replace)?.step == ReplaceStep.Cleanup) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        changeFilesTo(first, world.record().installId, 103, 102)
        doomed.cancel()
        world.store.afterStore = {}
        return assertIs<PendingChange.Replace>(world.manifest().pending)
    }

    @Test
    fun aMovedFileChangedOnceAChangeOfFilesIsCommittedKeepsItsBytesAndItsAnchorGoes() = runTest {
        val world = World()
        changeStoppedOnceCommitted(world)
        val changed = FakeVpk.directory(seed = 9)
        world.files.rewrite(root, "$addons/${pak(1, "dir")}", changed)

        with(world) { library() }
        assertNull(world.manifest().pending)
        assertEquals(listOf(103L, 102L), world.record().sets.map { it.fileId })
        assertEquals(mapOf(pak(1, "dir") to Sha256.image(changed), pak(2, "dir") to Sha256.image(werewolf)), world.addons(), "the moved file keeps what it holds now, with no anchor or copy beside it")
    }

    @Test
    fun aMovedFileReplacedOrDeletedOnceAChangeOfFilesIsCommittedKeepsItsAnchorTheOnlyCopyOfItsBytes() = runTest {
        onlyCopyStaysOnceCommitted(moved = true)
    }

    @Test
    fun aNewFileReplacedOrDeletedOnceAChangeOfFilesIsCommittedKeepsItsCopyTheOnlyCopyOfItsBytes() = runTest {
        onlyCopyStaysOnceCommitted(moved = false)
    }

    private fun TestScope.onlyCopyStaysOnceCommitted(moved: Boolean) {
        for (deleted in listOf(false, true)) {
            val case = if (deleted) "deleted" else "replaced"
            val world = World()
            val change = changeStoppedOnceCommitted(world)
            val name = "$addons/${pak(if (moved) 1 else 2, "dir")}"
            val fed = "${pak(2, "dir")}$TemporaryInfix${change.token.value}${if (moved) "" else "-new"}"
            if (deleted) world.files.remove(root, name) else world.files.putBytes(root, name, FakeVpk.directory(seed = 9))

            with(world) { library() }
            assertNull(world.manifest().pending, case)
            assertEquals(listOf(103L, 102L), world.record().sets.map { it.fileId }, case)
            assertEquals(mapOf(fed to Sha256.image(if (moved) cabezas else werewolf)), world.addons().filterKeys { TemporaryInfix in it }, "$case: the name that fed it, the only copy of its bytes, stays, and nothing else does")
            assertTrue(world.logged.any { "$addons/$fed" in it }, "$case: and is reported left in place")
        }
    }

    @Test
    fun anAnchorOfAFileThatWentWhoseBytesChangedOnceAChangeOfFilesIsCommittedStaysAndIsReported() = runTest {
        val world = World()
        val change = changeStoppedOnceCommitted(world)
        val anchor = "${pak(1, "dir")}$TemporaryInfix${change.token.value}"
        val changed = FakeVpk.directory(seed = 9)
        world.files.rewrite(root, "$addons/$anchor", changed)

        with(world) { library() }
        assertNull(world.manifest().pending)
        assertEquals(mapOf(pak(1, "dir") to Sha256.image(cabezas), pak(2, "dir") to Sha256.image(werewolf), anchor to Sha256.image(changed)), world.addons(), "Default's directory anchor holds bytes no one recorded, so it stays, and nothing else does")
        assertTrue(world.logged.any { "$addons/$anchor" in it }, "and is reported left in place")
    }

    @Test
    fun aNameTakenAgainAsItsAnchorGoesInAnUndoneChangeOfFilesKeepsTheAnchorAndTheUndoWaits() = runTest {
        val (world, library) = installed(101, 103)
        val old = world.record()
        val directory = "$addons/${pak(1, "dir")}"
        val stranger = FakeVpk.directory(seed = 12)
        world.files.failing += FaultPoint.Link("$addons/${pak(2, "dir")}")
        world.store.afterStore = { if ((it.pending as? PendingChange.Replace)?.step == ReplaceStep.RollingBack) world.files.failing.clear() }
        world.files.around = { point ->
            if (point is FaultPoint.Remove && point.path.startsWith("$directory$TemporaryInfix")) {
                world.files.around = null
                world.files.putBytes(root, directory, stranger)
            }
        }
        changeFilesTo(library, old.installId, 103, 102)

        val change = assertIs<PendingChange.Replace>(world.manifest().pending)
        assertEquals(ReplaceStep.RollingBack, change.step, "the undo waits")
        assertEquals(old, world.record())
        assertEquals(Sha256.image(stranger), world.addons()[pak(1, "dir")], "the file put at the name stays")
        assertEquals(Sha256.image(defaultDir), world.addons()["${pak(1, "dir")}$TemporaryInfix${change.token.value}"], "and Default's directory stays under its anchor, its only copy")
    }
}
