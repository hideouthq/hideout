package gg.hideouthq.feature.stats.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.feature.stats.AbilityOrder
import gg.hideouthq.feature.stats.AbilityRow
import gg.hideouthq.feature.stats.AbilityStep
import gg.hideouthq.feature.stats.BuildOrder
import gg.hideouthq.feature.stats.EnemyContact
import gg.hideouthq.feature.stats.ItemFate
import gg.hideouthq.feature.stats.MatchPlayer
import gg.hideouthq.feature.stats.MatchStat
import gg.hideouthq.feature.stats.Purchase
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_abilities_caption
import gg.hideouthq.resources.stats_abilities_missing
import gg.hideouthq.resources.stats_abilities_title
import gg.hideouthq.resources.stats_ability_numbered
import gg.hideouthq.resources.stats_ability_step_unlock
import gg.hideouthq.resources.stats_ability_step_upgrade
import gg.hideouthq.resources.stats_ability_unnamed
import gg.hideouthq.resources.stats_bought
import gg.hideouthq.resources.stats_bought_kept
import gg.hideouthq.resources.stats_bought_removed
import gg.hideouthq.resources.stats_bought_sold
import gg.hideouthq.resources.stats_bought_upgraded
import gg.hideouthq.resources.stats_build_at_end
import gg.hideouthq.resources.stats_build_missing
import gg.hideouthq.resources.stats_build_removed
import gg.hideouthq.resources.stats_build_sold
import gg.hideouthq.resources.stats_build_title
import gg.hideouthq.resources.stats_build_unresolved
import gg.hideouthq.resources.stats_build_upgraded
import gg.hideouthq.resources.stats_enemies_caption
import gg.hideouthq.resources.stats_enemies_title
import gg.hideouthq.resources.stats_enemy_deaths
import gg.hideouthq.resources.stats_enemy_description
import gg.hideouthq.resources.stats_enemy_kills
import gg.hideouthq.resources.stats_rank_of
import gg.hideouthq.resources.stats_row_accuracy
import gg.hideouthq.resources.stats_row_creeps
import gg.hideouthq.resources.stats_row_damage_min
import gg.hideouthq.resources.stats_row_damage_taken
import gg.hideouthq.resources.stats_row_headshots
import gg.hideouthq.resources.stats_row_healing
import gg.hideouthq.resources.stats_row_kill_share
import gg.hideouthq.resources.stats_row_last_hits
import gg.hideouthq.resources.stats_row_longest_life
import gg.hideouthq.resources.stats_row_mitigated
import gg.hideouthq.resources.stats_row_souls_min
import gg.hideouthq.resources.stats_row_time_dead
import gg.hideouthq.resources.stats_stats_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PlayerPanel(player: MatchPlayer, playerCount: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(0.dp, 0.dp, 20.dp, 20.dp)).background(scheme.surfaceContainerHigh).padding(start = 16.dp, top = 6.dp, end = 16.dp, bottom = 16.dp),
    ) {
        BuildSection(player.build)
        AbilitySection(player.abilities)
        EnemySection(player.enemies)
        StatSection(player, playerCount)
    }
}

@Composable
private fun SectionTitle(title: String, caption: String?, end: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = type.rowTitle.resized(13.5.sp).copy(fontWeight = HideoutWeights.Title), color = scheme.onSurface, maxLines = 1)
        if (caption != null) {
            if (end) Spacer(Modifier.weight(1f))
            Text(caption, style = type.note, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (end) Modifier else Modifier.weight(1f, fill = false))
        }
    }
}

@Composable
private fun BuildSection(build: BuildOrder?) {
    val caption = build?.let {
        listOfNotNull(
            pluralStringResource(Res.plurals.stats_bought, it.purchases.size, it.purchases.size),
            it.upgraded.takeIf { n -> n > 0 }?.let { n -> pluralStringResource(Res.plurals.stats_build_upgraded, n, n) },
            it.sold.takeIf { n -> n > 0 }?.let { n -> pluralStringResource(Res.plurals.stats_build_sold, n, n) },
            it.removed.takeIf { n -> n > 0 }?.let { n -> pluralStringResource(Res.plurals.stats_build_removed, n, n) },
            stringResource(Res.string.stats_build_at_end, it.kept),
            it.unresolved.takeIf { n -> n > 0 }?.let { n -> pluralStringResource(Res.plurals.stats_build_unresolved, n, n) },
        ).joinToString(StatsLabels.Separator)
    }
    SectionTitle(stringResource(Res.string.stats_build_title), caption ?: stringResource(Res.string.stats_build_missing))
    if (build == null || build.purchases.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        build.purchases.forEach { purchase -> BuildCell(purchase) }
    }
}

@Composable
private fun BuildCell(purchase: Purchase) {
    val gone = purchase.fate != ItemFate.Kept
    val bought = StatsFormat.duration(purchase.boughtAtS)
    val at = purchase.goneAtS?.let(StatsFormat::duration).orEmpty()
    val story = when (purchase.fate) {
        ItemFate.Kept -> stringResource(Res.string.stats_bought_kept, bought)
        ItemFate.Upgraded -> stringResource(Res.string.stats_bought_upgraded, bought, at)
        ItemFate.Sold -> stringResource(Res.string.stats_bought_sold, bought, at)
        ItemFate.Removed -> stringResource(Res.string.stats_bought_removed, bought, at)
    }
    val tip = "${itemTip(purchase.item)}${StatsLabels.Separator}$story"
    HideoutTooltip(tip) {
        Column(Modifier.width(40.dp).semantics(mergeDescendants = true) { contentDescription = tip }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MatchItemTile(purchase.item, 28.dp, 9.dp, gone = gone)
            Text(
                bought, style = statsType().small.resized(10.5.sp).copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.outline,
                maxLines = 1, modifier = if (gone) Modifier.alpha(0.7f) else Modifier,
            )
        }
    }
}

private val AbilityCell = 20.dp
private val AbilityGap = 4.dp

@Composable
private fun AbilitySection(abilities: AbilityOrder?) {
    SectionTitle(stringResource(Res.string.stats_abilities_title), stringResource(if (abilities == null) Res.string.stats_abilities_missing else Res.string.stats_abilities_caption))
    if (abilities == null) return
    val scheme = MaterialTheme.colorScheme
    val names = abilities.rows.mapIndexed { index, row -> abilityName(row, index) }
    Column(Modifier.horizontalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(AbilityGap)) {
        abilities.rows.forEachIndexed { index, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                HideoutTooltip(names[index]) { AbilityBadge(row) }
                Row(horizontalArrangement = Arrangement.spacedBy(AbilityGap)) {
                    abilities.steps.forEach { step ->
                        if (step.row == index) {
                            HideoutTooltip(stepTip(names[index], step)) { StepCell(step) }
                        } else {
                            Box(Modifier.size(AbilityCell).clip(RoundedCornerShape(6.dp)).background(scheme.onSurface.copy(alpha = 0.03f)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun abilityName(row: AbilityRow, index: Int): String {
    val name = row.name?.takeIf { it.isNotEmpty() }
    return when {
        row.slot != null && name != null -> "${stringResource(Res.string.stats_ability_numbered, row.slot)}${StatsLabels.Separator}$name"
        row.slot != null -> stringResource(Res.string.stats_ability_numbered, row.slot)
        name != null -> name
        else -> stringResource(Res.string.stats_ability_unnamed, index + 1)
    }
}

@Composable
private fun stepTip(name: String, step: AbilityStep): String = if (step.tier == 0) {
    stringResource(Res.string.stats_ability_step_unlock, name, StatsFormat.duration(step.timeS))
} else {
    stringResource(Res.string.stats_ability_step_upgrade, name, step.tier, StatsFormat.duration(step.timeS))
}

@Composable
private fun AbilityBadge(row: AbilityRow) {
    val scheme = MaterialTheme.colorScheme
    val image = row.iconUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Fit) }
    Box(Modifier.size(AbilityCell).clip(RoundedCornerShape(6.dp)).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        if (image?.loaded != true) {
            Text(row.slot?.toString() ?: "·", style = statsType().small.resized(10.5.sp).copy(fontWeight = HideoutWeights.Heavy), color = scheme.onSurfaceVariant, maxLines = 1)
        }
        if (image != null) {
            Image(image.painter, contentDescription = null, colorFilter = ColorFilter.tint(scheme.onSurfaceVariant), contentScale = ContentScale.Fit, modifier = Modifier.size(15.dp).then(image.layoutModifier))
        }
    }
}

@Composable
private fun StepCell(step: AbilityStep) {
    val scheme = MaterialTheme.colorScheme
    if (step.tier == 0) {
        val ring = scheme.primary
        Box(Modifier.size(AbilityCell).clip(RoundedCornerShape(6.dp)).background(scheme.onSurface.copy(alpha = 0.03f)).drawBehind {
            drawCircle(ring, 3.dp.toPx(), center, style = Stroke(2.dp.toPx()))
        })
    } else {
        Box(Modifier.size(AbilityCell).clip(RoundedCornerShape(6.dp)).background(scheme.primary), contentAlignment = Alignment.Center) {
            Text("${step.tier}", style = statsType().small.resized(10.5.sp).copy(fontWeight = HideoutWeights.Heavy), color = scheme.onPrimary, maxLines = 1)
        }
    }
}

private val EnemyMinWidth = 76.dp

@Composable
private fun EnemySection(enemies: List<EnemyContact>) {
    if (enemies.isEmpty()) return
    SectionTitle(stringResource(Res.string.stats_enemies_title), stringResource(Res.string.stats_enemies_caption))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 4.dp
        val cell = maxOf((maxWidth - gap * (enemies.size - 1)) / enemies.size, EnemyMinWidth)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
            enemies.forEach { enemy -> EnemyCell(enemy, Modifier.width(cell)) }
        }
    }
}

@Composable
private fun EnemyCell(enemy: EnemyContact, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val none = StatsLabels.none()
    val kills = enemy.kills
    val deaths = enemy.deaths
    val description = stringResource(
        Res.string.stats_enemy_description, StatsLabels.playerName(enemy.player), StatsLabels.heroName(enemy.hero), kills?.toString() ?: none, deaths?.toString() ?: none,
    )
    Row(
        modifier.alpha(if (enemy.none) 0.45f else 1f).semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        HeroTile(enemy.hero, 28.dp, type.small.copy(fontWeight = HideoutWeights.Heavy))
        val style = type.small.resized(11.sp).copy(lineHeight = (11 * 1.4).sp, fontFeatureSettings = "tnum")
        Column {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = scheme.onSurface, fontWeight = HideoutWeights.Title)) { append(kills?.toString() ?: none) }
                append(" ")
                append(enemyWord(Res.plurals.stats_enemy_kills, kills))
            }, style = style, color = scheme.outline, maxLines = 1, softWrap = false)
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = StatsColors.Loss, fontWeight = HideoutWeights.Title)) { append(deaths?.toString() ?: none) }
                append(" ")
                append(enemyWord(Res.plurals.stats_enemy_deaths, deaths))
            }, style = style, color = scheme.outline, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun enemyWord(plural: org.jetbrains.compose.resources.PluralStringResource, count: Int?): String = pluralStringResource(plural, count ?: 2)

private val StatRows: List<Pair<StringResource, MatchStat>> = listOf(
    Res.string.stats_row_kill_share to MatchStat.KillShare,
    Res.string.stats_row_accuracy to MatchStat.Accuracy,
    Res.string.stats_row_damage_min to MatchStat.DamagePerMinute,
    Res.string.stats_row_headshots to MatchStat.Headshots,
    Res.string.stats_row_souls_min to MatchStat.SoulsPerMinute,
    Res.string.stats_row_damage_taken to MatchStat.DamageTaken,
    Res.string.stats_row_last_hits to MatchStat.LastHits,
    Res.string.stats_row_mitigated to MatchStat.DamageMitigated,
    Res.string.stats_row_creeps to MatchStat.Creeps,
    Res.string.stats_row_healing to MatchStat.Healing,
    Res.string.stats_row_time_dead to MatchStat.TimeDead,
    Res.string.stats_row_longest_life to MatchStat.LongestLife,
)

@Composable
private fun StatSection(player: MatchPlayer, playerCount: Int) {
    SectionTitle(stringResource(Res.string.stats_stats_title), stringResource(Res.string.stats_rank_of, playerCount), end = true)
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        (0..1).forEach { column ->
            Column(Modifier.weight(1f)) {
                StatRows.filterIndexed { index, _ -> index % 2 == column }.forEach { (label, stat) -> StatLine(stringResource(label), player, stat) }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, player: MatchPlayer, stat: MatchStat) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val ranked = player.stat(stat)
    val own = statText(stat, ranked.value)
    val value = when (stat) {
        MatchStat.LastHits -> "$own / ${statText(MatchStat.Denies, player.stat(MatchStat.Denies).value)}"
        MatchStat.Creeps -> "$own / ${statText(MatchStat.Neutrals, player.stat(MatchStat.Neutrals).value)}"
        else -> own
    }
    val line = scheme.onSurface.copy(alpha = 0.06f)
    Row(
        Modifier.fillMaxWidth().height(32.dp).drawBehind { drawLine(line, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1.dp.toPx()) },
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = type.meta, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(value, style = type.rowTitle.resized(13.5.sp).copy(fontWeight = HideoutWeights.Title, fontFeatureSettings = "tnum"), color = scheme.onSurface, maxLines = 1, softWrap = false)
        val praised = ranked.best && stat.praised
        Text(
            ranked.rank?.let(StatsFormat::ordinal).orEmpty(),
            style = type.small.copy(fontWeight = if (praised) HideoutWeights.Title else type.small.fontWeight, fontFeatureSettings = "tnum"),
            color = if (praised) scheme.secondary else scheme.outline, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.width(30.dp),
        )
    }
}
