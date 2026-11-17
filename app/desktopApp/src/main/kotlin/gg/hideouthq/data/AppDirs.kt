package gg.hideouthq.data

import gg.hideouthq.OnWindows
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.Win32Exception
import java.io.File

const val AppId = "gg.hideouthq.Hideout"

/**
 * On Windows they go by [AppId]: the per-user MSI installs the program into `%LOCALAPPDATA%\Hideout` and deletes that
 * folder whole when it uninstalls or upgrades.
 */
object AppDirs {
    private const val LinuxName = "hideout"

    fun data(): File = if (OnWindows) local() else xdg().data().resolve(LinuxName)

    fun cache(): File = if (OnWindows) local().resolve("Cache") else xdg().cache().resolve(LinuxName)

    fun config(): File = if (OnWindows) File(System.getenv("APPDATA")?.let(::File) ?: home().resolve("AppData/Roaming"), AppId) else xdg().config().resolve(LinuxName)

    fun pictures(): File = if (OnWindows) knownPictures() ?: home().resolve("Pictures") else xdg().pictures()

    fun configHome(): File = xdg().config()

    private fun home() = File(System.getProperty("user.home"))

    private fun local() = File(System.getenv("LOCALAPPDATA")?.let(::File) ?: home().resolve("AppData/Local"), AppId)

    private fun xdg() = XdgDirs(home(), System::getenv)

    private fun knownPictures(): File? = try {
        File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Pictures))
    } catch (e: Win32Exception) {
        null
    }
}

/** A relative value is invalid by the XDG spec and is ignored like a missing one. */
internal class XdgDirs(private val home: File, private val environment: (String) -> String?) {
    fun data(): File = base("XDG_DATA_HOME", ".local/share")

    fun cache(): File = base("XDG_CACHE_HOME", ".cache")

    fun config(): File = base("XDG_CONFIG_HOME", ".config")

    fun pictures(): File = userDir("XDG_PICTURES_DIR") ?: home.resolve("Pictures")

    private fun base(variable: String, fallback: String) = environment(variable)?.let(::File)?.takeIf { it.isAbsolute } ?: home.resolve(fallback)

    /** [name]'s folder as xdg-user-dirs writes it, `"$HOME/<path>"` or `"/<path>"` with shell escapes; the last line naming it counts. */
    private fun userDir(name: String): File? {
        val file = config().resolve("user-dirs.dirs").takeIf { it.isFile } ?: return null
        val quoted = file.useLines { lines -> lines.map { it.trim() }.lastOrNull { it.startsWith("$name=") } }?.substringAfter('=')?.removeSurrounding("\"") ?: return null
        val (base, path) = when {
            quoted == "\$HOME" || quoted.startsWith("\$HOME/") -> home.path to quoted.removePrefix("\$HOME")
            quoted.startsWith("/") -> "" to quoted
            else -> return null
        }
        return File(base + path.replace(ShellEscape, "$1"))
    }

    private companion object {
        val ShellEscape = Regex("""\\(.)""")
    }
}
