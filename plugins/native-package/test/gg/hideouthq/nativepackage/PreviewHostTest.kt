package gg.hideouthq.nativepackage

import java.util.HexFormat
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class PreviewHostTest {
    private val root = createTempDirectory("preview-host")
    private val localFile = root / "preview-build.properties"
    private val output = root / "generated"
    private val written = output / "gg" / "example" / "App" / "preview.bin"

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun write(vararg environment: Pair<String, String>) = writePreviewHostFile("gg.example.App", localFile, output, mapOf(*environment)::get)

    private val key = "a-key-0123456789abcdef0123456789abcdef"

    @Test
    fun aBuildWithNeitherValueWritesNothingAndDropsWhatAnEarlierBuildWrote() {
        write()
        assertFalse(written.exists())
        write(PreviewUrlVariable to "https://previews.example.test", PreviewKeyVariable to key)
        assertTrue(written.exists())
        write()
        assertFalse(written.exists())
    }

    @Test
    fun oneValueWithoutTheOtherOrAShortKeyStopsTheBuild() {
        for (environment in listOf(arrayOf(PreviewUrlVariable to "https://previews.example.test"), arrayOf(PreviewKeyVariable to key))) {
            assertContains(assertFailsWith<IllegalStateException> { write(*environment) }.message.orEmpty(), "or neither")
        }
        localFile.writeText("$PreviewKeyVariable=$key\n")
        assertFailsWith<IllegalStateException> { write() }
        assertContains(
            assertFailsWith<IllegalStateException> { write(PreviewUrlVariable to "https://previews.example.test", PreviewKeyVariable to "short") }.message.orEmpty(),
            "shorter than 32",
        )
        assertFalse(written.exists())
    }

    @Test
    fun theEnvironmentComesBeforeTheLocalFileAndNeitherValueIsStoredReadably() {
        localFile.writeText("$PreviewUrlVariable=https://file.example.test\n$PreviewKeyVariable=file-key-0123456789abcdef0123456789\n")
        write()
        assertContentEquals(maskPreviewHost("https://file.example.test", "file-key-0123456789abcdef0123456789"), written.readBytes())
        write(PreviewUrlVariable to "https://environment.example.test", PreviewKeyVariable to " environment-key-0123456789abcdef01 ")
        val stored = written.readBytes()
        assertContentEquals(maskPreviewHost("https://environment.example.test", "environment-key-0123456789abcdef01"), stored)
        for (plain in listOf("https://environment.example.test", "environment.example", "environment-key")) {
            assertFalse(String(stored, Charsets.ISO_8859_1).contains(plain), plain)
        }
    }

    @Test
    fun theMaskMatchesTheKnownAnswerTheAppUnmasks() {
        assertEquals(
            "33681c7e7dbbae392f000d7afd302fe99720869cbb5c0c34febf3adb90e42adcfbeb2e90eadd69d40110fc33589711decb912c3c18725bd5722e329197fcbfc48baa8ad412271f",
            HexFormat.of().formatHex(maskPreviewHost("https://previews.example.test", "known-key")),
        )
    }
}
