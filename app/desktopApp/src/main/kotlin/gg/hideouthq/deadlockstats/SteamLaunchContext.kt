package gg.hideouthq.deadlockstats

import gg.hideouthq.library.SteamClient
import java.nio.file.Files
import java.nio.file.Path

internal sealed interface SteamLaunchContext {
    data object Native : SteamLaunchContext
    data class Fhs(val runner: Path?) : SteamLaunchContext
    data object Flatpak : SteamLaunchContext
    data object Snap : SteamLaunchContext

    fun command(arguments: List<String>): List<String> = when (this) {
        Native -> arguments
        is Fhs -> listOf(requireNotNull(runner) { "missing_fhs_runner" }.toString()) + arguments
        Flatpak -> listOf("flatpak", "run", "--command=${arguments.first()}", "com.valvesoftware.Steam") + arguments.drop(1)
        Snap -> error("Snap inspection requires the selected client's existing runtime service")
    }

    companion object {
        fun detect(client: SteamClient, path: String = System.getenv("PATH").orEmpty()): SteamLaunchContext {
            fun executable(name: String): Path? = path.split(':').filter { it.isNotBlank() }
                .map { Path.of(it).resolve(name) }.firstOrNull { Files.isExecutable(it) }
            return when (client) {
                SteamClient.Flatpak -> Flatpak
                SteamClient.Snap -> Snap
                SteamClient.Desktop -> {
                    val steam = executable("steam")?.let { runCatching { it.toRealPath() }.getOrNull() }
                    if (steam?.startsWith("/nix/store") == true) Fhs(executable("steam-run")) else Native
                }
            }
        }
    }
}

internal fun SteamInstallationTarget.probeCommand(bundle: Path): List<String> {
    val runtimeBundle = runtimeRoot.resolve(root.relativize(bundle))
    val command = listOf(runtimeBundle.resolve("bridge_probe").toString(), runtimeExecutable.toString(),
        runtimeModule.toString(), runtimeBundle.resolve("libhideout_steam_bridge.so").toString())
    val environment = when (architecture) {
        SteamArchitecture.I386 -> runtimeRoot.resolve("ubuntu12_32/steam-runtime/run.sh")
        SteamArchitecture.X86_64 -> runtimeRoot.resolve("steamrt64/pv-run.sh")
    }
    require(Files.isExecutable(root.resolve(runtimeRoot.relativize(environment)))) { "missing_client_runtime" }
    val inspection = listOf(environment.toString()) +
        (if (architecture == SteamArchitecture.X86_64) listOf("--") else emptyList()) + command
    return if (launchContext == SteamLaunchContext.Snap) runningSnapCommand(inspection + "--require-snap")
        else launchContext.command(inspection)
}
