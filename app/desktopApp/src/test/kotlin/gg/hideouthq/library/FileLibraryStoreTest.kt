package gg.hideouthq.library

import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ManifestCodec
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileLibraryStoreTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "library-store-tests")), "home").toRealPath()
    private val opened = mutableListOf<FileLibraryStore>()

    @AfterTest
    fun closeStores() {
        opened.forEach { it.close() }
        deleteTree(home)
    }

    private fun open(directory: Path): FileLibraryStore = FileLibraryStore(directory).also { opened += it }

    private fun store(sub: String = "data"): FileLibraryStore = open(home.resolve(sub).also { it.createDirectories() })

    private fun record(number: Int = 5, title: String = "Nicole over Pocket") = ManifestRecord(
        installId = "install-$number",
        gameRoot = GameRoot("/games/Deadlock"),
        title = title,
        placement = Placement.On(Slot(number)),
        sets = listOf(
            InstalledSet(
                source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, "0332f45e5015186cb47219853f499a25")),
                originalName = "nicole.zip",
                variant = "Default",
                origin = SetOrigin.Downloaded(ArchiveIdentity("/downloads/nicole.zip", 1_000L, "a".repeat(64))),
                version = null,
                componentKey = "pak01_dir.vpk",
                files = listOf(SetFile(VpkPart.Directory, FileImage(1_000L, "b".repeat(64)))),
            ),
        ),
    )

    private val record = record()

    private val manifest = LibraryManifest(records = listOf(record))

    private fun directory(name: String): Path = home.resolve(name).also { it.createDirectories() }

    private fun saved(directory: Path): FileLibraryStore = runBlocking {
        open(directory).also { assertEquals(ManifestSave.Saved, it.save(manifest)) }
    }

    private fun Path.entries(): Set<String> = Files.list(this).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }

    private inline fun <T> withPermissions(path: Path, permissions: String, restore: String, block: () -> T): T {
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions))
        try {
            return block()
        } finally {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(restore))
        }
    }

    @Test
    fun loadingBeforeAnyWriteIsAbsent() = runBlocking {
        assertEquals(ManifestLoad.Absent, store().load())
        assertEquals(ManifestLoad.Absent, open(home.resolve("never-created")).load())
        val leftovers = directory("leftovers").also { it.resolve("library.json.123.tmp").writeText("{}") }
        assertEquals(ManifestLoad.Absent, open(leftovers).load(), "a temp file left beside no library.json is no library")
    }

    @Test
    fun savingCreatesTheDirectoryIfMissing() = runBlocking {
        val library = open(home.resolve("not-yet-created"))
        assertEquals(ManifestSave.Saved, library.save(manifest))
        assertTrue(home.resolve("not-yet-created/library.json").exists())
    }

    @Test
    fun aLinkedOrDanglingManifestOrDataFolderIsUnreadable() = runBlocking {
        val dangling = directory("dangling").also { Files.createSymbolicLink(it.resolve("library.json"), it.resolve("gone.json")) }
        assertIs<ManifestLoad.Unreadable>(open(dangling).load())

        val elsewhere = directory("elsewhere").resolve("real.json").also { it.writeText(ManifestCodec.encode(manifest)) }
        val linked = directory("linked").also { Files.createSymbolicLink(it.resolve("library.json"), elsewhere) }
        val library = open(linked)
        assertIs<ManifestLoad.Unreadable>(library.load())
        assertIs<ManifestSave.Refused>(library.save(LibraryManifest()))
        assertEquals(ManifestCodec.encode(manifest), elsewhere.readText(), "the link target is untouched")

        val moved = home.resolve("broken-data").also { Files.createSymbolicLink(it, home.resolve("data-that-was-moved")) }
        assertIs<ManifestLoad.Unreadable>(open(moved).load())
        Unit
    }

    @Test
    fun aDataFolderOrManifestThatCannotBeReadIsUnreadable() = runBlocking {
        val unlistable = directory("unlistable").also { saved(it).close() }
        assertIs<ManifestLoad.Unreadable>(withPermissions(unlistable, "---------", "rwx------") { open(unlistable).load() })
        val unreadable = directory("unreadable-primary").also { saved(it).close() }
        assertIs<ManifestLoad.Unreadable>(withPermissions(unreadable.resolve("library.json"), "---------", "rw-------") { open(unreadable).load() })
        Unit
    }

    @Test
    fun aManifestOfExactlyTheCapLoadsAndOneByteMoreDoesNot() = runBlocking {
        val empty = ManifestCodec.encode(LibraryManifest())
        val atCap = directory("at-cap").resolve("library.json").also { it.writeText(empty + " ".repeat(ManifestCodec.MaxBytes - empty.length)) }
        assertEquals(ManifestCodec.MaxBytes.toLong(), Files.size(atCap))
        assertEquals(ManifestLoad.Loaded(LibraryManifest()), open(atCap.parent).load())

        val overCap = directory("over-cap").resolve("library.json").also { it.writeText(empty + " ".repeat(ManifestCodec.MaxBytes - empty.length + 1)) }
        assertIs<ManifestLoad.Unreadable>(open(overCap.parent).load())
        Unit
    }

    @Test
    fun aManifestHideoutCannotTrustIsUnreadableAndNeverSavedOver() = runBlocking {
        val untrusted = mapOf(
            "not-utf8" to ManifestCodec.encode(LibraryManifest()).encodeToByteArray() + byteArrayOf(0xC3.toByte()),
            "malformed" to "{not json".encodeToByteArray(),
            "unsupported-schema" to """{"schemaVersion":99,"records":[]}""".encodeToByteArray(),
        )
        for ((name, bytes) in untrusted) {
            val directory = directory(name).also { it.resolve("library.json").writeBytes(bytes) }
            val store = open(directory)
            assertIs<ManifestLoad.Unreadable>(store.load(), name)
            assertIs<ManifestSave.Refused>(store.save(LibraryManifest()), name)
            assertContentEquals(bytes, directory.resolve("library.json").readBytes(), name)
        }
        val folder = directory("not-a-file").also { it.resolve("library.json").createDirectories() }
        assertIs<ManifestLoad.Unreadable>(open(folder).load())
        Unit
    }

    @Test
    fun savingIsAtomicAndLeavesNoTempFileBehind() = runBlocking {
        val directory = directory("atomic")
        saved(directory).save(LibraryManifest(records = listOf(record(6))))
        assertEquals(setOf("library.json", "hideout.lock"), directory.entries())
    }

    @Test
    fun anOversizedSaveIsRefusedAndLeavesTheManifestUnchanged() = runBlocking {
        val directory = directory("oversized-save")
        val library = saved(directory)
        val primary = directory.resolve("library.json").readBytes()
        val huge = LibraryManifest(records = listOf(record(7, title = "€".repeat(3_000_000))))
        assertTrue(ManifestCodec.encode(huge).length < ManifestCodec.MaxBytes, "under the limit in characters, over it in bytes")

        assertIs<ManifestSave.Refused>(library.save(huge))
        assertContentEquals(primary, directory.resolve("library.json").readBytes())
        assertEquals(setOf("library.json", "hideout.lock"), directory.entries())
    }

    @Test
    fun aSaveThatCannotPublishIsRefusedAndLeavesTheManifestUnchanged() = runBlocking {
        val directory = directory("read-only")
        val library = saved(directory)
        val primary = directory.resolve("library.json").readBytes()

        val refused = withPermissions(directory, "r-x------", "rwx------") { library.save(LibraryManifest(records = listOf(record(9)))) }
        assertIs<ManifestSave.Refused>(refused)
        assertContentEquals(primary, directory.resolve("library.json").readBytes())
        assertEquals(setOf("library.json", "hideout.lock"), directory.entries())
        assertEquals(ManifestLoad.Loaded(manifest), library.load())
    }

    @Test
    fun theManifestSurvivesAcrossSeparateStoreInstances() = runBlocking {
        val directory = directory("restart")
        open(directory).use { it.save(manifest) }
        assertEquals(ManifestLoad.Loaded(manifest), open(directory).load())
    }

    @Test
    fun aSecondStoreOnTheSameFolderIsRefusedUntilTheFirstIsClosed() = runBlocking<Unit> {
        val directory = directory("two-owners")
        val first = open(directory)
        assertEquals(ManifestSave.Saved, first.save(manifest))
        val saved = directory.resolve("library.json").readBytes()

        val second = open(directory)
        assertIs<ManifestLoad.Unreadable>(second.load())
        assertIs<ManifestSave.Refused>(second.save(LibraryManifest()))
        val alias = home.resolve("two-owners-alias").also { Files.createSymbolicLink(it, directory) }
        assertIs<ManifestLoad.Unreadable>(open(alias).load(), "another spelling of the folder is the same folder")
        assertContentEquals(saved, directory.resolve("library.json").readBytes())
        assertEquals(ManifestLoad.Loaded(manifest), first.load(), "the first owner carries on")

        first.close()
        val reopened = open(directory)
        assertEquals(ManifestLoad.Loaded(manifest), reopened.load())
        assertIs<ManifestLoad.Unreadable>(first.load(), "a store that let go is refused while another holds the folder")
    }

    @Test
    fun aStoreInAnotherProcessHoldsTheFolderUntilThatProcessExits() = runBlocking<Unit> {
        val directory = directory("other-process")
        val child = holder(directory)
        val first = child.inputReader().readLine()
        assertEquals("held", first, home.resolve("holder.err").takeIf { Files.exists(it) }?.toFile()?.readText())
        assertIs<ManifestLoad.Unreadable>(open(directory).load())
        assertIs<ManifestSave.Refused>(open(directory).save(manifest))
        assertEquals(setOf("hideout.lock"), directory.entries())

        child.outputStream.close()
        assertTrue(child.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, child.exitValue())
        assertEquals(ManifestLoad.Absent, open(directory).load(), "the folder is free once the other process is gone")
        assertIs<ManifestLoad.Unreadable>(open(directory).load())

        val refused = holder(directory)
        assertTrue(refused.inputReader().readLine().startsWith("refused"), "a refused second store here has not let go of the lock")
        refused.outputStream.close()
        assertTrue(refused.waitFor(30, TimeUnit.SECONDS))
    }

    private fun holder(directory: Path): Process {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(java, "-cp", testClassPath(), LibraryHolder::class.java.name, directory.toString())
            .redirectError(home.resolve("holder.err").toFile())
            .start()
    }

    private fun testClassPath(): String {
        val entries = LinkedHashSet(System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() })
        generateSequence(LibraryHolder::class.java.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.urLs.forEach { entries += Path.of(it.toURI()).toString() }
        }
        return entries.joinToString(File.pathSeparator)
    }
}

object LibraryHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val loaded = runBlocking { FileLibraryStore(Path.of(args.single())).load() }
        println(if (loaded is ManifestLoad.Unreadable) "refused: ${loaded.reason}" else "held")
        System.out.flush()
        while (System.`in`.read() >= 0) Unit
    }
}
