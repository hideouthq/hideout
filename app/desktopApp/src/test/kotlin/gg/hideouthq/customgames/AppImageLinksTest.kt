package gg.hideouthq.customgames

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppImageLinksTest {
    private val folder = Files.createTempDirectory("hideout-appimage-links")
    private val applications = folder.resolve("applications")
    private val entry = applications.resolve("gg.hideouthq.Hideout-links.desktop")
    private val calls = mutableListOf<List<String>>()
    private var currentDefault = ""

    @AfterTest
    fun cleanUp() = folder.toFile().deleteRecursively().let {}

    private fun register(appImage: String) = registerHandler(Path.of(appImage), applications) { arguments ->
        calls += arguments
        if (arguments.first() == "default") currentDefault = arguments[1]
        // Like the real one, with no default stated it answers with any entry that lists the type.
        if (arguments.first() == "query") currentDefault.ifEmpty { if (Files.exists(entry)) entry.fileName.toString() else "" } else ""
    }

    @Test
    fun theFirstRunBecomesTheHandlerWithAHiddenEntryForItsOwnFile() {
        register("/home/me/Apps/Hideout-1.1.0-x86_64.AppImage")
        val text = Files.readString(entry)
        assertContains(text, "Exec=/home/me/Apps/Hideout-1.1.0-x86_64.AppImage %u\n")
        assertContains(text, "MimeType=x-scheme-handler/hideout;\n")
        assertContains(text, "NoDisplay=true\n")
        assertEquals("gg.hideouthq.Hideout-links.desktop", currentDefault)
    }

    @Test
    fun aMovedFileRewritesItsEntryAndStaysTheHandler() {
        register("/home/me/Apps/Hideout.AppImage")
        register("/home/me/Games/Hideout.AppImage")
        assertContains(Files.readString(entry), "Exec=/home/me/Games/Hideout.AppImage %u\n")
        assertEquals(1, calls.count { it.first() == "default" })
    }

    @Test
    fun aHandlerAlreadyChosenStays() {
        currentDefault = "gg.hideouthq.Hideout.desktop"
        register("/home/me/Apps/Hideout.AppImage")
        assertEquals("gg.hideouthq.Hideout.desktop", currentDefault)
        assertFalse(calls.any { it.first() == "default" })
    }

    @Test
    fun aPathWithSpecialCharactersIsQuotedTheWayDesktopEntriesAsk() {
        val text = assertNotNullEntry("/home/me/My Games/100%/\$x\"y.AppImage")
        assertContains(text, "Exec=\"/home/me/My Games/100%%/\\\\\$x\\\\\"y.AppImage\" %u\n")
    }

    @Test
    fun aPathWithALineBreakRegistersNothing() {
        assertNull(handlerEntry(Path.of("/home/me/a\nExec=evil")))
        register("/home/me/a\nb")
        assertFalse(Files.exists(entry))
        assertTrue(calls.isEmpty())
    }

    private fun assertNotNullEntry(path: String): String = handlerEntry(Path.of(path)) ?: throw AssertionError("no entry for $path")
}
