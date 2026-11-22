package gg.hideouthq.core.library

import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.DownloadProgress
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.ModInstaller
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.UnpackResult
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryQueueTest {
    private val root = GameRoot("/games/Deadlock")
    private val other = GameRoot("/other/Deadlock")

    private val mods = (1L..4L).map { SubmissionRef(SubmissionKind.Mod, it) }
    private val versions = SubmissionRef(SubmissionKind.Mod, 20)
    private val chunked = SubmissionRef(SubmissionKind.Mod, 30)
    private val red = FakeVpk.directory(seed = 5)
    private val chunkedDir = FakeVpk.directory(embeddedBytes = 4, chunkReads = mapOf(0 to 30L), seed = 2)
    private val chunk0 = ByteArray(30) { 3 }

    private fun remote(id: Long, size: Long, downloads: Long = 1) = RemoteFile(id, "file-$id.zip", size, null, null, downloads, 1, "https://gamebanana.com/dl/$id")

    private fun off(part: String) = VpkPart.parse(part)!!.fileName(SetKey.of(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, chunked.id, 31, null)), "ivy_dir.vpk").offStem("id1"))

    private inner class World {
        val files = InMemoryGameFiles().apply { listOf(root, other).forEach { putBytes(it, InMemoryGameFiles.GameInfo, VanillaGameInfo) } }
        val store = InMemoryLibraryStore()
        val disk = FlakyStore(store)
        val preferences = Preferences(MemoryStorage())
        val processes = FakeProcesses()

        val scripted = ArrayDeque<ProcessScan>()
        val banana = FakeGameBanana(
            mods.associateWith { FileListing.Available("Mod ${it.id}", listOf(remote(10 + it.id, 100 * it.id))) } +
                (versions to FileListing.Available("Versions", listOf(remote(21, 50, downloads = 50), remote(22, 60, downloads = 5)))) +
                (chunked to FileListing.Available("Chunked", listOf(remote(31, 40)))),
        )
        var listingGate: CompletableDeferred<Unit>? = null
        var unpackGate: CompletableDeferred<Unit>? = null
        val downloader = FakeDownloader(files).apply {
            mods.forEach { serve(10 + it.id, listOf("m${it.id}.vpk" to FakeVpk.directory(seed = it.id.toInt()))) }
            serve(21, listOf("blue.vpk" to FakeVpk.directory(seed = 21)))
            serve(22, listOf("Blue/x.vpk" to FakeVpk.directory(seed = 22), "Red/y.vpk" to red))
            serve(31, listOf("ivy_dir.vpk" to chunkedDir, "ivy_000.vpk" to chunk0))
        }
        var archives: Downloader = downloader
        private var nextId = 0

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root, other)), preferences, disk, files,
                InstallPorts(
                    GameBananaClient { submission ->
                        listingGate?.await()
                        banana.files(submission)
                    },
                    archives,
                    object : ModInstaller by files {
                        override suspend fun unpack(archive: ArchiveIdentity, limits: UnpackLimits, originalName: String): UnpackResult {
                            unpackGate?.await()
                            return files.unpack(archive, limits)
                        }
                    },
                    files,
                    object : GameProcesses by processes {
                        override suspend fun scan() = scripted.removeFirstOrNull() ?: processes.scan()
                    },
                ),
                scope,
                ids = { "id${++nextId}" },
                watch = pollingWatchTiming,
            ),
        )

        fun manifest() = store.manifest ?: LibraryManifest()

        fun installed(): List<Long> = manifest().records.map { it.remoteIdentity.submissionId }.sorted()

        fun addonsFiles() = files.addonsFiles(root)

        fun strayArchives(): Set<String> = files.archivePaths() - manifest().downloads.map { it.archive.path }.toSet()
    }

    private fun ModLibrary.stage(mod: SubmissionRef): OperationStage? = state.value.operations[mod.modId]?.stage

    private fun TestScope.installAll(library: ModLibrary, vararg submissions: SubmissionRef) {
        submissions.forEach { library.install(it.modId) }
        runCurrent()
    }

    @Test
    fun twoDownloadsRunAtOnceAndTheRestWaitInTheOrderAsked() = runTest {
        val world = World()
        val gates = mods.associate { 10 + it.id to CompletableDeferred<Unit>() }
        world.downloader.gates.putAll(gates)
        val library = with(world) { library() }
        mods.forEach { library.install(it.modId) }
        assertEquals(TransferSummary(count = 4, doneBytes = 0, totalBytes = 0), library.state.value.transfers(), "preparing installs count but add no bytes")
        runCurrent()

        assertEquals(listOf(11L, 12L), world.downloader.requested)
        assertEquals(OperationStage.Downloading(DownloadProgress(50, 100)), library.stage(mods[0]))
        assertEquals(OperationStage.Downloading(DownloadProgress(100, 200)), library.stage(mods[1]))
        assertEquals(OperationStage.Waiting(300), library.stage(mods[2]))
        assertEquals(OperationStage.Waiting(400), library.stage(mods[3]))
        assertEquals(InstallStatus.Waiting, library.status(mods[2].modId).first())
        val summary = assertNotNull(library.state.value.transfers())
        assertEquals(TransferSummary(count = 4, doneBytes = 150, totalBytes = 1_000), summary)
        assertEquals(15, summary.percent)

        gates.getValue(12).complete(Unit)
        runCurrent()
        assertEquals(listOf(11L, 12L, 13L), world.downloader.requested, "the first install waiting takes the free slot")
        assertEquals(OperationStage.Waiting(400), library.stage(mods[3]))
        assertEquals(listOf(2L), world.installed())

        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(listOf(11L, 12L, 13L, 14L), world.downloader.requested)
        assertEquals(listOf(1L, 2L, 3L, 4L), world.installed())
        assertEquals(2, world.downloader.mostAtOnce)
        assertNull(library.state.value.transfers())
        assertEquals(emptyMap(), library.state.value.operations)
    }

    @Test
    fun cancellingAWaitingOrDownloadingInstallFreesItsSlotAndLeavesNothingBehind() = runTest {
        val world = World()
        val gates = mods.associate { 10 + it.id to CompletableDeferred<Unit>() }
        world.downloader.gates.putAll(gates)
        val library = with(world) { library() }
        installAll(library, *mods.toTypedArray())

        assertTrue(library.cancel(mods[2].modId), "waiting")
        assertTrue(library.cancel(mods[0].modId), "downloading")
        assertEquals(OperationStage.Cancelling, library.stage(mods[0]))
        assertEquals(InstallStatus.Cancelling, library.status(mods[0].modId).first())
        assertFalse(library.cancel(mods[0].modId), "already cancelling")
        runCurrent()

        assertEquals(setOf(mods[1].modId, mods[3].modId), library.state.value.operations.keys)
        assertEquals(listOf(11L, 12L, 14L), world.downloader.requested, "the freed slot went to the next install still waiting")
        assertEquals(emptySet(), world.files.archivePaths())
        assertEquals(emptyList(), world.manifest().downloads)

        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        assertEquals(listOf(2L, 4L), world.installed())
        assertEquals(emptySet(), world.strayArchives())
        assertEquals(emptySet(), world.files.stagingFolders)
        assertFalse(library.cancel(mods[1].modId), "nothing runs")
    }

    @Test
    fun cancellingBeforeCommitAndRefusingRunningInstallsLeavesNothingBehind() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        val library = with(world) { library() }

        world.listingGate = CompletableDeferred()
        installAll(library, mods[0])
        assertEquals(OperationStage.Preparing, library.stage(mods[0]))
        assertTrue(library.cancel(mods[0].modId))
        runCurrent()
        world.listingGate = null
        assertNull(library.stage(mods[0]))

        installAll(library, versions)
        assertIs<OperationStage.ChoosingFile>(library.stage(versions))
        assertTrue(library.cancel(versions.modId))
        runCurrent()
        assertNull(library.stage(versions))

        world.unpackGate = CompletableDeferred()
        installAll(library, mods[1])
        assertEquals(OperationStage.Unpacking, library.stage(mods[1]))
        assertTrue(library.cancel(mods[1].modId))
        runCurrent()
        world.unpackGate = null
        assertNull(library.stage(mods[1]))

        world.processes.scan = ProcessScan.Seen(game = true)
        installAll(library, mods[2])
        assertNull(library.stage(mods[2]), "installed while Deadlock runs")
        assertEquals(0, world.files.stagingFolders.size)
        assertFalse(library.cancel(mods[2].modId))
        assertFalse(world.manifest().records.single().enabled, "placed in its off folder")

        assertEquals(listOf(12L, 13L), world.downloader.requested)
        assertEquals(listOf(3L), world.installed())
        assertEquals(emptyList(), world.manifest().downloads, "each download was registered, then deleted as Settings › Mods says")
        assertEquals(emptySet(), world.files.archivePaths())
        assertEquals(emptySet(), world.files.stagingFolders)
        assertEquals(emptyMap(), world.addonsFiles())
        assertContentEquals(VanillaGameInfo, world.files.gameInfo(root))
    }

    @Test
    fun anInstallPlacingItsFilesCannotBeCancelled() = runTest {
        val world = World()
        lateinit var library: ModLibrary
        val answers = mutableListOf<Boolean>()
        world.files.around = { point -> if (point is FaultPoint.Stage) answers += library.cancel(mods[0].modId) }
        library = with(world) { library() }
        installAll(library, mods[0])
        assertEquals(listOf(false), answers)
        assertEquals(listOf(1L), world.installed())
    }

    @Test
    fun aDownloadCancelledAfterItsArchiveIsNamedIsRegisteredThenKeptOrDeletedAsSettingsSay() = runTest {
        for (keep in listOf(true, false)) {
            val world = World()
            world.preferences.setKeepDownloads(keep)
            world.downloader.held = CompletableDeferred()
            val library = with(world) { library() }
            installAll(library, mods[0])
            val archive = world.files.archivePaths().single()
            assertEquals(OperationStage.Downloading(DownloadProgress(100, 100)), library.stage(mods[0]))

            assertTrue(library.cancel(mods[0].modId))
            runCurrent()
            assertEquals(emptyMap(), library.state.value.operations, "keep=$keep")
            assertEquals(emptyList(), world.manifest().records)
            assertEquals(emptySet(), world.strayArchives(), "keep=$keep: no archive is left that the manifest does not know")
            assertEquals(if (keep) listOf(archive) else emptyList(), world.manifest().downloads.map { it.archive.path }, "keep=$keep")
            assertEquals(if (keep) setOf(archive) else emptySet(), world.files.archivePaths(), "keep=$keep")
            assertEquals(world.manifest().downloads, assertIs<LibraryStatus.Ready>(library.state.value.library).clearable, "keep=$keep: no longer pinned")
            assertEquals(emptySet(), world.files.stagingFolders)
        }
    }

    private fun TestScope.retriedAfterARefusedSave(world: World, sets: List<String>): ModLibrary {
        world.store.refuse = { manifest -> "disk full".takeIf { manifest.records.isNotEmpty() } }
        val library = with(world) { library() }
        val seen = mutableListOf<OperationStage?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { library.state.collect { seen += it.operations[versions.modId]?.stage } }
        installAll(library, versions)
        val file = assertIs<OperationStage.ChoosingFile>(library.stage(versions))
        assertTrue(library.answer(file.id, ChoiceAnswer.Files(setOf(22), emptySet())))
        runCurrent()
        val set = assertIs<OperationStage.ChoosingSet>(library.stage(versions))
        assertTrue(library.answer(set.id, ChoiceAnswer.Sets(sets, emptySet())))
        runCurrent()
        assertEquals(OperationStage.Failed(LibraryProblem.ManifestSave("disk full")), library.stage(versions))

        world.store.refuse = { null }
        seen.clear()
        assertTrue(library.retry(versions.modId))
        runCurrent()
        assertEquals(emptyList(), seen.filter { it is OperationStage.ChoosingFile || it is OperationStage.ChoosingSet }, "nothing is asked again")
        assertEquals(listOf(22L, 22L), world.downloader.requested, "the archive is fetched once per attempt, not once per component")
        return library
    }

    @Test
    fun retryRepeatsTheChosenFileSetAndSwitchWithoutAskingAgain() = runTest {
        val world = World()
        val library = retriedAfterARefusedSave(world, listOf("Red/y.vpk"))
        val record = world.manifest().records.single()
        assertEquals(22L, record.remoteIdentity.fileId)
        assertFalse(record.enabled, "the switch stays off as it was answered")
        assertEquals(mapOf(record.files.single().name to Sha256.image(red)), world.files.filesIn(root, SetFolder.Off(versions)))
        assertEquals(emptyMap(), world.addonsFiles())
        assertFalse(library.retry(versions.modId), "only a failed install is retried")
    }

    @Test
    fun retryRepeatsEveryChosenComponentInItsChosenOrderAndFetchesTheArchiveOnce() = runTest {
        val world = World()
        retriedAfterARefusedSave(world, listOf("Red/y.vpk", "Blue/x.vpk"))
        val record = world.manifest().records.single()
        assertEquals(listOf("Red/y.vpk", "Blue/x.vpk"), record.sets.map { it.componentKey }, "the remembered order wins")
        assertEquals(listOf(22L, 22L), record.sets.map { it.fileId }, "both components share the one GameBanana file")
    }

    @Test
    fun retryAsksWhatAFailedInstallWasNeverToldAndCannotRepeatAnInstallThatNeverRan() = runTest {
        val world = World()
        val listing = world.banana.listings.getValue(versions)
        world.banana.listings = world.banana.listings + (versions to FileListing.Unavailable("HTTP 502"))
        val library = with(world) { library() }
        installAll(library, versions)
        assertEquals(OperationStage.Failed(LibraryProblem.MetadataUnavailable("HTTP 502")), library.stage(versions))

        world.banana.listings = world.banana.listings + (versions to listing)
        assertTrue(library.retry(versions.modId))
        runCurrent()
        assertIs<OperationStage.ChoosingFile>(library.stage(versions))

        library.install("werecat")
        assertEquals(OperationStage.Failed(LibraryProblem.NotAGameBananaMod), library.state.value.operations["werecat"]?.stage)
        assertFalse(library.retry("werecat"))
        assertFalse(library.retry("mod-404"))
    }

    @Test
    fun aLateProgressReportFromAnEarlierAttemptChangesNothing() = runTest {
        val world = World()
        val reports = mutableListOf<(DownloadProgress) -> Unit>()
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[11] = gate
        world.archives = object : Downloader by world.downloader {
            override suspend fun download(
                folder: DownloadFolder,
                submission: SubmissionRef,
                file: RemoteFile,
                onPublished: (ArchiveIdentity) -> Unit,
                onProgress: (DownloadProgress) -> Unit,
            ): DownloadResult {
                reports += onProgress
                return world.downloader.download(folder, submission, file, onPublished, onProgress)
            }
        }
        val library = with(world) { library() }
        installAll(library, mods[0])
        assertTrue(library.cancel(mods[0].modId))
        reports.single()(DownloadProgress(99, 100))
        assertEquals(OperationStage.Cancelling, library.stage(mods[0]), "a report never undoes a cancel")
        runCurrent()
        assertNull(library.stage(mods[0]))

        installAll(library, mods[0])
        assertEquals(OperationStage.Downloading(DownloadProgress(50, 100)), library.stage(mods[0]))
        reports.first()(DownloadProgress(99, 100))
        assertEquals(OperationStage.Downloading(DownloadProgress(50, 100)), library.stage(mods[0]), "the first attempt's report does not reach the second")

        gate.complete(Unit)
        runCurrent()
        reports.first()(DownloadProgress(1, 100))
        assertEquals(emptyMap(), library.state.value.operations)
        assertEquals(listOf(1L), world.installed())
    }

    @Test
    fun deadlockStartingWhileFilesArePlacedLetsTheInstallFinishOffAndItTurnsOnOnceDeadlockCloses() = runTest {
        val points = listOf<Pair<String, (FaultPoint) -> Boolean>>(
            "after staging the first copy" to { it is FaultPoint.Stage && it.temporary.startsWith(off("000")) },
            "after publishing the chunk" to { it == FaultPoint.Linked(off("000")) },
            "after publishing the directory" to { it == FaultPoint.Linked(off("dir")) },
        )
        for ((name, at) in points) {
            val world = World()
            world.files.around = { point -> if (at(point)) world.processes.scan = ProcessScan.Seen(game = true) }
            val library = with(world) { library() }
            installAll(library, chunked)
            assertNull(library.stage(chunked), "$name: the install finishes in its own off folder")
            assertEquals(listOf(30L), world.installed(), "$name: installed exactly once")
            assertNull(world.manifest().pending, name)
            assertEquals(ChoiceSync.Waiting(GameActivity.Running), library.state.value.choiceSync, "$name: turning it on waits")
            advanceTimeBy(10.seconds)
            assertEquals(emptyMap(), world.addonsFiles(), "$name: nothing is placed where the game loads it while it runs")

            world.files.around = null
            world.processes.scan = ProcessScan.Seen()
            advanceTimeBy(2.seconds)
            runCurrent()
            assertEquals(setOf("pak99_dir.vpk", "pak99_000.vpk"), world.addonsFiles().keys, "$name: on once Deadlock closes, with no copy left")
            assertEquals(Sha256.image(chunk0), world.addonsFiles()["pak99_000.vpk"])
            assertTrue(world.downloader.requested.all { it == 31L }, name)
            assertNull(assertIs<LibraryStatus.Ready>(library.state.value.library).interrupted)
        }
    }

    private val offDirectory = "${SetFolder.Off(chunked).path}/${off("dir")}"

    private fun TestScope.earlierPlacement(world: World, step: InstallStep): ModLibrary {
        val releasing = step == InstallStep.Releasing
        world.files.crashAt = if (releasing) FaultPoint.Remove("${SetFolder.Off(chunked).path}/${off("000")}${TemporaryInfix}id1") else FaultPoint.Linked(off("dir"))
        val doomed = crashableScope()
        with(world) { library(doomed) }.install(chunked.modId)
        runCurrent()
        doomed.cancel()
        world.files.crashAt = null
        assertEquals(step, assertIs<PendingChange.Install>(world.manifest().pending).step)
        world.files.failFile(root, offDirectory, "permission denied")
        world.files.failing += FaultPoint.Inspect(off("dir"))
        return with(world) { library() }.also { assertEquals(step, assertIs<PendingChange.Install>(world.manifest().pending).step, "the placement waits") }
    }

    private fun World.readAgain() {
        files.readable(root, offDirectory)
        files.failing.clear()
    }

    @Test
    fun noWayToStartOrCancelAnInstallHidesAnEarlierPlacementThatMayStillCommit() = runTest {
        val failures = mutableListOf<String>()
        for (step in listOf(InstallStep.Releasing, InstallStep.Publishing)) {
            for ((path, runsBeforeCancel) in listOf("install" to true, "install, cancelled before it runs" to false)) {
                val world = World()
                val library = earlierPlacement(world, step)
                library.install(chunked.modId)
                if (runsBeforeCancel) runCurrent()
                val seen = library.stage(chunked)
                val cancelled = library.cancel(chunked.modId)
                runCurrent()
                world.readAgain()
                advanceTimeBy(5.seconds)
                runCurrent()
                with(world) { library() }
                val installed = world.installed()
                val line = "$step / $path: stage $seen, cancel ${if (cancelled) "accepted" else "refused"}, afterwards installed $installed"
                if (cancelled && installed.isNotEmpty()) failures += "$line: a successful cancel was followed by the install"
                if (runsBeforeCancel && seen?.cancellable == true && !cancelled) failures += "$line: the stage claims it can be cancelled"
                if (seen?.cancellable == false && cancelled) failures += "$line: a stage that says it cannot be cancelled was"
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun anInstallInterruptedByASaveThatCannotBeConfirmedFailsRatherThanWaitingAsIfCancellable() = runTest {
        val world = World()
        var unconfirmed = false
        world.store.afterStore = { manifest ->
            if (!unconfirmed && (manifest.pending as? PendingChange.Install)?.step == InstallStep.Releasing) {
                unconfirmed = true
                world.processes.scan = ProcessScan.Seen(game = true)
                world.disk.unreadableLoads = 1
                error("the disk did not confirm the write")
            }
        }
        val library = with(world) { library() }
        installAll(library, chunked)

        val failed = assertIs<OperationStage.Failed>(library.stage(chunked), "what the manifest holds is unknown, so the install cannot honestly be cancelled")
        assertIs<LibraryProblem.Interrupted>(failed.problem)
        assertFalse(library.cancel(chunked.modId))
    }

    @Test
    fun deadlockStartingJustAsTheInstallBeginsPlacesItOffUntilItCloses() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.unpackGate = CompletableDeferred()
        installAll(library, mods[0])
        assertEquals(OperationStage.Unpacking, library.stage(mods[0]))

        world.scripted += listOf(ProcessScan.Seen(), ProcessScan.Seen(game = true))
        world.processes.scan = ProcessScan.Seen(game = true)
        world.unpackGate!!.complete(Unit)
        runCurrent()
        assertNull(library.stage(mods[0]), "seen closed, then running once the library was taken: placed off instead")
        assertEquals(emptyList(), world.scripted.toList())
        assertEquals(emptyMap(), world.addonsFiles())
        assertContentEquals(VanillaGameInfo, world.files.gameInfo(root))
        assertEquals(listOf(1L), world.installed())
        assertFalse(world.manifest().records.single().enabled)

        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertTrue(world.manifest().records.single().enabled, "turned on once Deadlock closes")
    }

    @Test
    fun deadlockStartingJustBeforeGameinfoIsReplacedLeavesItUntouchedUntilItCloses() = runTest {
        val world = World()
        world.store.afterStore = { manifest -> if (manifest.pending is PendingChange.ConfigEdit) world.processes.scan = ProcessScan.Seen(game = true) }
        val library = with(world) { library() }
        installAll(library, mods[0])

        assertNull(library.stage(mods[0]), "placed off instead")
        assertContentEquals(VanillaGameInfo, world.files.gameInfo(root))
        assertNull(world.manifest().pending, "the edit was withdrawn before it touched gameinfo.gi")
        assertEquals(emptyMap(), world.addonsFiles())
        assertEquals(listOf(1L), world.installed())

        world.store.afterStore = {}
        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(listOf(1L), world.installed())
        assertTrue(world.manifest().records.single().enabled)
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(world.files.gameInfo(root)!!))
    }

    @Test
    fun aWaitingInstallGoesOnlyIntoTheGameItWasStartedFor() = runTest {
        val world = World()
        val gate = CompletableDeferred<Unit>()
        world.downloader.gates[11] = gate
        val library = with(world) { library() }
        installAll(library, mods[0])
        assertIs<OperationStage.Downloading>(library.stage(mods[0]))

        assertEquals(FolderCheck.Valid(other), library.useGameFolder(other.path))
        gate.complete(Unit)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(OperationStage.Failed(LibraryProblem.GameNotFound), library.stage(mods[0]))
        assertEquals(emptyList(), world.manifest().records)
        assertEquals(emptyMap(), world.files.addonsFiles(root))
        assertEquals(emptyMap(), world.files.addonsFiles(other))
        assertEquals(emptySet(), world.files.stagingFolders)
    }

    @Test
    fun anInstallReachingTheGameWhileDragsWaitSavesAndAppliesThemFirstThenInstalls() = runTest {
        val world = World()
        val library = with(world) { library() }
        installAll(library, mods[0], mods[1])
        assertEquals(listOf(1L, 2L), world.installed())
        val turnedOn = world.store.arranges()
        val unpacking = CompletableDeferred<Unit>()
        world.unpackGate = unpacking
        library.install(mods[2].modId)
        runCurrent()
        assertEquals(OperationStage.Unpacking, library.stage(mods[2]))

        val scans = CompletableDeferred<Unit>()
        world.processes.gate = scans
        val shown = assertNotNull(library.state.value.orderView)
        library.reorder(shown, shown.sets.reversed())
        runCurrent()
        library.reorder(assertNotNull(library.state.value.orderView), shown.sets)
        assertTrue(library.state.value.saving, "the second drag waits while the first one's files are held")
        unpacking.complete(Unit)
        runCurrent()
        assertEquals(OperationStage.Installing, library.stage(mods[2]))

        scans.complete(Unit)
        runCurrent()
        assertNull(library.stage(mods[2]), "the install goes ahead once the drags admitted before it are saved and applied")
        assertEquals(listOf(1L, 2L, 3L), world.installed())
        assertNull(library.state.value.choiceProblem)
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertNull(world.manifest().savedChoices)
        val third = world.manifest().records.single { it.modId == mods[2].modId }.installId
        assertEquals(listOf(third) + shown.sets, onLoadOrder(world.manifest().records.filter { it.gameRoot == root }), "the new mod first, then the order the drags left")
        assertEquals(turnedOn + 2, world.store.arranges(), "the first drag, then the second with the new mod turned on")
    }
}
