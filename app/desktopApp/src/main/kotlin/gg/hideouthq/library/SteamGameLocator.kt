package gg.hideouthq.library

import gg.hideouthq.OnWindows
import gg.hideouthq.core.data.GameLocator
import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.data.deadlockstats.mostRecentIdentity
import gg.hideouthq.core.library.DeadlockAppId
import gg.hideouthq.core.library.DeadlockManifestName
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.FolderProblem
import gg.hideouthq.core.library.GameInfoSegments
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.KeyValues
import gg.hideouthq.core.library.KeyValuesException
import gg.hideouthq.core.library.deadlockInstallDir
import gg.hideouthq.core.library.isAbsolutePath
import gg.hideouthq.core.library.steamLibraryFolders
import gg.hideouthq.data.XdgDirs
import gg.hideouthq.library.SteamClient.Desktop
import gg.hideouthq.library.SteamClient.Flatpak
import gg.hideouthq.library.SteamClient.Snap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Roots, libraries and games are compared with links resolved, so `~/.steam/steam` and `~/.local/share/Steam` count once. */
class SteamGameLocator(
    private val steamInstalls: () -> List<SteamInstall>,
    override val separator: Char = File.separatorChar,
) : GameLocator {
    override suspend fun detect(): List<GameRoot> = withContext(Dispatchers.IO) {
        val roots = LinkedHashSet<Path>()
        val libraries = LinkedHashSet<Path>()
        val games = LinkedHashSet<Path>()
        for (root in steamInstalls().mapNotNull(::realRoot)) {
            if (!roots.add(root)) continue
            for (library in listOf(root) + libraryFolders(root)) {
                val real = realDirectory(library) ?: continue
                if (libraries.add(real)) deadlockIn(real)?.let(games::add)
            }
        }
        games.map { GameRoot(it.toString()) }
    }

    override suspend fun check(path: String): FolderCheck = withContext(Dispatchers.IO) {
        val folder = absolutePath(path)?.let(::realDirectory) ?: return@withContext FolderCheck.Invalid(path, FolderProblem.Unavailable)
        val near = generateSequence(folder) { it.parent } + listOf(folder.resolve("steamapps/common/Deadlock"), folder.resolve("common/Deadlock"), folder.resolve("Deadlock"))
        near.firstNotNullOfOrNull { realDirectory(it)?.takeIf(::isDeadlock) }?.let { FolderCheck.Valid(GameRoot(it.toString())) } ?: FolderCheck.Invalid(path, FolderProblem.NotDeadlock)
    }

    companion object {
        internal const val MaxSteamFileBytes = 1 shl 20

        fun forThisComputer(): SteamGameLocator = SteamGameLocator(steamInstalls = ::steamInstalls)
    }
}

internal data class SteamCloud(val steam: Path, val account: SteamAccountId)

internal fun steamCloud(game: GameRoot, installs: List<SteamInstall>): SteamCloud? {
    fun account(install: SteamInstall) = SteamLoginIdentities.at(listOf(install)).mostRecentIdentity()?.let { SteamAccountId.ofSteamId64(it.steamId64) }
    val owners = owningInstalls(game, installs)
    if (owners.size > 1) return null
    val steam = owners.singleOrNull() ?: installs.firstOrNull { account(it) != null } ?: return null
    val root = realRoot(steam) ?: return null
    return account(steam)?.let { SteamCloud(root, it) }
}

internal fun owningInstalls(game: GameRoot, installs: List<SteamInstall>): List<SteamInstall> {
    val installed = absolutePath(game.path)?.let(::realDirectory) ?: return emptyList()
    return canonicalSteamInstalls(installs).filter { install ->
        val root = Path.of(install.root)
        (listOf(root) + libraryFolders(root)).mapNotNull(::realDirectory).any { deadlockIn(it) == installed }
    }
}

internal fun canonicalSteamInstalls(installs: List<SteamInstall>): List<SteamInstall> =
    installs.mapNotNull { install -> realRoot(install)?.let { install.copy(root = it.toString()) } }.distinct()

internal fun deadlockUserData(steam: Path, account: SteamAccountId): Path = listOf("userdata", account.toString(), DeadlockAppId.toString()).fold(steam, Path::resolve)

internal const val HeroSettingsName = "citadel_hero_settings.lst"

private fun libraryFolders(root: Path): List<Path> {
    val document = readSmallText(root.resolve("steamapps").resolve("libraryfolders.vdf"))?.let(::parse) ?: return emptyList()
    return steamLibraryFolders(document).mapNotNull(::absolutePath)
}

private fun deadlockIn(library: Path): Path? {
    val steamapps = library.resolve("steamapps")
    val manifest = readSmallText(steamapps.resolve(DeadlockManifestName))?.let(::parse) ?: return null
    val installDir = deadlockInstallDir(manifest) ?: return null
    val path = try {
        steamapps.resolve("common").resolve(installDir)
    } catch (e: InvalidPathException) {
        return null
    }
    return realDirectory(path)?.takeIf(::isDeadlock)
}

private fun isDeadlock(folder: Path): Boolean = Files.isRegularFile(GameInfoSegments.fold(folder, Path::resolve))

private fun parse(text: String): KeyValues? = try {
    KeyValues.parse(text, escapes = true)
} catch (e: KeyValuesException) {
    null
}

enum class SteamClient { Desktop, Flatpak, Snap }

data class SteamInstall(val root: String, val client: SteamClient)

fun steamInstalls(): List<SteamInstall> = steamInstallCandidates(OnWindows, System.getProperty("user.home"), System::getenv, WindowsRegistry)

/**
 * Canonical's Steam snap runs Steam with `HOME` at `~/snap/steam/common`, so its install sits where native Steam's
 * would, under that folder.
 */
internal fun steamInstallCandidates(windows: Boolean, home: String, environment: (String) -> String?, registry: Registry): List<SteamInstall> {
    fun under(client: SteamClient, vararg segments: String) = SteamInstall((listOf(home) + segments).joinToString(File.separator), client)
    return when {
        windows -> listOfNotNull(
            registry.string(RegistryHive.CurrentUser, """Software\Valve\Steam""", "SteamPath"),
            registry.string(RegistryHive.LocalMachine, """SOFTWARE\WOW6432Node\Valve\Steam""", "InstallPath"),
            environment("ProgramFiles(x86)")?.let { """$it\Steam""" },
            """C:\Program Files (x86)\Steam""",
        ).map { SteamInstall(it, Desktop) }
        else -> listOf(
            under(Desktop, ".steam", "steam"),
            under(Desktop, ".steam", "root"),
            SteamInstall(XdgDirs(File(home), environment).data().resolve("Steam").path, Desktop),
            under(Desktop, ".local", "share", "Steam"),
            under(Flatpak, ".var", "app", FlatpakSteamId, ".local", "share", "Steam"),
            under(Flatpak, ".var", "app", FlatpakSteamId, "data", "Steam"),
            under(Desktop, ".steam", "debian-installation"),
            under(Snap, "snap", "steam", "common", ".local", "share", "Steam"),
            under(Snap, "snap", "steam", "common", ".steam", "steam"),
        )
    }.distinct()
}

internal const val FlatpakSteamId = "com.valvesoftware.Steam"

enum class RegistryHive { CurrentUser, LocalMachine }

fun interface Registry {
    fun string(hive: RegistryHive, key: String, value: String): String?
}

private fun realRoot(install: SteamInstall): Path? = absolutePath(install.root)?.let(::realDirectory)

private fun absolutePath(text: String): Path? {
    if (!isAbsolutePath(text)) return null
    return try {
        Path.of(text).takeIf(Path::isAbsolute)
    } catch (e: InvalidPathException) {
        null
    }
}

private fun realDirectory(path: Path): Path? = try {
    path.toRealPath().takeIf { Files.isDirectory(it) }
} catch (e: IOException) {
    null
}

internal fun readSmallText(file: Path): String? = try {
    if (!Files.isRegularFile(file) || Files.size(file) > SteamGameLocator.MaxSteamFileBytes) return null
    val bytes = Files.newInputStream(file).use { it.readNBytes(SteamGameLocator.MaxSteamFileBytes + 1) }
    if (bytes.size > SteamGameLocator.MaxSteamFileBytes) return null
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (e: IOException) {
    null
}
