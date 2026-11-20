package gg.hideouthq.data

import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.InstallDefaults
import gg.hideouthq.core.data.SettingsSnapshot
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FoundVia
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.report.Machine
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class HideoutLogTest {
    private val root: Path = Files.createTempDirectory("hideout-log")

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    @Test
    fun aLogPastItsLimitAtAStartBecomesTheOldOneAndBothAreReadOlderFirst() {
        root.resolve("hideout.old.log").writeText("oldest\n")
        root.resolve("hideout.log").writeText("x".repeat(1 shl 20) + "\n")

        val log = HideoutLog(root)
        log.write("started")

        assertEquals("x".repeat(1 shl 20) + "\n", root.resolve("hideout.old.log").readText())
        assertTrue(log.read().startsWith("x".repeat(1 shl 20) + "\n"))
        assertTrue(log.read().endsWith(" started\n"))
    }

    @Test
    fun aLogWithinItsLimitKeepsGrowing() {
        root.resolve("hideout.log").writeText("earlier\n")

        HideoutLog(root).write("later")

        assertFalse(Files.exists(root.resolve("hideout.old.log")))
        assertTrue(root.resolve("hideout.log").readText().let { it.startsWith("earlier\n") && it.endsWith(" later\n") })
    }

    @Test
    fun theUsersHomeAndNameAreLeftOutHoweverAPathWritesThem() {
        assertEquals(
            "~/.local/share/hideout, ~/x, Z:\\home\\<user>\\Steam, /run/media/<user>/Games, mirax/stays",
            withoutUser("/home/mira/.local/share/hideout, /home/mira/x, Z:\\home\\mira\\Steam, /run/media/mira/Games, mirax/stays", "/home/mira"),
        )
        assertEquals(
            "~\\AppData, ~\\\\AppData, ~/AppData, D:\\Users\\<user>\\Mods",
            withoutUser("C:\\Users\\Mira\\AppData, C:\\\\Users\\\\Mira\\\\AppData, C:/Users/Mira/AppData, D:\\Users\\mira\\Mods", "C:\\Users\\Mira"),
        )
        assertEquals("~/x, /home/alice/x", withoutUser("/home/al/x, /home/alice/x", "/home/al/"))
        assertEquals("/a/b", withoutUser("/a/b", "/"))
    }

    @Test
    fun theReportLeavesTheUsersHomeAndNameOutOfEveryPartAndSaysWhichAddonsAreNotHideouts() = runBlocking {
        val home = root.resolve("home/mira").also(Files::createDirectories).toString()
        val addons = Path.of(home, "Deadlock/game/citadel/addons").also(Files::createDirectories)
        addons.resolve("pak01_dir.vpk").writeBytes(ByteArray(2048))
        addons.resolve("pak07_dir.vpk").writeBytes(ByteArray(10))
        addons.resolveSibling("gameinfo.gi").writeText("GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel/modlock_dev/31bb65da30bd1ed1\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n")
        val game = GameRoot("$home/Deadlock")
        val lash = ManifestRecord(
            "lash", game, "Lash Gold", Placement.On(Slot(1)),
            listOf(InstalledSet(InstalledSource.Local("l1", "lash.vpk"), null, null, SetOrigin.Imported(FileImage(2048, "a".repeat(64))), null, "lash.vpk", listOf(SetFile(VpkPart.Directory, FileImage(2048, "a".repeat(64)))))),
        )
        val library = LibraryState(game = GameStatus.Ready(game, FoundVia.Chosen), library = LibraryStatus.Ready(listOf(lash)))
        val settings = SettingsSnapshot("$home/Deadlock", InstallDefaults(downloadFolder = DownloadFolder("$home/Downloads/Hideout")), true, true, true, false, true, false, true, false, false, emptyList())
        val log = HideoutLog(root)
        log.write("Left in place: $home/Deadlock/game/citadel/addons/pak01_dir.vpk")
        val machine = Machine("1.2.3", "on the JVM, not packaged, doesn't update itself", "Linux 6.8 amd64", "KDE, wayland", "en-GB", "Java 25, OpenJDK")
        val offered = mutableListOf<File>()

        val name = DesktopReportExport(log, machine, Path.of(home), home = home, saveAs = { _, suggested -> offered += suggested; File(home, "report") })
            .export("Save a bug report", library, null, settings)

        assertEquals("report.txt", name)
        assertTrue(offered.single().name.matches(Regex("hideout-report-\\d{4}-\\d{2}-\\d{2}\\.txt")))
        val saved = File(home, "report.txt").readText()
        assertTrue(saved.startsWith("Hideout bug report\n\n## Hideout\n"), saved)
        assertFalse("mira" in saved, saved)
        listOf(
            "Folder: ~/Deadlock, the folder chosen in Settings",
            "Search paths: citadel/modlock_dev/31bb65da30bd1ed1, citadel",
            "- pak01_dir.vpk, 2.0 KB: Hideout's, Lash Gold (local-l1)",
            "- pak07_dir.vpk, 10 B: NOT Hideout's, it loads all the same",
            "Game folder: chosen, ~/Deadlock",
            "Download folder: ~/Downloads/Hideout",
        ).forEach { assertTrue("\n$it\n" in saved, "$it in\n$saved") }
        assertTrue(saved.endsWith(" Left in place: ~/Deadlock/game/citadel/addons/pak01_dir.vpk\n"), saved)
        assertNull(DesktopReportExport(log, machine, Path.of(home), home = home, saveAs = { _, _ -> null }).export("Save a bug report", library, null, settings))
    }
}
