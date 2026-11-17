package gg.hideouthq.feature.stats.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.RankChart
import gg.hideouthq.feature.stats.RankPoint
import gg.hideouthq.feature.stats.data.linearRank
import gg.hideouthq.feature.stats.monotoneTangents
import kotlin.math.max
import kotlin.math.min

@Composable
internal fun rememberReveal(key: Any?): Animatable<Float, *> {
    val motion = HideoutTheme.motion
    val reveal = remember(key) { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(key) { if (!motion.reduced) reveal.animateTo(1f, motion.effectsSlow()) }
    return reveal
}

internal fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    if (points.size == 1) return path
    val tangents = monotoneTangents(DoubleArray(points.size) { points[it].x.toDouble() }, DoubleArray(points.size) { points[it].y.toDouble() })
    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val dx = (p1.x - p0.x) / 3
        path.cubicTo(p0.x + dx, p0.y + tangents[i].toFloat() * dx, p1.x - dx, p1.y - tangents[i + 1].toFloat() * dx, p1.x, p1.y)
    }
    return path
}

@Composable
internal fun Sparkline(values: List<Double?>, benchmark: Double?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val line = scheme.primary
    val area = scheme.primary.copy(alpha = 0.12f)
    val average = scheme.onSurfaceVariant.copy(alpha = 0.55f)
    val ring = scheme.surfaceContainer
    val reveal = rememberReveal(values)
    Canvas(modifier.fillMaxWidth().height(40.dp)) {
        val known = values.filterNotNull() + listOfNotNull(benchmark)
        if (known.isEmpty()) return@Canvas
        val low = known.min()
        val high = known.max()
        val span = (high - low).takeIf { it > 0 } ?: 1.0
        val top = 7.dp.toPx()
        val bottom = size.height - 7.dp.toPx()
        fun y(value: Double) = (bottom - (value - low) / span * (bottom - top)).toFloat()
        val step = if (values.size > 1) size.width / (values.size - 1) else 0f
        val runs = values.indices.fold(mutableListOf<MutableList<Offset>>()) { acc, i ->
            val value = values[i]
            if (value == null) acc.add(mutableListOf()) else (acc.lastOrNull() ?: mutableListOf<Offset>().also(acc::add)).add(Offset(i * step, y(value)))
            acc
        }.filter { it.isNotEmpty() }
        clipRect(right = size.width * reveal.value + 6.dp.toPx()) {
            runs.forEach { run ->
                if (run.size < 2) return@forEach
                val path = Path().apply {
                    moveTo(run.first().x, size.height)
                    run.forEach { lineTo(it.x, it.y) }
                    lineTo(run.last().x, size.height)
                    close()
                }
                drawPath(path, area)
                drawPath(Path().apply { run.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        if (benchmark != null) {
            val by = y(benchmark)
            drawLine(average, Offset(0f, by), Offset(size.width, by), strokeWidth = 1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
        }
        val lastIndex = values.indexOfLast { it != null }
        if (lastIndex >= 0 && runs.sumOf { it.size } >= 2 && reveal.value >= 1f) {
            val end = Offset(lastIndex * step, y(values[lastIndex]!!))
            drawCircle(ring, radius = 7.5.dp.toPx(), center = end)
            drawCircle(line, radius = 4.5.dp.toPx(), center = end)
        }
    }
}

@Composable
internal fun WinDonut(fraction: Double?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val reveal = rememberReveal(fraction)
    Canvas(modifier.size(62.dp)) {
        val stroke = 8.dp.toPx()
        val radius = 24.dp.toPx()
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arc = Size(radius * 2, radius * 2)
        drawArc(track, 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
        val sweep = 360f * (fraction ?: 0.0).toFloat().coerceIn(0f, 1f) * reveal.value
        if (sweep > 0f) drawArc(StatsColors.Win, -90f, sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
internal fun FormBars(results: List<Boolean>, description: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(22.dp).semantics { contentDescription = description }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        results.forEach { win ->
            Box(
                Modifier.weight(1f).height(22.dp).drawBehind {
                    drawRoundRect(if (win) StatsColors.Win else StatsColors.Loss.copy(alpha = 0.85f), cornerRadius = CornerRadius(3.dp.toPx()))
                },
            )
        }
    }
}

@Composable
internal fun RankChartView(chart: RankChart, peakLabel: String, axis: List<Pair<Float, String>>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val measurer = rememberTextMeasurer()
    val labelStyle = type.small.copy(color = scheme.outline)
    val tagStyle = type.small.copy(color = scheme.onSurface, fontWeight = type.chip.fontWeight)
    val grid = scheme.outlineVariant
    val tag = scheme.surfaceContainerHighest
    val ring = scheme.surfaceContainer
    val reveal = rememberReveal(chart)
    BoxWithConstraints(modifier.fillMaxWidth().height(236.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val left = 76.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val plotBottom = size.height - 26.dp.toPx()
            val levels = chart.levels()
            val lowest = levels.first()
            val highest = levels.last()
            fun y(rank: Double): Float {
                val t = ((rank - linearRank(lowest)) / (linearRank(highest) - linearRank(lowest))).toFloat()
                return plotBottom * (0.853f - t * (0.853f - 0.209f))
            }
            fun y(badge: Int): Float = y(linearRank(badge).toDouble())
            fun y(point: RankPoint): Float = y(point.position ?: linearRank(point.badge).toDouble())
            val span = (chart.endEpoch - chart.startEpoch).coerceAtLeast(1)
            fun x(epoch: Long) = left + (right - left) * ((epoch - chart.startEpoch).toFloat() / span).coerceIn(0f, 1f)
            val labelled = levels.labelled()
            labelled.forEach { badge ->
                val ly = y(badge)
                drawLine(grid, Offset(left, ly), Offset(size.width, ly), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
                drawLabel(measurer, chart.names[badge].orEmpty(), labelStyle, Offset(0f, ly), centerY = true, maxWidth = 68.dp.toPx())
            }
            val points = chart.points.map { Offset(x(it.epochSeconds), y(it)) }
            val line = smoothPath(points)
            clipRect(right = left + (right - left) * reveal.value + 8.dp.toPx()) {
                if (points.size > 1) {
                    val fill = Path().apply {
                        addPath(line)
                        lineTo(points.last().x, plotBottom)
                        lineTo(points.first().x, plotBottom)
                        close()
                    }
                    drawPath(fill, Brush.verticalGradient(listOf(StatsColors.Gold.copy(alpha = 0.2f), StatsColors.Gold.copy(alpha = 0f)), startY = 0f, endY = plotBottom))
                    drawPath(line, StatsColors.Gold, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            if (reveal.value >= 1f) {
                chart.peakAt?.let { peak ->
                    val p = Offset(x(peak.epochSeconds), y(peak))
                    if (peak != chart.points.last()) {
                        drawCircle(ring, 4.dp.toPx(), p)
                        drawCircle(StatsColors.Gold, 4.dp.toPx(), p, style = Stroke(2.dp.toPx()))
                    }
                    drawTag(measurer, peakLabel, tagStyle, tag, Offset(p.x, p.y), left, size.width)
                }
                points.lastOrNull()?.let { last ->
                    drawCircle(StatsColors.Gold.copy(alpha = 0.22f), 14.dp.toPx(), last)
                    drawCircle(ring, 10.dp.toPx(), last)
                    drawCircle(StatsColors.Gold, 6.dp.toPx(), last)
                }
            }
            axis.forEach { (fraction, text) ->
                val lx = left + (right - left) * fraction
                drawLabel(measurer, text, labelStyle, Offset(lx, size.height), centerX = true, alignBottom = true, rightBleed = 14.dp.toPx())
            }
        }
    }
}

private fun RankChart.levels(): List<Int> {
    val ranks = points.map { linearRank(it.badge) }
    val low = ranks.min()
    val high = ranks.max()
    val (from, to) = if (low == high) (low - 1).coerceAtLeast(0) to high + 1 else low to high
    return (from..to).map { step -> (step / 6) * 10 + step % 6 + 1 }
}

private fun List<Int>.labelled(): List<Int> {
    if (size <= 6) return this
    val stride = (size - 1 + 4) / 5
    return filterIndexed { index, _ -> index % stride == 0 || index == lastIndex }
}

internal fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    anchor: Offset,
    centerX: Boolean = false,
    centerY: Boolean = false,
    alignBottom: Boolean = false,
    maxWidth: Float? = null,
    rightBleed: Float = 0f,
) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, style, maxLines = 1, softWrap = false)
    val width = min(layout.size.width.toFloat(), maxWidth ?: Float.MAX_VALUE)
    val x = (if (centerX) anchor.x - width / 2 else anchor.x).coerceIn(0f, max(0f, size.width - width + rightBleed))
    val y = when {
        centerY -> anchor.y - layout.size.height / 2f
        alignBottom -> anchor.y - layout.size.height
        else -> anchor.y
    }
    drawText(layout, topLeft = Offset(x, y))
}

private fun DrawScope.drawTag(measurer: TextMeasurer, text: String, style: TextStyle, fill: Color, point: Offset, minX: Float, maxX: Float) {
    if (text.isEmpty()) return
    val layout = measurer.measure(text, style, maxLines = 1, softWrap = false)
    val height = 24.dp.toPx()
    val width = layout.size.width + 18.dp.toPx()
    val x = (point.x - width / 2).coerceIn(minX, maxX - width)
    val y = (point.y - height * 1.4f).let { if (it < 0f) point.y + height * 0.4f else it }
    drawRoundRect(fill, Offset(x, y), Size(width, height), CornerRadius(height / 2))
    drawText(layout, topLeft = Offset(x + 9.dp.toPx(), y + (height - layout.size.height) / 2))
}
