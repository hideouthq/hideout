package gg.hideouthq.core.model

import kotlin.jvm.JvmInline

/**
 * A themed set of skins as the Hideout team publishes it. [cover] and [members] are catalogue mod ids, best first; the
 * cover's mods have character renders. A collection with a [season] is topical only on its days.
 */
data class SkinCollection(
    val id: String,
    val title: String,
    val season: Season?,
    val look: CollectionLook,
    val cover: List<String>,
    val members: List<String>,
)

/**
 * How a collection's art is lit, like a photo studio: a [top] to [bottom] gradient, soft
 * [glows], a [main] light behind the middle render and, with [arch], an arch of that light. The renders are lit from
 * behind in [rim]: one colour from the right, or two, the first from the left and the second from the right.
 */
data class CollectionLook(val top: Rgb, val bottom: Rgb, val main: Rgb, val glows: List<Glow>, val arch: Boolean, val rim: List<Rgb>) {
    init {
        require(rim.size in 1..2) { "A collection is rim lit in one or two colours, not ${rim.size}" }
    }
}

/**
 * A soft light, [color] at [alpha] in its centre and fading out. Its centre is [x] across and [y] down the stage and
 * its [radius] is a fraction of the stage's width; all are fractions.
 */
data class Glow(val color: Rgb, val alpha: Float, val x: Float, val y: Float, val radius: Float) {
    init {
        require(alpha in 0f..1f && x in 0f..1f && y in 0f..1f && radius > 0f && radius <= 1f) { "A glow's alpha, x and y are 0 to 1, its radius above 0 to 1" }
    }
}

/** An opaque colour, `0xRRGGBB`. */
@JvmInline
value class Rgb(val value: Int)

/** A day of any year, such as 7 November. */
data class MonthDay(val month: Int, val day: Int) : Comparable<MonthDay> {
    init {
        require(month in 1..12 && day in 1..DaysInMonth[month - 1]) { "Invalid day $month-$day" }
    }

    override fun compareTo(other: MonthDay): Int = compareValuesBy(this, other, MonthDay::month, MonthDay::day)

    companion object {
        private val DaysInMonth = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

        fun of(date: CalendarDate): MonthDay = MonthDay(date.month, date.day)
    }
}

/** The days a collection is topical, [from] through [until]. When [until] comes before [from] it runs over the new year. */
data class Season(val from: MonthDay, val until: MonthDay) {
    operator fun contains(date: CalendarDate): Boolean {
        val day = MonthDay.of(date)
        return if (from <= until) day in from..until else day >= from || day <= until
    }

    /** The first day on or after [date] that the season ends on: while it runs, the day it ends. */
    fun end(date: CalendarDate): CalendarDate =
        CalendarDate(if (MonthDay.of(date) <= until) date.year else date.year + 1, until.month, until.day)
}
