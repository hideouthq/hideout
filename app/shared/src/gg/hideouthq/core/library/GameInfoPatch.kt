package gg.hideouthq.core.library

/** Who makes Deadlock read `addons`: Hideout's own marked block, or a line someone else wrote. */
enum class LoadingOwner { Hideout, AnotherTool }

enum class GameInfoProblem {
    Missing,
    Unreadable,
    Unparseable,
    NoSearchPaths,
    AmbiguousSearchPaths,
    NoBaseGameEntry,

    /** The base `Game citadel` entry does not sit on a line of its own, so there is no line to put a block before. */
    UnsupportedLayout,
}

sealed interface SearchPathsCheck {
    /**
     * [coveredBy] is null when no active entry loads `addons` ahead of the base game. [customGames] says an intact Hideout
     * block ahead of it names the custom games paths, and [overflow] how many loading folders past `addons` it mounts after
     * an entry that loads `addons`.
     */
    data class Read(val coveredBy: LoadingOwner?, val customGames: Boolean, val overflow: Int = 0) : SearchPathsCheck

    data class Problem(val problem: GameInfoProblem) : SearchPathsCheck
}

sealed interface MountsRead {
    /** The folders under `citadel` that `Game` entries mount ahead of the base game, earliest first, case as written. */
    data class Read(val mounts: List<CitadelPath>) : MountsRead

    data class Problem(val problem: GameInfoProblem) : MountsRead
}

/** An unconditional `Game` entry of `SearchPaths`, its path as written, and whether it is in Hideout's block. */
data class SearchEntry(val path: String, val hideouts: Boolean)

sealed interface SearchOrderRead {
    /**
     * [entries] are the `Game` entries Deadlock reads up to and including the base game's, earliest first. [aheadOfMenus]
     * are the paths of those it reads before Hideout's menus, none while Hideout's block names no custom games paths.
     */
    data class Read(val entries: List<SearchEntry>, val aheadOfMenus: List<String>) : SearchOrderRead

    data class Problem(val problem: GameInfoProblem) : SearchOrderRead
}

sealed interface GameInfoEdit {
    class Patched(val bytes: ByteArray) : GameInfoEdit

    data object Unchanged : GameInfoEdit

    data class Refused(val problem: GameInfoProblem) : GameInfoEdit
}

/** What Hideout's one block carries: `addons` for [mods], and the two custom games paths for [customGames]. Both false is no block. */
data class HideoutLines(val mods: Boolean, val customGames: Boolean)

/**
 * Reads and edits `GameInfo › FileSystem › SearchPaths` in `gameinfo.gi` without disturbing any other byte. Mods
 * load when an active `Game citadel/addons` entry comes before the first active `Game citadel`. A comment, an entry
 * under a condition, a later entry or another key does not count. Each of `GameInfo`, `FileSystem` and `SearchPaths`
 * must be the only entry of its name where it sits, with no condition; otherwise which entries the engine reads is
 * not known, so nothing is counted or edited. Hideout's edit is one marked block put on the lines before that base
 * entry, copying its indentation, separator, quoting and line ending; removing it gives back the exact bytes it was
 * added to. A block a Modlock development session left is a mod author's: it stays through every edit, and the `Mod`
 * and `Write` it copied never stand in for the file's own. Bytes are read as ISO-8859-1, so offsets are byte offsets
 * whatever the comments are written in.
 */
object GameInfoPatch {
    /** Under `game/`, the folder that holds both custom games search paths and nothing else on one. */
    const val CustomGamesFolder = "citadel/hideout_games"

    val CustomGamesPaths = listOf("$CustomGamesFolder/menus", "$CustomGamesFolder/mode")

    private const val StartMarker = "// Hideout start"
    private const val EndMarker = "// Hideout end"
    private val DmmMarkers = setOf("// Deadlock Mod Manager - Start", "// Deadlock Mod Manager - End")
    private const val AddonsPath = "citadel/addons"
    private val OverflowPaths = SetFolder.Loading.drop(1).map { "citadel/${it.inCitadel}" }
    private const val BasePath = "citadel"
    private const val GameKey = "Game"
    private const val ModKey = "Mod"
    private const val WriteKey = "Write"
    private val SearchPathsLocation = listOf("GameInfo", "FileSystem", "SearchPaths")
    private const val ModlockBegin = "// MODLOCK DEV BEGIN"
    private const val ModlockEnd = "// MODLOCK DEV END"
    private const val ModlockMounts = "citadel/modlock_dev"

    fun check(bytes: ByteArray): SearchPathsCheck = when (val read = read(bytes)) {
        is Parse.Failed -> SearchPathsCheck.Problem(read.problem)
        is Parse.Done -> SearchPathsCheck.Read(read.view.coveredBy, read.view.customGames, read.view.overflow)
    }

    /**
     * The file with Hideout's block exactly as [lines] asks, rendered afresh, or without it when it asks for nothing.
     * While [lines] asks for mods, the block also mounts the first [overflow] loading folders past `addons`, in order,
     * right after `addons` loads; with none it is the block as it always was. While another tool's entry loads `addons`,
     * Hideout adds no `addons` line of its own, keeps one its block has, and keeps the block where it is, unless that
     * would mount a folder past `addons` ahead of `addons`: then the block goes before the base game, after every entry
     * that loads `addons`. `Mod` and `Write` defaults go in only where no unconditional entry outside Modlock's block
     * gives them.
     */
    fun withBlock(bytes: ByteArray, lines: HideoutLines, overflow: Int): GameInfoEdit {
        val view = when (val read = read(bytes)) {
            is Parse.Failed -> return GameInfoEdit.Refused(read.problem)
            is Parse.Done -> read.view
        }
        val cleaned = removeRanges(bytes, view.blocks)
        val retained = when (val again = read(cleaned)) {
            is Parse.Failed -> return GameInfoEdit.Refused(again.problem)
            is Parse.Done -> again.view
        }
        val addons = AddonsPath.takeIf { lines.mods && (view.coveredBy != LoadingOwner.AnotherTool || view.hideoutLoads) }
        val past = if (lines.mods) OverflowPaths.take(overflow) else emptyList()
        val paths = (if (lines.customGames) CustomGamesPaths else emptyList()) + listOfNotNull(addons) + past
        if (paths.isEmpty()) return cleaned.asEditFrom(bytes)
        val base = retained.base
        val text = cleaned.latin1()
        val modlock = modlockBlock(text, retained)
        val leaves = retained.leaves.filter { modlock?.contains(it.start) != true }
        val baseLine = text.lastIndexOf('\n', base.key.start - 1) + 1
        val kept = view.blocks.firstOrNull()?.first?.takeIf { at ->
            view.coveredBy == LoadingOwner.AnotherTool && at < view.base.key.start && (addons != null || past.isEmpty() || view.addonsFrom.let { it != null && it < at })
        }
        val lineStart = kept ?: baseLine
        val indent = text.substring(baseLine, base.key.start)
        val separator = text.substring(base.key.end, base.value.start)
        if (!indent.isBlankSpace() || !separator.isBlankSpace() || separator.isEmpty()) return GameInfoEdit.Refused(GameInfoProblem.UnsupportedLayout)
        val lineEnd = text.indexOf('\n', base.value.end)
        val eol = if (lineEnd > 0 && text[lineEnd - 1] == '\r') "\r\n" else "\n"
        fun keyText(name: String) = if (base.key.quoted) "\"$name\"" else name
        fun valueText(path: String) = if (base.value.quoted) "\"$path\"" else path
        fun entryLine(name: String, path: String) = "$indent${keyText(name)}$separator${valueText(path)}$eol"
        val entries = buildString {
            paths.forEach { append(entryLine(GameKey, it)) }
            if (!leaves.hasUnconditionalKey(ModKey)) append(entryLine(ModKey, BasePath))
            if (!leaves.hasUnconditionalKey(WriteKey)) append(entryLine(WriteKey, BasePath))
        }
        val block = "$indent$StartMarker$eol$entries$indent$EndMarker$eol"
        return (text.substring(0, lineStart) + block + text.substring(lineStart)).latin1Bytes().asEditFrom(bytes)
    }

    private fun ByteArray.asEditFrom(original: ByteArray): GameInfoEdit =
        if (contentEquals(original)) GameInfoEdit.Unchanged else GameInfoEdit.Patched(this)

    private fun List<KvLeaf>.hasUnconditionalKey(name: String): Boolean =
        any { leaf -> leaf.key.value.split('+').any { it.trim().equals(name, ignoreCase = true) } }

    private fun modlockBlock(text: String, view: View): IntRange? {
        fun content(line: IntRange) = text.substring(line).trim(' ', '\t', '\r', '\n')
        val lines = text.lineRanges().filter { it.first > view.searchPaths.open && it.last < view.searchPaths.close }
        val begin = lines.singleOrNull { content(it) == ModlockBegin } ?: return null
        val end = lines.singleOrNull { content(it) == ModlockEnd } ?: return null
        val mounted = lines.any { it.first > begin.first && it.first < end.first && isModlockMount(content(it)) }
        return (begin.first..end.last).takeIf { end.first > begin.first && mounted }
    }

    private fun isModlockMount(line: String): Boolean {
        val tokens = entryTokens(line)
        if (tokens.size != 2 || !tokens[0].equals(GameKey, ignoreCase = true)) return false
        val path = tokens[1].normalizedPath()
        return path == ModlockMounts || path.startsWith("$ModlockMounts/")
    }

    /** Which folders load mods today, in the engine's order: what sets found in them are ordered by. */
    fun mounts(bytes: ByteArray): MountsRead = when (val read = read(bytes)) {
        is Parse.Failed -> MountsRead.Problem(read.problem)
        is Parse.Done -> MountsRead.Read(
            read.view.leaves.filter { it.isGame }.takeWhile { it !== read.view.base }.mapNotNull { citadelFolder(it.value.value) },
        )
    }

    /** Every `Game` entry in the engine's order up to the base game: what a bug report shows, and what can load ahead of Hideout's menus. */
    fun searchOrder(bytes: ByteArray): SearchOrderRead = when (val read = read(bytes)) {
        is Parse.Failed -> SearchOrderRead.Problem(read.problem)
        is Parse.Done -> {
            val view = read.view
            val games = view.leaves.filter { it.isGame }.let { games -> games.take(games.indexOfFirst { it === view.base } + 1) }
            val entries = games.map { leaf -> SearchEntry(leaf.value.value, view.blocks.any { leaf.start in it }) }
            val menus = entries.indexOfFirst { it.hideouts && it.path.normalizedPath() == CustomGamesPaths.first() }
            SearchOrderRead.Read(entries, entries.take(menus.coerceAtLeast(0)).map { it.path })
        }
    }

    fun takeOver(bytes: ByteArray, lines: HideoutLines, overflow: Int, roots: Set<ForeignRoot>): GameInfoEdit {
        val view = when (val read = read(bytes)) {
            is Parse.Failed -> return GameInfoEdit.Refused(read.problem)
            is Parse.Done -> read.view
        }
        val text = bytes.latin1()
        val foreign = foreignMounts(view).filter { (_, root) -> root in roots }.map { it.first }
        val entryLines = foreign.map { ownLine(text, it) ?: return GameInfoEdit.Refused(GameInfoProblem.UnsupportedLayout) }
        val dmmTakenOver = roots.containsAll(ForeignRoot.keptBy(Manager.DeadlockModManager))
        val markerLines = if (dmmTakenOver) dmmMarkerLines(text) else emptyList()
        val cleaned = removeRanges(bytes, entryLines + markerLines)
        val loaded = when (val edit = withBlock(cleaned, lines, overflow)) {
            is GameInfoEdit.Patched -> edit.bytes
            GameInfoEdit.Unchanged -> cleaned
            is GameInfoEdit.Refused -> return edit
        }
        return loaded.asEditFrom(bytes)
    }

    fun heldBy(bytes: ByteArray): Set<Manager> {
        val view = (read(bytes) as? Parse.Done)?.view ?: return emptySet()
        val held = foreignMounts(view).map { it.second.managers } + listOfNotNull(setOf(Manager.DeadlockModManager).takeIf { dmmMarkerLines(bytes.latin1()).isNotEmpty() })
        return held.filter { it.size == 1 }.flatten().toSet().ifEmpty { held.flatten().toSet() }
    }

    private fun foreignMounts(view: View): List<Pair<KvLeaf, ForeignRoot>> = view.entries
        .filter { leaf -> leaf.isGame && view.blocks.none { leaf.start in it } }
        .mapNotNull { leaf -> citadelFolder(leaf.value.value)?.let(ForeignRoot::of)?.let { leaf to it } }

    private fun dmmMarkerLines(text: String): List<IntRange> = text.lineRanges().filter { text.substring(it.first, it.last + 1).trim(' ', '\t', '\r', '\n') in DmmMarkers }

    private val KvLeaf.isGame: Boolean get() = key.value.equals(GameKey, ignoreCase = true)

    private fun citadelFolder(value: String): CitadelPath? {
        val path = value.replace('\\', '/').trim().trimEnd('/')
        if (!path.startsWith("$BasePath/", ignoreCase = true)) return null
        return CitadelPath.parse(path.substring(BasePath.length + 1))
    }

    private fun ownLine(text: String, leaf: KvLeaf): IntRange? {
        val start = text.lastIndexOf('\n', leaf.start - 1) + 1
        val newline = text.indexOf('\n', leaf.end)
        val end = if (newline < 0) text.length else newline + 1
        val after = text.substring(leaf.end, if (newline < 0) text.length else newline).trim(' ', '\t', '\r')
        if (!text.substring(start, leaf.start).isBlankSpace() || !(after.isEmpty() || after.startsWith("//"))) return null
        return start until end
    }

    private sealed interface Parse {
        data class Done(val view: View) : Parse

        data class Failed(val problem: GameInfoProblem) : Parse
    }

    /**
     * [entries] are every leaf of SearchPaths; [leaves] the unconditional ones, which are all the engine is sure to read.
     * [addonsFrom] is where the first entry that loads `addons` starts.
     */
    private class View(
        val base: KvLeaf,
        val coveredBy: LoadingOwner?,
        val hideoutLoads: Boolean,
        val customGames: Boolean,
        val overflow: Int,
        val addonsFrom: Int?,
        val blocks: List<IntRange>,
        val leaves: List<KvLeaf>,
        val entries: List<KvLeaf>,
        val searchPaths: KvBlock,
    )

    private fun read(bytes: ByteArray): Parse {
        val text = bytes.latin1()
        val parsed = try {
            KeyValues.parse(text.withoutUtf8Mark(), escapes = false)
        } catch (e: KeyValuesException) {
            return Parse.Failed(GameInfoProblem.Unparseable)
        }
        var level = parsed.nodes
        lateinit var block: KvBlock
        for (key in SearchPathsLocation) {
            val named = level.filter { it.key.value.equals(key, ignoreCase = true) }
            if (named.isEmpty()) return Parse.Failed(GameInfoProblem.NoSearchPaths)
            block = (named.singleOrNull() as? KvBlock)?.takeIf { it.condition == null }
                ?: return Parse.Failed(GameInfoProblem.AmbiguousSearchPaths)
            level = block.children
        }
        val searchPaths = block
        val entries = searchPaths.children.filterIsInstance<KvLeaf>()
        val leaves = entries.filter { it.condition == null }
        val gameLeaves = leaves.filter { it.isGame }
        val base = gameLeaves.firstOrNull { it.value.value.normalizedPath() == BasePath } ?: return Parse.Failed(GameInfoProblem.NoBaseGameEntry)
        val blocks = hideoutBlocks(text, searchPaths.open, searchPaths.close)
        val ahead = gameLeaves.takeWhile { it !== base }
        fun KvLeaf.inBlock() = blocks.any { start in it }
        val covering = ahead.filter { it.value.value.normalizedPath() == AddonsPath }
        val coveredBy = when {
            covering.isEmpty() -> null
            covering.all { it.inBlock() } -> LoadingOwner.Hideout
            else -> LoadingOwner.AnotherTool
        }
        val customGames = CustomGamesPaths.all { path -> ahead.any { it.inBlock() && it.value.value.normalizedPath() == path } }
        val addonsFrom = covering.firstOrNull()?.start
        val overflow = addonsFrom?.let { from -> OverflowPaths.takeWhile { path -> ahead.any { it.inBlock() && it.start > from && it.value.value.normalizedPath() == path } }.size } ?: 0
        return Parse.Done(View(base, coveredBy, covering.any { it.inBlock() }, customGames, overflow, addonsFrom, blocks, leaves, entries, searchPaths))
    }

    private fun hideoutBlocks(text: String, open: Int, close: Int): List<IntRange> {
        val lines = text.lineRanges().filter { it.first > open && it.last < close }
        fun entryAt(at: Int, key: String, path: String) = at < lines.size && isEntry(text.substring(lines[at].first, lines[at].last).trim(' ', '\t', '\r'), key, path)
        fun markerAt(at: Int, marker: String) = at < lines.size && text.substring(lines[at].first, lines[at].last).trim(' ', '\t', '\r') == marker
        val blocks = mutableListOf<IntRange>()
        var i = 0
        while (i < lines.size) {
            if (!markerAt(i, StartMarker)) {
                i++
                continue
            }
            var j = i + 1
            if (CustomGamesPaths.withIndex().all { (k, path) -> entryAt(j + k, GameKey, path) }) j += CustomGamesPaths.size
            if (entryAt(j, GameKey, AddonsPath)) j++
            j += OverflowPaths.withIndex().takeWhile { (k, path) -> entryAt(j + k, GameKey, path) }.size
            if (entryAt(j, ModKey, BasePath)) j++
            if (entryAt(j, WriteKey, BasePath)) j++
            if (markerAt(j, EndMarker)) {
                blocks += lines[i].first..lines[j].last
                i = j + 1
            } else {
                i++
            }
        }
        return blocks
    }

    private fun isEntry(line: String, key: String, path: String): Boolean {
        val tokens = entryTokens(line)
        return tokens.size == 2 && tokens[0].equals(key, ignoreCase = true) && tokens[1].normalizedPath() == path
    }

    private fun entryTokens(line: String): List<String> = line.split(' ', '\t').filter { it.isNotEmpty() }.map { it.removeSurrounding("\"") }

    private fun removeRanges(bytes: ByteArray, ranges: List<IntRange>): ByteArray =
        if (ranges.isEmpty()) bytes else bytes.latin1().splice(ranges.map { it to "" }).latin1Bytes()

    private fun String.normalizedPath(): String = replace('\\', '/').trim().trimEnd('/').lowercase()

    private fun String.isBlankSpace(): Boolean = all { it == ' ' || it == '\t' }

    private fun String.withoutUtf8Mark(): String =
        if (startsWith("ï»¿")) "   " + substring(3) else this
}

/** A config file's bytes as text, one character per byte, so offsets in the text are offsets in the file. */
internal fun ByteArray.latin1(): String = toString(Charsets.ISO_8859_1)

internal fun String.latin1Bytes(): ByteArray = toByteArray(Charsets.ISO_8859_1)

/** The text with each range, none overlapping another, replaced by its text. */
internal fun String.splice(edits: List<Pair<IntRange, String>>): String = buildString {
    var at = 0
    edits.sortedBy { it.first.first }.forEach { (range, replacement) ->
        append(this@splice, at, range.first).append(replacement)
        at = range.last + 1
    }
    append(this@splice, at, this@splice.length)
}

/** Every line of a config file's text, end of line included. */
internal fun String.lineRanges(): List<IntRange> {
    val lines = mutableListOf<IntRange>()
    var start = 0
    while (start < length) {
        val newline = indexOf('\n', start)
        val end = if (newline < 0) length else newline + 1
        lines += start until end
        start = end
    }
    return lines
}
