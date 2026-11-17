package gg.hideouthq.library

import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class FileHeroSettingsTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "hero-settings-tests")), "home").toRealPath()

    @AfterTest
    fun cleanUp() = home.deleteRecursively()

    private fun file(relative: String, text: String = ""): Path = home.resolve(relative).also { it.parent.createDirectories(); it.writeText(text) }

    private fun deadlock(library: String): GameRoot {
        file("$library/steamapps/appmanifest_1422450.acf", "\"AppState\" { \"installdir\" \"Deadlock\" }")
        file("$library/steamapps/common/Deadlock/game/citadel/gameinfo.gi", "\"GameInfo\" {}")
        return GameRoot(home.resolve("$library/steamapps/common/Deadlock").toString())
    }

    private fun libraries(steam: String, vararg paths: String) = file(
        "$steam/steamapps/libraryfolders.vdf",
        "\"libraryfolders\"\n{\n" + paths.mapIndexed { i, path -> "\t\"$i\" { \"path\" \"$path\" }\n" }.joinToString("") + "}\n",
    )

    private fun users(steam: String, vararg users: Pair<Long, Boolean>) = file(
        "$steam/config/loginusers.vdf",
        "\"users\"\n{\n" + users.joinToString("") { (id, recent) -> "\t\"$id\"\n\t{\n\t\t\"AccountName\"\t\t\"a$id\"\n\t\t\"MostRecent\"\t\t\"${if (recent) 1 else 0}\"\n\t}\n" } + "}\n",
    )

    private fun cloud(steam: String, account: Long) = home.resolve("$steam/userdata/$account/1422450/remote/cfg/citadel_hero_settings.lst")

    private fun account(id: Long) = checkNotNull(SteamAccountId.ofAccountId(id))

    private fun installs(vararg roots: String) = roots.map { SteamInstall(home.resolve(it).toString(), SteamClient.Desktop) }

    private fun settings(vararg roots: String, beforeReplace: (Path) -> Unit = {}) = FileHeroSettings({ installs(*roots) }, beforeReplace)

    @Test
    fun onLinuxSteamCloudsCopyIsTheMostRecentAccountsUnderTheSteamRootWhoseLibraryHoldsTheGame() {
        val game = deadlock("mnt/games/SteamLibrary")
        libraries(".local/share/Steam", home.resolve(".local/share/Steam").toString(), home.resolve("mnt/games/SteamLibrary").toString())
        users(".local/share/Steam", 76561198000000001L to false, 76561199118057941L to true)
        users(".steam/debian-installation", 76561198000000009L to true)
        assertEquals(SteamCloud(home.resolve(".local/share/Steam"), account(1157792213)), steamCloud(game, installs(".steam/debian-installation", ".local/share/Steam")))
        assertEquals(listOf(HeroSettingsCopy.Game, HeroSettingsCopy.Cloud(account(1157792213))), runBlocking { settings(".steam/debian-installation", ".local/share/Steam").copies(game) })
    }

    @Test
    fun onWindowsTheGameInSteamsOwnLibraryFindsTheSameLayout() {
        val steam = "Program Files (x86)/Steam"
        val game = deadlock(steam)
        users(steam, 76561198000000002L to true)
        assertEquals(SteamCloud(home.resolve(steam), account(39734274)), steamCloud(game, installs(steam)))
    }

    @Test
    fun aGameNoLibraryListsTakesTheFirstSteamRootWithAnAccount() {
        val game = deadlock("elsewhere")
        Files.createDirectories(home.resolve("empty/Steam"))
        users("sandbox/Steam", 76561198000000003L to true)
        assertEquals(SteamCloud(home.resolve("sandbox/Steam"), account(39734275)), steamCloud(game, installs("empty/Steam", "sandbox/Steam")))
    }

    @Test
    fun withoutSteamOrWithAccountsNoneOfWhichSignedInLastOnlyTheGamesOwnCopyIsThere() {
        val game = deadlock("Steam")
        assertEquals(listOf(HeroSettingsCopy.Game), runBlocking { settings("Steam").copies(game) }, "no loginusers.vdf")
        file("Steam/config/loginusers.vdf", "\"users\"\n{\n\t\"76561198000000001\" { \"MostRecent\" \"0\" }\n\t\"76561198000000002\" { \"MostRecent\" \"0\" }\n}\n")
        assertNull(steamCloud(game, installs("Steam")), "two accounts and no telling which")
        assertEquals(listOf(HeroSettingsCopy.Game), runBlocking { settings("does/not/exist").copies(game) })
    }

    @Test
    fun eachCopyIsReadAndWrittenWholeWithTheGamesFolderMadeAndSteamsRecordsLeftAlone() = runBlocking<Unit> {
        val game = deadlock("Steam")
        users("Steam", 76561198000000002L to true)
        val remotecache = file("Steam/userdata/39734274/1422450/remotecache.vdf", "\"1422450\" { }")
        val stamp = remotecache.getLastModifiedTime()
        file("Steam/userdata/39734274/1422450/remote/cfg/citadel_hero_settings.lst", "old\n")
        val files = settings("Steam")
        assertContentEquals("old\n".encodeToByteArray(), assertIs<FileRead.Present>(files.read(game, HeroSettingsCopy.Cloud(account(39734274)))).bytes)
        assertEquals(FileRead.Absent, files.read(game, HeroSettingsCopy.Game))
        files.write(game, HeroSettingsCopy.Cloud(account(39734274)), "cloud\n".encodeToByteArray())
        files.write(game, HeroSettingsCopy.Game, "game\r\n".encodeToByteArray())
        assertEquals("cloud\n", cloud("Steam", 39734274).readText())
        assertEquals("game\r\n", Path.of(game.path, "game/citadel/cfg/citadel_hero_settings.lst").readText())
        assertContentEquals("game\r\n".encodeToByteArray(), assertIs<FileRead.Present>(files.read(game, HeroSettingsCopy.Game)).bytes)
        assertEquals("\"1422450\" { }", remotecache.readText())
        assertEquals(stamp, remotecache.getLastModifiedTime())
        assertEquals(listOf("citadel_hero_settings.lst"), cloud("Steam", 39734274).parent.listDirectoryEntries().map { it.fileName.toString() }, "nothing is left beside it")
    }

    @Test
    fun anAccountThatNeverRanDeadlockHereHasNoSteamCloudCopyAndAWriteMakesNoFolderForIt() = runBlocking<Unit> {
        val game = deadlock("Steam")
        users("Steam", 76561198000000002L to true)
        val files = settings("Steam")
        val cloud = HeroSettingsCopy.Cloud(account(39734274))
        assertEquals(listOf(HeroSettingsCopy.Game, cloud), files.copies(game))
        assertEquals(FileRead.Absent, files.read(game, cloud))
        assertFailsWith<IOException> { files.write(game, cloud, "cloud\n".encodeToByteArray()) }
        assertEquals(false, home.resolve("Steam/userdata").exists())
    }

    @Test
    fun steamCloudsCopyIsStagedOutsideTheFolderSteamSyncsAndTheNextWriteClearsWhatAStopLeftThere() = runBlocking<Unit> {
        val game = deadlock("Steam")
        users("Steam", 76561198000000002L to true)
        val target = file("Steam/userdata/39734274/1422450/remote/cfg/citadel_hero_settings.lst", "old")
        val userData = home.resolve("Steam/userdata/39734274/1422450")
        val left = file("Steam/userdata/39734274/1422450/citadel_hero_settings.lst.hideout-next-left-by-a-crash", "ne")
        var staged: Path? = null
        settings("Steam") { staged = it }.write(game, HeroSettingsCopy.Cloud(account(39734274)), "new".encodeToByteArray())
        assertEquals("new", target.readText())
        assertEquals(userData, checkNotNull(staged).parent, "staged outside remote, which Steam syncs, on the same drive")
        assertEquals(false, left.exists(), "and what a stop left there is gone")
        assertEquals(listOf("remote"), userData.listDirectoryEntries().map { it.fileName.toString() })
        assertEquals(listOf("citadel_hero_settings.lst"), target.parent.listDirectoryEntries().map { it.fileName.toString() })
    }

    @Test
    fun aWriteReplacesTheFileInOneStepRatherThanChangingItInPlace() = runBlocking<Unit> {
        val game = deadlock("Steam")
        val target = file("Steam/steamapps/common/Deadlock/game/citadel/cfg/citadel_hero_settings.lst", "old")
        val earlier = Files.createLink(home.resolve("earlier"), target)
        settings("Steam").write(game, HeroSettingsCopy.Game, "new".encodeToByteArray())
        assertEquals("new", target.readText())
        assertEquals("old", earlier.readText(), "the old file was replaced, not written over")
    }

    @Test
    fun aStopAtAnyStepLeavesTheOldFileOrTheNewOneAndTheNextWriteClearsWhatItLeft() = runBlocking<Unit> {
        val game = deadlock("Steam")
        val target = file("Steam/steamapps/common/Deadlock/game/citadel/cfg/citadel_hero_settings.lst", "old")
        val folder = target.parent
        val crashed = settings("Steam") { throw IOException("stopped before the move") }
        assertFailsWith<IOException> { crashed.write(game, HeroSettingsCopy.Game, "new".encodeToByteArray()) }
        assertEquals("old", target.readText(), "stopped once staged: the old file")

        val halfStaged = folder.resolve("citadel_hero_settings.lst.hideout-next-left-by-a-crash")
        halfStaged.writeBytes("ne".encodeToByteArray())
        assertContentEquals("old".encodeToByteArray(), assertIs<FileRead.Present>(settings("Steam").read(game, HeroSettingsCopy.Game)).bytes, "stopped while staging: the old file")

        var staged: Path? = null
        settings("Steam") { staged = it }.write(game, HeroSettingsCopy.Game, "new".encodeToByteArray())
        assertEquals("new", target.readText(), "the next write lands whole")
        assertTrue(checkNotNull(staged).fileName.toString().startsWith("citadel_hero_settings.lst.hideout-next-"), "it was staged beside the file first")
        assertEquals(false, halfStaged.exists(), "and what the crash left is gone")
        assertEquals(listOf("citadel_hero_settings.lst"), folder.listDirectoryEntries().map { it.fileName.toString() })
        assertContentEquals("new".encodeToByteArray(), target.readBytes())
    }
}
