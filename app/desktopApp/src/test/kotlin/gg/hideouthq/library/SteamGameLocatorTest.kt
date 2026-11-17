package gg.hideouthq.library

import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.deadlockstats.LocalSteamIdentity
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.FolderProblem
import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SteamGameLocatorTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "steam-locator-tests")), "home").toRealPath()

    private fun file(relative: String, text: String = ""): Path =
        home.resolve(relative).also { it.parent.createDirectories(); it.writeText(text) }

    private fun dir(relative: String): Path = home.resolve(relative).createDirectories()

    private fun link(relative: String, target: Path): Path =
        home.resolve(relative).also { it.parent.createDirectories(); it.createSymbolicLinkPointingTo(target) }

    private fun deadlock(library: String, installDir: String = "Deadlock"): Path {
        file("$library/steamapps/$Manifest", manifest(installDir))
        file("$library/steamapps/common/$installDir/game/citadel/gameinfo.gi", "\"GameInfo\" {}")
        return home.resolve("$library/steamapps/common/$installDir")
    }

    private fun libraries(root: String, vararg paths: String) = file(
        "$root/steamapps/libraryfolders.vdf",
        "\"libraryfolders\"\n{\n" + paths.mapIndexed { i, path -> "\t\"$i\" { \"path\" \"${path.replace("\\", "\\\\")}\" }\n" }.joinToString("") + "}\n",
    )

    private fun locator(vararg roots: String) = SteamGameLocator(steamInstalls = { roots.map { SteamInstall(home.resolve(it).toString(), SteamClient.Desktop) } }, separator = '/')

    private fun detect(locator: SteamGameLocator) = runBlocking { locator.detect() }

    private fun check(path: Path) = runBlocking { locator().check(path.toString()) }

    @Test
    fun nativeSteamThroughItsLinksCountsOnce() {
        val game = deadlock(".local/share/Steam")
        libraries(".local/share/Steam", home.resolve(".local/share/Steam").toString())
        link(".steam/steam", home.resolve(".local/share/Steam"))
        link(".steam/root", home.resolve(".local/share/Steam"))
        val found = detect(locator(".steam/steam", ".steam/root", ".local/share/Steam"))
        assertEquals(listOf(GameRoot(game.toRealPath().toString())), found)
    }

    @Test
    fun flatpakSteamIsFoundAndSharedLibrariesCountOnce() {
        val shared = deadlock("mnt/games/SteamLibrary")
        libraries(".local/share/Steam", home.resolve(".local/share/Steam").toString(), home.resolve("mnt/games/SteamLibrary").toString())
        libraries(".var/app/com.valvesoftware.Steam/.local/share/Steam", home.resolve("mnt/games/SteamLibrary").toString())
        assertEquals(
            listOf(GameRoot(shared.toString())),
            detect(locator(".local/share/Steam", ".var/app/com.valvesoftware.Steam/.local/share/Steam")),
        )
        val flatpakOnly = deadlock(".var/app/com.valvesoftware.Steam/data/Steam")
        assertEquals(listOf(GameRoot(flatpakOnly.toString())), detect(locator(".steam/steam", ".var/app/com.valvesoftware.Steam/data/Steam")))
    }

    @Test
    fun ownershipRetainsAllClientsButDeduplicatesTheirRootAliases() {
        val shared = deadlock("shared")
        libraries("native", home.resolve("shared").toString())
        libraries("flatpak", home.resolve("shared").toString())
        val alias = link("native-alias", home.resolve("native"))
        val native = SteamInstall(home.resolve("native").toString(), SteamClient.Desktop)
        val flatpak = SteamInstall(home.resolve("flatpak").toString(), SteamClient.Flatpak)
        assertEquals(listOf(native, flatpak), owningInstalls(GameRoot(shared.toString()),
            listOf(native, SteamInstall(alias.toString(), SteamClient.Desktop), flatpak)))
    }

    @Test
    fun everyLibraryIsSearchedInSteamsOrder() {
        dir("Steam/steamapps/common")
        val second = deadlock("libraries/two")
        val third = deadlock("libraries/three")
        libraries("Steam", home.resolve("Steam").toString(), home.resolve("libraries/two").toString(), home.resolve("libraries/three").toString())
        assertEquals(listOf(GameRoot(second.toString()), GameRoot(third.toString())), detect(locator("Steam")))
    }

    @Test
    fun aManifestCannotLeadOutOfSteamappsCommon() {
        file("outside/game/citadel/gameinfo.gi")
        file("Steam/steamapps/$Manifest", manifest("../../outside"))
        dir("Steam/steamapps/common")
        assertEquals(emptyList(), detect(locator("Steam")))
        file("Steam/steamapps/$Manifest", manifest(home.resolve("outside").toString()))
        assertEquals(emptyList(), detect(locator("Steam")))
    }

    @Test
    fun aManifestWithoutTheGameFilesIsNotTheGame() {
        file("Steam/steamapps/$Manifest", manifest("Deadlock"))
        dir("Steam/steamapps/common/Deadlock/game/citadel")
        assertEquals(emptyList(), detect(locator("Steam")))
    }

    @Test
    fun brokenSteamFilesAreSkippedWithoutLosingTheRest() {
        val game = deadlock("Steam")
        file("Steam/steamapps/libraryfolders.vdf", "\"libraryfolders\" { \"0\" { \"path\" ")
        assertEquals(listOf(GameRoot(game.toString())), detect(locator("Steam")), "a malformed library list still leaves the root's own library")

        file("Other/steamapps/$Manifest", "\"AppState\" { \"appid\" \"1422450\" \"installdir\" ")
        dir("Other/steamapps/common/Deadlock/game/citadel")
        file("Other/steamapps/common/Deadlock/game/citadel/gameinfo.gi")
        assertEquals(emptyList(), detect(locator("Other")), "a malformed manifest is skipped")

        deadlock("Fourth")
        home.resolve("Steam/steamapps/libraryfolders.vdf").writeText("\"libraryfolders\" { \"0\" { \"path\" \"${home.resolve("Fourth")}\" } }" + " ".repeat(SteamGameLocator.MaxSteamFileBytes))
        assertEquals(listOf(GameRoot(game.toString())), detect(locator("Steam")), "an oversized library list is not read")

        deadlock("Fifth")
        home.resolve("Fifth/steamapps/$Manifest").writeBytes(manifest("Deadlock").toByteArray() + "// \u00e9 in Latin-1: ".toByteArray() + byteArrayOf(0xE9.toByte(), '\n'.code.toByte()))
        assertEquals(emptyList(), detect(locator("Fifth")), "a manifest that is not UTF-8 is not read")
    }

    @Test
    fun aWindowsInvalidInstallDirDoesNotHideTheNextLibrary() {
        file("Steam/steamapps/$Manifest", manifest("Deadlock?"))
        val valid = deadlock("Other")
        libraries("Steam", home.resolve("Other").toString())
        assertEquals(listOf(GameRoot(valid.toString())), detect(locator("Steam")))
    }

    @Test
    fun relativeAndForeignPlatformLibraryPathsAreIgnored() {
        dir("Steam/steamapps")
        deadlock("relative")
        file("Steam/steamapps/libraryfolders.vdf", "\"libraryfolders\" { \"1\" { \"path\" \"relative\" } \"2\" { \"path\" \"D:\\\\relative\" } }")
        assertEquals(emptyList(), detect(locator("Steam")))
    }

    @Test
    fun aLinkedGameFolderIsReportedWhereItReallyIs() {
        val real = home.resolve("games/Deadlock")
        file("games/Deadlock/game/citadel/gameinfo.gi")
        file("Steam/steamapps/$Manifest", manifest("Deadlock"))
        link("Steam/steamapps/common/Deadlock", real)
        assertEquals(listOf(GameRoot(real.toString())), detect(locator("Steam")))
        assertEquals(FolderCheck.Valid(GameRoot(real.toString())), check(home.resolve("Steam/steamapps/common/Deadlock")))
    }

    @Test
    fun missingSteamFindsNothing() {
        assertEquals(emptyList(), detect(locator(".steam/steam", "nowhere")))
        assertEquals(emptyList(), detect(SteamGameLocator(steamInstalls = { listOf("", "relative/Steam", "C:").map { SteamInstall(it, SteamClient.Desktop) } })))
    }

    @Test
    fun aFolderInsideDeadlockOrAroundItIsDeadlock() {
        val game = deadlock("Steam")
        file("Steam/steamapps/libraryfolders.vdf", "\"libraryfolders\" {}")
        for (path in listOf(game, game.resolve("game"), game.resolve("game/citadel"), dir("Steam/steamapps/common/Deadlock/game/bin/win64"), home.resolve("Steam"), home.resolve("Steam/steamapps"), home.resolve("Steam/steamapps/common"))) {
            assertEquals(FolderCheck.Valid(GameRoot(game.toString())), check(path), path.toString())
        }
        val cases = mapOf(dir("Downloads") to FolderProblem.NotDeadlock, home.resolve("missing") to FolderProblem.Unavailable, file("notes.txt") to FolderProblem.Unavailable)
        cases.forEach { (path, problem) -> assertEquals(FolderCheck.Invalid(path.toString(), problem), check(path), path.toString()) }
        assertEquals(FolderCheck.Invalid("Steam", FolderProblem.Unavailable), runBlocking { locator().check("Steam") })
    }

    @Test
    fun aChosenFolderAndSteamsAgreeThroughLinks() {
        val game = deadlock(".local/share/Steam")
        link("shortcut", game)
        val viaLink = check(home.resolve("shortcut"))
        assertEquals(FolderCheck.Valid(GameRoot(game.toString())), viaLink)
        assertEquals(detect(locator(".local/share/Steam")).single(), (viaLink as FolderCheck.Valid).root)
    }

    @Test
    fun windowsAsksTheUsersRegistryThenTheMachinesThenTheDefaultFolders() {
        val game = deadlock("Machine Steam")
        val machineOnly = Registry { hive, key, value ->
            home.resolve("Machine Steam").toString().takeIf { hive == RegistryHive.LocalMachine && key == """SOFTWARE\WOW6432Node\Valve\Steam""" && value == "InstallPath" }
        }
        val environment = { name: String -> if (name == "ProgramFiles(x86)") """D:\Programs""" else null }
        val windows = steamInstallCandidates(windows = true, """C:\Users\u""", environment, machineOnly)
        assertEquals(listOf(home.resolve("Machine Steam").toString(), """D:\Programs\Steam""", """C:\Program Files (x86)\Steam""").map { SteamInstall(it, SteamClient.Desktop) }, windows)
        assertEquals(listOf(GameRoot(game.toString())), detect(SteamGameLocator(steamInstalls = { windows })))

        val both = Registry { hive, key, value ->
            when {
                hive == RegistryHive.CurrentUser && key == """Software\Valve\Steam""" && value == "SteamPath" -> "c:/users/u/steam"
                hive == RegistryHive.LocalMachine && key == """SOFTWARE\WOW6432Node\Valve\Steam""" && value == "InstallPath" -> """E:\Steam"""
                else -> null
            }
        }
        assertEquals(listOf("c:/users/u/steam", """E:\Steam""", """C:\Program Files (x86)\Steam"""), steamInstallCandidates(windows = true, """C:\Users\u""", { null }, both).map(SteamInstall::root))
        assertEquals(listOf("""C:\Program Files (x86)\Steam"""), steamInstallCandidates(windows = true, """C:\Users\u""", { null }, { _, _, _ -> null }).map(SteamInstall::root))
    }

    @Test
    fun linuxAlsoLooksInTheDataHome() {
        val linux = steamInstallCandidates(windows = false, "/home/u", { if (it == "XDG_DATA_HOME") "/data" else null }) { _, _, _ -> error("no registry off Windows") }
        assertTrue(SteamInstall("/data${File.separator}Steam", SteamClient.Desktop) in linux)
    }

    @Test
    fun snapSteamIsFoundWithItsAccountsAndItsSteamCloudCopy() = runBlocking {
        val steam = "snap/steam/common/.local/share/Steam"
        val game = GameRoot(deadlock(steam).toString())
        file("$steam/config/loginusers.vdf", "\"users\"\n{\n\t\"76561198000000002\"\n\t{\n\t\t\"PersonaName\"\t\t\"Snap\"\n\t\t\"MostRecent\"\t\t\"1\"\n\t}\n}\n")
        link("snap/steam/common/.steam/steam", home.resolve(steam))
        val installs = steamInstallCandidates(windows = false, home.toString(), { null }, { _, _, _ -> null })
        assertEquals(listOf(game), SteamGameLocator(steamInstalls = { installs }).detect())
        assertEquals(listOf(LocalSteamIdentity(76561198000000002, "Snap", mostRecent = true)), SteamLoginIdentities.at(installs))
        val account = checkNotNull(SteamAccountId.ofSteamId64(76561198000000002))
        assertEquals(listOf(HeroSettingsCopy.Game, HeroSettingsCopy.Cloud(account)), FileHeroSettings(steamInstalls = { installs }).copies(game))
    }

    private companion object {
        const val Manifest = "appmanifest_1422450.acf"

        fun manifest(installDir: String) =
            "\"AppState\"\n{\n\t\"appid\"\t\t\"1422450\"\n\t\"name\"\t\t\"Deadlock\"\n\t\"installdir\"\t\t\"${installDir.replace("\\", "\\\\")}\"\n\t\"buildid\"\t\t\"25838798\"\n}\n"
    }
}
