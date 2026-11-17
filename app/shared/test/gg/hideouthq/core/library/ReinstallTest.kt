package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.LocalArchiveSnapshot
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.LocalImport
import gg.hideouthq.core.data.LocalImportResult
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ReinstallTest {
    private val root = GameRoot("/games/Deadlock")
    private val lilith = SubmissionRef(SubmissionKind.Mod, 551_201)

    private val dir = FakeVpk.directory(embeddedBytes = 4, chunkReads = mapOf(0 to 30L, 1 to 20L), seed = 1)
    private val chunk0 = ByteArray(30) { 7 }
    private val chunk1 = ByteArray(20) { 8 }
    private val cabezas = FakeVpk.directory(seed = 3)

    private val edited = FakeVpk.directory(seed = 9)

    private val defaultEntries = listOf("Default/lilith_dir.vpk" to dir, "Default/lilith_000.vpk" to chunk0, "Default/lilith_001.vpk" to chunk1)
    private val cabezasEntries = listOf("Cabezas/pak01_dir.vpk" to cabezas)

    private fun md5(fileId: Long) = fileId.toString().padStart(32, '0')

    private fun identity(fileId: Long) = SubmissionIdentity(SubmissionKind.Mod, lilith.id, fileId, md5(fileId))

    private fun archive(fileId: Long, name: String, entries: List<Pair<String, ByteArray>>) =
        ArchiveIdentity("/data/hideout/downloads/mod-551201/$fileId/$name", 1_000, sha256Hex(entries.flatMap { it.second.asIterable() }.toByteArray()))

    private val defaultArchive = archive(101, "lilith_hell_warden.zip", defaultEntries)
    private val cabezasArchive = archive(103, "lilith_werewolf_cabezas.zip", cabezasEntries)

    private fun remote(id: Long, name: String, md5: String? = md5(id)) = RemoteFile(id, name, 1_000, md5, null, 10, 1, "https://gamebanana.com/dl/$id")

    private val listed = FileListing.Available("Lilith, the Hell Warden", listOf(remote(101, "lilith_hell_warden.zip"), remote(103, "lilith_werewolf_cabezas.zip")))

    private fun pak(number: Int, part: String) = "pak${number.toString().padStart(2, '0')}_$part.vpk"

    private fun lilith(enabled: Boolean = true, first: Int = 1) = ManifestRecord(
        "lilith", root, "Lilith, the Hell Warden", placedAt(first.takeIf { enabled }),
        listOf(
            InstalledSet(
                InstalledSource.Remote(identity(101)), "lilith_hell_warden.zip", "Default", SetOrigin.Downloaded(defaultArchive), VersionPosition(1, 2), "Default/lilith_dir.vpk",
                listOf(owned(VpkPart.Directory, dir), owned(VpkPart.Chunk(0), chunk0), owned(VpkPart.Chunk(1), chunk1)),
            ),
            InstalledSet(
                InstalledSource.Remote(identity(103)), "lilith_werewolf_cabezas.zip", "Cabezas", SetOrigin.Downloaded(cabezasArchive), VersionPosition(2, 2), "Cabezas/pak01_dir.vpk",
                listOf(owned(VpkPart.Directory, cabezas)),
            ),
        ),
    )

    private fun neighbour(id: String, number: Int) = ManifestRecord(
        id, root, "Neighbour $id", placedAt(number),
        listOf(InstalledSet(InstalledSource.Local(id, "$id.vpk"), null, null, SetOrigin.Imported(FileImage(1, "a".repeat(64))), null, "$id.vpk", listOf(owned(VpkPart.Directory, neighbourBytes(number))))),
    )

    private fun neighbourBytes(number: Int) = FakeVpk.directory(seed = 40 + number)

    private inner class World(
        val record: ManifestRecord = lilith(),
        private val others: List<ManifestRecord> = emptyList(),
        book: ProfileBook = ProfileBook.main(listOf(record) + others),
        originals: List<RetainedOriginal> = emptyList(),
    ) {
        val folder: SetFolder = record.setFolder()
        val contents = record.files.map { it.name }.zip(listOf(dir, chunk0, chunk1, cabezas)).toMap()
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo)
            contents.forEach { (name, bytes) -> putBytes(root, "${folder.path}/$name", bytes) }
            others.forEach { other -> other.files.forEach { putBytes(root, it.relativePath(other), neighbourBytes(other.number)) } }
            putArchive(defaultArchive.path, defaultEntries, FileImage(defaultArchive.sizeBytes, defaultArchive.sha256))
            putArchive(cabezasArchive.path, cabezasEntries, FileImage(cabezasArchive.sizeBytes, cabezasArchive.sha256))
        }
        val store = InMemoryLibraryStore(
            LibraryManifest(
                records = listOf(record) + others,
                downloads = listOf(OwnedDownload(identity(101), defaultArchive), OwnedDownload(identity(103), cabezasArchive)),
                profiles = book,
                originals = originals,
            ),
        )
        val banana = FakeGameBanana(mapOf(lilith to listed))
        val downloader = FakeDownloader(files).apply {
            serve(101, defaultEntries)
            serve(103, cabezasEntries)
        }
        val processes = FakeProcesses()
        var local: LocalImport = LocalImport.Unavailable
        private var nextId = 0

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, files,
                InstallPorts(banana, downloader, files, files, processes, localImport = local, backups = files.backups), scope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
            ),
        )

        fun manifest() = assertNotNull(store.manifest)

        fun stored() = manifest().records.single { it.installId == record.installId }

        fun path(name: String) = "${folder.path}/$name"

        fun inFolder(): Map<String, FileImage> = files.filesIn(root, folder).filterKeys { name -> others.none { other -> other.files.any { it.name == name } } }

        fun identity(name: String) = files.identity(root, path(name))

        fun default(part: VpkPart) = record.filesOf(record.sets[0]).single { it.part == part }.name

        fun damage(damage: FilesDamage) = when (damage) {
            FilesDamage.Changed -> files.rewrite(root, path(default(VpkPart.Directory)), edited)
            FilesDamage.Missing -> files.remove(root, path(default(VpkPart.Chunk(0))))
        }

        fun edited(bytes: ByteArray, part: VpkPart = VpkPart.Directory) = record.copy(sets = listOf(record.sets[0].copy(edits = mapOf(part to Sha256.image(bytes))), record.sets[1]))

        fun noted(damage: FilesDamage) = if (damage == FilesDamage.Changed) edited(this@ReinstallTest.edited) else record

        fun problem(damage: FilesDamage): LibraryProblem = when (damage) {
            FilesDamage.Changed -> LibraryProblem.FileChanged(path(default(VpkPart.Directory)))
            FilesDamage.Missing -> LibraryProblem.FileMissing(path(default(VpkPart.Chunk(0))))
        }

        fun TestScope.seen() {
            val once = crashableScope()
            library(once)
            once.cancel()
        }

        fun aside(name: String) = "${InMemoryGameFiles.Edits}/${record.title} ${LocalDate.now()}/$name"

        fun assertOriginals() {
            assertEquals(contents.mapValues { Sha256.image(it.value) }, inFolder())
        }
    }

    private fun ModLibrary.ready(): LibraryStatus.Ready = assertIs<LibraryStatus.Ready>(state.value.library)

    private fun ModLibrary.failure(modId: String = lilith.modId): LibraryProblem? = (state.value.operations[modId]?.stage as? OperationStage.Failed)?.problem

    private fun TestScope.reinstall(library: ModLibrary, installId: String = "lilith", file: LocalFile? = null) {
        library.reinstall(installId, file)
        runCurrent()
    }

    @Test
    fun aMissingFileComesBackFromTheKeptDownloadWithNothingDownloaded() = runTest {
        val world = World()
        world.damage(FilesDamage.Missing)
        val cabezasFile = world.identity(pak(2, "dir"))
        val library = with(world) { library() }
        assertEquals(mapOf("lilith" to FilesDamage.Missing), library.ready().damaged)

        reinstall(library)
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(world.record, world.stored(), "the mod is exactly as it was installed")
        world.assertOriginals()
        assertEquals(emptyList(), world.downloader.requested)
        assertEquals(0, world.banana.calls, "GameBanana is not asked")
        assertEquals(cabezasFile, world.identity(pak(2, "dir")), "the intact set keeps its very file")
        assertTrue(world.files.linkedPaths.none { pak(2, "dir") in it }, "the intact set is not touched")
        assertEquals(emptyMap(), library.ready().damaged)
        assertNull(world.manifest().pending)
    }

    @Test
    fun theOriginalOfAnEditedFileIsPutBackAndTheEditMovesToTheEditsFolderWithNothingLeftBeside() = runTest {
        val world = World()
        world.damage(FilesDamage.Changed)
        val edit = world.identity(pak(1, "dir"))
        val library = with(world) { library() }
        assertEquals(mapOf("lilith" to FilesDamage.Changed), library.ready().damaged)
        assertEquals(world.edited(edited), world.stored(), "the edit is noted")
        assertEquals(emptySet(), library.ready().stored, "edited in place, the backups hold nothing of the original")

        reinstall(library)
        assertEquals(world.record, world.stored())
        world.assertOriginals()
        assertTrue(world.files.everyFile(root).keys.none { TemporaryInfix in it }, "no anchor or temporary stays")
        assertEquals(edit, world.files.identity(root, world.aside(pak(1, "dir"))), "the edit is the very file, moved aside")
        assertContentEquals(edited, world.files.bytes(root, world.aside(pak(1, "dir"))))
        assertEquals(emptyList(), world.downloader.requested)
        assertEquals(emptyMap(), library.ready().damaged)
        assertEquals(emptyList(), library.state.value.backups, "nothing is backed up for it")
    }

    @Test
    fun anEditWrittenAsANewFileIsRestoredFromTheBackupsAndWhatIsThereNowMovesAside() = runTest {
        for (again in listOf(false, true)) {
            val world = World()
            val original = world.identity(pak(1, "dir"))
            with(world) { seen() }
            world.files.putBytes(root, world.path(pak(1, "dir")), edited)
            val library = with(world) { library() }
            assertEquals(setOf("lilith"), library.ready().stored, "again=$again: the backups still hold the original")
            val newest = if (again) FakeVpk.directory(seed = 12).also { world.files.putBytes(root, world.path(pak(1, "dir")), it) } else edited
            val edit = world.identity(pak(1, "dir"))

            reinstall(library)
            assertEquals(world.record, world.stored(), "again=$again")
            world.assertOriginals()
            assertEquals(original, world.identity(pak(1, "dir")), "again=$again: the original comes back as the very file the backups kept, nothing unpacked")
            assertEquals(edit, world.files.identity(root, world.aside(pak(1, "dir"))), "again=$again")
            assertContentEquals(newest, world.files.bytes(root, world.aside(pak(1, "dir"))), "again=$again")
            assertEquals(emptyList(), world.downloader.requested, "again=$again")
            assertTrue(world.files.everyFile(root).keys.none { TemporaryInfix in it }, "again=$again")

            library.backUp()
            runCurrent()
            assertContentEquals(newest, world.files.bytes(root, world.aside(pak(1, "dir"))), "again=$again: pruning the backups never touches it")
        }
    }

    @Test
    fun aModWithNoSourceToGetItsFilesFromIsRestoredFromTheBackups() = runTest {
        val adopted = neighbour("dmm", 5).let { it.copy(sets = it.sets.map { set -> set.copy(origin = SetOrigin.Adopted(MigrationId("dmm-1"))) }) }
        assertNull(adopted.reinstallFrom, "taken over from another manager, nothing says where its files came from")
        val world = World(others = listOf(adopted))
        val path = adopted.files.single().relativePath(adopted)
        val original = world.files.identity(root, path)
        with(world) { seen() }
        val other = FakeVpk.directory(seed = 13)
        world.files.putBytes(root, path, other)
        val library = with(world) { library() }
        assertEquals(setOf("dmm"), library.ready().stored)

        reinstall(library, "dmm")
        assertEquals(adopted, world.manifest().records.single { it.installId == "dmm" })
        assertEquals(original, world.files.identity(root, path))
        assertContentEquals(other, world.files.bytes(root, "${InMemoryGameFiles.Edits}/Neighbour dmm ${LocalDate.now()}/pak05_dir.vpk"))
        assertEquals(emptyMap(), library.ready().damaged)
    }

    @Test
    fun aKeptDownloadWhoseBytesChangedIsDownloadedAgainUnderTheSameFileId() = runTest {
        val world = World()
        world.damage(FilesDamage.Missing)
        world.files.rewriteArchive(defaultArchive.path, FileImage(1_000, "f".repeat(64)))
        val library = with(world) { library() }

        reinstall(library)
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(listOf(101L), world.downloader.requested)
        val set = world.stored().sets.first()
        val download = assertNotNull(set.download)
        assertNotEquals(defaultArchive, download)
        assertEquals(world.record.sets.first().copy(origin = SetOrigin.Downloaded(download)), set, "the set names the download its files came from now")
        assertEquals(world.record.sets[1], world.stored().sets[1])
        assertTrue(world.manifest().downloads.any { it.archive == download && it.source == identity(101) })
        world.assertOriginals()
    }

    @Test
    fun aFileGameBananaNoLongerOffersFailsAndTouchesNothing() = runTest {
        val others = listOf(
            FileListing.Available("Lilith, the Hell Warden", listOf(remote(103, "lilith_werewolf_cabezas.zip"))),
            FileListing.Available("Lilith, the Hell Warden", listOf(remote(101, "lilith_hell_warden.zip", md5 = "e".repeat(32)), remote(103, "lilith_werewolf_cabezas.zip"))),
        )
        for (listing in others) {
            val world = World()
            world.damage(FilesDamage.Missing)
            world.files.rewriteArchive(defaultArchive.path, FileImage(1_000, "f".repeat(64)))
            world.banana.listings = mapOf(lilith to listing)
            val library = with(world) { library() }
            val before = world.manifest()
            val folder = world.inFolder()

            reinstall(library)
            assertEquals(LibraryProblem.FileNotOffered, library.failure(), "$listing")
            assertEquals(before, world.manifest())
            assertEquals(folder, world.inFolder())
            assertEquals(emptyList(), world.downloader.requested)
            assertEquals(emptyList(), world.files.linkedPaths)
            assertEquals(emptyList(), world.files.removedPaths)
        }
    }

    @Test
    fun aLocalModRefusesAnotherFileAndComesBackFromTheOneItWasInstalledFrom() = runTest {
        val skin = FakeVpk.directory(seed = 21)
        val right = ArchiveIdentity("/imports/right.zip", 500, "a".repeat(64))
        val wrong = ArchiveIdentity("/imports/wrong.zip", 500, "b".repeat(64))
        val mine = ManifestRecord(
            "mine", root, "Mine", placedAt(4),
            listOf(InstalledSet(InstalledSource.Local("mine", "skin.zip"), null, null, SetOrigin.Imported(FileImage(500, "a".repeat(64))), null, "skin.vpk", listOf(owned(VpkPart.Directory, skin)))),
        )
        val world = World(others = listOf(mine))
        world.files.putBytes(root, mine.files.single().relativePath(mine), skin)
        world.files.putArchive(right.path, listOf("skin.vpk" to skin), FileImage(right.sizeBytes, right.sha256))
        world.files.putArchive(wrong.path, listOf("skin.vpk" to FakeVpk.directory(seed = 22)), FileImage(wrong.sizeBytes, wrong.sha256))
        val released = mutableListOf<ArchiveIdentity>()
        world.local = object : LocalImport {
            override suspend fun snapshot(file: LocalFile) = LocalImportResult.Ready(LocalArchiveSnapshot(if (file.name == "skin.zip") right else wrong, file.name))
            override suspend fun release(snapshot: LocalArchiveSnapshot) {
                released += snapshot.archive
            }
        }
        val path = mine.files.single().relativePath(mine)
        world.files.rewrite(root, path, edited)
        val library = with(world) { library() }
        assertEquals(mapOf("mine" to FilesDamage.Changed), library.ready().damaged)
        assertEquals(ReinstallFrom.File, mine.reinstallFrom)

        reinstall(library, "mine")
        assertNull(library.state.value.operations["local-mine"], "a local mod is reinstalled only from a file the user picks")
        reinstall(library, "mine", LocalFile("/elsewhere/other.zip", "other.zip", 500))
        assertEquals(LibraryProblem.NotTheInstalledFile, library.failure("local-mine"))
        assertEquals(Sha256.image(edited), world.files.addonsFiles(root)[pak(4, "dir")])
        assertEquals(emptyList(), world.files.linkedPaths)

        reinstall(library, "mine", LocalFile("/picked/skin.zip", "skin.zip", 500))
        assertNull(library.state.value.operations["local-mine"])
        assertEquals(mine, world.manifest().records.single { it.installId == "mine" })
        assertEquals(Sha256.image(skin), world.files.addonsFiles(root)[pak(4, "dir")])
        assertEquals(listOf(wrong, right), released, "every snapshot is let go")
        assertEquals(emptyMap(), library.ready().damaged)
    }

    @Test
    fun anOnModWaitsWhileDeadlockMayRunAndAnOffOneIsReplacedInItsOwnFolder() = runTest {
        for (enabled in listOf(true, false)) {
            val world = World(lilith(enabled))
            world.damage(FilesDamage.Missing)
            world.processes.scan = ProcessScan.Seen(game = true)
            val library = with(world) { library() }

            reinstall(library)
            if (enabled) {
                assertEquals(OperationStage.WaitingForGame(canCancel = true), library.state.value.operations[lilith.modId]?.stage)
                assertNull(world.inFolder()[pak(1, "000")])
                assertEquals(emptyList(), world.files.linkedPaths, "nothing the game loads changes while it may run")
                world.processes.scan = ProcessScan.Seen()
                advanceTimeBy(testWatchTiming.processPoll * 2)
                runCurrent()
            }
            assertNull(library.state.value.operations[lilith.modId], "enabled=$enabled")
            assertEquals(world.record, world.stored(), "enabled=$enabled")
            world.assertOriginals()
        }
    }

    @Test
    fun aCustomGamesHoldMakesAnOnModWaitWithoutRetryingAndItsReleaseLetsItGoOn() = runTest {
        val world = World(lilith(enabled = true))
        world.damage(FilesDamage.Missing)
        val library = with(world) { library() }
        assertIs<SessionAdmission.Held>(library.admitSession())

        reinstall(library)
        assertEquals(OperationStage.WaitingForGame(canCancel = true), library.state.value.operations[lilith.modId]?.stage)
        assertEquals(GameActivity.Closed, library.state.value.deadlock)
        assertEquals(emptyList(), world.files.linkedPaths, "nothing the game loads changes during the hold")

        assertNull(library.releaseSession())
        runCurrent()
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(world.record, world.stored())
        world.assertOriginals()
    }

    @Test
    fun anOffModIsReplacedInItsOwnFolderDuringACustomGamesHold() = runTest {
        val world = World(lilith(enabled = false))
        world.damage(FilesDamage.Missing)
        val library = with(world) { library() }
        assertIs<SessionAdmission.Held>(library.admitSession())

        reinstall(library)
        assertNull(library.state.value.operations[lilith.modId])
        assertEquals(world.record, world.stored())
        world.assertOriginals()
    }

    @Test
    fun theModKeepsItsInstallIdProfilesPlaceAndNumbers() = runTest {
        val record = lilith(first = 2)
        val others = listOf(neighbour("a", 1), neighbour("b", 4))
        val main = ProfileBook.main(listOf(record) + others)
        val book = main.copy(profiles = main.profiles + Profile(ProfileId("ranked"), "Ranked", ProfileColour.Blue, setOf("lilith", "b"), main.activeProfile.orders))
        val world = World(record, others, book)
        world.damage(FilesDamage.Changed)
        val library = with(world) { library() }
        val order = onLoadOrder(world.manifest().records)

        reinstall(library)
        assertEquals(listOf(others[0], record, others[1]), world.manifest().records.sortedBy { it.numbers.first() })
        assertEquals(book, world.manifest().profiles)
        assertEquals(order, onLoadOrder(world.manifest().records))
        assertEquals(listOf("a", "lilith", "b"), library.state.value.orderView?.sets)
        assertNull(world.manifest().savedChoices)
        world.assertOriginals()
    }

    @Test
    fun aCrashAtAnyStepLeavesTheUsersFilesOrTheOriginalsWhole() = runTest {
        for (damage in FilesDamage.entries) {
            val dry = World()
            dry.damage(damage)
            val users = dry.inFolder()
            val first = with(dry) { library() }
            val points = mutableListOf<FaultPoint>()
            val saves = dry.store.saved.size
            dry.files.around = { points += it }
            reinstall(first)
            dry.assertOriginals()
            val reinstallSaves = dry.store.saved.size - saves
            assertTrue(points.size > 10 && reinstallSaves >= 5, "the trace has $points and $reinstallSaves saves")

            val cuts = points.indices.map { index -> "step $index ${points[index]}" to { world: World -> var seen = 0; world.files.around = { if (seen++ == index) throw SimulatedCrash(it) } } } +
                (0 until reinstallSaves).flatMap { index ->
                    listOf(
                        "before save $index" to { world: World -> var seen = 0; world.store.refuse = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig); null } },
                        "after save $index" to { world: World -> var seen = 0; world.store.afterStore = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig) } },
                    )
                }
            for ((name, cut) in cuts) {
                val case = "$damage, a crash $name"
                val world = World()
                world.damage(damage)
                val doomed = with(world) { crashableScope() }
                val crashed = with(world) { library(doomed) }
                cut(world)
                reinstall(crashed)
                doomed.cancel()
                world.files.around = null
                world.store.refuse = { null }
                world.store.afterStore = {}

                val restarted = with(world) { library() }
                assertNull(world.manifest().pending, case)
                assertNull(restarted.ready().interrupted, case)
                val now = world.inFolder()
                val own = now.filterKeys { TemporaryInfix !in it }
                val originals = world.contents.mapValues { Sha256.image(it.value) }
                assertTrue(own == originals || own == users, "$case: the mod's own files or the user's, whole, got $own")
                assertEquals(if (own == originals) world.record else world.noted(damage), world.stored(), case)
                if (damage == FilesDamage.Changed && own == originals) assertContentEquals(edited, world.files.bytes(root, world.aside(pak(1, "dir"))), "$case: the edit is set aside")
                assertTrue(now.keys.none { TemporaryInfix in it }, "$case: no anchor or copy is left, got ${now.keys}")
            }
        }
    }

    @Test
    fun aFileThatChangesAgainBeforeItIsKeptBesideItselfUndoesTheReinstall() = runTest {
        val world = World()
        world.damage(FilesDamage.Changed)
        val again = FakeVpk.directory(seed = 10)
        val path = world.path(pak(1, "dir"))
        world.files.around = { point ->
            if (point is FaultPoint.Link && point.path.startsWith("$path$TemporaryInfix")) {
                world.files.around = null
                world.files.rewrite(root, path, again)
            }
        }
        val library = with(world) { library() }

        reinstall(library)
        assertEquals(LibraryProblem.FileChanged(path), library.failure())
        assertNull(world.manifest().pending)
        assertEquals(world.edited(again), world.stored(), "the newest edit is what is noted")
        assertEquals(world.contents.mapValues { Sha256.image(it.value) } + (pak(1, "dir") to Sha256.image(again)), world.inFolder().filterKeys { TemporaryInfix !in it })
        assertTrue(world.inFolder().keys.none { TemporaryInfix in it }, "no anchor or copy stays")
        assertNull(library.ready().interrupted)
    }

    @Test
    fun aMissingNameSomethingElseTakesMeanwhileIsLeftToItAndTheReinstallUndone() = runTest {
        val world = World()
        world.damage(FilesDamage.Missing)
        val stranger = FakeVpk.directory(seed = 11)
        val path = world.path(pak(1, "000"))
        val library = with(world) { library() }
        var strangerFile: Int? = null
        world.files.around = { point ->
            if (point == FaultPoint.Link(path)) {
                world.files.around = null
                world.files.putBytes(root, path, stranger)
                strangerFile = world.files.identity(root, path)
            }
        }

        reinstall(library)
        assertEquals(LibraryProblem.NameTaken(path), library.failure())
        assertNull(world.manifest().pending)
        assertEquals(world.edited(stranger, VpkPart.Chunk(0)), world.stored(), "what took the name is the user's to decide on")
        assertEquals(strangerFile, world.files.identity(root, path), "the stranger is the very file it put there")
        val own = world.inFolder().filterKeys { TemporaryInfix !in it }
        assertEquals(world.contents.mapValues { Sha256.image(it.value) } + (pak(1, "000") to Sha256.image(stranger)), own, "every other file is as it was")
        assertNull(library.ready().interrupted)
    }

    @Test
    fun changingFilesOrUpdatingStillRefusesAFileThatIsMissingOrChanged() = runTest {
        val werewolf = FakeVpk.directory(seed = 2)
        for (damage in FilesDamage.entries) {
            val world = World()
            world.damage(damage)
            world.banana.listings = mapOf(lilith to FileListing.Available("Lilith, the Hell Warden", listed.files + remote(102, "lilith_werewolf_transform.zip")))
            world.downloader.serve(102, listOf("werewolf.vpk" to werewolf))
            val library = with(world) { library() }
            val folder = world.inFolder()

            assertTrue(changeFilesTo(library, "lilith", 103, 102))
            assertEquals(world.problem(damage), library.failure(), "$damage: Change files")
            assertEquals(world.noted(damage), world.stored())
            assertEquals(folder, world.inFolder())

            library.dismiss(lilith.modId)
            val newer = identity(101).copy(fileMd5 = "e".repeat(32))
            world.banana.listings = mapOf(lilith to FileListing.Available("Lilith, the Hell Warden", listOf(remote(101, "lilith_hell_warden.zip", md5 = newer.fileMd5), remote(103, "lilith_werewolf_cabezas.zip"))))
            val plan = UpdatePlan(root, "lilith", world.record.provenance, listOf(Replacement(identity(101), newer)))
            assertTrue(library.update(plan))
            runCurrent()
            assertEquals(world.problem(damage), library.failure(), "$damage: Update")
            assertEquals(world.noted(damage), world.stored())
            assertEquals(folder, world.inFolder())
            assertEquals(emptyList(), world.files.linkedPaths)
            assertNull(world.manifest().pending)
        }
    }

    @Test
    fun keepTakesTheChangedBytesAsTheModsWithoutMovingAFileAndTheModStillTurns() = runTest {
        val world = World()
        world.damage(FilesDamage.Changed)
        val identities = world.inFolder().keys.associateWith { world.identity(it) }
        val library = with(world) { library() }

        library.keepEdits("lilith")
        runCurrent()
        val directory = owned(VpkPart.Directory, edited)
        assertEquals(world.record.copy(sets = listOf(world.record.sets[0].copy(files = listOf(directory) + world.record.sets[0].files.drop(1)), world.record.sets[1])), world.stored())
        assertEquals(emptyList(), world.files.linkedPaths)
        assertEquals(emptyList(), world.files.removedPaths)
        assertEquals(identities, world.inFolder().keys.associateWith { world.identity(it) }, "every file is the very file it was")
        assertEquals(emptyMap(), library.ready().damaged)

        library.setEnabled("lilith", false)
        runCurrent()
        assertEquals(ChoiceSync.Settled, library.state.value.choiceSync)
        assertEquals(false, world.stored().enabled)
        assertEquals(Sha256.image(edited), world.files.filesIn(root, SetFolder.Off(lilith))[world.stored().files.first().name])
    }

    @Test
    fun keepIsRefusedWhileAFileIsMissingAndSaysWhy() = runTest {
        val missing = World()
        missing.damage(FilesDamage.Missing)
        val library = with(missing) { library() }
        val before = missing.manifest()
        library.keepEdits("lilith")
        runCurrent()
        assertEquals(before, missing.manifest())
        assertEquals(mapOf("lilith" to FilesDamage.Missing), library.ready().damaged)
        assertEquals(LibraryProblem.FileMissing(missing.path(pak(1, "000"))), library.state.value.choiceProblem, "the notice says why")
    }

    @Test
    fun aSetRebuiltWithAChunkLessOrMoreIsKeptAsItsFilesAreAndTheirMoveCarriesThemAll() = runTest {
        for (more in listOf(false, true)) {
            val world = World()
            val rebuilt = FakeVpk.directory(seed = 11)
            val extra = ByteArray(12) { 4 }
            world.files.putBytes(root, world.path(pak(1, "dir")), rebuilt)
            if (more) world.files.putBytes(root, world.path(pak(1, "002")), extra) else world.files.remove(root, world.path(pak(1, "001")))
            val library = with(world) { library() }
            assertEquals(mapOf("lilith" to FilesDamage.Changed), library.ready().damaged, "more=$more: a chunk gone with the directory edited is part of the edit")

            library.keepEdits("lilith")
            runCurrent()
            val chunks = if (more) listOf(owned(VpkPart.Chunk(0), chunk0), owned(VpkPart.Chunk(1), chunk1), owned(VpkPart.Chunk(2), extra)) else listOf(owned(VpkPart.Chunk(0), chunk0))
            assertEquals(listOf(owned(VpkPart.Directory, rebuilt)) + chunks, world.stored().sets[0].files, "more=$more: the set's files are the ones found")
            assertEquals(emptyMap(), library.ready().damaged, "more=$more")

            library.setEnabled("lilith", false)
            runCurrent()
            val off = world.files.filesIn(root, SetFolder.Off(lilith))
            assertEquals(world.stored().files.map { it.name to it.image }.toMap(), off, "more=$more: every file moved")
            assertTrue(world.files.addonsFiles(root).keys.none { it.startsWith("pak01_") }, "more=$more: nothing of the set is left at its number")
        }
    }

    @Test
    fun aRepairedSetWhoseFilesAreKeptForgetsHowItWasMade() = runTest {
        val images = listOf(dir, chunk0, chunk1).map { Sha256.image(it) }
        val original = RetainedOriginal("o1", "lilith", InstalledSource.Remote(identity(101)), "Default/lilith_dir.vpk", listOf(VpkPart.Directory, VpkPart.Chunk(0), VpkPart.Chunk(1)).zip(images) { part, image -> SetFile(part, image) })
        val derivation = SetDerivation("o1", images, listOf(RuleIdentity("frame", 1)), "codec", "build", 1, 1)
        val repaired = lilith().let { it.copy(sets = listOf(it.sets[0].copy(derivation = derivation), it.sets[1].copy(derivation = derivation.copy(original = "o2")))) }
        val second = original.copy(id = "o2", componentKey = "Cabezas/pak01_dir.vpk", source = InstalledSource.Remote(identity(103)), files = listOf(SetFile(VpkPart.Directory, Sha256.image(cabezas))))
        val world = World(repaired, originals = listOf(original, second))
        world.damage(FilesDamage.Changed)
        val library = with(world) { library() }

        library.keepEdits("lilith")
        runCurrent()
        assertNull(world.stored().sets[0].derivation, "its files are no longer made from its original")
        assertEquals(repaired.sets[1], world.stored().sets[1], "a set whose files did not change keeps its own")
        assertEquals(listOf(original, second), world.manifest().originals, "the originals stay until the sweep forgets the one nothing is made from")
    }

    @Test
    fun aChoiceForAnEditedModWaitsUntilTheEditIsKeptOrTheOriginalRestoredAndThenGoesThrough() = runTest {
        for (keep in listOf(false, true)) {
            val world = World()
            world.damage(FilesDamage.Changed)
            val library = with(world) { library() }
            library.setEnabled("lilith", false)
            runCurrent()
            assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "keep=$keep: nothing waits on the edit but the mod itself")
            assertEquals(true, world.stored().enabled, "keep=$keep: the edited mod stays where it is")

            if (keep) library.keepEdits("lilith") else library.reinstall("lilith")
            runCurrent()
            assertEquals(ChoiceSync.Settled, library.state.value.choiceSync, "keep=$keep")
            assertEquals(false, world.stored().enabled, "keep=$keep")
        }
    }
}
