package gg.hideouthq.core.crosshair

/**
 * The twenty settings a [Crosshair] is, in the order the game saves them: each the key `crosshair_<suffix>` in the
 * `base` block of `citadel_hero_settings.lst`, and the console variable `citadel_crosshair_<suffix>`. The one list the
 * saved settings, the line parser, share codes and `crosshairs.json` follow. Never `pip_border`, which the game does not
 * have.
 */
enum class CrosshairSetting(val suffix: String, private val field: Field) {
    GapStatic("pip_gap_static", Switch({ it.fixedGap }, { c, on -> c.copy(fixedGap = on) })),
    GapCollapse("pip_gap_collapse", Switch({ it.collapse }, { c, on -> c.copy(collapse = on) })),
    Width("pip_width", Number(Adjustable.Thickness)),
    Height("pip_height", Number(Adjustable.Length)),
    Gap("pip_gap", Number(Adjustable.Gap)),
    Opacity("pip_opacity", Number(Adjustable.Opacity)),
    OutlineBorder("pip_outline_border", Number(Adjustable.LineBorder)),
    OutlineGap("pip_outline_gap", Number(Adjustable.LineBorderGap)),
    OutlineOpacity("pip_outline_opacity", Number(Adjustable.LineBorderOpacity)),
    DotSize("dot_size", Number(Adjustable.DotSize)),
    DotOpacity("dot_opacity", Number(Adjustable.DotOpacity)),
    DotOutlineBorder("dot_outline_border", Number(Adjustable.DotBorder)),
    DotOutlineGap("dot_outline_gap", Number(Adjustable.DotBorderGap)),
    DotOutlineOpacity("dot_outline_opacity", Number(Adjustable.DotBorderOpacity)),
    ColorR("color_r", Channel({ it.color.r }, { c, v -> c.copy(color = c.color.copy(r = v)) })),
    ColorG("color_g", Channel({ it.color.g }, { c, v -> c.copy(color = c.color.copy(g = v)) })),
    ColorB("color_b", Channel({ it.color.b }, { c, v -> c.copy(color = c.color.copy(b = v)) })),
    OutlineColorR("outline_color_r", Channel({ it.borderColor.r }, { c, v -> c.copy(borderColor = c.borderColor.copy(r = v)) })),
    OutlineColorG("outline_color_g", Channel({ it.borderColor.g }, { c, v -> c.copy(borderColor = c.borderColor.copy(g = v)) })),
    OutlineColorB("outline_color_b", Channel({ it.borderColor.b }, { c, v -> c.copy(borderColor = c.borderColor.copy(b = v)) }));

    val key: String get() = "crosshair_$suffix"

    val convar: String get() = "citadel_$key"

    fun format(crosshair: Crosshair): String = field.format(crosshair)

    /** [crosshair] with this setting set from [value], quotes already taken off; null when [value] is not one of its values. */
    fun read(crosshair: Crosshair, value: String): Crosshair? = field.read(crosshair, value)

    companion object {
        fun convar(name: String): CrosshairSetting? = entries.firstOrNull { it.convar.equals(name, ignoreCase = true) }
    }
}

private sealed interface Field {
    fun format(crosshair: Crosshair): String
    fun read(crosshair: Crosshair, value: String): Crosshair?
}

private class Number(private val slider: Adjustable) : Field {
    override fun format(crosshair: Crosshair) = slider.get(crosshair).toString()
    override fun read(crosshair: Crosshair, value: String) = ConvarNumber.parse(value)?.let { slider.put(crosshair, it) }
}

private class Switch(private val get: (Crosshair) -> Boolean, private val put: (Crosshair, Boolean) -> Crosshair) : Field {
    override fun format(crosshair: Crosshair) = get(crosshair).toString()
    override fun read(crosshair: Crosshair, value: String) = flag(value)?.let { put(crosshair, it) }
}

private class Channel(private val get: (Crosshair) -> Int, private val put: (Crosshair, Int) -> Crosshair) : Field {
    override fun format(crosshair: Crosshair) = get(crosshair).toString()
    override fun read(crosshair: Crosshair, value: String) = ConvarNumber.parse(value)?.takeIf { it.isWhole }?.whole?.takeIf { it in 0..255 }?.let { put(crosshair, it) }
}

internal fun Crosshair.settings(): Map<String, String> = CrosshairSetting.entries.associate { it.suffix to it.format(this) }

internal fun crosshairOf(settings: Map<String, String>): ReadCrosshair {
    val set = mutableSetOf<CrosshairSetting>()
    val crosshair = CrosshairSetting.entries.fold(Crosshair.GameDefault) { crosshair, setting ->
        settings[setting.suffix]?.let { setting.read(crosshair, it) }?.also { set += setting } ?: crosshair
    }
    return ReadCrosshair(crosshair, set)
}

data class ReadCrosshair(val crosshair: Crosshair, val set: Set<CrosshairSetting>)

object CrosshairLines {
    private const val PipBorder = "citadel_crosshair_pip_border"

    /**
     * [lines], each statement `name value`, quoted or not, several on a line split by `;`, applied in order on the
     * game's defaults as the console runs them. A line counts only when every statement on it is one of the twenty
     * with a value it takes, or `pip_border`, which sets a border of 1 or none only when `pip_outline_border` is not set.
     */
    fun read(lines: List<String>): ReadCrosshair {
        var crosshair = Crosshair.GameDefault
        val set = mutableSetOf<CrosshairSetting>()
        var border: Boolean? = null
        for (line in lines) {
            for ((name, value) in settings(line) ?: continue) {
                val setting = CrosshairSetting.convar(name)
                if (setting == null) {
                    border = flag(value)
                } else {
                    crosshair = checkNotNull(setting.read(crosshair, value))
                    set += setting
                }
            }
        }
        if (border != null && CrosshairSetting.OutlineBorder !in set) {
            crosshair = checkNotNull(CrosshairSetting.OutlineBorder.read(crosshair, if (border) "1" else "0"))
            set += CrosshairSetting.OutlineBorder
        }
        return ReadCrosshair(crosshair, set)
    }

    private fun settings(line: String): List<Pair<String, String>>? {
        val statements = statements(line)?.takeIf { it.isNotEmpty() } ?: return null
        return statements.map { tokens ->
            if (tokens.size != 2) return null
            val (name, value) = tokens
            val known = CrosshairSetting.convar(name)?.read(Crosshair.GameDefault, value) != null || (name.equals(PipBorder, ignoreCase = true) && flag(value) != null)
            if (!known) return null
            name to value
        }
    }
}

/**
 * [line] as the console splits it: statements on `;` outside quotes, each its tokens with their quotes taken off, a
 * `//` outside quotes ending the line. Empty statements are left out. Null when a quote is not closed.
 */
internal fun statements(line: String): List<List<String>>? {
    val statements = mutableListOf<List<String>>()
    var tokens = mutableListOf<String>()
    fun end() {
        if (tokens.isNotEmpty()) statements += tokens
        tokens = mutableListOf()
    }
    var at = 0
    while (at < line.length) {
        val char = line[at]
        when {
            char == '"' -> {
                val close = line.indexOf('"', at + 1).takeIf { it >= 0 } ?: return null
                tokens += line.substring(at + 1, close)
                at = close + 1
            }
            char == ';' -> {
                end()
                at++
            }
            line.startsWith("//", at) -> break
            char.isWhitespace() -> at++
            else -> {
                val start = at
                while (at < line.length && !line[at].isWhitespace() && line[at] != ';' && line[at] != '"' && !line.startsWith("//", at)) at++
                tokens += line.substring(start, at)
            }
        }
    }
    end()
    return statements
}

private fun flag(value: String): Boolean? = when (value.trim().lowercase()) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}
