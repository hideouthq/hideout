package gg.hideouthq.feature.stats.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.EndAlignedFlow
import gg.hideouthq.feature.stats.ItemVisual
import gg.hideouthq.feature.stats.MatchDetailContent
import gg.hideouthq.feature.stats.MatchPlayer
import gg.hideouthq.feature.stats.MatchStat
import gg.hideouthq.feature.stats.MatchTeam
import gg.hideouthq.feature.stats.ScoreColumns
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_col_accuracy
import gg.hideouthq.resources.stats_col_damage
import gg.hideouthq.resources.stats_col_damage_min
import gg.hideouthq.resources.stats_col_denies
import gg.hideouthq.resources.stats_col_headshots
import gg.hideouthq.resources.stats_col_healing
import gg.hideouthq.resources.stats_col_kda
import gg.hideouthq.resources.stats_col_kill_share
import gg.hideouthq.resources.stats_col_last_hits_short
import gg.hideouthq.resources.stats_col_level
import gg.hideouthq.resources.stats_col_objective_damage
import gg.hideouthq.resources.stats_col_player
import gg.hideouthq.resources.stats_col_souls
import gg.hideouthq.resources.stats_col_souls_min
import gg.hideouthq.resources.stats_col_taken
import gg.hideouthq.resources.stats_item_souls
import gg.hideouthq.resources.stats_lost
import gg.hideouthq.resources.stats_match_description
import gg.hideouthq.resources.stats_names_failed
import gg.hideouthq.resources.stats_player_level
import gg.hideouthq.resources.stats_row_closed
import gg.hideouthq.resources.stats_row_open
import gg.hideouthq.resources.stats_score_combat
import gg.hideouthq.resources.stats_score_economy
import gg.hideouthq.resources.stats_scoreboard
import gg.hideouthq.resources.stats_scoreboard_hint
import gg.hideouthq.resources.stats_tab_overview
import gg.hideouthq.resources.stats_team_summary
import gg.hideouthq.resources.stats_unknown_item
import gg.hideouthq.resources.stats_won
import gg.hideouthq.resources.stats_you
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val SideBySide = 1_040.dp
private val ColumnGap = 10.dp
private val Portrait = 36.dp
private val Chevron = 16.dp
private const val ItemSlots = 12

@Composable
internal fun MatchScoreboard(
    content: MatchDetailContent,
    columns: ScoreColumns,
    onColumns: (ScoreColumns) -> Unit,
    open: Long?,
    onToggle: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val labels = mapOf(
        ScoreColumns.Overview to stringResource(Res.string.stats_tab_overview),
        ScoreColumns.Combat to stringResource(Res.string.stats_score_combat),
        ScoreColumns.Economy to stringResource(Res.string.stats_score_economy),
    )
    val players = content.players
    StatsCard(modifier.fillMaxWidth()) {
        EndAlignedFlow(Modifier.fillMaxWidth().heightIn(min = StatsDimens.CardHeaderHeight).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), gap = 10.dp, lineGap = 6.dp) {
            Text(stringResource(Res.string.stats_scoreboard), style = type.cardTitle, color = scheme.onSurface, maxLines = 1, softWrap = false)
            Text(stringResource(Res.string.stats_scoreboard_hint), style = type.caption, color = scheme.outline, maxLines = 1, softWrap = false)
            SegmentedControl(ScoreColumns.entries, columns, onColumns, { labels.getValue(it) }, style = SegmentedStyle.Card)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val half: @Composable (MatchTeam, Modifier) -> Unit = { team, halfModifier ->
                TeamHalf(team, columns, open, onToggle, content.names, players.size, halfModifier)
            }
            if (maxWidth >= SideBySide && content.teams.size == 2) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    half(content.teams[0], Modifier.weight(1f).padding(start = 2.dp, end = 12.dp, bottom = 2.dp))
                    val line = scheme.outlineVariant
                    half(
                        content.teams[1],
                        Modifier.weight(1f).drawBehind { drawLine(line, Offset(0.5f, 0f), Offset(0.5f, size.height), 1.dp.toPx()) }.padding(start = 12.dp, end = 2.dp, bottom = 2.dp),
                    )
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    content.teams.forEach { team -> half(team, Modifier.fillMaxWidth().padding(horizontal = 2.dp)) }
                }
            }
        }
        if (content.names is Loadable.Failed) SectionNote(stringResource(Res.string.stats_names_failed), onRetry, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
}

private class ScoreColumn(val title: StringResource, val width: Dp?, val minWidth: Dp = 0.dp, val titleEnd: Boolean = true, val cell: @Composable (MatchPlayer, Modifier) -> Unit)

private fun figure(title: StringResource, width: Dp, stat: MatchStat) = ScoreColumn(title, width) { player, modifier -> Figure(player, stat, modifier) }

@Composable
private fun scoreColumns(columns: ScoreColumns, team: MatchTeam): List<ScoreColumn> = when (columns) {
    ScoreColumns.Overview -> listOf(
        ScoreColumn(Res.string.stats_col_kda, 74.dp, titleEnd = false) { player, modifier ->
            Text(
                kdaText(player.kills, player.deaths, player.assists), style = statsType().kda.resized(12.sp), color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End, maxLines = 1, modifier = modifier,
            )
        },
        figure(Res.string.stats_col_souls, 46.dp, MatchStat.Souls),
        ScoreColumn(Res.string.stats_col_damage, null, minWidth = 96.dp, titleEnd = false) { player, modifier -> DamageBar(player, StatsColors.team(team.team), modifier) },
        figure(Res.string.stats_col_healing, 46.dp, MatchStat.Healing),
    )
    ScoreColumns.Combat -> listOf(
        figure(Res.string.stats_col_kill_share, 56.dp, MatchStat.KillShare),
        figure(Res.string.stats_col_damage_min, 58.dp, MatchStat.DamagePerMinute),
        figure(Res.string.stats_col_taken, 50.dp, MatchStat.DamageTaken),
        figure(Res.string.stats_col_accuracy, 62.dp, MatchStat.Accuracy),
        figure(Res.string.stats_col_headshots, 70.dp, MatchStat.Headshots),
    )
    ScoreColumns.Economy -> listOf(
        figure(Res.string.stats_col_souls_min, 66.dp, MatchStat.SoulsPerMinute),
        figure(Res.string.stats_col_last_hits_short, 58.dp, MatchStat.LastHits),
        figure(Res.string.stats_col_denies, 50.dp, MatchStat.Denies),
        figure(Res.string.stats_col_objective_damage, 62.dp, MatchStat.ObjectiveDamage),
        figure(Res.string.stats_col_level, 38.dp, MatchStat.Level),
    )
}

@Composable
internal fun statText(stat: MatchStat, value: Double?): String = when (stat) {
    MatchStat.KillShare, MatchStat.Accuracy, MatchStat.Headshots -> StatsLabels.percent(value)
    MatchStat.DamagePerMinute, MatchStat.SoulsPerMinute, MatchStat.LastHits, MatchStat.Denies, MatchStat.Level, MatchStat.Creeps, MatchStat.Neutrals -> StatsLabels.count(value)
    MatchStat.TimeDead, MatchStat.LongestLife -> StatsLabels.duration(value?.toInt())
    else -> StatsLabels.thousands(value)
}

@Composable
private fun Figure(player: MatchPlayer, of: MatchStat, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val stat = player.stat(of)
    Text(
        statText(of, stat.value),
        style = statsType().value.resized(13.sp).copy(fontWeight = if (stat.best) HideoutWeights.Heavy else HideoutWeights.Medium),
        color = if (stat.best) scheme.onSurface else scheme.onSurfaceVariant,
        textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = modifier,
    )
}

@Composable
private fun DamageBar(player: MatchPlayer, color: Color, modifier: Modifier) {
    val stat = player.stat(MatchStat.HeroDamage)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ShareBar((player.damageShare ?: 0.0).toFloat(), color, Modifier.weight(1f))
        Text(
            StatsLabels.thousands(stat.value),
            style = statsType().value.resized(13.sp).copy(fontWeight = if (stat.best) HideoutWeights.Heavy else HideoutWeights.Label),
            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.width(38.dp),
        )
    }
}

@Composable
private fun RowScope.Cells(columns: List<ScoreColumn>, cell: @Composable (ScoreColumn, Modifier) -> Unit) {
    columns.forEach { column ->
        Spacer(Modifier.width(ColumnGap))
        cell(column, if (column.width != null) Modifier.width(column.width) else Modifier.weight(1f).widthIn(min = column.minWidth))
    }
}

@Composable
private fun TeamHalf(team: MatchTeam, columnSet: ScoreColumns, open: Long?, onToggle: (Long) -> Unit, names: Loadable<Unit>, playerCount: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val columns = scoreColumns(columnSet, team)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 10.dp, end = 10.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(StatsColors.team(team.team)))
            Text(StatsLabels.team(team.team), style = type.teamTitle.resized(15.sp), color = scheme.onSurface, maxLines = 1)
            team.won?.let { won -> StatChip(stringResource(if (won) Res.string.stats_won else Res.string.stats_lost), if (won) ChipTone.Up else ChipTone.Neutral, height = 24.dp) }
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(Res.string.stats_team_summary, StatsLabels.count(team.kills), StatsLabels.thousands(team.souls)),
                style = type.rowSub, color = scheme.outline, maxLines = 1,
            )
        }
        val header = type.small.copy(fontWeight = HideoutWeights.Label)
        Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(Portrait + ColumnGap))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.stats_col_player), style = header, color = scheme.outline, maxLines = 1, modifier = Modifier.weight(1f))
                Cells(columns) { column, cellModifier ->
                    Text(stringResource(column.title), style = header, color = scheme.outline, maxLines = 1, softWrap = false, textAlign = if (column.titleEnd) TextAlign.End else TextAlign.Start, modifier = cellModifier)
                }
            }
            Spacer(Modifier.width(ColumnGap + Chevron))
        }
        team.players.forEach { player ->
            val isOpen = open == player.accountId
            PlayerRow(player, columns, isOpen, names) { onToggle(player.accountId) }
            if (isOpen) PlayerPanel(player, playerCount, Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun PlayerRow(player: MatchPlayer, columns: List<ScoreColumn>, isOpen: Boolean, names: Loadable<Unit>, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val shape = if (isOpen) RoundedCornerShape(16.dp, 16.dp, 0.dp, 0.dp) else StatsShapes.Row
    val fill by animateColorAsState(
        when {
            isOpen || states.hovered -> scheme.surfaceContainerHigh
            player.isYou -> HideoutTheme.colors.selectedRow
            else -> scheme.surfaceContainerHigh.copy(alpha = 0f)
        },
        HideoutTheme.motion.effectsDefault(), label = "scoreRow",
    )
    val turn by animateFloatAsState(if (isOpen) 180f else 0f, HideoutTheme.motion.effectsDefault(), label = "chevron")
    val description = stringResource(
        Res.string.stats_match_description, StatsLabels.playerName(player.player, names), StatsLabels.heroName(player.hero),
        StatsLabels.count(player.kills), StatsLabels.count(player.deaths), StatsLabels.count(player.assists),
    )
    val state = stringResource(if (isOpen) Res.string.stats_row_open else Res.string.stats_row_closed)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(fill)
            .focusRing(states.focusVisible, shape)
            .hideoutClickable(source, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = state
            }
            .padding(start = 6.dp, top = 7.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroTile(player.hero, Portrait, type.small.copy(fontWeight = HideoutWeights.Heavy))
        Spacer(Modifier.width(ColumnGap))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                NameCell(player, names, Modifier.weight(1f))
                Cells(columns) { column, cellModifier -> column.cell(player, cellModifier) }
            }
            ItemSlotRow(player.items, Modifier.height(22.dp))
        }
        Spacer(Modifier.width(ColumnGap))
        HideoutIcon(HideoutIcons.ChevronDown, null, size = Chevron, tint = if (isOpen) scheme.onSurface else scheme.outline, modifier = Modifier.rotate(turn))
    }
}

@Composable
private fun NameCell(player: MatchPlayer, names: Loadable<Unit>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (player.player.name.isEmpty() && names is Loadable.Loading) {
                LoadingLine(80.dp, height = 12.dp)
            } else {
                Text(
                    StatsLabels.playerName(player.player, names), style = type.rowTitle.resized(14.sp), color = scheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (player.isYou) TinyChip(stringResource(Res.string.stats_you), scheme.primary, scheme.onPrimary)
        }
        Text(
            listOfNotNull(StatsLabels.heroName(player.hero), player.level?.let { stringResource(Res.string.stats_player_level, it) }).joinToString(StatsLabels.Separator),
            style = type.rowSub, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ItemSlotRow(items: List<ItemVisual>, modifier: Modifier) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item -> HideoutTooltip(itemTip(item)) { MatchItemTile(item, 20.dp, 6.dp) } }
        repeat(ItemSlots - items.size) {
            Box(Modifier.size(20.dp).drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(empty, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), CornerRadius(6.dp.toPx() - stroke / 2), style = Stroke(stroke))
            })
        }
    }
}

@Composable
internal fun itemTip(item: ItemVisual): String = listOfNotNull(
    item.name.takeIf { it.isNotEmpty() } ?: stringResource(Res.string.stats_unknown_item),
    item.cost?.let { stringResource(Res.string.stats_item_souls, StatsFormat.count(it)) },
).joinToString(StatsLabels.Separator)

internal fun Modifier.tintedTile(fill: Color?, ring: Color, radius: Dp, ringWidth: Dp = 1.dp, ringRadius: Dp = radius - ringWidth / 2): Modifier = drawBehind {
    if (fill != null) drawRoundRect(fill, cornerRadius = CornerRadius(radius.toPx()))
    val stroke = ringWidth.toPx()
    drawRoundRect(ring, Offset(stroke / 2, stroke / 2), Size(size.width - stroke, size.height - stroke), CornerRadius(ringRadius.toPx()), style = Stroke(stroke))
}

@Composable
internal fun MatchItemTile(
    item: ItemVisual, size: Dp, radius: Dp, gone: Boolean = false, base: Color = MaterialTheme.colorScheme.surfaceContainer, tint: Float = 0.17f,
    ringAlpha: Float = 0.36f, ringWidth: Dp = 1.dp, ringRadius: Dp = radius - ringWidth / 2, letters: TextUnit = (size.value * 0.4f).sp,
) {
    val scheme = MaterialTheme.colorScheme
    val color = if (gone) scheme.outline else StatsColors.category(item.category)
    val image = item.iconUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
    val art = image?.loaded == true
    val ring = when {
        gone -> scheme.surfaceContainerHighest
        art -> color
        else -> color.copy(alpha = ringAlpha)
    }
    Box(Modifier.size(size).tintedTile(if (gone) null else lerp(base, color, tint), ring, radius, ringWidth, ringRadius), contentAlignment = Alignment.Center) {
        if (!art) Text(item.initials, style = statsType().itemCode.resized(letters), color = color, maxLines = 1)
        if (image != null) {
            Image(
                image.painter, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = if (gone) GoneArt else null, alpha = if (gone) 0.5f else 1f,
                modifier = Modifier.fillMaxSize().padding(ringWidth).clip(RoundedCornerShape(ringRadius - ringWidth / 2)).then(image.layoutModifier),
            )
        }
    }
}

private val GoneArt = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
