package gg.hideouthq.library

import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ChangeToken
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.citadelPath
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NioLinksTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "link-tests")), "home").toRealPath()
    private val game = home.resolve("Deadlock")
    private val root = GameRoot(game.toString())
    private val addons = game.resolve("game/citadel/addons").createDirectories()
    private val mod = SubmissionRef(SubmissionKind.Mod, 628_739)
    private val off = game.resolve("game/citadel/hideout_off/mod-628739")
    private val installer = NioModInstaller(home.resolve("staging"))
    private val bytes = ByteArray(4_096) { (it % 251).toByte() }
    private val image = Sha256.image(bytes)

    private fun entry(folder: SetFolder, name: String) = GameEntry(root, folder, name)

    private fun fileKey(path: Path): Any = checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()) {
        "The fixture filesystem must expose file identity: $path"
    }

    private val parkedName = "set-3fa94c0d12be_dir.vpk"

    private fun settle(file: Path) = Files.setLastModifiedTime(file, FileTime.from(Instant.now().minusSeconds(3_600)))

    @Test
    fun linkingMakesASecondNameOfTheSameFileAndCreatesOnlyTheModsOwnFolder() = runBlocking<Unit> {
        addons.resolve("pak03_dir.vpk").writeBytes(bytes)
        val anchor = entry(SetFolder.Addons, "pak03_dir.vpk.hideout-t1")
        assertEquals(LinkResult.Linked, installer.link(entry(SetFolder.Addons, "pak03_dir.vpk"), anchor))
        assertEquals(fileKey(addons.resolve("pak03_dir.vpk")), fileKey(addons.resolve("pak03_dir.vpk.hideout-t1")))

        assertFalse(off.exists())
        assertEquals(LinkResult.Linked, installer.link(anchor, entry(SetFolder.Off(mod), parkedName)))
        assertEquals(fileKey(addons.resolve("pak03_dir.vpk")), fileKey(off.resolve(parkedName)), "moving copies nothing")
        assertEquals(EntryState.Present(image, twin = true), installer.inspect(entry(SetFolder.Off(mod), parkedName), anchor))

        Files.delete(game.resolve("game/citadel/addons").resolve("pak03_dir.vpk.hideout-t1"))
        val elsewhere = GameRoot(home.resolve("NotDeadlock").toString())
        assertEquals(LinkResult.NotThere, installer.link(GameEntry(elsewhere, SetFolder.Addons, "pak03_dir.vpk"), GameEntry(elsewhere, SetFolder.Off(mod), parkedName)))
        assertFalse(home.resolve("NotDeadlock").exists(), "Deadlock's own folders are never made")
    }

    @Test
    fun aNameThatIsTakenIsNeverReplacedEvenByTheSameBytes() = runBlocking<Unit> {
        addons.resolve("pak03_dir.vpk").writeBytes(bytes)
        off.createDirectories().resolve(parkedName).writeBytes(bytes)
        val before = fileKey(off.resolve(parkedName))
        assertEquals(LinkResult.Taken, installer.link(entry(SetFolder.Addons, "pak03_dir.vpk"), entry(SetFolder.Off(mod), parkedName)))
        assertEquals(before, fileKey(off.resolve(parkedName)))
        assertNotEquals(fileKey(addons.resolve("pak03_dir.vpk")), before)
    }

    @Test
    fun anEqualByteCopyIsNotATwinAndIsNeverDeletedAsOne() = runBlocking<Unit> {
        addons.resolve("pak03_000.vpk").writeBytes(bytes)
        addons.resolve("pak03_000.vpk.hideout-t1").writeBytes(bytes)
        val copy = entry(SetFolder.Addons, "pak03_000.vpk")
        val anchor = entry(SetFolder.Addons, "pak03_000.vpk.hideout-t1")
        assertEquals(false, (installer.inspect(copy, anchor) as EntryState.Present).twin)
        assertIs<Removal.Kept>(installer.removeTwin(copy, anchor, image))
        assertContentEquals(bytes, addons.resolve("pak03_000.vpk").readBytes())

        Files.delete(addons.resolve("pak03_000.vpk.hideout-t1"))
        Files.createLink(addons.resolve("pak03_000.vpk.hideout-t1"), addons.resolve("pak03_000.vpk"))
        assertEquals(true, (installer.inspect(copy, anchor) as EntryState.Present).twin)
        assertEquals(Removal.Removed, installer.removeTwin(copy, anchor, image))
        assertContentEquals(bytes, addons.resolve("pak03_000.vpk.hideout-t1").readBytes(), "the bytes live on in the twin")
        assertEquals(Removal.Absent, installer.removeTwin(copy, anchor, image))
    }

    @Test
    fun aTwinChangedInPlaceIsKeptAndAnAnchorLeftAloneGoesOnlyAsAPlainFileHoldingItsBytes() = runBlocking<Unit> {
        addons.resolve("pak03_000.vpk").writeBytes(bytes)
        val original = entry(SetFolder.Addons, "pak03_000.vpk")
        val anchor = entry(SetFolder.Addons, "pak03_000.vpk.hideout-t1")
        val anchorPath = addons.resolve("pak03_000.vpk.hideout-t1")
        assertEquals(LinkResult.Linked, installer.link(original, anchor))
        addons.resolve("pak03_000.vpk").writeBytes(ByteArray(10))
        assertIs<Removal.Kept>(installer.removeTwin(original, anchor, image), "changed bytes are never deleted")
        addons.resolve("pak03_000.vpk").writeBytes(bytes)
        assertEquals(Removal.Removed, installer.removeTwin(original, anchor, image))

        anchorPath.writeBytes(ByteArray(10))
        assertIs<Removal.Kept>(installer.removeExact(anchor, image), "an anchor whose bytes changed stays")
        anchorPath.writeBytes(bytes)
        assertIs<Removal.Kept>(installer.removeExact(anchor, FileImage(1, image.sha256)), "it must hold the expected size too")
        assertEquals(Removal.Removed, installer.removeExact(anchor, image))
        assertEquals(Removal.Absent, installer.removeExact(anchor, image))

        val mine = home.resolve("mine.vpk").also { it.writeBytes(bytes) }
        Files.createSymbolicLink(anchorPath, mine)
        assertIs<Removal.Kept>(installer.removeExact(anchor, image), "a link at the anchor's name is not a plain file")
        Files.delete(anchorPath)
        anchorPath.createDirectories()
        assertIs<Removal.Kept>(installer.removeExact(anchor, image), "nor is a folder")
        assertContentEquals(bytes, mine.readBytes())
        assertEquals(listOf(anchorPath), Files.list(addons).use { it.toList() })

        val parked = entry(SetFolder.Off(mod), "$parkedName.hideout-t1")
        off.createDirectories().resolve(parked.name).writeBytes(bytes)
        assertEquals(Removal.Removed, installer.removeExact(parked, image))
        assertFalse(off.exists(), "the mod's off folder goes with its last name")
    }

    @Test
    fun aFileReadOnceIsCheckedWithoutOpeningItWhileItShowsTheSameFileSizeAndTimes() = runBlocking<Unit> {
        val chunk = addons.resolve("pak03_000.vpk").also { it.writeBytes(bytes) }
        settle(chunk)
        val original = entry(SetFolder.Addons, "pak03_000.vpk")
        val anchor = entry(SetFolder.Addons, "pak03_000.vpk.hideout-t1")
        val moved = entry(SetFolder.Off(mod), parkedName)
        assertEquals(LinkResult.Linked, installer.link(original, anchor))
        assertEquals(EntryState.Present(image, twin = true), installer.inspect(anchor, original))

        assumeTrue("permissions are POSIX here", "posix" in chunk.fileSystem.supportedFileAttributeViews())
        Files.setPosixFilePermissions(chunk, emptySet())
        assumeFalse("this user can read any file", Files.isReadable(chunk))
        assertEquals(EntryState.Present(image, twin = true), installer.inspect(anchor, original), "known, so not opened")
        assertEquals(LinkResult.Linked, installer.link(anchor, moved))
        assertEquals(EntryState.Present(image, twin = true), installer.inspect(moved, anchor), "a new name of the file is known to hold it too")
        assertEquals(Removal.Removed, installer.removeTwin(original, anchor, image), "a name its twin keeps goes on what its file is known to hold")
        assertEquals(Removal.Removed, installer.unlinkTwin(anchor, moved))
        assertIs<Removal.Failed>(installer.removeExact(moved, image), "a name that may be its file's last is read before it goes")

        Files.setPosixFilePermissions(off.resolve(parkedName), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        assertEquals(Removal.Removed, installer.removeExact(moved, image))
    }

    @Test
    fun anEditInPlaceAfterTheFileWasReadIsSeenThroughEitherName() = runBlocking<Unit> {
        val chunk = addons.resolve("pak03_000.vpk").also { it.writeBytes(bytes) }
        settle(chunk)
        val original = entry(SetFolder.Addons, "pak03_000.vpk")
        val anchor = entry(SetFolder.Addons, "pak03_000.vpk.hideout-t1")
        assertEquals(LinkResult.Linked, installer.link(original, anchor))
        assertEquals(EntryState.Present(image, twin = true), installer.inspect(anchor, original))

        val edited = ByteArray(bytes.size) { 9 }
        chunk.writeBytes(edited)
        assertIs<Removal.Kept>(installer.removeTwin(original, anchor, image), "changed bytes are never deleted")
        assertEquals(EntryState.Present(Sha256.image(edited), twin = true), installer.inspect(anchor, original))
        assertIs<Removal.Kept>(installer.removeExact(anchor, image))
        assertContentEquals(edited, chunk.readBytes())
    }

    @Test
    fun anotherFilePutAtTheNameWithTheSameSizeAndTimeIsRead() = runBlocking<Unit> {
        val chunk = addons.resolve("pak03_000.vpk").also { it.writeBytes(bytes) }
        settle(chunk)
        val original = entry(SetFolder.Addons, "pak03_000.vpk")
        assertEquals(EntryState.Present(image, twin = true), installer.inspect(original, original))

        val modified = Files.getLastModifiedTime(chunk)
        Files.delete(chunk)
        val other = ByteArray(bytes.size) { 7 }
        chunk.writeBytes(other)
        Files.setLastModifiedTime(chunk, modified)
        assertEquals(EntryState.Present(Sha256.image(other), twin = true), installer.inspect(original, original))
        assertIs<Removal.Kept>(installer.removeExact(original, image))
    }

    @Test
    fun aFileWhoseLastWriteIsWithinItsFileSystemsTimeStepIsReadAtEveryCheck() = runBlocking<Unit> {
        val chunk = addons.resolve("pak03_000.vpk").also { it.writeBytes(bytes) }
        val second = FileTime.from(Instant.now().truncatedTo(ChronoUnit.SECONDS).minusSeconds(1))
        Files.setLastModifiedTime(chunk, second)
        val original = entry(SetFolder.Addons, "pak03_000.vpk")
        assertEquals(EntryState.Present(image, twin = true), installer.inspect(original, original))

        val other = ByteArray(bytes.size) { 7 }
        chunk.writeBytes(other)
        Files.setLastModifiedTime(chunk, second)
        assertEquals(EntryState.Present(Sha256.image(other), twin = true), installer.inspect(original, original), "a time in whole seconds, as FAT keeps them, could be a second write's too")
    }

    @Test
    fun anAnchorGoesWhateverItHoldsOnlyWhileItIsOneFileWithItsTwin() = runBlocking<Unit> {
        val chunk = addons.resolve("pak03_000.vpk").also { it.writeBytes(bytes) }
        val original = entry(SetFolder.Addons, "pak03_000.vpk")
        val anchor = entry(SetFolder.Addons, "pak03_000.vpk.hideout-t1")
        val anchorPath = addons.resolve(anchor.name).also { it.writeBytes(bytes) }
        assertIs<Removal.Kept>(installer.unlinkTwin(anchor, original), "an equal-byte copy is not a twin")
        Files.delete(anchorPath)
        assertEquals(LinkResult.Linked, installer.link(original, anchor))
        chunk.writeBytes(ByteArray(10))
        assertEquals(Removal.Removed, installer.unlinkTwin(anchor, original))
        assertContentEquals(ByteArray(10), chunk.readBytes(), "the bytes live on in the twin")
        assertEquals(Removal.Absent, installer.unlinkTwin(anchor, original))
    }

    @Test
    fun linksAndJunctionsInPlaceOfAFolderAreRefused() = runBlocking<Unit> {
        addons.resolve("pak03_dir.vpk").writeBytes(bytes)
        val elsewhere = home.resolve("elsewhere").createDirectories()
        Files.createDirectories(off.parent)
        Files.createSymbolicLink(off, elsewhere)
        assertIs<LinkResult.Failed>(installer.link(entry(SetFolder.Addons, "pak03_dir.vpk"), entry(SetFolder.Off(mod), "pak03_dir.vpk")))
        assertEquals(emptyList(), Files.list(elsewhere).use { it.toList() }, "nothing lands through the link")
        assertIs<EntryState.Unreadable>(installer.inspect(entry(SetFolder.Off(mod), "pak03_dir.vpk"), entry(SetFolder.Addons, "pak03_dir.vpk")))
    }

    @Test
    fun aDownloadIsAnchoredBesideItselfAndOnlyArchivesLaidOutUnderTheirOwnSourceAreTouched() = runBlocking<Unit> {
        val downloads = home.resolve("downloads")
        val folder = downloads.resolve("mod-628739/9").createDirectories()
        folder.resolve("ivy.zip").writeBytes(bytes)
        val archive = archive(folder.resolve("ivy.zip"))
        val downloader = downloader(downloads)
        val token = ChangeToken("t1")
        assertEquals(LinkResult.Linked, downloader.link(download(archive), download(archive, token)))
        assertEquals(fileKey(folder.resolve("ivy.zip")), fileKey(folder.resolve("ivy.zip.hideout-t1")))
        assertEquals(Removal.Removed, downloader.removeTwin(download(archive), download(archive, token), image))
        assertEquals(Removal.Removed, downloader.removeExact(download(archive, token), image), "the anchor, now the last name, goes on its bytes")
        assertEquals(emptyList(), Files.list(downloads).use { it.toList() }, "with the file and mod folders it emptied")

        val outside = home.resolve("mine.zip").also { it.writeBytes(bytes) }
        val foreign = archive(outside)
        assertIs<LinkResult.Failed>(downloader.link(download(foreign), download(foreign, token)))
        assertIs<Removal.Failed>(downloader.removeTwin(download(foreign), download(foreign, token), image))
        assertTrue(outside.exists())

        val linked = downloads.resolve("mod-5").also { Files.createSymbolicLink(it, home.resolve("staging").createDirectories()) }
        home.resolve("staging/9").createDirectories().resolve("x.zip").writeBytes(bytes)
        assertIs<Removal.Failed>(downloader.removeExact(DownloadEntry(SubmissionIdentity(SubmissionKind.Mod, 5, 9, null), archive(linked.resolve("9/x.zip"))), image))
        assertTrue(home.resolve("staging/9/x.zip").exists())
    }

    @Test
    fun aDeletionTakesOnlyTheDownloadFoldersItEmptiesAndNeverTheFolderDownloadsWentTo() = runBlocking<Unit> {
        val downloads = home.resolve("Mods")
        val downloader = downloader(home.resolve("current"))
        fun saved(fileId: Long, name: String): DownloadEntry {
            val file = downloads.resolve("mod-628739/$fileId").createDirectories().resolve(name).also { it.writeBytes(bytes) }
            return DownloadEntry(SubmissionIdentity(SubmissionKind.Mod, 628_739, fileId, null), archive(file))
        }
        val first = saved(9, "ivy.zip")
        val second = saved(9, "ivy (2).zip")
        val other = saved(10, "ivy.zip")
        val notes = downloads.resolve("mod-628739/10/notes.txt").also { it.writeBytes(byteArrayOf(1)) }

        assertEquals(Removal.Removed, downloader.removeExact(first, image))
        assertTrue(downloads.resolve("mod-628739/9").exists(), "a file folder still holding a download stays")
        assertEquals(Removal.Removed, downloader.removeTwin(second, second, image))
        assertFalse(downloads.resolve("mod-628739/9").exists(), "the file folder the last deletion emptied goes")
        assertEquals(Removal.Removed, downloader.removeExact(other, image))
        assertTrue(notes.exists(), "a file folder holding anything else stays, and so does its mod folder")
        Files.delete(notes)
        assertEquals(Removal.Removed, downloader.removeExact(saved(10, "ivy (2).zip"), image))
        assertEquals(emptyList(), Files.list(downloads).use { it.toList() }, "the folder downloads went to stays, emptied of the mod's folders")
    }

    @Test
    fun anArchiveIsFoundFromItsOwnPathInWhicheverFolderItWentToAndNeverFromTheOneInUseNow() = runBlocking<Unit> {
        val current = home.resolve("current").createDirectories()
        val downloader = downloader(current)
        val old = home.resolve("old library/downloads").resolve("mod-628739/9").createDirectories()
        old.resolve("ivy.zip").writeBytes(bytes)
        val archive = archive(old.resolve("ivy.zip"))
        val token = ChangeToken("t1")
        assertEquals(EntryState.Present(image, twin = true), downloader.inspect(download(archive), download(archive)), "an archive in a folder used before")
        assertEquals(LinkResult.Linked, downloader.link(download(archive), download(archive, token)))
        assertEquals(Removal.Removed, downloader.removeTwin(download(archive), download(archive, token), image))
        assertFalse(old.resolve("ivy.zip").exists())

        val sentinel = old.resolve("ivy.zip").also { it.writeBytes(bytes) }
        val wrongIds = listOf(
            SubmissionIdentity(SubmissionKind.Mod, 628_739, 10, null),
            SubmissionIdentity(SubmissionKind.Mod, 628_740, 9, null),
            SubmissionIdentity(SubmissionKind.Sound, 628_739, 9, null),
        )
        for (source in wrongIds) {
            assertIs<EntryState.Unreadable>(downloader.inspect(DownloadEntry(source, archive), DownloadEntry(source, archive)), "$source")
            assertIs<Removal.Failed>(downloader.removeTwin(DownloadEntry(source, archive), DownloadEntry(source, archive, token), image), "$source")
        }
        val shallow = home.resolve("9").createDirectories().resolve("ivy.zip").also { it.writeBytes(bytes) }
        assertIs<Removal.Failed>(downloader.removeExact(download(archive(shallow)), image), "a path without its mod and file folders")
        val deep = old.resolve("extra").createDirectories().resolve("ivy.zip").also { it.writeBytes(bytes) }
        assertIs<Removal.Failed>(downloader.removeExact(download(archive(deep)), image), "a path one folder deeper")
        listOf(sentinel, shallow, deep).forEach { assertContentEquals(bytes, it.readBytes(), "$it stays") }

        Files.createSymbolicLink(home.resolve("linked base"), home.resolve("old library/downloads"))
        assertIs<Removal.Failed>(downloader.removeExact(download(archive(home.resolve("linked base/mod-628739/9/ivy.zip"))), image), "the folder above a mod's folder is a link")
        assertContentEquals(bytes, sentinel.readBytes())
    }

    @Test
    fun theFileManagerIsGivenOnlyAPlainSetFolder() = runBlocking<Unit> {
        val opened = mutableListOf<File>()
        val opener = DesktopFolderOpener(DesktopHandoff(osName = "Windows", native = { opened += it; true }))
        assertTrue(opener.open(root, CitadelPath.Addons))
        assertFalse(opener.open(root, SetFolder.Off(mod).citadelPath), "an off folder that is not there")
        off.createDirectories()
        assertTrue(opener.open(root, SetFolder.Off(mod).citadelPath))
        assertEquals(listOf(addons.toFile(), off.toFile()), opened)

        Files.delete(off)
        Files.createSymbolicLink(off, home.resolve("elsewhere").createDirectories())
        assertFalse(opener.open(root, SetFolder.Off(mod).citadelPath))
        assertEquals(2, opened.size)

        val fresh = home.resolve("fresh/game/citadel").createDirectories()
        assertTrue(opener.open(GameRoot(home.resolve("fresh").toString()), CitadelPath.Addons), "no mods folder yet: the folder it will be made in")
        assertEquals(fresh.toFile(), opened.last())
    }

    @Test
    fun anArchiveWhoseFoldersAreGoneIsAbsentAndOneReachedThroughAnyLinkIsRefused() = runBlocking<Unit> {
        val downloader = downloader(home.resolve("current"))
        fun at(path: String) = archive(home.resolve(path)).let { download(it) to download(it, ChangeToken("t1")) }

        val (gone, goneAnchor) = at("downloads/mod-628739/9/ivy.zip")
        for (kept in listOf(null, "downloads", "downloads/mod-628739")) {
            kept?.let { home.resolve(it).createDirectories() }
            assertEquals(EntryState.Absent, downloader.inspect(gone, goneAnchor), "$kept")
            assertEquals(EntryState.Absent, downloader.inspect(goneAnchor, goneAnchor), "$kept")
            assertEquals(Removal.Absent, downloader.removeTwin(gone, goneAnchor, image), "$kept")
            assertEquals(LinkResult.NotThere, downloader.link(gone, goneAnchor), "$kept")
        }
        assertFalse(home.resolve("downloads/mod-628739/9").exists(), "looking creates nothing")

        val foreign = home.resolve("foreign/downloads/mod-628739/9").createDirectories().resolve("ivy.zip").also { it.writeBytes(bytes) }
        val foreignKey = fileKey(foreign)
        val links = listOf(
            "the file folder" to (home.resolve("a/downloads/mod-628739/9") to foreign.parent),
            "the downloads folder" to (home.resolve("b/downloads") to home.resolve("foreign/downloads")),
            "a folder above it" to (home.resolve("c") to home.resolve("foreign")),
            "a dangling link above it" to (home.resolve("d") to home.resolve("nowhere")),
        )
        for ((place, link) in links) {
            Files.createSymbolicLink(link.first.also { it.parent.createDirectories() }, link.second)
            val (original, anchor) = at("${home.relativize(link.first).getName(0)}/downloads/mod-628739/9/ivy.zip")
            assertIs<EntryState.Unreadable>(downloader.inspect(original, anchor), place)
            assertIs<LinkResult.Failed>(downloader.link(original, anchor), place)
            assertIs<Removal.Failed>(downloader.removeExact(anchor, image), place)
            assertIs<Removal.Failed>(downloader.removeTwin(original, anchor, image), place)
        }
        assertEquals(listOf("ivy.zip"), Files.list(foreign.parent).use { paths -> paths.map { it.fileName.toString() }.toList() }, "nothing is named beside the file a link leads to")
        assertEquals(foreignKey, fileKey(foreign))
        assertContentEquals(bytes, foreign.readBytes())
        assertFalse(home.resolve("nowhere").exists(), "nothing is made through a dangling link")
    }

    private fun downloader(folder: Path) = HttpDownloader(HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) }), folder, hideoutAreas = emptyList())

    private fun archive(path: Path) = ArchiveIdentity(path.toString(), image.sizeBytes, image.sha256)

    private val ivySource = SubmissionIdentity(SubmissionKind.Mod, 628_739, 9, null)

    private fun download(archive: ArchiveIdentity, anchor: ChangeToken? = null) = DownloadEntry(ivySource, archive, anchor)
}
