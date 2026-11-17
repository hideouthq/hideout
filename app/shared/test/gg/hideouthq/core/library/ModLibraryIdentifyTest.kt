package gg.hideouthq.core.library

import gg.hideouthq.core.data.ArchiveIndex
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.printOf
import gg.hideouthq.feature.moddetail.pageInstallation
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModLibraryIdentifyTest {
    private val root = GameRoot("/games/Deadlock")
    private val citadel = "game/citadel"
    private val info = "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\t// Hideout start\n\t\t\tGame\tcitadel/addons\n\t\t\t// Hideout end\n\t\t\tGame\tcitadel\n\t\t\tGame\tcore\n\t\t}\n\t}\n}\n"
    private val onBytes = FakeVpk.directory(seed = 61)
    private val offBytes = FakeVpk.directory(seed = 62)
    private val files = game()
    private val looked = mutableListOf<String>()

    private fun game() = InMemoryGameFiles().apply { putBytes(root, "$citadel/gameinfo.gi", info.encodeToByteArray()) }

    private fun local(installId: String, id: String, title: String, enabled: Boolean, number: Int, vararg bytes: ByteArray, submission: SubmissionRef?, game: InMemoryGameFiles = files): ManifestRecord {
        val source = InstalledSource.Local(id, "pak%02d_dir.vpk".format(number), submission)
        val record = ManifestRecord(
            installId, root, title, placedAt(number.takeIf { enabled }),
            bytes.mapIndexed { offset, set -> InstalledSet(source, null, null, SetOrigin.Adopted(MigrationId("m-1")), null, "pak%02d_dir.vpk".format(number + offset), listOf(directory(Sha256.image(set)))) },
        )
        record.files.zip(bytes.toList()).forEach { (file, set) -> game.putBytes(root, file.relativePath(record), set) }
        return record
    }

    private fun TestScope.library(
        store: LibraryStore, banana: FakeGameBanana, archives: ArchiveIndex = ArchiveIndex.Unavailable, processes: FakeProcesses = FakeProcesses(),
        scope: CoroutineScope = backgroundScope, game: InMemoryGameFiles = files,
    ): ModLibrary {
        val inventory = object : GameInventory by game {
            override suspend fun vpk(root: GameRoot, path: CitadelPath) = game.vpk(root, path).also { looked += path.value }
        }
        val downloader = FakeDownloader(game).apply { serve(Ace.noBag.id, listOf("pak02_dir.vpk" to onBytes)) }
        return started(ModLibrary(FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, inventory, InstallPorts(banana, downloader, game, game, processes, gameFiles = game.gameFiles, archives = archives), scope, watch = testWatchTiming))
    }

    private fun offAce(game: InMemoryGameFiles = files): Pair<ManifestRecord, ManifestRecord> {
        val off = local("b", "off", "Ace Infernus", enabled = false, 6, offBytes, submission = null, game = game)
        return off to off.copy(title = Ace.listing.title, sets = listOf(off.only.copy(source = InstalledSource.Remote(Ace.withBag.identity(Ace.ref)))))
    }

    private fun mugetsu(enabled: Boolean, game: InMemoryGameFiles = files) =
        local("m", "mugetsu", "Mugetsu (pak09_dir.vpk)", enabled, 9, FakeVpk.directory(seed = 67), submission = Ace.ref, game = game)

    private fun repaired(record: ManifestRecord): Pair<ManifestRecord, RetainedOriginal> {
        val original = RetainedOriginal("orig-1", record.installId, record.only.source, record.only.componentKey, listOf(SetFile(VpkPart.Directory, Sha256.image(FakeVpk.directory(seed = 69)))))
        return record.withOnly { copy(derivation = SetDerivation(original.id, original.files.map { it.image }, listOf(RuleIdentity("root-motion-attachment-frame", 1)), "fake-codec-1", "build:fake", resources = 1, edits = 1)) } to original
    }

    private val aceFound = FakeGameBanana(mapOf(Ace.ref to Ace.listing), searches = mapOf("Ace Infernus" to listOf(Ace.ref)))

    private val aceListed = FakeGameBanana(mapOf(Ace.ref to Ace.listing))

    private val holdingOff = ArchiveIndex { listOf(printOf(offBytes)) }

    @Test
    fun checkingForUpdatesProvesWhoLocalModsAreReadingOnlyFilesAnArchiveCouldHold() = runTest {
        val on = local("a", "on", "Ace", enabled = true, 5, onBytes, submission = Ace.ref)
        val off = local("b", "off", "Ace Infernus", enabled = false, 6, offBytes, submission = null)
        val other = local("c", "other", "Something else", enabled = true, 7, FakeVpk.directory(embeddedBytes = 40, seed = 63), submission = null)
        val known = local("d", "known", "Ace again", enabled = false, 8, FakeVpk.directory(seed = 64), submission = Ace.ref)
        val before = files.everyFile(root).keys.associateWith { files.bytes(root, it)?.toList() }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(on, off, other, known)))
        val banana = FakeGameBanana(mapOf(Ace.ref to FileListing.Available(Ace.listing.title, listOf(Ace.withBag, Ace.noBag))), searches = mapOf("Ace Infernus" to listOf(Ace.ref), "Something else" to listOf(Ace.ref)))
        val archives = ArchiveIndex { file -> listOf(if (file.id == Ace.noBag.id) printOf(onBytes) else printOf(offBytes)) }
        val library = library(store, banana, archives)
        looked.clear()

        library.identifyLocal().join()

        val records = checkNotNull(store.manifest).records.associateBy { it.installId }
        assertEquals(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 626_469, Ace.noBag.id, Ace.noBag.md5)), records.getValue("a").only.source, "a mod that is on becomes the file its bytes are in")
        assertEquals(Ace.listing.title, records.getValue("a").title, "and takes GameBanana's title")
        assertEquals(off.copy(sets = listOf(off.only.copy(source = InstalledSource.Local("off", "pak06_dir.vpk", Ace.ref)))), records.getValue("b"), "one found by its title whose submission another mod already is keeps its folder and its title and gains the submission")
        assertEquals(listOf(other, known), listOf(records.getValue("c"), records.getValue("d")), "one nothing proves, and one whose bytes no file of its submission holds, stay as they were")
        assertEquals(listOf(on, off, known).map { it.files.single().citadelPath(it).value }, looked, "each file is read once, and only when an archive holds a file its size")
        assertEquals(listOf("Ace Infernus", "Something else"), banana.searched, "only mods with no submission are searched for")
        assertEquals(1, banana.calls, "and each submission is asked once a check")
        assertEquals(before, files.everyFile(root).keys.associateWith { files.bytes(root, it)?.toList() }, "no file moved")
    }

    @Test
    fun theModsAnImportBroughtAreFoundInTheBackgroundAndOneGameBananaDoesNotKnowStaysLocal() = runTest {
        val on = local("a", "on", "Ace", enabled = true, 5, onBytes, submission = Ace.ref).copy(unasked = true)
        val other = local("c", "other", "Something else", enabled = true, 7, FakeVpk.directory(embeddedBytes = 40, seed = 63), submission = null).copy(unasked = true)
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(on, other)))
        val library = library(store, FakeGameBanana(mapOf(Ace.ref to FileListing.Available(Ace.listing.title, listOf(Ace.withBag, Ace.noBag)))), ArchiveIndex { listOf(printOf(onBytes)) })
        runCurrent()

        val records = checkNotNull(store.manifest).records.associateBy { it.installId }
        assertEquals(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 626_469, Ace.withBag.id, Ace.withBag.md5)), records.getValue("a").only.source, "nothing had to ask for it")
        assertEquals(other.copy(unasked = false), records.getValue("c"), "GameBanana found nothing, so it stays a local mod with its name")
        assertEquals(emptyList(), library.state.value.unasked)
        assertEquals(false, library.state.value.findingLater)
    }

    @Test
    fun whileGameBananaDoesNotAnswerTheModsWaitAcrossARestartAndAreFoundOnceItDoes() = runTest {
        val on = local("a", "on", "Ace", enabled = true, 5, onBytes, submission = Ace.ref).copy(unasked = true)
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(on)))
        val banana = FakeGameBanana()
        val archives = ArchiveIndex { listOf(printOf(onBytes)) }
        val closed = CoroutineScope(backgroundScope.coroutineContext + Job())
        val first = library(store, banana, archives, scope = closed)
        runCurrent()
        assertEquals(listOf(on), checkNotNull(store.manifest).records, "the mod stays as the import brought it")
        assertEquals(listOf(on), first.state.value.unasked)
        assertTrue(first.state.value.findingLater, "and the library says GameBanana will be asked later")
        closed.cancel()

        val again = library(store, banana, archives)
        runCurrent()
        assertTrue(again.state.value.findingLater, "Hideout opened again asks again")
        banana.listings = mapOf(Ace.ref to Ace.listing)
        advanceTimeBy(testWatchTiming.findAgain)
        runCurrent()
        assertEquals(InstalledSource.Remote(Ace.withBag.identity(Ace.ref)), checkNotNull(store.manifest).records.single().only.source)
        assertEquals(false, again.state.value.findingLater)
    }

    @Test
    fun eachSetOfALocalModIsPinnedToItsOwnFileAndOneWithSomePinnedGainsTheSubmission() = runTest {
        val nakayama = SubmissionRef(SubmissionKind.Mod, 612_201)
        val vivian = SubmissionRef(SubmissionKind.Mod, 551_337)
        fun file(id: Long) = RemoteFile(id, "$id.zip", 1, "%032x".format(id), null, 1, id, "https://gamebanana.com/dl/$id")
        val (main, lines, banshee) = listOf(1_702_938L, 1_703_360L, 1_555_419L).map(::file)
        val bytes = (71..74).map { FakeVpk.directory(seed = it) }
        val festa = local("n", "festa", "Nakayama Festa over Wraith", enabled = true, 10, bytes[0], bytes[1], submission = null)
        val banshees = local("v", "banshee", "Vivian Banshee", enabled = true, 12, bytes[2], bytes[3], submission = null)
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(festa, banshees)))
        val banana = FakeGameBanana(
            mapOf(nakayama to FileListing.Available("Nakayama Festa", listOf(main, lines)), vivian to FileListing.Available("Vivian", listOf(banshee))),
            searches = mapOf(festa.title to listOf(nakayama), banshees.title to listOf(vivian)),
        )
        val indexes = mapOf(main.id to bytes[0], lines.id to bytes[1], banshee.id to bytes[2]).mapValues { listOf(printOf(it.value)) }
        library(store, banana, ArchiveIndex { indexes[it.id] }).identifyLocal().join()

        val records = checkNotNull(store.manifest).records.associateBy { it.installId }
        assertEquals(listOf(main, lines).map { InstalledSource.Remote(it.identity(nakayama)) }, records.getValue("n").sets.map { it.source }, "a main file and its voice lines, two files of one submission")
        assertEquals("Nakayama Festa", records.getValue("n").title)
        assertEquals(banshees.copy(sets = banshees.sets.map { it.copy(source = InstalledSource.Local("banshee", "pak12_dir.vpk", vivian)) }), records.getValue("v"), "a mod one of whose sets pins stays local, known by its submission")
    }

    @Test
    fun anOffLocalModThatPinsMovesToItsSubmissionsOffFolderAsTheSameFilesWhileDeadlockRunsToo() = runTest {
        val (off, remote) = offAce()
        val identity = files.identity(root, off.files.single().relativePath(off))
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(off)))
        val library = library(store, aceFound, holdingOff, FakeProcesses(ProcessScan.Seen(game = true)))
        library.identifyLocal().join()

        val manifest = checkNotNull(store.manifest)
        assertEquals(listOf(remote) to null, manifest.records to manifest.pending, "it is GameBanana's file, still off")
        assertEquals(mapOf(remote.files.single().name to Sha256.image(offBytes)), files.filesIn(root, SetFolder.Off(Ace.ref)), "its bytes are in its submission's off folder, under its file's own key")
        assertEquals(identity, files.identity(root, remote.files.single().relativePath(remote)), "as the same file, never a copy")
        assertEquals(emptyMap(), files.filesIn(root, SetFolder.Off("local-off")), "and nothing is left in its own")
        assertEquals(setOf(Ace.withBag.identity(Ace.ref)), library.installedSources.first(), "so its updates are asked for as any GameBanana mod's are")
    }

    @Test
    fun anOffLocalModThatCannotMoveGainsOnlyItsSubmissionAndMovesAtALaterCheck() = runTest {
        val (off, remote) = offAce()
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(off)))
        val library = library(store, aceFound, holdingOff)
        val before = files.everyFile(root)
        val blocked = FaultPoint.Link(remote.files.single().relativePath(remote))
        files.failing += blocked
        library.identifyLocal().join()
        assertEquals(listOf(off.withOnly { copy(source = InstalledSource.Local("off", "pak06_dir.vpk", Ace.ref)) }), checkNotNull(store.manifest).records, "a move that cannot be made leaves it where it is, known by its submission")
        assertEquals(before, files.everyFile(root), "with every file as it was")

        files.failing -= blocked
        library.identifyLocal().join()
        assertEquals(listOf(remote), checkNotNull(store.manifest).records)
    }

    private fun TestScope.eachCrashConverges(before: (InMemoryGameFiles) -> ManifestRecord, banana: FakeGameBanana, archives: ArchiveIndex, act: CoroutineScope.(ModLibrary) -> Unit) {
        class World : CrashWorld {
            override val files = game()
            val record = before(files)
            override val store = InMemoryLibraryStore(LibraryManifest(records = listOf(record)))
            val processes = FakeProcesses().apply { scan = ProcessScan.Seen(game = true) }

            override fun TestScope.open(scope: CoroutineScope) = library(store, banana, archives, processes, scope, files)
        }
        val dry = World()
        val trace = traced(dry, act)
        val after = checkNotNull(dry.store.manifest).records.single()
        assertTrue(after != dry.record && trace.points.size >= 10, "the run changes the mod, and its trace has ${trace.points}")

        crashAtEveryStep(trace, ::World, act) { world, _ ->
            val record = checkNotNull(world.store.manifest).records.single()
            assertTrue(record == world.record || record == after, "$record is the mod before or after")
            assertEquals(record.files.associate { it.name to it.image }, world.files.filesIn(root, record.setFolder()).filterKeys { TemporaryInfix !in it })
            assertEquals(emptyMap(), world.files.filesIn(root, (if (record == after) world.record else after).setFolder()).filterKeys { TemporaryInfix !in it }, "but for anchors")
        }
    }

    @Test
    fun aCrashAtAnyStepOfMovingAnOffModWhileDeadlockRunsConvergesWithItWholeInOneFolder() = runTest {
        eachCrashConverges({ game -> offAce(game).first }, aceFound, holdingOff) { it.identifyLocal() }
    }

    @Test
    fun aCrashAtAnyStepOfChangingAPageLinkedOffModToAFileWhileDeadlockRunsConvergesWithItWholeInOneFolder() = runTest {
        eachCrashConverges({ game -> mugetsu(enabled = false, game) }, aceListed, ArchiveIndex.Unavailable) { changeFilesTo(it, "m", Ace.noBag.id) }
    }

    @Test
    fun aMoveWhoseReleasingSaveIsNotConfirmedWaitsInterruptedAndRecoveryFinishesIt() = runTest {
        val (off, remote) = offAce()
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(off)))
        val disk = FlakyStore(store)
        store.afterStore = { saved ->
            if ((saved.pending as? PendingChange.Relocate)?.step == RelocateStep.Releasing) {
                store.afterStore = {}
                disk.unreadableLoads = 1
                error("the disk did not confirm the write")
            }
        }
        val first = crashableScope()
        val library = library(disk, aceFound, holdingOff, scope = first)
        library.identifyLocal().join()
        assertNotNull(assertIs<LibraryStatus.Ready>(library.state.value.library).interrupted, "the library waits")
        assertEquals(listOf(off), checkNotNull(store.manifest).records, "with the mod as it was")
        assertIs<PendingChange.Relocate>(store.manifest?.pending, "and its move kept for recovery")
        first.cancel()

        val restarted = library(disk, aceFound, holdingOff)
        val manifest = checkNotNull(store.manifest)
        assertEquals(listOf(remote) to null, manifest.records to manifest.pending, "recovery finishes the move")
        assertNull(assertIs<LibraryStatus.Ready>(restarted.state.value.library).interrupted)
        assertEquals(mapOf(remote.files.single().name to Sha256.image(offBytes)), files.filesIn(root, SetFolder.Off(Ace.ref)))
        assertEquals(emptyMap(), files.filesIn(root, SetFolder.Off("local-off")))
    }

    @Test
    fun aMoveWhoseSourceIsReplacedBeforeItGoesIsUndoneAndTheModGainsOnlyItsSubmission() = runTest {
        val (off, _) = offAce()
        val source = off.files.single().relativePath(off)
        val replaced = FakeVpk.directory(seed = 68)
        files.around = { if (it == FaultPoint.Remove(source)) { files.around = null; files.putBytes(root, source, replaced) } }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(off)))
        val library = library(store, aceFound, holdingOff)
        library.identifyLocal().join()

        val manifest = checkNotNull(store.manifest)
        assertEquals(listOf(off.withOnly { copy(source = InstalledSource.Local("off", "pak06_dir.vpk", Ace.ref)) }) to null, manifest.records to manifest.pending, "the move is undone, and nothing waits")
        assertNull(assertIs<LibraryStatus.Ready>(library.state.value.library).interrupted)
        assertEquals(emptyMap(), files.filesIn(root, SetFolder.Off(Ace.ref)), "nothing is left in its submission's folder")
        assertEquals(setOf(Sha256.image(replaced), Sha256.image(offBytes)), files.filesIn(root, SetFolder.Off("local-off")).values.toSet(), "the new file stays, and the bytes the move had are kept beside it")
    }

    @Test
    fun aMoveWhoseSourceIsEditedInPlaceBeforeItGoesIsUndoneWithNothingLeftInTheWayOfALaterOne() = runTest {
        val (off, remote) = offAce()
        val source = off.files.single().relativePath(off)
        files.around = { if (it == FaultPoint.Remove(source)) { files.around = null; files.rewrite(root, source, FakeVpk.directory(seed = 68)) } }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(off)))
        val library = library(store, aceFound, holdingOff)
        library.identifyLocal().join()

        val manifest = checkNotNull(store.manifest)
        assertEquals(listOf(off.withOnly { copy(source = InstalledSource.Local("off", "pak06_dir.vpk", Ace.ref)) }) to null, manifest.records to manifest.pending, "the move is undone, and nothing waits")
        assertEquals(emptyMap(), files.filesIn(root, SetFolder.Off(Ace.ref)), "nothing is left in its submission's folder")
        files.rewrite(root, source, offBytes)
        library.identifyLocal().join()
        assertEquals(listOf(remote), checkNotNull(store.manifest).records, "so once its bytes are back it moves there")
    }

    @Test
    fun aChunkSwappedWhileAMoveReleasesItsSourcesUndoesItWithEveryFileOfTheModAtItsName() = runTest {
        val chunk = FakeVpk.directory(seed = 70)
        val swapped = FakeVpk.directory(seed = 71)
        val off = offAce().first.withOnly { copy(files = this.files + SetFile(VpkPart.Chunk(1), FileImage(chunk.size.toLong(), sha256Hex(chunk)))) }
        val (directory, chunkPath) = off.files.map { it.relativePath(off) }
        files.putBytes(root, chunkPath, chunk)
        files.around = { if (it == FaultPoint.Remove(chunkPath)) { files.around = null; files.putBytes(root, chunkPath, swapped) } }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(off)))
        library(store, aceFound, ArchiveIndex { listOf(printOf(offBytes), printOf(chunk)) }).identifyLocal().join()

        val manifest = checkNotNull(store.manifest)
        assertEquals(listOf(off.withOnly { copy(source = InstalledSource.Local("off", "pak06_dir.vpk", Ace.ref)) }) to null, manifest.records to manifest.pending, "the move is undone")
        val left = files.filesIn(root, SetFolder.Off("local-off"))
        assertEquals(Sha256.image(offBytes) to Sha256.image(swapped), left[directory.substringAfterLast('/')] to left[chunkPath.substringAfterLast('/')], "with each of its files at its name")
        assertEquals(emptyMap(), files.filesIn(root, SetFolder.Off(Ace.ref)))
    }

    @Test
    fun aRepairedOnModThatPinsIsThatFilesInstallAndItsOriginalFollowsIt() = runTest {
        val (repaired, original) = repaired(local("a", "on", "Ace", enabled = true, 5, onBytes, submission = Ace.ref))
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(repaired), originals = listOf(original)))
        library(store, aceListed, ArchiveIndex { listOf(printOf(onBytes)) }).identifyLocal().join()

        val manifest = checkNotNull(store.manifest)
        val pinned = InstalledSource.Remote(Ace.withBag.identity(Ace.ref))
        assertEquals(repaired.copy(title = Ace.listing.title, sets = listOf(repaired.only.copy(source = pinned))), manifest.records.single())
        assertEquals(listOf(original.copy(source = pinned)), manifest.originals, "and its original follows it")
    }

    @Test
    fun aRepairedOffModThatPinsGainsOnlyItsSubmissionAndTheModsAfterItAreStillIdentified() = runTest {
        val (repaired, original) = repaired(offAce().first)
        val on = local("a", "on", "Ace", enabled = true, 5, onBytes, submission = null)
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(repaired, on), originals = listOf(original)))
        val banana = FakeGameBanana(mapOf(Ace.ref to Ace.listing), searches = mapOf("Ace Infernus" to listOf(Ace.ref), "Ace" to listOf(Ace.ref)))
        library(store, banana, ArchiveIndex { file -> listOf(if (file.id == Ace.noBag.id) printOf(onBytes) else printOf(offBytes)) }).identifyLocal().join()

        val manifest = checkNotNull(store.manifest)
        val known = InstalledSource.Local("off", "pak06_dir.vpk", Ace.ref)
        assertEquals(repaired.withOnly { copy(source = known) }, manifest.records.single { it.installId == "b" }, "it stays in its folder, known by its submission")
        assertEquals(listOf(original.copy(source = known)), manifest.originals, "and its original follows it")
        assertEquals(InstalledSource.Remote(Ace.noBag.identity(Ace.ref)), manifest.records.single { it.installId == "a" }.only.source, "the mod after it is still proved")
    }

    @Test
    fun aLocalModLinkedToAPageChangesToAFileOfItAndIsThenThatFilesInstallInOneRow() = runTest {
        for (enabled in listOf(true, false)) {
            val game = game()
            val linked = mugetsu(enabled, game)
            val store = InMemoryLibraryStore(LibraryManifest(records = listOf(linked)))
            val library = library(store, aceListed, game = game)
            assertTrue(changeFilesTo(library, linked.installId, Ace.noBag.id), "its page offers GameBanana's files")

            val record = checkNotNull(store.manifest).records.single()
            assertEquals(listOf(InstalledSource.Remote(Ace.noBag.identity(Ace.ref))), record.sets.map { it.source }, "on $enabled: it is that file's install")
            assertEquals(Ace.listing.title to enabled, record.title to record.enabled)
            assertEquals(record.files.associate { it.name to Sha256.image(onBytes) }, game.filesIn(root, record.setFolder()), "with its bytes where that file belongs")
            assertEquals(emptyMap(), game.filesIn(root, SetFolder.Off("local-mugetsu")))
        }
    }

    @Test
    fun aChangeOfFilesRefusedBecauseTheModWasProvedMeanwhileSucceedsOnTryAgain() = runTest {
        val linked = mugetsu(enabled = true)
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(linked)))
        val library = library(store, aceListed, ArchiveIndex { file -> listOf(printOf(if (file.id == Ace.withBag.id) FakeVpk.directory(seed = 67) else onBytes)) })
        library.changeFiles(linked.installId)
        runCurrent()
        val question = assertIs<OperationStage.ChoosingFile>(library.state.value.operations[Ace.ref.modId]?.stage)
        library.identifyLocal().join()
        assertEquals(InstalledSource.Remote(Ace.withBag.identity(Ace.ref)), checkNotNull(store.manifest).records.single().only.source, "Check for updates proved it while the question was open")

        library.answer(question.id, ChoiceAnswer.Files(setOf(Ace.noBag.id), question.profiles))
        runCurrent()
        assertEquals(LibraryProblem.VersionChanged, assertIs<OperationStage.Failed>(library.state.value.operations[Ace.ref.modId]?.stage).problem)
        assertTrue(library.retry(Ace.ref.modId))
        runCurrent()
        assertNull(library.state.value.operations[Ace.ref.modId])
        assertEquals(InstalledSource.Remote(Ace.noBag.identity(Ace.ref)), checkNotNull(store.manifest).records.single().only.source, "Try again starts from the mod as it is now")
    }

    @Test
    fun aLocalModLinkedToAPageWhoseSubmissionIsInstalledBesideItNeverBecomesASecondInstallOfIt() = runTest {
        val linked = mugetsu(enabled = false)
        val remote = local("r", "ace", "Ace", enabled = true, 10, FakeVpk.directory(seed = 65), submission = null).withOnly { copy(source = InstalledSource.Remote(Ace.withBag.identity(Ace.ref))) }
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(linked, remote)))
        val library = library(store, aceListed)
        assertTrue(changeFilesTo(library, linked.installId, Ace.noBag.id))
        assertEquals(LibraryProblem.AlreadyInstalled, assertIs<OperationStage.Failed>(library.state.value.operations[Ace.ref.modId]?.stage).problem)
        assertEquals(listOf(linked, remote), checkNotNull(store.manifest).records, "and both stay as they were")
    }

    @Test
    fun aLocalModIsSearchedForByItsTitleWithoutTheFileNameAnImportPutAfterItAndLinkedByThatExactTitle() = runTest {
        val sinclair = SubmissionRef(SubmissionKind.Mod, 669_760)
        val toa = SubmissionRef(SubmissionKind.Mod, 705_790)
        fun listed(title: String, id: Long) = FileListing.Available(title, listOf(RemoteFile(id, "$id.zip", 1, null, null, 1, 1, "https://gamebanana.com/dl/$id")))
        val record = local("s", "sinclair", "The Great Magnificent Sinclair (pak09_dir.vpk)", enabled = true, 9, FakeVpk.directory(seed = 66), submission = null)
        val store = InMemoryLibraryStore(LibraryManifest(records = listOf(record)))
        val banana = FakeGameBanana(
            mapOf(toa to listed("The Great Magnificent Sinclair TOA", 1), sinclair to listed("The Great Magnificent Sinclair", 2)),
            searches = mapOf("The Great Magnificent Sinclair" to listOf(toa, sinclair)),
        )
        library(store, banana).identifyLocal().join()

        assertEquals(listOf("The Great Magnificent Sinclair"), banana.searched)
        assertEquals(record.withOnly { copy(source = InstalledSource.Local("sinclair", "pak09_dir.vpk", sinclair)) }, checkNotNull(store.manifest).records.single(), "the page of that exact title, not the near one found first, and its own title stays")
    }

    @Test
    fun aLocalModKnownByItsSubmissionIsThatSubmissionsInstall() = runTest {
        val known = local("d", "known", "Ace again", enabled = false, 8, FakeVpk.directory(seed = 64), submission = Ace.ref)
        val library = library(InMemoryLibraryStore(LibraryManifest(records = listOf(known))), FakeGameBanana(mapOf(Ace.ref to Ace.listing)))
        val page = library.state.value.pageInstallation(Ace.ref.modId)
        assertEquals(known to InstallStatus.Installed(enabled = false), page.record to page.status, "its page shows it installed")
        library.install(Ace.ref.modId)
        runCurrent()
        assertEquals(LibraryProblem.AlreadyInstalled, assertIs<OperationStage.Failed>(library.state.value.operations[Ace.ref.modId]?.stage).problem, "so the page never installs it twice")
    }

    @Test
    fun aSubmissionsPageShowsItsRemoteInstallOverALocalOneKnownToBeFromIt() = runTest {
        val known = local("d", "known", "Ace again", enabled = false, 8, FakeVpk.directory(seed = 64), submission = Ace.ref)
        val remote = local("r", "ace", "Ace", enabled = true, 9, FakeVpk.directory(seed = 65), submission = null).let { record ->
            record.copy(sets = record.sets.map { it.copy(source = InstalledSource.Remote(Ace.noBag.identity(Ace.ref))) })
        }
        val library = library(InMemoryLibraryStore(LibraryManifest(records = listOf(known, remote))), FakeGameBanana(mapOf(Ace.ref to Ace.listing)))
        val page = library.state.value.pageInstallation(Ace.ref.modId)
        assertEquals(remote to InstallStatus.Installed(enabled = true), page.record to page.status)
    }
}
