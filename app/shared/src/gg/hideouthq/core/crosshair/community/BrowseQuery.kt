package gg.hideouthq.core.crosshair.community

import gg.hideouthq.core.crosshair.ConvarNumber
import gg.hideouthq.core.crosshair.Crosshair
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

enum class CrosshairSort { Popular, ThisWeek, New }

enum class CrosshairStyle(val holds: (Crosshair) -> Boolean) {
    Small({ it.hasLines && it.lines.length <= ConvarNumber.of(3.5) }),
    DotOnly({ it.lines.opacity == ConvarNumber.Zero && it.hasDot }),
    WithDot({ it.hasDot }),
    Outline({ it.lines.border.shows }),
    FixedGap({ it.fixedGap }),
}

data class BrowseQuery(
    val text: String = "",
    val sort: CrosshairSort = CrosshairSort.Popular,
    val styles: Set<CrosshairStyle> = emptySet(),
) {
    /** Those of [all] that match, in order, at [now]. Every word of [text] must be in the name or the creator, ignoring case. */
    fun matches(all: List<CommunityCrosshair>, now: Instant): List<CommunityCrosshair> {
        val words = text.lowercase().split(Whitespace).filter { it.isNotEmpty() }
        val matches = all.filter { crosshair ->
            words.all { word -> word in crosshair.name.lowercase() || word in crosshair.creator.orEmpty().lowercase() } && styles.all { it.holds(crosshair.crosshair) }
        }
        return when (sort) {
            CrosshairSort.Popular -> matches.sortedWith(compareByDescending<CommunityCrosshair> { it.likes }.thenByDescending { it.createdAt })
            CrosshairSort.ThisWeek -> matches.filter { now - it.createdAt <= 7.days }.sortedByDescending { it.createdAt }
            CrosshairSort.New -> matches.sortedByDescending { it.createdAt }
        }
    }

    private companion object {
        val Whitespace = Regex("\\s+")
    }
}
