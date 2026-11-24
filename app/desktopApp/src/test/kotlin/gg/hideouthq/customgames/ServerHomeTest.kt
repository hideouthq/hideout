package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.Sha256
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ServerHomeTest {
    private val root: Path = Files.createTempDirectory("hideout-server-home")
    private val logs = mutableListOf<String>()
    private val home = ServerHome(root.resolve("customgames-server"), logs::add)

    @AfterTest
    fun cleanUp() {
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        root.toFile().deleteRecursively()
    }

    @Test
    fun everyRuntimeRunsFromTheSamePathWithItsOwnFilesBesideIt() {
        val first = runtime("1", "host 1", "lib/file" to "library 1", "old.dll" to "gone later")
        val second = runtime("2", "host 2", "lib/file" to "library 2")

        val program = home.take(first, digest("1"))
        assertEquals(root.resolve("customgames-server/modlock-host.exe"), program)
        assertEquals("host 1", program.readText())
        assertEquals("library 1", program.resolveSibling("lib/file").readText())

        assertEquals(program, home.take(second, digest("2")))
        assertEquals("host 2", program.readText())
        assertEquals("library 2", program.resolveSibling("lib/file").readText())
        assertFalse(program.resolveSibling("old.dll").exists(), "nothing of the last runtime stays to load beside the new one")
    }

    @Test
    fun theSameRuntimeIsNotCopiedAgain() {
        val source = runtime("1", "host 1")
        val program = home.take(source, digest("1"))
        program.writeText("still the copy")

        assertEquals(program, home.take(source, digest("1")))
        assertEquals("still the copy", program.readText())
    }

    @Test
    fun aServerThatCannotBeCopiedRunsFromItsPackage() {
        val source = runtime("1", "host 1")
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-x------"))

        assertEquals(source.resolve("modlock-host.exe"), home.take(source, digest("1")))
        assertEquals(1, logs.size)
    }

    private fun runtime(name: String, host: String, vararg files: Pair<String, String>): Path {
        val folder = Files.createDirectories(root.resolve("runtime $name"))
        folder.resolve("modlock-host.exe").writeText(host)
        for ((path, text) in files) {
            val file = folder.resolve(path)
            Files.createDirectories(file.parent)
            file.writeText(text)
        }
        return folder
    }

    private fun digest(seed: String) = Sha256.parse(seed.repeat(64))!!
}
