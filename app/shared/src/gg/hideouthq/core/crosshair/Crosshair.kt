package gg.hideouthq.core.crosshair

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A number as a crosshair setting holds it, kept to four decimals so a value written to the game's saved settings and
 * read back is equal to the one written. That equality is what "in use" is decided by. [toString] is the text Hideout
 * writes, as the game does: `3`, `1.5`, `-7`, `0.35`. At most 10 000 either way, far past any crosshair the game draws,
 * so no value read from a file or a community row overflows its units.
 */
@JvmInline
value class ConvarNumber internal constructor(internal val units: Long) : Comparable<ConvarNumber> {
    val value: Double get() = units.toDouble() / Scale

    /** Cut toward zero to a whole number, as the game cuts sizes and the gap. */
    val whole: Int get() = (units / Scale).toInt()

    val isWhole: Boolean get() = units % Scale == 0L

    override fun compareTo(other: ConvarNumber): Int = units.compareTo(other.units)

    override fun toString(): String {
        val fraction = (abs(units) % Scale).toString().padStart(4, '0').trimEnd('0')
        return (if (units < 0) "-" else "") + abs(units) / Scale + if (fraction.isEmpty()) "" else ".$fraction"
    }

    companion object {
        private const val Scale = 10_000L
        private const val Max = 10_000.0
        val Zero = ConvarNumber(0)

        /** [value], which is finite and at most 10 000 either way. */
        fun of(value: Double): ConvarNumber = requireNotNull(read(value)) { "$value is no crosshair value" }

        /** [value]; null for one that is not finite or is beyond 10 000 either way. */
        fun read(value: Double): ConvarNumber? = value.takeIf { it.isFinite() && abs(it) <= Max }?.let { ConvarNumber((it * Scale).roundToLong()) }

        /** `1.5`, `-7`, `.5`; null for anything that is not a number within 10 000 either way. */
        fun parse(text: String): ConvarNumber? = text.trim().toDoubleOrNull()?.let(::read)
    }
}

/** A colour of the crosshair: its fill, `crosshair_color_r/g/b`, or its border, `crosshair_outline_color_r/g/b`. */
data class CrosshairColor(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "a colour channel is 0 to 255" }
    }

    val hex: String get() = listOf(r, g, b).joinToString("", prefix = "#") { it.toString(16).padStart(2, '0').uppercase() }

    companion object {
        val White = CrosshairColor(255, 255, 255)
        val Black = CrosshairColor(0, 0, 0)

        val Swatches: List<CrosshairColor> = listOf(White, CrosshairColor(0, 255, 0), CrosshairColor(0, 255, 255), CrosshairColor(255, 230, 0), CrosshairColor(255, 80, 200), CrosshairColor(255, 59, 59))

        /** `#00ff8c` or `00FF8C`; null for anything else. */
        fun parseHex(text: String): CrosshairColor? {
            val digits = text.trim().removePrefix("#").takeIf { it.length == 6 } ?: return null
            val channels = digits.chunked(2).map { it.toIntOrNull(16) ?: return null }
            return CrosshairColor(channels[0], channels[1], channels[2])
        }
    }
}

data class Border(val size: ConvarNumber, val gap: ConvarNumber, val opacity: ConvarNumber) {
    val shows: Boolean get() = size > ConvarNumber.Zero && opacity > ConvarNumber.Zero
}

/** The four lines: each [length] long and [thickness] thick, [gap] further out than the game's own offset. */
data class Lines(val length: ConvarNumber, val thickness: ConvarNumber, val gap: ConvarNumber, val opacity: ConvarNumber, val border: Border)

data class Dot(val size: ConvarNumber, val opacity: ConvarNumber, val border: Border)

/**
 * One crosshair as the game saves it, its reticle: every crosshair setting of the `base` block of
 * `citadel_hero_settings.lst` but `crosshair_themed`, which Hideout leaves to the game. Values are what was read or set;
 * only [Adjustable.set] clamps, so an out-of-range community value shows as it is.
 */
data class Crosshair(
    val color: CrosshairColor,
    val borderColor: CrosshairColor,
    val lines: Lines,
    val dot: Dot,
    /** `pip_gap_static`: the lines don't spread when moving or shooting. The game's panel shows it as Reticle motion off. */
    val fixedGap: Boolean,
    /** `pip_gap_collapse`: a fixed gap closes while standing still. The game greys it out unless the gap is fixed. */
    val collapse: Boolean,
) {
    val hasLines: Boolean get() = lines.length > ConvarNumber.Zero && lines.thickness > ConvarNumber.Zero && lines.opacity > ConvarNumber.Zero

    val hasDot: Boolean get() = dot.size > ConvarNumber.Zero && dot.opacity > ConvarNumber.Zero

    val drawsNothing: Boolean get() = !hasLines && !hasDot && !lines.border.shows && !dot.border.shows

    companion object {
        val GameDefault = crosshair()
    }
}

/**
 * The editor's sliders, each one setting with its range and step, as the game's own Settings › Reticle panel has them
 * (`panorama/layout/popups/popup_settings.vxml`). The one list of ranges the editor and clamping read. [percent] ones
 * are opacities, shown as percentages; the others are pixels.
 */
enum class Adjustable(min: Double, max: Double, val step: Double, val percent: Boolean, val get: (Crosshair) -> ConvarNumber, internal val put: (Crosshair, ConvarNumber) -> Crosshair) {
    Length(1.0, 50.0, 1.0, false, { it.lines.length }, { c, value -> c.copy(lines = c.lines.copy(length = value)) }),
    Thickness(1.0, 50.0, 1.0, false, { it.lines.thickness }, { c, value -> c.copy(lines = c.lines.copy(thickness = value)) }),
    Gap(-40.0, 50.0, 1.0, false, { it.lines.gap }, { c, value -> c.copy(lines = c.lines.copy(gap = value)) }),
    Opacity(0.0, 1.0, 0.01, true, { it.lines.opacity }, { c, value -> c.copy(lines = c.lines.copy(opacity = value)) }),
    LineBorder(0.0, 10.0, 1.0, false, { it.lines.border.size }, { c, value -> c.copy(lines = c.lines.copy(border = c.lines.border.copy(size = value))) }),
    LineBorderGap(0.0, 100.0, 1.0, false, { it.lines.border.gap }, { c, value -> c.copy(lines = c.lines.copy(border = c.lines.border.copy(gap = value))) }),
    LineBorderOpacity(0.0, 1.0, 0.01, true, { it.lines.border.opacity }, { c, value -> c.copy(lines = c.lines.copy(border = c.lines.border.copy(opacity = value))) }),
    DotSize(1.0, 100.0, 1.0, false, { it.dot.size }, { c, value -> c.copy(dot = c.dot.copy(size = value)) }),
    DotOpacity(0.0, 1.0, 0.01, true, { it.dot.opacity }, { c, value -> c.copy(dot = c.dot.copy(opacity = value)) }),
    DotBorder(0.0, 10.0, 1.0, false, { it.dot.border.size }, { c, value -> c.copy(dot = c.dot.copy(border = c.dot.border.copy(size = value))) }),
    DotBorderGap(0.0, 100.0, 1.0, false, { it.dot.border.gap }, { c, value -> c.copy(dot = c.dot.copy(border = c.dot.border.copy(gap = value))) }),
    DotBorderOpacity(0.0, 1.0, 0.01, true, { it.dot.border.opacity }, { c, value -> c.copy(dot = c.dot.copy(border = c.dot.border.copy(opacity = value))) });

    val range: ClosedFloatingPointRange<Double> = min..max

    /** [crosshair] with this slider at [value], moved into its range and onto its step. */
    fun set(crosshair: Crosshair, value: Double): Crosshair {
        val steps = ((value.coerceIn(range) - range.start) / step).roundToLong()
        return put(crosshair, ConvarNumber(ConvarNumber.of(range.start).units + steps * ConvarNumber.of(step).units))
    }
}

enum class CrosshairPreset(val title: String, val crosshair: Crosshair) {
    Classic("Classic", crosshair(length = 10.0, gap = 6.0, opacity = 1.0, dotOpacity = 0.0, dotBorderOpacity = 0.0)),
    Dot("Dot", crosshair(CrosshairColor(0, 255, 255), length = 4.0, gap = 0.0, opacity = 0.0, lineBorder = 0.0, dotOpacity = 1.0, dotBorderOpacity = 0.0)),
    WideGap("Wide gap", crosshair(CrosshairColor(255, 230, 0), length = 9.0, gap = 12.0, opacity = 0.9, dotOpacity = 1.0, dotBorderOpacity = 0.0)),
}

internal fun crosshair(
    color: CrosshairColor = CrosshairColor.White,
    length: Double = 16.0,
    thickness: Double = 2.0,
    gap: Double = 4.0,
    opacity: Double = 0.5,
    lineBorder: Double = 1.0,
    lineBorderGap: Double = 0.0,
    lineBorderOpacity: Double = 0.7,
    dotSize: Double = 4.0,
    dotOpacity: Double = 0.7,
    dotBorder: Double = 2.0,
    dotBorderGap: Double = 0.0,
    dotBorderOpacity: Double = 0.7,
    borderColor: CrosshairColor = CrosshairColor.Black,
    fixedGap: Boolean = false,
    collapse: Boolean = false,
): Crosshair {
    fun n(value: Double) = ConvarNumber.of(value)
    return Crosshair(
        color, borderColor,
        Lines(n(length), n(thickness), n(gap), n(opacity), Border(n(lineBorder), n(lineBorderGap), n(lineBorderOpacity))),
        Dot(n(dotSize), n(dotOpacity), Border(n(dotBorder), n(dotBorderGap), n(dotBorderOpacity))),
        fixedGap, collapse,
    )
}
