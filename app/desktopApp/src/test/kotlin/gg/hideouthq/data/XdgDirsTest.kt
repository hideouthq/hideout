package gg.hideouthq.data

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class XdgDirsTest {
    private val home: File = Files.createTempDirectory(Files.createDirectories(Path.of("build", "xdg-tests")), "home").toRealPath().toFile()

    private fun userDirs(text: String, config: File = home.resolve(".config")) = config.resolve("user-dirs.dirs").also {
        it.parentFile.mkdirs()
        it.writeText(text)
    }

    @Test
    fun aBaseFolderIsTheAbsoluteValueOfItsVariableAndARelativeOneIsIgnored() {
        val environment = mapOf("XDG_DATA_HOME" to "relative/share", "XDG_CACHE_HOME" to "", "XDG_CONFIG_HOME" to "/srv/config")
        val dirs = XdgDirs(home, environment::get)
        assertEquals(home.resolve(".local/share"), dirs.data())
        assertEquals(home.resolve(".cache"), dirs.cache())
        assertEquals(File("/srv/config"), dirs.config())
        assertEquals(home.resolve(".config"), XdgDirs(home) { null }.config())
    }

    @Test
    fun picturesAreWhereUserDirsSaysWithHomeExpanded() {
        val dirs = XdgDirs(home) { null }
        assertEquals(home.resolve("Pictures"), dirs.pictures(), "no user-dirs.dirs")
        userDirs("# written by xdg-user-dirs-update\nXDG_DESKTOP_DIR=\"\$HOME/Desktop\"\n")
        assertEquals(home.resolve("Pictures"), dirs.pictures(), "none named")
        userDirs("XDG_DESKTOP_DIR=\"\$HOME/Desktop\"\nXDG_PICTURES_DIR=\"\$HOME/Bilder\"\n")
        assertEquals(home.resolve("Bilder"), dirs.pictures())
        userDirs("XDG_PICTURES_DIR=\"\$HOME/Bilder\"\nXDG_PICTURES_DIR=\"/mnt/photos/画像\"\n")
        assertEquals(File("/mnt/photos/画像"), dirs.pictures(), "the last line counts")
        userDirs("XDG_PICTURES_DIR=\"\$HOME/My \\\"Shots\\\" \\\$5\"\n")
        assertEquals(home.resolve("My \"Shots\" \$5"), dirs.pictures(), "shell escapes")
        userDirs("XDG_PICTURES_DIR=\"Pictures/elsewhere\"\n")
        assertEquals(home.resolve("Pictures"), dirs.pictures(), "a relative path is not one xdg-user-dirs writes")
    }

    @Test
    fun userDirsIsReadFromTheConfigFolderTheEnvironmentNames() {
        val config = home.resolve("custom-config")
        userDirs("XDG_PICTURES_DIR=\"\$HOME/Shots\"\n", config)
        userDirs("XDG_PICTURES_DIR=\"\$HOME/Default\"\n")
        assertEquals(home.resolve("Shots"), XdgDirs(home, mapOf("XDG_CONFIG_HOME" to config.path)::get).pictures())
        assertEquals(home.resolve("Default"), XdgDirs(home, mapOf("XDG_CONFIG_HOME" to "custom-config")::get).pictures())
    }
}
