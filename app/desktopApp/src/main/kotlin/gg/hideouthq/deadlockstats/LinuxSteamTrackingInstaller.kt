package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstallation
import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstaller
import gg.hideouthq.data.AppDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object SteamBridgeCompatibility {
    fun hash(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }
}

internal class LinuxSteamTrackingInstaller(
    private val targets: suspend () -> List<SteamInstallationTarget>,
    private val os: String = System.getProperty("os.name"),
    private val data: Path = AppDirs.data().resolve("deadlock-stats").toPath(),
    private val applications: Path = Path.of(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.local/share", "applications"),
    private val autostart: Path = Path.of(System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "${System.getProperty("user.home")}/.config", "autostart"),
    private val systemDesktop: Path? = null,
    private val resource: (SteamArchitecture, String) -> ByteArray? = { architecture, name -> LinuxSteamTrackingInstaller::class.java.getResourceAsStream("/steam-bridge/${architecture.resource}/$name")?.use { it.readBytes() } },
    private val probe: (SteamInstallationTarget, Path) -> SteamBridgeReport = ::probeSteamBridge,
) : SteamTrackingInstaller {
    @Serializable private data class Edit(val path: String, val original: String?, val installed: String, val mode: String)
    @Serializable private data class Receipt(val version: Int = 1, val committed: Boolean = false, val bundle: String,
        val libraryHash: String, val targetId: String, val edits: List<Edit>)
    @Serializable private data class Selection(val id: String, val game: String?)
    private val json = Json { ignoreUnknownKeys = true }
    private val installRoot = data.resolve("steam-bridge-install")
    private val receiptFile = installRoot.resolve("receipt.json")
    private val lock = Mutex()
    private val artifactNames = listOf("libhideout_steam_bridge.so", "bridge_probe", "launch.sh", "final-launch.sh", "client-container.sh", "THIRD_PARTY_NOTICES.txt", "BUILD.json")

    private fun receipt(): Receipt? = if (Files.isRegularFile(receiptFile)) json.decodeFromString<Receipt>(Files.readString(receiptFile)).also { require(it.version == 1) } else null
    private val selectionFile = installRoot.resolve("selection.json")
    @Volatile private var selectedTarget: SteamInstallationTarget? = null

    private fun selected(available: List<SteamInstallationTarget>): SteamInstallationTarget? {
        available.singleOrNull { it.ownsGame }?.let { return it }
        val saved = if (Files.isRegularFile(selectionFile)) json.decodeFromString<Selection>(Files.readString(selectionFile)) else null
        return available.singleOrNull { it.id == saved?.id && it.game == saved.game }
    }

    override suspend fun select(id: String): SteamTrackingInstallation = withContext(Dispatchers.IO) {
        lock.withLock {
            val available = targets()
            val target = available.single { it.id == id }
            val owner = available.singleOrNull { it.ownsGame }
            require(owner == null || owner.id == id) { "Select the Steam installation that owns the game." }
            if (selected(available)?.id == id) return@withLock inspectSelected()
            selectedTarget = null
            restore()
            privateDirectory(installRoot)
            atomicWrite(selectionFile, json.encodeToString(Selection(id, target.game)).toByteArray(), "rw-------")
            inspectSelected()
        }
    }

    private fun artifactHashes(manifest: ByteArray): Map<String, String> {
        val entries = manifest.decodeToString().lineSequence().filter { it.isNotBlank() }.map { line ->
            val parts = line.split("  ")
            require(parts.size == 2 && parts[0].matches(Regex("[0-9a-f]{64}")) && parts[1] in artifactNames) { "Invalid bridge artifact manifest" }
            parts[1] to parts[0]
        }.toList()
        require(entries.size == artifactNames.size && entries.map { it.first }.toSet() == artifactNames.toSet()) { "Incomplete bridge artifact manifest" }
        return entries.toMap()
    }

    private fun validBundle(bundle: Path): Boolean = runCatching {
        val manifest = Files.readAllBytes(bundle.resolve("SHA256SUMS"))
        bundle.fileName.toString() == MessageDigest.getInstance("SHA-256").digest(manifest).toHexString() &&
            artifactHashes(manifest).all { (name, hash) -> Files.isRegularFile(bundle.resolve(name)) && SteamBridgeCompatibility.hash(bundle.resolve(name)) == hash }
    }.getOrDefault(false)

    private fun prepareBundle(target: SteamInstallationTarget): Path? {
        val manifest = resource(target.architecture, "SHA256SUMS") ?: return null
        val hashes = artifactHashes(manifest)
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = artifactNames.associateWith { name ->
            requireNotNull(resource(target.architecture, name)) { "Missing bridge artifact $name" }.also {
                check(digest.digest(it).toHexString() == hashes.getValue(name)) { "Damaged bridge artifact $name" }
            }
        }
        val bundle = target.storage.resolve(digest.digest(manifest).toHexString())
        privateDirectory(target.storage)
        privateDirectory(bundle)
        if (!validBundle(bundle)) {
            bytes.forEach { (name, content) -> atomicWrite(bundle.resolve(name), content, if (name.endsWith(".sh") || name == "bridge_probe") "rwx------" else "rw-------") }
            atomicWrite(bundle.resolve("SHA256SUMS"), manifest, "rw-------")
        }
        check(validBundle(bundle)) { "Bridge artifact integrity verification failed" }
        privateDirectory(target.endpointDirectory)
        val config = mapOf("steam-root" to target.runtimeRoot.toString(), "architecture" to target.architecture.clientDirectory,
            "package" to target.client.name, "endpoint-path" to target.runtimeRoot.resolve(".hideout-tracking/session/endpoint").toString())
        config.forEach { (name, value) -> atomicWrite(bundle.resolve(name), (value + "\n").toByteArray(), "rw-------") }
        return bundle
    }

    override suspend fun inspect(): SteamTrackingInstallation = lock.withLock { inspectSelected() }

    private suspend fun inspectSelected(): SteamTrackingInstallation = withContext(Dispatchers.IO) {
        if (!os.lowercase().contains("linux")) return@withContext SteamTrackingInstallation(reason = "Steam tracking is not available on this platform yet.")
        var saved = receipt()
        val available = targets()
        val target = selected(available)
        if (saved != null && saved.targetId != target?.id) {
            selectedTarget = null
            restore()
            saved = null
        }
        selectedTarget = target
        val choices = available.map { it.choice() }
        if (target == null) return@withContext SteamTrackingInstallation(installed = saved != null,
            reason = if (available.isEmpty()) "No Steam installation found." else "Choose the Steam installation to track.", choices = choices)
        if (target.launchContext is SteamLaunchContext.Fhs && target.launchContext.runner == null)
            return@withContext SteamTrackingInstallation(installed = saved != null, reason = "Steam tracking needs the matching steam-run FHS command runner.", choices = choices, selectedId = target.id)
        val bundle = prepareBundle(target) ?: return@withContext SteamTrackingInstallation(installed = saved != null,
            reason = "This Hideout build does not include the native Steam bridge.", choices = choices, selectedId = target.id)
        val report = probe(target, bundle)
        val reason = report.reason.takeUnless { report.version == 1 && report.compatible }
        if (reason != null) return@withContext SteamTrackingInstallation(installed = saved != null,
            reason = when (reason) {
                "snap_runtime_service_missing_or_ambiguous" -> "Open the selected Snap Steam client, then check tracking again. Inspection needs its existing runtime service."
                "runtime_command_failed" -> "Steam's runtime could not run the compatibility check. Check that its runtime components are available, then try again. Steam will start normally."
                "invalid_probe_response" -> "Steam's runtime did not return a valid compatibility result. Try the check again. Steam will start normally."
                else -> "Steam bridge compatibility: $reason. Steam will start normally."
            }, choices = choices, selectedId = target.id)
        if (saved != null && Path.of(saved.bundle) != bundle.toAbsolutePath() && owned(saved)) {
            activate(target, bundle, saved)
            saved = receipt()
        }
        val installed = saved?.targetId == target.id && Path.of(saved.bundle) == bundle.toAbsolutePath() && owned(saved)
        if (saved?.targetId == target.id && !installed) return@withContext SteamTrackingInstallation(false, true,
            "The Steam launcher installation needs repair. Use Disable Steam tracking to restore it; later edits will be preserved.", choices = choices, selectedId = target.id)
        SteamTrackingInstallation(true, installed, choices = choices, selectedId = target.id)
    }

    override suspend fun install(): SteamTrackingInstallation = withContext(Dispatchers.IO) {
        lock.withLock {
            val status = inspectSelected()
            if (!status.supported || status.installed) return@withContext status
            val targetInstallation = requireNotNull(selectedTarget)
            val bundle = requireNotNull(prepareBundle(targetInstallation))
            if (receipt()?.targetId != targetInstallation.id) restore()
            check(receipt() == null) { "A previous launcher installation needs to be disabled/restored before reinstalling." }
            activate(targetInstallation, bundle)
            inspectSelected()
        }
    }

    private fun owns(edit: Edit): Boolean {
        val path = Path.of(edit.path)
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.getOwner(path) == Files.getOwner(Path.of(System.getProperty("user.home"))) &&
            PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) == edit.mode &&
            Files.readString(path) == edit.installed
    }

    private fun owned(saved: Receipt): Boolean = saved.committed && saved.edits.all(::owns) &&
        validBundle(Path.of(saved.bundle)) && !Files.exists(Path.of(saved.bundle).resolve("disabled"))

    private fun activate(targetInstallation: SteamInstallationTarget, bundle: Path, previous: Receipt? = null) {
        val report = probe(targetInstallation, bundle)
        check(report.version == 1 && report.compatible) { "Steam bridge compatibility: ${report.reason}; Steam launchers were not changed." }
        if (previous != null) {
            check(owned(previous)) { "The Steam launcher changed during inspection; its edits were preserved." }
            selectedTarget = null
            restore()
            selectedTarget = targetInstallation
        }
        privateDirectory(installRoot)
        val libraryHash = SteamBridgeCompatibility.hash(bundle.resolve("libhideout_steam_bridge.so"))
        atomicWrite(bundle.resolve("disabled"), byteArrayOf(), "rw-------")
        val target = applications.resolve(targetInstallation.desktopName)
        val source = if (Files.exists(target)) target else systemDesktop ?: defaultSteamDesktop(targetInstallation.desktopName)
        require(Files.isRegularFile(source) && !Files.isSymbolicLink(target)) { "No editable Steam desktop entry found" }
        fun prepare(path: Path, content: String): Edit {
            require(!Files.isSymbolicLink(path)) { "Refusing a symbolic-link desktop override" }
            val original = if (Files.exists(path)) Files.readString(path) else null
            val mode = if (original != null) PosixFilePermissions.toString(Files.getPosixFilePermissions(path)) else "rw-r--r--"
            return Edit(path.toAbsolutePath().toString(), original, SteamDesktopEntry.wrap(content, bundle.resolve("launch.sh").toAbsolutePath().toString()), mode)
        }
        val edits = mutableListOf(prepare(target, Files.readString(source)))
        val auto = autostart.resolve(targetInstallation.desktopName)
        if (Files.isRegularFile(auto)) edits += prepare(auto, Files.readString(auto))
        val saved = Receipt(bundle = bundle.toAbsolutePath().toString(), libraryHash = libraryHash, targetId = targetInstallation.id, edits = edits)
        atomicWrite(receiptFile, json.encodeToString(saved).toByteArray(), "rw-------")
        try {
            for (edit in edits) atomicWrite(Path.of(edit.path), edit.installed.toByteArray(), edit.mode)
            atomicWrite(receiptFile, json.encodeToString(saved.copy(committed = true)).toByteArray(), "rw-------")
            Files.delete(bundle.resolve("disabled"))
        } catch (failure: Exception) {
            try {
                restore()
            } catch (rollback: Exception) {
                failure.addSuppressed(rollback)
            }
            throw failure
        }
    }

    private fun restore() {
        val saved = receipt() ?: return
        atomicWrite(Path.of(saved.bundle).resolve("disabled"), byteArrayOf(), "rw-------")
        for (edit in saved.edits) {
            val path = Path.of(edit.path)
            if (!owns(edit)) continue
            if (edit.original == null) Files.delete(path) else atomicWrite(path, edit.original.toByteArray(), edit.mode)
        }
        Files.delete(receiptFile)
    }

    override suspend fun uninstall(): SteamTrackingInstallation = withContext(Dispatchers.IO) {
        lock.withLock {
            selectedTarget = null
            restore()
            inspectSelected()
        }
    }

    fun endpointDirectory(): Path = requireNotNull(selectedTarget) { "No Steam installation selected" }.endpointDirectory

    fun startupStatus() = selectedTarget?.let { readSteamStartupStatus(it.endpointDirectory, it) }

    fun admit(hello: SteamBridgeHello): SteamProcessIdentity? = runCatching {
        val saved = receipt() ?: return null
        val target = selectedTarget ?: return null
        if (saved.targetId != target.id || !saved.committed || !validBundle(Path.of(saved.bundle)) ||
            Files.exists(Path.of(saved.bundle).resolve("disabled"))) return null
        SteamProcessResolver().admit(hello, target, Path.of(saved.bundle).resolve("libhideout_steam_bridge.so"), saved.libraryHash)
    }.getOrElse { if (it is SteamProcessMetadataUnavailable) throw it else null }

    private fun privateDirectory(path: Path) {
        Files.createDirectories(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        require(!Files.isSymbolicLink(path) && Files.getOwner(path) == Files.getOwner(Path.of(System.getProperty("user.home"))))
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
    }

    private fun atomicWrite(path: Path, bytes: ByteArray, mode: String) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".hideout-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)))
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal fun defaultSteamDesktop(name: String, home: Path = Path.of(System.getProperty("user.home")),
    dataDirs: String = System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share"): Path =
    (dataDirs.split(':').filter { it.isNotBlank() }.map(Path::of) +
        listOf(home.resolve(".local/share/flatpak/exports/share"), Path.of("/var/lib/flatpak/exports/share"), Path.of("/var/lib/snapd/desktop")))
    .filter { it.isAbsolute }.distinct()
    .map { it.resolve("applications/$name") }.firstOrNull { Files.isRegularFile(it) }
    ?: Path.of("/usr/share/applications/$name")

@Serializable
internal data class SteamBridgeReport(val version: Int, val compatible: Boolean, val reason: String, val buildId: String = "")

/** maps uses the superblock device; Btrfs getattr substitutes a subvolume device in stat(). */
internal fun linuxMappedDevice(path: Path, statDevice: Long, mountInfo: List<String>): Pair<Long, Long> {
    val mount = mountInfo.mapNotNull { line ->
        val sections = line.split(" - ", limit = 2)
        if (sections.size != 2) return@mapNotNull null
        val fields = sections[0].split(' ')
        if (fields.size < 6) return@mapNotNull null
        val point = Path.of(procPath(fields[4]))
        if (!point.isAbsolute || !path.startsWith(point)) return@mapNotNull null
        Triple(point, fields[2], sections[1].substringBefore(' '))
    }.maxByOrNull { it.first.toString().length }
    if (mount?.third == "btrfs") {
        val device = mount.second.split(':')
        require(device.size == 2)
        return device[0].toLong() to device[1].toLong()
    }
    return ((statDevice shr 8 and 0xfff) or (statDevice shr 32 and 0xfffff000L)) to
        ((statDevice and 0xff) or (statDevice shr 12 and 0xffffff00L))
}

internal fun probeSteamBridge(target: SteamInstallationTarget, bundle: Path): SteamBridgeReport = runCatching {
    val process = ProcessBuilder(target.probeCommand(bundle))
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    try {
        if (!process.waitFor(45, TimeUnit.SECONDS)) return SteamBridgeReport(1, false, "probe_timeout")
        val output = process.inputStream.readNBytes(4096).decodeToString()
        val report = runCatching { Json.decodeFromString<SteamBridgeReport>(output) }.getOrNull()
            ?: return SteamBridgeReport(1, false, if (output.isBlank() && process.exitValue() != 0)
                "runtime_command_failed" else "invalid_probe_response")
        if (report.version != 1 || report.compatible != (process.exitValue() == 0))
            return SteamBridgeReport(1, false, "invalid_probe_response")
        report
    } finally {
        process.destroyForcibly()
    }
}.getOrElse { SteamBridgeReport(1, false, if (it is IllegalArgumentException) it.message ?: "invalid_runtime" else "probe_failed") }
