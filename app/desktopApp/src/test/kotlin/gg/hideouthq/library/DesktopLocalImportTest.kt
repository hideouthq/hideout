package gg.hideouthq.library

import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.core.data.LocalArchiveSnapshot
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.LocalImportResult
import gg.hideouthq.core.data.LocalRefusal
import gg.hideouthq.core.data.isSupportedImport
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.preview.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopLocalImportTest {
    private val project = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("project.yaml")) }
    private val home = Files.createTempDirectory(Files.createDirectories(project.resolve("build/profiles/import-ownership-fixtures")), "case-")
    private val copies = home.resolve("copies")
    private val importer = DesktopLocalImport(copies)

    private fun copiesLeft(): List<Path> = if (Files.exists(copies)) Files.list(copies).use { it.toList() } else emptyList()

    @Test fun supportedTypesAndUnsupportedFeedback() = runBlocking {
        listOf("zip", "7z", "rar", "vpk", "ZIP", "VPK").forEach { extension ->
            val file = home.resolve("mod.$extension").toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }.asLocalFile()
            assertTrue(file.isSupportedImport())
            val ready = assertIs<LocalImportResult.Ready>(importer.snapshot(file))
            assertEquals(file.name, ready.snapshot.originalName)
            importer.release(ready.snapshot)
        }
        val bad = LocalFile(home.resolve("mod.exe").toString(), "mod.exe", 3)
        assertFalse(bad.isSupportedImport())
        assertEquals(LocalRefusal.UnsupportedType, assertIs<LocalImportResult.Refused>(importer.snapshot(bad)).reason)
        assertEquals(LocalRefusal.Unreadable, assertIs<LocalImportResult.Refused>(importer.snapshot(LocalFile(home.resolve("gone.zip").toString(), "gone.zip", 3))).reason)
        for (part in listOf("mod.7z.001", "mod.zip.002", "mod.z01", "mod.r00")) {
            val file = home.resolve(part).toFile().apply { writeBytes(byteArrayOf(1)) }.asLocalFile()
            assertEquals(LocalRefusal.SplitArchive, assertIs<LocalImportResult.Refused>(importer.snapshot(file)).reason, part)
        }
        val empty = home.resolve("empty.vpk").toFile().apply { writeBytes(ByteArray(0)) }.asLocalFile()
        assertEquals(LocalRefusal.Empty, assertIs<LocalImportResult.Refused>(importer.snapshot(empty)).reason)
        assertEquals(emptyList(), copiesLeft(), "a refused snapshot leaves no copy")
    }

    @Test fun aSnapshotIsACopyThatOutlivesItsSourceAndReleaseDeletesOnlyIt() = runBlocking {
        val bytes = byteArrayOf(4, 5, 6)
        val source = home.resolve("mod.zip").toFile().apply { writeBytes(bytes) }
        val snapshot = assertIs<LocalImportResult.Ready>(importer.snapshot(source.asLocalFile())).snapshot
        val path = Path.of(snapshot.archive.path)
        assertEquals(ArchiveIdentity(path.toString(), 3, sha256Hex(bytes)), snapshot.archive)
        source.writeBytes(byteArrayOf(9))
        assertContentEquals(bytes, Files.readAllBytes(path))
        importer.release(LocalArchiveSnapshot(snapshot.archive.copy(path = source.absolutePath), source.name))
        assertTrue(source.exists())
        importer.release(snapshot)
        importer.release(snapshot)
        assertFalse(Files.exists(path))
        assertContentEquals(byteArrayOf(9), source.readBytes())
    }

    @Test fun sourceAliasesPreserveUnicodeDisplayNameAndSnapshotBytes() = runBlocking {
        val source = home.resolve("original.zip").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val link = Files.createSymbolicLink(home.resolve("選んだ skin.zip"), source)
        val linkedFolder = Files.createSymbolicLink(home.resolve("Downloads リンク"), home)
        for (selected in listOf(link, linkedFolder.resolve(link.fileName))) {
            val snapshot = assertIs<LocalImportResult.Ready>(importer.snapshot(selected.toFile().asLocalFile())).snapshot
            assertEquals("選んだ skin.zip", snapshot.originalName)
            assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(Path.of(snapshot.archive.path)))
            importer.release(snapshot)
        }
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(source))
    }

    @Test fun anotherHideoutStartingLeavesACopyThatGoesOnlyWithStagingOnceTheLibraryIsHeld() = runBlocking {
        val http = HttpClient(MockEngine { error("no request is made") })
        val first = DesktopInstall.ports(home, home.resolve("cache"), http, http, BusyHosts(), log = {})
        val file = home.resolve("mod.zip").toFile().apply { writeBytes(byteArrayOf(1)) }.asLocalFile()
        val copy = Path.of(assertIs<LocalImportResult.Ready>(first.localImport.snapshot(file)).snapshot.archive.path)
        val second = DesktopInstall.ports(home, home.resolve("cache"), http, http, BusyHosts(), log = {})
        assertTrue(Files.exists(copy), "a second Hideout starting leaves the first one's copy")
        second.installer.discardAllStaging()
        assertFalse(Files.exists(copy), "the library discards staging, copies included, once it holds the install list")
    }
}
