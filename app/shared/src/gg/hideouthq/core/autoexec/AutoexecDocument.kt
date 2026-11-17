package gg.hideouthq.core.autoexec

import gg.hideouthq.core.crosshair.statements
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.latin1
import gg.hideouthq.core.library.latin1Bytes

/**
 * `game/citadel/cfg/autoexec.cfg`, whole and byte-exact. Bytes are read as ISO-8859-1, one char per byte, so [render]
 * of a document nothing changed gives back the bytes [parse] read, each line's own ending, a missing last newline and a
 * leading UTF-8 byte order mark included; the mark stays before every line, the header too. Text is shown and edited
 * as UTF-8, or one char per byte for a line that is not UTF-8, and only lines an edit touches are written anew. The
 * file is free lines (the header, blank lines, comments, commands on or off, lines Hideout does not read) and other
 * managers' marked sections. Immutable: every [AutoexecEdit] gives a new document.
 */
class AutoexecDocument internal constructor(internal val parts: List<Part>, internal val eol: LineEnd, private val bom: Boolean) {
    val commands: List<CommandRow> by lazy {
        val seen = mutableMapOf<String, Int>()
        parts.mapNotNull { (it as? Part.Free)?.command }.map { command ->
            val occurrence = seen.getOrElse(command.name.lowercase()) { 0 }.also { seen[command.name.lowercase()] = it + 1 }
            CommandRow(CommandRef(command.name, occurrence, command.text), command.shown, command.on, canTurnOff = classify(command.turned(on = false).text) is FreeKind.Command)
        }
    }

    val editorText: EditorText by lazy {
        var at = 0
        val locked = mutableListOf<LockedLines>()
        parts.forEachIndexed { index, part ->
            val owner = when {
                part is Part.Foreign -> LockOwner.Tool(part.markers)
                index == 0 && part.isHeader -> LockOwner.Header
                else -> null
            }
            owner?.let { locked += LockedLines(at until at + part.lines.size, it) }
            at += part.lines.size
        }
        EditorText(parts.flatMap { it.lines }.map { it.text }, locked)
    }

    val commandCount: Int by lazy {
        parts.sumOf { part ->
            when (part) {
                is Part.Free -> if (part.command != null) 1 else 0
                else -> part.body.count { statements(it.text)?.isNotEmpty() == true }
            }
        }
    }

    fun foreignLines(sections: List<SectionMarkers>): AutoexecLines {
        val found = parts.filterIsInstance<Part.Foreign>().filter { it.markers in sections }
        fun linesOf(kind: SectionKind) = found.filter { it.markers.kind == kind }.flatMap { it.body }.map { it.text }.filter { it.isNotBlank() }
        return AutoexecLines(linesOf(SectionKind.Crosshair), linesOf(SectionKind.Commands))
    }

    fun render(): ByteArray = parts.flatMap { it.lines }.joinToString("", prefix = if (bom) Bom else "") { it.bytes + it.end.text }.latin1Bytes()

    /** What Hideout writes: [render] with [Header] put first when it is not, so autoexec.cfg is never written empty, which can crash the game. */
    fun writable(): ByteArray = (if (parts.firstOrNull()?.isHeader == true) this else withParts(listOf(line(Header)) + parts)).render()

    override fun equals(other: Any?): Boolean = other is AutoexecDocument && parts == other.parts && eol == other.eol && bom == other.bom

    override fun hashCode(): Int = parts.hashCode()

    internal fun withParts(parts: List<Part>) = AutoexecDocument(parts, eol, bom)

    internal fun line(text: String) = Part.Free(Line.of(text, eol))

    internal fun indexOf(ref: CommandRef): Int? {
        val named = parts.indices.filter { (parts[it] as? Part.Free)?.command?.name.equals(ref.name, ignoreCase = true) }
        fun reads(index: Int) = (parts[index] as Part.Free).line.text == ref.text
        return named.getOrNull(ref.occurrence)?.takeIf(::reads) ?: named.firstOrNull(::reads)
    }

    internal fun appended(added: List<Part>): AutoexecDocument {
        val last = parts.lastOrNull()?.takeIf { it.lines.last().end == LineEnd.None } ?: return withParts(parts + added)
        return withParts(parts.dropLast(1) + last.ending(eol) + added.dropLast(1) + added.last().ending(LineEnd.None))
    }

    internal fun inserted(at: Int, added: List<Part>): AutoexecDocument =
        if (at >= parts.size) appended(added) else withParts(parts.take(at) + added + parts.drop(at))

    internal fun unwrapped(taken: (Part.Foreign) -> Boolean): AutoexecDocument =
        withParts(parts.flatMap { part -> if (part is Part.Foreign && taken(part)) part.body.map { Part.Free(it) } else listOf(part) })

    internal fun withLines(texts: List<String>): AutoexecDocument = texts.filter { it.isNotBlank() }.fold(this) { document, text ->
        if (document.parts.any { it is Part.Free && it.line.text.trim() == text.trim() }) document else document.inserted(document.commandsEnd(), listOf(document.line(text)))
    }

    internal fun commandsEnd(): Int =
        parts.indexOfLast { (it as? Part.Free)?.command != null }.takeIf { it >= 0 }?.plus(1) ?: if (parts.firstOrNull()?.isHeader == true) 1 else 0

    companion object {
        const val Header = "// Written by Hideout. Lines you add by hand are kept."

        /**
         * [bytes] (null: no file) as a document. A section runs from its start line to the first end line after it, each
         * matched as a whole line trimmed of spaces and tabs; a start with no end is a plain line, so nothing past it is
         * ever claimed. New lines end as the file's first line does, `\n` for a file with none.
         */
        fun parse(bytes: ByteArray?): AutoexecDocument {
            val text = bytes?.latin1().orEmpty()
            val lines = split(text.removePrefix(Bom))
            val parts = mutableListOf<Part>()
            var at = 0
            while (at < lines.size) {
                val start = lines[at].bytes.trim(' ', '\t')
                val foreign = SectionMarkers.Known.firstOrNull { it.start == start }
                val close = foreign?.let { (at + 1 until lines.size).firstOrNull { lines[it].bytes.trim(' ', '\t') == foreign.end } }
                if (foreign == null || close == null) {
                    parts += Part.Free(lines[at++])
                    continue
                }
                parts += Part.Foreign(lines.subList(at, close + 1).toList(), foreign)
                at = close + 1
            }
            return AutoexecDocument(parts, lines.firstOrNull { it.end != LineEnd.None }?.end ?: LineEnd.Lf, bom = text.startsWith(Bom))
        }

        private const val Bom = "\u00EF\u00BB\u00BF"

        private fun split(text: String): List<Line> {
            val lines = mutableListOf<Line>()
            var start = 0
            while (start < text.length) {
                val at = text.indexOfAny(charArrayOf('\n', '\r'), start)
                if (at < 0) {
                    lines += Line(text.substring(start), LineEnd.None)
                    break
                }
                val end = when {
                    text[at] == '\n' -> LineEnd.Lf
                    text.startsWith("\r\n", at) -> LineEnd.CrLf
                    else -> LineEnd.Cr
                }
                lines += Line(text.substring(start, at), end)
                start = at + end.text.length
            }
            return lines
        }
    }
}

/**
 * A command as a List row names it: the [occurrence]th line, on or off, named [name], which read [text] when the row
 * was shown, so an edit finds that line again in a file that changed meanwhile.
 */
data class CommandRef(val name: String, val occurrence: Int, val text: String)

/**
 * One row of the List view. [value] is as written, one pair of quotes around all of it aside. [canTurnOff] is false
 * for a command whose commented-out line would read back as a plain comment, such as `bind F5 kill`.
 */
data class CommandRow(val ref: CommandRef, val value: String, val on: Boolean, val canTurnOff: Boolean)

/** The Text view: the file's lines, and the 0-based line ranges the editor keeps as they are. */
data class EditorText(val lines: List<String>, val locked: List<LockedLines>) {
    val text: String get() = lines.joinToString("\n")
}

data class LockedLines(val lines: IntRange, val owner: LockOwner)

sealed interface LockOwner {
    data object Header : LockOwner

    data class Tool(val markers: SectionMarkers) : LockOwner
}

enum class SectionKind { Crosshair, Commands }

data class SectionMarkers(val start: String, val end: String, val kind: SectionKind, val manager: Manager) {
    companion object {
        val DmmCrosshair = SectionMarkers("// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===", "// === End Crosshair Settings ===", SectionKind.Crosshair, Manager.DeadlockModManager)
        val DmmMap = SectionMarkers("// === Deadlock Mod Manager - Map Command (DO NOT EDIT) ===", "// === End Map Command ===", SectionKind.Commands, Manager.DeadlockModManager)
        val GrimoireCommands = SectionMarkers("// === AUTOEXEC COMMANDS (Mod Manager) ===", "// === END COMMANDS ===", SectionKind.Commands, Manager.Grimoire)
        val GrimoireCrosshair = SectionMarkers("// === CROSSHAIR SETTINGS (Mod Manager) ===", "// === END CROSSHAIR ===", SectionKind.Crosshair, Manager.Grimoire)

        val Known: List<SectionMarkers> = listOf(DmmCrosshair, DmmMap, GrimoireCommands, GrimoireCrosshair)
    }
}

data class AutoexecLines(val crosshair: List<String>, val commands: List<String>)

internal data class Line(val bytes: String, val end: LineEnd) {
    val text: String get() = utf8() ?: bytes

    fun edited(text: String, end: LineEnd = this.end): Line = if (utf8() == null && text.all { it.code <= 0xFF }) Line(text, end) else of(text, end)

    private fun utf8(): String? = try {
        bytes.latin1Bytes().decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        null
    }

    companion object {
        fun of(text: String, end: LineEnd) = Line(text.encodeToByteArray().latin1(), end)
    }
}

internal enum class LineEnd(val text: String) { Lf("\n"), CrLf("\r\n"), Cr("\r"), None("") }

internal sealed interface Part {
    val lines: List<Line>

    val body: List<Line> get() = lines.drop(1).dropLast(1)

    val isHeader: Boolean get() = this is Free && kind == FreeKind.Header

    fun ending(end: LineEnd): Part

    data class Free(val line: Line) : Part {
        val kind: FreeKind = classify(line.text)
        val command: CommandText? get() = (kind as? FreeKind.Command)?.text
        override val lines: List<Line> get() = listOf(line)
        override fun ending(end: LineEnd) = Free(line.copy(end = end))
    }

    data class Foreign(override val lines: List<Line>, val markers: SectionMarkers) : Part {
        override fun ending(end: LineEnd) = Foreign(lines.dropLast(1) + lines.last().copy(end = end), markers)
    }
}

internal sealed interface FreeKind {
    data object Header : FreeKind
    data object Blank : FreeKind
    data object Comment : FreeKind

    data object Unparsed : FreeKind

    data class Command(val text: CommandText) : FreeKind
}

internal fun classify(text: String): FreeKind {
    if (text.isBlank()) return FreeKind.Blank
    if (text.trim() == AutoexecDocument.Header) return FreeKind.Header
    val command = CommandText.parse(text) ?: return if (text.trimStart().startsWith("//")) FreeKind.Comment else FreeKind.Unparsed
    val off = !command.on && !(command.oneValue && (CommandLibrary.has(command.name) || (OffName.matches(command.name) && '_' in command.name)))
    return if (off) FreeKind.Comment else FreeKind.Command(command)
}

private val OffName = Regex("[a-z][a-z0-9_]*")

internal data class CommandText(val indent: String, val marker: String, val name: String, val separator: String, val value: String, val tail: String) {
    val on: Boolean get() = marker.isEmpty()
    val text: String get() = indent + marker + name + separator + value + tail
    private val quoted: Boolean get() = value.length >= 2 && value.first() == '"' && value.last() == '"' && value.count { it == '"' } == 2
    val oneValue: Boolean get() = quoted || (value.isNotEmpty() && value.none { it.isWhitespace() || it == '"' })

    val shown: String get() = if (quoted) value.substring(1, value.length - 1) else value

    fun valued(shown: String): CommandText = written(if (quoted && shown.isNotEmpty()) "\"$shown\"" else shown.trim())

    fun written(value: String): CommandText = if (value.isEmpty()) copy(separator = "", value = "") else copy(separator = separator.ifEmpty { " " }, value = value)

    fun turned(on: Boolean): CommandText = when {
        on -> copy(marker = "")
        this.on -> copy(marker = "// ")
        else -> this
    }

    companion object {
        fun parse(text: String): CommandText? {
            val start = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
            var at = start
            if (text.startsWith("//", at)) {
                at += 2
                while (at < text.length && text[at].isWhitespace()) at++
            }
            val nameStart = at
            if (at >= text.length || !(text[at].isLetter() || text[at] in "_+-")) return null
            while (at < text.length && (text[at].isLetterOrDigit() || text[at] in "_.+-")) at++
            if (at < text.length && !text[at].isWhitespace()) return null
            val rest = text.substring(at)
            var quoted = false
            var cut = rest.length
            for (index in rest.indices) {
                when {
                    rest[index] == '"' -> quoted = !quoted
                    quoted -> Unit
                    rest.startsWith("//", index) -> {
                        cut = index
                        break
                    }
                    rest[index] == ';' && nameStart == start -> return null
                }
            }
            val body = rest.substring(0, cut)
            val value = body.trim()
            val separator = body.substring(0, body.length - body.trimStart().length)
            return CommandText(text.substring(0, start), text.substring(start, nameStart), text.substring(nameStart, at), separator, value, body.substring(separator.length + value.length) + rest.substring(cut))
        }
    }
}
