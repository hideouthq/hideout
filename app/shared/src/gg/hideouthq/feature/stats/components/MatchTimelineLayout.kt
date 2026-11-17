package gg.hideouthq.feature.stats.components

import gg.hideouthq.feature.stats.GraphView
import gg.hideouthq.feature.stats.MatchCurve
import gg.hideouthq.feature.stats.MatchDeath
import gg.hideouthq.feature.stats.MatchPlayer
import gg.hideouthq.feature.stats.MatchTimeline
import gg.hideouthq.feature.stats.ObjectiveEvent
import gg.hideouthq.feature.stats.Team
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class GraphTick(val value: Double, val labelled: Boolean, val zero: Boolean)

internal data class GraphScale(val high: Double, val low: Double, val ticks: List<GraphTick>) {
    fun fraction(value: Double): Double = (high - value) / (high - low)
}

internal fun niceStep(value: Double): Double {
    if (value <= 0) return 1.0
    val magnitude = 10.0.pow(floor(log10(value)))
    return listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= value }
}

internal fun leadScale(largest: Double): GraphScale {
    val step = niceStep(largest / LeadSpan).coerceAtLeast(MinLeadStep)
    val edge = step * LeadSpan
    return GraphScale(
        high = edge, low = -edge,
        ticks = listOf(GraphTick(2 * step, true, false), GraphTick(step, false, false), GraphTick(0.0, true, true), GraphTick(-step, false, false), GraphTick(-2 * step, true, false)),
    )
}

private const val LeadSpan = 2 * 0.5 / 0.42
private const val MinLeadStep = 500.0

internal fun amountScale(largest: Double): GraphScale {
    val peak = largest.takeIf { it > 0 } ?: 1_000.0
    val top = peak * 1.14
    val step = niceStep(peak / 2.5)
    return GraphScale(
        high = top, low = -top * 0.06 / 0.94,
        ticks = generateSequence(0.0) { it + step }.takeWhile { it <= top }.map { GraphTick(it, true, it == 0.0) }.toList(),
    )
}

internal data class PlacedObjective(val event: ObjectiveEvent, val x: Float, val line: Int)

internal fun placeObjectives(events: List<ObjectiveEvent>, durationS: Int, width: Float, spacing: Float): List<PlacedObjective> {
    val last = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
    return events.sortedBy { it.timeS }.map { event ->
        val x = width * event.timeS / durationS.coerceAtLeast(1)
        val free = (0..1).firstOrNull { x - last[it] >= spacing }
        if (free != null) {
            last[free] = x
            PlacedObjective(event, x, free)
        } else {
            val line = if (last[0] <= last[1]) 0 else 1
            last[line] += spacing
            PlacedObjective(event, last[line], line)
        }
    }
}

internal fun stackLevels(xs: List<Float>, spacing: Float): List<Int> {
    val levels = ArrayList<Int>(xs.size)
    xs.forEachIndexed { i, x ->
        val taken = (0 until i).filter { abs(xs[it] - x) < spacing }.map { levels[it] }.toSet()
        levels += generateSequence(0) { it + 1 }.first { it !in taken }
    }
    return levels
}

internal data class TimelineMark(val death: MatchDeath, val kill: Boolean) {
    val timeS: Int get() = death.timeS
}

internal fun marksOf(timeline: MatchTimeline, subject: Long?): List<TimelineMark> {
    if (subject == null) return emptyList()
    return timeline.deaths.orEmpty().mapNotNull { death ->
        when {
            death.victim == subject -> TimelineMark(death, kill = false)
            death.killer == subject -> TimelineMark(death, kill = true)
            else -> null
        }
    }
}

internal fun plottedMarks(marks: List<TimelineMark>, curve: MatchCurve?): List<Pair<TimelineMark, Double>> =
    marks.mapNotNull { mark -> curve?.valueAt(mark.timeS.toDouble())?.let { mark to it } }

internal fun deadBands(timeline: MatchTimeline, subject: Long?): List<IntRange> =
    timeline.deaths.orEmpty().filter { it.victim == subject && it.durationS != null && it.durationS > 0 }
        .map { it.timeS..(it.timeS + it.durationS!!).coerceAtMost(timeline.durationS) }

internal fun shownCurve(timeline: MatchTimeline, view: GraphView, viewer: MatchPlayer?): MatchCurve? = when (view) {
    GraphView.SoulsLead -> timeline.lead
    GraphView.YourSouls -> viewer?.souls
    GraphView.YourDamage -> viewer?.damage
}

internal fun averageCurve(timeline: MatchTimeline, view: GraphView): MatchCurve? = when (view) {
    GraphView.SoulsLead -> null
    GraphView.YourSouls -> timeline.averageSouls
    GraphView.YourDamage -> timeline.averageDamage
}

internal fun hoverStop(timeS: Double, durationS: Int): Int {
    val last = durationS / 60
    if (hasEndStop(durationS) && timeS >= last * 60 + HalfMinute) return durationS
    return (timeS / 60).roundToInt().coerceIn(0, last) * 60
}

internal fun inWindow(eventS: Int, stopS: Int, durationS: Int): Boolean =
    if (hasEndStop(durationS) && stopS == durationS) eventS in durationS / 60 * 60 + HalfMinute..durationS
    else eventS >= stopS - HalfMinute && eventS < stopS + HalfMinute

private fun hasEndStop(durationS: Int): Boolean = durationS % 60 >= HalfMinute

private const val HalfMinute = 30

internal data class MinuteReadout(
    val timeS: Int,
    val value: Double?,
    val average: Double?,
    val kills: Pair<Int, Int>?,
    val marks: List<TimelineMark>,
    val objectives: List<ObjectiveEvent>,
) {
    val quiet: Boolean get() = kills == null && marks.isEmpty() && objectives.isEmpty()
}

internal fun minuteReadout(
    timeline: MatchTimeline,
    view: GraphView,
    viewer: MatchPlayer?,
    subject: Long?,
    perspective: Team,
    teams: Map<Long, Team?>,
    stopS: Int,
): MinuteReadout {
    fun within(eventS: Int) = inWindow(eventS, stopS, timeline.durationS)
    val window = timeline.deaths.orEmpty().filter { within(it.timeS) }
    val credited = window.mapNotNull { death -> death.killer?.let(teams::get)?.takeIf { it != teams[death.victim] } }
    return MinuteReadout(
        timeS = stopS,
        value = shownCurve(timeline, view, viewer)?.valueAt(stopS.toDouble()),
        average = averageCurve(timeline, view)?.valueAt(stopS.toDouble()),
        kills = if (timeline.deathsComplete && credited.isNotEmpty()) credited.count { it == perspective } to credited.count { it != perspective } else null,
        marks = marksOf(timeline, subject).filter { within(it.timeS) },
        objectives = timeline.objectives.filter { within(it.timeS) },
    )
}

internal fun tooltipStart(x: Float, width: Float, tooltipWidth: Float, gap: Float, before: Float, after: Float): Float {
    val flip = x > width * 2 / 3 || x + gap + tooltipWidth > width + after
    val start = if (flip) x - gap - tooltipWidth else x + gap
    return start.coerceIn(-before, width + after - tooltipWidth)
}

internal fun axisTimes(durationS: Int, width: Float, minGap: Float): List<Int> =
    (0 until durationS step 300).filter { it == 0 || (durationS - it).toFloat() / durationS * width >= minGap }
