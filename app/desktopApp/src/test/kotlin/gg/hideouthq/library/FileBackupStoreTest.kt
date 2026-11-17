package gg.hideouthq.library

import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.HeldFile
import gg.hideouthq.core.data.PlaceResult
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.library.BackupCodec
import gg.hideouthq.core.library.BackupId
import gg.hideouthq.core.library.BackupInfo
import gg.hideouthq.core.library.BackupReason
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ManifestSchemaVersion
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FileBackupStoreTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "backup-store-tests")), "home").toRealPath()
    private val root = GameRoot(home.resolve("Deadlock").toString())
    private val addons = home.resolve("Deadlock/game/citadel/addons").createDirectories()
    private val backups = home.resolve("Deadlock/game/citadel/hideout_backups")
    private val files = backups.resolve("files")
    private val originals = home.resolve("data/originals")
    private val store = FileBackupStore(originals)

    private val dir = "the directory".encodeToByteArray()
    private val chunk = "the chunk".encodeToByteArray()

    @AfterTest
    fun cleanUp() = deleteTree(home)

    private fun entry(name: String) = GameEntry(root, SetFolder.Addons, name)

    private fun key(path: Path): Any = Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()

    private fun names(folder: Path): Set<String> = Files.list(folder).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }

    private fun info(id: String, createdAt: Long = 1) = BackupInfo(BackupId(id), createdAt, BackupReason.Manual, mods = 0)

    private suspend fun keepRead(name: String, image: FileImage) = store.keep(entry(name), image, store.modified(entry(name)))

    @Test
    fun aGameFileIsStoredAsTheSameFileOnceAndOnlyWhatItShouldHoldIsStored() = runBlocking<Unit> {
        addons.resolve("pak01_dir.vpk").writeBytes(dir)
        val image = Sha256.image(dir)
        store.keep(entry("pak01_dir.vpk"), image)
        assertEquals(key(addons.resolve("pak01_dir.vpk")), key(files.resolve(image.sha256)), "the stored copy is a hard link")
        store.keep(entry("pak01_dir.vpk"), image)
        assertEquals(setOf(image.sha256), names(files))

        store.keep(entry("pak01_dir.vpk"), Sha256.image(chunk))
        store.keep(entry("pak02_dir.vpk"), image)
        assertEquals(setOf(image.sha256), names(files), "neither a file that does not hold what it should nor one that is missing is stored")
    }

    @Test
    fun aGameFileHoldsItsStoredContentUnreadWhileItIsOneFileWithItAtItsSizeAndAnEditInPlaceIsForgotten() = runBlocking<Unit> {
        val file = addons.resolve("pak01_dir.vpk")
        file.writeBytes(dir)
        val image = Sha256.image(dir)
        assertFalse(store.holds(entry("pak01_dir.vpk"), image), "nothing is stored yet")
        keepRead("pak01_dir.vpk", image)
        assertEquals(key(file), key(files.resolve(image.sha256)), "not modified since the read, it is stored as the very file")
        assertTrue(store.holds(entry("pak01_dir.vpk"), image))
        assertFalse(store.holds(entry("pak02_dir.vpk"), image), "a name with no file")

        Files.delete(file)
        file.writeBytes(dir)
        assertFalse(store.holds(entry("pak01_dir.vpk"), image), "another file with the same bytes is not the stored one")
        store.forget(entry("pak01_dir.vpk"), image)
        assertEquals(setOf(image.sha256), names(files), "a stored content that is another file stays")

        keepRead("pak01_dir.vpk", image)
        Files.write(file, "the directory, edited in place".encodeToByteArray())
        assertFalse(store.holds(entry("pak01_dir.vpk"), image), "an edit in place that changes the size shows")
        store.forget(entry("pak01_dir.vpk"), image)
        assertEquals(emptySet(), names(files), "the stored name of the old bytes goes")
        assertContentEquals("the directory, edited in place".encodeToByteArray(), file.readBytes(), "the game file keeps its bytes")
    }

    @Test
    fun anEditInPlaceAtTheSameSizeShowsByItsModifiedTimeAndOnlyAKeepAfterAReadVouchesAgain() = runBlocking<Unit> {
        val file = addons.resolve("pak01_dir.vpk")
        file.writeBytes(dir)
        val image = Sha256.image(dir)
        val written = FileTime.from(Instant.parse("2026-10-01T12:00:00Z"))
        Files.setLastModifiedTime(file, written)
        keepRead("pak01_dir.vpk", image)
        assertTrue(store.holds(entry("pak01_dir.vpk"), image))
        val stored = key(files.resolve(image.sha256))

        Files.write(file, "the_directory".encodeToByteArray())
        assertEquals(dir.size.toLong(), Files.size(file))
        assertFalse(store.holds(entry("pak01_dir.vpk"), image), "the same size, but modified since it was kept")
        store.keep(entry("pak01_dir.vpk"), image)
        assertFalse(store.holds(entry("pak01_dir.vpk"), image), "a backup, which reads nothing, vouches for nothing")

        Files.write(file, dir)
        keepRead("pak01_dir.vpk", image)
        assertTrue(store.holds(entry("pak01_dir.vpk"), image), "read again and found to hold its bytes, it is vouched for at its new time")
        assertEquals(stored, key(files.resolve(image.sha256)), "the stored content is still the very file")

        Files.setLastModifiedTime(file, written)
        val beforeRead = store.modified(entry("pak01_dir.vpk"))
        Files.write(file, "the_directory".encodeToByteArray())
        store.keep(entry("pak01_dir.vpk"), image, beforeRead)
        assertFalse(store.holds(entry("pak01_dir.vpk"), image), "written while it was read, it has a time other than the one taken before the read")
    }

    @Test
    fun anEditIsSetAsideAsTheVeryFileWhichNeitherAnotherFileNorPruneTouches() = runBlocking<Unit> {
        val file = addons.resolve("pak01_dir.vpk")
        file.writeBytes(dir)
        store.setAside(entry("pak01_dir.vpk"), "Blackout 2026-10-31")
        val aside = backups.resolve("edits/Blackout 2026-10-31/pak01_dir.vpk")
        assertEquals(key(file), key(aside), "a second name, no copy")
        store.setAside(entry("pak01_dir.vpk"), "Blackout 2026-10-31")
        assertEquals(key(file), key(aside), "set aside already, it is left as it is")

        Files.delete(file)
        file.writeBytes(chunk)
        assertFailsWith<IOException>("another file has the name") { store.setAside(entry("pak01_dir.vpk"), "Blackout 2026-10-31") }
        assertFailsWith<IOException>("a folder name is one plain name") { store.setAside(entry("pak01_dir.vpk"), "../Blackout") }
        store.prune(root, emptySet())
        assertContentEquals(dir, aside.readBytes(), "prune never goes in")
        assertEquals(emptyList(), store.list(root), "and it is no backup")

        assertFalse(store.has(root, Sha256.image(chunk)))
        store.keep(entry("pak01_dir.vpk"), Sha256.image(chunk))
        assertTrue(store.has(root, Sha256.image(chunk)))
        assertFalse(store.has(root, FileImage(chunk.size + 1L, Sha256.image(chunk).sha256)), "a content of another size is not that content")
    }

    @Test
    fun aStoredCopyThatIsAnotherFileIsReplacedByTheGameFile() = runBlocking<Unit> {
        val image = Sha256.image(dir)
        files.createDirectories().resolve(image.sha256).writeBytes("edited where it stood".encodeToByteArray())
        addons.resolve("pak01_dir.vpk").writeBytes(dir)
        store.keep(entry("pak01_dir.vpk"), image)
        assertEquals(key(addons.resolve("pak01_dir.vpk")), key(files.resolve(image.sha256)))
        assertContentEquals(dir, files.resolve(image.sha256).readBytes())
    }

    @Test
    fun aDriveThatCannotLinkKeepsCheckedCopiesThatCountAsTheGameFileWhileTheyHoldItsBytes() = runBlocking<Unit> {
        val refusing = FileBackupStore(originals, createLink = { link, _ -> throw FileSystemException(link.toString(), null, "Operation not permitted") })
        val game = addons.resolve("pak01_dir.vpk").apply { writeBytes(dir) }
        val image = Sha256.image(dir)
        refusing.keep(entry("pak01_dir.vpk"), image, refusing.modified(entry("pak01_dir.vpk")))
        assertNotEquals(key(game), key(files.resolve(image.sha256)), "a copy, as no link can be made")
        assertContentEquals(dir, files.resolve(image.sha256).readBytes())
        assertEquals(setOf(image.sha256), names(files), "nothing is left beside it")
        assertTrue(refusing.holds(entry("pak01_dir.vpk"), image))

        refusing.place(image, entry("pak01_dir.vpk.hideout-t-new")).let { assertEquals(PlaceResult.Placed, it) }
        assertContentEquals(dir, addons.resolve("pak01_dir.vpk.hideout-t-new").readBytes())

        game.writeBytes(chunk)
        assertFalse(refusing.holds(entry("pak01_dir.vpk"), image), "an edit is never taken for the stored bytes")
        refusing.forget(entry("pak01_dir.vpk"), image)
        assertTrue(files.resolve(image.sha256).exists(), "nor is it forgotten for them")
    }

    @Test
    fun aPlacementIsALinkToTheStoredFileReadAgainFirst() = runBlocking<Unit> {
        addons.resolve("pak01_dir.vpk").writeBytes(dir)
        val image = Sha256.image(dir)
        store.keep(entry("pak01_dir.vpk"), image)
        assertEquals(PlaceResult.Placed, store.place(image, entry("pak03_dir.vpk.hideout-t-new")))
        assertEquals(key(files.resolve(image.sha256)), key(addons.resolve("pak03_dir.vpk.hideout-t-new")))
        assertIs<PlaceResult.Failed>(store.place(image, entry("pak03_dir.vpk.hideout-t-new")), "a name that is taken")
        val off = GameEntry(root, SetFolder.Off("mod-7"), "set-3fa94c0d12be_dir.vpk.hideout-t-new")
        assertEquals(PlaceResult.Placed, store.place(image, off), "an off folder is made for it")
        assertIs<PlaceResult.Failed>(store.place(image, GameEntry(root, SetFolder.Off("mod-7"), "pak04_dir.vpk.hideout-t-new")), "a pak name is never an off folder's")

        addons.resolve("pak01_dir.vpk").writeBytes("edited in place".encodeToByteArray())
        assertEquals(PlaceResult.Unavailable, store.place(image, entry("pak05_dir.vpk.hideout-t-new")), "a stored copy edited in place")
        assertFalse(addons.resolve("pak05_dir.vpk.hideout-t-new").exists(), "and nothing is left of it")
        assertEquals(PlaceResult.Unavailable, store.place(Sha256.image(chunk), entry("pak06_dir.vpk.hideout-t-new")), "content never stored")
        assertIs<PlaceResult.Failed>(store.place(image, entry("readme.txt")), "a name Hideout does not make")
    }

    @Test
    fun anOffFileIsKeptFromItsOffName() = runBlocking<Unit> {
        val parked = home.resolve("Deadlock/game/citadel/hideout_off/mod-7").createDirectories().resolve("set-0b1e7a9c55d2_000.vpk")
        parked.writeBytes(chunk)
        val image = Sha256.image(chunk)
        store.keep(GameEntry(root, SetFolder.Off("mod-7"), "set-0b1e7a9c55d2_000.vpk"), image)
        assertEquals(key(parked), key(files.resolve(image.sha256)), "kept as a second name of the very file")
        assertEquals(PlaceResult.Placed, store.place(image, entry("pak04_000.vpk.hideout-t-new")))
        assertContentEquals(chunk, addons.resolve("pak04_000.vpk.hideout-t-new").readBytes())
    }

    @Test
    fun aBackupKeepsItsOwnCopyOfEachSettingsFileOrNone() = runBlocking<Unit> {
        val cfg = home.resolve("Deadlock/game/citadel/cfg").createDirectories().resolve("autoexec.cfg")
        val bytes = "fps_max 240\r\n".encodeToByteArray()
        val reticle = "{ base = { crosshair_pip_gap = \"4\" } }".encodeToByteArray()
        val cloud = "{\n\tbase = { }\n}".encodeToByteArray()
        val otherCloud = "{\n\tbase = { crosshair_pip_gap = \"9\" }\n}".encodeToByteArray()
        val accounts = listOf(39734274L, 1157792213L).map { HeldFile.CloudHeroSettings(checkNotNull(SteamAccountId.ofAccountId(it))) }
        cfg.writeBytes(bytes)
        store.publish(root, info("with"), LibraryManifest(), held = mapOf(HeldFile.Autoexec to bytes, HeldFile.HeroSettings to reticle, accounts[0] to cloud, accounts[1] to otherCloud))
        store.publish(root, info("without"), LibraryManifest(), held = emptyMap())
        store.publish(root, info("empty"), LibraryManifest(), held = mapOf(HeldFile.Autoexec to ByteArray(0)))
        cfg.writeBytes("fps_max 60\n".encodeToByteArray())
        val with = store.held(root, BackupId("with"))
        assertEquals(listOf(bytes, reticle, cloud, otherCloud).map { it.toList() }, (listOf(HeldFile.Autoexec, HeldFile.HeroSettings) + accounts).map { with.getValue(it).toList() }, "an editor saving in place does not change them, and each account's copy is its own")
        assertEquals(setOf(HeldFile.Autoexec, HeldFile.HeroSettings) + accounts, with.keys)
        assertNotEquals(key(cfg), key(backups.resolve("with/autoexec.cfg")))
        assertEquals(emptyMap(), store.held(root, BackupId("without")))
        assertContentEquals(ByteArray(0), store.held(root, BackupId("empty"))[HeldFile.Autoexec], "a game with no autoexec.cfg is kept as an empty one")
    }

    @Test
    fun aBackupIsPublishedListedReadAndDeletedWholeAndPruneKeepsWhatIsNamed() = runBlocking<Unit> {
        addons.resolve("pak01_dir.vpk").writeBytes(dir)
        addons.resolve("pak01_000.vpk").writeBytes(chunk)
        keepRead("pak01_dir.vpk", Sha256.image(dir))
        keepRead("pak01_000.vpk", Sha256.image(chunk))
        val library = LibraryManifest()
        store.publish(root, info("first"), library, held = emptyMap())
        assertEquals(listOf(info("first")), store.list(root))
        assertEquals(library, store.read(root, BackupId("first")))
        assertEquals(setOf("first", "files", "seen"), names(backups), "nothing is left of writing it")

        backups.resolve(".incoming-second").createDirectories().resolve("backup.json").writeText(BackupCodec.encode(info("second")))
        backups.resolve(".deleting-third").createDirectories()
        backups.resolve("retired").createDirectories().resolve("backup.json").writeText(BackupCodec.encode(info("retired")).replace("\"schemaVersion\":$ManifestSchemaVersion", "\"schemaVersion\":${ManifestSchemaVersion - 1}"))
        files.resolve(".incoming-x").writeBytes(dir)
        assertEquals(listOf(info("first")), store.list(root), "neither a half-written, a half-deleted nor a retired backup is listed")

        store.prune(root, setOf(Sha256.image(chunk).sha256))
        assertEquals(setOf(Sha256.image(chunk).sha256), names(files))
        assertEquals(setOf(Sha256.image(chunk).sha256), names(backups.resolve("seen")), "a content's modified time goes with it")
        assertEquals(setOf("first", "files", "seen", "retired"), names(backups))

        store.delete(root, BackupId("first"))
        assertEquals(emptyList(), store.list(root))
        assertEquals(setOf("files", "seen", "retired"), names(backups))
        assertFailsWith<IOException> { store.read(root, BackupId("first")) }
    }

    @Test
    fun anOriginalIsStoredAndPutBackAsCopiesAndOnlyWhole() = runBlocking<Unit> {
        val original = RetainedOriginal(
            "kept-1", "install-1", InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 1, 10, null)), "pak01_dir.vpk",
            listOf(SetFile(VpkPart.Directory, Sha256.image(dir)), SetFile(VpkPart.Chunk(0), Sha256.image(chunk))),
        )
        val folder = originals.resolve("kept-1").createDirectories()
        folder.resolve("dir.vpk").writeBytes(dir)
        folder.resolve("000.vpk").writeBytes(chunk)
        backups.createDirectories()
        store.keepOriginal(root, original)
        assertNotEquals(key(folder.resolve("dir.vpk")), key(files.resolve(Sha256.image(dir).sha256)), "the store holds a copy, never the original itself")

        deleteTree(folder)
        originals.resolve("kept-1").createDirectories().resolve("stale.vpk").writeBytes(chunk)
        assertEquals(PlaceResult.Placed, store.placeOriginal(root, original))
        assertEquals(setOf("dir.vpk", "000.vpk"), names(folder), "what a stopped placement left under its id goes")
        assertContentEquals(dir, folder.resolve("dir.vpk").readBytes())
        assertNotEquals(key(files.resolve(Sha256.image(dir).sha256)), key(folder.resolve("dir.vpk")))

        Files.delete(files.resolve(Sha256.image(chunk).sha256))
        deleteTree(folder)
        assertEquals(PlaceResult.Unavailable, store.placeOriginal(root, original))
        assertEquals(emptySet(), names(originals), "nothing of a part placed is left")
        store.keepOriginal(root, original.copy(id = "gone"))
        assertEquals(setOf(Sha256.image(dir).sha256), names(files), "an original whose copies are gone stores nothing")
    }
}
