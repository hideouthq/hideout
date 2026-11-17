package gg.hideouthq.core.customgames

/** [name] is null when Steam keeps no usable persona name. */
data class LocalPlayer(val steamId: String, val name: DisplayName?) {
    override fun toString() = "LocalPlayer(redacted)"
}

/** A Steam name, which a friend reads in an invite. */
@JvmInline
value class DisplayName private constructor(val value: String) {
    override fun toString() = "DisplayName(redacted)"

    companion object {
        const val MaxLength = 32

        fun parse(text: String?): DisplayName? {
            val trimmed = text?.trim() ?: return null
            val fits = trimmed.isNotEmpty() && trimmed.codePointCount(0, trimmed.length) <= MaxLength && trimmed.none(Char::isISOControl)
            return if (fits) DisplayName(trimmed) else null
        }
    }
}
