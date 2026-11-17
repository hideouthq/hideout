package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.GameRevision
import gg.hideouthq.core.library.GameRoot
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteamInfTest {
    private val root = Files.createTempDirectory("hideout-steam-inf")
    private val game = GameRoot(root.toString())

    @AfterTest
    fun cleanUp() = root.toFile().deleteRecursively().let {}

    private fun write(text: String) {
        val file = root.resolve("game/citadel/steam.inf")
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    @Test
    fun theRevisionIsReadFromTheGamesFile() {
        write("ClientVersion=6844\r\nServerVersion=6844\r\nSourceRevision=11201066\r\nVersionDate=Nov 03 2026\r\n")
        assertEquals(GameRevision(11201066), SteamInf.revision(game))
    }

    @Test
    fun aMissingFileOrLineIsNoRevision() {
        assertNull(SteamInf.revision(game))
        write("ClientVersion=6844\n")
        assertNull(SteamInf.revision(game))
        write("SourceRevision=soon\n")
        assertNull(SteamInf.revision(game))
    }
}
