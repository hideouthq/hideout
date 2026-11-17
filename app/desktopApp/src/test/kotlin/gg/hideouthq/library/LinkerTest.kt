package gg.hideouthq.library

import gg.hideouthq.core.data.PublishResult
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkerTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "linker-tests")), "home").toRealPath()
    private val bytes = "the mod's bytes".encodeToByteArray()
    private val refusing = Linker { link, _ -> throw FileSystemException(link.toString(), null, "Operation not permitted") }

    @AfterTest
    fun cleanUp() = deleteTree(home)

    private fun names(): Set<String> = Files.list(home).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }

    @Test
    fun aDriveThatMakesHardLinksGetsOneAndTheSameBytesElsewhereAreAnotherFile() {
        val linker = Linker()
        val first = home.resolve("a").apply { writeBytes(bytes) }
        assertEquals(PublishResult.Published, linker.link(first, home.resolve("b")))
        assertTrue(Files.isSameFile(first, home.resolve("b")))
        assertTrue(linker.same(first, home.resolve("b")))
        home.resolve("c").writeBytes(bytes)
        assertFalse(linker.same(first, home.resolve("c")), "where links are made, only a link is one file")
        assertTrue(linker.links(home))
        assertEquals(setOf("a", "b", "c"), names(), "the trial names went")
    }

    @Test
    fun aDriveThatRefusesThemGetsACheckedCopyUnderTheNameAndNothingBeside() {
        val first = home.resolve("a").apply { writeBytes(bytes) }
        assertEquals(PublishResult.Published, refusing.link(first, home.resolve("b")))
        assertFalse(Files.isSameFile(first, home.resolve("b")))
        assertContentEquals(bytes, home.resolve("b").readBytes())
        assertTrue(refusing.same(first, home.resolve("b")), "the same bytes count as one file")
        assertFalse(refusing.links(home))
        assertEquals(setOf("a", "b"), names(), "no copy or trial is left under a name of its own")

        home.resolve("b").writeBytes("edited".encodeToByteArray() + bytes.copyOf(bytes.size - 6))
        assertFalse(refusing.same(first, home.resolve("b")), "other bytes are another file, even at the same size")
    }

    @Test
    fun aCopyNeverTakesANameThatIsTaken() {
        val first = home.resolve("a").apply { writeBytes(bytes) }
        val other = "someone else's".encodeToByteArray()
        home.resolve("b").writeBytes(other)
        assertEquals(PublishResult.Taken, refusing.link(first, home.resolve("b")))
        assertContentEquals(other, home.resolve("b").readBytes())
        assertEquals(setOf("a", "b"), names())
        assertEquals(PublishResult.Failed("c is not there"), refusing.link(home.resolve("c"), home.resolve("d")))
    }

    @Test
    fun aLinkThatFailsWhereLinksAreMadeIsReportedNotCopied() {
        var refuse = true
        val linker = Linker { link, existing -> if (refuse && link.fileName.toString() == "b") throw FileSystemException(link.toString(), null, "Too many links") else Files.createLink(link, existing) }
        val first = home.resolve("a").apply { writeBytes(bytes) }
        assertFailsWith<FileSystemException> { linker.link(first, home.resolve("b")) }
        assertEquals(setOf("a"), names())
        refuse = false
        assertEquals(PublishResult.Published, linker.link(first, home.resolve("b")))
    }
}
