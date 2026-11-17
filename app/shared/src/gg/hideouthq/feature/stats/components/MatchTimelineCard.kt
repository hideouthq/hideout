package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.EndAlignedFlow
import gg.hideouthq.feature.stats.GraphView
import gg.hideouthq.feature.stats.MatchCurve
import gg.hideouthq.feature.stats.MatchDetailContent
import gg.hideouthq.feature.stats.MatchPlayer
import gg.hideouthq.feature.stats.MatchTimeline
import gg.hideouthq.feature.stats.ObjectiveEvent
import gg.hideouthq.feature.stats.ObjectiveKind
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.feature.stats.Team
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_graph
import gg.hideouthq.resources.stats_graph_damage
import gg.hideouthq.resources.stats_graph_lead
import gg.hideouthq.resources.stats_graph_missing
import gg.hideouthq.resources.stats_graph_souls
import gg.hideouthq.resources.stats_legend_ahead
import gg.hideouthq.resources.stats_marker_dead
import gg.hideouthq.resources.stats_marker_death
import gg.hideouthq.resources.stats_marker_kill
import gg.hideouthq.resources.stats_marks_loading
import gg.hideouthq.resources.stats_marks_missing
import gg.hideouthq.resources.stats_marks_others
import gg.hideouthq.resources.stats_marks_pick
import gg.hideouthq.resources.stats_marks_yours
import gg.hideouthq.resources.stats_match_average
import gg.hideouthq.resources.stats_objective_base_guardian
import gg.hideouthq.resources.stats_objective_guardian
import gg.hideouthq.resources.stats_objective_mid_boss
import gg.hideouthq.resources.stats_objective_patron
import gg.hideouthq.resources.stats_objective_shrine
import gg.hideouthq.resources.stats_objective_walker
import gg.hideouthq.resources.stats_objectives_label
import gg.hideouthq.resources.stats_summary_ahead
import gg.hideouthq.resources.stats_summary_behind
import gg.hideouthq.resources.stats_summary_kills
import gg.hideouthq.resources.stats_summary_objectives
import gg.hideouthq.resources.stats_summary_team_ahead
import gg.hideouthq.resources.stats_summary_team_behind
import gg.hideouthq.resources.stats_team_you
import gg.hideouthq.resources.stats_tooltip_average
import gg.hideouthq.resources.stats_tooltip_even
import gg.hideouthq.resources.stats_tooltip_kills
import gg.hideouthq.resources.stats_tooltip_lead
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.max

private val PlotLeft = 56.dp
private val ObjectivesHeight = 50.dp
private val ChartTop = ObjectivesHeight + 4.dp
private val ChartHeight = 220.dp
private val AxisTop = ChartTop + ChartHeight + 8.dp
private val TimelineHeight = AxisTop + 22.dp + 2.dp
private val ObjectiveTileSize = 22.dp
private val ObjectiveLine = 24.dp
private val MarkerSize = 24.dp
private val MarkerStep = 26.dp
private val GuideRise = 30.dp
private val TooltipWidth = 244.dp
private val TooltipGap = 14.dp

private val TooltipRoom = 16.dp
private val AxisLabelGap = 48.dp

@Composable
internal fun MatchTimelineCard(
    content: MatchDetailContent,
    view: GraphView,
    onView: (GraphView) -> Unit,
    subject: MatchPlayer?,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val timeline = content.timeline
    val viewer = content.you
    val available = if (viewer?.souls == null) listOf(GraphView.SoulsLead) else GraphView.entries
    val shown = view.takeIf { it in available } ?: GraphView.SoulsLead
    val labels = mapOf(
        GraphView.SoulsLead to stringResource(Res.string.stats_graph_lead),
        GraphView.YourSouls to stringResource(Res.string.stats_graph_souls),
        GraphView.YourDamage to stringResource(Res.string.stats_graph_damage),
    )
    Column(modifier.fillMaxWidth().background(scheme.surfaceContainer, StatsShapes.Card).padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 8.dp)) {
        EndAlignedFlow(Modifier.fillMaxWidth().heightIn(min = StatsDimens.CardHeaderHeight).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), gap = 10.dp, lineGap = 6.dp) {
            SegmentedControl(available, shown, onView, { labels.getValue(it) }, style = SegmentedStyle.Card)
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (shown == GraphView.SoulsLead) {
                    val other = content.perspective.other
                    TeamSwatch(if (content.spectator) StatsLabels.team(content.perspective) else stringResource(Res.string.stats_team_you, StatsLabels.team(content.perspective)), StatsColors.team(content.perspective))
                    TeamSwatch(StatsLabels.team(other), StatsColors.team(other))
                } else {
                    TeamSwatch(labels.getValue(shown), scheme.onSurface)
                    TeamSwatch(stringResource(Res.string.stats_match_average), scheme.outline)
                }
            }
        }
        if (timeline == null) {
            Box(Modifier.fillMaxWidth().height(250.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(Res.string.stats_graph_missing), style = statsType().caption, color = scheme.outline)
            }
        } else {
            Summary(content, timeline, subject, refreshing)
            TimelinePlot(content, timeline, shown, subject, Modifier.zIndex(1f).padding(start = 8.dp, top = 6.dp, end = 8.dp))
            ObjectiveKey()
        }
    }
}

internal val Team.other: Team get() = if (this == Team.Amber) Team.Sapphire else Team.Amber

object MatchTimelineTags {
    const val Tooltip = "matchMinuteTooltip"
}

@Composable
private fun TeamSwatch(label: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(label, style = statsType().note, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
    }
}

private fun emphasise(text: String, part: String, color: Color): AnnotatedString = buildAnnotatedString {
    val at = text.indexOf(part)
    if (at < 0) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substring(0, at))
    withStyle(SpanStyle(color = color, fontWeight = HideoutWeights.Title, fontFeatureSettings = "tnum")) { append(part) }
    append(text.substring(at + part.length))
}

@Composable
private fun Summary(content: MatchDetailContent, timeline: MatchTimeline, subject: MatchPlayer?, refreshing: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val style = type.meta.copy(color = scheme.outline)
    val summary = timeline.summary
    val none = StatsLabels.none()
    EndAlignedFlow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 4.dp), gap = 22.dp, lineGap = 6.dp) {
        val side = StatsLabels.team(content.perspective)
        summary.aheadShare?.let { share ->
            val part = StatsLabels.percent(share)
            val text = if (content.spectator) stringResource(Res.string.stats_summary_team_ahead, side, part) else stringResource(Res.string.stats_summary_ahead, part)
            Text(emphasise(text, part, scheme.onSurface), style = style, maxLines = 1)
        }
        val kills = "${summary.kills ?: none} – ${summary.killsAgainst ?: none}"
        Text(emphasise(stringResource(Res.string.stats_summary_kills, kills), kills, scheme.onSurface), style = style, maxLines = 1)
        val objectives = "${summary.objectives} – ${summary.objectivesAgainst}"
        Text(emphasise(stringResource(Res.string.stats_summary_objectives, objectives), objectives, scheme.onSurface), style = style, maxLines = 1)
        summary.furthestBehind?.let { low ->
            val part = StatsFormat.signedThousands(low.value)
            val at = StatsFormat.duration(low.timeS)
            val text = if (content.spectator) stringResource(Res.string.stats_summary_team_behind, side, part, at) else stringResource(Res.string.stats_summary_behind, part, at)
            Text(emphasise(text, part, StatsColors.team(content.perspective.other)), style = style, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            MarkerLegend(timeline, subject, refreshing)
        }
    }
}

@Composable
private fun MarkerLegend(timeline: MatchTimeline, subject: MatchPlayer?, refreshing: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val style = type.note.copy(color = scheme.onSurfaceVariant)
    when {
        timeline.deaths == null -> {
            Text(stringResource(if (refreshing) Res.string.stats_marks_loading else Res.string.stats_marks_missing), style = style.copy(color = scheme.outline), maxLines = 1, softWrap = false)
            return
        }
        subject == null -> Text(stringResource(Res.string.stats_marks_pick), style = style.copy(color = scheme.outline), maxLines = 1, softWrap = false)
        else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeroTile(subject.hero, 20.dp, type.you)
            Text(
                if (subject.isYou) stringResource(Res.string.stats_marks_yours) else stringResource(Res.string.stats_marks_others, StatsLabels.playerOrHero(subject)),
                style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
    }
    LegendMark(stringResource(Res.string.stats_marker_kill), style) { drawCircle(StatsColors.Win, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx())) }
    LegendMark(stringResource(Res.string.stats_marker_death), style) { drawCircle(StatsColors.Loss, size.minDimension / 2 - 1.dp.toPx(), style = Stroke(2.dp.toPx())) }
    LegendMark(stringResource(Res.string.stats_marker_dead), style, width = 12.dp) { drawRoundRect(StatsColors.Loss.copy(alpha = 0.22f), cornerRadius = CornerRadius(3.dp.toPx())) }
}

@Composable
private fun LegendMark(label: String, style: TextStyle, width: Dp = 10.dp, draw: DrawScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width, 10.dp).drawBehind(draw))
        Text(label, style = style, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun ObjectiveKey() {
    val scheme = MaterialTheme.colorScheme
    val style = statsType().note.copy(color = scheme.outline)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            HideoutIcons.ObjectiveGuardian to Res.string.stats_objective_guardian,
            HideoutIcons.ObjectiveWalker to Res.string.stats_objective_walker,
            HideoutIcons.ObjectiveBaseGuardian to Res.string.stats_objective_base_guardian,
            HideoutIcons.ObjectiveShrine to Res.string.stats_objective_shrine,
            HideoutIcons.ObjectivePatron to Res.string.stats_objective_patron,
            HideoutIcons.ObjectiveMidBoss to Res.string.stats_objective_mid_boss,
        ).forEach { (icon, label) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HideoutIcon(icon, null, size = 13.dp, tint = scheme.onSurfaceVariant)
                Text(stringResource(label), style = style, maxLines = 1)
            }
        }
    }
}

internal fun objectiveIcon(kind: ObjectiveKind): ImageVector = when (kind) {
    ObjectiveKind.Guardian -> HideoutIcons.ObjectiveGuardian
    ObjectiveKind.Walker -> HideoutIcons.ObjectiveWalker
    ObjectiveKind.BaseGuardian -> HideoutIcons.ObjectiveBaseGuardian
    ObjectiveKind.Shrine -> HideoutIcons.ObjectiveShrine
    ObjectiveKind.PatronWeakened -> HideoutIcons.ObjectivePatron
    ObjectiveKind.Patron -> HideoutIcons.ObjectivePatronDestroyed
    ObjectiveKind.MidBoss -> HideoutIcons.ObjectiveMidBoss
}

@Composable
internal fun ObjectiveTile(event: ObjectiveEvent, size: Dp, iconSize: Dp, modifier: Modifier = Modifier) {
    val color = StatsColors.team(event.takenBy)
    Box(modifier.size(size).tintedTile(lerp(MaterialTheme.colorScheme.surfaceContainer, color, 0.17f), color.copy(alpha = 0.34f), 7.dp), contentAlignment = Alignment.Center) {
        HideoutIcon(objectiveIcon(event.kind), null, size = iconSize, tint = color)
    }
}

@Composable
internal fun MarkPortrait(player: MatchPlayer?, kill: Boolean, size: Dp, ring: Dp, gap: Dp, gapColor: Color, modifier: Modifier = Modifier) {
    val color = if (kill) StatsColors.Win else StatsColors.Loss
    Box(
        modifier.size(size).drawBehind {
            drawCircle(gapColor, this.size.minDimension / 2 + (ring + gap).toPx())
            drawCircle(color, this.size.minDimension / 2 + ring.toPx())
        },
    ) {
        HeroTile(player?.hero, size, statsType().you.copy(fontSize = (size.value * 0.36f).sp), shape = CircleShape)
    }
}

@Composable
private fun TimelinePlot(content: MatchDetailContent, timeline: MatchTimeline, view: GraphView, subject: MatchPlayer?, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val measurer = rememberTextMeasurer()
    val viewer = content.you
    val players = remember(content) { content.players.associateBy { it.accountId } }
    val teams = remember(content) { players.mapValues { it.value.team } }
    val lead = view == GraphView.SoulsLead
    val curve = shownCurve(timeline, view, viewer)
    val average = averageCurve(timeline, view)
    val scale = remember(timeline, view, curve, average) {
        if (lead) leadScale(timeline.lead.points.maxOfOrNull { abs(it.value) } ?: 0.0)
        else amountScale(listOfNotNull(curve, average).flatMap { it.points }.maxOfOrNull { it.value } ?: 0.0)
    }
    val marks = remember(timeline, subject) { marksOf(timeline, subject?.accountId) }
    val bands = remember(timeline, subject) { deadBands(timeline, subject?.accountId) }
    val reveal = rememberReveal(timeline to view)
    var stop by remember(timeline) { mutableStateOf<Int?>(null) }
    val ours = StatsColors.team(content.perspective)
    val theirs = StatsColors.team(content.perspective.other)
    val axisStyle = type.small.copy(color = scheme.outline)
    val description = stringResource(Res.string.stats_graph)
    val objectivesLabel = stringResource(Res.string.stats_objectives_label)
    BoxWithConstraints(modifier.fillMaxWidth().height(TimelineHeight).semantics { contentDescription = description }) {
        val density = LocalDensity.current
        val plotWidth = maxWidth - PlotLeft
        val plotPx = with(density) { plotWidth.toPx() }
        val duration = timeline.durationS
        fun xOf(timeS: Double): Float = plotPx * (timeS / duration).toFloat().coerceIn(0f, 1f)
        fun yOf(value: Double): Dp = ChartTop + ChartHeight * scale.fraction(value).toFloat()
        Canvas(Modifier.fillMaxSize()) {
            val left = PlotLeft.toPx()
            val top = ChartTop.toPx()
            val height = ChartHeight.toPx()
            val bottom = top + height
            fun y(value: Double) = top + height * scale.fraction(value).toFloat()
            measurer.measure(objectivesLabel, axisStyle, maxLines = 1).let { drawText(it, topLeft = Offset(max(0f, 44.dp.toPx() - it.size.width), 17.dp.toPx())) }
            scale.ticks.forEach { tick ->
                val ty = y(tick.value)
                if (tick.zero) drawLine(scheme.surfaceContainerHighest, Offset(left, ty), Offset(left + plotPx, ty), 1.dp.toPx())
                else drawLine(scheme.outlineVariant, Offset(left, ty), Offset(left + plotPx, ty), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
                if (tick.labelled) {
                    val text = axisLabel(tick.value, signed = lead)
                    val layout = measurer.measure(text, axisStyle, maxLines = 1)
                    drawText(layout, topLeft = Offset(44.dp.toPx() - layout.size.width, ty - layout.size.height / 2f))
                }
            }
            bands.forEach { band ->
                val x0 = left + xOf(band.first.toDouble())
                val x1 = left + xOf(band.last.toDouble())
                drawRect(StatsColors.Loss.copy(alpha = 0.07f), Offset(x0, top), Size(max(x1 - x0, 1f), height))
            }
            timeline.objectives.filter { it.guide }.forEach { event ->
                val gx = left + xOf(event.timeS.toDouble())
                drawLine(
                    StatsColors.team(event.takenBy).copy(alpha = 0.5f), Offset(gx, top - GuideRise.toPx()), Offset(gx, bottom), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                )
            }
            fun path(of: MatchCurve, close: Boolean): Path = Path().apply {
                of.segments().forEach { run ->
                    val start = Offset(left + xOf(run.start.timeS.toDouble()), y(run.start.value))
                    moveTo(start.x, start.y)
                    run.segments.forEach { s -> cubicTo(left + xOf(s.c1t), y(s.c1v), left + xOf(s.c2t), y(s.c2v), left + xOf(s.t), y(s.v)) }
                    if (close && run.segments.isNotEmpty()) {
                        lineTo(left + xOf(run.segments.last().t), y(0.0))
                        lineTo(start.x, y(0.0))
                        close()
                    }
                }
            }
            clipRect(left = left - 2.dp.toPx(), right = left + plotPx * reveal.value + 2.dp.toPx()) {
                if (lead) {
                    val zeroY = y(0.0)
                    val area = path(timeline.lead, close = true)
                    val line = path(timeline.lead, close = false)
                    val stroke = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    clipRect(top = 0f, bottom = zeroY) {
                        drawPath(area, ours.copy(alpha = 0.2f))
                        drawPath(line, ours, style = stroke)
                    }
                    clipRect(top = zeroY, bottom = size.height) {
                        drawPath(area, theirs.copy(alpha = 0.2f))
                        drawPath(line, theirs, style = stroke)
                    }
                } else {
                    average?.let { drawPath(path(it, close = false), scheme.outline, style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx())))) }
                    curve?.let { drawPath(path(it, close = false), scheme.onSurface, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                }
            }
            val axisTop = AxisTop.toPx()
            axisTimes(duration, plotPx, AxisLabelGap.toPx()).forEach { time ->
                val layout = measurer.measure(StatsFormat.duration(time), axisStyle, maxLines = 1)
                drawText(layout, topLeft = Offset(left + xOf(time.toDouble()) - layout.size.width / 2f, axisTop))
            }
            val end = measurer.measure(StatsFormat.duration(duration), axisStyle, maxLines = 1)
            drawText(end, topLeft = Offset(left + plotPx - end.size.width, axisTop))
        }
        if (lead) {
            AheadLabel(content.perspective, HideoutIcons.TriangleUp, Modifier.offset(x = PlotLeft + 10.dp, y = ChartTop + 2.dp))
            AheadLabel(content.perspective.other, HideoutIcons.TriangleDown, Modifier.offset(x = PlotLeft + 10.dp, y = ChartTop + ChartHeight - 2.dp).layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, -placeable.height) }
            })
        }
        val spacing = with(density) { ObjectiveLine.toPx() }
        placeObjectives(timeline.objectives, duration, plotPx, spacing).forEach { placed ->
            val said = "${StatsLabels.objectiveLine(placed.event, content.perspective, content.spectator)}${StatsLabels.Separator}${StatsFormat.duration(placed.event.timeS)}"
            ObjectiveTile(
                placed.event, ObjectiveTileSize, 13.dp,
                Modifier.offset(x = PlotLeft + with(density) { placed.x.toDp() } - ObjectiveTileSize / 2, y = 2.dp + ObjectiveLine * placed.line)
                    .semantics { contentDescription = said },
            )
        }
        val plotted = plottedMarks(marks, curve)
        val markXs = plotted.map { (mark, _) -> xOf(mark.timeS.toDouble()) }
        val levels = stackLevels(markXs, with(density) { MarkerStep.toPx() })
        plotted.forEachIndexed { i, (mark, value) ->
            val other = players[if (mark.kill) mark.death.victim else mark.death.killer ?: -1L]
            val said = "${StatsLabels.markLine(mark, players)}${StatsLabels.Separator}${StatsFormat.duration(mark.timeS)}"
            MarkPortrait(
                other, mark.kill, MarkerSize, 2.dp, 2.dp, scheme.surfaceContainer,
                Modifier.offset(x = PlotLeft + with(density) { markXs[i].toDp() } - MarkerSize / 2, y = yOf(value) - MarkerSize / 2 - MarkerStep * levels[i])
                    .semantics { contentDescription = said },
            )
        }
        val readout = stop?.let { minuteReadout(timeline, view, viewer, subject?.accountId, content.perspective, teams, it) }
        if (readout != null) {
            val cx = xOf(readout.timeS.toDouble())
            val dotColor = when {
                !lead -> scheme.onSurface
                (readout.value ?: 0.0) < 0 -> theirs
                else -> ours
            }
            Canvas(Modifier.fillMaxSize()) {
                val x = PlotLeft.toPx() + cx
                drawLine(scheme.onSurface.copy(alpha = 0.34f), Offset(x, 0f), Offset(x, (ChartTop + ChartHeight).toPx()), 1.dp.toPx())
                readout.value?.let { value ->
                    val center = Offset(x, yOf(value).toPx())
                    drawCircle(scheme.surfaceContainer, 7.5.dp.toPx(), center)
                    drawCircle(dotColor, 5.dp.toPx(), center)
                }
            }
            MinuteTooltip(readout, view, lead, content, players, Modifier.layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = TooltipWidth.roundToPx()))
                val start = tooltipStart(cx, plotPx, placeable.width.toFloat(), TooltipGap.toPx(), PlotLeft.toPx(), TooltipRoom.toPx())
                layout(0, 0) { placeable.place(PlotLeft.roundToPx() + start.toInt(), (ChartTop + 4.dp).roundToPx()) }
            })
        }
        Box(
            Modifier.offset(x = PlotLeft).size(plotWidth, ChartTop + ChartHeight).pointerInput(timeline, plotPx) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        stop = if (event.type == PointerEventType.Exit) null
                        else hoverStop(event.changes.first().position.x.toDouble() / plotPx * duration, duration)
                    }
                }
            },
        )
    }
}

private fun axisLabel(value: Double, signed: Boolean): String {
    if (value == 0.0) return "0"
    val size = abs(value)
    val text = if (size >= 1000) {
        val k = size / 1000
        if (k % 1.0 == 0.0) "${k.toLong()}k" else "${StatsFormat.decimal(k)}k"
    } else {
        StatsFormat.count(size)
    }
    return when {
        !signed -> text
        value > 0 -> "+$text"
        else -> "−$text"
    }
}

@Composable
private fun AheadLabel(team: Team, icon: ImageVector, modifier: Modifier) {
    val color = StatsColors.team(team)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        HideoutIcon(icon, null, size = 12.dp, tint = color)
        Text(stringResource(Res.string.stats_legend_ahead, StatsLabels.team(team)), style = statsType().small.copy(fontWeight = HideoutWeights.Title), color = color, maxLines = 1)
    }
}

@Composable
private fun MinuteTooltip(readout: MinuteReadout, view: GraphView, lead: Boolean, content: MatchDetailContent, players: Map<Long, MatchPlayer>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val value = readout.value
    val leader = when {
        !lead || value == null -> null
        StatsFormat.signedThousands(value) == "0" -> null
        value > 0 -> content.perspective
        else -> content.perspective.other
    }
    val headline = when {
        value == null -> StatsLabels.none()
        lead && leader == null -> stringResource(Res.string.stats_tooltip_even)
        lead -> StatsFormat.signedThousands(value)
        else -> StatsFormat.thousands(value)
    }
    val sub = when {
        lead && leader != null -> stringResource(Res.string.stats_legend_ahead, StatsLabels.team(leader))
        lead -> stringResource(Res.string.stats_tooltip_lead)
        else -> stringResource(Res.string.stats_tooltip_average, stringResource(if (view == GraphView.YourSouls) Res.string.stats_graph_souls else Res.string.stats_graph_damage), StatsLabels.thousands(readout.average))
    }
    Column(
        modifier
            .testTag(MatchTimelineTags.Tooltip)
            .width(TooltipWidth)
            .dropShadow(RoundedCornerShape(14.dp), Shadow(radius = 28.dp, color = Color.Black.copy(alpha = 0.5f), offset = DpOffset(0.dp, 12.dp)))
            .clip(RoundedCornerShape(14.dp))
            .background(scheme.surfaceContainerHighest)
            .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(StatsFormat.duration(readout.timeS), style = type.meta.copy(fontWeight = HideoutWeights.Title, fontFeatureSettings = "tnum"), color = scheme.onSurface, maxLines = 1)
            Spacer(Modifier.weight(1f).width(10.dp))
            Text(
                headline, style = type.meta.resized(13.5.sp).copy(fontWeight = HideoutWeights.Heavy, fontFeatureSettings = "tnum"),
                color = leader?.let(StatsColors::team) ?: scheme.onSurface, maxLines = 1,
            )
        }
        Text(sub, style = type.note, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 1.dp))
        if (!readout.quiet) {
            Column(
                Modifier.padding(top = 8.dp).fillMaxWidth().drawBehind { drawLine(scheme.onSurface.copy(alpha = 0.08f), Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }.padding(top = 9.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val rowStyle = type.caption.copy(color = scheme.onSurfaceVariant)
                readout.kills?.let { (ours, theirs) ->
                    TooltipRow(stringResource(Res.string.stats_tooltip_kills), rowStyle, trailing = buildAnnotatedString {
                        withStyle(SpanStyle(color = StatsColors.team(content.perspective), fontWeight = HideoutWeights.Heavy)) { append("$ours") }
                        append(" – ")
                        withStyle(SpanStyle(color = StatsColors.team(content.perspective.other), fontWeight = HideoutWeights.Heavy)) { append("$theirs") }
                    })
                }
                readout.marks.forEach { mark ->
                    val other = players[if (mark.kill) mark.death.victim else mark.death.killer ?: -1L]
                    TooltipRow(StatsLabels.markLine(mark, players), rowStyle, time = mark.timeS) {
                        MarkPortrait(other, mark.kill, 20.dp, 1.5.dp, 0.dp, Color.Transparent)
                    }
                }
                readout.objectives.forEach { event ->
                    TooltipRow(StatsLabels.objectiveLine(event, content.perspective, content.spectator), rowStyle, time = event.timeS) {
                        ObjectiveTile(event, 20.dp, 12.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TooltipRow(text: String, style: TextStyle, time: Int? = null, trailing: AnnotatedString? = null, leading: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.invoke()
        Text(text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        when {
            trailing != null -> Text(trailing, style = style.copy(fontFeatureSettings = "tnum"), maxLines = 1)
            time != null -> Text(StatsFormat.duration(time), style = style.copy(color = MaterialTheme.colorScheme.outline, fontFeatureSettings = "tnum"), maxLines = 1)
        }
    }
}
