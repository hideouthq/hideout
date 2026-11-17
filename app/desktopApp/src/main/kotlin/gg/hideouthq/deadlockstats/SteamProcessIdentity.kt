package gg.hideouthq.deadlockstats

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.UserPrincipal

/** A connection remains bound to this process incarnation, never just its namespace-local PID. */
internal data class SteamProcessIdentity(val hostPid: Long, val start: String)

internal class SteamProcessMetadataUnavailable : IOException(
    "This app runtime cannot inspect Steam's process metadata. Run Hideout in a runtime with access to that Steam process.",
)

internal class SteamProcessResolver(
    private val procRoot: Path = Path.of("/proc"),
    private val owner: UserPrincipal = Files.getOwner(Path.of(System.getProperty("user.home"))),
) {
    private fun identity(proc: Path): SteamProcessIdentity? = runCatching {
        val pid = proc.fileName.toString().toLongOrNull()?.takeIf { it > 0 } ?: return null
        if (Files.getOwner(proc) != owner) return null
        val stat = Files.readString(proc.resolve("stat"))
        val end = stat.lastIndexOf(") ")
        if (end < 0 || stat.substringBefore(' ').toLongOrNull() != pid) return null
        val start = stat.substring(end + 2).split(' ').getOrNull(19)?.takeIf { it.toULongOrNull() != null } ?: return null
        SteamProcessIdentity(pid, start)
    }.getOrNull()

    fun resolve(pid: Long, executable: Path, architecture: SteamArchitecture, verify: (Path) -> Boolean = { true }): SteamProcessIdentity? {
        if (pid <= 0) return null
        val matches = Files.newDirectoryStream(procRoot).use { entries ->
            entries.mapNotNull { proc ->
                var namespaceMatches = false
                runCatching {
                    val before = identity(proc) ?: return@runCatching null
                    val namespacePids = Files.readAllLines(proc.resolve("status")).singleOrNull { it.startsWith("NSpid:") }
                        ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.map { it.toLong() } ?: return@runCatching null
                    if (namespacePids.firstOrNull() != before.hostPid || namespacePids.lastOrNull() != pid) return@runCatching null
                    namespaceMatches = true
                    if (!Files.isSameFile(proc.resolve("exe"), executable) || SteamArchitecture.read(proc.resolve("exe")) != architecture) return@runCatching null
                    if (!verify(proc) || identity(proc) != before || !Files.isSameFile(proc.resolve("exe"), executable)) return@runCatching null
                    before
                }.getOrElse {
                    if (namespaceMatches && it is AccessDeniedException) throw SteamProcessMetadataUnavailable()
                    null
                }
            }
        }
        return matches.singleOrNull()
    }

    fun admit(hello: SteamBridgeHello, target: SteamInstallationTarget, library: Path, libraryHash: String): SteamProcessIdentity? = runCatching {
        if (hello.bits != target.architecture.bits || SteamBridgeCompatibility.hash(library) != libraryHash ||
            SteamArchitecture.read(target.module) != target.architecture || SteamArchitecture.read(library) != target.architecture) return null
        resolve(hello.pid, target.executable, target.architecture) { proc ->
            val mounts = Files.readAllLines(proc.resolve("mountinfo"))
            val maps = Files.readAllLines(proc.resolve("maps")).map { it.split(Regex("\\s+"), limit = 6) }
            listOf(target.executable, target.module, library).all { expected ->
                val entries = maps.filter { fields ->
                    if (fields.size != 6) false else runCatching {
                        val path = Path.of(procPath(fields[5]).removeSuffix(" (deleted)"))
                        path.isAbsolute && Files.isSameFile(proc.resolve("root").resolve(path.toString().removePrefix("/")), expected)
                    }.getOrElse { if (it is AccessDeniedException) throw it else false }
                }
                entries.isNotEmpty() && entries.any { it[1].contains('x') } && entries.all { fields ->
                    val mapped = Path.of(procPath(fields[5]))
                    val view = proc.resolve("root").resolve(mapped.toString().removePrefix("/"))
                    val inode = (Files.getAttribute(view, "unix:ino") as Number).toLong()
                    val device = (Files.getAttribute(view, "unix:dev") as Number).toLong()
                    val (major, minor) = linuxMappedDevice(mapped, device, mounts)
                    val parts = fields[3].split(':')
                    !fields[5].endsWith(" (deleted)") &&
                        Files.readSymbolicLink(proc.resolve("map_files/${fields[0]}")).toString() == mapped.toString() &&
                        fields[4].toLong() == inode && parts.size == 2 &&
                        parts[0].toLong(16) == major && parts[1].toLong(16) == minor
                }
            }
        }
    }.getOrElse { if (it is SteamProcessMetadataUnavailable) throw it else null }
}

internal fun procPath(value: String) = value.replace(Regex("""\\([0-7]{3})""")) { it.groupValues[1].toInt(8).toChar().toString() }
