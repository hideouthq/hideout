package gg.hideouthq.deadlockstats

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Snap's command chain has exit-time cleanup, so a probe must never run that chain. */
internal fun SteamInstallationTarget.runningSnapCommand(arguments: List<String>, proc: Path = Path.of("/proc")): List<String> {
    val owner = Files.getOwner(root)
    fun start(process: Path) = Files.readString(process.resolve("stat")).substringAfterLast(") ").split(' ').getOrNull(19)
    fun selectedAncestor(process: Path): Boolean {
        var current = process
        repeat(32) {
            if (Files.getOwner(current) != owner) return false
            if (Files.isSameFile(current.resolve("exe"), executable)) return true
            val parent = Files.readAllLines(current.resolve("status")).single { it.startsWith("PPid:") }
                .substringAfter(':').trim().toLong()
            if (parent <= 1) return false
            current = proc.resolve(parent.toString())
        }
        return false
    }
    data class Service(val process: Path, val start: String, val bus: String)
    val services = Files.newDirectoryStream(proc).use { entries ->
        entries.mapNotNull { process -> runCatching {
            if (process.fileName.toString().toLongOrNull() == null || Files.getOwner(process) != owner) return@runCatching null
            val name = Files.readSymbolicLink(process.resolve("exe")).fileName.toString()
            if (name != "steam-runtime-launcher-service" && !name.endsWith("-srt-launcher-service")) return@runCatching null
            val before = start(process) ?: return@runCatching null
            val args = Files.newInputStream(process.resolve("cmdline")).use { it.readNBytes(8192) }
                .decodeToString().split('\u0000')
            val bus = args.firstOrNull { it.startsWith("--bus-name=") }?.substringAfter('=')
                ?: args.indexOf("--bus-name").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
                ?: "com.steampowered.PressureVessel.LaunchAlongsideSteam".takeIf { "--alongside-steam" in args }
                ?: return@runCatching null
            if (!bus.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]{1,254}")) || !selectedAncestor(process) || start(process) != before) return@runCatching null
            Service(process, before, bus)
        }.getOrNull() }
    }
    val service = services.singleOrNull() ?: throw IllegalArgumentException("snap_runtime_service_missing_or_ambiguous")
    val runner = root.resolve("ubuntu12_32/steam-runtime/run.sh")
    require(Files.isExecutable(runner)) { "missing_runtime_command_runner" }
    fun query(method: String, name: String): String {
        val process = ProcessBuilder(runner.toString(), "dbus-send", "--session", "--print-reply=literal",
            "--dest=org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus.$method", "string:$name")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            require(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) { "runtime_service_identity_unavailable" }
            return process.inputStream.readNBytes(1024).decodeToString().trim()
        } finally { process.destroyForcibly() }
    }
    // Pin the unique connection name: another Steam process taking the well-known
    // name after inspection must not redirect the probe into its runtime.
    val connection = query("GetNameOwner", service.bus)
    require(connection.matches(Regex(":[0-9]+\\.[0-9]+"))) { "runtime_service_identity_unavailable" }
    val reply = query("GetConnectionUnixProcessID", connection)
    val pid = Regex("uint32 ([0-9]+)").matchEntire(reply)?.groupValues?.get(1)
    require(pid == service.process.fileName.toString() && start(service.process) == service.start) { "runtime_service_identity_changed" }
    return listOf(runner.toString(), "steam-runtime-launch-client", "--bus-name=$connection", "--") + arguments
}
