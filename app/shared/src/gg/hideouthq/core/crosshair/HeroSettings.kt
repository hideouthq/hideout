package gg.hideouthq.core.crosshair

import gg.hideouthq.core.library.latin1
import gg.hideouthq.core.library.latin1Bytes

/**
 * `cfg/citadel_hero_settings.lst`, where Deadlock saves its reticle, whole and byte-exact. It is kv3 text: a root
 * object whose `base` object holds the crosshair Settings › Reticle shows, beside the mouse settings, `heroes`' per-hero
 * overrides and whatever else the game keeps. The game reads it before autoexec.cfg runs and applies it over any
 * crosshair console variable.
 *
 * [crosshair] is the `base` block's twenty crosshair settings on the game's defaults. [with] changes only the values
 * that differ, written as the game writes them, quoted; a setting the block lacks goes in at its end, indented and
 * ended as its other lines. Every other byte stays: `crosshair_themed`, the mouse settings, `heroes`, comments, key
 * order, indentation and line endings. Bytes are read as ISO-8859-1, one char per byte, so nothing is decoded and
 * written back differently.
 */
class HeroSettings private constructor(private val text: String, private val base: KvObject) {
    val crosshair: Crosshair by lazy {
        CrosshairSetting.entries.fold(Crosshair.GameDefault) { crosshair, setting ->
            (base.entries.lastOrNull { it.key == setting.key }?.value as? KvScalar)?.let { setting.read(crosshair, it.text) } ?: crosshair
        }
    }

    /** These settings holding [crosshair]; the same bytes when they hold it already. */
    fun with(crosshair: Crosshair): HeroSettings {
        val edits = mutableListOf<Edit>()
        val missing = mutableListOf<CrosshairSetting>()
        for (setting in CrosshairSetting.entries) {
            val wanted = setting.format(crosshair)
            val found = base.entries.filter { it.key == setting.key }
            if (found.isEmpty()) missing += setting
            for (entry in found) {
                val now = (entry.value as? KvScalar)?.let { setting.read(Crosshair.GameDefault, it.text) }?.let(setting::format)
                if (now != wanted) edits += Edit(entry.value.start, entry.value.end, "\"$wanted\"")
            }
        }
        if (missing.isNotEmpty()) edits += added(missing.map { "${it.key} = \"${it.format(crosshair)}\"" })
        if (edits.isEmpty()) return this
        val next = edits.sortedByDescending { it.start }.fold(StringBuilder(text)) { built, edit -> built.replace(edit.start, edit.end, edit.text) }
        return checkNotNull(read(next.toString())) { "a change of values keeps the settings readable" }
    }

    fun render(): ByteArray = text.latin1Bytes()

    override fun equals(other: Any?): Boolean = other is HeroSettings && text == other.text

    override fun hashCode(): Int = text.hashCode()

    private fun added(lines: List<String>): Edit {
        val close = base.end - 1
        val lineStart = text.lastIndexOfAny(charArrayOf('\n', '\r'), close) + 1
        val closeIndent = text.substring(lineStart, close).takeIf { it.isBlank() }
        val indent = base.entries.firstOrNull()?.let { first -> text.substring(text.lastIndexOfAny(charArrayOf('\n', '\r'), first.start) + 1, first.start) }
            ?.takeIf { it.isBlank() } ?: "${closeIndent.orEmpty()}\t"
        val eol = if ("\r\n" in text) "\r\n" else "\n"
        val body = lines.joinToString("") { "$indent$it$eol" }
        return if (closeIndent != null) Edit(lineStart, lineStart, body) else Edit(close, close, "$eol$body")
    }

    private class Edit(val start: Int, val end: Int, val text: String)

    companion object {
        /** The game's settings in [bytes]; null when they are not kv3 text with a `base` object, so Hideout never writes over them. */
        fun parse(bytes: ByteArray): HeroSettings? = read(bytes.latin1())

        val New: HeroSettings = checkNotNull(
            read(
                "<!-- kv3 encoding:text:version{e21c7f3c-8a33-41c5-9977-a76d3a32aa0d} format:generic:version{7412167c-06e9-4698-aff2-e63eb59037e7} -->\n" +
                    "{\n\tbase = \n\t{\n" +
                    CrosshairSetting.entries.joinToString("") { "\t\t${it.key} = \"${it.format(Crosshair.GameDefault)}\"\n" } +
                    "\t}\n\theroes = \n\t{\n\t}\n}",
            ),
        )

        private fun read(text: String): HeroSettings? {
            val root = Kv3(text).document() as? KvObject ?: return null
            val base = root.entries.lastOrNull { it.key == "base" }?.value as? KvObject ?: return null
            return HeroSettings(text, base)
        }
    }
}

private sealed interface KvValue {
    val start: Int
    val end: Int
}

private class KvObject(override val start: Int, override val end: Int, val entries: List<KvEntry>) : KvValue

private class KvEntry(val key: String, val start: Int, val value: KvValue)

private class KvArray(override val start: Int, override val end: Int) : KvValue

private class KvScalar(override val start: Int, override val end: Int, val text: String) : KvValue

private class Kv3(private val text: String) {
    private var at = 0

    fun document(): KvValue? {
        skip()
        val root = value() ?: return null
        skip()
        return root.takeIf { at == text.length }
    }

    private fun value(): KvValue? {
        val start = at
        return when (text.getOrNull(at)) {
            null -> null
            '{' -> {
                at++
                val entries = mutableListOf<KvEntry>()
                while (true) {
                    skip()
                    if (text.getOrNull(at) == '}') break
                    val keyAt = at
                    val key = (if (text.getOrNull(at) == '"') string() else bare()) ?: return null
                    skip()
                    if (text.getOrNull(at) != '=') return null
                    at++
                    skip()
                    entries += KvEntry(key, keyAt, value() ?: return null)
                    skip()
                    if (text.getOrNull(at) == ',') at++
                }
                at++
                KvObject(start, at, entries)
            }
            '[' -> {
                at++
                while (true) {
                    skip()
                    if (text.getOrNull(at) == ']') break
                    value() ?: return null
                    skip()
                    if (text.getOrNull(at) == ',') at++
                }
                at++
                KvArray(start, at)
            }
            '"' -> string()?.let { KvScalar(start, at, it) }
            else -> {
                val word = bare() ?: return null
                // A flagged value, such as resource:"path", is one value.
                if (word.endsWith(':') && text.getOrNull(at) == '"') string() ?: return null
                KvScalar(start, at, word)
            }
        }
    }

    private fun string(): String? {
        if (text.startsWith("\"\"\"", at)) {
            val close = text.indexOf("\"\"\"", at + 3).takeIf { it >= 0 } ?: return null
            return text.substring(at + 3, close).also { at = close + 3 }
        }
        val built = StringBuilder()
        at++
        while (at < text.length) {
            when (val char = text[at++]) {
                '"' -> return built.toString()
                '\\' -> built.append(text.getOrNull(at++) ?: return null)
                else -> built.append(char)
            }
        }
        return null
    }

    private fun bare(): String? {
        val start = at
        while (at < text.length && !text[at].isWhitespace() && text[at] !in "{}[]=,\"") at++
        return text.substring(start, at).takeIf { it.isNotEmpty() }
    }

    private fun skip() {
        while (at < text.length) {
            at = when {
                text[at].isWhitespace() -> at + 1
                text.startsWith("//", at) -> text.indexOfAny(charArrayOf('\n', '\r'), at).takeIf { it >= 0 } ?: text.length
                text.startsWith("/*", at) -> text.indexOf("*/", at + 2).takeIf { it >= 0 }?.plus(2) ?: return
                text.startsWith("<!--", at) -> text.indexOf("-->", at + 4).takeIf { it >= 0 }?.plus(3) ?: return
                else -> return
            }
        }
    }
}
