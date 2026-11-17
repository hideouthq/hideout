package gg.hideouthq.feature.stats.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.HeaderRank
import gg.hideouthq.feature.stats.StatsHeader
import gg.hideouthq.feature.stats.SyncStatus
import gg.hideouthq.feature.stats.TrackedTotals
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_all_time
import gg.hideouthq.resources.stats_all_time_note
import gg.hideouthq.resources.stats_all_time_tracked
import gg.hideouthq.resources.stats_calibrating
import gg.hideouthq.resources.stats_calibrating_open
import gg.hideouthq.resources.stats_fetching
import gg.hideouthq.resources.stats_fetching_progress
import gg.hideouthq.resources.stats_last_match
import gg.hideouthq.resources.stats_last_ranked
import gg.hideouthq.resources.stats_loss_streak
import gg.hideouthq.resources.stats_mostly
import gg.hideouthq.resources.stats_rank_held
import gg.hideouthq.resources.stats_ranked_label
import gg.hideouthq.resources.stats_refresh
import gg.hideouthq.resources.stats_source_steam
import gg.hideouthq.resources.stats_strip_heroes
import gg.hideouthq.resources.stats_strip_kda
import gg.hideouthq.resources.stats_strip_losses
import gg.hideouthq.resources.stats_strip_matches
import gg.hideouthq.resources.stats_strip_played
import gg.hideouthq.resources.stats_strip_win_rate
import gg.hideouthq.resources.stats_strip_wins
import gg.hideouthq.resources.stats_tiers
import gg.hideouthq.resources.stats_top_percent
import gg.hideouthq.resources.stats_tracked_since
import gg.hideouthq.resources.stats_updated
import gg.hideouthq.resources.stats_win_streak
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable
internal fun StatsHeaderCard(header: StatsHeader, status: SyncStatus, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    ArtSurface(header.mostPlayed?.artUrl, StatsColors.HeaderShade, modifier.fillMaxWidth().clip(StatsShapes.Header)) {
        Column(Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth < 760.dp
                if (stacked) {
                    Column(Modifier.padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Identity(header, status, onRefresh)
                        header.rank?.let { RankPanel(it) }
                    }
                } else {
                    Row(Modifier.padding(start = 28.dp, top = 28.dp, end = 28.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Identity(header, status, onRefresh, Modifier.weight(1f))
                        header.rank?.let { RankPanel(it) }
                    }
                }
            }
            TotalsStrip(header.totals)
        }
    }
}

@Composable
private fun Identity(header: StatsHeader, status: SyncStatus, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val type = statsType()
    val name = StatsLabels.playerName(header.player)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        SelfAvatar(header.player.initial, header.player.avatarUrl, StatsDimens.SelfAvatar)
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = type.name, color = StatsColors.OnArt, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                MetaText(stringResource(Res.string.stats_source_steam))
                header.lastMatch?.let {
                    Dot()
                    MetaText(stringResource(Res.string.stats_last_match, StatsLabels.moment(it)))
                }
                val progress = status.progress
                when {
                    status.refreshing || progress != null -> {
                        Dot()
                        MetaText(if (progress != null) stringResource(Res.string.stats_fetching_progress, progress.covered, progress.of) else stringResource(Res.string.stats_fetching))
                    }
                    header.updated != null -> {
                        Dot()
                        MetaText(stringResource(Res.string.stats_updated, StatsLabels.moment(header.updated)))
                    }
                }
                RefreshButton(spinning = status.refreshing, onClick = onRefresh)
            }
            FlowRow(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                header.mostPlayed?.let { hero ->
                    GlassChip(stringResource(Res.string.stats_mostly, StatsLabels.heroName(hero))) {
                        HeroTile(hero, 20.dp, type.you.resized(type.you.fontSize * (9.5f / 10.5f)))
                    }
                }
                header.winStreak?.let { GlassChip(stringResource(Res.string.stats_win_streak, it)) { HideoutIcon(HideoutIcons.Streak, null, size = 15.dp, tint = StatsColors.OnArtChip) } }
                header.lossStreak?.let { GlassChip(stringResource(Res.string.stats_loss_streak, it)) { HideoutIcon(HideoutIcons.Streak, null, size = 15.dp, tint = StatsColors.OnArtChip) } }
                header.trackedSince?.let { GlassChip(stringResource(Res.string.stats_tracked_since, StatsLabels.moment(it))) { HideoutIcon(HideoutIcons.Since, null, size = 15.dp, tint = StatsColors.OnArtChip) } }
            }
        }
    }
}

@Composable
private fun MetaText(text: String) {
    Text(text, style = statsType().meta, color = StatsColors.OnArtVariant, maxLines = 1)
}

@Composable
private fun Dot() {
    Box(Modifier.size(3.dp).graphicsLayer { alpha = 0.6f }.clip(CircleShape).background(StatsColors.OnArtVariant))
}

@Composable
private fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val fill by animateColorAsState(if (states.hovered) StatsColors.ArtButtonHover else Color.Transparent, HideoutTheme.motion.effectsDefault(), label = "refreshHover")
    val spin = rememberInfiniteTransition(label = "refreshSpin")
    val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(900), RepeatMode.Restart), label = "refreshTurn")
    val reduced = HideoutTheme.motion.reduced
    val label = stringResource(Res.string.stats_refresh)
    Box(
        Modifier
            .offset(x = (-4).dp)
            .size(30.dp)
            .clip(CircleShape)
            .drawBehind { drawRect(fill) }
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .semantics { contentDescription = label }
            .hideoutClickable(source, enabled = !spinning, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        HideoutIcon(
            HideoutIcons.Refresh, contentDescription = null, size = 16.dp, tint = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.graphicsLayer { rotationZ = if (spinning && !reduced) turn else 0f },
        )
    }
}

@Composable
private fun GlassChip(text: String, leading: @Composable () -> Unit) {
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(StatsColors.ArtChip).padding(start = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Text(text, style = HideoutTheme.typography.labelChip, color = StatsColors.OnArtChip, maxLines = 1)
    }
}

@Composable
internal fun RankPanel(rank: HeaderRank) {
    val type = statsType()
    Row(
        Modifier.clip(StatsShapes.RankGlass).background(StatsColors.ArtGlass).padding(start = 14.dp, top = 14.dp, end = 22.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RankBadge(rank.rank, StatsDimens.RankBadge)
        Column {
            Text(stringResource(Res.string.stats_ranked_label).uppercase(), style = type.eyebrow, color = StatsColors.OnArtLabel)
            Text(
                rank.rank?.label ?: stringResource(Res.string.stats_ranked_label), style = type.rankName, color = StatsColors.OnArt,
                maxLines = 1, modifier = Modifier.padding(top = 2.dp),
            )
            val calibration = rank.calibration
            if (calibration != null) {
                Text(
                    calibration.of?.let { stringResource(Res.string.stats_calibrating, calibration.played, it) }
                        ?: stringResource(Res.string.stats_calibrating_open, calibration.played),
                    style = type.caption, color = StatsColors.OnArtVariant, modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Pips(rank.rank?.subrank ?: 0, Modifier.padding(top = 10.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val change = rank.change
                if (change != null) {
                    if (change != 0) {
                        val up = change > 0
                        val color = if (up) StatsColors.Win else StatsColors.Loss
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                            HideoutIcon(if (up) HideoutIcons.ArrowUp else HideoutIcons.ArrowDown, null, size = 14.dp, tint = color)
                            Text(pluralStringResource(Res.plurals.stats_tiers, abs(change), abs(change)), style = type.caption.copy(fontWeight = type.rankName.fontWeight), color = color)
                        }
                    } else {
                        Text(stringResource(Res.string.stats_rank_held), style = type.caption, color = StatsColors.OnArtVariant)
                    }
                    Text(StatsLabels.periodPhrase(rank.changePeriod), style = type.caption, color = StatsColors.OnArtVariant)
                } else {
                    rank.lastRanked?.let { Text(stringResource(Res.string.stats_last_ranked, StatsLabels.moment(it)), style = type.caption, color = StatsColors.OnArtVariant, maxLines = 1) }
                }
                rank.topPercent?.let {
                    if (change != null || rank.lastRanked != null) Dot()
                    Text(stringResource(Res.string.stats_top_percent, it), style = type.caption, color = StatsColors.OnArtVariant)
                }
            }
        }
    }
}

@Composable
private fun Pips(lit: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(6) { index ->
            Box(Modifier.size(22.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(if (index < lit) StatsColors.Gold else StatsColors.PipOff))
        }
    }
}

@Composable
private fun TotalsStrip(totals: TrackedTotals) {
    val type = statsType()
    val note = stringResource(Res.string.stats_all_time_note)
    val heading = stringResource(Res.string.stats_all_time).uppercase()
    val tracked = stringResource(Res.string.stats_all_time_tracked)
    val cells = listOf(
        StatsLabels.count(totals.matches) to stringResource(Res.string.stats_strip_matches),
        StatsLabels.count(totals.wins) to stringResource(Res.string.stats_strip_wins),
        StatsLabels.count(totals.losses) to stringResource(Res.string.stats_strip_losses),
        StatsLabels.percent(totals.winRate) to stringResource(Res.string.stats_strip_win_rate),
        StatsLabels.decimal(totals.kda) to stringResource(Res.string.stats_strip_kda),
        StatsLabels.played(totals.playedSeconds) to stringResource(Res.string.stats_strip_played),
        StatsLabels.count(totals.heroes) to stringResource(Res.string.stats_strip_heroes),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val (cellWidth, labelWidth) = remember(cells, heading, tracked, type, density) {
        with(density) {
            val widest = cells.maxOf { (value, label) -> maxOf(measurer.measure(value, type.stripValue).size.width, measurer.measure(label, type.rowSub).size.width) }
            val label = maxOf(measurer.measure(heading, type.stripLabel).size.width, measurer.measure(tracked, type.small).size.width)
            (widest.toDp() + CellPadding * 2 + 1.dp) to (label.toDp() + LabelPadding + 1.dp)
        }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRect(StatsColors.ArtStrip)
                drawRect(StatsColors.ArtStripTop, size = size.copy(height = 1.dp.toPx()))
            }
            .padding(horizontal = 12.dp, vertical = 14.dp),
    ) {
        val label: @Composable () -> Unit = {
            HideoutTooltip(note) {
                Column(Modifier.padding(start = 16.dp, end = 20.dp).semantics { contentDescription = note }, verticalArrangement = Arrangement.Center) {
                    Text(heading, style = type.stripLabel, color = StatsColors.OnArtLabel, maxLines = 1, softWrap = false)
                    Text(tracked, style = type.small, color = StatsColors.OnArtLabel.copy(alpha = 0.45f), maxLines = 1, softWrap = false)
                }
            }
        }
        if (maxWidth - labelWidth >= cellWidth * cells.size) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                label()
                cells.forEach { (value, name) -> TotalCell(value, name, Modifier.weight(1f)) }
            }
        } else {
            val perRow = (maxWidth / cellWidth).toInt().coerceIn(1, cells.size)
            val rows = (cells.size + perRow - 1) / perRow
            val even = (cells.size + rows - 1) / rows
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                label()
                cells.chunked(even).forEach { row ->
                    Row {
                        row.forEach { (value, name) -> TotalCell(value, name, Modifier.weight(1f)) }
                        repeat(even - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalCell(value: String, label: String, modifier: Modifier) {
    val type = statsType()
    Column(
        modifier.drawBehind { drawRect(StatsColors.ArtStripDivider, size = size.copy(width = 1.dp.toPx())) }.padding(horizontal = CellPadding),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = type.stripValue, color = StatsColors.OnArt, maxLines = 1, softWrap = false)
        Text(label, style = type.rowSub, color = StatsColors.OnArtLabel, maxLines = 1, softWrap = false)
    }
}

private val CellPadding = 16.dp
private val LabelPadding = 36.dp

@Composable
internal fun CompactHeader(header: StatsHeader, modifier: Modifier = Modifier) {
    val type = statsType()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        SelfAvatar(header.player.initial, header.player.avatarUrl, 32.dp)
        Text(StatsLabels.playerName(header.player), style = type.cardTitle, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        header.rank?.rank?.let { rank ->
            RankBadge(rank, 28.dp)
            Text(rank.label, style = type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}
