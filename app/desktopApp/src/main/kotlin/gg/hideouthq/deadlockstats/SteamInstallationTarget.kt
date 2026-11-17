package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlockstats.SteamInstallationChoice
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.library.SteamClient
import gg.hideouthq.library.SteamGameLocator
import gg.hideouthq.library.SteamInstall
import gg.hideouthq.library.canonicalSteamInstalls
import gg.hideouthq.library.owningInstalls
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal enum class SteamArchitecture(val bits: Int, val resource: String, val clientDirectory: String) {
    I386(32, "linux-i386", "ubuntu12_32"), X86_64(64, "linux-x86_64", "steamrt64");

    companion object {
        fun read(path: Path): SteamArchitecture? = runCatching {
            Files.newInputStream(path).use { input ->
                val header = input.readNBytes(20)
                if (header.size != 20 || !header.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70)) || header[5] != 1.toByte()) return null
                when {
                    header[4] == 1.toByte() && header[18] == 3.toByte() && header[19] == 0.toByte() -> I386
                    header[4] == 2.toByte() && header[18] == 62.toByte() && header[19] == 0.toByte() -> X86_64
                    else -> null
                }
            }
        }.getOrNull()
    }
}

internal data class SteamInstallationTarget(
    val id: String,
    val client: SteamClient,
    val root: Path,
    val runtimeRoot: Path,
    val architecture: SteamArchitecture,
    val ownsGame: Boolean,
    val game: String?,
    val launchContext: SteamLaunchContext = SteamLaunchContext.Native,
) {
    val storage: Path get() = root.resolve(".hideout-tracking")
    val endpointDirectory: Path get() = storage.resolve("session")
    val executable: Path get() = root.resolve("${architecture.clientDirectory}/steam")
    val module: Path get() = root.resolve("${architecture.clientDirectory}/steamclient.so")
    val runtimeExecutable: Path get() = runtimeRoot.resolve("${architecture.clientDirectory}/steam")
    val runtimeModule: Path get() = runtimeRoot.resolve("${architecture.clientDirectory}/steamclient.so")
    val desktopName: String get() = when (client) {
        SteamClient.Desktop -> "steam.desktop"
        SteamClient.Flatpak -> "com.valvesoftware.Steam.desktop"
        SteamClient.Snap -> "steam_steam.desktop"
    }
    fun choice() = SteamInstallationChoice(id, when (client) {
        SteamClient.Desktop -> "Steam"
        SteamClient.Flatpak -> "Steam (Flatpak)"
        SteamClient.Snap -> "Steam (Snap)"
    }, root.toString(), ownsGame)
}

internal suspend fun discoverSteamTrackingTargets(installs: List<SteamInstall>, selectedGame: GameRoot?): List<SteamInstallationTarget> {
    val game = selectedGame ?: SteamGameLocator({ installs }).detect().singleOrNull()
    return steamTrackingTargets(installs, game)
}

internal fun steamTrackingTargets(
    installs: List<SteamInstall>,
    game: GameRoot?,
    forcedClient: String? = System.getenv("STEAM_FORCE_CLIENT"),
): List<SteamInstallationTarget> {
    val canonical = canonicalSteamInstalls(installs)
    val owners = game?.let { owningInstalls(it, canonical).toSet() }.orEmpty()
    val canonicalGame = game?.let { runCatching { Path.of(it.path).toRealPath().toString() }.getOrNull() }
    return canonical.map { install ->
        val root = Path.of(install.root)
        val preferred = when (forcedClient) {
            "ubuntu12_32", "scout", "ldlp" -> SteamArchitecture.I386
            "steamrt64", "steamrt3c", "steamrt" -> SteamArchitecture.X86_64
            else -> if (Files.exists(root.resolve(".steam-enable-steamrt64-client"))) SteamArchitecture.X86_64 else SteamArchitecture.I386
        }
        // Updates can temporarily remove or replace the executable. Keep the
        // installation identity; the runtime probe validates its current ELF.
        val identity = "${install.client.name}\u0000$root"
        SteamInstallationTarget(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).toHexString(),
            install.client, root, root, preferred, install in owners, canonicalGame, SteamLaunchContext.detect(install.client))
    }
}
