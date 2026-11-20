package gg.hideouthq.data

import gg.hideouthq.OnWindows
import gg.hideouthq.appupdate.isNativeImage
import gg.hideouthq.appupdate.packageFormat
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.data.ReportExport
import gg.hideouthq.core.data.SettingsSnapshot
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.inCitadel
import gg.hideouthq.core.report.CustomGamesFacts
import gg.hideouthq.core.report.Machine
import gg.hideouthq.core.report.ReportFacts
import gg.hideouthq.core.report.bugReport
import gg.hideouthq.library.NioGameInventory
import gg.hideouthq.library.replaceAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlin.time.Clock

class HideoutLog(folder: Path) {
    private val current = folder.resolve("hideout.log")
    private val old = folder.resolve("hideout.old.log")

    init {
        runCatching { Files.createDirectories(folder) }
        runCatching { if (Files.size(current) > MaxBytes) Files.move(current, old, StandardCopyOption.REPLACE_EXISTING) }
    }

    @Synchronized
    fun write(line: String) {
        runCatching { Files.writeString(current, "${Instant.now()} $line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
    }

    fun read(): String = listOf(old, current).filter(Files::isRegularFile).joinToString("") { Files.readAllBytes(it).decodeToString() }

    private companion object {
        const val MaxBytes = 1L shl 20
    }
}

class DesktopReportExport(
    private val log: HideoutLog,
    private val machine: Machine,
    private val dataFolder: Path,
    private val home: String = System.getProperty("user.home"),
    private val saveAs: SaveAs = ::saveFileDialog,
) : ReportExport {
    override suspend fun export(title: String, library: LibraryState, customGames: CustomGamesFacts?, settings: SettingsSnapshot): String? {
        val target = saveTarget(title, File(home, "hideout-report-${LocalDate.now()}.txt"), saveAs) ?: return null
        val root = (library.game as? GameStatus.Ready)?.root
        val loading = root?.let { SetFolder.Loading.associateWith { folder -> NioGameInventory.list(it, folder.inCitadel) } }
        val gameInfo = root?.let { NioGameInventory.read(it, ConfigTarget.GameInfo.Path, MaxConfigBytes) }
        withContext(Dispatchers.IO) {
            val facts = ReportFacts(Clock.System.now(), machine, library, settings, customGames, loading, gameInfo, root?.let { freeBytes(Path.of(it.path)) }, freeBytes(dataFolder), log.read())
            replaceAtomically(target.toPath(), withoutUser(bugReport(facts), home).encodeToByteArray())
        }
        return target.name
    }
}

internal fun readMachine(version: String, updatesItself: Boolean): Machine {
    val build = (if (isNativeImage()) packageFormat()?.let { "$it package" } ?: "native, not from a package" else "on the JVM, not packaged") +
        if (updatesItself) ", updates itself" else ", doesn't update itself"
    val system = listOfNotNull(System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch")).joinToString(" ") +
        distribution()?.let { " ($it)" }.orEmpty()
    val desktop = if (OnWindows) null else listOfNotNull(
        System.getenv("XDG_CURRENT_DESKTOP"), System.getenv("XDG_SESSION_TYPE"), "Flatpak".takeIf { System.getenv("FLATPAK_ID") != null },
    ).joinToString(", ").ifEmpty { "unknown" }
    return Machine(version, build, system, desktop, Locale.getDefault().toLanguageTag(), "Java ${System.getProperty("java.version")}, ${System.getProperty("java.vm.name")}")
}

private fun distribution(): String? = try {
    Files.readAllLines(Path.of("/etc/os-release")).firstOrNull { it.startsWith("PRETTY_NAME=") }?.substringAfter('=')?.trim('"')
} catch (e: IOException) {
    null
}

private fun freeBytes(folder: Path): Long? = try {
    Files.getFileStore(folder).usableSpace
} catch (e: IOException) {
    null
}

/** The user's name goes as `<user>` wherever else a path names it, such as Wine's `Z:\home\<user>` or another drive's `Users\<user>`. */
internal fun withoutUser(text: String, home: String): String {
    val folder = home.trimEnd('/', '\\')
    val user = folder.substringAfterLast('/').substringAfterLast('\\')
    if (user.isEmpty()) return text
    val homes = listOf(folder, folder.replace("\\", "\\\\"), folder.replace('\\', '/')).distinct().sortedByDescending { it.length }
    val folded = homes.fold(text) { result, written -> Regex("""${Regex.escape(written)}(?![\w.-])""", RegexOption.IGNORE_CASE).replace(result, "~") }
    return Regex("""(?<=[/\\])${Regex.escape(user)}(?![\w.-])""", RegexOption.IGNORE_CASE).replace(folded, "<user>")
}
