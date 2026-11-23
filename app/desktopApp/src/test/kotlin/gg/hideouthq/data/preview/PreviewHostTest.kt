package gg.hideouthq.data.preview

import gg.hideouthq.BuildMask
import gg.hideouthq.data.BuildSecrets
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
            "0c8de5ff7f34cab65c721b5463a7ecbb05626cc4c011a28c663668fdc20db3ae8ae94f5b57657a3df082c2854d907635b387d5ed658b3988",
        )
        assertEquals("https://previews.example.test:8443/a-path-past-one-block", BuildSecrets.unmask(ByteArray(32) { it.toByte() }, "preview-url", stored).decodeToString())
    }

    @Test
    fun aBuildWithoutTheValuesHasNoBuiltHost() {
        assumeTrue("this build has a preview host", PreviewHost::class.java.getResource(BuildSecrets.resource("preview-key")) == null)
        assertNull(PreviewHost.built)
    }

    @Test
    fun theBuiltValuesAndTheirMaskAreNotReadableInTheBuildsOutput() {
        val mask = BuildMask.bytes()
        val plain = listOf("preview-url", "preview-key", "posthog-key").mapNotNull { name ->
            PreviewHost::class.java.getResourceAsStream(BuildSecrets.resource(name))?.use { BuildSecrets.unmask(mask, name, it.readBytes()).decodeToString() }
        }
        assumeTrue("this build has no secrets", plain.isNotEmpty())
        val checkout = File(System.getProperty("user.dir")).resolve("../..").canonicalFile
        val outputs = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).canonicalFile }.filter { it.startsWith(checkout) && it.exists() }
        assertTrue(outputs.isNotEmpty(), "no build output of $checkout on the classpath")
        var files = 0
        fun check(name: String, bytes: ByteArray) {
            files++
            val text = String(bytes, Charsets.ISO_8859_1)
            for (value in plain + String(mask, Charsets.ISO_8859_1)) assertTrue(value !in text, "$name holds a built value or the mask")
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
