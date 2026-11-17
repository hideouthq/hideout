package gg.hideouthq.library

import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.FilePrint
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.VpkLook
import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderEntry
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Imprint
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.CRC32
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class NioGameInventoryTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "nio-inventory-tests")), "home").toRealPath()

    private fun root(name: String = "game"): GameRoot {
        val path = home.resolve(name)
        path.resolve("game/citadel/addons").createDirectories()
        return GameRoot(path.toString())
    }

    private fun write(root: GameRoot, relative: String, bytes: ByteArray): Path =
        Path.of(root.path, *relative.split('/').toTypedArray()).also { it.parent.createDirectories(); it.writeBytes(bytes) }

    private fun outside(name: String, bytes: ByteArray): Path = home.resolve("outside").resolve(name).also { it.parent.createDirectories(); it.writeBytes(bytes) }

    private inline fun <T> withPermissions(path: Path, permissions: String, restore: String, block: () -> T): T {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        try {
            return block()
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(restore))
        }
    }

    @Test
    fun anyFolderBelowCitadelListsEachEntryAsItIsWithoutFollowingLinks() = runBlocking {
        val root = root()
        write(root, "game/citadel/addons/profile_1/pak01_dir.vpk", byteArrayOf(1))
        write(root, "game/citadel/addons/pak02_dir.vpk", byteArrayOf(2))
        Files.createSymbolicLink(Path.of(root.path, "game/citadel/addons/replays"), outside("replays/a.dem", byteArrayOf(3)).parent)
        val listing = assertIs<FolderListing.Listed>(NioGameInventory.list(root, "addons"))
        assertEquals(setOf("profile_1" to EntryKind.Directory, "pak02_dir.vpk" to EntryKind.File, "replays" to EntryKind.Link), listing.kinds().toSet())
        assertEquals(listOf(FileStamp(1, Files.getLastModifiedTime(Path.of(root.path, "game/citadel/addons/pak02_dir.vpk")).toMillis())), listing.entries.mapNotNull { it.stamp }, "only a plain file has a stamp")
        assertEquals(listOf("pak01_dir.vpk" to EntryKind.File), assertIs<FolderListing.Listed>(NioGameInventory.list(root, "addons/profile_1")).kinds())
        assertEquals(setOf("addons"), assertIs<FolderListing.Listed>(NioGameInventory.list(root, "")).names.toSet(), "game/citadel itself lists too")
        assertIs<FolderListing.Unreadable>(NioGameInventory.list(root, "addons/replays"))
        Unit
    }

    @Test
    fun aMissingAddonsFolderIsAnEmptyListingNotAnError() = runBlocking {
        val path = home.resolve("no-addons-yet").also { it.resolve("game/citadel").createDirectories() }
        assertEquals(FolderListing.Listed(emptyList()), NioGameInventory.list(GameRoot(path.toString()), "addons"))
    }

    @Test
    fun aMissingGameFolderOrOneNotOnThisComputerIsUnknownNotEmpty() = runBlocking {
        val gone = home.resolve("unplugged-drive/Deadlock").toString()
        for (root in listOf(GameRoot(gone), GameRoot("relative/Deadlock"), GameRoot("""C:\Program Files (x86)\Steam\steamapps\common\Deadlock"""))) {
            assertIs<FolderListing.Unreadable>(NioGameInventory.list(root, "addons"))
            assertIs<FileObservation.Unreadable>(NioGameInventory.file(root, "game/citadel/addons/pak05_dir.vpk"))
        }
    }

    @Test
    fun fileStatReportsSizeAndSha256OfARealFile() = runBlocking {
        val root = root()
        val bytes = "hello hideout".encodeToByteArray()
        write(root, "game/citadel/addons/pak05_dir.vpk", bytes)
        assertEquals(FileObservation.Present(bytes.size.toLong(), sha256Hex(bytes)), NioGameInventory.file(root, "game/citadel/addons/pak05_dir.vpk"))
    }

    @Test
    fun aMissingFileOrAFolderIsAbsent() = runBlocking {
        val root = root()
        for (path in listOf("game/citadel/addons/nope.vpk", "game/citadel/hideout_off/mod-1/pak05_dir.vpk", "game/citadel/addons")) {
            assertEquals(FileObservation.Absent, NioGameInventory.file(root, path), path)
        }
    }

    @Test
    fun nothingReachedThroughALinkIsOwned() = runBlocking {
        val bytes = byteArrayOf(1, 2, 3)
        val linkedFile = root()
        Files.createSymbolicLink(Path.of(linkedFile.path, "game/citadel/addons/pak05_dir.vpk"), outside("same.vpk", bytes))
        assertEquals(FileObservation.Absent, NioGameInventory.file(linkedFile, "game/citadel/addons/pak05_dir.vpk"))
        assertEquals(FolderListing.Listed(listOf(FolderEntry("pak05_dir.vpk", EntryKind.Link))), NioGameInventory.list(linkedFile, "addons"), "the link is still an entry that occupies its name")

        val real = root("real-install")
        val owned = listOf("game/citadel/addons/pak05_dir.vpk", "game/citadel/hideout_off/mod-1/pak05_dir.vpk")
        owned.forEach { write(real, it, bytes) }
        for (linked in listOf("game", "game/citadel", "game/citadel/addons", "game/citadel/hideout_off", "game/citadel/hideout_off/mod-1")) {
            val game = home.resolve("through-" + linked.replace('/', '-'))
            game.resolve(linked).parent.createDirectories()
            Files.createSymbolicLink(game.resolve(linked), Path.of(real.path, linked))
            val through = GameRoot(game.toString())
            owned.forEach { assertEquals(FileObservation.Absent, NioGameInventory.file(through, it), "$it with $linked linked") }
            if ("hideout_off" !in linked) assertIs<FolderListing.Unreadable>(NioGameInventory.list(through, "addons"), linked)
        }
    }

    @Test
    fun aConfigThatIsALinkOrIsReachedThroughOneIsUnreadableSayingSo() = runBlocking {
        val linkedFile = root("linked-file")
        Path.of(linkedFile.path, "game/citadel/cfg").createDirectories()
        Files.createSymbolicLink(Path.of(linkedFile.path, "game/citadel/cfg/autoexec.cfg"), outside("autoexec.cfg", "fps_max 240\n".encodeToByteArray()))
        val linkedFolder = root("linked-folder")
        Files.createSymbolicLink(Path.of(linkedFolder.path, "game/citadel/cfg"), outside("cfg/autoexec.cfg", "fps_max 240\n".encodeToByteArray()).parent)
        for ((root, linked) in listOf(linkedFile to "game/citadel/cfg/autoexec.cfg", linkedFolder to "game/citadel/cfg")) {
            assertEquals(FileRead.Unreadable("$linked is a link. Hideout never reads or changes the game's files through one."), NioGameInventory.read(root, ConfigTarget.Autoexec.path, MaxConfigBytes))
        }
        assertEquals(FileRead.Absent, NioGameInventory.read(root("no-cfg"), ConfigTarget.Autoexec.path, MaxConfigBytes), "a file that is not there is not there")
    }

    @Test
    fun aSteamInstallReachedThroughALinkedParentStillWorks() = runBlocking {
        val library = home.resolve("SteamLibrary/steamapps/common").also { it.createDirectories() }
        val root = GameRoot(library.resolve("Deadlock").toString()).also { Path.of(it.path, "game/citadel/addons").createDirectories() }
        val bytes = byteArrayOf(8, 9)
        write(root, "game/citadel/addons/pak05_dir.vpk", bytes)
        val steam = home.resolve("steam").also { it.createDirectories() }
        Files.createSymbolicLink(steam.resolve("steamapps"), home.resolve("SteamLibrary/steamapps"))
        val throughLink = GameRoot(steam.resolve("steamapps/common/Deadlock").toString())

        for (given in listOf(root, throughLink)) {
            assertEquals(listOf("pak05_dir.vpk" to EntryKind.File), assertIs<FolderListing.Listed>(NioGameInventory.list(given, "addons")).kinds())
            assertEquals(FileObservation.Present(2, sha256Hex(bytes)), NioGameInventory.file(given, "game/citadel/addons/pak05_dir.vpk"))
        }
    }

    @Test
    fun anUnreadableFolderOrFileIsUnknownNotAbsent() = runBlocking {
        val root = root()
        val file = write(root, "game/citadel/addons/pak05_dir.vpk", byteArrayOf(1))
        withPermissions(file, "---------", "rw-------") {
            assertIs<FileObservation.Unreadable>(NioGameInventory.file(root, "game/citadel/addons/pak05_dir.vpk"))
        }
        withPermissions(file.parent, "---------", "rwx------") {
            assertIs<FolderListing.Unreadable>(NioGameInventory.list(root, "addons"))
            assertIs<FileObservation.Unreadable>(NioGameInventory.file(root, "game/citadel/addons/pak05_dir.vpk"))
        }
        Unit
    }

    private fun FolderListing.Listed.kinds() = entries.map { it.name to it.kind }

    @Test
    fun aVpkIsHashedWholeAndItsDirectoryReadInOnePass() = runBlocking {
        val root = root()
        val directory = FakeVpk.directory(embeddedBytes = 40_000, chunkReads = mapOf(0 to 7L), seed = 4)
        write(root, "game/citadel/grimoire/pak05_dir.vpk", directory)
        write(root, "game/citadel/addons/notes.vpk", byteArrayOf(1, 2, 3))
        val look = assertIs<VpkLook.Present>(NioGameInventory.vpk(root, CitadelPath("grimoire/pak05_dir.vpk")))
        assertEquals(Sha256.image(directory), look.image)
        assertEquals(CRC32().apply { update(directory) }.value, look.crc32)
        assertEquals(mapOf(0 to 7L), assertIs<VpkRead.Parsed>(look.directory).chunkExtents)
        val notes = assertIs<VpkLook.Present>(NioGameInventory.vpk(root, CitadelPath("addons/notes.vpk")))
        assertIs<VpkRead.Unreadable>(notes.directory)
        assertEquals(0x55BC801DL, notes.crc32, "a file that is no VPK is still measured whole")
        assertEquals(VpkLook.Absent, NioGameInventory.vpk(root, CitadelPath("addons/gone.vpk")))
    }

    @Test
    fun anImprintIsReadInTheSamePassThatHashesTheFile() = runBlocking {
        val root = root()
        val modinfo = """{"format":"vpk-modinfo","schemaVersion":1,"kind":"mod","writtenBy":{"tool":"grimoire","version":"1.22.0"},"writtenAt":"2026-07-03T18:24:07.512Z",
            "firstImprintedAt":"2026-05-11T09:02:44.108Z","game":{"name":"Deadlock","steamAppId":1422450,"gameBananaGameId":20948},
            "identity":{"sha256":"6f5902ac237024bdd0c176cb93063dc44c34fb724fe72f5a68be6c4022e9bb3f","size":18874368,"crc32":"3b7a2c91"},
            "title":"Neon Vindicta","source":{"gamebananaId":512345,"gamebananaFileId":987654,"section":"Mod"}}""".encodeToByteArray()
        val vindicta = Imprint(ModIdentity.File(SubmissionIdentity(SubmissionKind.Mod, 512_345, 987_654, null)), FilePrint(18_874_368, 0x3B7A2C91))
        for (preload in listOf(false, true)) {
            val vpk = FakeVpk.withRootFile("modinfo.json", modinfo, preload) + ByteArray(300_000) { it.toByte() }
            write(root, "game/citadel/addons/pak07_dir.vpk", vpk)
            val look = assertIs<VpkLook.Present>(NioGameInventory.vpk(root, CitadelPath("addons/pak07_dir.vpk")))
            assertEquals(vindicta, look.imprint, "preload $preload")
            assertEquals(Sha256.image(vpk), look.image, "the file is still hashed whole")
            assertEquals(CRC32().apply { update(vpk) }.value, look.crc32)
        }
        write(root, "game/citadel/addons/pak08_dir.vpk", FakeVpk.directory(seed = 8))
        assertEquals(null, assertIs<VpkLook.Present>(NioGameInventory.vpk(root, CitadelPath("addons/pak08_dir.vpk"))).imprint)
    }
}
