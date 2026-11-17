package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.stats.Change
import gg.hideouthq.feature.stats.FeaturedHero
import gg.hideouthq.feature.stats.HeroLine
import gg.hideouthq.feature.stats.MatchRowUi
import gg.hideouthq.feature.stats.MetricKind
import gg.hideouthq.feature.stats.MetricTile
import gg.hideouthq.feature.stats.Outcome
import gg.hideouthq.feature.stats.OverviewContent
import gg.hideouthq.feature.stats.RankChange
import gg.hideouthq.feature.stats.RankChart
import gg.hideouthq.feature.stats.RankCohort
import gg.hideouthq.feature.stats.RankedAt
import gg.hideouthq.feature.stats.StatsActions
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.feature.stats.StatsMode
import gg.hideouthq.feature.stats.StatsTab
import gg.hideouthq.feature.stats.TeammateRow
import gg.hideouthq.feature.stats.WinRateTile
import gg.hideouthq.feature.stats.data.linearRank
import gg.hideouthq.feature.stats.data.valueOrNull
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_all
import gg.hideouthq.resources.stats_all_heroes
import gg.hideouthq.resources.stats_all_matches
import gg.hideouthq.resources.stats_calibrating_match
import gg.hideouthq.resources.stats_couldnt_load
import gg.hideouthq.resources.stats_crits
import gg.hideouthq.resources.stats_from_matches
import gg.hideouthq.resources.stats_kda_per_match
import gg.hideouthq.resources.stats_kda_value
import gg.hideouthq.resources.stats_kpi_accuracy
import gg.hideouthq.resources.stats_kpi_damage
import gg.hideouthq.resources.stats_kpi_kda
import gg.hideouthq.resources.stats_kpi_souls
import gg.hideouthq.resources.stats_kpi_win_rate
import gg.hideouthq.resources.stats_last_hits
import gg.hideouthq.resources.stats_last_played
import gg.hideouthq.resources.stats_last_ranked_rank
import gg.hideouthq.resources.stats_last_results
import gg.hideouthq.resources.stats_match_description
import gg.hideouthq.resources.stats_matches_count
import gg.hideouthq.resources.stats_mode_normal
import gg.hideouthq.resources.stats_mode_ranked
import gg.hideouthq.resources.stats_most_games
import gg.hideouthq.resources.stats_most_played
import gg.hideouthq.resources.stats_names_failed
import gg.hideouthq.resources.stats_no_ranked
import gg.hideouthq.resources.stats_no_teammates
import gg.hideouthq.resources.stats_party
import gg.hideouthq.resources.stats_party_last_played
import gg.hideouthq.resources.stats_peak
import gg.hideouthq.resources.stats_per_match
import gg.hideouthq.resources.stats_placed
import gg.hideouthq.resources.stats_rank_average
import gg.hideouthq.resources.stats_rank_average_missing
import gg.hideouthq.resources.stats_rank_average_normal
import gg.hideouthq.resources.stats_rank_average_ranked
import gg.hideouthq.resources.stats_rank_average_unranked
import gg.hideouthq.resources.stats_rank_held
import gg.hideouthq.resources.stats_rank_span
import gg.hideouthq.resources.stats_rank_title
import gg.hideouthq.resources.stats_ranked_and_normal
import gg.hideouthq.resources.stats_ranked_matches
import gg.hideouthq.resources.stats_recent_title
import gg.hideouthq.resources.stats_retry
import gg.hideouthq.resources.stats_souls
import gg.hideouthq.resources.stats_souls_per_min_short
import gg.hideouthq.resources.stats_strip_kda
import gg.hideouthq.resources.stats_strip_win_rate
import gg.hideouthq.resources.stats_teammates_title
import gg.hideouthq.resources.stats_tier_down
import gg.hideouthq.resources.stats_tier_up
import gg.hideouthq.resources.stats_unranked
import gg.hideouthq.resources.stats_wins_losses
import gg.hideouthq.resources.stats_your_heroes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OverviewTab(content: OverviewContent, mode: StatsMode, actions: StatsActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = maxWidth
        val wide = width >= 880.dp
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KpiGrid(content, mode, width, actions::retry, Modifier.staggeredEnter(2))
            Pair(
                wide,
                first = { RecentMatchesCard(content.recent, mode, actions) },
                second = { YourHeroesCard(content.featured, content.heroes, actions) },
                modifier = Modifier.staggeredEnter(3),
            )
            Pair(
                wide,
                first = { RankCard(content.rank, content.lastRanked) },
                second = { TeammatesCard(content.teammates, content.names, { actions.selectTab(StatsTab.Teammates) }, actions::retry) },
                modifier = Modifier.staggeredEnter(4),
            )
        }
    }
}

@Composable
internal fun Pair(wide: Boolean, first: @Composable () -> Unit, second: @Composable () -> Unit, modifier: Modifier = Modifier, ratio: Float = 1.55f) {
    if (wide) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.weight(ratio)) { first() }
            Box(Modifier.weight(1f)) { second() }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            first()
            second()
        }
    }
}

@Composable
private fun KpiGrid(content: OverviewContent, mode: StatsMode, width: Dp, onRetry: () -> Unit, modifier: Modifier) {
    val columns = if (width >= 1000.dp) 5 else 3
    val tiles: List<@Composable (Modifier) -> Unit> = listOf<@Composable (Modifier) -> Unit>({ WinRateTileView(content.winRate, it) }) +
        content.metrics.map { tile -> @Composable { m: Modifier -> MetricTileView(tile, content.cohort, content.averages, mode, onRetry, m) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

@Composable
private fun KpiFrame(label: String, change: Change?, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .heightIn(min = StatsDimens.KpiMinHeight)
            .clip(StatsShapes.Tile)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = HideoutTheme.typography.labelPill, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (change != null && !StatsFormat.isFlat(change)) DeltaChip(change)
        }
        content()
    }
}

@Composable
private fun KpiValue(value: String, unit: String? = null) {
    val type = statsType()
    Row(Modifier.padding(top = 8.dp)) {
        Text(value, style = type.kpiValue, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (unit != null) Text(unit, style = type.kpiUnit, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.alignByBaseline().padding(start = 2.dp))
    }
}

@Composable
private fun KpiSub(text: String) {
    Text(text, style = statsType().kpiSub, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
}

@Composable
private fun WinRateTileView(tile: WinRateTile, modifier: Modifier) {
    val type = statsType()
    Box(modifier) {
        KpiFrame(stringResource(Res.string.stats_kpi_win_rate), tile.change, Modifier.fillMaxSize()) {
            KpiValue(tile.winRate?.let(StatsFormat::percentOf) ?: StatsLabels.none(), if (tile.winRate != null) "%" else null)
            KpiSub(stringResource(Res.string.stats_wins_losses, tile.wins, tile.losses))
            Spacer(Modifier.weight(1f))
            val wins = tile.form.count { it }
            val footer = stringResource(Res.string.stats_last_results, tile.form.size, wins, tile.form.size - wins)
            if (tile.form.isNotEmpty()) FormBars(tile.form, footer)
            Text(footer, style = type.small, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp), maxLines = 1)
        }
        WinDonut(tile.winRate, Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = 44.dp))
    }
}

@Composable
private fun MetricTileView(tile: MetricTile, cohort: RankCohort?, averages: Loadable<Map<MetricKind, Double>>, mode: StatsMode, onRetry: () -> Unit, modifier: Modifier) {
    val type = statsType()
    val none = StatsLabels.none()
    val label = stringResource(
        when (tile.kind) {
            MetricKind.Kda -> Res.string.stats_kpi_kda
            MetricKind.SoulsPerMin -> Res.string.stats_kpi_souls
            MetricKind.DamagePerMin -> Res.string.stats_kpi_damage
            MetricKind.Accuracy -> Res.string.stats_kpi_accuracy
        },
    )
    KpiFrame(label, tile.change, modifier) {
        when (tile.kind) {
            MetricKind.Kda -> KpiValue(tile.value?.let(StatsFormat::decimal) ?: none)
            MetricKind.SoulsPerMin, MetricKind.DamagePerMin -> KpiValue(tile.value?.let(StatsFormat::count) ?: none)
            MetricKind.Accuracy -> KpiValue(tile.value?.let(StatsFormat::percentOf) ?: none, if (tile.value != null) "%" else null)
        }
        val sub = when (tile.kind) {
            MetricKind.Kda -> tile.sub.filterNotNull().takeIf { it.size == 3 }?.let { (kills, deaths, assists) ->
                stringResource(Res.string.stats_kda_per_match, StatsFormat.decimal(kills), StatsFormat.decimal(deaths), StatsFormat.decimal(assists))
            }
            MetricKind.SoulsPerMin, MetricKind.DamagePerMin -> tile.sub.firstOrNull()?.let { stringResource(Res.string.stats_per_match, StatsFormat.thousands(it)) }
            MetricKind.Accuracy -> tile.sub.firstOrNull()?.let { stringResource(Res.string.stats_crits, "${StatsFormat.percentOf(it)}%") }
        }
        val coverage = tile.coverage
        KpiSub(
            when {
                coverage != null && tile.kind != MetricKind.Kda -> stringResource(Res.string.stats_from_matches, coverage.covered, coverage.of)
                else -> sub ?: none
            },
        )
        Spacer(Modifier.weight(1f))
        val average = averages.valueOrNull?.get(tile.kind)?.takeIf { cohort != null }
        Sparkline(tile.series, average)
        Row(Modifier.padding(top = 8.dp).height(16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable
            fun Note(text: String) = Text(text, style = type.small, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                cohort == null -> Note(stringResource(if (mode == StatsMode.Normal) Res.string.stats_rank_average_normal else Res.string.stats_rank_average_unranked))
                averages is Loadable.Loading -> LoadingLine(110.dp)
                averages is Loadable.Failed -> {
                    Note(stringResource(Res.string.stats_couldnt_load))
                    LinkButton(stringResource(Res.string.stats_retry), onRetry)
                }
                average == null -> Note(stringResource(Res.string.stats_rank_average_missing))
                else -> {
                    val value = when (tile.kind) {
                        MetricKind.Kda -> StatsFormat.decimal(average)
                        MetricKind.Accuracy -> "${StatsFormat.percentOf(average)}%"
                        else -> StatsFormat.count(average)
                    }
                    DashMark()
                    Note(stringResource(if (cohort.rankedOnly) Res.string.stats_rank_average_ranked else Res.string.stats_rank_average, cohort.tierName, value))
                }
            }
        }
    }
}

@Composable
private fun DashMark() {
    val color = MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(12.dp, 2.dp).drawBehind {
            drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())))
        },
    )
}

@Composable
private fun RecentMatchesCard(rows: List<MatchRowUi>, mode: StatsMode, actions: StatsActions) {
    val note = stringResource(
        when (mode) {
            StatsMode.All -> Res.string.stats_ranked_and_normal
            StatsMode.Ranked -> Res.string.stats_mode_ranked
            StatsMode.Normal -> Res.string.stats_mode_normal
        },
    )
    StatsCard {
        CardHeader(stringResource(Res.string.stats_recent_title), note = note) {
            LinkButton(stringResource(Res.string.stats_all_matches), { actions.selectTab(StatsTab.Matches) })
        }
        rows.forEach { row -> RecentMatchRow(row) { actions.openMatch(row.matchId) } }
    }
}

@Composable
internal fun ResultBar(outcome: Outcome) {
    val color = when (outcome) {
        Outcome.Win -> StatsColors.Win
        Outcome.Loss -> StatsColors.Loss
        Outcome.Other -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(4.dp, 34.dp).clip(RoundedCornerShape(2.dp)).background(color))
}

@Composable
internal fun outcomeColor(outcome: Outcome): Color = when (outcome) {
    Outcome.Win -> StatsColors.Win
    Outcome.Loss -> StatsColors.Loss
    Outcome.Other -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun matchDescription(row: MatchRowUi): String {
    val none = StatsLabels.none()
    return stringResource(
        Res.string.stats_match_description, StatsLabels.outcome(row.outcome), StatsLabels.heroName(row.hero),
        row.kills?.toString() ?: none, row.deaths?.toString() ?: none, row.assists?.toString() ?: none,
    )
}

@Composable
private fun RecentMatchRow(row: MatchRowUi, onClick: () -> Unit) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    StatsRow(onClick, Modifier.fillMaxWidth().height(StatsDimens.MatchRowHeight), contentDescription = matchDescription(row)) {
        Spacer(Modifier.width(8.dp))
        ResultBar(row.outcome)
        Spacer(Modifier.width(12.dp))
        HeroTile(row.hero, StatsDimens.HeroTile, type.itemCode.resized(type.kda.fontSize).copy(fontWeight = type.result.fontWeight))
        Spacer(Modifier.width(12.dp))
        TwoLine(StatsLabels.heroName(row.hero), listOfNotNull(StatsLabels.kind(row.kind), row.durationS?.let(StatsFormat::duration)).joinToString(StatsLabels.Separator), Modifier.weight(1.25f))
        Spacer(Modifier.width(12.dp))
        TwoLine(StatsLabels.outcome(row.outcome), shortRankChange(row.rankChange), Modifier.weight(0.75f), titleColor = outcomeColor(row.outcome), titleStyle = type.result)
        Spacer(Modifier.width(12.dp))
        KdaCell(row, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        TwoLine(StatsLabels.thousands(row.souls), stringResource(Res.string.stats_souls), Modifier.weight(0.7f), titleStyle = type.value)
        Spacer(Modifier.width(12.dp))
        TwoLine(StatsLabels.count(row.lastHits), stringResource(Res.string.stats_last_hits), Modifier.weight(0.7f), titleStyle = type.value)
        Spacer(Modifier.width(12.dp))
        Text(row.playedAt?.let { StatsLabels.moment(it) } ?: "", style = type.caption, color = scheme.outline, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(66.dp))
        Spacer(Modifier.width(12.dp))
        HideoutIcon(HideoutIcons.ChevronRight, null, size = 16.dp, tint = scheme.outline)
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
internal fun shortRankChange(change: RankChange): String = when (change) {
    RankChange.Unranked -> stringResource(Res.string.stats_unranked)
    RankChange.NoChange -> stringResource(Res.string.stats_rank_held)
    RankChange.Unknown -> StatsLabels.none()
    RankChange.Calibrating -> stringResource(Res.string.stats_calibrating_match)
    is RankChange.Placed -> stringResource(Res.string.stats_placed, change.to.label)
    is RankChange.Up -> pluralStringResource(Res.plurals.stats_tier_up, change.steps, change.steps)
    is RankChange.Down -> pluralStringResource(Res.plurals.stats_tier_down, change.steps, change.steps)
}

@Composable
private fun YourHeroesCard(featured: FeaturedHero?, heroes: List<HeroLine>, actions: StatsActions) {
    StatsCard {
        CardHeader(stringResource(Res.string.stats_your_heroes)) {
            LinkButton(stringResource(Res.string.stats_all_heroes), { actions.selectTab(StatsTab.Heroes) })
        }
        if (featured != null) FeaturedHeroCard(featured) { actions.openHero(featured.hero.id) }
        heroes.forEach { line -> HeroLineRow(line) { actions.openHero(line.hero.id) } }
    }
}

@Composable
private fun FeaturedHeroCard(featured: FeaturedHero, onClick: () -> Unit) {
    val type = statsType()
    StatsRow(onClick, Modifier.fillMaxWidth().padding(bottom = 6.dp).height(206.dp), shape = StatsShapes.Featured, hoverFill = Color.Transparent) {
        ArtSurface(featured.hero.artUrl, StatsColors.FeaturedShade, Modifier.fillMaxSize()) {
            HeroRender(featured.hero.renderUrl, Modifier.align(Alignment.BottomEnd))
            Column(Modifier.fillMaxHeight().padding(start = 20.dp, top = 18.dp, bottom = 18.dp)) {
                Text(stringResource(Res.string.stats_most_played).uppercase(), style = type.eyebrow, color = Color.White.copy(alpha = 0.66f))
                Text(StatsLabels.heroName(featured.hero), style = type.rankName, color = StatsColors.OnArt, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                Text(
                    listOf(pluralStringResource(Res.plurals.stats_matches_count, featured.matches, featured.matches), StatsLabels.played(featured.playedSeconds)).joinToString(StatsLabels.Separator),
                    style = type.caption, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    FeaturedStat(StatsLabels.percent(featured.winRate), stringResource(Res.string.stats_strip_win_rate))
                    FeaturedStat(StatsLabels.decimal(featured.kda), stringResource(Res.string.stats_strip_kda))
                    FeaturedStat(StatsLabels.count(featured.soulsPerMin), stringResource(Res.string.stats_souls_per_min_short))
                }
            }
        }
    }
}

@Composable
private fun FeaturedStat(value: String, label: String) {
    val type = statsType()
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, style = type.featuredValue, color = StatsColors.OnArt, maxLines = 1)
        Text(label, style = type.small, color = Color.White.copy(alpha = 0.62f), maxLines = 1)
    }
}

@Composable
internal fun HeroRender(url: String?, modifier: Modifier = Modifier) {
    val image = url?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Fit) }
    Box(modifier.size(220.dp, 206.dp)) {
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 26.dp)
                .size(190.dp)
                .clip(Cookie.Twelve.shape)
                .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f)), center = Offset(95f * 2.75f, 57f * 2.75f))),
        )
        // Composed while loading too: the picture only starts loading once it is laid out.
        if (image != null) {
            Image(image.painter, null, Modifier.align(Alignment.BottomEnd).offset(x = (-24).dp).size(150.dp, 204.dp).then(image.layoutModifier), contentScale = ContentScale.Fit, alignment = Alignment.BottomCenter)
        }
    }
}

@Composable
private fun HeroLineRow(line: HeroLine, onClick: () -> Unit) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    StatsRow(onClick, Modifier.fillMaxWidth().height(54.dp), shape = StatsShapes.SmallRow) {
        Spacer(Modifier.width(8.dp))
        HeroTile(line.hero, StatsDimens.SmallHeroTile, type.chip.copy(fontWeight = type.result.fontWeight))
        Spacer(Modifier.width(12.dp))
        TwoLine(
            StatsLabels.heroName(line.hero),
            listOf(pluralStringResource(Res.plurals.stats_matches_count, line.matches, line.matches), stringResource(Res.string.stats_kda_value, StatsLabels.decimal(line.kda))).joinToString(StatsLabels.Separator),
            Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        WinRateBar(line.winRate, Modifier.width(120.dp))
        Spacer(Modifier.width(12.dp))
        Box(Modifier.width(44.dp), contentAlignment = Alignment.CenterEnd) { HideoutIcon(HideoutIcons.ChevronRight, null, size = 16.dp, tint = scheme.outline) }
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun RankCard(chart: RankChart?, lastRanked: RankedAt?) {
    val type = statsType()
    StatsCard {
        CardHeader(
            stringResource(Res.string.stats_rank_title),
            afterTitle = {
                val from = chart?.from
                val to = chart?.to
                if (from != null && to != null) {
                    val rising = linearRank(to.badge) > linearRank(from.badge)
                    StatChip(stringResource(Res.string.stats_rank_span, from.label, to.label), if (rising) ChipTone.Up else ChipTone.Neutral, icon = if (rising) HideoutIcons.ArrowUp else null)
                }
                chart?.peak?.let { StatChip(stringResource(Res.string.stats_peak, it.label), ChipTone.Gold) }
            },
        ) {
            if (chart != null) {
                Text(pluralStringResource(Res.plurals.stats_ranked_matches, chart.rankedMatches, chart.rankedMatches), style = type.caption, color = MaterialTheme.colorScheme.outline, maxLines = 1, modifier = Modifier.padding(end = 10.dp))
            }
        }
        if (chart == null) {
            Column(Modifier.fillMaxWidth().height(236.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.stats_no_ranked), style = type.caption, color = MaterialTheme.colorScheme.outline)
                lastRanked?.let {
                    Text(stringResource(Res.string.stats_last_ranked_rank, StatsLabels.moment(it.day), it.rank.label), style = type.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            val peakLabel = listOfNotNull(chart.peak?.label, chart.peakDay?.let { StatsLabels.moment(it, capitalized = true) }).joinToString(StatsLabels.Separator)
            val axis = chart.axisLabels.map { it.fraction to StatsLabels.moment(it.moment, capitalized = true) }
            RankChartView(chart, peakLabel, axis, Modifier.padding(start = 6.dp, top = 4.dp, end = 6.dp))
        }
    }
}

@Composable
private fun TeammatesCard(teammates: Loadable<List<TeammateRow>>, names: Loadable<Unit>, onAll: () -> Unit, onRetry: () -> Unit) {
    StatsCard {
        CardHeader(stringResource(Res.string.stats_teammates_title), note = stringResource(Res.string.stats_most_games)) {
            LinkButton(stringResource(Res.string.stats_all), onAll)
        }
        Fetched(teammates, onRetry, loading = { LoadingRows(3) }) { rows ->
            if (rows.isEmpty()) SectionNote(stringResource(Res.string.stats_no_teammates))
            rows.forEach { TeammateSummaryRow(it, names) }
            if (names is Loadable.Failed && rows.isNotEmpty()) SectionNote(stringResource(Res.string.stats_names_failed), onRetry)
        }
    }
}

@Composable
internal fun teammateNote(row: TeammateRow): String? {
    val when_ = row.lastPlayed?.let { StatsLabels.moment(it) }
    return when {
        row.party && when_ != null -> stringResource(Res.string.stats_party_last_played, when_)
        row.party -> stringResource(Res.string.stats_party)
        when_ != null -> stringResource(Res.string.stats_last_played, when_)
        else -> null
    }
}

@Composable
private fun TeammateSummaryRow(row: TeammateRow, names: Loadable<Unit>) {
    val type = statsType()
    StatsRow(null, Modifier.fillMaxWidth().height(56.dp), shape = StatsShapes.SmallRow) {
        Spacer(Modifier.width(8.dp))
        PlayerAvatar(row.player, 36.dp, type.result.resized(type.kda.fontSize))
        Spacer(Modifier.width(12.dp))
        PlayerLines(row.player, teammateNote(row), names, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(StatsLabels.count(row.games), style = type.value.copy(fontWeight = HideoutTheme.typography.body.fontWeight, fontSize = type.percent.fontSize * (13.5f / 13f)), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(54.dp))
        Spacer(Modifier.width(12.dp))
        WinRateBar(row.winRate, Modifier.width(110.dp))
        Spacer(Modifier.width(10.dp))
    }
}
