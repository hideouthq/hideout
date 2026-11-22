package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
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
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class BackupRestoreTest {
    private val root = GameRoot("/games/Deadlock")
    private val addons = SetFolder.Addons.path
    private val ranked = ProfileId("ranked")
    private val foreign = ByteArray(9) { 7 }

    private class Spec(val id: String, val number: Int?, val parts: List<String>)

    private val specs = listOf(
        Spec("a", 3, listOf("dir", "000")),
        Spec("b", 5, listOf("dir")),
        Spec("c", null, listOf("dir", "000")),
        Spec("d", 9, listOf("dir")),
    )

    private fun bytesOf(id: String, part: String) = "$id-$part".encodeToByteArray() + ByteArray(12 + id[0].code % 5) { (it * 7 + part.length).toByte() }

    private fun record(spec: Spec, number: Int? = spec.number, derivation: SetDerivation? = null): ManifestRecord {
        val files = spec.parts.map { part -> SetFile(checkNotNull(VpkPart.parse(part)), Sha256.image(bytesOf(spec.id, part))) }
        val source = InstalledSource.Local(spec.id, "${spec.id}.zip")
        val placement = number?.let { Placement.On(Slot(it)) } ?: Placement.Off
        return ManifestRecord(spec.id, root, "Mod ${spec.id}", placement, listOf(InstalledSet(source, null, null, SetOrigin.Imported(FileImage(1, "a".repeat(64))), null, "pak01_dir.vpk", files, derivation)))
    }

    private fun records(vararg numbers: Int?) = specs.zip(numbers.toList()) { spec, number -> record(spec, number) }

    private fun book(records: List<ManifestRecord>): ProfileBook {
        val main = ProfileBook.main(records)
        return main.copy(profiles = main.profiles + Profile(ranked, "Ranked", ProfileColour.Blue, setOf("c"), mapOf(root to listOf("c", "a", "b", "d"))))
    }

    private val original = listOf(bytesOf("a", "dir-original"), bytesOf("a", "000"))

    private val repaired = SetDerivation("kept-a", original.map { Sha256.image(it) }, listOf(FakeCompatibility.Frame), "fake-codec-1", "build:fake", 1, 4)

    private inner class World(repairedA: Boolean = false, compatibility: Boolean = false) : CrashWorld {
        val records = specs.map { record(it, derivation = repaired.takeIf { _ -> repairedA && it.id == "a" }) }
        override val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, HideoutGameInfo)
            records.forEach { record -> record.files.forEach { file -> putBytes(root, file.relativePath(record), bytesOf(record.installId, file.part.label)) } }
            putBytes(root, "$addons/pak01_dir.vpk", foreign)
            if (repairedA) originals["kept-a"] = original
        }
        override val store = InMemoryLibraryStore(
            LibraryManifest(
                records = records,
                profiles = book(records),
                originals = listOfNotNull(RetainedOriginal("kept-a", "a", records[0].sets[0].source, records[0].sets[0].componentKey, listOf(SetFile(VpkPart.Directory, Sha256.image(original[0])), SetFile(VpkPart.Chunk(0), Sha256.image(original[1])))).takeIf { repairedA }),
                repairChoices = mapOf("a" to if (repairedA) RepairChoice.Repaired else RepairChoice.Original),
            ),
        )
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        val processes = FakeProcesses()
        val port = FakeCompatibility(files).takeIf { compatibility }
        val logs = mutableListOf<String>()
        private var nextId = 0
        var now = Start
        private var session: CoroutineScope? = null

        fun TestScope.library(scope: CoroutineScope? = null): ModLibrary {
            session?.cancel()
            val owned = scope ?: CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            session = owned
            return ModLibrary(
                FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, files,
                InstallPorts(banana, downloader, files, files, processes, gameFiles = files.gameFiles, backups = files.backups, log = { logs += it }), owned,
                ids = { "id${++nextId}" },
                watch = pollingWatchTiming,
                compatibility = port ?: CompatibilityPort.Unavailable,
                clock = object : Clock {
                    override fun now(): Instant = Instant.fromEpochMilliseconds(now).also { now += 60_000 }
                },
            ).also {
                it.start()
                runCurrent()
            }
        }

        override fun TestScope.open(scope: CoroutineScope) = library(scope)

        override fun check(point: FaultPoint) = checkMounts(point)

        fun manifest() = assertNotNull(store.manifest)

        fun record(id: String) = manifest().records.single { it.installId == id }

        fun game(): Map<String, List<Byte>> = files.everyFile(root).keys.filterNot { it.startsWith(InMemoryGameFiles.Stored) }.associateWith { assertNotNull(files.bytes(root, it)).toList() }

        fun gameOf(records: List<ManifestRecord>): Map<String, List<Byte>> =
            mapOf(InMemoryGameFiles.GameInfo to HideoutGameInfo.toList(), "$addons/pak01_dir.vpk" to foreign.toList()) +
                records.flatMap { record -> record.files.map { it.relativePath(record) to bytesOf(record.installId, it.part.label).toList() } }

        fun offer(mod: Long, bytes: ByteArray, md5: String = Md5A) {
            val remote = RemoteFile(mod * 10, "mod-$mod.zip", 100, md5, null, 1, 1, "https://gamebanana.com/dl/${mod * 10}")
            banana.listings = banana.listings + (SubmissionRef(SubmissionKind.Mod, mod) to FileListing.Available("Mod $mod", listOf(remote)))
            downloader.serve(mod * 10, listOf("mod.vpk" to bytes))
        }

        val mountProblems = mutableListOf<String>()

        fun checkMounts(at: FaultPoint) {
            for (spec in specs) {
                val shown = files.addonsFiles(root).keys.filter { it.endsWith("_dir.vpk") && files.bytes(root, "$addons/$it")?.contentEquals(bytesOf(spec.id, "dir")) == true }
                for (dir in shown) for (part in spec.parts - "dir") {
                    if (files.bytes(root, "$addons/${dir.replace("_dir.vpk", "_$part.vpk")}")?.contentEquals(bytesOf(spec.id, part)) != true) mountProblems += "at $at $dir was visible without its chunk $part"
                }
            }
        }
    }

    private val later = FakeVpk.directory(seed = 41)

    private fun ModLibrary.ready(): LibraryStatus.Ready = assertIs<LibraryStatus.Ready>(state.value.library)

    private fun TestScope.backUp(library: ModLibrary): BackupInfo {
        library.backUp()
        runCurrent()
        assertEquals(Maintenance.Backups(BackupWork.BackUp, BackupStage.Done), library.state.value.maintenance)
        return library.state.value.backups.first()
    }

    private fun TestScope.restore(library: ModLibrary, backup: BackupInfo): BackupStage {
        library.restoreBackup(backup)
        runCurrent()
        val maintenance = assertIs<Maintenance.Backups>(library.state.value.maintenance)
        assertEquals(BackupWork.Revert(backup), maintenance.work)
        return maintenance.stage
    }

    private fun TestScope.change(world: World, library: ModLibrary) {
        library.uninstall(assertNotNull(library.uninstallConfirmation("b")), deleteDownload = false)
        runCurrent()
        library.reorder(assertNotNull(library.state.value.orderView), listOf("a", "c", "d"))
        runCurrent()
        library.setEnabled("c", true)
        runCurrent()
        world.offer(9, later)
        library.install("mod-9")
        runCurrent()
        val view = assertNotNull(library.state.value.orderView)
        library.reorder(view, view.sets.let { listOf(it[0], "c", "a", "d") })
        runCurrent()
        backgroundScope.launch { library.deleteProfile(ranked) }
        runCurrent()
        assertEquals(mapOf("a" to (true to 98), "c" to (true to 8), "d" to (true to 99)), world.manifest().records.filter { it.modId != "mod-9" }.associate { it.installId to (it.enabled to it.number) })
        assertEquals(listOf(ProfileId("main")), world.manifest().profiles.profiles.map { it.id })
    }

    @Test
    fun aRestorePutsBackEveryModOnOrOffWithItsProfilesOrdersAndRepairChoicesAndASetOnNowWithItsBytesKeepsItsName() = runTest {
        val world = World()
        val library = with(world) { library() }
        val before = world.manifest()
        val backup = backUp(library)
        assertEquals(BackupInfo(backup.id, Start, BackupReason.Manual, mods = 4), backup)
        change(world, library)
        val names = listOf("pak99_dir.vpk")
        val kept = names.associateWith { world.files.identity(root, "$addons/$it") }

        world.files.around = world::checkMounts
        assertEquals(BackupStage.Done, restore(library, backup))
        val after = world.manifest()
        assertEquals(records(97, 98, null, 99), after.records, "d keeps the 99 it loads at now, not the backup's 09, a moves only from 98 to 97 rather than back to 03, and b takes 98 between them")
        assertEquals(before.profiles, after.profiles)
        assertEquals(before.repairChoices, after.repairChoices)
        assertNull(after.pending)
        assertNull(after.savedChoices)
        assertEquals(world.gameOf(after.records), world.game(), "every file is where its record names it, and nothing else of Hideout's is left")
        assertEquals(kept, names.associateWith { world.files.identity(root, "$addons/$it") }, "a set on now with the bytes the backup has is not touched")
        assertEquals(emptyList(), world.mountProblems)
        assertEquals(listOf(BackupReason.BeforeRestore, BackupReason.Manual), library.state.value.backups.map { it.reason })
        assertNull(library.ready().interrupted)
        assertEquals(emptyMap(), library.ready().damaged)
        assertEquals(before.profiles, library.state.value.profiles)
        assertEquals(listOf("a", "b", "d", "c"), library.state.value.orderView?.sets)
    }

    @Test
    fun theBackupBeforeARestoreBringsBackTheModsInstalledSinceAndTheirFiles() = runTest {
        val world = World()
        val library = with(world) { library() }
        val backup = backUp(library)
        change(world, library)
        val changed = world.manifest()
        val game = world.game()
        val installed = changed.records.single { it.modId == "mod-9" }

        restore(library, backup)
        assertTrue(world.manifest().records.none { it.installId == installed.installId }, "the mod installed since is removed")
        assertNull(world.files.bytes(root, "$addons/pak02_dir.vpk"))
        val safety = library.state.value.backups.first()
        assertEquals(BackupInfo(safety.id, safety.createdAt, BackupReason.BeforeRestore, mods = 4), safety)

        assertEquals(BackupStage.Done, restore(library, safety))
        val moved = mapOf(installed.installId to 95, "c" to 96, "a" to 97)
        assertEquals(changed.records.map { record -> moved[record.installId]?.let { record.placed(Placement.On(Slot(it))) } ?: record }, world.manifest().records, "c, off since, comes back on at the highest number free below a, which keeps 97")
        assertEquals(changed.profiles, world.manifest().profiles)
        assertEquals(game.mapKeys { (path, _) -> path.replace("/pak02_", "/pak95_").replace("/pak08_", "/pak96_").replace("/pak98_", "/pak97_") }, world.game())
    }

    @Test
    fun updateAllBacksUpFirstAndThatBackupPutsTheOldVersionBack() = runTest {
        val world = World()
        val library = with(world) { library() }
        val old = FakeVpk.directory(seed = 51)
        world.offer(9, old)
        library.install("mod-9")
        runCurrent()
        val installed = world.manifest().records.single { it.modId == "mod-9" }
        val identity = installed.remoteIdentity
        world.offer(9, FakeVpk.directory(seed = 52), md5 = Md5B)

        library.updateAll(listOf(UpdatePlan(root, installed.installId, installed.provenance, listOf(Replacement(identity, identity.copy(fileMd5 = Md5B))))))
        runCurrent()
        val backup = library.state.value.backups.single()
        assertEquals(BackupReason.BeforeUpdates, backup.reason)
        assertEquals(installed, world.files.backups.read(root, backup.id).records.single { it.installId == installed.installId }, "the backup was taken before the update")
        assertEquals(Md5B, world.record(installed.installId).remoteIdentity.fileMd5)

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals(installed, world.record(installed.installId))
        assertContentEquals(old, world.files.bytes(root, "$addons/pak02_dir.vpk"))
    }

    @Test
    fun aRetainedOriginalThatWasForgottenIsPutBackWithTheRepairedSet() = runTest {
        val world = World(repairedA = true, compatibility = true)
        val library = with(world) { library() }
        val before = world.manifest()
        val backup = backUp(library)
        library.uninstall(assertNotNull(library.uninstallConfirmation("a")), deleteDownload = false)
        runCurrent()
        assertEquals(emptyList(), world.manifest().originals, "nothing is made from it any more")
        assertNull(world.files.originals["kept-a"], "so its copy went")

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals(listOf(record(specs[0], 4, repaired)) + before.records.drop(1), world.manifest().records, "a comes back at the highest number free below b")
        assertEquals(before.originals, world.manifest().originals)
        assertEquals(original.map { it.toList() }, assertNotNull(world.files.originals["kept-a"]).map { it.toList() })
        assertEquals(before.repairChoices, world.manifest().repairChoices)
    }

    @Test
    fun aSetWhoseFilesTheStoreLostComesBackMissingForReinstallAndAGameBananaOneIsGotAgain() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.offer(9, later)
        library.install("mod-9")
        runCurrent()
        val installed = world.manifest().records.single { it.modId == "mod-9" }
        val backup = backUp(library)
        world.files.remove(root, "${InMemoryGameFiles.Stored}/${installed.files.single().sha256}")
        world.files.rewrite(root, "$addons/pak05_dir.vpk", bytesOf("b", "edited"))
        library.keepEdits("b")
        runCurrent()
        library.uninstall(assertNotNull(library.uninstallConfirmation(installed.installId)), deleteDownload = false)
        runCurrent()

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals(installed, world.record(installed.installId))
        assertContentEquals(later, world.files.bytes(root, "$addons/pak02_dir.vpk"), "the store does not hold it, so it comes back from its kept download")
        assertEquals(listOf(90L), world.downloader.requested, "downloaded only when it was installed")
        assertEquals(record(specs[1], 8), world.record("b"), "b's file now holds other bytes, so b is placed as if it had none")
        assertNull(world.files.bytes(root, "$addons/pak08_dir.vpk"), "an edit in place reached the stored copy too")
        assertNull(world.files.bytes(root, "$addons/pak05_dir.vpk"))
        assertEquals(mapOf("b" to FilesDamage.Missing), library.ready().damaged, "a local mod's file nothing else holds is left for Reinstall")
    }

    @Test
    fun aChangedOrUnreadableFileRefusesTheRestoreWithNothingChangedOrBackedUp() = runTest {
        val world = World()
        val library = with(world) { library() }
        val backup = backUp(library)
        change(world, library)
        val changed = world.manifest()
        val game = world.game()
        val saves = world.store.saved.size

        world.files.putBytes(root, "$addons/pak99_dir.vpk", bytesOf("d", "edited"))
        assertEquals(BackupStage.Refused(LibraryProblem.FileChanged("$addons/pak99_dir.vpk")), restore(library, backup))
        world.files.putBytes(root, "$addons/pak99_dir.vpk", bytesOf("d", "dir"))
        world.files.failFile(root, "$addons/pak08_000.vpk", "the drive is busy")
        assertEquals(BackupStage.Refused(LibraryProblem.FileUnreadable("$addons/pak08_000.vpk")), restore(library, backup))

        assertEquals(saves, world.store.saved.size)
        assertEquals(changed, world.manifest())
        assertEquals(game - "$addons/pak99_dir.vpk", world.game() - "$addons/pak99_dir.vpk")
        assertEquals(listOf(backup), library.state.value.backups, "no backup is taken before a refusal")
    }

    @Test
    fun aRestoreIsRefusedWhileDeadlockRunsAndOneItStartsDuringWaitsAndThenFinishes() = runTest {
        val world = World()
        val library = with(world) { library() }
        val before = world.manifest()
        val backup = backUp(library)
        change(world, library)
        world.processes.scan = ProcessScan.Seen(game = true)
        advanceTimeBy(2.seconds)
        assertEquals(BackupStage.Refused(LibraryProblem.GameRunning), restore(library, backup))
        assertEquals(listOf(backup), library.state.value.backups, "and nothing is backed up")

        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2.seconds)
        world.files.around = { if (it == FaultPoint.Remove("$addons/pak08_dir.vpk")) world.processes.scan = ProcessScan.Seen(game = true) }
        assertIs<LibraryProblem.Interrupted>(assertIs<BackupStage.Refused>(restore(library, backup)).problem)
        assertEquals(ReplaceStep.Releasing, assertIs<PendingChange.Revert>(world.manifest().pending).step)
        world.files.around = null

        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(3.seconds)
        runCurrent()
        assertNull(world.manifest().pending)
        assertEquals(records(97, 98, null, 99), world.manifest().records)
        assertEquals(before.profiles, world.manifest().profiles)
        assertEquals(world.gameOf(records(97, 98, null, 99)), world.game().filterKeys { TemporaryInfix !in it })
        assertNull(library.ready().interrupted)
    }

    @Test
    fun aRestorePlacesItsModsAroundAFileThatTookANumberOfItsSinceTheBackup() = runTest {
        val world = World()
        val library = with(world) { library() }
        val backup = backUp(library)
        library.uninstall(assertNotNull(library.uninstallConfirmation("b")), deleteDownload = false)
        runCurrent()
        world.files.putBytes(root, "$addons/pak08_dir.vpk", foreign)

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals(records(3, 7, null, 9), world.manifest().records, "b comes back at 07, below the file that took the 08 it would take")
        assertEquals(world.gameOf(records(3, 7, null, 9)) + ("$addons/pak08_dir.vpk" to foreign.toList()), world.game())
    }

    @Test
    fun modsUninstalledSinceTheBackupComeBackAtTheHighestFreeNumbersInItsOrder() = runTest {
        val world = World()
        val library = with(world) { library() }
        val backup = backUp(library)
        for (id in listOf("a", "b", "c", "d")) {
            library.uninstall(assertNotNull(library.uninstallConfirmation(id)), deleteDownload = false)
            runCurrent()
        }
        assertEquals(emptyList(), world.manifest().records)
        world.files.putBytes(root, "$addons/pak99_dir.vpk", foreign)

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals(records(96, 97, null, 98), world.manifest().records, "a, b and d in the backup's order up to 98, since a file Hideout did not put there holds 99")
        assertEquals(world.gameOf(records(96, 97, null, 98)) + ("$addons/pak99_dir.vpk" to foreign.toList()), world.game())
        assertEquals(listOf("a", "b", "d", "c"), library.state.value.orderView?.sets)
    }

    @Test
    fun savedChoicesTheBackupHeldComeBackAllowedAndTheFilesFollowThem() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.processes.scan = ProcessScan.Seen(game = true)
        advanceTimeBy(2.seconds)
        library.setEnabled("c", true)
        runCurrent()
        assertNotNull(world.manifest().savedChoices)
        val backup = backUp(library)
        assertTrue(world.files.backups.read(root, backup.id).savedChoices?.target?.activeProfile?.enabled.orEmpty().contains("c"))
        library.setEnabled("c", false)
        runCurrent()
        assertNull(world.manifest().savedChoices)

        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2.seconds)
        assertEquals(BackupStage.Done, restore(library, backup))
        advanceTimeBy(2.seconds)
        assertNull(world.manifest().pending)
        assertNull(world.manifest().savedChoices)
        assertTrue(world.record("c").enabled, "the saved choice turned c on once the backup was restored")
        assertContentEquals(bytesOf("c", "000"), world.files.bytes(root, "$addons/pak99_000.vpk"), "after d")
    }

    @Test
    fun theNewestFiveAutomaticBackupsAndEveryManualOneAreKeptAndPruningKeepsWhatTheyName() = runTest {
        val world = World()
        val library = with(world) { library() }
        val manual = backUp(library)
        world.files.putBytes(root, "$addons/pak09_dir.vpk", bytesOf("d", "edited"))
        library.keepEdits("d")
        runCurrent()
        val only = "${InMemoryGameFiles.Stored}/${sha256Hex(bytesOf("d", "edited"))}"

        restore(library, manual)
        assertNotNull(world.files.identity(root, only), "the backup before the restore holds d as it was edited")
        repeat(5) { restore(library, manual) }
        val kept = library.state.value.backups
        assertEquals(List(5) { BackupReason.BeforeRestore } + BackupReason.Manual, kept.map { it.reason })
        assertNull(world.files.identity(root, only), "what only a deleted backup named goes")
        for (backup in kept) for (sha in world.files.backups.read(root, backup.id).contents) assertNotNull(world.files.identity(root, "${InMemoryGameFiles.Stored}/$sha"), sha)

        library.deleteBackup(manual)
        runCurrent()
        assertEquals(Maintenance.Backups(BackupWork.Delete(manual), BackupStage.Done), library.state.value.maintenance)
        assertEquals(kept - manual, library.state.value.backups)
        for (sha in world.manifest().contents) assertNotNull(world.files.identity(root, "${InMemoryGameFiles.Stored}/$sha"), "$sha is named by the backups left")
    }

    @Test
    fun aRestoreThatStopsKeepsTheBackupItWasRestoringAndEveryFileItNames() = runTest {
        val world = World()
        val library = with(world) { library() }
        val manual = backUp(library)
        world.offer(9, later)
        library.install("mod-9")
        runCurrent()
        restore(library, manual)
        val oldest = library.state.value.backups.first()
        repeat(4) { restore(library, manual) }
        assertEquals(List(5) { BackupReason.BeforeRestore } + BackupReason.Manual, library.state.value.backups.map { it.reason })

        world.store.refuse = { manifest -> "the disk is full".takeIf { manifest.pending is PendingChange.Revert } }
        assertIs<BackupStage.Refused>(restore(library, oldest))
        assertTrue(oldest in library.state.value.backups, "the oldest of six automatic backups stays while it is not restored")
        for (sha in world.files.backups.read(root, oldest.id).pinned) assertNotNull(world.files.identity(root, "${InMemoryGameFiles.Stored}/$sha"), sha)

        world.store.refuse = { null }
        assertEquals(BackupStage.Done, restore(library, oldest))
        assertContentEquals(later, world.files.bytes(root, "$addons/pak02_dir.vpk"), "mod 9 comes back from its kept download")
    }

    @Test
    fun aBackupMadeWhileTheClockIsBehindIsKeptThoughItSortsOldest() = runTest {
        val world = World()
        val library = with(world) { library() }
        val manual = backUp(library)
        repeat(5) { restore(library, manual) }
        world.now = Start - 3_600_000

        assertEquals(BackupStage.Done, restore(library, manual))
        val safety = library.state.value.backups.last()
        assertTrue(safety.reason == BackupReason.BeforeRestore && safety.createdAt < manual.createdAt, "the backup made before the restore stays: $safety")
        for (sha in world.files.backups.read(root, safety.id).contents) assertNotNull(world.files.identity(root, "${InMemoryGameFiles.Stored}/$sha"), sha)
    }

    @Test
    fun aDriveWithoutHardLinksBacksUpByCopyingAndRestoresFromTheCopies() = runTest {
        val world = World()
        world.files.hardLinks = false
        val library = with(world) { library() }
        val backup = backUp(library)
        for (sha in world.files.backups.read(root, backup.id).contents) assertNotNull(world.files.identity(root, "${InMemoryGameFiles.Stored}/$sha"), sha)
        assertEquals(BackupStage.Done, restore(library, backup))
    }

    @Test
    fun aBackupThatCannotBeWrittenIsRefusedAndUpdateAllGoesOnWithoutOne() = runTest {
        val world = World()
        world.files.around = { if (it is FaultPoint.Link && it.path.startsWith(InMemoryGameFiles.Stored)) error("no space left on the drive") }
        val library = with(world) { library() }
        library.backUp()
        runCurrent()
        assertEquals(Maintenance.Backups(BackupWork.BackUp, BackupStage.Refused(LibraryProblem.BackupFailed("no space left on the drive"))), library.state.value.maintenance)
        assertEquals(emptyList(), library.state.value.backups)

        world.files.around = null
        world.offer(9, later)
        library.install("mod-9")
        runCurrent()
        val installed = world.manifest().records.single { it.modId == "mod-9" }
        val identity = installed.remoteIdentity
        world.offer(9, FakeVpk.directory(seed = 52), md5 = Md5B)
        world.files.remove(root, "${InMemoryGameFiles.Stored}/${sha256Hex(later)}")
        world.files.failing += FaultPoint.Link("${InMemoryGameFiles.Stored}/${sha256Hex(later)}")
        library.updateAll(listOf(UpdatePlan(root, installed.installId, installed.provenance, listOf(Replacement(identity, identity.copy(fileMd5 = Md5B))))))
        runCurrent()
        assertEquals(emptyList(), library.state.value.backups)
        assertEquals(Md5B, world.record(installed.installId).remoteIdentity.fileMd5, "the update went on")
        assertTrue(world.logs.any { it.startsWith("Update all went on without a backup") }, "${world.logs}")
    }

    @Test
    fun aCrashAtAnyStepOfARestoreThatMovesNumbersAndDuringItsRecoveryLeavesTheLibraryWholeAsItWasOrAsRestored() = runTest {
        fun TestScope.prepared(): Pair<World, BackupInfo> {
            val world = World()
            val library = with(world) { library() }
            val backup = backUp(library)
            change(world, library)
            val view = assertNotNull(library.state.value.orderView)
            library.reorder(view, listOf(view.sets[0], "c", "d", "a"))
            runCurrent()
            assertEquals(mapOf("c" to 8, "d" to 97, "a" to 98), world.manifest().records.filter { it.modId != "mod-9" }.associate { it.installId to it.number })
            return world to backup
        }

        val (dry, backup) = prepared()
        val start = dry.manifest()
        val startGame = dry.game()
        val act: CoroutineScope.(ModLibrary) -> Unit = { restore(it, backup) }
        val trace = traced(dry, act)
        val after = dry.manifest()
        val afterGame = dry.game()
        assertEquals(emptyList(), dry.mountProblems)
        assertTrue(trace.points.size > 30 && trace.saves >= 6, "the trace has ${trace.points.size} points and ${trace.saves} saves")
        assertEquals(records(95, 96, null, 97), after.records, "d keeps 97, so a moves from 98 below it and b comes back between them")

        crashAtEveryStep(trace, { prepared().first }, act) { world, _ ->
            val manifest = world.manifest()
            val undone = manifest.records == start.records
            assertTrue(undone || manifest.records == after.records, "the records are all as they were or all as backed up: ${manifest.records}")
            assertEquals(if (undone) start.profiles else after.profiles, manifest.profiles, "the applied book goes with the files")
            assertEquals(if (undone) startGame else afterGame, world.game(), "every file is where it should be with its bytes, and no anchor is left")
            assertEquals(emptyList(), world.mountProblems)
        }
    }

    private companion object {
        const val Start = 1_761_400_000_000L
        const val Md5A = "ab965746d197e296c253fb552dc1fd47"
        const val Md5B = "0cc175b9c0f1b6a831c399e269772661"
    }
}
