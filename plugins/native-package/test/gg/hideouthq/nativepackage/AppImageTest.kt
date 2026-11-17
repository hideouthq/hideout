package gg.hideouthq.nativepackage

import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.isExecutable
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class AppImageTest {
    private val root = createTempDirectory("native-package-appimage")
    private val settings = testSettings(root)

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun theDesktopEntryNamesTheAppAndTheWindowClassAwtGivesTheMainClass() {
        assertEquals(
            """
            [Desktop Entry]
            Type=Application
            Name=Example
            Comment=An example app
            Exec=/opt/example/Example %u
            MimeType=x-scheme-handler/example;
            Icon=com.example.app
            Terminal=false
            Categories=Game;
            StartupWMClass=com-example-MainKt

            """.trimIndent(),
            desktopEntry(settings, "com.example.MainKt", exec = "/opt/example/Example", icon = "com.example.app"),
        )
    }

    @Test
    fun anAppDirStartsTheExecutableAndCarriesTheEntryAndIconUnderTheId() {
        val folder = (root / "image").createDirectories()
        (folder / "Example").writeText("executable")
        settings.linuxIcon.writeText("png")

        makeAppDir(folder, settings, "com.example.MainKt")

        assertEquals((folder / "Example").toRealPath(), (folder / "AppRun").toRealPath())
        assertEquals(desktopEntry(settings, "com.example.MainKt", exec = "Example", icon = "com.example.app"), (folder / "com.example.app.desktop").readText())
        assertEquals("png", (folder / "com.example.app.png").readText())
        assertEquals((folder / "com.example.app.png").toRealPath(), (folder / ".DirIcon").toRealPath())
        assertEquals(1, folder.listDirectoryEntries("*.desktop").size, "appimagetool takes exactly one entry at the top")
        assertEquals("appimage", (folder / "package-format").readText())
    }

    @Test
    fun aPinnedDownloadIsKeptExecutableOnlyWhenItsHashMatches() {
        val published = (root / "published" / "tool").also { it.parent.createDirectories(); it.writeText("tool bytes") }
        val tools = root / "tools"

        val wrong = Pinned(published.toUri().toString(), sha256Of("other bytes"))
        assertFailsWith<IllegalStateException> { download(wrong, tools) }
        assertEquals(emptyList(), tools.listDirectoryEntries(), "a mismatched download leaves nothing behind")

        val right = Pinned(published.toUri().toString(), sha256Of("tool bytes"))
        val tool = download(right, tools)
        assertEquals("tool", tool.name)
        assertEquals("tool bytes", tool.readText())
        assertTrue(tool.isExecutable())
    }

    @Test
    fun aToolAlreadyDownloadedWithTheRightHashIsNotFetchedAgain() {
        val published = (root / "published" / "tool").also { it.parent.createDirectories(); it.writeText("tool bytes") }
        val pinned = Pinned(published.toUri().toString(), sha256Of("tool bytes"))
        download(pinned, root / "tools")
        published.toFile().delete()

        assertEquals("tool bytes", download(pinned, root / "tools").readText())
    }

    private fun sha256Of(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
