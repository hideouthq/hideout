package gg.hideouthq.core.data.live

internal object HtmlText {
    private val LineBreak = Regex("(?i)<br\\s*/?>")
    private val Block = Regex("(?i)</?(p|div|h[1-6]|ul|ol|blockquote|pre|table|tr)(\\s[^>]*)?>")
    private val ListItem = Regex("(?i)<li[^>]*>")
    private val Tag = Regex("<[^>]*>")
    private val NumericEntity = Regex("&#(x[0-9a-fA-F]+|[0-9]+);")
    private val NamedEntity = Regex("&([a-zA-Z]+);")
    private val Spaces = Regex("[ \\t\\u00A0]+")
    private val ExtraBlankLines = Regex("\\n{3,}")

    private val Named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    )

    fun toPlainText(html: String): String {
        val text = html
            .replace(LineBreak, "\n")
            .replace(Block, "\n")
            .replace(ListItem, "\n• ")
            .replace(Tag, "")
        return decodeEntities(text)
            .lines()
            .joinToString("\n") { it.replace(Spaces, " ").trim() }
            .replace(ExtraBlankLines, "\n\n")
            .trim()
    }

    fun decodeEntities(text: String): String = text
        .replace(NumericEntity) { match ->
            val code = match.groupValues[1]
            val value = if (code.startsWith('x')) code.drop(1).toIntOrNull(16) else code.toIntOrNull()
            value?.takeIf { it in 1..0x10FFFF }?.let(::codePointToString) ?: match.value
        }
        .replace(NamedEntity) { match -> Named[match.groupValues[1].lowercase()] ?: match.value }

    private fun codePointToString(codePoint: Int): String =
        if (codePoint < 0x10000) {
            codePoint.toChar().toString()
        } else {
            val offset = codePoint - 0x10000
            charArrayOf(((offset shr 10) + 0xD800).toChar(), ((offset and 0x3FF) + 0xDC00).toChar()).concatToString()
        }
}
