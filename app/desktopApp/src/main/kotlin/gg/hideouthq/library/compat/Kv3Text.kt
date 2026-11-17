package gg.hideouthq.library.compat

internal object Kv3Text {
    sealed interface Value

    data class Member(val key: String, val keyStart: Int, val keyEnd: Int, val value: Value)

    data class Obj(val members: List<Member>) : Value

    data class Arr(val items: List<Value>) : Value

    data class Str(val flag: String?, val value: String) : Value

    data object Scalar : Value

    data object Blob : Value

    fun parse(text: String): Value {
        val reader = Reader(text)
        reader.header()
        val root = reader.value(0)
        reader.space()
        if (!reader.done) throw Kv3Failure("text after the KV3 root value")
        return root
    }

    private class Reader(val text: String) {
        var at = 0
        val done: Boolean get() = at >= text.length

        fun header() {
            space()
            if (!text.startsWith("<!--", at)) throw Kv3Failure("the KV3 text has no header")
            val end = text.indexOf("-->", at)
            if (end < 0) throw Kv3Failure("the KV3 header is not closed")
            if (!text.substring(at, end).contains("kv3")) throw Kv3Failure("the header is not a KV3 header")
            at = end + 3
        }

        fun space() {
            while (!done) {
                val c = text[at]
                when {
                    c == ' ' || c == '\t' || c == '\r' || c == '\n' -> at++
                    text.startsWith("//", at) -> at = text.indexOf('\n', at).let { if (it < 0) text.length else it + 1 }
                    text.startsWith("/*", at) -> at = text.indexOf("*/", at + 2).let { if (it < 0) throw Kv3Failure("a comment is not closed") else it + 2 }
                    else -> return
                }
            }
        }

        fun value(depth: Int): Value {
            if (depth > MaxDepth) throw Kv3Failure("KV3 text nests too deeply")
            space()
            if (done) throw Kv3Failure("a KV3 value is missing")
            return when (val c = text[at]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> Str(null, string())
                '#' -> blob()
                else -> {
                    val word = word()
                    if (word.isEmpty()) throw Kv3Failure("unexpected '$c' in KV3 text")
                    if (!done && text[at] == ':') {
                        if (!word.all { it.isLetterOrDigit() || it == '_' }) throw Kv3Failure("a KV3 flag is not a word")
                        at++
                        when (val flagged = value(depth + 1)) {
                            is Str -> Str(word, flagged.value)
                            is Obj, is Arr, is Scalar -> flagged
                            is Blob -> throw Kv3Failure("a flagged blob")
                        }
                    } else {
                        if (!Number.matches(word) && word != "true" && word != "false" && word != "null") throw Kv3Failure("\"$word\" is not a KV3 value")
                        Scalar
                    }
                }
            }
        }

        fun obj(depth: Int): Obj {
            at++
            val members = mutableListOf<Member>()
            while (true) {
                space()
                if (done) throw Kv3Failure("an object is not closed")
                if (text[at] == '}') {
                    at++
                    return Obj(members)
                }
                val keyStart = at
                val key = if (text[at] == '"') string() else word().also { if (it.isEmpty() || !Identifier.matches(it)) throw Kv3Failure("\"$it\" is not a key") }
                val keyEnd = at
                space()
                if (done || text[at] != '=') throw Kv3Failure("a key is not followed by =")
                at++
                members += Member(key, keyStart, keyEnd, value(depth + 1))
            }
        }

        fun arr(depth: Int): Arr {
            at++
            val items = mutableListOf<Value>()
            while (true) {
                space()
                if (done) throw Kv3Failure("an array is not closed")
                if (text[at] == ']') {
                    at++
                    return Arr(items)
                }
                items += value(depth + 1)
                space()
                if (done) throw Kv3Failure("an array is not closed")
                when (text[at]) {
                    ',' -> at++
                    ']' -> Unit
                    else -> throw Kv3Failure("array items are not separated by commas")
                }
            }
        }

        fun blob(): Blob {
            if (!text.startsWith("#[", at)) throw Kv3Failure("unexpected '#'")
            val end = text.indexOf(']', at)
            if (end < 0) throw Kv3Failure("a blob is not closed")
            val hex = text.substring(at + 2, end)
            if (!hex.all { it.isWhitespace() || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw Kv3Failure("a blob holds more than hex")
            at = end + 1
            return Blob
        }

        fun word(): String {
            val start = at
            while (!done && (text[at].isLetterOrDigit() || text[at] in "_.+-")) at++
            return text.substring(start, at)
        }

        fun string(): String {
            if (text.startsWith("\"\"\"", at)) {
                val end = text.indexOf("\"\"\"", at + 3)
                if (end < 0) throw Kv3Failure("a multi-line string is not closed")
                return text.substring(at + 3, end).also { at = end + 3 }
            }
            at++
            val out = StringBuilder()
            while (true) {
                if (done) throw Kv3Failure("a string is not closed")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\n' -> throw Kv3Failure("a string runs past its line")
                    '\\' -> {
                        if (done) throw Kv3Failure("a string ends in an escape")
                        out.append(
                            when (val e = text[at++]) {
                                'n' -> '\n'
                                't' -> '\t'
                                'r' -> '\r'
                                '\\' -> '\\'
                                '"' -> '"'
                                '\'' -> '\''
                                '?' -> '?'
                                else -> throw Kv3Failure("unknown escape \\$e")
                            },
                        )
                    }
                    else -> out.append(c)
                }
            }
        }

        companion object {
            const val MaxDepth = 64
            val Number = Regex("""[-+]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][-+]?[0-9]+)?""")
            val Identifier = Regex("""[A-Za-z_][A-Za-z0-9_.]*""")
        }
    }
}
