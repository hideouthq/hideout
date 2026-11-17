package gg.hideouthq.tools

import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.ReplaceResult
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.MaxTokenLength
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.TemporaryInfix
import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.AppId
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.library.FileGameConfig
import gg.hideouthq.library.HttpDownloader
import gg.hideouthq.library.NioGameInventory
import gg.hideouthq.library.ProcessTable
import gg.hideouthq.library.SteamGameLocator
import gg.hideouthq.library.steamInstalls
import com.sun.nio.file.ExtendedOpenOption
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

fun main(args: Array<String>) {
    val running = when (args.singleOrNull()) {
        "running" -> true
        "stopped" -> false
        else -> error("Usage: WindowsProbeKt running|stopped")
    }
    exitProcess(runBlocking { probe(running) })
}

private suspend fun probe(running: Boolean): Int {
    val probe = Probe()
    val scan = ProcessTable().scan()
    probe.check("toolhelp32 scan", scan == ProcessScan.Seen(game = running), "$scan while deadlock.exe is ${if (running) "running" else "stopped"}")
    if (running) {
        val work = Files.createTempDirectory("hideout-probe").toRealPath()
        try {
            registry(probe)
            folders(probe)
            busyFiles(probe, work)
            readOnly(probe, work)
            downloadNames(probe, work)
        } finally {
            work.toFile().deleteRecursively()
        }
    }
    return probe.failed
}

private const val RegistrySteam = """C:\Games\Valve Steam"""
private const val MachineSteam = """C:\Games\Machine Steam"""
private const val Shots = """C:\Shots"""

private class Probe {
    var failed = 0
        private set

    fun check(name: String, passed: Boolean, detail: String) {
        println("${if (passed) "PASS" else "FAIL"} $name: $detail")
        if (!passed) failed++
    }
}

private suspend fun registry(probe: Probe) {
    val games = SteamGameLocator.forThisComputer().detect().map { it.path }
    fun found(steam: String) = games.any { it.equals("""$steam\steamapps\common\Deadlock""", ignoreCase = true) }
    val roots = steamInstalls().map { it.root }
    probe.check("registry HKCU SteamPath", found(RegistrySteam), "Steam roots $roots, detect() found $games")
    probe.check("registry HKLM InstallPath", found(MachineSteam), "Steam roots $roots, detect() found $games")
}

private fun folders(probe: Probe) {
    val local = File(System.getenv("LOCALAPPDATA"))
    probe.check("AppDirs.data", AppDirs.data() == local.resolve(AppId), "${AppDirs.data()}")
    probe.check("AppDirs.cache", AppDirs.cache() == local.resolve(AppId).resolve("Cache"), "${AppDirs.cache()}")
    probe.check("AppDirs.config", AppDirs.config() == File(System.getenv("APPDATA")).resolve(AppId), "${AppDirs.config()}")
    probe.check("AppDirs.pictures", AppDirs.pictures().path.equals(Shots, ignoreCase = true), "${AppDirs.pictures()}, the known folder the registry moved; ~\\Pictures would be the fallback")
}

private fun hold(file: Path, time: Duration, vararg refused: ExtendedOpenOption): Thread {
    val open = CountDownLatch(1)
    val holder = thread {
        FileChannel.open(file, StandardOpenOption.READ, *refused).use {
            open.countDown()
            Thread.sleep(time.inWholeMilliseconds)
        }
    }
    open.await()
    return holder
}

private fun Throwable?.described() = this?.let { "${it::class.simpleName} (${(it as? java.nio.file.FileSystemException)?.reason ?: it.message})" } ?: "no failure"

private suspend fun busyFiles(probe: Probe, work: Path) {
    val cache = work.resolve("cache").createDirectories()
    val library = cache.resolve("library.json").also { it.writeBytes("before".encodeToByteArray()) }
    var holder = hold(library, 300.milliseconds, ExtendedOpenOption.NOSHARE_READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)
    val sibling = cache.resolve("raw.tmp").also { it.writeBytes("raw".encodeToByteArray()) }
    val raw = runCatching { Files.move(sibling, library, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }.exceptionOrNull()
    Files.deleteIfExists(sibling)
    var waited = measureTime { FileCacheStorage(cache.toFile()).write("library.json", "after") }
    holder.join()
    probe.check(
        "busy replace (share mode 0)",
        library.readBytes().decodeToString() == "after" && waited >= 250.milliseconds,
        "a plain move over it saw ${raw.described()}; FileCacheStorage.write waited $waited and replaced it",
    )

    holder = hold(library, 3.seconds, ExtendedOpenOption.NOSHARE_READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)
    var failure: Throwable? = null
    waited = measureTime { failure = runCatching { FileCacheStorage(cache.toFile()).write("library.json", "late") }.exceptionOrNull() }
    holder.join()
    probe.check("busy replace held for 3 s", failure != null && waited in 1.8.seconds..2.8.seconds, "gave up after $waited with ${failure.described()}")

    val citadel = work.resolve("busy/Deadlock/game/citadel").createDirectories()
    val gameInfo = citadel.resolve("gameinfo.gi").also { it.writeBytes(GameInfo) }
    holder = hold(gameInfo, 300.milliseconds, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)
    val rawRename = runCatching { Files.move(gameInfo, citadel.resolve("gameinfo.gi.raw")) }.exceptionOrNull()
    val replaced = FileGameConfig().replace(edit(GameRoot(work.resolve("busy/Deadlock").toString())), Patched)
    holder.join()
    probe.check(
        "busy rename (shared for reading)",
        rawRename != null && replaced == ReplaceResult.Replaced && gameInfo.readBytes().contentEquals(Patched) && names(citadel) == listOf("gameinfo.gi"),
        "a plain rename saw ${rawRename.described()}; FileGameConfig.replace gave $replaced, leaving ${names(citadel)}",
    )

    val game = work.resolve("busy-read/Deadlock")
    val vpk = game.resolve("game/citadel/addons").createDirectories().resolve("pak01_dir.vpk").also { it.writeBytes(Body) }
    holder = hold(vpk, 300.milliseconds, ExtendedOpenOption.NOSHARE_READ, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)
    val rawRead = runCatching { Files.readAllBytes(vpk) }.exceptionOrNull()
    var seen: FileObservation? = null
    waited = measureTime { seen = NioGameInventory.file(GameRoot(game.toString()), "game/citadel/addons/pak01_dir.vpk") }
    holder.join()
    val image = Sha256.image(Body)
    probe.check(
        "busy read (share mode 0)",
        seen == FileObservation.Present(image.sizeBytes, image.sha256) && waited >= 250.milliseconds,
        "a plain read saw ${rawRead.described()}; NioGameInventory.file waited $waited and gave $seen",
    )

    val downloader = HttpDownloader(HttpClient(MockEngine { respond(Body) }) { expectSuccess = false }, work.resolve("busy-downloads"), hideoutAreas = emptyList())
    val archive = (downloader.download(downloader.defaultFolder, SubmissionRef(SubmissionKind.Mod, 1), remote(1, "bg_ivy.zip")) {} as DownloadResult.Done).archive
    val file = Path.of(archive.path)
    holder = hold(file, 300.milliseconds, ExtendedOpenOption.NOSHARE_WRITE, ExtendedOpenOption.NOSHARE_DELETE)
    val rawDelete = runCatching { Files.delete(file) }.exceptionOrNull()
    val entry = DownloadEntry(SubmissionIdentity(SubmissionKind.Mod, 1, 1, null), archive)
    val removed = downloader.removeExact(entry, Sha256.image(Body))
    holder.join()
    probe.check("busy delete (shared for reading)", rawDelete != null && removed == Removal.Removed && !Files.exists(file), "a plain delete saw ${rawDelete.described()}; removeExact gave $removed")
}

private suspend fun readOnly(probe: Probe, work: Path) {
    val citadel = work.resolve("read-only/Deadlock/game/citadel").createDirectories()
    val gameInfo = citadel.resolve("gameinfo.gi").also { it.writeBytes(GameInfo) }
    Files.setAttribute(gameInfo, "dos:readonly", true)
    val replaced = FileGameConfig().replace(edit(GameRoot(work.resolve("read-only/Deadlock").toString())), Patched)
    val stillMarked = Files.getAttribute(gameInfo, "dos:readonly") == true
    probe.check(
        "read-only gameinfo.gi",
        replaced == ReplaceResult.Replaced && gameInfo.readBytes().contentEquals(Patched) && names(citadel) == listOf("gameinfo.gi") && stillMarked,
        "FileGameConfig.replace gave $replaced, leaving ${names(citadel)}, the new file ${if (stillMarked) "still" else "no longer"} read-only",
    )
    Files.setAttribute(gameInfo, "dos:readonly", false)
}

private suspend fun downloadNames(probe: Probe, work: Path) {
    val published = listOf(
        "長".repeat(300) + ".zip" to "長".repeat(57) + ".zip",
        "🙂".repeat(100) + ".7z" to "🙂".repeat(43) + ".7z",
        "CON.zip" to "_CON.zip",
        "nul" to "_nul",
        "Aux.tar.gz" to "_Aux.tar.gz",
        "COM1.rar" to "_COM1.rar",
        "lpt9.vpk" to "_lpt9.vpk",
        "bg_ivy.zip" to "bg_ivy.zip",
    )
    val downloader = HttpDownloader(HttpClient(MockEngine { respond(Body) }) { expectSuccess = false }, work.resolve("downloads"), hideoutAreas = emptyList())
    val files = published.mapIndexed { index, (given, expected) ->
        val result = downloader.download(downloader.defaultFolder, SubmissionRef(SubmissionKind.Mod, 1), remote(index + 10L, given)) {}
        val archive = (result as? DownloadResult.Done)?.archive
        val file = archive?.let { Path.of(it.path) }
        val listed = file?.parent?.let(::names)
        probe.check(
            "download name ${given.take(12)}",
            file?.name == expected && listed == listOf(expected) && file.readBytes().contentEquals(Body),
            "published as ${file?.name ?: result}, ${expected.encodeToByteArray().size} bytes, listed as $listed",
        )
        file
    }
    val anchor = checkNotNull(files.first()).resolveSibling("${"長".repeat(57)} (100).zip$TemporaryInfix${"z".repeat(MaxTokenLength)}")
    val made = runCatching { Files.createFile(anchor) }.exceptionOrNull()
    probe.check("longest anchored name", made == null && anchor.name in names(anchor.parent), "${anchor.name.encodeToByteArray().size} bytes: ${made.described()}")
}

private fun remote(id: Long, name: String) = RemoteFile(id, name, Body.size.toLong(), null, null, 0, 0, "https://gamebanana.com/dl/$id")

private fun edit(root: GameRoot) = PendingChange.ConfigEdit(root, ConfigTarget.GameInfo(HideoutLines(mods = true, customGames = false)), "probe", Sha256.image(GameInfo), Sha256.image(Patched))

private fun names(folder: Path) = Files.list(folder).use { paths -> paths.map { it.name }.sorted().toList() }

private val Body = ByteArray(4096) { (it % 251).toByte() }
private val GameInfo = "GameInfo { FileSystem { SearchPaths {\n Game citadel\n } } }\n".encodeToByteArray()
private val Patched = "GameInfo { FileSystem { SearchPaths {\n // Hideout start\n Game citadel/addons\n // Hideout end\n Game citadel\n } } }\n".encodeToByteArray()
