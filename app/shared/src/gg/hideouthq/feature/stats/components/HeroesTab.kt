package gg.hideouthq.feature.stats.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.BenchmarkKind
import gg.hideouthq.feature.stats.BenchmarkRow
import gg.hideouthq.feature.stats.BuildItem
import gg.hideouthq.feature.stats.Change
import gg.hideouthq.feature.stats.ChangeUnit
import gg.hideouthq.feature.stats.HeroPanel
import gg.hideouthq.feature.stats.HeroSort
import gg.hideouthq.feature.stats.HeroStatRow
import gg.hideouthq.feature.stats.HeroVsRank
import gg.hideouthq.feature.stats.HeroesContent
import gg.hideouthq.feature.stats.Matchup
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.StatsActions
import gg.hideouthq.feature.stats.StatsFilter
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.feature.stats.StatsMode
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.data.valueOrNull
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_axis_high
import gg.hideouthq.resources.stats_axis_low
import gg.hideouthq.resources.stats_axis_zero
import gg.hideouthq.resources.stats_bench_accuracy
import gg.hideouthq.resources.stats_bench_average
import gg.hideouthq.resources.stats_bench_crit
import gg.hideouthq.resources.stats_bench_damage
import gg.hideouthq.resources.stats_bench_objective
import gg.hideouthq.resources.stats_bench_souls
import gg.hideouthq.resources.stats_best_against
import gg.hideouthq.resources.stats_bought_in
import gg.hideouthq.resources.stats_col_accuracy
import gg.hideouthq.resources.stats_col_damage_min
import gg.hideouthq.resources.stats_col_hero
import gg.hideouthq.resources.stats_col_kda_short
import gg.hideouthq.resources.stats_col_matches
import gg.hideouthq.resources.stats_col_souls_min
import gg.hideouthq.resources.stats_col_win_rate
import gg.hideouthq.resources.stats_couldnt_load
import gg.hideouthq.resources.stats_hardest_against
import gg.hideouthq.resources.stats_hero_summary
import gg.hideouthq.resources.stats_heroes_title
import gg.hideouthq.resources.stats_matches_count
import gg.hideouthq.resources.stats_matchup_none
import gg.hideouthq.resources.stats_matchups
import gg.hideouthq.resources.stats_most_bought
import gg.hideouthq.resources.stats_next_to
import gg.hideouthq.resources.stats_next_to_none
import gg.hideouthq.resources.stats_next_to_ranked
import gg.hideouthq.resources.stats_next_to_sub
import gg.hideouthq.resources.stats_no_build
import gg.hideouthq.resources.stats_played_in
import gg.hideouthq.resources.stats_points
import gg.hideouthq.resources.stats_skins_for
import gg.hideouthq.resources.stats_time_played
import gg.hideouthq.resources.stats_usual_build
import gg.hideouthq.resources.stats_versus
import gg.hideouthq.resources.stats_vs_rank_missing
import gg.hideouthq.resources.stats_vs_rank_normal
import gg.hideouthq.resources.stats_vs_rank_subtitle
import gg.hideouthq.resources.stats_vs_rank_subtitle_ranked
import gg.hideouthq.resources.stats_vs_rank_title
import gg.hideouthq.resources.stats_vs_rank_title_none
import gg.hideouthq.resources.stats_vs_rank_unranked
import gg.hideouthq.resources.stats_w_l
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

@Composable
internal fun HeroesTab(content: HeroesContent, filter: StatsFilter, selected: Int?, sort: HeroSort, actions: StatsActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 1000.dp
        val chosen = content.panel?.hero?.id ?: selected
        val left: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroTableCard(content.rows, filter.period, chosen, sort, actions)
                VsRankCard(content.cohort, content.vsRank, filter.mode, actions::retry)
            }
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { left() }
                content.panel?.let { HeroPanelCard(it, content.cohort, filter, actions, Modifier.width(StatsDimens.HeroPanelWidth)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                left()
                content.panel?.let { HeroPanelCard(it, content.cohort, filter, actions, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun noComparison(mode: StatsMode): String =
    stringResource(if (mode == StatsMode.Normal) Res.string.stats_vs_rank_normal else Res.string.stats_vs_rank_unranked)

@Composable
private fun HeroTableCard(rows: List<HeroStatRow>, period: StatsPeriod, selected: Int?, sort: HeroSort, actions: StatsActions) {
    StatsCard {
        CardHeader(stringResource(Res.string.stats_heroes_title), note = stringResource(Res.string.stats_played_in, rows.size, StatsLabels.periodPhrase(period)))
        HeroHeaderRow(sort, actions)
        val most = rows.maxOfOrNull { it.matches }?.takeIf { it > 0 } ?: 1
        rows.forEach { row -> HeroTableRow(row, row.hero.id == selected, most) { actions.selectHero(row.hero.id) } }
    }
}

@Composable
private fun RowScope.HeroCells(
    hero: @Composable (Modifier) -> Unit,
    matches: @Composable (Modifier) -> Unit,
    winRate: @Composable (Modifier) -> Unit,
    kda: @Composable (Modifier) -> Unit,
    souls: @Composable (Modifier) -> Unit,
    damage: @Composable (Modifier) -> Unit,
    accuracy: @Composable (Modifier) -> Unit,
) {
    val gap = 14.dp
    hero(Modifier.weight(1.1f))
    Spacer(Modifier.width(gap))
    matches(Modifier.weight(0.9f))
    Spacer(Modifier.width(gap))
    winRate(Modifier.weight(1.2f))
    Spacer(Modifier.width(gap))
    kda(Modifier.width(56.dp))
    Spacer(Modifier.width(gap))
    souls(Modifier.width(76.dp))
    Spacer(Modifier.width(gap))
    damage(Modifier.width(76.dp))
    Spacer(Modifier.width(gap))
    accuracy(Modifier.width(70.dp))
}

@Composable
private fun HeroHeaderRow(sort: HeroSort, actions: StatsActions) {
    @Composable
    fun Header(text: String, by: HeroSort, modifier: Modifier, end: Boolean = false) {
        val source = remember { MutableInteractionSource() }
        val states = source.collectInteractionStates()
        val color = if (sort == by || states.hovered) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline
        TableHeaderText(text, modifier.hideoutClickable(source, role = Role.Button) { actions.setHeroSort(by) }, end, color)
    }
    Row(Modifier.fillMaxWidth().height(34.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(40.dp + 14.dp))
        HeroCells(
            hero = { Header(stringResource(Res.string.stats_col_hero), HeroSort.Name, it) },
            matches = { Header(stringResource(Res.string.stats_col_matches), HeroSort.MostPlayed, it) },
            winRate = { Header(stringResource(Res.string.stats_col_win_rate), HeroSort.WinRate, it) },
            kda = { Header(stringResource(Res.string.stats_col_kda_short), HeroSort.Kda, it, end = true) },
            souls = { Header(stringResource(Res.string.stats_col_souls_min), HeroSort.SoulsPerMin, it, end = true) },
            damage = { Header(stringResource(Res.string.stats_col_damage_min), HeroSort.DamagePerMin, it, end = true) },
            accuracy = { Header(stringResource(Res.string.stats_col_accuracy), HeroSort.Accuracy, it, end = true) },
        )
    }
}

@Composable
private fun HeroTableRow(row: HeroStatRow, selected: Boolean, most: Int, onClick: () -> Unit) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    val number = type.value.resized(type.percent.fontSize * (13.5f / 13f))
    StatsRow(onClick, Modifier.fillMaxWidth().height(60.dp), selected = selected) {
        Spacer(Modifier.width(8.dp))
        HeroTile(row.hero, StatsDimens.HeroTile, type.kda.copy(fontFamily = type.result.fontFamily, fontWeight = type.result.fontWeight))
        Spacer(Modifier.width(14.dp))
        HeroCells(
            hero = { TwoLine(StatsLabels.heroName(row.hero), stringResource(Res.string.stats_time_played, StatsLabels.played(row.playedSeconds)), it) },
            matches = { modifier ->
                Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(StatsLabels.count(row.matches), style = number, color = scheme.onSurface, maxLines = 1, modifier = Modifier.width(22.dp))
                    ShareBar(row.matches.toFloat() / most, scheme.primary, Modifier.weight(1f))
                }
            },
            winRate = { WinRateBar(row.winRate, it) },
            kda = { NumberCell(StatsLabels.decimal(row.kda), it) },
            souls = { NumberCell(StatsLabels.count(row.soulsPerMin), it) },
            damage = { NumberCell(StatsLabels.count(row.damagePerMin), it) },
            accuracy = { NumberCell(StatsLabels.percent(row.accuracy), it) },
        )
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
private fun VsRankCard(cohort: RankCohort?, vsRank: Loadable<List<HeroVsRank>>, mode: StatsMode, onRetry: () -> Unit) {
    StatsCard {
        if (cohort == null) {
            CardHeader(stringResource(Res.string.stats_vs_rank_title_none))
            SectionNote(noComparison(mode))
            return@StatsCard
        }
        val subtitle = if (cohort.rankedOnly) Res.string.stats_vs_rank_subtitle_ranked else Res.string.stats_vs_rank_subtitle
        CardHeader(stringResource(Res.string.stats_vs_rank_title, cohort.tierName), note = stringResource(subtitle, cohort.tierName))
        Fetched(vsRank, onRetry, loading = { LoadingRows(4, 46.dp) }) { rows ->
            if (rows.isEmpty()) SectionNote(stringResource(Res.string.stats_vs_rank_missing)) else VsRankRows(rows)
        }
    }
}

@Composable
private fun VsRankRows(rows: List<HeroVsRank>) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(32.dp + 14.dp + 84.dp + 14.dp))
            Box(Modifier.weight(1f)) {
                Text(stringResource(Res.string.stats_axis_low), style = type.small, color = scheme.outline, modifier = Modifier.align(Alignment.CenterStart))
                Text(stringResource(Res.string.stats_axis_zero), style = type.small, color = scheme.outline, modifier = Modifier.align(Alignment.Center))
                Text(stringResource(Res.string.stats_axis_high), style = type.small, color = scheme.outline, modifier = Modifier.align(Alignment.CenterEnd))
            }
            Spacer(Modifier.width(14.dp + 64.dp + 14.dp + 92.dp))
        }
        rows.forEach { row ->
            val points = (row.yours - row.rankAverage) * 100
            val good = points >= 0
            Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 8.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                HeroTile(row.hero, 32.dp, type.you.resized(type.small.fontSize * (11f / 11.5f)))
                Spacer(Modifier.width(14.dp))
                Text(StatsLabels.heroName(row.hero), style = type.rowTitle.resized(type.percent.fontSize * (13.5f / 13f)), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(84.dp))
                Spacer(Modifier.width(14.dp))
                DivergingBar(points, Modifier.weight(1f))
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(Res.string.stats_points, StatsFormat.signedPoints(points)), style = type.percent.copy(fontWeight = type.result.fontWeight),
                    color = if (good) StatsColors.Win else StatsColors.Loss, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(64.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(Res.string.stats_versus, StatsLabels.percent(row.yours), StatsLabels.percent(row.rankAverage)),
                    style = type.rowSub, color = scheme.outline, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(92.dp),
                )
            }
        }
    }
}

@Composable
private fun DivergingBar(points: Double, modifier: Modifier) {
    val mid = MaterialTheme.colorScheme.surfaceContainerHighest
    val shown by animateFloatAsState((points / 20.0).toFloat().coerceIn(-1f, 1f), HideoutTheme.motion.spatialDefault(), label = "diverging")
    Canvas(modifier.height(28.dp)) {
        val barHeight = 12.dp.toPx()
        val top = (size.height - barHeight) / 2
        val center = size.width / 2
        val width = abs(shown) * center
        val left = if (shown >= 0) center else center - width
        drawRoundRect(if (shown >= 0) StatsColors.Win else StatsColors.Loss, Offset(left, top), Size(width, barHeight), CornerRadius(4.dp.toPx()))
        val line = 1.5.dp.toPx()
        drawRect(mid, Offset(center - line / 2, top - 8.dp.toPx()), Size(line, barHeight + 16.dp.toPx()))
    }
}

@Composable
private fun HeroPanelCard(panel: HeroPanel, cohort: RankCohort?, filter: StatsFilter, actions: StatsActions, modifier: Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    Column(modifier.clip(StatsShapes.Card).background(scheme.surfaceContainer)) {
        ArtSurface(panel.hero.artUrl, StatsColors.PanelShade, Modifier.fillMaxWidth().height(150.dp)) {
            Row(Modifier.align(Alignment.BottomStart).padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                HeroTile(panel.hero, 60.dp, type.featuredValue.resized(type.featuredValue.fontSize * (19f / 20f)).copy(fontWeight = type.result.fontWeight))
                Column {
                    Text(StatsLabels.heroName(panel.hero), style = type.rankName.resized(type.rankName.fontSize * (24f / 28f)).copy(letterSpacing = type.cardTitle.letterSpacing), color = StatsColors.OnArt, maxLines = 1)
                    Text(
                        stringResource(Res.string.stats_hero_summary, pluralStringResource(Res.plurals.stats_matches_count, panel.matches, panel.matches), StatsLabels.percent(panel.winRate), StatsLabels.played(panel.playedSeconds)),
                        style = type.caption, color = StatsColors.OnArtVariant, maxLines = 1, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        Column(Modifier.padding(start = 18.dp, top = 10.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val heading = when {
                cohort == null -> stringResource(Res.string.stats_next_to_none)
                cohort.rankedOnly -> stringResource(Res.string.stats_next_to_ranked, cohort.tierName)
                else -> stringResource(Res.string.stats_next_to, cohort.tierName)
            }
            SubHeading(heading, stringResource(Res.string.stats_next_to_sub, StatsLabels.heroName(panel.hero), StatsLabels.periodTail(filter.period)))
            val averages = panel.averages.valueOrNull?.takeIf { cohort != null }
            panel.benchmarks.forEach { BenchmarkBar(it, averages?.get(it.kind)) }
            when {
                cohort == null -> SectionNote(noComparison(filter.mode), modifier = Modifier.padding(top = 2.dp))
                panel.averages is Loadable.Loading -> LoadingLine(120.dp, Modifier.padding(top = 4.dp))
                panel.averages is Loadable.Failed -> SectionNote(stringResource(Res.string.stats_couldnt_load), actions::retry, Modifier.padding(top = 2.dp))
                else -> Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(2.dp, 12.dp).clip(RoundedCornerShape(1.dp)).background(scheme.onSurface))
                    Text(stringResource(Res.string.stats_bench_average, cohort.tierName), style = type.small, color = scheme.outline)
                }
            }
            SubHeading(stringResource(Res.string.stats_usual_build), stringResource(Res.string.stats_most_bought), Modifier.padding(top = 4.dp))
            Fetched(panel.build, actions::retry, loading = { LoadingRows(2, 46.dp) }, noteModifier = Modifier) { build ->
                if (build.isEmpty()) SectionNote(stringResource(Res.string.stats_no_build), modifier = Modifier)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    build.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            pair.forEach { BuildWell(it, panel.buildOf, Modifier.weight(1f)) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            SubHeading(stringResource(Res.string.stats_matchups), null, Modifier.padding(top = 4.dp))
            Fetched(panel.matchups, actions::retry, loading = { LoadingRows(1, 54.dp) }, noteModifier = Modifier) { matchups ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MatchupWell(stringResource(Res.string.stats_best_against), matchups.best, Modifier.weight(1f))
                    MatchupWell(stringResource(Res.string.stats_hardest_against), matchups.hardest, Modifier.weight(1f))
                }
            }
            panel.hero.catalogId?.let { catalogId ->
                HideoutButton(
                    stringResource(Res.string.stats_skins_for, StatsLabels.heroName(panel.hero)), { actions.browseSkins(catalogId) },
                    variant = ButtonVariant.Tonal, leadingIcon = HideoutIcons.Shirt, leadingIconSize = 16.dp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SubHeading(title: String, note: String?, modifier: Modifier = Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    Row(modifier.padding(top = 10.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.caption.copy(fontWeight = type.chip.fontWeight), color = scheme.onSurfaceVariant, maxLines = 1)
        if (note != null) Text(note, style = type.caption, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun benchmarkLabel(kind: BenchmarkKind): String = stringResource(
    when (kind) {
        BenchmarkKind.SoulsPerMin -> Res.string.stats_bench_souls
        BenchmarkKind.HeroDamagePerMin -> Res.string.stats_bench_damage
        BenchmarkKind.Accuracy -> Res.string.stats_bench_accuracy
        BenchmarkKind.CritRate -> Res.string.stats_bench_crit
        BenchmarkKind.ObjectiveDamagePerMin -> Res.string.stats_bench_objective
    },
)

private val BenchmarkKind.isFraction get() = this == BenchmarkKind.Accuracy || this == BenchmarkKind.CritRate

@Composable
private fun BenchmarkBar(row: BenchmarkRow, average: Double?) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    val yours = row.yours
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(benchmarkLabel(row.kind), style = HideoutTheme.typography.captionSmall, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                when {
                    yours == null -> StatsLabels.none()
                    row.kind.isFraction -> StatsLabels.percent(yours)
                    else -> StatsFormat.count(yours)
                },
                style = type.value.resized(type.percent.fontSize * (13.5f / 13f)).copy(fontWeight = type.rankName.fontWeight), color = scheme.onSurface,
            )
            if (yours != null && average != null) {
                val change = if (row.kind.isFraction) Change((yours - average) * 100, ChangeUnit.Points) else if (average > 0) Change((yours / average - 1) * 100, ChangeUnit.Percent) else null
                if (change != null && !StatsFormat.isFlat(change)) DeltaChip(change)
            }
        }
        val below = yours != null && average != null && yours < average
        val track = scheme.surfaceContainerHighest
        val fill = if (below) lerp(track, StatsColors.Loss, 0.7f) else scheme.primary
        val tick = scheme.onSurface
        val top = maxOf(yours ?: 0.0, average ?: 0.0).takeIf { it > 0 }
        val yourShare by animateFloatAsState(((yours ?: 0.0) / (top ?: 1.0) * 0.82).toFloat(), HideoutTheme.motion.spatialDefault(), label = "benchmark")
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val radius = CornerRadius(4.dp.toPx())
            drawRoundRect(track, cornerRadius = radius)
            if (yours != null && top != null) drawRoundRect(fill, size = Size(size.width * yourShare, size.height), cornerRadius = radius)
            if (average != null && top != null) {
                val x = size.width * (average / top * 0.82).toFloat()
                drawRoundRect(tick, Offset(x - 1.dp.toPx(), -4.dp.toPx()), Size(2.dp.toPx(), size.height + 8.dp.toPx()), CornerRadius(1.dp.toPx()))
            }
        }
    }
}

@Composable
private fun BuildWell(item: BuildItem, of: Int, modifier: Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    Row(modifier.clip(StatsShapes.SmallRow).background(scheme.surfaceContainerHigh).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        MatchItemTile(item.item, 34.dp, 12.dp, base = scheme.surfaceContainerHigh, tint = 0.2f, ringAlpha = 0.4f, ringWidth = 1.5.dp, ringRadius = 12.dp, letters = type.itemCode.fontSize)
        TwoLine(item.item.name, stringResource(Res.string.stats_bought_in, item.boughtIn, of), Modifier.weight(1f), titleStyle = type.rowTitle.resized(type.caption.fontSize))
    }
}

@Composable
private fun MatchupWell(label: String, matchup: Matchup?, modifier: Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    Row(modifier.clip(StatsShapes.SmallRow).background(scheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HeroTile(matchup?.enemy, 34.dp, type.small.copy(fontWeight = type.result.fontWeight))
        Column(Modifier.weight(1f)) {
            Text(label, style = type.rowSub, color = scheme.outline, maxLines = 1)
            val title = type.rowTitle.resized(type.percent.fontSize * (13.5f / 13f))
            if (matchup == null) {
                Text(stringResource(Res.string.stats_matchup_none), style = title, color = scheme.onSurfaceVariant, maxLines = 1)
            } else {
                val wl = stringResource(Res.string.stats_w_l, matchup.wins, matchup.losses)
                Text(
                    buildAnnotatedString {
                        append(StatsLabels.heroName(matchup.enemy))
                        append(" ")
                        withStyle(SpanStyle(color = if (matchup.wins > matchup.losses) StatsColors.Win else StatsColors.Loss)) { append(wl) }
                    },
                    style = title, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
