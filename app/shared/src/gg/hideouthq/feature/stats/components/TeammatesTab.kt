package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.OpponentRow
import gg.hideouthq.feature.stats.SocialContent
import gg.hideouthq.feature.stats.TeammateRow
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_col_kda_short
import gg.hideouthq.resources.stats_col_met
import gg.hideouthq.resources.stats_col_player
import gg.hideouthq.resources.stats_col_together
import gg.hideouthq.resources.stats_col_win_rate_together
import gg.hideouthq.resources.stats_col_you_won
import gg.hideouthq.resources.stats_names_failed
import gg.hideouthq.resources.stats_no_opponents
import gg.hideouthq.resources.stats_no_party
import gg.hideouthq.resources.stats_no_teammates
import gg.hideouthq.resources.stats_opponents
import gg.hideouthq.resources.stats_opponents_subtitle
import gg.hideouthq.resources.stats_party_only
import gg.hideouthq.resources.stats_teammates_subtitle
import gg.hideouthq.resources.stats_teammates_title
import gg.hideouthq.resources.stats_usually
import gg.hideouthq.resources.stats_usually_plays
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TeammatesTab(content: SocialContent, partyOnly: Boolean, onPartyOnly: (Boolean) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        Pair(
            maxWidth >= 900.dp,
            first = { TeammatesCard(content.teammates, content.names, partyOnly, onPartyOnly, onRetry) },
            second = { OpponentsCard(content.opponents, content.names, content.usualHeroes, onRetry) },
            ratio = 1.2f,
        )
    }
}

@Composable
private fun TableHeader(cells: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(32.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(36.dp + 14.dp))
        cells()
    }
}

@Composable
private fun TeammatesCard(teammates: Loadable<List<TeammateRow>>, names: Loadable<Unit>, partyOnly: Boolean, onPartyOnly: (Boolean) -> Unit, onRetry: () -> Unit) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(Res.string.stats_party_only)
    StatsCard {
        CardHeader(stringResource(Res.string.stats_teammates_title), note = stringResource(Res.string.stats_teammates_subtitle)) {
            Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = HideoutTheme.typography.labelPill, color = scheme.onSurfaceVariant, maxLines = 1)
                HideoutSwitch(partyOnly, onPartyOnly, contentDescription = label, modifier = Modifier.scale(0.86f))
            }
        }
        TableHeader {
            TableHeaderText(stringResource(Res.string.stats_col_player), Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            TableHeaderText(stringResource(Res.string.stats_col_together), Modifier.width(64.dp), end = true)
            Spacer(Modifier.width(14.dp))
            TableHeaderText(stringResource(Res.string.stats_col_win_rate_together), Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            TableHeaderText(stringResource(Res.string.stats_col_kda_short), Modifier.width(52.dp), end = true)
        }
        Fetched(teammates, onRetry, loading = { LoadingRows(4, 58.dp) }) { rows ->
            if (rows.isEmpty()) SectionNote(stringResource(if (partyOnly) Res.string.stats_no_party else Res.string.stats_no_teammates))
            rows.forEach { row ->
                StatsRow(null, Modifier.fillMaxWidth().height(58.dp), shape = StatsShapes.SmallRow) {
                    Spacer(Modifier.width(8.dp))
                    PlayerAvatar(row.player, 36.dp, type.result.resized(type.kda.fontSize))
                    Spacer(Modifier.width(14.dp))
                    PlayerLines(row.player, teammateNote(row), names, Modifier.weight(1f))
                    Spacer(Modifier.width(14.dp))
                    NumberCell(StatsLabels.count(row.games), Modifier.width(64.dp))
                    Spacer(Modifier.width(14.dp))
                    WinRateBar(row.winRate, Modifier.weight(1f))
                    Spacer(Modifier.width(14.dp))
                    NumberCell(StatsLabels.decimal(row.kda), Modifier.width(52.dp))
                    Spacer(Modifier.width(12.dp))
                }
            }
            if (names is Loadable.Failed && rows.isNotEmpty()) SectionNote(stringResource(Res.string.stats_names_failed), onRetry)
        }
    }
}

@Composable
private fun OpponentsCard(rows: List<OpponentRow>, names: Loadable<Unit>, usualHeroes: Loadable<Unit>, onRetry: () -> Unit) {
    val type = statsType()
    StatsCard {
        CardHeader(stringResource(Res.string.stats_opponents), note = stringResource(Res.string.stats_opponents_subtitle))
        TableHeader {
            TableHeaderText(stringResource(Res.string.stats_col_player), Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            TableHeaderText(stringResource(Res.string.stats_col_met), Modifier.width(48.dp), end = true)
            Spacer(Modifier.width(14.dp))
            TableHeaderText(stringResource(Res.string.stats_col_you_won), Modifier.weight(1f))
            Spacer(Modifier.width(14.dp + 44.dp))
        }
        if (rows.isEmpty()) SectionNote(stringResource(Res.string.stats_no_opponents))
        rows.forEach { row ->
            StatsRow(null, Modifier.fillMaxWidth().height(58.dp), shape = StatsShapes.SmallRow) {
                Spacer(Modifier.width(8.dp))
                PlayerAvatar(row.player, 36.dp, type.result.resized(type.kda.fontSize))
                Spacer(Modifier.width(14.dp))
                PlayerLines(row.player, row.usualHero?.let { stringResource(Res.string.stats_usually, StatsLabels.heroName(it)) }, names, Modifier.weight(1f))
                Spacer(Modifier.width(14.dp))
                NumberCell(StatsLabels.count(row.met), Modifier.width(48.dp))
                Spacer(Modifier.width(14.dp))
                WinRateBar(row.winRate, Modifier.weight(1f))
                Spacer(Modifier.width(14.dp))
                Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) {
                    val hero = row.usualHero
                    when {
                        hero != null -> HideoutTooltip(stringResource(Res.string.stats_usually_plays, StatsLabels.heroName(hero))) { HeroTile(hero, 30.dp, type.you) }
                        usualHeroes is Loadable.Loading -> LoadingLine(30.dp, height = 30.dp)
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
        }
        val failed = names is Loadable.Failed || usualHeroes is Loadable.Failed
        if (failed && rows.isNotEmpty()) SectionNote(stringResource(Res.string.stats_names_failed), onRetry)
    }
}
