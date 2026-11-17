package gg.hideouthq.core.library

import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.UnfinishedInstall
import gg.hideouthq.core.data.Preferences
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
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryDownloadsTest {
    private val root = GameRoot("/games/Deadlock")
    private val other = GameRoot("/other/Deadlock")
    private val folderA = "/downloads/a"
    private val folderB = "/downloads/b"
    private val addons = SetFolder.Addons.path

    private val single = SubmissionRef(SubmissionKind.Mod, 101)
    private val twoSets = SubmissionRef(SubmissionKind.Mod, 102)
    private val next = SubmissionRef(SubmissionKind.Mod, 103)

    private val foreignVpk = ByteArray(9) { 7 }
    private val strayPath = "$folderA/mod-9/9/stray.zip"
    private val strayImage = FileImage(5, sha256Hex(ByteArray(5) { 1 }))

    private fun remote(id: Long, size: Long) = RemoteFile(id, "file-$id.zip", size, null, null, 1, 1, "https://gamebanana.com/dl/$id")

    private fun identity(modId: Long, fileId: Long) = SubmissionIdentity(SubmissionKind.Mod, modId, fileId, null)

    private fun owned(modId: Long, fileId: Long, size: Int, retention: DownloadRetention = DownloadRetention.Keep) = OwnedDownload(
        identity(modId, fileId),
        ArchiveIdentity("$folderA/mod-$modId/$fileId/archive-$size.zip", size.toLong(), sha256Hex(ByteArray(size) { size.toByte() })),
        retention,
    )

    private fun record(installId: String, gameRoot: GameRoot, source: SubmissionIdentity, archive: ArchiveIdentity, number: Int, bytes: ByteArray) = oneSetRecord(
        installId = installId,
        gameRoot = gameRoot,
        source = InstalledSource.Remote(source),
        title = installId,
        variant = null,
        archive = archive,
        number = number,
        files = listOf(SetFile(VpkPart.Directory, FileImage(bytes.size.toLong(), sha256Hex(bytes)))),
    )

    private inner class World(seed: LibraryManifest? = null) {
        val files = InMemoryGameFiles().apply {
            listOf(root, other).forEach { putBytes(it, InMemoryGameFiles.GameInfo, VanillaGameInfo) }
            putBytes(root, "$addons/pak01_dir.vpk", foreignVpk)
            putArchive(strayPath, emptyList(), strayImage)
            seed?.records?.forEach { record -> putBytes(record.gameRoot, "$addons/${record.files.single().name}", ByteArray(record.files.single().sizeBytes.toInt()) { 2 }) }
            seed?.downloads?.forEach { putArchive(it.archive.path, emptyList(), FileImage(it.archive.sizeBytes, it.archive.sha256)) }
        }
        val store = InMemoryLibraryStore(seed)
        val disk = FlakyStore(store)
        val storage = MemoryStorage()
        val preferences = Preferences(storage)
        val processes = FakeProcesses()
        val banana = FakeGameBanana(
            mapOf(
                single to FileListing.Available("Single", listOf(remote(11, 11))),
                twoSets to FileListing.Available("Two sets", listOf(remote(12, 19))),
                next to FileListing.Available("Next", listOf(remote(13, 13))),
            ),
        )
        val downloader: FakeDownloader = FakeDownloader(files, folderA).apply {
            serve(11, listOf("a.vpk" to FakeVpk.directory(seed = 1)))
            serve(12, listOf("one/x.vpk" to FakeVpk.directory(seed = 4), "two/y.vpk" to FakeVpk.directory(seed = 5)))
            serve(13, listOf("n.vpk" to FakeVpk.directory(seed = 3)))
        }
        var archives: Downloader = downloader
        val logged = mutableListOf<String>()
        private var nextId = 0

        val gameBananaBusy = MutableStateFlow<Instant?>(null)
        var listings: GameBananaClient = banana

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root, other)), preferences, disk, files,
                InstallPorts(listings, archives, files, files, processes, log = { logged += it }), scope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
                gameBananaBusy = gameBananaBusy,
            ),
        )

        fun manifest() = store.manifest ?: LibraryManifest()

        fun registered(archive: ArchiveIdentity): OwnedDownload? = manifest().downloads.singleOrNull { it.archive == archive }

        fun journal() = store.saved.mapNotNull { it.pending as? PendingChange.ArchiveRemoval }

        fun anchors(): Set<String> = files.archivePaths().filter { TemporaryInfix in it }.toSet()

        fun assertSentinels() {
            assertEquals(Sha256.image(foreignVpk), files.addonsFiles(root)["pak01_dir.vpk"], "the foreign VPK")
            assertEquals(strayImage, files.archiveFile(strayPath), "the archive nothing registered")
            assertEquals(VanillaGameInfo.toList(), files.gameInfo(other)?.toList(), "gameinfo.gi in $other")
        }
    }

    private fun TestScope.install(library: ModLibrary, submission: SubmissionRef) {
        library.install(submission.modId)
        runCurrent()
    }

    private fun TestScope.clear(library: ModLibrary, vararg downloads: OwnedDownload): ClearStage {
        library.clearDownloads(ClearConfirmation(downloads.toList()))
        runCurrent()
        return assertIs<Maintenance.Clear>(library.state.value.maintenance).stage
    }

    @Test
    fun aHeldDownloadFinishesInTheFolderItStartedInAndTheNextInstallUsesTheNewFolder() = runTest {
        val world = World()
        world.preferences.setDownloadFolder(DownloadFolder(folderA))
        val library = with(world) { library() }
        val gate = CompletableDeferred<Unit>()
        world.downloader.gate = gate
        install(library, single)
        assertIs<OperationStage.Downloading>(library.state.value.operations[single.modId]?.stage)

        world.preferences.setDownloadFolder(DownloadFolder(folderB))
        world.preferences.setKeepDownloads(false)
        gate.complete(Unit)
        runCurrent()
        val first = world.manifest().records.single { it.remoteIdentity.ref == single }.archive
        assertEquals("$folderA/mod-101/11/file-11.zip", first.path)
        assertEquals(DownloadRetention.Keep, world.registered(first)?.retention, "keeping was decided when the install began")
        assertTrue(first.path in world.files.archivePaths())

        world.downloader.gate = null
        install(library, next)
        val second = world.manifest().records.single { it.remoteIdentity.ref == next }.archive
        assertEquals("$folderB/mod-103/13/file-13.zip", second.path)
        assertFalse(second.path in world.files.archivePaths(), "the next install began with keeping off")
        assertEquals(listOf(first), world.manifest().downloads.map { it.archive })
        world.assertSentinels()
    }

    @Test
    fun aRememberedFolderThatIsGoneFailsTheDownloadInsteadOfSendingItElsewhere() = runTest {
        val world = World()
        world.preferences.setDownloadFolder(DownloadFolder(folderB))
        world.downloader.unavailable[folderB] = FolderRefusal.Missing
        val library = with(world) { library() }
        install(library, single)
        val failed = assertIs<OperationStage.Failed>(library.state.value.operations[single.modId]?.stage)
        assertIs<LibraryProblem.Download>(failed.problem)
        assertEquals(setOf(strayPath), world.files.archivePaths())
        assertEquals(emptyList(), world.manifest().downloads)
    }

    @Test
    fun anArchiveInAFolderUsedBeforeIsStillDeletedOnUninstall() = runTest {
        val world = World()
        world.preferences.setDownloadFolder(DownloadFolder(folderA))
        val library = with(world) { library() }
        install(library, single)
        val record = world.manifest().records.single()
        world.preferences.setDownloadFolder(DownloadFolder(folderB))

        val confirmation = assertNotNull(library.uninstallConfirmation(record.installId))
        assertEquals(listOf(record.archive), confirmation.downloads)
        library.uninstall(confirmation, deleteDownload = true)
        runCurrent()
        assertFalse(record.archive.path in world.files.archivePaths())
        assertEquals(emptyList(), world.manifest().downloads)
        assertEquals(emptyList(), world.manifest().records)
    }

    @Test
    fun keepOffDeletesTheDownloadOnceItsInstallEndsAndTheRecordKeepsItsIdentity() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        val library = with(world) { library() }
        install(library, single)

        val record = world.manifest().records.single()
        assertEquals(setOf("pak01_dir.vpk") + record.files.map { it.name }, world.files.addonsFiles(root).keys, "installed beside the foreign VPK")
        assertEquals("$folderA/mod-101/11/file-11.zip", record.archive.path, "the record still says which archive it came from")
        assertEquals(setOf(strayPath), world.files.archivePaths())
        assertEquals(emptyList(), world.manifest().downloads)
        assertNull(world.manifest().pending)
        val journal = world.journal()
        assertEquals(listOf(ArchiveStep.Anchoring, ArchiveStep.Releasing, ArchiveStep.Settled(RemovalOutcome.Removed)), journal.map { it.step })
        assertEquals(setOf(record.installId), journal.map { it.installedInto }.toSet(), "only the record made from it may name it")
        assertEquals(DownloadRetention.DiscardAfterInstall, journal.first().download.retention)
        world.assertSentinels()
    }

    @Test
    fun keepOffDeletesTheDownloadOfAnInstallThatFailed() = runTest {
        val world = World()
        world.downloader.serve(11, listOf("a/x.vpk" to FakeVpk.directory(seed = 1), "A/X.VPK" to FakeVpk.directory(seed = 1)))
        world.preferences.setKeepDownloads(false)
        val library = with(world) { library() }
        install(library, single)

        assertIs<LibraryProblem.ArchiveRefused>(assertIs<OperationStage.Failed>(library.state.value.operations[single.modId]?.stage).problem)
        assertEquals(emptyList(), world.manifest().records)
        assertEquals(emptyList(), world.manifest().downloads)
        assertEquals(setOf(strayPath), world.files.archivePaths())
        assertEquals(listOf(null), world.journal().map { it.installedInto }.distinct())
        world.assertSentinels()
    }

    @Test
    fun aDownloadWaitingOnAQuestionIsNeverClearedAndKeepOffDeletesItOnlyAfterItsUnpackedFilesArePutAway() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        val library = with(world) { library() }
        install(library, twoSets)
        val asked = assertIs<OperationStage.ChoosingSet>(library.state.value.operations[twoSets.modId]?.stage)
        val download = world.manifest().downloads.single()
        assertEquals(19, download.archive.sizeBytes)
        assertNull(assertNotNull(library.measureDownloads()).clear, "an archive an install is reading is never offered")

        assertEquals(ClearStage.Cleared(removed = 0, freedBytes = 0, kept = 1), clear(library, download))
        assertTrue(download.archive.path in world.files.archivePaths())
        assertTrue(world.journal().isEmpty(), "nothing is journalled for an archive in use")

        val stagingAtRemoval = mutableListOf<Boolean>()
        world.files.around = { point -> if (point == FaultPoint.Remove(download.archive.path)) stagingAtRemoval += world.files.stagingFolders.isEmpty() }
        assertTrue(library.answer(asked.id, ChoiceAnswer.Cancel))
        runCurrent()
        assertFalse(download.archive.path in world.files.archivePaths())
        assertEquals(listOf(true), stagingAtRemoval, "the archive went only once what it unpacked was put away")
        assertEquals(emptyList(), world.manifest().downloads)
        assertEquals(emptyList(), world.manifest().records)
    }

    @Test
    fun aDownloadAnotherRecordNamesInAnyGameIsNeverDiscardedAndAKeptOneNeverBecomesDisposable() = runTest {
        val discard = owned(201, 21, size = 31, retention = DownloadRetention.DiscardAfterInstall)
        val kept = owned(202, 22, size = 37)
        val installed = record("installed", root, discard.source, discard.archive, number = 3, bytes = ByteArray(4) { 2 })
        val elsewhere = record("elsewhere", other, identity(203, 23), discard.archive, number = 3, bytes = ByteArray(4) { 2 })
        val world = World(LibraryManifest(records = listOf(installed, elsewhere), downloads = listOf(discard, kept)))
        world.preferences.setKeepDownloads(false)
        with(world) { library() }

        assertEquals(setOf(discard.archive.path, kept.archive.path, strayPath), world.files.archivePaths())
        assertEquals(listOf(discard, kept), world.manifest().downloads)
        assertTrue(world.journal().isEmpty())
    }

    @Test
    fun aCrashBetweenTheInstallAndItsDiscardIsFinishedWhenHideoutStartsAgain() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        world.store.refuse = { if (it.pending is PendingChange.ArchiveRemoval) throw SimulatedCrash(FaultPoint.ReplaceConfig) else null }
        val doomed = with(world) { crashableScope() }
        val library = with(world) { library(doomed) }
        install(library, single)
        doomed.cancel()
        val record = world.manifest().records.single()
        assertTrue(record.archive.path in world.files.archivePaths(), "the crash came before the discard")
        assertEquals(DownloadRetention.DiscardAfterInstall, world.registered(record.archive)?.retention)

        world.store.refuse = { null }
        with(world) { library() }
        assertEquals(setOf(strayPath), world.files.archivePaths())
        assertEquals(emptyList(), world.manifest().downloads)
        assertEquals(listOf(record), world.manifest().records)
        assertEquals(listOf(record.installId), world.journal().map { it.installedInto }.distinct())
    }

    @Test
    fun aDiscardThatCannotProveTheFileIsKeptFromThenOn() = runTest {
        val changed = owned(204, 24, size = 29, retention = DownloadRetention.DiscardAfterInstall)
        val world = World(LibraryManifest(downloads = listOf(changed)))
        world.files.rewriteArchive(changed.archive.path, FileImage(29, sha256Hex(ByteArray(29) { 0 })))
        with(world) { library() }

        assertTrue(changed.archive.path in world.files.archivePaths())
        assertEquals(listOf(changed.copy(retention = DownloadRetention.Keep)), world.manifest().downloads)
        val saves = world.store.saved.size
        with(world) { library() }
        assertEquals(saves, world.store.saved.size, "a kept download is not tried again")
    }

    @Test
    fun clearDeletesOnlyTheUnusedDownloadAndKeepsEveryOneSomethingStillNeedsOrThatChanged() = runTest {
        val loose = owned(301, 31, size = 11)
        val otherRoot = owned(302, 32, size = 13)
        val changed = owned(305, 35, size = 23)
        val unreadable = owned(306, 36, size = 27)
        val elsewhere = record("elsewhere", other, otherRoot.source, otherRoot.archive, number = 4, bytes = ByteArray(4) { 2 })
        val world = World(LibraryManifest(records = listOf(elsewhere), downloads = listOf(loose, otherRoot, changed, unreadable)))
        world.files.rewriteArchive(changed.archive.path, FileImage(23, sha256Hex(ByteArray(23) { 0 })))
        world.files.failing += FaultPoint.Look(unreadable.archive.path)
        val library = with(world) { library() }
        install(library, twoSets)
        val pinned = world.manifest().downloads.single { it.source.ref == twoSets }
        assertEquals(19, pinned.archive.sizeBytes)

        val space = assertNotNull(library.measureDownloads())
        assertEquals(DownloadSpace(matching = 3, matchingBytes = 11 + 13 + 19, clear = ClearConfirmation(listOf(loose))), space, "a changed or unreadable download is not counted")
        assertEquals(ClearStage.Cleared(removed = 1, freedBytes = 11, kept = 0), clear(library, *space.clear!!.downloads.toTypedArray()))
        assertEquals(
            setOf(strayPath) + listOf(otherRoot, pinned, changed, unreadable).map { it.archive.path },
            world.files.archivePaths(),
        )
        assertEquals(listOf(otherRoot, changed, unreadable, pinned), world.manifest().downloads)

        val everything = listOf(otherRoot, pinned, changed, unreadable)
        assertEquals(ClearStage.Cleared(removed = 0, freedBytes = 0, kept = 4), clear(library, *everything.toTypedArray()), "even a confirmation that names them all")
        assertEquals(setOf(strayPath) + everything.map { it.archive.path }, world.files.archivePaths())
        assertEquals(FileImage(23, sha256Hex(ByteArray(23) { 0 })), world.files.archiveFile(changed.archive.path))
        assertEquals(emptySet(), world.anchors())
        assertEquals(listOf(elsewhere), world.manifest().records)
        world.assertSentinels()
    }

    @Test
    fun installAdmissionWaitsForTheCurrentArchiveJournalAndBothOperationsFinish() = runTest {
        val first = owned(401, 41, size = 11)
        val second = owned(402, 42, size = 12)
        val world = World(LibraryManifest(downloads = listOf(first, second)))
        val anchoring = CompletableDeferred<Unit>()
        world.archives = object : Downloader by world.downloader {
            override suspend fun link(existing: DownloadEntry, created: DownloadEntry): LinkResult {
                if (created.archive == first.archive) anchoring.await()
                return world.downloader.link(existing, created)
            }
        }
        val library = with(world) { library() }
        library.clearDownloads(ClearConfirmation(listOf(first, second)))
        runCurrent()
        install(library, single)
        anchoring.complete(Unit)
        runCurrent()

        assertEquals(ClearStage.Cleared(removed = 2, freedBytes = 23, kept = 0), assertIs<Maintenance.Clear>(library.state.value.maintenance).stage)
        val saves = world.store.saved
        val firstSettled = saves.indexOfLast { (it.pending as? PendingChange.ArchiveRemoval)?.download == first }
        val registered = saves.indexOfFirst { manifest -> manifest.downloads.any { it.source.ref == single } }
        val secondBegun = saves.indexOfFirst { (it.pending as? PendingChange.ArchiveRemoval)?.download == second }
        assertTrue(firstSettled < registered && firstSettled < secondBegun, "install admission and the next archive wait for the first journal: $firstSettled, $registered, $secondBegun")
        assertEquals(1, world.manifest().records.size)
        assertEquals(setOf(strayPath, world.manifest().records.single().archive.path), world.files.archivePaths())
    }

    @Test
    fun aCrashAtAnyStepOfClearingADownloadEndsWithItRegisteredAndWholeOrGoneAndUnregistered() = runTest {
        val loose = owned(501, 51, size = 11)
        val image = FileImage(loose.archive.sizeBytes, loose.archive.sha256)
        val dry = World(LibraryManifest(downloads = listOf(loose)))
        val points = mutableListOf<FaultPoint>()
        dry.files.around = { points += it }
        val start = dry.store.saved.size
        clear(with(dry) { library() }, loose)
        val saves = dry.store.saved.size - start
        assertTrue(points.size >= 5 && saves == 4, "the trace has $points and $saves saves")

        val cuts = points.indices.map { index -> "step $index ${points[index]}" to { world: World -> var seen = 0; world.files.around = { if (seen++ == index) throw SimulatedCrash(it) } } } +
            (0 until saves).flatMap { index ->
                listOf(
                    "before save $index" to { world: World -> var seen = 0; world.store.refuse = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig); null } },
                    "after save $index" to { world: World -> var seen = 0; world.store.afterStore = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig) } },
                    "unconfirmed save $index" to { world: World ->
                        var seen = 0
                        world.store.afterStore = { if (seen++ == index) { world.disk.unreadableLoads = 1; error("the disk did not confirm the write") } }
                    },
                )
            }
        for ((name, cut) in cuts) {
            val world = World(LibraryManifest(downloads = listOf(loose)))
            cut(world)
            val doomed = with(world) { crashableScope() }
            clear(with(world) { library(doomed) }, loose)
            doomed.cancel()
            world.files.around = null
            world.store.refuse = { null }
            world.store.afterStore = {}
            val restarted = with(world) { library() }
            try {
                assertNull(world.manifest().pending)
                assertNull((restarted.state.value.library as LibraryStatus.Ready).interrupted)
                val present = loose.archive.path in world.files.archivePaths()
                if (present) {
                    assertEquals(image, world.files.archiveFile(loose.archive.path))
                    assertEquals(listOf(loose.archive), world.manifest().downloads.map { it.archive })
                } else {
                    assertEquals(emptyList(), world.manifest().downloads)
                }
                assertEquals(emptySet(), world.anchors(), "no anchor of the download is left")
                world.assertSentinels()
            } catch (failure: AssertionError) {
                throw AssertionError("after a crash $name: ${failure.message}", failure)
            }
        }
    }

    @Test
    fun anEqualByteFilePutAtTheArchivesPathAfterACrashIsNeverDeleted() = runTest {
        val loose = owned(601, 61, size = 11)
        val world = World(LibraryManifest(downloads = listOf(loose)))
        world.store.afterStore = { if ((it.pending as? PendingChange.ArchiveRemoval)?.step == ArchiveStep.Releasing) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        val doomed = with(world) { crashableScope() }
        clear(with(world) { library(doomed) }, loose)
        doomed.cancel()
        assertEquals(1, world.anchors().size, "anchored and released nothing yet")

        val image = FileImage(loose.archive.sizeBytes, loose.archive.sha256)
        world.files.removeArchive(loose.archive.path)
        world.files.replaceArchive(loose.archive.path, image)
        world.store.afterStore = {}
        with(world) { library() }

        assertEquals(image, world.files.archiveFile(loose.archive.path), "the new file at the path stays")
        assertEquals(listOf(loose), world.manifest().downloads, "and the registration with it")
        assertEquals(emptySet(), world.anchors(), "Hideout's own copy goes with its anchor")
        assertNull(world.manifest().pending)
        assertIs<RemovalOutcome.Preserved>(assertIs<ArchiveStep.Settled>(world.journal().last().step).outcome)
    }

    @Test
    fun anAnchorOfAClearedDownloadWhoseBytesChangedStaysAndIsReported() = runTest {
        val loose = owned(611, 62, size = 11)
        val world = World(LibraryManifest(downloads = listOf(loose)))
        world.store.afterStore = { if ((it.pending as? PendingChange.ArchiveRemoval)?.step is ArchiveStep.Settled) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        val doomed = with(world) { crashableScope() }
        clear(with(world) { library(doomed) }, loose)
        doomed.cancel()
        world.store.afterStore = {}
        val anchor = world.anchors().single()
        assertFalse(loose.archive.path in world.files.archivePaths(), "the archive went, so its anchor is its last name")
        world.files.rewriteArchive(anchor, FileImage(loose.archive.sizeBytes, "8".repeat(64)))

        with(world) { library() }
        assertNull(world.manifest().pending)
        assertEquals(emptyList(), world.manifest().downloads, "the download is gone")
        assertEquals(setOf(anchor), world.anchors(), "its anchor holds bytes no one recorded, so it stays")
        assertTrue(world.logged.any { anchor in it }, "and is reported left in place")
        world.assertSentinels()
    }

    @Test
    fun aReferenceThatAppearsAfterTheConfirmationKeepsTheArchiveEvenMidJournal() = runTest {
        val loose = owned(701, 71, size = 11)
        val files = InMemoryGameFiles().apply { putArchive(loose.archive.path, emptyList(), FileImage(loose.archive.sizeBytes, loose.archive.sha256)) }
        val store = InMemoryLibraryStore(LibraryManifest(downloads = listOf(loose)))
        val preserved = mutableListOf<Preserved>()
        var id = 0
        fun ledger() = LibraryLedger(store, files, InstallPorts(FakeGameBanana(), FakeDownloader(files, folderA), files, files, FakeProcesses()), { "t${++id}" }, { preserved += it })
        val referencing = record("later", root, identity(709, 79), loose.archive, number = 2, bytes = ByteArray(4) { 2 })

        val ledger = ledger()
        ledger.reload()
        assertTrue(ledger.removable(loose, except = null))
        ledger.save(ledger.manifest.copy(records = listOf(referencing), profiles = ProfileBook.main(listOf(referencing))))
        assertEquals(ArchiveResult.Kept, ledger.removeArchive(loose, installedInto = null))
        assertEquals(emptyList(), store.saved.filter { it.pending != null })

        ledger.save(ledger.manifest.copy(records = emptyList(), profiles = ProfileBook.main()))
        store.afterStore = { if ((it.pending as? PendingChange.ArchiveRemoval)?.step == ArchiveStep.Anchoring) throw SimulatedCrash(FaultPoint.ReplaceConfig) }
        runCatching { ledger.removeArchive(loose, installedInto = null) }
        store.afterStore = {}
        val restarted = ledger()
        restarted.reload()
        restarted.save(restarted.manifest.copy(records = listOf(referencing), profiles = ProfileBook.main(listOf(referencing))))
        assertEquals(Recovery.Settled, restarted.recover())
        assertEquals(FileImage(loose.archive.sizeBytes, loose.archive.sha256), files.archiveFile(loose.archive.path))
        assertEquals(RemovalOutcome.Preserved("another install uses this download"), (store.saved.last { it.pending != null }.pending as PendingChange.ArchiveRemoval).step.let { (it as ArchiveStep.Settled).outcome })
        assertEquals(listOf(loose), restarted.manifest.downloads)
        assertNull(restarted.manifest.pending)
    }

    @Test
    fun aClearStoppedByARefusedSaveStillSaysWhatItAlreadyRemoved() = runTest {
        val first = owned(801, 81, size = 11)
        val second = owned(802, 82, size = 13)
        val world = World(LibraryManifest(downloads = listOf(first, second)))
        world.store.refuse = { manifest -> "the disk is full".takeIf { (manifest.pending as? PendingChange.ArchiveRemoval)?.download == second } }
        val library = with(world) { library() }

        val stopped = clear(library, first, second)
        assertFalse(first.archive.path in world.files.archivePaths(), "the first archive went")
        assertEquals(FileImage(13, second.archive.sha256), world.files.archiveFile(second.archive.path), "the second stays whole")
        assertEquals(listOf(second), world.manifest().downloads)
        assertNull(world.manifest().pending)
        assertEquals(ClearStage.Refused(LibraryProblem.ManifestSave("the disk is full")), stopped)
        world.assertSentinels()

        world.store.refuse = { null }
        assertEquals(ClearStage.Cleared(removed = 1, freedBytes = 13, kept = 0, alreadyGone = 1), clear(library, first, second), "the same confirmation again")
        assertEquals(setOf(strayPath), world.files.archivePaths())
        assertEquals(emptyList(), world.manifest().downloads)
        world.assertSentinels()
    }

    @Test
    fun retryingAClearWhoseLastSaveLandedUnreadReportsTheArchiveGoneAndNeverLooksAtItsPathAgain() = runTest {
        val loose = owned(901, 91, size = 11)
        val world = World(LibraryManifest(downloads = listOf(loose)))
        var landed = false
        world.store.afterStore = { saved ->
            if (!landed && saved.pending == null && saved.downloads.isEmpty()) {
                landed = true
                world.disk.unreadableLoads = 1
                error("the disk did not confirm the write")
            }
        }
        val library = with(world) { library() }

        val first = clear(library, loose)
        assertFalse(loose.archive.path in world.files.archivePaths(), "the archive went")
        assertEquals(emptyList(), world.manifest().downloads, "and the save unregistering it landed")
        assertNull(world.manifest().pending)
        assertNotNull(assertIs<LibraryStatus.Ready>(library.state.value.library).interrupted, "though Hideout could not read it back: $first")
        val unconfirmed = "Hideout could not confirm that its install list was saved (the disk did not confirm the write), so it reads the list again before changing anything"
        assertEquals(ClearStage.Refused(LibraryProblem.Interrupted(unconfirmed)), first)

        val looked = mutableListOf<FaultPoint>()
        world.files.around = { looked += it }
        val retried = clear(library, loose)
        assertEquals(emptyList(), looked, "nothing is looked at again once the removal settled")
        assertEquals(ClearStage.Cleared(removed = 0, freedBytes = 0, kept = 0, alreadyGone = 1), retried)
        assertNull(assertIs<LibraryStatus.Ready>(library.state.value.library).interrupted)
        assertEquals(listOf(ArchiveStep.Anchoring, ArchiveStep.Releasing, ArchiveStep.Settled(RemovalOutcome.Removed)), world.journal().map { it.step }, "one journal, never begun again")
        world.assertSentinels()
    }

    @Test
    fun aClearStoppedAfterItsRemovalSettledFinishesFromTheJournalAndNeverLooksAtTheOriginalAgain() = runTest {
        val loose = owned(951, 95, size = 11)
        val original = loose.archive.path
        val world = World(LibraryManifest(downloads = listOf(loose)))
        world.store.refuse = { saved -> "the disk is full".takeIf { saved.pending == null && saved.downloads.isEmpty() } }
        val library = with(world) { library() }

        assertEquals(ClearStage.Refused(LibraryProblem.ManifestSave("the disk is full")), clear(library, loose))
        assertEquals(ArchiveStep.Settled(RemovalOutcome.Removed), (world.manifest().pending as PendingChange.ArchiveRemoval).step)
        assertFalse(original in world.files.archivePaths())

        world.store.refuse = { null }
        val looked = mutableListOf<FaultPoint>()
        world.files.around = { looked += it }
        assertEquals(ClearStage.Cleared(removed = 0, freedBytes = 0, kept = 0, alreadyGone = 1), clear(library, loose))
        val atOriginal = listOf(FaultPoint.Look(original), FaultPoint.Link(original), FaultPoint.LinkMade(original), FaultPoint.Remove(original), FaultPoint.RemoveDone(original))
        assertEquals(emptyList(), looked.filter { it in atOriginal }, "only the anchor is cleaned up: $looked")
        assertEquals(emptySet(), world.anchors())
        assertNull(world.manifest().pending)
        assertEquals(emptyList(), world.manifest().downloads)
        world.assertSentinels()
    }

    @Test
    fun aClearStoppedBeforeItsOutcomeWasSavedIsSettledByTheNextRun() = runTest {
        val loose = owned(961, 96, size = 11)
        val world = World(LibraryManifest(downloads = listOf(loose)))
        world.store.refuse = { saved -> "the disk is full".takeIf { (saved.pending as? PendingChange.ArchiveRemoval)?.step is ArchiveStep.Settled } }
        val library = with(world) { library() }

        assertEquals(ClearStage.Refused(LibraryProblem.ManifestSave("the disk is full")), clear(library, loose))
        assertEquals(ArchiveStep.Releasing, (world.manifest().pending as PendingChange.ArchiveRemoval).step)
        assertFalse(loose.archive.path in world.files.archivePaths(), "it went before its outcome could be saved")

        world.store.refuse = { null }
        assertEquals(ClearStage.Cleared(removed = 0, freedBytes = 0, kept = 0, alreadyGone = 1), clear(library, loose))
        assertEquals(ArchiveStep.Settled(RemovalOutcome.Absent), world.journal().last().step, "recovery found it gone")
        assertEquals(emptySet(), world.anchors())
        assertNull(world.manifest().pending)
        assertEquals(emptyList(), world.manifest().downloads)
        world.assertSentinels()
    }

    @Test
    fun anInstallGameBananaHoldsSaysUntilWhenAndItsPageCanCancelIt() = runTest {
        val world = World()
        val asked = CompletableDeferred<FileListing>()
        world.listings = object : GameBananaClient by world.banana {
            override suspend fun files(submission: SubmissionRef): FileListing = asked.await()
        }
        val library = with(world) { library() }
        install(library, single)
        assertEquals(InstallStatus.Preparing, library.status(single.modId).first())

        val until = Instant.parse("2026-11-11T18:05:00Z")
        world.gameBananaBusy.value = until
        runCurrent()
        assertEquals(InstallStatus.Busy(until), library.status(single.modId).first(), "the page and the featured card say until when")

        assertTrue(library.cancel(single.modId))
        runCurrent()
        assertNull(library.state.value.operations[single.modId], "cancelled from where it was started, with nothing left")
        assertEquals(InstallStatus.NotInstalled, library.status(single.modId).first())
        assertEquals(emptyList(), world.downloader.requested)
    }

    @Test
    fun aDownloadThatStallsSaysItIsTryingAgainAndOneTheHostHoldsSaysUntilWhen() = runTest {
        val world = World()
        world.downloader.stalls += 11
        world.downloader.gate = CompletableDeferred()
        val library = with(world) { library() }
        install(library, single)
        assertEquals(InstallStatus.Downloading(45, stalled = true), library.status(single.modId).first())
        world.downloader.gate!!.complete(Unit)
        runCurrent()
        assertIs<InstallStatus.Installed>(library.status(single.modId).first())
    }

    @Test
    fun cancellingADownloadPutsAwayWhatItGot() = runTest {
        val world = World()
        world.downloader.gate = CompletableDeferred()
        val library = with(world) { library() }
        install(library, single)
        assertEquals(setOf(Triple(world.downloader.defaultFolder.path, single.modId, 11L)), world.downloader.unfinished.keys)
        assertEquals(listOf(single.modId), world.preferences.unfinishedInstalls().map { it.modId })

        assertTrue(library.cancel(single.modId))
        runCurrent()
        assertEquals(emptyMap(), world.downloader.unfinished, "no part file is left")
        assertEquals(emptyList(), world.preferences.unfinishedInstalls())
    }

    @Test
    fun tryAgainAfterADownloadFailedOnTheConnectionGoesOnFromWhatItGotAndDismissPutsThatAway() = runTest {
        val world = World()
        world.downloader.results[11] = DownloadResult.Failed(DownloadProblem.Network("reset", resumable = true))
        val library = with(world) { library() }
        install(library, single)
        assertIs<OperationStage.Failed>(library.state.value.operations[single.modId]?.stage)
        assertEquals(setOf(11L), world.downloader.unfinished.keys.map { it.third }.toSet(), "kept for Try again")

        assertTrue(library.retry(single.modId))
        runCurrent()
        assertEquals(listOf(11L to 0L, 11L to 5L), world.downloader.resumedFrom, "the second try went on from the half the first one got")

        library.dismiss(single.modId)
        runCurrent()
        assertEquals(emptyMap(), world.downloader.unfinished)
        assertEquals(emptyList(), world.preferences.unfinishedInstalls())
    }

    @Test
    fun installingAModAgainRightAfterDismissingItsFailureKeepsWhatTheNewInstallDownloads() = runTest {
        val world = World()
        world.downloader.results[11] = DownloadResult.Failed(DownloadProblem.Network("reset", resumable = true))
        val library = with(world) { library() }
        install(library, single)
        assertIs<OperationStage.Failed>(library.state.value.operations[single.modId]?.stage)

        val discarding = CompletableDeferred<Unit>()
        world.downloader.discarding = discarding
        world.downloader.results.remove(11)
        world.downloader.gate = CompletableDeferred()
        library.dismiss(single.modId)
        install(library, single)
        discarding.complete(Unit)
        runCurrent()
        val part = Triple(world.downloader.defaultFolder.path, single.modId, 11L)
        assertEquals(setOf(part), world.downloader.unfinished.keys, "the new install's part stays")
        assertEquals(listOf(single.modId), world.preferences.unfinishedInstalls().map { it.modId }, "and so does what lets it resume")

        world.downloader.gate!!.complete(Unit)
        runCurrent()
        assertEquals(single.modId, world.manifest().records.single().modId)
        assertEquals(emptyMap(), world.downloader.unfinished)
        assertEquals(emptyList(), world.preferences.unfinishedInstalls())
    }

    @Test
    fun aDownloadUnderWayWhenHideoutStopsGoesOnByItselfAtTheNextStartAndLeavesNothingBehind() = runTest {
        val world = World()
        world.downloader.gate = CompletableDeferred()
        val first = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val before = with(world) { library(first) }
        install(before, single)
        assertIs<OperationStage.Downloading>(before.state.value.operations[single.modId]?.stage)
        first.cancel()
        runCurrent()
        assertEquals(
            listOf(Triple(single.modId, world.downloader.defaultFolder.path, listOf(11L))),
            world.preferences.unfinishedInstalls().map { Triple(it.modId, it.folder, it.fileIds) },
            "quitting keeps it",
        )

        world.downloader.gate = null
        val after = with(world) { library() }
        runCurrent()
        assertEquals(listOf(11L to 0L, 11L to 5L), world.downloader.resumedFrom, "it went on from what the first run got")
        assertEquals(single.modId, world.manifest().records.single().modId)
        assertEquals(emptyMap(), world.downloader.unfinished, "no part file is left")
        assertEquals(emptyList(), world.preferences.unfinishedInstalls())
        assertNull(after.state.value.operations[single.modId])
    }

    @Test
    fun anUnfinishedInstallOfAModThatCannotBeResumedIsPutAwayAtTheNextStart() = runTest {
        val world = World()
        world.downloader.unfinished[Triple(folderA, single.modId, 11L)] = 5
        world.preferences.keepUnfinished(UnfinishedInstall(single.modId, folderA, listOf(11L), installId = "gone"))
        with(world) { library() }
        runCurrent()
        assertEquals(emptyMap(), world.downloader.unfinished)
        assertEquals(emptyList(), world.preferences.unfinishedInstalls())
        assertEquals(emptyList(), world.downloader.requested)
    }
    @Test
    fun anUninstallUndoneWhileItsReinstallDownloadsLeavesTheDownloadGoingAndTheModAsItWas() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        val library = with(world) { library() }
        install(library, single)
        val record = world.manifest().records.single()
        world.files.remove(root, record.files.single().relativePath(record))
        world.downloader.gate = CompletableDeferred()
        library.reinstall(record.installId)
        runCurrent()
        assertIs<OperationStage.Downloading>(library.state.value.operations[single.modId]?.stage)

        library.uninstall(setOf(record.installId))
        runCurrent()
        assertEquals(setOf(record.installId), library.state.value.leaving)
        assertIs<MutationResult.Done<Unit>>(library.undo(assertNotNull(library.state.value.undo)))
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(emptySet(), library.state.value.leaving)
        assertIs<OperationStage.Downloading>(library.state.value.operations[single.modId]?.stage, "the reinstall goes on")
        assertEquals(listOf(single.modId), world.preferences.unfinishedInstalls().map { it.modId }, "and so does what lets it resume")
        assertEquals(1, world.downloader.unfinished.size)

        world.downloader.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf(record.installId to record.enabled), world.manifest().records.map { it.installId to it.enabled })
        assertEquals(emptyMap(), world.downloader.unfinished)
        assertEquals(emptyList(), world.preferences.unfinishedInstalls())
        world.assertSentinels()
    }

    @Test
    fun anUninstallSavedLateBecauseTheLibraryWasBusyStillOffersAWorkingUndo() = runTest {
        val world = World()
        val library = with(world) { library() }
        install(library, single)
        val record = world.manifest().records.single()
        val gate = CompletableDeferred<Unit>()
        world.processes.gate = gate
        val busy = launch(start = CoroutineStart.UNDISPATCHED) { library.reapply() }
        runCurrent()

        library.uninstall(setOf(record.installId))
        advanceTimeBy(10_000)
        runCurrent()
        assertNull(library.state.value.undo, "nothing is saved while the library is held")
        assertEquals(listOf(record.installId), world.manifest().records.map { it.installId })

        gate.complete(Unit)
        world.processes.gate = null
        runCurrent()
        busy.join()
        assertEquals(listOf(record.installId), world.manifest().records.map { it.installId }, "the files stay while Undo is offered")
        assertIs<MutationResult.Done<Unit>>(library.undo(assertNotNull(library.state.value.undo)))
        advanceTimeBy(7_000)
        runCurrent()
        assertEquals(emptySet(), library.state.value.leaving)
        assertEquals(listOf(record.installId to true), world.manifest().records.map { it.installId to it.enabled }, "Undo brought it back")
        world.assertSentinels()
    }

    @Test
    fun anUninstallWhoseWindowPassesStopsItsReinstallAndLeavesNoPartRecordOrDownloadBehind() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        val library = with(world) { library() }
        install(library, single)
        val record = world.manifest().records.single()
        world.files.remove(root, record.files.single().relativePath(record))
        world.downloader.gate = CompletableDeferred()
        library.reinstall(record.installId)
        runCurrent()
        assertEquals(1, world.downloader.unfinished.size)

        library.uninstall(setOf(record.installId))
        advanceTimeBy(7_000)
        runCurrent()
        assertNull(library.state.value.operations[single.modId], "the reinstall stopped with the mod")
        assertEquals(emptyList(), world.manifest().records)
        assertEquals(emptyMap(), world.downloader.unfinished, "no part file is left")
        assertEquals(emptyList(), world.preferences.unfinishedInstalls(), "nor anything that would resume it")
        assertEquals(setOf(strayPath), world.files.archivePaths(), "nor its download")
        assertEquals(setOf("pak01_dir.vpk"), world.files.addonsFiles(root).keys)
        world.assertSentinels()
    }

    @Test
    fun theStartsTidyAndAnUninstallNeverTouchAPartAnUnfinishedInstallNames() = runTest {
        val world = World()
        world.preferences.setKeepDownloads(false)
        val first = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val before = with(world) { library(first) }
        install(before, next)
        world.downloader.gate = CompletableDeferred()
        install(before, single)
        first.cancel()
        runCurrent()
        val part = Triple(world.downloader.defaultFolder.path, single.modId, 11L)
        assertEquals(setOf(part), world.downloader.unfinished.keys)

        val held = CompletableDeferred<Unit>()
        world.downloader.gate = null
        world.downloader.gates[11] = held
        val after = with(world) { library() }
        runCurrent()
        val other = world.manifest().records.single { it.modId == next.modId }
        after.uninstall(assertNotNull(after.uninstallConfirmation(other.installId)), deleteDownload = true)
        runCurrent()
        assertEquals(emptyList(), world.manifest().records)
        assertEquals(setOf(part), world.downloader.unfinished.keys, "the part the resumed install goes on from stays")
        assertEquals(listOf(single.modId), world.preferences.unfinishedInstalls().map { it.modId })

        held.complete(Unit)
        runCurrent()
        assertEquals(listOf(11L to 0L, 11L to 5L), world.downloader.resumedFrom.filter { it.first == 11L })
        assertEquals(listOf(single.modId), world.manifest().records.map { it.modId })
        assertEquals(emptyMap(), world.downloader.unfinished)
    }
}
