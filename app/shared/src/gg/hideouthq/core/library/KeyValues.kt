package gg.hideouthq.core.library

/**
 * Valve's KeyValues text format, as in `libraryfolders.vdf`, `appmanifest_*.acf` and `gameinfo.gi`, parsed without losing
 * where anything came from. Every token keeps its offsets in the parsed text, so an edit can splice lines in and leave
 * every other character as it was.
 *
 * Keys and values are quoted or bare. A bare token ends at ASCII whitespace, a quote, a brace or a bracket. `//` starts
 * a comment outside quotes. A `[$CONDITION]` may follow a value, or a key before its block. Key lookups ignore case, as
 * Steam and the engine do. Text decoded as ISO-8859-1 makes every offset a byte offset, whatever encoding the comments use.
 */
class KeyValues private constructor(val nodes: List<KvNode>) {
    /** The first top-level block named [key]. */
    fun block(key: String): KvBlock? = nodes.firstBlock(key)

    companion object {
        /**
         * Parses [text]. With [escapes], `\"`, `\\`, `\n` and `\t` inside quotes are unescaped (Steam's own files); without,
         * a backslash is an ordinary character (the engine's gameinfo.gi). Throws [KeyValuesException] on malformed input.
         */
        fun parse(text: String, escapes: Boolean): KeyValues = KeyValues(Parser(text, escapes).document())
    }
}

/** A key, value or condition: what it says, and where it sits in the text, quotes included ([start] inclusive, [end] exclusive). */
data class KvToken(val value: String, val start: Int, val end: Int, val quoted: Boolean)

sealed interface KvNode {
    val key: KvToken
    val condition: KvToken?

    /** Where the node starts: its key. */
    val start: Int get() = key.start

    /** Just past the node's value, closing brace or trailing condition. */
    val end: Int
}

data class KvLeaf(override val key: KvToken, val value: KvToken, override val condition: KvToken? = null) : KvNode {
    override val end: Int get() = maxOf(value.end, condition?.end ?: 0)
}

/** A key with braces: [open] and [close] are the offsets of `{` and `}`. */
data class KvBlock(
    override val key: KvToken,
    override val condition: KvToken?,
    val open: Int,
    val close: Int,
    val children: List<KvNode>,
) : KvNode {
    override val end: Int get() = close + 1

    fun block(key: String): KvBlock? = children.firstBlock(key)

    /** The value of the first unconditional leaf named [key]. */
    fun string(key: String): String? =
        children.filterIsInstance<KvLeaf>().firstOrNull { it.condition == null && it.key.value.equals(key, ignoreCase = true) }?.value?.value
}

class KeyValuesException(message: String) : Exception(message)

private fun List<KvNode>.firstBlock(key: String): KvBlock? =
    filterIsInstance<KvBlock>().firstOrNull { it.key.value.equals(key, ignoreCase = true) }

/** Deeper than any Valve file nests: refused before the parser's recursion can overflow the stack. */
private const val MaxDepth = 32

private class Parser(private val text: String, private val escapes: Boolean) {
    private var pos = 0

    fun document(): List<KvNode> = nodes(depth = 0, inBlock = false)

    private fun nodes(depth: Int, inBlock: Boolean): List<KvNode> {
        val nodes = mutableListOf<KvNode>()
        while (true) {
            skipSpaceAndComments()
            if (pos >= text.length) {
                if (inBlock) fail("A block is missing its closing brace")
                return nodes
            }
            when (text[pos]) {
                '}' -> if (inBlock) return nodes else fail("A closing brace has no block to close")
                '{' -> fail("A block has no key")
                '[', ']' -> fail("A condition has no key")
            }
            nodes += node(depth)
        }
    }

    private fun node(depth: Int): KvNode {
        val key = token()
        skipSpaceAndComments()
        val keyCondition = if (peek() == '[') condition().also { skipSpaceAndComments() } else null
        return when (peek()) {
            null -> fail("\"${key.value}\" has no value")
            '{' -> {
                if (depth + 1 > MaxDepth) fail("Blocks are nested more than $MaxDepth deep")
                val open = pos++
                val children = nodes(depth + 1, inBlock = true)
                val close = pos++
                KvBlock(key, keyCondition, open, close, children)
            }
            '}', '[', ']' -> fail("\"${key.value}\" has no value")
            else -> {
                val value = token()
                val valueEnd = pos
                skipSpaceAndComments()
                val valueCondition = if (peek() == '[') {
                    condition()
                } else {
                    pos = valueEnd
                    null
                }
                KvLeaf(key, value, valueCondition ?: keyCondition)
            }
        }
    }

    private fun token(): KvToken = if (text[pos] == '"') quoted() else bare()

    private fun quoted(): KvToken {
        val start = pos++
        val value = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("A quoted string is not closed")
            val c = text[pos]
            if (c == '"') {
                pos++
                return KvToken(value.toString(), start, pos, quoted = true)
            }
            if (escapes && c == '\\' && pos + 1 < text.length) {
                when (val next = text[pos + 1]) {
                    'n' -> value.append('\n')
                    't' -> value.append('\t')
                    '\\', '"' -> value.append(next)
                    else -> value.append(c).append(next)
                }
                pos += 2
            } else {
                value.append(c)
                pos++
            }
        }
    }

    private fun bare(): KvToken {
        val start = pos
        while (pos < text.length && !endsBareToken(text[pos])) pos++
        if (pos == start) fail("Unexpected '${text[pos]}'")
        return KvToken(text.substring(start, pos), start, pos, quoted = false)
    }

    private fun condition(): KvToken {
        val start = pos++
        while (pos < text.length && text[pos] != ']' && text[pos] != '\n') pos++
        if (peek() != ']') fail("A condition is not closed")
        pos++
        return KvToken(text.substring(start + 1, pos - 1).trim(), start, pos, quoted = false)
    }

    private fun skipSpaceAndComments() {
        while (pos < text.length) {
            val c = text[pos]
            when {
                isSpace(c) -> pos++
                c == '/' && pos + 1 < text.length && text[pos + 1] == '/' -> {
                    while (pos < text.length && text[pos] != '\n') pos++
                }
                else -> return
            }
        }
    }

    private fun peek(): Char? = if (pos < text.length) text[pos] else null

    private fun endsBareToken(c: Char) = isSpace(c) || c == '"' || c == '{' || c == '}' || c == '[' || c == ']'

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

    private fun fail(message: String): Nothing = throw KeyValuesException(message)
}
