package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.HeldFile
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LeanBackupsTest {
    private val root = GameRoot("/games/Deadlock")
    private val lilith = SubmissionRef(SubmissionKind.Mod, 551_201)

    private val dir = FakeVpk.directory(embeddedBytes = 4, chunkReads = mapOf(0 to 30L), seed = 1)
    private val chunk0 = ByteArray(30) { 7 }
    private val cabezas = FakeVpk.directory(seed = 3)
    private val skinBytes = FakeVpk.directory(seed = 45)

    private val defaultEntries = listOf("Default/lilith_dir.vpk" to dir, "Default/lilith_000.vpk" to chunk0)
    private val cabezasEntries = listOf("Cabezas/pak01_dir.vpk" to cabezas)

    private fun md5(fileId: Long) = fileId.toString().padStart(32, '0')

    private fun identity(fileId: Long) = SubmissionIdentity(SubmissionKind.Mod, lilith.id, fileId, md5(fileId))

    private fun archive(fileId: Long, name: String, entries: List<Pair<String, ByteArray>>) =
        ArchiveIdentity("/data/hideout/downloads/mod-551201/$fileId/$name", 1_000, sha256Hex(entries.flatMap { it.second.asIterable() }.toByteArray()))

    private val defaultArchive = archive(101, "lilith_hell_warden.zip", defaultEntries)
    private val cabezasArchive = archive(103, "lilith_werewolf_cabezas.zip", cabezasEntries)

    private fun remote(id: Long, name: String) = RemoteFile(id, name, 1_000, md5(id), null, 10, 1, "https://gamebanana.com/dl/$id")

    private val listed = FileListing.Available("Lilith, the Hell Warden", listOf(remote(101, "lilith_hell_warden.zip"), remote(103, "lilith_werewolf_cabezas.zip")))

    private val lilithRecord = ManifestRecord(
        "lilith", root, "Lilith, the Hell Warden", placedAt(1),
        listOf(
            InstalledSet(
                InstalledSource.Remote(identity(101)), "lilith_hell_warden.zip", "Default", SetOrigin.Downloaded(defaultArchive), VersionPosition(1, 2), "Default/lilith_dir.vpk",
                listOf(owned(VpkPart.Directory, dir), owned(VpkPart.Chunk(0), chunk0)),
            ),
            InstalledSet(
                InstalledSource.Remote(identity(103)), "lilith_werewolf_cabezas.zip", "Cabezas", SetOrigin.Downloaded(cabezasArchive), VersionPosition(2, 2), "Cabezas/pak01_dir.vpk",
                listOf(owned(VpkPart.Directory, cabezas)),
            ),
        ),
    )

    private val skinRecord = ManifestRecord(
        "skin", root, "Local skin", placedAt(5),
        listOf(InstalledSet(InstalledSource.Local("skin", "skin.vpk"), null, null, SetOrigin.Imported(FileImage(1, "a".repeat(64))), null, "skin.vpk", listOf(owned(VpkPart.Directory, skinBytes)))),
    )

    private inner class World {
        val files = InMemoryGameFiles().apply {
            putBytes(root, InMemoryGameFiles.GameInfo, LoadsAddonsGameInfo)
            putBytes(root, InMemoryGameFiles.Autoexec, "fps_max 240\n".encodeToByteArray())
            for (record in listOf(lilithRecord, skinRecord)) record.files.zip(if (record == skinRecord) listOf(skinBytes) else listOf(dir, chunk0, cabezas)) { file, bytes -> putBytes(root, file.relativePath(record), bytes) }
            putArchive(defaultArchive.path, defaultEntries, FileImage(defaultArchive.sizeBytes, defaultArchive.sha256))
            putArchive(cabezasArchive.path, cabezasEntries, FileImage(cabezasArchive.sizeBytes, cabezasArchive.sha256))
        }
        val store = InMemoryLibraryStore(
            LibraryManifest(
                records = listOf(lilithRecord, skinRecord),
                downloads = listOf(OwnedDownload(identity(101), defaultArchive), OwnedDownload(identity(103), cabezasArchive)),
                profiles = ProfileBook.main(listOf(lilithRecord, skinRecord)),
            ),
        )
        val banana = FakeGameBanana(mapOf(lilith to listed))
        val downloader = FakeDownloader(files).apply {
            serve(101, defaultEntries)
            serve(103, cabezasEntries)
        }
        private var nextId = 0

        fun TestScope.library(scope: CoroutineScope = backgroundScope): ModLibrary = started(
            ModLibrary(
                FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, files,
                InstallPorts(banana, downloader, files, files, FakeProcesses(), backups = files.backups), scope,
                ids = { "id${++nextId}" },
                watch = testWatchTiming,
            ),
        )

        fun TestScope.seen() {
            val once = crashableScope()
            library(once)
            once.cancel()
        }

        fun manifest() = assertNotNull(store.manifest)

        fun stored(image: FileImage): Boolean = files.identity(root, "${InMemoryGameFiles.Stored}/${image.sha256}") != null

        fun autoexec() = files.bytes(root, InMemoryGameFiles.Autoexec)?.decodeToString()
    }

    private val lilithImages = lilithRecord.sets.flatMap { it.files }.map { it.image }
    private val skinImage = skinRecord.sets.single().files.single().image

    private fun ModLibrary.ready(): LibraryStatus.Ready = assertIs<LibraryStatus.Ready>(state.value.library)

    private fun TestScope.uninstall(library: ModLibrary, installId: String, deleteDownload: Boolean = false) {
        library.uninstall(assertNotNull(library.uninstallConfirmation(installId)), deleteDownload)
        runCurrent()
        assertTrue(library.ready().records.none { it.installId == installId })
    }

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

    @Test
    fun aBackupBeforeAGameSettingsChangeHoldsOnlyTheSettingsAndKeepsNoModsFiles() = runTest {
        val world = World()
        val library = with(world) { library() }
        library.writeAutoexec { it.withLines(listOf("volume 0.5")) }
        runCurrent()
        val backup = library.state.value.backups.single()
        assertEquals(BackupReason.BeforeGameSettings, backup.reason)
        assertEquals(emptyList(), world.files.backups.read(root, backup.id).records, "it holds no mod")

        uninstall(library, "skin")
        assertTrue(!world.stored(skinImage), "a local mod's file goes from the store once nothing but a settings backup could name it")

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals("fps_max 240\n", world.autoexec())
        assertEquals(listOf("lilith"), world.manifest().records.map { it.installId }, "restoring it leaves the mods as they are")
        assertEquals(emptyMap(), library.ready().damaged)
    }

    @Test
    fun anUninstalledGameBananaModsFilesLeaveTheStoreWhileALocalModsStay() = runTest {
        val world = World()
        val library = with(world) { library() }
        backUp(library)
        assertTrue(lilithImages.all(world::stored) && world.stored(skinImage))

        uninstall(library, "lilith")
        uninstall(library, "skin")
        assertEquals(emptyList(), lilithImages.filter(world::stored), "GameBanana gives those again")
        assertTrue(world.stored(skinImage), "the backup holds the only copy of a local mod's file")
    }

    @Test
    fun backupsMadeBeforeThisFreeTheSpaceOfUninstalledModsTheNextTimeHideoutStarts() = runTest {
        val world = World()
        val held = world.manifest().backupOf(root)
        for (record in held.records) for (file in record.files) world.files.backups.keep(GameEntry(root, record.setFolder(), file.name), file.image)
        world.files.backups.publish(root, BackupInfo(BackupId("manual"), 1, BackupReason.Manual, mods = 2), held, emptyMap())
        repeat(4) { world.files.backups.publish(root, BackupInfo(BackupId("autoexec$it"), 2L + it, BackupReason.BeforeGameSettings, mods = 2), held, mapOf(HeldFile.Autoexec to ByteArray(0))) }
        for (record in held.records) for (file in record.files) world.files.remove(root, file.relativePath(record))
        world.store.save(LibraryManifest())
        assertTrue(lilithImages.all(world::stored) && world.stored(skinImage))

        with(world) { library() }
        assertEquals(emptyList(), lilithImages.filter(world::stored), "no backup keeps a GameBanana file once it is out of the game")
        assertTrue(world.stored(skinImage), "the manual backup still keeps the local mod")
    }

    @Test
    fun theOriginalOfAnEditedFileStaysStoredAndRestoreOriginalPutsItBackWithNothingDownloaded() = runTest {
        val world = World()
        with(world) { seen() }
        val path = lilithRecord.files.first().relativePath(lilithRecord)
        val original = world.files.identity(root, path)
        world.files.putBytes(root, path, FakeVpk.directory(seed = 9))
        val library = with(world) { library() }
        backUp(library)
        uninstall(library, "skin")
        assertTrue(lilithImages.all(world::stored), "the Library still names the original")

        library.reinstall("lilith")
        runCurrent()
        assertEquals(original, world.files.identity(root, path))
        assertEquals(lilithRecord, world.manifest().records.single { it.installId == "lilith" })
        assertEquals(emptyList(), world.downloader.requested)
        assertEquals(0, world.banana.calls)
    }

    @Test
    fun aRestoreGetsAGameBananaModTheStoreNoLongerHoldsFromItsKeptDownload() = runTest {
        val world = World()
        val library = with(world) { library() }
        val backup = backUp(library)
        uninstall(library, "lilith")
        assertEquals(emptyList(), lilithImages.filter(world::stored))

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals(lilithRecord.sets, world.manifest().records.single { it.installId == "lilith" }.sets)
        assertEquals(emptyMap(), library.ready().damaged)
        assertEquals(emptyList(), world.downloader.requested, "the kept download holds it")
        assertEquals(0, world.banana.calls)
    }

    @Test
    fun aRestoreDownloadsAGameBananaModAgainWhenNoDownloadIsKept() = runTest {
        val world = World()
        val library = with(world) { library() }
        val backup = backUp(library)
        uninstall(library, "lilith", deleteDownload = true)
        assertEquals(emptyList(), lilithImages.filter(world::stored))

        assertEquals(BackupStage.Done, restore(library, backup))
        assertEquals(lilithRecord.sets.map { it.files }, world.manifest().records.single { it.installId == "lilith" }.sets.map { it.files })
        assertEquals(emptyMap(), library.ready().damaged)
        assertEquals(listOf(101L, 103L), world.downloader.requested)
        assertNull(library.state.value.operations[lilith.modId])
    }

    @Test
    fun aRestoreThatCannotGetAGameBananaModBackSaysWhyForThatModAndRestoresTheRest() = runTest {
        val failures = listOf(
            emptyMap<SubmissionRef, FileListing>() to LibraryProblem.MetadataUnavailable("no answer for mod-551201"),
            mapOf(lilith to FileListing.Available("Lilith, the Hell Warden", listOf(remote(103, "lilith_werewolf_cabezas.zip")))) to LibraryProblem.FileNotOffered,
        )
        for ((listings, problem) in failures) {
            val world = World()
            val library = with(world) { library() }
            val backup = backUp(library)
            uninstall(library, "lilith", deleteDownload = true)
            uninstall(library, "skin")
            world.banana.listings = listings

            assertEquals(BackupStage.Incomplete(listOf(MissingMod("Lilith, the Hell Warden", problem))), restore(library, backup))
            assertEquals(setOf("lilith", "skin"), world.manifest().records.map { it.installId }.toSet(), "$problem")
            assertEquals(mapOf("lilith" to FilesDamage.Missing), library.ready().damaged, "the local mod is back whole, and Lilith is left for Reinstall")
            assertEquals(lilithRecord.reinstallFrom, ReinstallFrom.Source)
        }
    }
}
