package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.MenusPack
import gg.hideouthq.core.customgames.MenusStamp
import gg.hideouthq.core.customgames.PackToken
import gg.hideouthq.core.customgames.StageResult
import gg.hideouthq.core.customgames.LayoutSource
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameInfoSegments
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.PakNumbers
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.SearchOrderRead
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.library.FolderLock
import gg.hideouthq.library.deleteTree
import gg.hideouthq.library.headOf
import gg.hideouthq.library.readSmallText
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.time.Duration

internal class GamesFolder(private val helper: Path, private val workFolder: Path, private val log: (String) -> Unit, private val runLimit: Duration) {
    fun pack(game: GameRoot): MenusPack? {
        val root = rootOf(game)
        if (!Files.isRegularFile(root.resolve(MenusFolder).resolve(PackFile))) return null
        val read = readSmallText(root.resolve(StampFile))?.let(::objectOf) ?: return null
        val stamp = try {
            Json.decodeFromJsonElement(MenusStamp.serializer(), JsonObject(read - TokenKey))
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        val token = read.text(TokenKey)?.let(PackToken::parse)?.takeIf { stamp.inGame != null }
        return MenusPack(if (token == null) stamp.copy(inGame = null) else stamp, token)
    }

    /** The pack is built from [want] but for [InGameStamp.layouts], which come from the layouts the helper copied. */
    suspend fun buildMenus(game: GameRoot, renderer: Path, want: MenusStamp): MenusPack? = locked(game, { null }) { root ->
        Files.newDirectoryStream(root, "$PartPrefix*").use { leftovers -> leftovers.forEach(Files::deleteIfExists) }
        val part = root.resolve(PartPrefix + UUID.randomUUID())
        val layers = layersOf(game)
        val inGame = want.inGame?.let { listOf("--ingame-port", it.endpoint.toString()) + layers.flatMap { layer -> listOf("--layer", layer.pathString) } }
        val ran = run(
            listOf("menus", "--game", game.path, "--renderer", renderer.pathString, "--page", want.page, "--screen-port", want.screen.toString()) +
                (inGame ?: listOf("--renderer-only")) + listOf("--out", part.pathString),
        )
        val token = ran.token
        if (!ran.done || (want.inGame != null && token == null)) {
            log("Custom games: the menus were not built: ${if (ran.done) "the helper named no pack token" else ran.message}")
            Files.deleteIfExists(part)
            return@locked null
        }
        val built = want.copy(inGame = want.inGame?.copy(layouts = sourcesOf(game, layers, ran.shadows)))
        Files.createDirectories(root.resolve(MenusFolder))
        Files.move(part, root.resolve(MenusFolder).resolve(PackFile), StandardCopyOption.REPLACE_EXISTING)
        val stamp = root.resolve("$StampFile.part")
        val written = Json.encodeToJsonElement(MenusStamp.serializer(), built).jsonObject
        Files.writeString(stamp, (if (token == null) written else JsonObject(written + (TokenKey to JsonPrimitive(token.hex)))).toString())
        Files.move(stamp, root.resolve(StampFile), StandardCopyOption.REPLACE_EXISTING)
        MenusPack(built, token)
    }

    /** Where Deadlock would load each of [layouts] from without Hideout's menus. */
    fun sourcesOf(game: GameRoot, layouts: Collection<String>): Map<String, LayoutSource?> = sourcesOf(game, layersOf(game), layouts)

    private fun sourcesOf(game: GameRoot, layers: List<Path>, layouts: Collection<String>): Map<String, LayoutSource?> {
        val trees = layers.map { it to filesIn(it) }
        val under = Path.of(game.path, "game")
        return layouts.associateWith { layout ->
            trees.firstNotNullOfOrNull { (pack, files) -> files[layout]?.let { LayoutSource(under.relativize(pack).invariantSeparatorsPathString, it) } }
        }
    }

    /** The packs Deadlock reads after Hideout's menus and before its own, in the order it reads them. */
    private fun layersOf(game: GameRoot): List<Path> = searchOrderOf(game)?.behindMenus.orEmpty().flatMap { path ->
        packsIn(game, path).mapNotNull { pack -> PakNumbers.mounted(pack.name)?.let { it.value to pack } }.sortedBy { it.first }.map { it.second }
    }

    suspend fun removeMenus(game: GameRoot): Boolean = locked(game, { false }) { root ->
        Files.deleteIfExists(root.resolve(StampFile))
        Files.deleteIfExists(root.resolve(MenusFolder).resolve(PackFile))
        true
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

    /**
     * The first folder ahead of the menus with a file the renderer needs. What the engine reads of each folder is its loose
     * files and its `pak*_dir.vpk` packs; only the packs' trees are read here.
     */
    fun menusBlockedBy(game: GameRoot): String? {
        val own = filesIn(rootOf(game).resolve(MenusFolder).resolve(PackFile)).keys.filterNot(::isInGame).toSet()
        if (own.isEmpty()) return null
        return aheadWith(game, own::contains)
    }

    /** The first folder ahead of the menus with a file of the in-game UI's, whether or not the pack holds that UI now. */
    fun inGameBlockedBy(game: GameRoot): String? = aheadWith(game, ::isInGame)

    private fun aheadWith(game: GameRoot, wanted: (String) -> Boolean): String? =
        searchOrderOf(game)?.aheadOfMenus.orEmpty().firstOrNull { path -> packsIn(game, path).any { pack -> filesIn(pack).keys.any(wanted) } }

    private fun searchOrderOf(game: GameRoot): SearchOrderRead.Read? {
        val info = try {
            Files.readAllBytes(GameInfoSegments.fold(Path.of(game.path), Path::resolve))
        } catch (e: IOException) {
            return null
        }
        return GameInfoPatch.searchOrder(info) as? SearchOrderRead.Read
    }

    private fun packsIn(game: GameRoot, path: String): List<Path> = try {
        Files.newDirectoryStream(Path.of(game.path, "game").resolve(path.replace('\\', '/')), "pak*_dir.vpk").use { it.toList() }
    } catch (e: IOException) {
        emptyList()
    } catch (e: InvalidPathException) {
        emptyList()
    }

    private fun filesIn(pack: Path): Map<String, Long> = try {
        val size = Files.size(pack)
        (VpkFormat.resources(headOf(pack, size), size) as? ResourceIndex.Ready)?.files.orEmpty()
    } catch (e: IOException) {
        emptyMap()
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
        val built = said.firstOrNull { it.text("type") == "built" }
        return Ran(
            done = exited && process.exitValue() == 0,
            held = held.orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            message = if (exited) stopped?.text("message") else "it ran past its limit",
            devSession = dev?.booleanOrNull,
            token = built?.text("token")?.let(PackToken::parse),
            shadows = (built?.get("shadows") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { it.isString }?.content },
        )
    }

    /** [devSession] is whether a Modlock development session's block was live, null when there was none. */
    private class Ran(val done: Boolean, val held: List<String>, val message: String?, val devSession: Boolean? = null, val token: PackToken? = null, val shadows: List<String> = emptyList())

    private fun rootOf(game: GameRoot): Path = Path.of(game.path, "game", GameInfoPatch.CustomGamesFolder)

    private companion object {
        const val MenusFolder = "menus"
        const val ModeFolder = "mode"
        const val PackFile = "pak01_dir.vpk"
        const val StampFile = "menus.json"
        const val TokenKey = "token"
        const val PartPrefix = "menus.part-"
        const val LockFile = "hideout.lock"
        const val OutputWaitMillis = 1_000L
        const val Busy = "another Hideout is using this game's custom games folder"
    }
}

/** `hideout-link`'s in-game UI: its own folders and its shadows of the game's layouts, which tools/link/ingame_test.go checks. */
private val InGameLayouts = setOf("panorama/layout/citadel_hud_and_db_overlay.vxml_c", "panorama/layout/popups/citadel_popup_custom_match.vxml_c")
private val InGameFolder = Regex("panorama/[^/]+/hideout/.+")

private fun isInGame(name: String): Boolean = name in InGameLayouts || InGameFolder.matches(name)

private fun objectOf(line: String): JsonObject? = try {
    Json.parseToJsonElement(line) as? JsonObject
} catch (e: SerializationException) {
    null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
