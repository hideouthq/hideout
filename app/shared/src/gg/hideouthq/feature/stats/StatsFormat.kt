package gg.hideouthq.feature.stats

import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object StatsFormat {
    private val symbols = DecimalFormatSymbols(Locale.ENGLISH)

    private fun pattern(value: String) = DecimalFormat(value, symbols).apply { roundingMode = RoundingMode.HALF_UP }

    /** 1,124 */
    fun count(value: Number): String = pattern("#,##0").format(value)

    /** 41.2k, 0.9k, 209.4k: thousands with one decimal. */
    fun thousands(value: Number): String = pattern("#,##0.0").format(value.toDouble() / 1000) + "k"

    /** 3.4, 7.0 */
    fun decimal(value: Double): String = pattern("0.0").format(value)

    /** 58 for a 0.58 fraction. */
    fun percentOf(fraction: Double): String = pattern("0").format(fraction * 100)

    /** "+625", "−16.1k": a lead with its sign, in thousands from a thousand; "0" for none. */
    fun signedThousands(value: Double): String {
        val size = abs(value).roundToLong()
        val text = if (size >= 1000) thousands(abs(value)) else count(size)
        return when {
            size == 0L -> "0"
            value > 0 -> "+$text"
            else -> "−$text"
        }
    }

    /** 1st, 2nd, 3rd, 4th, 11th, 21st. */
    fun ordinal(value: Int): String {
        val suffix = when {
            value % 100 in 11..13 -> "th"
            value % 10 == 1 -> "st"
            value % 10 == 2 -> "nd"
            value % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$value$suffix"
    }

    /** 34:12, or 1:02:03 past an hour. */
    fun duration(seconds: Int): String {
        val total = seconds.coerceAtLeast(0)
        fun twoDigits(value: Int) = value.toString().padStart(2, '0')
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val rest = twoDigits(total % 60)
        return if (hours > 0) "$hours:${twoDigits(minutes)}:$rest" else "$minutes:$rest"
    }

    /** Whole hours, rounded half up. */
    fun hours(seconds: Long): String = count((seconds / 3600.0).roundToLong())

    /** A change's magnitude in its unit's style: "4" points, "6" percent, "0.3" plain. */
    fun change(change: Change): String = when (change.unit) {
        ChangeUnit.Points, ChangeUnit.Percent -> pattern("0").format(abs(change.amount))
        ChangeUnit.Plain -> decimal(abs(change.amount))
    }

    /** Whether a change rounds to nothing in its own style, so no chip is worth showing. */
    fun isFlat(change: Change): Boolean = change(change).toDouble() == 0.0

    /** The 17-digit Steam ID people see on their profile, for a 32-bit account id. */
    fun steamId(accountId: Long): String = SteamAccountId.ofAccountId(accountId)?.toSteamId64()?.toString() ?: accountId.toString()

    /** "41 236 118": a match id in groups of three. */
    fun matchId(id: Long): String = id.toString().reversed().chunked(3).joinToString(" ").reversed()

    /** "+17" or "−12", with a real minus sign. */
    fun signedPoints(points: Double): String {
        val rounded = points.roundToLong()
        return when {
            rounded > 0 -> "+$rounded"
            rounded < 0 -> "−${-rounded}"
            else -> "0"
        }
    }
}
