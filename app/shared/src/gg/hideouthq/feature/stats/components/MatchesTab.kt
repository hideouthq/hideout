package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.MatchDay
import gg.hideouthq.feature.stats.MatchRowUi
import gg.hideouthq.feature.stats.MatchesContent
import gg.hideouthq.feature.stats.RankChange
import gg.hideouthq.feature.stats.ResultFilter
import gg.hideouthq.feature.stats.StatsActions
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_abandoned
import gg.hideouthq.resources.stats_all_heroes
import gg.hideouthq.resources.stats_calibrating_match
import gg.hideouthq.resources.stats_col_damage
import gg.hideouthq.resources.stats_col_hero
import gg.hideouthq.resources.stats_col_kda
import gg.hideouthq.resources.stats_col_last_hits
import gg.hideouthq.resources.stats_col_rank
import gg.hideouthq.resources.stats_col_result
import gg.hideouthq.resources.stats_col_souls
import gg.hideouthq.resources.stats_col_when
import gg.hideouthq.resources.stats_day_summary
import gg.hideouthq.resources.stats_down_to
import gg.hideouthq.resources.stats_hero_filter
import gg.hideouthq.resources.stats_hero_search
import gg.hideouthq.resources.stats_level
import gg.hideouthq.resources.stats_match_details_soon
import gg.hideouthq.resources.stats_matches_count
import gg.hideouthq.resources.stats_matches_summary
import gg.hideouthq.resources.stats_no_matches_message
import gg.hideouthq.resources.stats_no_matches_title
import gg.hideouthq.resources.stats_placed
import gg.hideouthq.resources.stats_rank_held
import gg.hideouthq.resources.stats_result_all
import gg.hideouthq.resources.stats_result_losses
import gg.hideouthq.resources.stats_result_wins
import gg.hideouthq.resources.stats_show_more
import gg.hideouthq.resources.stats_showing
import gg.hideouthq.resources.stats_unranked
import gg.hideouthq.resources.stats_up_to
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal class MatchColumns(val damage: Boolean, val lastHits: Boolean) {
    companion object {
        fun of(width: Dp) = MatchColumns(damage = width >= 860.dp, lastHits = width >= 760.dp)
    }
}

private enum class Slice { Top, Middle, Bottom }

@Composable
private fun CardSlice(slice: Slice, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val corner = StatsShapes.CardRadius
    val shape = when (slice) {
        Slice.Top -> RoundedCornerShape(topStart = corner, topEnd = corner)
        Slice.Middle -> RectangleShape
        Slice.Bottom -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 8.dp, end = 8.dp, top = if (slice == Slice.Top) 6.dp else 0.dp, bottom = if (slice == Slice.Bottom) 8.dp else 0.dp),
    ) { content() }
}

internal fun LazyListScope.matchesTab(content: MatchesContent, heroFilter: Int?, result: ResultFilter, columns: MatchColumns, actions: StatsActions) {
    if (content.total == 0) {
        item("matches-empty") {
            StatsCard(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                MatchFilters(content, heroFilter, result, actions)
                EmptyState(
                    icon = HideoutIcons.StatsMatches,
                    title = stringResource(Res.string.stats_no_matches_title),
                    message = stringResource(Res.string.stats_no_matches_message),
                )
            }
        }
        return
    }
    item("matches-top") {
        CardSlice(Slice.Top) {
            Column {
                MatchFilters(content, heroFilter, result, actions)
                HeaderRow(columns)
            }
        }
    }
    content.days.forEachIndexed { index, day ->
        item("matches-day-$index") { CardSlice(Slice.Middle) { DayHeading(day) } }
        items(day.rows, key = { "match-${it.matchId}" }) { row ->
            CardSlice(Slice.Middle) { MatchTableRow(row, columns) { actions.openMatch(row.matchId) } }
        }
    }
    item("matches-bottom") {
        CardSlice(Slice.Bottom, Modifier.padding(bottom = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(Res.string.stats_showing, content.shown, content.total), style = statsType().rowSub, color = MaterialTheme.colorScheme.outline)
                if (content.shown < content.total) {
                    HideoutButton(stringResource(Res.string.stats_show_more), actions::showMore, variant = ButtonVariant.Tonal, modifier = Modifier.height(40.dp))
                }
            }
        }
    }
}

@Composable
private fun MatchFilters(content: MatchesContent, heroFilter: Int?, result: ResultFilter, actions: StatsActions) {
    val scheme = MaterialTheme.colorScheme
    val allHeroes = stringResource(Res.string.stats_all_heroes)
    val heroes = content.heroOptions + listOfNotNull(content.selectedHero?.takeIf { chosen -> content.heroOptions.none { it.id == chosen.id } })
    val options = listOf(MenuOption(allHeroes, heroFilter == null, searchable = false) { actions.setHeroFilter(null) }) +
        heroes.map { hero -> MenuOption(StatsLabels.heroName(hero), hero.id == heroFilter) { actions.setHeroFilter(hero.id) } }
    val labels = mapOf(
        ResultFilter.All to stringResource(Res.string.stats_result_all),
        ResultFilter.Wins to stringResource(Res.string.stats_result_wins),
        ResultFilter.Losses to stringResource(Res.string.stats_result_losses),
    )
    Row(
        Modifier.fillMaxWidth().height(StatsDimens.CardHeaderHeight).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuPill(
            content.selectedHero?.let { StatsLabels.heroName(it) } ?: allHeroes, HideoutIcons.Person, options, stringResource(Res.string.stats_hero_filter),
            fill = scheme.surfaceContainerHigh, search = stringResource(Res.string.stats_hero_search),
        )
        SegmentedControl(ResultFilter.entries, result, actions::setResult, { labels.getValue(it) }, style = SegmentedStyle.Card)
        Spacer(Modifier.weight(1f))
        val summary = stringResource(
            Res.string.stats_matches_summary, pluralStringResource(Res.plurals.stats_matches_count, content.total, content.total),
            content.wins, content.losses, StatsLabels.percent((content.wins + content.losses).takeIf { it > 0 }?.let { content.wins.toDouble() / it }),
        )
        Text(summary, style = statsType().caption, color = scheme.outline, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
    }
}

@Composable
private fun RowScope.Cells(
    columns: MatchColumns,
    hero: @Composable (Modifier) -> Unit,
    result: @Composable (Modifier) -> Unit,
    kda: @Composable (Modifier) -> Unit,
    souls: @Composable (Modifier) -> Unit,
    damage: @Composable (Modifier) -> Unit,
    lastHits: @Composable (Modifier) -> Unit,
    rank: @Composable (Modifier) -> Unit,
    whenCell: @Composable (Modifier) -> Unit,
) {
    val gap = 12.dp
    hero(Modifier.weight(1.2f))
    Spacer(Modifier.width(gap))
    result(Modifier.weight(0.7f))
    Spacer(Modifier.width(gap))
    kda(Modifier.weight(0.9f))
    Spacer(Modifier.width(gap))
    souls(Modifier.weight(0.65f))
    if (columns.damage) {
        Spacer(Modifier.width(gap))
        damage(Modifier.weight(0.75f))
    }
    if (columns.lastHits) {
        Spacer(Modifier.width(gap))
        lastHits(Modifier.weight(0.8f))
    }
    Spacer(Modifier.width(gap))
    rank(Modifier.weight(0.95f))
    Spacer(Modifier.width(gap))
    whenCell(Modifier.width(70.dp))
}

@Composable
private fun HeaderRow(columns: MatchColumns) {
    Row(Modifier.fillMaxWidth().height(34.dp).padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(4.dp + 12.dp + StatsDimens.HeroTile + 12.dp))
        Cells(
            columns,
            hero = { TableHeaderText(stringResource(Res.string.stats_col_hero), it) },
            result = { TableHeaderText(stringResource(Res.string.stats_col_result), it) },
            kda = { TableHeaderText(stringResource(Res.string.stats_col_kda), it) },
            souls = { TableHeaderText(stringResource(Res.string.stats_col_souls), it) },
            damage = { TableHeaderText(stringResource(Res.string.stats_col_damage), it) },
            lastHits = { TableHeaderText(stringResource(Res.string.stats_col_last_hits), it) },
            rank = { TableHeaderText(stringResource(Res.string.stats_col_rank), it) },
            whenCell = { TableHeaderText(stringResource(Res.string.stats_col_when), it, end = true) },
        )
        Spacer(Modifier.width(12.dp + 16.dp))
    }
}

@Composable
private fun DayHeading(day: MatchDay) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    Row(Modifier.fillMaxWidth().height(36.dp).padding(start = 12.dp, top = 8.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(day.day?.let { StatsLabels.moment(it, capitalized = true) } ?: "", style = type.caption.copy(fontWeight = type.chip.fontWeight), color = scheme.onSurfaceVariant, maxLines = 1)
        Text(stringResource(Res.string.stats_day_summary, day.wins, day.losses), style = type.caption, color = scheme.outline, maxLines = 1)
    }
}

@Composable
private fun MatchTableRow(row: MatchRowUi, columns: MatchColumns, onClick: () -> Unit) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    StatsRow(onClick, Modifier.fillMaxWidth().height(StatsDimens.MatchRowHeight), contentDescription = matchDescription(row)) {
        Spacer(Modifier.width(8.dp))
        ResultBar(row.outcome)
        Spacer(Modifier.width(12.dp))
        HeroTile(row.hero, StatsDimens.HeroTile, type.kda.copy(fontFamily = type.result.fontFamily, fontWeight = type.result.fontWeight))
        Spacer(Modifier.width(12.dp))
        Cells(
            columns,
            hero = { TwoLine(StatsLabels.heroName(row.hero), listOfNotNull(StatsLabels.kind(row.kind), row.level?.let { stringResource(Res.string.stats_level, it) }).joinToString(StatsLabels.Separator), it) },
            result = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(StatsLabels.outcome(row.outcome), style = type.result, color = outcomeColor(row.outcome), maxLines = 1)
                        if (row.abandoned) TinyChip(stringResource(Res.string.stats_abandoned), StatsColors.chip(StatsColors.Loss), StatsColors.Loss)
                    }
                    Text(
                        if (row.detailsPending) stringResource(Res.string.stats_match_details_soon) else StatsLabels.duration(row.durationS),
                        style = type.rowSub, color = scheme.outline, maxLines = 1,
                    )
                }
            },
            kda = { KdaCell(row, it) },
            souls = { Text(StatsLabels.thousands(row.souls), style = type.value, color = scheme.onSurface, maxLines = 1, modifier = it) },
            damage = { Text(StatsLabels.thousands(row.heroDamage), style = type.value, color = if (row.heroDamage == null) scheme.outline else scheme.onSurface, maxLines = 1, modifier = it) },
            lastHits = { modifier ->
                val none = StatsLabels.none()
                Text(
                    buildAnnotatedString {
                        append(row.lastHits?.let(StatsFormat::count) ?: none)
                        withStyle(SpanStyle(color = scheme.outline, fontWeight = HideoutTheme.typography.body.fontWeight)) { append(" / ${row.denies?.let(StatsFormat::count) ?: none}") }
                    },
                    style = type.value, color = scheme.onSurface, maxLines = 1, modifier = modifier,
                )
            },
            rank = { RankChangeText(row.rankChange, it) },
            whenCell = { Text(row.playedAt?.let { moment -> StatsLabels.moment(moment) } ?: "", style = type.caption, color = scheme.outline, textAlign = TextAlign.End, maxLines = 1, modifier = it) },
        )
        Spacer(Modifier.width(12.dp))
        HideoutIcon(HideoutIcons.ChevronRight, null, size = 16.dp, tint = scheme.outline)
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
internal fun RankChangeText(change: RankChange, modifier: Modifier = Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    val (text, color) = when (change) {
        is RankChange.Up -> stringResource(Res.string.stats_up_to, change.to.label) to StatsColors.Win
        is RankChange.Down -> stringResource(Res.string.stats_down_to, change.to.label) to StatsColors.Loss
        RankChange.NoChange -> stringResource(Res.string.stats_rank_held) to scheme.onSurfaceVariant
        RankChange.Unranked -> stringResource(Res.string.stats_unranked) to scheme.outline
        RankChange.Unknown -> StatsLabels.none() to scheme.outline
        RankChange.Calibrating -> stringResource(Res.string.stats_calibrating_match) to scheme.outline
        is RankChange.Placed -> stringResource(Res.string.stats_placed, change.to.label) to StatsColors.Gold
    }
    val weight = if (change is RankChange.NoChange) HideoutTheme.typography.labelNav.fontWeight else HideoutTheme.typography.labelPill.fontWeight
    Text(text, style = type.caption.copy(fontWeight = weight), color = color, maxLines = 1, modifier = modifier)
}
