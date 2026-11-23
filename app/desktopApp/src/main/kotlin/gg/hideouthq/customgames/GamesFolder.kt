package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.MenusPack
import gg.hideouthq.core.customgames.MenusStamp
import gg.hideouthq.core.customgames.PackToken
import gg.hideouthq.core.customgames.StageResult
import gg.hideouthq.core.customgames.isMenusFile
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameInfoSegments
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.SearchOrderRead
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.library.FolderLock
import gg.hideouthq.library.deleteTree
import gg.hideouthq.library.headOf
import gg.hideouthq.library.readSmallText
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.time.Duration

internal class GamesFolder(private val helper: Path, private val workFolder: Path, private val log: (String) -> Unit, private val runLimit: Duration) {
    fun pack(game: GameRoot): MenusPack? {
        val root = rootOf(game)
        if (!Files.isRegularFile(root.resolve(MenusFolder).resolve(PackFile))) return null
        val read = readSmallText(root.resolve(StampFile))?.let { text ->
            try {
                Json.decodeFromString(MenusFile.serializer(), text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        } ?: return null
        return PackToken.parse(read.token)?.let { MenusPack(read.stamp, it) }
    }

    suspend fun buildMenus(game: GameRoot, renderer: Path, want: MenusStamp): MenusPack? = locked(game, { null }) { root ->
        Files.newDirectoryStream(root, "$PartPrefix*").use { leftovers -> leftovers.forEach(Files::deleteIfExists) }
        val part = root.resolve(PartPrefix + UUID.randomUUID())
        val ran = run(
            listOf("menus", "--game", game.path, "--renderer", renderer.pathString, "--page", want.page, "--screen-port", want.screen.toString(), "--ingame-port", want.endpoint.toString(), "--out", part.pathString),
        )
        val token = ran.token
        if (!ran.done || token == null) {
            log("Custom games: the menus were not built: ${if (ran.done) "the helper named no pack token" else ran.message}")
            Files.deleteIfExists(part)
            return@locked null
        }
        Files.createDirectories(root.resolve(MenusFolder))
        Files.move(part, root.resolve(MenusFolder).resolve(PackFile), StandardCopyOption.REPLACE_EXISTING)
        val stamp = root.resolve("$StampFile.part")
        Files.writeString(stamp, Json.encodeToString(MenusFile.serializer(), MenusFile(want, token.hex)))
        Files.move(stamp, root.resolve(StampFile), StandardCopyOption.REPLACE_EXISTING)
        MenusPack(want, token)
    }

    suspend fun stage(game: GameRoot, content: List<String>): StageResult = locked(game, StageResult::Failed) { root ->
        val ran = run(listOf("stage", "--into", root.resolve(ModeFolder).pathString) + content.flatMap { listOf("--content", it) })
        if (ran.done) StageResult.Staged(ran.held) else StageResult.Failed(ran.message ?: "the stage did not finish")
    }

    /** Modlock's own clear of a development session's block no live session holds, which the helper runs. */
    suspend fun clearEndedDevSession(game: GameRoot) {
        val ran = run(listOf("clear-dev", "--game", game.path))
        when {
            !ran.done -> log("Custom games: could not check for a Modlock dev session's block: ${ran.message}")
            ran.devSession == false -> log("Custom games: took out the block of a Modlock dev session that had ended, whose menus loaded ahead of Hideout's")
            ran.devSession == true -> log("Custom games: a Modlock dev session is running in this game, so its block stays")
        }
    }

    /** What the engine reads of each folder is its loose files and its `pak*_dir.vpk` packs; only the packs' trees are read here. */
    fun menusBlockedBy(game: GameRoot): String? {
        val info = try {
            Files.readAllBytes(GameInfoSegments.fold(Path.of(game.path), Path::resolve))
        } catch (e: IOException) {
            return null
        }
        val ahead = (GameInfoPatch.searchOrder(info) as? SearchOrderRead.Read)?.aheadOfMenus.orEmpty()
        return ahead.firstOrNull { path -> packsIn(game, path).any(::holdsMenusFiles) }
    }

    private fun packsIn(game: GameRoot, path: String): List<Path> = try {
        Files.newDirectoryStream(Path.of(game.path, "game").resolve(path.replace('\\', '/')), "pak*_dir.vpk").use { it.toList() }
    } catch (e: IOException) {
        emptyList()
    } catch (e: InvalidPathException) {
        emptyList()
    }

    private fun holdsMenusFiles(pack: Path): Boolean = try {
        val size = Files.size(pack)
        (VpkFormat.resources(headOf(pack, size), size) as? ResourceIndex.Ready)?.files?.keys?.any(::isMenusFile) == true
    } catch (e: IOException) {
        false
    }

    fun remove(game: GameRoot): Boolean {
        val root = rootOf(game)
        if (!Files.isDirectory(root)) return true
        try {
            val lock = FolderLock.take(root) ?: return false
            lock.use {
                Files.list(root).use { files -> files.filter { it.name != LockFile }.toList() }.forEach(::deleteTree)
            }
            Files.deleteIfExists(root.resolve(LockFile))
            Files.deleteIfExists(root)
            return true
        } catch (e: IOException) {
            log("Custom games: could not remove the custom games folder: ${e::class.simpleName}")
            return false
        }
    }

    private suspend fun <T> locked(game: GameRoot, failed: (String) -> T, work: suspend (Path) -> T): T {
        val root = rootOf(game)
        val problem = try {
            FolderLock.take(root)?.use { return work(root) } ?: Busy
        } catch (e: IOException) {
            "could not write the custom games folder: ${e::class.simpleName}"
        }
        log("Custom games: $problem")
        return failed(problem)
    }

    private suspend fun run(arguments: List<String>): Ran {
        Files.createDirectories(workFolder)
        val process = ProcessBuilder(listOf(helper.pathString) + arguments)
            .directory(workFolder.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.close()
        val lines = mutableListOf<JsonObject>()
        val output = thread(isDaemon = true, name = "custom-games-${arguments.first()}-stdout") {
            try {
                process.inputStream.bufferedReader().forEachLine { line -> objectOf(line)?.let { synchronized(lines) { lines += it } } }
            } catch (e: IOException) {
            }
        }
        val exited = try {
            withTimeoutOrNull(runLimit) { runInterruptible { process.waitFor() } } != null
        } finally {
            // Windows holds the files a process wrote until it has exited, and the release after a stop removes them.
            if (process.isAlive) process.destroyForcibly().waitFor(OutputWaitMillis, TimeUnit.MILLISECONDS)
        }
        output.join(OutputWaitMillis)
        val said = synchronized(lines) { lines.toList() }
        val stopped = said.lastOrNull { it.text("type") == "stopped" }
        val held = said.firstOrNull { it.text("type") == "staged" }?.get("held") as? JsonArray
        val dev = said.firstOrNull { it.text("type") == "dev-session" }?.get("live") as? JsonPrimitive
        return Ran(
            done = exited && process.exitValue() == 0,
            held = held.orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            message = if (exited) stopped?.text("message") else "it ran past its limit",
            devSession = dev?.booleanOrNull,
            token = said.firstOrNull { it.text("type") == "built" }?.text("token")?.let(PackToken::parse),
        )
    }

    /** [devSession] is whether a Modlock development session's block was live, null when there was none. */
    private class Ran(val done: Boolean, val held: List<String>, val message: String?, val devSession: Boolean? = null, val token: PackToken? = null)

    @Serializable
    private class MenusFile(val stamp: MenusStamp, val token: String)

    private fun rootOf(game: GameRoot): Path = Path.of(game.path, "game", GameInfoPatch.CustomGamesFolder)

    private companion object {
        const val MenusFolder = "menus"
        const val ModeFolder = "mode"
        const val PackFile = "pak01_dir.vpk"
        const val StampFile = "menus.json"
        const val PartPrefix = "menus.part-"
        const val LockFile = "hideout.lock"
        const val OutputWaitMillis = 1_000L
        const val Busy = "another Hideout is using this game's custom games folder"
    }
}

private fun objectOf(line: String): JsonObject? = try {
    Json.parseToJsonElement(line) as? JsonObject
} catch (e: SerializationException) {
    null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
