package gg.hideouthq.data.preview

import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.HexFormat
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewHostTest {
    private val directory: Path = Files.createTempDirectory("preview-host")
    private val missing: Path = directory.resolve("absent.properties")
    private val built = "https://built.example.test"

    private fun userFile(vararg lines: String): Path = directory.resolve("hideout.properties").also { Files.write(it, lines.toList()) }

    @Test
    fun thePropertyOverridesTheEnvironmentWhichOverridesTheUsersFile() {
        val file = userFile("hideout.preview.baseUrl=https://file.example/")
        assertEquals("https://property.example", PreviewHost.resolve("https://property.example/", "https://environment.example", file, built))
        assertEquals("https://environment.example", PreviewHost.resolve(null, "https://environment.example", file, built))
        assertEquals("https://environment.example", PreviewHost.resolve("  ", "https://environment.example", file, built))
        assertEquals("https://file.example", PreviewHost.resolve(null, null, file, built))
        assertEquals("https://file.example", PreviewHost.resolve("", "", file, null))
    }

    @Test
    fun withNothingSetTheAppUsesTheHostItWasBuiltWith() {
        assertEquals(built, PreviewHost.resolve(null, null, missing, built))
        assertEquals(built, PreviewHost.resolve(null, null, userFile("# no host yet", "other.key=https://other.example"), built))
        assertEquals(built, PreviewHost.resolve(null, null, directory, built))
    }

    @Test
    fun aBuildWithoutAHostHasNoPreviews() {
        assertNull(PreviewHost.resolve(null, null, missing, null))
    }

    @Test
    fun aBlankUserSettingOrAValueThatIsNotAHostDisablesPreviews() {
        assertNull(PreviewHost.resolve(null, null, userFile("hideout.preview.baseUrl="), built))
        assertNull(PreviewHost.resolve("ftp://property.example", "https://environment.example", userFile("hideout.preview.baseUrl=https://file.example"), built))
    }

    @Test
    fun theAppUnmasksWhatThePluginStores() {
        val stored = HexFormat.of().parseHex(
            "33681c7e7dbbae392f000d7afd302fe99720869cbb5c0c34febf3adb90e42adcfbeb2e90eadd69d40110fc33589711decb912c3c18725bd5722e329197fcbfc48baa8ad412271f",
        )
        assertEquals("https://previews.example.test" to "known-key", PreviewHost.unmask(stored))
    }

    @Test
    fun theBuiltAddressAndKeyAreNotReadableInTheBuildsOutput() {
        val stored = PreviewHost::class.java.getResourceAsStream(PreviewHost.Resource)?.use { it.readBytes() }
        assumeTrue("this build has no preview host", stored != null)
        val checkout = File(System.getProperty("user.dir")).resolve("../..").canonicalFile
        val plain = PreviewHost.unmask(stored!!).toList()
        val outputs = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).canonicalFile }.filter { it.startsWith(checkout) && it.exists() }
        assertTrue(outputs.isNotEmpty(), "no build output of $checkout on the classpath")
        var files = 0
        fun check(name: String, bytes: ByteArray) {
            files++
            val text = String(bytes, Charsets.ISO_8859_1)
            for (value in plain) assertTrue(value !in text, "$name holds the preview host's address or key")
        }
        for (output in outputs) {
            if (output.isDirectory) {
                output.walk().filter { it.isFile }.forEach { check(it.path, it.readBytes()) }
            } else {
                ZipFile(output).use { zip -> zip.entries().asSequence().filter { !it.isDirectory }.forEach { check("$output!${it.name}", zip.getInputStream(it).readBytes()) } }
            }
        }
        assertTrue(files > 100, "only $files files read from $outputs")
    }
}
