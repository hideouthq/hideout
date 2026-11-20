package gg.hideouthq.core.library

import gg.hideouthq.core.analytics.*
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameConfig
import gg.hideouthq.core.data.GameLocator
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.LocalImportResult
import gg.hideouthq.core.data.LocalImport
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.LocalArchiveSnapshot
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
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ModLibraryInstallTest {
    private val root = GameRoot("/games/Deadlock")
    private val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
    private val vanillaInfo = "GameInfo\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame\t\tcitadel\r\n\t\t\tGame\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n".encodeToByteArray()

    private val single = FakeVpk.directory(embeddedBytes = 40, seed = 1)
    private val chunkedDir = FakeVpk.directory(embeddedBytes = 4, chunkReads = mapOf(0 to 30L, 2 to 20L), seed = 2)
    private val chunk0 = ByteArray(30) { 3 }
    private val chunk2 = ByteArray(20) { 4 }

    private val ivyMd5 = "ae386fa2a8adb7bc07d17d47195a3c25"

    private fun remote(id: Long, name: String = "bg_ivy.zip", md5: String? = ivyMd5, downloads: Long = 10, description: String? = null) =
        RemoteFile(id, name, 1_000, md5, description, downloads, addedAt = 1_777_795_519, downloadUrl = "https://gamebanana.com/dl/$id")

    private val offFolder = SetFolder.Off(ivy).path

    private fun stem(fileId: Long, componentKey: String) = SetKey.of(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, ivy.id, fileId, ivyMd5)), componentKey).offStem("id1")

    private val chunkedStem = stem(9, "ivy_dir.vpk")
    private val singleStem = stem(4, "a.vpk")

    private fun off(part: String, stem: String = chunkedStem) = "${stem}_$part.vpk"

    private fun copy(name: String, installId: String = "id1") = "$name$TemporaryInfix$installId"

    private inner class World(var info: ByteArray? = vanillaInfo, initial: LibraryManifest? = null) : CrashWorld {
        override val files = InMemoryGameFiles().apply { info?.let { putBytes(root, InMemoryGameFiles.GameInfo, it) } }
        override val store = InMemoryLibraryStore(initial)
        val disk = FlakyStore(store)
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        val processes = FakeProcesses()
        var config: GameConfig = files
        var locator: GameLocator = FakeGameLocator(listOf(root.path), mapOf(root.path to null))
        val preferences = Preferences(MemoryStorage())
        var selectedProfiles: Set<ProfileId> = setOf(ProfileId("main"))
        var local: LocalImport = LocalImport.Unavailable
        val logged = mutableListOf<String>()
        private var nextId = 0

        fun offer(vararg files: RemoteFile, title: String = "Bubblegum ivy V2") {
            banana.listings = banana.listings + (ivy to FileListing.Available(title, files.toList()))
        }

        fun TestScope.library(scope: CoroutineScope = backgroundScope, analytics: UsageAnalytics? = null): ModLibrary = started(
            ModLibrary(
                locator,
                preferences,
                disk,
                files,
                InstallPorts(banana, downloader, files, config, processes, localImport = local, log = { logged += it }),
                scope,
                analytics = analytics,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
                resourceIndexes = ResourceIndexCache(MemoryStorage(), files.resourceIndexes),
            ),
        )

        override fun TestScope.open(scope: CoroutineScope) = library(scope)

        fun addons() = files.addonsFiles(root)

        fun parked(submission: SubmissionRef = ivy) = files.filesIn(root, SetFolder.Off(submission))
    }

    private fun question(library: ModLibrary, modId: String = ivy.modId): OperationStage = assertNotNull(library.state.value.operations[modId]).stage

    private fun TestScope.installed(world: World, library: ModLibrary, modId: String = ivy.modId): InstallStatus {
        library.install(InstallRequest(InstallInput.Remote(SubmissionRef.parse(modId)!!), world.selectedProfiles))
        runCurrent()
        return library.state.value.operations[modId]?.let { op -> (op.stage as? OperationStage.Failed)?.let { InstallStatus.Failed(it.problem) } ?: InstallStatus.Unknown }
            ?: (library.state.value.library as LibraryStatus.Ready).records.firstOrNull { it.remoteIdentity.ref.modId == modId }?.let { InstallStatus.Installed(it.enabled) }
            ?: InstallStatus.NotInstalled
    }

    private fun failure(library: ModLibrary, modId: String = ivy.modId): LibraryProblem =
        assertIs<OperationStage.Failed>(library.state.value.operations[modId]?.stage).problem

    @Test
    fun analyticsFollowsActualInstallationAndOmitsContentDetails() = runTest {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.load(); analytics.choose(true)
        val world = World()
        world.offer(remote(4))
        world.downloader.serve(4, listOf("secret-skin.vpk" to single))
        val library = with(world) { library(analytics = analytics) }
        library.install(ivy.modId)
        runCurrent()
        assertEquals(1, world.store.manifest!!.records.size)
        val results = sent.filterIsInstance<UsageEvent.Result>()
        assertEquals(1, results.size)
        assertEquals(UsageOperation.ModInstall, results.single().operation)
        assertEquals(UsageOutcome.Completed, results.single().outcome)
        assertFalse(sent.toString().contains("secret-skin"))
        assertFalse(sent.toString().contains(ivy.id.toString()))
    }

    @Test
    fun analyticsReportsCancellingAFileChoiceAsCancelled() = runTest {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.load(); analytics.choose(true)
        val world = World()
        world.offer(remote(1), remote(2))
        val library = with(world) { library(analytics = analytics) }
        library.install(ivy.modId)
        runCurrent()
        assertTrue(library.answer(assertNotNull(question(library).choice), ChoiceAnswer.Cancel))
        runCurrent()
        assertEquals(UsageOutcome.Cancelled, sent.filterIsInstance<UsageEvent.Result>().single().outcome)
    }

    private val rival = SubmissionRef(SubmissionKind.Mod, 700_001)
    private val wraith = "models/heroes/wraith/wraith.vmdl_c"

    private fun skin(bytes: String, path: String = wraith) = FakeVpk.of(mapOf(path to bytes.encodeToByteArray()))

    private fun TestScope.skinWorld(rivalSkin: ByteArray = skin("rival"), ivySkin: ByteArray = skin("ivy"), local: LocalImport = LocalImport.Unavailable): Pair<World, ModLibrary> {
        val world = World()
        world.local = local
        world.banana.listings = world.banana.listings + (rival to FileListing.Available("Rival skin", listOf(remote(11, "rival.zip"))))
        world.downloader.serve(11, listOf("rival.vpk" to rivalSkin))
        world.offer(remote(12))
        world.downloader.serve(12, listOf("ivy.vpk" to ivySkin))
        val library = with(world) { library() }
        library.install(InstallRequest(InstallInput.Remote(rival), world.selectedProfiles))
        runCurrent()
        return world to library
    }

    private fun ModLibrary.onByMod(): Map<String, Boolean> = (state.value.library as LibraryStatus.Ready).records.associate { it.modId to it.enabled }

    @Test
    fun aSkinShowingAModelOfAModThatIsOnAsksOnceUnpackedAndSwapTurnsThatModOffInTheChangeThatTurnsItOn() = runTest {
        val (world, library) = skinWorld()
        library.install(InstallRequest(InstallInput.Remote(ivy), world.selectedProfiles))
        runCurrent()
        val asked = assertIs<OperationStage.ChoosingSwap>(question(library))
        assertEquals(listOf(11L, 12L), world.downloader.requested, "asked once the skin is downloaded and unpacked")
        val rivalId = world.store.manifest!!.records.single { it.modId == rival.modId }.installId
        assertEquals(listOf(rivalId), asked.others)
        assertTrue(library.answer(asked.id, ChoiceAnswer.Swap))
        runCurrent()
        assertEquals(mapOf(rival.modId to false, ivy.modId to true), library.onByMod())
        assertEquals(Undoable.Turned(false, 1, profile = null), library.state.value.undo?.undoes)
        val last = world.store.saved.mapNotNull { it.pending as? PendingChange.Arrange }.last()
        assertEquals(mapOf(rivalId to false, world.store.manifest!!.records.single { it.modId == ivy.modId }.installId to true), last.moves.associate { it.source.installId to it.target.enabled }, "one change turns the new skin on and the old one off")
    }

    @Test
    fun aSwapTurnsTheOtherModOffOnlyInTheActiveProfile() = runTest {
        val (world, library) = skinWorld()
        val main = ProfileId("main")
        val other = assertIs<MutationResult.Done<ProfileId>>(library.createProfile(NewProfile("Other", ProfileColour.Blue, ProfileSeed.Copy(main), activate = false))).value
        library.install(InstallRequest(InstallInput.Remote(ivy), setOf(main, other)))
        runCurrent()
        assertTrue(library.answer(assertIs<OperationStage.ChoosingSwap>(question(library)).id, ChoiceAnswer.Swap))
        runCurrent()
        val rivalId = world.store.manifest!!.records.single { it.modId == rival.modId }.installId
        val profiles = library.state.value.profiles!!.profiles.associate { it.id to it.enabled }
        assertFalse(rivalId in profiles.getValue(main), "turned off where the question was asked")
        assertTrue(rivalId in profiles.getValue(other), "left on in a profile that is not active")
    }

    @Test
    fun keepBothLeavesBothOnAndCancelInstallsNothing() = runTest {
        val (world, library) = skinWorld()
        library.install(InstallRequest(InstallInput.Remote(ivy), world.selectedProfiles))
        runCurrent()
        assertTrue(library.answer(assertIs<OperationStage.ChoosingSwap>(question(library)).id, ChoiceAnswer.Cancel))
        runCurrent()
        assertEquals(mapOf(rival.modId to true), library.onByMod(), "a cancelled swap installs nothing")

        library.install(InstallRequest(InstallInput.Remote(ivy), world.selectedProfiles))
        runCurrent()
        assertTrue(library.answer(assertIs<OperationStage.ChoosingSwap>(question(library)).id, ChoiceAnswer.KeepBoth))
        runCurrent()
        assertEquals(mapOf(rival.modId to true, ivy.modId to true), library.onByMod())
        val records = world.store.manifest!!.records
        assertEquals(setOf(records.single { it.modId == rival.modId }.installId), records.single { it.modId == ivy.modId }.keptBeside, "the new mod remembers the one kept beside it")
    }

    @Test
    fun aRetryAfterAFailedPlacementDoesNotAskAgain() = runTest {
        val (world, library) = skinWorld()
        library.install(InstallRequest(InstallInput.Remote(ivy), world.selectedProfiles))
        runCurrent()
        val asked = assertIs<OperationStage.ChoosingSwap>(question(library))
        world.disk.unreadable = "the drive is gone"
        assertTrue(library.answer(asked.id, ChoiceAnswer.Swap))
        runCurrent()
        assertIs<OperationStage.Failed>(question(library), "the install list cannot be saved")
        world.disk.unreadable = null
        assertTrue(library.retry(ivy.modId))
        runCurrent()
        assertEquals(mapOf(rival.modId to false, ivy.modId to true), library.onByMod(), "the retry swaps as answered, asking nothing")
    }

    @Test
    fun aLocalSkinOfOnePartAsksOnlyAboutTheSwap() = runTest {
        val archive = ArchiveIdentity("/imports/ivy.zip", 100, "a".repeat(64))
        val (world, library) = skinWorld(local = object : LocalImport {
            override suspend fun snapshot(file: LocalFile): LocalImportResult = LocalImportResult.Ready(LocalArchiveSnapshot(archive, file.name))
            override suspend fun release(snapshot: LocalArchiveSnapshot) = Unit
        })
        world.files.putArchive(archive.path, listOf("ivy.vpk" to skin("local ivy")), FileImage(100, "a".repeat(64)))
        library.install(InstallRequest(InstallInput.Local(LocalFile("/picked/ivy.zip", "ivy.zip", 100)), world.selectedProfiles))
        runCurrent()
        val local = library.state.value.operations.values.single()
        val swap = assertIs<OperationStage.ChoosingSwap>(library.state.value.operations.getValue(local.modId).stage, "a skin from the user's own file is asked about too")
        assertEquals(listOf(world.store.manifest!!.records.single { it.modId == rival.modId }.installId), swap.others)
    }

    @Test
    fun noSwapIsAskedForOtherModelsTheSameModelAModThatIsOffOrANewModLeftOff() = runTest {
        for ((ivySkin, why) in listOf(skin("ivy", path = "models/heroes/haze/haze.vmdl_c") to "another hero's model", skin("rival") to "the same model's bytes")) {
            val (world, library) = skinWorld(ivySkin = ivySkin)
            assertEquals(InstallStatus.Installed(enabled = true), installed(world, library), why)
            assertEquals(mapOf(rival.modId to true, ivy.modId to true), library.onByMod(), why)
        }
        val (offWorld, offLibrary) = skinWorld()
        offLibrary.setEnabled(offWorld.store.manifest!!.records.single().installId, false)
        runCurrent()
        assertEquals(InstallStatus.Installed(enabled = true), installed(offWorld, offLibrary), "the other skin is off")
        val (leftOff, leftOffLibrary) = skinWorld()
        leftOff.selectedProfiles = emptySet()
        assertEquals(InstallStatus.Installed(enabled = false), installed(leftOff, leftOffLibrary), "the new skin stays off")
        assertEquals(mapOf(rival.modId to true, ivy.modId to false), leftOffLibrary.onByMod())
    }

    @Test
    fun aSingleFileModInstallsUnderTheLowestFreeNumberWithItsExactIdentity() = runTest {
        val world = World()
        world.offer(remote(1_691_864))
        world.downloader.serve(1_691_864, listOf("pak70_dir.vpk" to single, "readme.txt" to ByteArray(3)))
        world.files.addAddonsEntry(root, "pak01_dir.vpk")
        world.files.addAddonsEntry(root, "631628_pak02_dir.vpk")
        val library = with(world) { library() }

        assertEquals(InstallStatus.Installed(enabled = true), installed(world, library))

        val record = world.store.manifest!!.records.single()
        assertEquals(SubmissionIdentity(SubmissionKind.Mod, 628_739, 1_691_864, "ae386fa2a8adb7bc07d17d47195a3c25"), record.remoteIdentity)
        assertEquals("Bubblegum ivy V2", record.title)
        assertEquals("Bg ivy", record.variant)
        assertEquals(2, record.number)
        assertEquals(listOf(SetFile(VpkPart.Directory, FileImage(single.size.toLong(), sha256Hex(single)))), record.only.files)
        assertEquals(Sha256.image(single), world.addons()["pak02_dir.vpk"])
        assertEquals(setOf("pak01_dir.vpk", "631628_pak02_dir.vpk", "pak02_dir.vpk"), world.addons().keys)
        assertEquals(listOf(OwnedDownload(record.remoteIdentity, record.archive)), world.store.manifest!!.downloads)
        assertNull(world.store.manifest!!.pending)
        assertEquals(emptySet(), world.files.stagingFolders, "staging is discarded")
        assertEquals(emptyMap(), library.state.value.operations)
    }

    @Test
    fun aNewModIsPlacedOffAndOneArrangementTurnsItOnFirstInTheOrder() = runTest {
        val world = World()
        val seeded = seededIntact(world)
        world.serveOne()
        val library = with(world) { library() }
        assertEquals(InstallStatus.Installed(enabled = true), installed(world, library))

        val placing = world.store.saved.mapNotNull { it.pending as? PendingChange.Install }
        assertTrue(placing.isNotEmpty() && placing.all { it.record.placement == Placement.Off }, "the install's own journal placed it off")
        assertEquals(1, world.store.arranges(), "one arrangement turned it on")
        val record = world.store.manifest!!.records.single { it.modId == ivy.modId }
        assertEquals(listOf(record.installId, seeded.installId), world.store.manifest!!.profiles.activeProfile.orders[root], "first in the order")
        assertEquals(1, record.number, "the lowest number before the mod at 09")
        assertEquals(setOf("pak01_dir.vpk", "pak09_dir.vpk"), world.addons().keys)
        assertEquals(emptyMap(), world.parked())
        assertNull(world.store.manifest!!.savedChoices)
    }

    @Test
    fun aFileWithoutAPublishedMd5StillInstallsAndIsOwnedByItsHashes() = runTest {
        val world = World()
        world.offer(remote(7, md5 = null))
        world.downloader.serve(7, listOf("mod.vpk" to single))
        val library = with(world) { library() }
        assertEquals(InstallStatus.Installed(enabled = true), installed(world, library))
        val record = world.store.manifest!!.records.single()
        assertNull(record.remoteIdentity.fileMd5)
        assertEquals(sha256Hex(single), record.files.single().sha256)
    }

    @Test
    fun aChunkedSetTakesOneNumberAndItsDirectoryIsPublishedLast() = runTest {
        val world = World()
        world.offer(remote(9))
        world.downloader.serve(9, listOf("Ivy/ivy_000.vpk" to chunk0, "Ivy/ivy_dir.vpk" to chunkedDir, "Ivy/ivy_002.vpk" to chunk2, "Ivy/ivy_005.vpk" to ByteArray(2)))
        val library = with(world) { library() }
        installed(world, library)
        assertEquals(listOf("000", "002", "005", "dir").map { off(it, stem(9, "Ivy/ivy_dir.vpk")) }, world.files.publishedNames, "into its off folder, the directory last")
        assertEquals(setOf("pak01_dir.vpk", "pak01_000.vpk", "pak01_002.vpk", "pak01_005.vpk"), world.addons().keys)
        assertEquals(Sha256.image(chunk2), world.addons()["pak01_002.vpk"])
    }

    @Test
    fun foreignEntriesAndTemporariesAllTakeNumbers() = runTest {
        val world = World()
        world.files.addAddonsEntry(root, "pak01_dir.vpk")
        world.files.addAddonsEntry(root, "PAK02_000.VPK")
        world.files.addFolder(root, "${InMemoryGameFiles.Addons}/pak03_dir.vpk")
        world.files.addLink(root, "${InMemoryGameFiles.Addons}/pak4")
        world.files.addAddonsEntry(root, "pak05_dir.vpk.hideout-someone")
        world.offer(remote(11))
        world.downloader.serve(11, listOf("a.vpk" to single))
        val library = with(world) { library() }
        installed(world, library)
        assertEquals(6, world.store.manifest!!.records.single().number)
    }

    @Test
    fun severalFilesWaitForAnExactChoiceAndNothingIsGuessed() = runTest {
        val world = World()
        world.offer(remote(1, "blue_cfda7.zip", downloads = 5), remote(2, "pink.zip", downloads = 50, description = "Pink"))
        world.downloader.serve(1, listOf("a.vpk" to single))
        world.downloader.serve(2, listOf("b.vpk" to FakeVpk.directory(seed = 9)))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()

        assertEquals(InstallStatus.NeedsChoice, library.status(ivy.modId).first())
        val choice = assertIs<OperationStage.ChoosingFile>(question(library))
        assertEquals(setOf(2L), choice.preselected)
        assertEquals(listOf(2L, 1L), choice.files.map { it.id })
        assertTrue(choice.profiles.isNotEmpty(), "the switch starts from Settings › Mods, which is on by default")
        assertEquals(emptyList(), world.downloader.requested, "nothing downloads before a choice")

        assertTrue(library.answer(choice.id, ChoiceAnswer.Files(setOf(1), setOf(ProfileId("main")))))
        runCurrent()
        assertEquals(listOf(1L), world.downloader.requested)
        assertEquals("Blue", world.store.manifest!!.records.single().variant)
    }

    @Test
    fun aChoiceCanBeCancelledLeavingNothingBehind() = runTest {
        val world = World()
        world.offer(remote(1), remote(2))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()
        assertTrue(library.answer(assertNotNull(question(library).choice), ChoiceAnswer.Cancel))
        runCurrent()
        assertEquals(emptyMap(), library.state.value.operations)
        assertEquals(emptyList(), world.downloader.requested)
    }

    @Test
    fun aDownloadHoldingSeveralSetsWaitsForOneToBeChosen() = runTest {
        val world = World()
        world.offer(remote(3))
        val red = FakeVpk.directory(seed = 5)
        world.downloader.serve(3, listOf("Blue/pak01_dir.vpk" to single, "Red/pak01_dir.vpk" to red, "Red/pak01_000.vpk" to ByteArray(1)))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()

        assertEquals(InstallStatus.NeedsChoice, library.status(ivy.modId).first())
        val choice = assertIs<OperationStage.ChoosingSet>(question(library))
        val sets = choice.options
        assertEquals(listOf("Blue/pak01_dir.vpk", "Red/pak01_dir.vpk"), sets.map { it.key })
        assertEquals(listOf("Red/pak01_dir.vpk", "Red/pak01_000.vpk"), sets[1].paths)
        assertEquals("bg_ivy.zip", choice.fileName)
        assertEquals(emptyMap(), world.addons(), "nothing is placed before a choice")

        assertTrue(library.answer(choice.id, ChoiceAnswer.Sets(listOf("Red/pak01_dir.vpk"), setOf(ProfileId("main")))))
        runCurrent()
        assertEquals(setOf("pak01_dir.vpk", "pak01_000.vpk"), world.addons().keys)
        assertEquals(Sha256.image(red), world.addons()["pak01_dir.vpk"])
        assertEquals(emptySet(), world.files.stagingFolders)
    }

    @Test
    fun aDownloadHoldingSeveralSetsCanInstallSeveralAsOneModInTheChosenOrderWithoutSplittingAChunkedSet() = runTest {
        val world = World()
        world.offer(remote(3))
        val red = FakeVpk.directory(seed = 5)
        world.downloader.serve(3, listOf("Blue/pak01_dir.vpk" to single, "Red/pak01_dir.vpk" to red, "Red/pak01_000.vpk" to ByteArray(1)))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()

        val choice = assertIs<OperationStage.ChoosingSet>(question(library))
        assertEquals(listOf("Blue/pak01_dir.vpk", "Red/pak01_dir.vpk"), choice.preselected, "every component starts ticked, in archive order")

        assertTrue(library.answer(choice.id, ChoiceAnswer.Sets(listOf("Blue/pak01_dir.vpk", "Red/pak01_dir.vpk"), setOf(ProfileId("main")))))
        runCurrent()
        val record = world.store.manifest!!.records.single()
        assertEquals(listOf("Blue/pak01_dir.vpk", "Red/pak01_dir.vpk"), record.sets.map { it.componentKey }, "Blue wins, as the chosen order lists it first")
        assertEquals(listOf(1, 2), record.numbers.map { it.value })
        assertEquals(1, record.sets[0].files.size, "the standalone component is one file")
        assertEquals(2, record.sets[1].files.size, "the chunked component keeps its chunk beside its directory, never split across a choice")
        assertEquals(setOf("pak01_dir.vpk", "pak02_dir.vpk", "pak02_000.vpk"), world.addons().keys)
        assertEquals(Sha256.image(single), world.addons()["pak01_dir.vpk"])
        assertEquals(Sha256.image(red), world.addons()["pak02_dir.vpk"])
        assertEquals(1, world.store.manifest!!.downloads.size, "one archive download serves every component")
        assertEquals(emptySet(), world.files.stagingFolders)
    }

    @Test
    fun cancellingASetChoiceDiscardsTheExtractionAndKeepsTheDownload() = runTest {
        val world = World()
        world.offer(remote(3))
        world.downloader.serve(3, listOf("Blue/pak01_dir.vpk" to single, "Red/pak01_dir.vpk" to FakeVpk.directory(seed = 5)))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()
        assertTrue(library.answer(assertIs<OperationStage.ChoosingSet>(question(library)).id, ChoiceAnswer.Cancel))
        assertEquals(OperationStage.Cancelling, question(library), "the question is gone before the extraction is put away")
        assertEquals(InstallStatus.Cancelling, library.status(ivy.modId).first())
        runCurrent()
        assertEquals(emptyMap(), library.state.value.operations)
        assertEquals(emptySet(), world.files.stagingFolders)
        assertEquals(1, world.store.manifest!!.downloads.size)
        assertEquals(emptyList(), world.store.manifest!!.records)
    }

    @Test
    fun aNameTakenAtTheLastMomentUndoesTheSetAndLeavesTheForeignFile() = runTest {
        val world = World()
        world.serveChunked()
        world.files.beforePublish = { at, name -> if (name == off("dir")) world.files.putBytes(at, "$offFolder/${off("dir")}", chunkedDir) }
        val library = with(world) { library() }
        installed(world, library)

        assertEquals(LibraryProblem.NameTaken(off("dir")), failure(library))
        assertEquals(
            mapOf(off("dir") to Sha256.image(chunkedDir), off("000") to Sha256.image(chunk0), off("002") to Sha256.image(chunk2)),
            world.parked(),
            "the foreign same-byte directory stays with the chunks it would mount; no copy of Hideout's does",
        )
        assertEquals(emptyMap(), world.addons())
        val rollback = world.store.saved.mapNotNull { it.pending as? PendingChange.Install }.single { it.step is InstallStep.RollingBack }
        assertEquals(setOf(ProfileId("main")), rollback.profiles, "the rollback journal keeps the profiles the install was for")
        world.assertNothingCommitted()
        assertContentEquals(vanillaInfo, world.files.gameInfo(root))
    }

    @Test
    fun filesAnUninstallLeftInTheOffFolderNeverStopTheSameFileInstallingAgain() = runTest {
        val world = World()
        world.selectedProfiles = emptySet()
        world.serveChunked()
        val library = with(world) { library() }
        assertEquals(InstallStatus.Installed(enabled = false), installed(world, library))
        val changed = FakeVpk.directory(seed = 78)
        world.files.rewrite(root, "$offFolder/${off("dir")}", changed)
        library.uninstall(assertNotNull(library.uninstallConfirmation("id1")), deleteDownload = false)
        runCurrent()
        val left = mapOf(off("dir") to Sha256.image(changed), off("000") to Sha256.image(chunk0), off("002") to Sha256.image(chunk2))
        assertEquals(left, world.parked(), "a changed directory stays with its chunks")

        assertEquals(InstallStatus.Installed(enabled = false), installed(world, library), "the same file installs again")
        val again = world.store.manifest!!.records.single()
        assertEquals(left + again.files.associate { it.name to it.image }, world.parked(), "beside what the uninstall left")
    }

    @Test
    fun aRefusedManifestSaveUndoesEveryPublishedFile() = runTest {
        val world = chunkedWorld()
        world.files.addAddonsEntry(root, "mine.vpk")
        world.store.refuse = { manifest -> "disk full".takeIf { manifest.records.isNotEmpty() } }
        val library = with(world) { library() }
        installed(world, library)

        assertEquals(LibraryProblem.ManifestSave("disk full"), failure(library))
        assertEquals(setOf("mine.vpk"), world.addons().keys)
        assertEquals(emptyMap(), world.parked())
        assertNull(world.store.manifest!!.pending)
    }

    @Test
    fun aCrashBetweenLinkingAndRemovingTheLastCopyIsFinishedOnTheNextStart() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Linked(off("dir")))

        val restarted = with(world) { library() }
        assertEquals(onNames, world.addons().keys, "committed off, then turned on")
        assertEquals(emptyMap(), world.parked())
        assertEquals(1, world.store.manifest!!.records.size, "every file was proven Hideout's, so the install is committed")
        assertEquals(emptyMap(), (restarted.state.value.library as LibraryStatus.Ready).damaged)
    }

    @Test
    fun aCrashWhileCopyingLeavesOnlyTemporariesThatTheNextStartRemoves() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Stage(copy(off("002"))))
        assertEquals(setOf(copy(off("dir")), copy(off("000"))), world.parked().keys)

        with(world) { library() }
        assertEquals(emptyMap(), world.parked())
        assertEquals(emptyMap(), world.addons())
    }

    @Test
    fun anUndoNeverDeletesAPublishedFileSomeoneChanged() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Publish(off("dir")))
        val edited = ByteArray(30) { 9 }
        world.files.putBytes(root, "$offFolder/${off("000")}", edited)

        with(world) { library() }
        assertEquals(mapOf(off("000") to Sha256.image(edited)), world.parked())
        assertNull(world.store.manifest!!.pending)
    }

    private fun TestScope.crashInstall(world: World, at: FaultPoint) {
        world.files.crashAt = at
        val doomed = with(world) { crashableScope() }
        with(world) { library(doomed) }.install(InstallRequest(InstallInput.Remote(ivy), world.selectedProfiles))
        runCurrent()
        doomed.cancel()
        world.files.crashAt = null
    }

    private fun World.serveChunked() {
        offer(remote(9))
        downloader.serve(9, listOf("ivy_dir.vpk" to chunkedDir, "ivy_000.vpk" to chunk0, "ivy_002.vpk" to chunk2))
    }

    private fun chunkedWorld() = World(info = LoadsAddonsGameInfo).apply { serveChunked() }

    private fun World.serveOne() {
        offer(remote(4))
        downloader.serve(4, listOf("a.vpk" to single))
    }

    private fun World.assertNothingCommitted() {
        assertEquals(emptyList(), store.manifest!!.records)
        assertNull(store.manifest!!.pending)
    }

    @Test
    fun aCrashAtAnyStepOfAnInstallOrOfTurningItOnAndDuringItsRecoveryEndsWithTheModWholeOrNotThere() = runTest {
        val act: CoroutineScope.(ModLibrary) -> Unit = { it.install(InstallRequest(InstallInput.Remote(ivy), setOf(ProfileId("main")))) }
        val dry = chunkedWorld()
        val trace = traced(dry, act)
        assertTrue(dry.store.manifest!!.records.single().enabled)
        assertEquals(listOf(PendingChange.Install::class, PendingChange.Arrange::class), dry.store.saved.mapNotNull { it.pending }.map { it::class }.distinct(), "the install's journal, then the one that turns it on")
        assertTrue(trace.points.size > 20, "the trace has ${trace.points}")

        crashAtEveryStep(trace, ::chunkedWorld, act) { world, _ ->
            val manifest = world.store.manifest ?: LibraryManifest()
            when (val record = manifest.records.singleOrNull()) {
                null -> assertEquals(emptyMap(), world.addons() + world.parked(), "undone whole")
                else -> {
                    assertTrue(record.enabled, "a committed install is turned on once Hideout opens again")
                    assertEquals(mapOf("pak01_dir.vpk" to Sha256.image(chunkedDir), "pak01_000.vpk" to Sha256.image(chunk0), "pak01_002.vpk" to Sha256.image(chunk2)), world.addons())
                    assertEquals(emptyMap(), world.parked())
                }
            }
            assertNull(manifest.savedChoices)
        }
    }

    @Test
    fun aCrashAtAnyStepOfAnInstallWhileDeadlockRunsIsSettledBeforeItCloses() = runTest {
        fun world() = chunkedWorld().apply { processes.scan = ProcessScan.Seen(game = true) }
        val act: CoroutineScope.(ModLibrary) -> Unit = { it.install(InstallRequest(InstallInput.Remote(ivy), setOf(ProfileId("main")))) }
        val dry = world()
        val trace = traced(dry, act)
        assertFalse(dry.store.manifest!!.records.single().enabled, "it waits off for Deadlock to close")
        assertTrue(trace.points.size > 10, "the trace has ${trace.points}")

        crashAtEveryStep(trace, ::world, act) { world, restarted ->
            val record = world.store.manifest?.records.orEmpty().singleOrNull()
            assertEquals(record?.files.orEmpty().associate { it.name to it.image }, world.parked(), "whole in its off folder, or undone")
            assertEquals(emptyMap(), world.addons())
            if (record != null) {
                restarted.setEnabled(record.installId, false)
                runCurrent()
                assertNull(restarted.state.value.choiceProblem, "an edit saves while Deadlock runs")
                assertEquals(emptySet(), restarted.state.value.profiles!!.activeProfile.enabled)
            }
        }
    }

    @Test
    fun aSameByteFileAtAnUnpublishedSingleNameIsNeverAdoptedAfterItsCopyVanishes() = runTest {
        val world = World(info = LoadsAddonsGameInfo)
        world.serveOne()
        val name = off("dir", singleStem)
        crashInstall(world, FaultPoint.Publish(name))
        world.files.remove(root, "$offFolder/${copy(name)}")
        world.files.putBytes(root, "$offFolder/$name", single)

        val restarted = with(world) { library() }
        assertEquals(emptyList(), world.store.manifest!!.records, "equal bytes are not proof that Hideout published the file")
        assertEquals(emptyList(), (restarted.state.value.library as LibraryStatus.Ready).records)
        assertNull(world.store.manifest!!.pending)
        assertEquals(mapOf(name to Sha256.image(single)), world.parked())
    }

    @Test
    fun aCopyReplacedWithOtherBytesWhilePublishingIsKept() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Publish(off("dir")))
        val sentinel = "not the copy Hideout wrote".encodeToByteArray()
        world.files.putBytes(root, "$offFolder/${copy(off("dir"))}", sentinel)

        with(world) { library() }
        assertEquals(mapOf(copy(off("dir")) to Sha256.image(sentinel)), world.parked(), "the chunks are undone and the changed copy stays")
        world.assertNothingCommitted()
    }

    @Test
    fun aTornCopyFromACrashWhileStagingIsKeptAndTheNextInstallGoesAhead() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Stage(copy(off("002"))))
        val torn = chunk2.copyOf(chunk2.size / 2)
        world.files.putBytes(root, "$offFolder/${copy(off("002"))}", torn)

        val restarted = with(world) { library() }
        assertEquals(mapOf(copy(off("002")) to Sha256.image(torn)), world.parked(), "the complete copies go and the torn one stays")
        assertNull(world.store.manifest!!.pending)

        installed(world, restarted)
        assertEquals(1, world.store.manifest!!.records.single().number)
        assertEquals(onNames, world.addons().keys)
        assertEquals(mapOf(copy(off("002")) to Sha256.image(torn)), world.parked())
    }

    @Test
    fun aCrashWhileReleasingTheCopiesIsCommittedOnTheNextStart() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Remove("$offFolder/${copy(off("000"))}"))

        val restarted = with(world) { library() }
        assertEquals(onNames, world.addons().keys)
        assertEquals(emptyMap(), world.parked())
        assertEquals(1, world.store.manifest!!.records.size)
        assertNull(world.store.manifest!!.pending)
        assertEquals(emptyMap(), (restarted.state.value.library as LibraryStatus.Ready).damaged)
    }

    @Test
    fun aFileChangedWhileReleasingUndoesTheInstallAndKeepsTheChange() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Remove("$offFolder/${copy(off("000"))}"))
        val edited = ByteArray(30) { 9 }
        world.files.putBytes(root, "$offFolder/${off("000")}", edited)

        with(world) { library() }
        assertEquals(mapOf(off("000") to Sha256.image(edited)), world.parked())
        assertEquals(emptyMap(), world.addons())
        world.assertNothingCommitted()
    }

    @Test
    fun aFileReplacedOrDeletedOnceAnInstallIsProvedKeepsItsCopyTheOnlyCopyOfItsBytes() = runTest {
        for (recovered in listOf(false, true)) for (deleted in listOf(false, true)) {
            val case = (if (deleted) "deleted" else "replaced") + if (recovered) " as a stopped install is finished" else ""
            val world = chunkedWorld()
            world.selectedProfiles = emptySet()
            if (recovered) crashInstall(world, FaultPoint.Linked(off("dir")))
            val chunk = "$offFolder/${off("000")}"
            world.store.afterStore = { manifest ->
                if ((manifest.pending as? PendingChange.Install)?.step == InstallStep.Releasing) {
                    if (deleted) world.files.remove(root, chunk) else world.files.putBytes(root, chunk, ByteArray(30) { 9 })
                }
            }
            val library = with(world) { library() }
            if (!recovered) installed(world, library)

            assertNull(world.store.manifest!!.pending, case)
            assertEquals(1, world.store.manifest!!.records.size, "$case: the install is committed")
            assertEquals(mapOf(copy(off("000")) to Sha256.image(chunk0)), world.parked().filterKeys { TemporaryInfix in it }, "$case: the chunk's copy, the only copy of its bytes, stays, and no other copy does")
            assertTrue(world.logged.any { copy(off("000")) in it }, "$case: and is reported left in place")
        }
    }

    @Test
    fun aRefusedReleasingSaveUndoesEveryLinkedFileAndKeepsForeignOnes() = runTest {
        val world = chunkedWorld()
        world.files.addAddonsEntry(root, "mine.vpk")
        world.store.refuse = { manifest -> "disk full".takeIf { (manifest.pending as? PendingChange.Install)?.step == InstallStep.Releasing } }
        val library = with(world) { library() }
        installed(world, library)

        assertEquals(LibraryProblem.ManifestSave("disk full"), failure(library))
        assertEquals(setOf("mine.vpk"), world.addons().keys)
        assertEquals(emptyMap(), world.parked())
        world.assertNothingCommitted()
    }

    @Test
    fun anUnreadableFileWhileReleasingWaitsWithoutDecidingAnything() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Remove("$offFolder/${copy(off("000"))}"))
        val journal = assertIs<PendingChange.Install>(world.store.manifest!!.pending)
        assertEquals(InstallStep.Releasing, journal.step)
        val afterCrash = world.parked()
        world.files.failFile(root, "$offFolder/${off("000")}", "permission denied")

        val restarted = with(world) { library() }
        assertNotNull((restarted.state.value.library as LibraryStatus.Ready).interrupted)
        assertEquals(journal, world.store.manifest!!.pending, "unknown evidence saves no rollback")
        assertEquals(afterCrash, world.parked())
    }

    @Test
    fun aLinkWhoseResultIsLostIsUndoneWithTheRest() = runTest {
        val world = chunkedWorld()
        world.files.addAddonsEntry(root, "mine.vpk")
        world.files.failing += FaultPoint.Linked(off("002"))
        val library = with(world) { library() }
        installed(world, library)

        assertIs<LibraryProblem.PlacementFailed>(failure(library))
        assertEquals(setOf("mine.vpk"), world.addons().keys)
        assertEquals(emptyMap(), world.parked(), "the link made but not confirmed is proven by its copy and undone")
        assertNull(world.store.manifest!!.pending)
    }

    @Test
    fun aLinkWhoseResultIsLostIsUndoneButNothingIsDeletedUntilItCanBeInspected() = runTest {
        val world = chunkedWorld()
        world.files.failing += FaultPoint.Linked(off("002"))
        world.files.failing += FaultPoint.Inspect(off("000"))
        val library = with(world) { library() }
        installed(world, library)

        assertIs<LibraryProblem.PlacementFailed>(failure(library))
        assertEquals(setOf(off("000"), off("002")) + copiesOf(offNames), world.parked().keys, "nothing is removed while the evidence cannot be read")
        assertEquals(InstallStep.Publishing, assertIs<PendingChange.Install>(world.store.manifest!!.pending).step)
        assertNotNull((library.state.value.library as LibraryStatus.Ready).interrupted)

        world.files.failing.clear()
        with(world) { library() }
        assertEquals(emptyMap(), world.parked(), "both links are proven by their copies and undone")
        assertNull(world.store.manifest!!.pending)
    }

    @Test
    fun aCopyThatWillNotBeReleasedIsReportedAndDoesNotBlockTheInstall() = runTest {
        val world = chunkedWorld()
        world.files.failing += FaultPoint.Remove("$offFolder/${copy(off("000"))}")
        val library = with(world) { library() }

        assertEquals(InstallStatus.Installed(enabled = true), installed(world, library))
        assertEquals(onNames, world.addons().keys)
        assertEquals(setOf(copy(off("000"))), world.parked().keys)
    }

    @Test
    fun aGameInfoAnotherProgramWroteDuringAnInterruptedEditIsKeptAndReported() = runTest {
        val world = World()
        val library = with(world) { library() }
        world.store.refuse = { manifest -> if (manifest.pending == null && manifest.receipt(root)?.written != null) throw SimulatedCrash(FaultPoint.ReplaceConfig) else null }
        assertFailsWith<SimulatedCrash> { library.launch(modded = true) {} }
        world.store.refuse = { null }
        val steams = vanillaInfo + "// steam\r\n".encodeToByteArray()
        world.files.writeGameInfo(root, steams)

        with(world) { library() }
        assertContentEquals(steams, world.files.gameInfo(root))
        assertNull(world.store.manifest!!.pending)
    }

    @Test
    fun aCrashDuringTheUndoConvergesOnALaterStart() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Publish(off("dir")))

        world.files.crashAt = FaultPoint.Remove("$offFolder/${off("000")}")
        val doomed = with(world) { crashableScope() }
        with(world) { library(doomed) }
        doomed.cancel()
        world.files.crashAt = null
        assertIs<PendingChange.Install>(world.store.manifest!!.pending)

        with(world) { library() }
        assertEquals(emptyMap(), world.parked())
        assertNull(world.store.manifest!!.pending)
        with(world) { library() }
        assertEquals(emptyMap(), world.parked())
        assertEquals(emptyList(), world.store.manifest!!.records)
    }

    private suspend fun seededIntact(world: World): ManifestRecord {
        val record = oneSetRecord(
            "seeded", root, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 77, 6, null)), "Seeded", null,
            ArchiveIdentity("/downloads/seeded.zip", 1, "a".repeat(64)), 9,
            listOf(SetFile(VpkPart.Directory, FileImage(1, "a".repeat(64)))),
        )
        world.store.save(LibraryManifest(records = listOf(record)))
        world.files.putFile(root, record.files.single().relativePath(record), 1, "a".repeat(64))
        return record
    }

    @Test
    fun aReapplyInterruptedAfterPlayingVanillaIsRecoveredAsModded() = runTest {
        val world = World()
        seededIntact(world)
        val library = with(world) { library() }
        assertNull(library.launch(modded = true) {})
        assertNull(library.launch(modded = false) {})
        assertFalse(world.store.manifest!!.receipt(root)!!.lines.mods)
        world.store.refuse = { manifest -> if (manifest.pending == null && manifest.receipt(root)!!.lines.mods) throw SimulatedCrash(FaultPoint.ReplaceConfig) else null }
        assertFailsWith<SimulatedCrash> { library.reapply() }
        world.store.refuse = { null }

        val restarted = with(world) { library() }
        assertNull(world.store.manifest!!.pending)
        assertTrue(world.store.manifest!!.receipt(root)!!.lines.mods)
        world.files.writeGameInfo(root, vanillaInfo)
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = false), restarted.state.value.loading, "a later rewrite is reported")
    }

    @Test
    fun aVanillaLaunchInterruptedAfterItsEditIsRecoveredAsVanilla() = runTest {
        val world = World()
        seededIntact(world)
        val library = with(world) { library() }
        assertNull(library.launch(modded = true) {})
        world.store.refuse = { manifest -> if (manifest.pending == null && manifest.receipt(root)!!.lines.mods == false) throw SimulatedCrash(FaultPoint.ReplaceConfig) else null }
        assertFailsWith<SimulatedCrash> { library.launch(modded = false) {} }
        world.store.refuse = { null }

        val restarted = with(world) { library() }
        assertNull(world.store.manifest!!.pending)
        assertFalse(world.store.manifest!!.receipt(root)!!.lines.mods)
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = true), restarted.state.value.loading, "playing vanilla is not a warning")
    }

    @Test
    fun anInstallInterruptedWhileDeadlockRunsIsUndoneBeforeItClosesAndTheModInstallsAgain() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Publish(off("dir")))

        world.processes.scan = ProcessScan.Seen(game = true)
        val restarted = with(world) { library() }
        assertNull((restarted.state.value.library as LibraryStatus.Ready).interrupted)
        assertEquals(emptyMap(), world.parked() + world.addons(), "undone in its off folder while the game runs")

        restarted.install(ivy.modId)
        runCurrent()
        assertEquals(world.store.manifest!!.records.single().files.map { it.name }.toSet(), world.parked().keys, "installed again, off while the game runs")
        world.processes.scan = ProcessScan.Seen()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(onNames, world.addons().keys, "and on once it closes")
    }

    @Test
    fun withdrawnUnknownAndFailedSourcesAreReportedNotInstalled() = runTest {
        val world = World()
        world.banana.listings = mapOf(ivy to FileListing.Withdrawn(Withdrawal.Trashed))
        val library = with(world) { library() }
        installed(world, library)
        assertEquals(LibraryProblem.Withdrawn(Withdrawal.Trashed), failure(library))

        library.dismiss(ivy.modId)
        world.banana.listings = mapOf(ivy to FileListing.Unavailable("HTTP 502"))
        installed(world, library)
        assertEquals(LibraryProblem.MetadataUnavailable("HTTP 502"), failure(library))

        library.dismiss(ivy.modId)
        world.offer(remote(4))
        world.downloader.results[4] = DownloadResult.Failed(DownloadProblem.SizeMismatch(1_000, 999))
        installed(world, library)
        assertEquals(LibraryProblem.Download(DownloadProblem.SizeMismatch(1_000, 999)), failure(library))
        assertEquals(emptyList(), world.store.manifest?.downloads.orEmpty())

        library.dismiss(ivy.modId)
        world.downloader.results.clear()
        world.downloader.serve(4, listOf("mod/pak01_dir.vpk" to single, "MOD/PAK01_DIR.VPK" to single))
        installed(world, library)
        assertEquals(LibraryProblem.ArchiveRefused(ArchiveRejection(RejectionReason.DuplicateName, "MOD/PAK01_DIR.VPK")), failure(library))

        library.dismiss(ivy.modId)
        world.downloader.serve(4, listOf("ivy_dir.vpk" to chunkedDir, "ivy_000.vpk" to chunk0))
        installed(world, library)
        assertEquals(LibraryProblem.NoCompleteSet(listOf(RejectedSet("ivy_dir.vpk", SetProblem.MissingChunks(listOf("ivy_002.vpk"))))), failure(library))
        assertEquals(emptyMap(), world.addons())
        assertEquals(emptySet(), world.files.stagingFolders)

        library.install("lilith")
        runCurrent()
        assertEquals(LibraryProblem.NotAGameBananaMod, failure(library, "lilith"))
        assertEquals(5, world.banana.calls, "one listing per install; a name that is not a submission asks GameBanana nothing")
    }

    @Test
    fun aSecondInstallOfTheSameModIsRefusedBeforeDownloading() = runTest {
        val world = World()
        world.serveOne()
        val library = with(world) { library() }
        installed(world, library)
        installed(world, library)
        assertEquals(LibraryProblem.AlreadyInstalled, failure(library))
        assertEquals(1, world.addons().size)
        assertEquals(listOf(4L), world.downloader.requested)
    }

    @Test
    fun theFirstInstallAddsHideoutsBlock() = runTest {
        val world = World()
        world.serveOne()
        val library = with(world) { library() }
        installed(world, library)

        val patched = world.files.gameInfo(root)!!
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(patched))
        assertContentEquals(vanillaInfo, withoutHideoutBlock(patched))
        assertEquals(GameConfigReceipt(root, Sha256.image(patched), HideoutLines(mods = true, customGames = false)), world.store.manifest!!.receipt(root))
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)
    }

    @Test
    fun anotherToolsLoadingIsLeftAloneAndAnUnreadableFileRefusesTheInstall() = runTest {
        val world = World(info = LoadsAddonsGameInfo)
        world.serveOne()
        val library = with(world) { library() }
        installed(world, library)
        assertContentEquals(LoadsAddonsGameInfo, world.files.gameInfo(root))
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Loads(LoadingOwner.AnotherTool), library.state.value.loading)

        val broken = World(info = "GameInfo { FileSystem {".encodeToByteArray())
        broken.serveOne()
        val refused = with(broken) { library() }
        assertEquals(InstallStatus.Installed(enabled = false), installed(broken, refused))
        assertEquals(ChoiceSync.Failed(LibraryProblem.GameInfo(GameInfoProblem.Unparseable)), refused.state.value.choiceSync, "it installs off, and turning it on pauses")
        assertEquals(emptyMap(), broken.addons())
    }

    @Test
    fun aRewriteWhileTheAppIsOpenIsCountedAndOneReapplyRestoresLoading() = runTest {
        val world = World()
        fun seeded(id: String, mod: Long, number: Int?) = oneSetRecord(
            id, root, InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, mod, 6, null)), "Seeded $mod", null,
            ArchiveIdentity("/downloads/$id.zip", 1, "a".repeat(64)), number,
            listOf(SetFile(VpkPart.Directory, FileImage(1, "a".repeat(64)))),
        )
        val off = seeded("off", 7, null)
        val damaged = seeded("damaged", 8, 8)
        val intact = seeded("intact", 9, 9)
        world.store.save(LibraryManifest(records = listOf(off, damaged, intact)))
        world.files.putFile(root, off.files.single().relativePath(off), 1, "a".repeat(64))
        world.files.putFile(root, intact.files.single().relativePath(intact), 1, "a".repeat(64))
        world.files.addAddonsEntry(root, "someone_elses_dir.vpk")
        world.serveOne()
        val library = with(world) { library() }
        installed(world, library)
        val patchedOnce = world.files.gameInfo(root)!!

        world.files.writeGameInfo(root, vanillaInfo)
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Off(notLoading = 2, vanilla = false), library.state.value.loading, "the new set and the intact one; not the off, damaged or foreign ones")
        assertTrue((library.state.value.loading as ModLoading.Off).alerts)

        assertNull(library.reapply())
        assertContentEquals(patchedOnce, world.files.gameInfo(root), "the same block, byte for byte")
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)
    }

    @Test
    fun playingVanillaRemovesHideoutsBlockAndReapplyPutsItBack() = runTest {
        val world = World()
        seededIntact(world)
        val library = with(world) { library() }
        assertNull(library.reapply())
        val patched = world.files.gameInfo(root)!!

        assertNull(library.launch(modded = false) {})
        assertContentEquals(vanillaInfo, world.files.gameInfo(root), "only Hideout's block goes")
        assertFalse(world.store.manifest!!.receipt(root)!!.lines.mods)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = true), library.state.value.loading)
        assertNull(library.launch(modded = false) {}, "playing vanilla again changes nothing")
        assertContentEquals(vanillaInfo, world.files.gameInfo(root))

        assertNull(library.reapply())
        assertContentEquals(patched, world.files.gameInfo(root))
        assertTrue(world.store.manifest!!.receipt(root)!!.lines.mods)
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)
    }

    @Test
    fun aRewriteNoWatcherNoticedIsStillCaughtByThePeriodicCheck() = runTest {
        val world = World()
        world.serveOne()
        val library = with(world) { library() }
        installed(world, library)
        advanceTimeBy(200.milliseconds)
        world.files.putBytes(root, InMemoryGameFiles.GameInfo, vanillaInfo)
        advanceTimeBy(1.seconds)
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading, "no event yet")
        advanceTimeBy(10.seconds)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = false), library.state.value.loading)
    }

    @Test
    fun anUnreadableGameInfoIsUnknownNotZero() = runTest {
        val world = World()
        world.serveOne()
        val library = with(world) { library() }
        installed(world, library)
        world.files.writeGameInfo(root, "SearchPaths {".encodeToByteArray())
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Unreadable(GameInfoProblem.Unparseable), library.state.value.loading)
    }

    @Test
    fun steamWritingDuringReapplyWins() = runTest {
        val world = World()
        world.serveOne()
        val library = with(world) { library() }
        installed(world, library)
        world.files.writeGameInfo(root, vanillaInfo)
        val steams = vanillaInfo + "// steam\r\n".encodeToByteArray()
        world.files.duringConfigReplace = { at -> world.files.putBytes(at, InMemoryGameFiles.GameInfo, steams) }
        assertIs<LibraryProblem.GameInfoConflict>(library.reapply())
        assertContentEquals(steams, world.files.gameInfo(root))
        assertNull(world.store.manifest!!.pending)
    }

    @Test
    fun playingModdedAddsLoadingAndPlayingVanillaRemovesOnlyHideoutsBlock() = runTest {
        val world = World()
        val library = with(world) { library() }
        assertNull(library.launch(modded = true) {})
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(world.files.gameInfo(root)!!))

        var launched: GameRoot? = null
        assertNull(library.launch(modded = false) { launched = it })
        assertEquals(root, launched)
        assertContentEquals(vanillaInfo, world.files.gameInfo(root))
        assertEquals(ModLoading.Off(notLoading = 0, vanilla = true), library.state.value.loading)

        val other = World(info = LoadsAddonsGameInfo)
        val otherLibrary = with(other) { library() }
        assertEquals(LibraryProblem.AnotherToolLoadsMods, otherLibrary.launch(modded = false) {})
        assertContentEquals(LoadsAddonsGameInfo, other.files.gameInfo(root))
    }

    @Test
    fun playingVanillaBesideAnotherToolsAddonsLineIsRefusedAndLeavesHideoutsBlock() = runTest {
        val hideout = (GameInfoPatch.withBlock(vanillaInfo, HideoutLines(mods = true, customGames = false), 0) as GameInfoEdit.Patched).bytes.decodeToString()
        val marker = hideout.lines().first { "// Hideout start" in it }
        val eol = if ("\r\n" in hideout) "\r\n" else "\n"
        val beside = hideout.replace(marker, marker.replace("// Hideout start", "Game citadel/addons") + eol + marker).encodeToByteArray()
        val world = World(info = beside)
        val library = with(world) { library() }
        var launched = false
        assertEquals(LibraryProblem.AnotherToolLoadsMods, library.launch(modded = false) { launched = true })
        assertFalse(launched)
        assertContentEquals(beside, world.files.gameInfo(root))
    }

    @Test
    fun aLaunchThatNeedsAnEditWaitsUntilDeadlockCloses() = runTest {
        val world = World()
        world.processes.scan = ProcessScan.Seen(game = true)
        val library = with(world) { library() }
        assertEquals(LibraryProblem.GameRunning, library.launch(modded = true) {})
        assertContentEquals(vanillaInfo, world.files.gameInfo(root))
    }

    private val offNames = setOf(off("dir"), off("000"), off("002"))

    private val onNames = setOf("pak01_dir.vpk", "pak01_000.vpk", "pak01_002.vpk")

    private fun copiesOf(names: Set<String>) = names.map { copy(it) }.toSet()

    @Test
    fun aChunkThatVanishesBeforeTheSetIsRecordedIsNotCommittedAndTheRestIsUndone() = runTest {
        val world = chunkedWorld()
        world.files.beforePublish = { at, name -> if (name == off("dir")) world.files.remove(at, "$offFolder/${off("000")}") }
        val library = with(world) { library() }
        installed(world, library)

        assertIs<LibraryProblem.PlacementFailed>(failure(library))
        assertEquals(emptyMap(), world.parked(), "the names still proven Hideout's and every copy are undone")
        world.assertNothingCommitted()
    }

    @Test
    fun aSameByteChunkPutInPlaceOfAPublishedOneSurvivesTheUndoOfALaterTakenName() = runTest {
        val world = chunkedWorld()
        val foreignDir = FakeVpk.directory(seed = 77)
        world.files.beforePublish = { at, name ->
            if (name == off("dir")) {
                world.files.putBytes(at, "$offFolder/${off("000")}", chunk0)
                world.files.putBytes(at, "$offFolder/${off("dir")}", foreignDir)
            }
        }
        val library = with(world) { library() }
        installed(world, library)

        assertEquals(LibraryProblem.NameTaken(off("dir")), failure(library))
        assertEquals(
            mapOf(off("000") to Sha256.image(chunk0), off("dir") to Sha256.image(foreignDir), off("002") to Sha256.image(chunk2)),
            world.parked(),
            "a directory at the set's name keeps the chunk Hideout published beside it",
        )
        world.assertNothingCommitted()
    }

    @Test
    fun aPublishedSetThatCannotBeInspectedWaitsAndIsCommittedOnceItCan() = runTest {
        val world = chunkedWorld()
        world.files.failing += FaultPoint.Inspect(off("000"))
        val library = with(world) { library() }
        installed(world, library)

        assertIs<LibraryProblem.Interrupted>(failure(library))
        assertEquals(InstallStep.Publishing, assertIs<PendingChange.Install>(world.store.manifest!!.pending).step)
        assertEquals(emptyList(), world.store.manifest!!.records)
        assertNotNull((library.state.value.library as LibraryStatus.Ready).interrupted)
        assertEquals(offNames + copiesOf(offNames), world.parked().keys, "nothing is removed or released while unproven")

        world.files.failing.clear()
        val restarted = with(world) { library() }
        assertEquals(onNames, world.addons().keys, "committed, then turned on")
        assertEquals(1, world.store.manifest!!.records.size)
        assertNull(world.store.manifest!!.pending)
        assertEquals(emptyMap(), (restarted.state.value.library as LibraryStatus.Ready).damaged)
    }

    @Test
    fun aPublishedFileThatCannotBeReadWaitsWithoutDecidingAnything() = runTest {
        val world = chunkedWorld()
        world.files.beforePublish = { at, name -> if (name == off("dir")) world.files.failFile(at, "$offFolder/${off("000")}", "permission denied") }
        val library = with(world) { library() }
        installed(world, library)

        assertIs<LibraryProblem.Interrupted>(failure(library))
        val journal = assertIs<PendingChange.Install>(world.store.manifest!!.pending)
        assertEquals(InstallStep.Publishing, journal.step)
        assertEquals(emptyList(), world.store.manifest!!.records)
        assertEquals(offNames + copiesOf(offNames), world.parked().keys)

        val restarted = with(world) { library() }
        assertEquals(journal, world.store.manifest!!.pending, "a restart decides nothing either")
        assertNotNull((restarted.state.value.library as LibraryStatus.Ready).interrupted)
        assertEquals(offNames + copiesOf(offNames), world.parked().keys)
    }

    @Test
    fun aRefusedSaveAfterGameInfoWasReplacedKeepsTheEditsJournalAndTheNextStartFinishesIt() = runTest {
        val world = World()
        world.serveOne()
        world.store.refuse = { manifest -> "disk full".takeIf { manifest.pending == null && manifest.receipt(root)?.written != null } }
        val library = with(world) { library() }

        assertEquals(InstallStatus.Installed(enabled = false), installed(world, library), "it installs off")
        assertEquals(ChoiceSync.Failed(LibraryProblem.ManifestSave("disk full")), library.state.value.choiceSync, "turning it on edits gameinfo.gi, whose receipt cannot be saved")
        assertEquals(emptyMap(), world.addons(), "nothing is placed")
        val patched = world.files.gameInfo(root)!!
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(patched))
        val journal = assertIs<PendingChange.ConfigEdit>(world.store.manifest!!.pending)
        assertEquals(Sha256.image(patched), journal.to)

        world.store.refuse = { null }
        val restarted = with(world) { library() }
        runCurrent()
        assertNull(world.store.manifest!!.pending)
        assertEquals(GameConfigReceipt(root, Sha256.image(patched), HideoutLines(mods = true, customGames = false)), world.store.manifest!!.receipt(root))
        assertNull((restarted.state.value.library as LibraryStatus.Ready).interrupted)
        assertEquals(setOf("pak01_dir.vpk"), world.addons().keys)
    }

    @Test
    fun anInterruptedAutoexecEditSettlesWithoutTouchingTheGameInfoReceipt() = runTest {
        val commands = "fps_max 240\n".encodeToByteArray()
        val receipt = GameConfigReceipt(root, Sha256.image(vanillaInfo), HideoutLines(mods = false, customGames = false))
        for ((written, settled) in listOf(true to "applied", false to "undone")) {
            val world = World()
            world.store.save(LibraryManifest(gameConfigs = listOf(receipt), pending = PendingChange.ConfigEdit(root, ConfigTarget.Autoexec, "a1", null, Sha256.image(commands))))
            if (written) world.files.putBytes(root, "game/citadel/cfg/autoexec.cfg", commands)
            val library = with(world) { library() }

            assertNull(world.store.manifest!!.pending, settled)
            assertEquals(listOf(receipt), world.store.manifest!!.gameConfigs, settled)
            assertNull((library.state.value.library as LibraryStatus.Ready).interrupted, settled)
        }
    }

    @Test
    fun aLaunchWhoseEditCannotBeRecordedIsRefusedAndTheEditIsRecoveredLater() = runTest {
        val world = World()
        seededIntact(world)
        val library = with(world) { library() }
        world.store.refuse = { manifest -> "disk full".takeIf { manifest.pending == null && manifest.receipt(root)?.written != null } }

        assertEquals(LibraryProblem.ManifestSave("disk full"), library.launch(modded = true) {})
        val patched = world.files.gameInfo(root)!!
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(patched))
        assertEquals(Sha256.image(patched), assertIs<PendingChange.ConfigEdit>(world.store.manifest!!.pending).to)
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)

        world.store.refuse = { null }
        with(world) { library() }
        assertNull(world.store.manifest!!.pending)
        assertEquals(Sha256.image(patched), world.store.manifest!!.receipt(root)!!.written)
        assertTrue(world.store.manifest!!.receipt(root)!!.lines.mods)
    }

    @Test
    fun aVanillaLaunchWhoseIntentCannotBeSavedIsRefused() = runTest {
        val world = World()
        seededIntact(world)
        val library = with(world) { library() }
        assertNull(library.launch(modded = true) {})
        world.files.writeGameInfo(root, vanillaInfo)
        world.store.refuse = { manifest -> "disk full".takeIf { manifest.receipt(root)?.lines?.mods == false } }

        assertEquals(LibraryProblem.ManifestSave("disk full"), library.launch(modded = false) {})
        assertTrue(world.store.manifest!!.receipt(root)!!.lines.mods)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = false), library.state.value.loading, "the set still counts as not loading, not as played vanilla")
    }

    @Test
    fun aStreamOfChangeEventsDoesNotHoldOffThePeriodicCheck() = runTest {
        val world = World()
        seededIntact(world)
        world.config = object : GameConfig by world.files {
            override fun changes(root: GameRoot, path: CitadelPath): Flow<Unit> = flow {
                while (true) {
                    emit(Unit)
                    delay(50.milliseconds)
                }
            }
        }
        val library = with(world) { library() }
        assertNull(library.launch(modded = true) {})
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)

        world.files.putBytes(root, InMemoryGameFiles.GameInfo, vanillaInfo)
        advanceTimeBy(11.seconds)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = false), library.state.value.loading)
    }

    @Test
    fun anOnSetWhoseFilesCannotBeReadIsNotCountedAsStoppedLoading() = runTest {
        val world = World()
        val record = seededIntact(world)
        world.files.failFile(root, record.files.single().relativePath(record), "permission denied")
        val library = with(world) { library() }
        assertEquals(setOf("seeded"), (library.state.value.library as LibraryStatus.Ready).unchecked)
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Off(notLoading = 0, vanilla = false), library.state.value.loading)
    }

    @Test
    fun aSameByteFileAtThePublishedSingleNameIsNeitherAdoptedNorDeleted() = runTest {
        val world = World(info = LoadsAddonsGameInfo)
        world.serveOne()
        world.files.afterPublish = { at, name -> world.files.putBytes(at, "$offFolder/$name", single) }
        val library = with(world) { library() }
        installed(world, library)

        assertIs<LibraryProblem.PlacementFailed>(failure(library))
        assertEquals(setOf(off("dir", singleStem)), world.parked().keys, "the independent file stays and the copy goes")
        assertContentEquals(single, world.files.bytes(root, "$offFolder/${off("dir", singleStem)}"))
        world.assertNothingCommitted()
    }

    @Test
    fun theSingleNameChangedInPlaceAfterItIsPublishedIsKeptAndNothingIsCommitted() = runTest {
        val world = World(info = LoadsAddonsGameInfo)
        world.serveOne()
        val sentinel = "changed through the published name".encodeToByteArray()
        world.files.afterPublish = { at, name -> world.files.rewrite(at, "$offFolder/$name", sentinel) }
        val library = with(world) { library() }
        installed(world, library)

        assertIs<OperationStage.Failed>(question(library))
        assertEquals(setOf(off("dir", singleStem)), world.parked().keys)
        assertContentEquals(sentinel, world.files.bytes(root, "$offFolder/${off("dir", singleStem)}"))
        world.assertNothingCommitted()
    }

    private val other = SubmissionRef(SubmissionKind.Mod, 551_201)

    private fun World.offerOther(vararg files: RemoteFile) {
        banana.listings = banana.listings + (other to FileListing.Available("Lilith, the Hell Warden", files.toList()))
    }

    @Test
    fun everyQuestionHasItsOwnIncreasingIdAndAnAnswerMovesOnlyItsOwnInstall() = runTest {
        val world = World()
        world.offer(remote(1), remote(2))
        world.offerOther(remote(5), remote(6))
        world.downloader.serve(2, listOf("a.vpk" to single))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()
        library.install(other.modId)
        runCurrent()

        val first = assertIs<OperationStage.ChoosingFile>(question(library, ivy.modId))
        val second = assertIs<OperationStage.ChoosingFile>(question(library, other.modId))
        assertTrue(second.id.value > first.id.value, "questions are numbered in the order they were asked")
        assertFalse(library.answer(first.id, ChoiceAnswer.Files(setOf(5), setOf(ProfileId("main")))), "a file the other install offered is not offered here")
        assertTrue(library.answer(first.id, ChoiceAnswer.Files(setOf(2), setOf(ProfileId("main")))))
        runCurrent()
        assertEquals(1, world.store.manifest!!.records.size)
        assertEquals(second, question(library, other.modId), "the other install still waits on its own question")
    }

    @Test
    fun staleWrongKindUnofferedAndRepeatedAnswersAreRefusedAndChangeNothing() = runTest {
        val world = World()
        world.offer(remote(1), remote(2))
        world.downloader.serve(2, listOf("Blue/pak01_dir.vpk" to single, "Red/pak01_dir.vpk" to FakeVpk.directory(seed = 5)))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()
        val file = assertIs<OperationStage.ChoosingFile>(question(library))

        assertFalse(library.answer(ChoiceId(file.id.value + 100), ChoiceAnswer.Files(setOf(2), setOf(ProfileId("main")))), "no such question")
        assertFalse(library.answer(file.id, ChoiceAnswer.Sets(listOf("Blue/pak01_dir.vpk"), setOf(ProfileId("main")))), "a set does not answer a file question")
        assertFalse(library.answer(file.id, ChoiceAnswer.Files(setOf(3), setOf(ProfileId("main")))), "file 3 was never offered")
        assertEquals(file, question(library), "a refused answer leaves the question open")

        assertTrue(library.answer(file.id, ChoiceAnswer.Files(setOf(2), setOf(ProfileId("main")))))
        assertEquals(OperationStage.Preparing, question(library), "accepting leaves the waiting stage at once, before the install resumes")
        assertFalse(library.answer(file.id, ChoiceAnswer.Files(setOf(2), setOf(ProfileId("main")))), "a second Enter answers nothing")
        assertFalse(library.answer(file.id, ChoiceAnswer.Cancel), "a late cancel answers nothing")
        runCurrent()

        val set = assertIs<OperationStage.ChoosingSet>(question(library))
        assertTrue(set.id.value > file.id.value)
        assertFalse(library.answer(file.id, ChoiceAnswer.Sets(listOf("Red/pak01_dir.vpk"), setOf(ProfileId("main")))), "the file question's id cannot answer the set question")
        assertFalse(library.answer(set.id, ChoiceAnswer.Files(setOf(2), setOf(ProfileId("main")))), "a file does not answer a set question")
        assertFalse(library.answer(set.id, ChoiceAnswer.Sets(listOf("Green/pak01_dir.vpk"), setOf(ProfileId("main")))), "that set was never offered")
        assertEquals(set, question(library))
        assertEquals(listOf(2L), world.downloader.requested)
        assertEquals(emptyMap(), world.addons())

        assertTrue(library.answer(set.id, ChoiceAnswer.Sets(listOf("Red/pak01_dir.vpk"), setOf(ProfileId("main")))))
        assertEquals(OperationStage.Preparing, question(library), "still cancellable until the install itself says it places files")
        runCurrent()
        assertEquals(1, world.store.manifest!!.records.size)
    }

    @Test
    fun dismissingClearsOnlyFailuresAndNeverCancelsAQuestion() = runTest {
        val world = World()
        world.offer(remote(1), remote(2))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()
        val waiting = question(library)
        library.dismiss(ivy.modId)
        runCurrent()
        assertEquals(waiting, question(library))
        assertEquals(InstallStatus.NeedsChoice, library.status(ivy.modId).first())
    }

    @Test
    fun withAskingOffTheMostDownloadedFileInstallsAndTiesGoToTheNewestThenTheLowestId() = runTest {
        val world = World()
        world.preferences.setAskWhichVersion(false)
        val newer = remote(3, downloads = 50).copy(addedAt = 1_777_800_000)
        world.offer(remote(1, downloads = 50), remote(2, downloads = 7), newer, remote(4, downloads = 50).copy(addedAt = 1_777_800_000))
        world.downloader.serve(3, listOf("a.vpk" to single))
        val library = with(world) { library() }
        assertEquals(InstallStatus.Installed(enabled = true), installed(world, library))
        assertEquals(listOf(3L), world.downloader.requested)
    }

    @Test
    fun anOffInstallKeepsAChoiceToPlayVanilla() = runTest {
        val world = World()
        seededIntact(world)
        val library = with(world) { library() }
        assertNull(library.launch(modded = true) {})
        assertNull(library.launch(modded = false) {})
        val vanilla = world.files.gameInfo(root)!!
        val receipt = world.store.manifest!!.receipt(root)
        assertFalse(receipt!!.lines.mods)
        world.selectedProfiles = emptySet()
        world.serveOne()

        assertEquals(InstallStatus.Installed(enabled = false), installed(world, library))
        assertContentEquals(vanilla, world.files.gameInfo(root))
        assertEquals(receipt, world.store.manifest!!.receipt(root))
        advanceTimeBy(200.milliseconds)
        assertEquals(ModLoading.Off(notLoading = 1, vanilla = true), library.state.value.loading, "only the seeded on set counts")
    }

    @Test
    fun operationRetainsAuthoritativeProfilesAcrossBothChoicesAndFailure() = runTest {
        val world = World()
        world.offer(remote(1), remote(2))
        world.downloader.serve(1, listOf("Blue/pak01_dir.vpk" to single, "Red/pak01_dir.vpk" to FakeVpk.directory(seed = 5)))
        val library = with(world) { library() }
        library.install(ivy.modId)
        assertEquals(setOf(ProfileId("main")), library.state.value.operations.getValue(ivy.modId).profiles)
        runCurrent()
        val file = assertIs<OperationStage.ChoosingFile>(question(library))
        assertFalse(library.answer(file.id, ChoiceAnswer.Files(setOf(99), emptySet())))
        assertEquals(setOf(ProfileId("main")), library.state.value.operations.getValue(ivy.modId).profiles)
        val selected = mutableSetOf<ProfileId>()
        assertTrue(library.answer(file.id, ChoiceAnswer.Files(setOf(1), selected)))
        selected += ProfileId("main")
        assertEquals(emptySet(), library.state.value.operations.getValue(ivy.modId).profiles)
        runCurrent()
        val set = assertIs<OperationStage.ChoosingSet>(question(library))
        assertEquals(emptySet(), library.state.value.operations.getValue(ivy.modId).profiles)
        assertTrue(library.answer(set.id, ChoiceAnswer.Sets(listOf("Blue/pak01_dir.vpk"), setOf(ProfileId("main")))))
        assertEquals(setOf(ProfileId("main")), library.state.value.operations.getValue(ivy.modId).profiles)
        world.files.failing += FaultPoint.Publish(off("dir", stem(1, "Blue/pak01_dir.vpk")))
        runCurrent()
        assertIs<OperationStage.Failed>(question(library))
        assertEquals(setOf(ProfileId("main")), library.state.value.operations.getValue(ivy.modId).profiles)
    }

    @Test
    fun theAnswersSwitchDecidesPlacementEitherWayAndCarriesIntoTheSetQuestion() = runTest {
        val world = World()
        world.offer(remote(1), remote(2))
        world.downloader.serve(1, listOf("Blue/pak01_dir.vpk" to single, "Red/pak01_dir.vpk" to FakeVpk.directory(seed = 5)))
        val library = with(world) { library() }
        library.install(ivy.modId)
        runCurrent()
        val file = assertIs<OperationStage.ChoosingFile>(question(library))
        assertTrue(file.profiles.isNotEmpty())
        assertTrue(library.answer(file.id, ChoiceAnswer.Files(setOf(1), emptySet())))
        runCurrent()
        val set = assertIs<OperationStage.ChoosingSet>(question(library))
        assertFalse(set.profiles.isNotEmpty(), "the set question starts from the file answer's switch, not from Settings")
        assertTrue(library.answer(set.id, ChoiceAnswer.Sets(listOf("Blue/pak01_dir.vpk"), emptySet())))
        runCurrent()
        assertEquals(setOf(off("dir", stem(1, "Blue/pak01_dir.vpk"))), world.parked().keys)
        assertEquals(emptyMap(), world.addons())
        assertContentEquals(vanillaInfo, world.files.gameInfo(root))

        val second = World()
        second.selectedProfiles = emptySet()
        second.offer(remote(1), remote(2))
        second.downloader.serve(2, listOf("a.vpk" to single))
        val onAnyway = with(second) { library() }
        onAnyway.install(InstallRequest(InstallInput.Remote(ivy), second.selectedProfiles))
        runCurrent()
        val asked = assertIs<OperationStage.ChoosingFile>(question(onAnyway))
        assertFalse(asked.profiles.isNotEmpty())
        assertTrue(onAnyway.answer(asked.id, ChoiceAnswer.Files(setOf(2), setOf(ProfileId("main")))))
        runCurrent()
        assertEquals(setOf("pak01_dir.vpk"), second.addons().keys)
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(second.files.gameInfo(root)!!), "an on install still makes the game load mods")
    }

    @Test
    fun aCrashAfterTheLastOffLinkIsCommittedOffOnTheNextStart() = runTest {
        val world = chunkedWorld()
        world.selectedProfiles = emptySet()
        crashInstall(world, FaultPoint.Linked(off("dir")))

        val restarted = with(world) { library() }
        assertEquals(offNames, world.parked().keys)
        assertEquals(emptyMap(), world.addons())
        val record = world.store.manifest!!.records.single()
        assertFalse(record.enabled)
        assertEquals(emptyMap(), (restarted.state.value.library as LibraryStatus.Ready).damaged)
    }

    @Test
    fun anInstallWhoseCommitLandsButCannotBeReadBackIsNeverRolledBack() = runTest {
        val world = chunkedWorld()
        var thrown = false
        world.store.afterStore = { manifest ->
            if (!thrown && manifest.pending == null && manifest.records.isNotEmpty()) {
                thrown = true
                world.disk.unreadableLoads = 1
                error("the disk did not confirm the write")
            }
        }
        val library = with(world) { library() }
        installed(world, library)

        assertTrue(thrown)
        assertIs<LibraryProblem.Interrupted>(failure(library))
        val committed = assertNotNull(world.store.manifest)
        val record = committed.records.single()
        assertNull(committed.pending)
        assertEquals(committed, world.disk.asked.last(), "nothing was asked of the store after the commit")
        assertTrue(world.store.saved.none { (it.pending as? PendingChange.Install)?.step is InstallStep.RollingBack }, "no rollback was journaled")
        assertEquals(record.files.associate { it.name to it.image }, world.parked())

        assertNull(library.reapply(), "the next change reads the store before anything else")
        val ready = assertIs<LibraryStatus.Ready>(library.state.value.library)
        assertEquals(listOf(record), ready.records)
        assertEquals(emptyMap(), ready.damaged)
        assertEquals(emptySet(), ready.unchecked)
        assertNull(ready.interrupted)
        assertEquals(committed, world.disk.asked.last())
        assertEquals(record.files.associate { it.name to it.image }, world.parked())
    }

    @Test
    fun aThrownSaveIsARefusalOnlyWhenTheStoreStillHoldsExactlyWhatItHeldBefore() = runTest {
        val fresh = chunkedWorld()
        fresh.store.refuse = { throw IllegalStateException("the encoder failed") }
        val first = with(fresh) { library() }
        installed(fresh, first)
        assertEquals(LibraryProblem.ManifestSave("the encoder failed"), failure(first), "a store read as empty and still empty refused it")
        assertNull(fresh.store.manifest)
        assertEquals(emptyMap(), fresh.parked())

        val emptied = World(info = LoadsAddonsGameInfo, initial = LibraryManifest())
        emptied.serveChunked()
        emptied.store.refuse = {
            emptied.disk.vanished = true
            throw IllegalStateException("the encoder failed")
        }
        val second = with(emptied) { library() }
        installed(emptied, second)
        assertIs<LibraryProblem.Interrupted>(failure(second), "a store that held a manifest and now reads as nothing is unknown, not refused")
        assertEquals(emptyMap(), emptied.parked())
        assertEquals(1, emptied.disk.asked.size)
    }

    @Test
    fun aReleasingSaveThatThrowsBeforeLandingIsStillARefusalAndIsUndone() = runTest {
        val world = chunkedWorld()
        world.store.refuse = { manifest -> if ((manifest.pending as? PendingChange.Install)?.step == InstallStep.Releasing) throw IllegalStateException("the encoder failed") else null }
        val library = with(world) { library() }
        installed(world, library)
        assertEquals(LibraryProblem.ManifestSave("the encoder failed"), failure(library))
        assertEquals(emptyMap(), world.parked())
        world.assertNothingCommitted()
    }

    @Test
    fun undoingAnInstallKeepsEveryChunkBesideAPublishedDirectoryThatChanged() = runTest {
        val world = chunkedWorld()
        val sentinel = ByteArray(chunkedDir.size) { 9 }
        world.files.afterPublish = { at, name -> if (name == off("dir")) world.files.rewrite(at, "$offFolder/${off("dir")}", sentinel) }
        val library = with(world) { library() }
        installed(world, library)

        assertIs<LibraryProblem.PlacementFailed>(failure(library))
        assertEquals(mapOf(off("dir") to Sha256.image(sentinel), off("000") to Sha256.image(chunk0), off("002") to Sha256.image(chunk2)), world.parked())
        world.assertNothingCommitted()
    }

    @Test
    fun aRestartUndoingAnInstallKeepsEveryChunkBesideAPublishedDirectoryThatChanged() = runTest {
        val world = chunkedWorld()
        crashInstall(world, FaultPoint.Linked(off("dir")))
        val sentinel = ByteArray(chunkedDir.size) { 9 }
        world.files.rewrite(root, "$offFolder/${off("dir")}", sentinel)

        val restarted = with(world) { library() }
        assertEquals(mapOf(off("dir") to Sha256.image(sentinel), off("000") to Sha256.image(chunk0), off("002") to Sha256.image(chunk2)), world.parked())
        world.assertNothingCommitted()
        assertNull((restarted.state.value.library as LibraryStatus.Ready).interrupted)
    }
}
