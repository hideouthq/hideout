package gg.hideouthq.library

import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SteamLauncherTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "steam-launcher-tests")), "home").toRealPath()
    private val commands = mutableListOf<List<String>>()
    private val launcher = SteamLauncher(
        steamInstalls = { steamInstallCandidates(windows = false, home.toString(), { null }, { _, _, _ -> null }) },
        handoff = DesktopHandoff("Linux", native = { error("Linux must not use AWT") }, start = { commands += it; DesktopHandoffTest.FinishedProcess(0) }),
    )

    @AfterTest
    fun clean() = deleteTree(home)

    private fun file(relative: String, text: String = ""): Path = home.resolve(relative).also { it.parent.createDirectories(); it.writeText(text) }

    private fun deadlock(steam: String, library: String): GameRoot {
        file("$steam/steamapps/libraryfolders.vdf", "\"libraryfolders\" { \"0\" { \"path\" \"${home.resolve(library)}\" } }")
        file("$library/steamapps/appmanifest_1422450.acf", "\"AppState\" { \"installdir\" \"Deadlock\" }")
        file("$library/steamapps/common/Deadlock/game/citadel/gameinfo.gi", "\"GameInfo\" {}")
        return GameRoot(home.resolve("$library/steamapps/common/Deadlock").toString())
    }

    @Test
    fun eachGameIsStartedByTheSteamWhoseLibraryHoldsIt() = runBlocking {
        val native = deadlock(".local/share/Steam", "games/native")
        val flatpak = deadlock(".var/app/com.valvesoftware.Steam/.local/share/Steam", "games/flatpak")
        val snap = deadlock("snap/steam/common/.local/share/Steam", "games/snap")
        launcher.launch(flatpak)
        launcher.launch(native)
        launcher.launch(snap)
        assertEquals(listOf(
            listOf("flatpak", "run", "com.valvesoftware.Steam", "steam://rungameid/1422450"),
            listOf("xdg-open", "steam://rungameid/1422450"),
            listOf("snap", "run", "steam", "steam://rungameid/1422450"),
        ), commands)
    }

    @Test
    fun aGameNoSteamListsGoesToTheDesktopsSteamHandler() = runBlocking {
        deadlock(".var/app/com.valvesoftware.Steam/.local/share/Steam", "games/flatpak")
        file("picked/Deadlock/game/citadel/gameinfo.gi", "\"GameInfo\" {}")
        launcher.launch(GameRoot(home.resolve("picked/Deadlock").toString()))
        assertEquals(listOf(listOf("xdg-open", "steam://rungameid/1422450")), commands)
    }
}
