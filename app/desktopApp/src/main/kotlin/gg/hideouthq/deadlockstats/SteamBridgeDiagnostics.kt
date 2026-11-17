package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstallation
import gg.hideouthq.core.data.deadlockstats.SteamTrackingPhase
import gg.hideouthq.core.data.deadlockstats.SteamTrackingPort
import gg.hideouthq.core.data.deadlockstats.SteamTrackingStatus
import gg.hideouthq.library.SteamClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object SteamBridgeDiagnostics {
    fun open(scope: CoroutineScope, directory: File, library: File, steamRoot: File): SteamTrackingPort {
        val path = library.toPath().toRealPath()
        val hash = SteamBridgeCompatibility.hash(path)
        val root = steamRoot.toPath().toRealPath()
        val architecture = requireNotNull(SteamArchitecture.read(path))
        val target = SteamInstallationTarget("diagnostic", SteamClient.Desktop, root, root, architecture, false, null)
        return SteamBridgeServer({ directory.toPath() }, scope, { SteamTrackingInstallation(supported = true, installed = true) },
            { hello -> SteamProcessResolver().admit(hello, target, path, hash) })
    }
}

@Serializable
private data class SteamStartupRecord(val version: Int, val pid: Long, val start: String, val stage: String, val reason: String)

internal fun readSteamStartupStatus(directory: Path, target: SteamInstallationTarget?): SteamTrackingStatus? = runCatching {
    if (!Files.isDirectory(directory)) return null
    val owner = Files.getOwner(Path.of(System.getProperty("user.home")))
    fun currentRecord(path: Path): SteamStartupRecord? = runCatching record@ {
        if (Files.size(path) > 4096 || Files.isSymbolicLink(path) || Files.getOwner(path) != owner) return@record null
        val startup = Json.decodeFromString<SteamStartupRecord>(Files.readString(path))
        if (startup.version != 1 || startup.pid <= 0 || path.fileName.toString() != "startup-${startup.pid}-${startup.start}.json") return@record null
        val selected = target ?: return@record null
        val identity = SteamProcessResolver().resolve(startup.pid, selected.executable, selected.architecture) ?: return@record null
        if (identity.start != startup.start) return@record null
        startup
    }.getOrNull()
    val records = Files.newDirectoryStream(directory, "startup-*.json").use { stream -> stream.mapNotNull(::currentRecord) }
    val record = records.maxByOrNull { it.start.toLongOrNull() ?: 0 } ?: return null
    val phase = when (record.stage) {
        "incompatible" -> SteamTrackingPhase.Failed
        "structurally_compatible", "armed", "waiting_for_game" -> SteamTrackingPhase.WaitingForGame
        "waiting_for_gc" -> SteamTrackingPhase.WaitingForGc
        "collecting" -> SteamTrackingPhase.WaitingForSteam
        else -> return null
    }
    SteamTrackingStatus(phase, if (record.stage == "incompatible")
        "Steam bridge: ${record.reason}. Restart Steam through its launcher after repairing tracking." else null)
}.getOrNull()
