package gg.hideouthq.feature.stats

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import gg.hideouthq.core.designsystem.component.BackLink
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.Pill
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.component.rememberShimmer
import gg.hideouthq.core.designsystem.component.skeleton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.stats.components.ArtSurface
import gg.hideouthq.feature.stats.components.ChipTone
import gg.hideouthq.feature.stats.components.HeroTile
import gg.hideouthq.feature.stats.components.MatchPendingBody
import gg.hideouthq.feature.stats.components.MatchScoreboard
import gg.hideouthq.feature.stats.components.MatchTimelineCard
import gg.hideouthq.feature.stats.components.StatChip
import gg.hideouthq.feature.stats.components.StatsColors
import gg.hideouthq.feature.stats.components.StatsDimens
import gg.hideouthq.feature.stats.components.StatsLabels
import gg.hideouthq.feature.stats.components.StatsSearchPanel
import gg.hideouthq.feature.stats.components.StatsShapes
import gg.hideouthq.feature.stats.components.TabSkeleton
import gg.hideouthq.feature.stats.components.resized
import gg.hideouthq.feature.stats.components.statsType
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_calibrating_match
import gg.hideouthq.resources.stats_choose_account
import gg.hideouthq.resources.stats_copy_match_id
import gg.hideouthq.resources.stats_crumb_matches
import gg.hideouthq.resources.stats_level
import gg.hideouthq.resources.stats_lobby_average
import gg.hideouthq.resources.stats_match_failed
import gg.hideouthq.resources.stats_match_finished_at
import gg.hideouthq.resources.stats_match_finished_relative
import gg.hideouthq.resources.stats_match_just_finished
import gg.hideouthq.resources.stats_match_not_found
import gg.hideouthq.resources.stats_match_offline
import gg.hideouthq.resources.stats_match_spectator
import gg.hideouthq.resources.stats_most_assists
import gg.hideouthq.resources.stats_no_account_message
import gg.hideouthq.resources.stats_no_account_title
import gg.hideouthq.resources.stats_off_message
import gg.hideouthq.resources.stats_off_title
import gg.hideouthq.resources.stats_open_api
import gg.hideouthq.resources.stats_placed
import gg.hideouthq.resources.stats_ranked_down_to
import gg.hideouthq.resources.stats_ranked_up_to
import gg.hideouthq.resources.stats_retry
import gg.hideouthq.resources.stats_search_placeholder
import gg.hideouthq.resources.stats_tile_damage
import gg.hideouthq.resources.stats_tile_damage_place
import gg.hideouthq.resources.stats_tile_healing
import gg.hideouthq.resources.stats_tile_kda
import gg.hideouthq.resources.stats_tile_last_hits
import gg.hideouthq.resources.stats_tile_objective
import gg.hideouthq.resources.stats_tile_souls
import gg.hideouthq.resources.stats_turn_on
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

interface StatsMatchActions {
    fun back() = Unit
    fun setView(view: GraphView)
    fun retry()
    fun checkMatch()
    fun turnOn()
    fun chooseAccount() = Unit
    fun copy(text: String, onCopied: () -> Unit) = Unit
    fun openOnApi(matchId: Long) = Unit
    fun setQuery(text: String)
    fun openMatch(matchId: Long) = Unit
    fun openPlayer(accountId: Long) = Unit
}

fun deadlockApiMatchUrl(matchId: Long) = "https://deadlock-api.com/matches/$matchId"

@Composable
fun StatsMatchScreen(state: StatsMatchState, matchId: Long, actions: StatsMatchActions, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    PaneScaffold(
        modifier = modifier,
        topBar = { SearchTopBar(state.search.query, actions::setQuery, stringResource(Res.string.stats_search_placeholder)) },
        scrolled = scroll.isScrolled,
        staggered = true,
    ) {
        Box(Modifier.fillMaxSize()) {
            ScrollColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val detail = state.detail
                val justFinished = detail is MatchDetailState.Pending && detail.content.phase != MatchPendingPhase.Unavailable
                Breadcrumb(matchId, when (detail) {
                    is MatchDetailState.Ready -> detail.content.started
                    is MatchDetailState.Pending -> detail.content.endedOn
                    else -> null
                }, justFinished, actions)
                val header = when (detail) {
                    is MatchDetailState.Ready -> detail.content.header
                    is MatchDetailState.Pending -> detail.content.header
                    else -> null
                }
                if (header != null) MatchHeroCard(header, Modifier.staggeredEnter(0), phase = (detail as? MatchDetailState.Pending)?.content?.phase)
                when (detail) {
                    MatchDetailState.Loading -> TabSkeleton(StatsTab.Matches)
                    MatchDetailState.Off -> EmptyState(HideoutIcons.NavStats, stringResource(Res.string.stats_off_title), stringResource(Res.string.stats_off_message)) {
                        HideoutButton(stringResource(Res.string.stats_turn_on), actions::turnOn, variant = ButtonVariant.Tonal)
                    }
                    MatchDetailState.NoAccount -> EmptyState(HideoutIcons.Person, stringResource(Res.string.stats_no_account_title), stringResource(Res.string.stats_no_account_message)) {
                        HideoutButton(stringResource(Res.string.stats_choose_account), actions::chooseAccount, variant = ButtonVariant.Tonal)
                    }
                    is MatchDetailState.Failed -> EmptyState(
                        HideoutIcons.Warning, stringResource(Res.string.stats_match_failed),
                        stringResource(if (detail.notFound) Res.string.stats_match_not_found else Res.string.stats_match_offline),
                    ) { HideoutButton(stringResource(Res.string.stats_retry), actions::retry, variant = ButtonVariant.Tonal) }
                    is MatchDetailState.Pending, is MatchDetailState.Ready -> {
                        val animateEntry = remember(matchId) { detail is MatchDetailState.Ready }
                        val fadeMillis = if (HideoutTheme.motion.reduced) 0 else 200
                        AnimatedContent(
                            targetState = detail,
                            contentKey = { it is MatchDetailState.Ready },
                            transitionSpec = { (fadeIn(tween(fadeMillis)) togetherWith fadeOut(tween(fadeMillis))).using(null) },
                            label = "matchMetadata",
                        ) { shown ->
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                when (shown) {
                                    is MatchDetailState.Pending -> MatchPendingBody(shown.content, actions::checkMatch)
                                    is MatchDetailState.Ready -> MatchBody(shown, state.view, actions, animateEntry)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
            StatsSearchPanel(state.search, actions::openMatch, actions::openPlayer, Modifier.align(Alignment.TopCenter).padding(top = 4.dp, start = 16.dp, end = 16.dp))
        }
    }
}

@Composable
private fun Breadcrumb(matchId: Long, started: Moment?, justFinished: Boolean, actions: StatsMatchActions) {
    val scheme = MaterialTheme.colorScheme
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    val id = StatsFormat.matchId(matchId)
    val copyLabel = stringResource(Res.string.stats_copy_match_id, id)
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        BackLink(stringResource(Res.string.stats_crumb_matches), actions::back)
        if (justFinished) {
            Text("/", style = HideoutTheme.typography.captionSmall, color = scheme.outline)
            Text(stringResource(Res.string.stats_match_just_finished), style = HideoutTheme.typography.captionSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        } else started?.let {
            Text("/", style = HideoutTheme.typography.captionSmall, color = scheme.outline)
            Text(StatsLabels.moment(it, capitalized = true), style = HideoutTheme.typography.captionSmall, color = scheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        Pill(
            text = id, selected = false, onClick = { actions.copy(matchId.toString()) { copied = true } },
            leadingIcon = if (copied) HideoutIcons.Check else HideoutIcons.Copy,
            key = "copy", role = Role.Button, textStyle = HideoutTheme.typography.monoCount,
            modifier = Modifier.semantics { contentDescription = copyLabel },
        )
        Pill(stringResource(Res.string.stats_open_api), false, { actions.openOnApi(matchId) }, leadingIcon = HideoutIcons.OpenExternal, role = Role.Button)
    }
}

@Composable
private fun MatchBody(detail: MatchDetailState.Ready, view: GraphView, actions: StatsMatchActions, animateEntry: Boolean) {
    val content = detail.content
    val scheme = MaterialTheme.colorScheme
    var columns by rememberSaveable(content.matchId) { mutableStateOf(ScoreColumns.Overview) }
    var open by rememberSaveable(content.matchId) { mutableStateOf(content.you?.accountId) }
    val subject = content.players.firstOrNull { it.accountId == open } ?: content.you
    if (content.spectator) Text(stringResource(Res.string.stats_match_spectator), style = statsType().caption, color = scheme.outline, modifier = Modifier.padding(horizontal = 4.dp))
    MatchTimelineCard(content, view, actions::setView, subject, detail.refreshing, (if (animateEntry) Modifier.staggeredEnter(1) else Modifier).zIndex(1f))
    MatchScoreboard(
        content, columns, { columns = it }, open, { accountId -> open = if (open == accountId) null else accountId }, actions::retry,
        if (animateEntry) Modifier.staggeredEnter(2) else Modifier,
    )
}

@Composable
private fun MatchHeroCard(header: MatchHeader, modifier: Modifier, phase: MatchPendingPhase? = null) {
    val type = statsType()
    ArtSurface(header.hero?.artUrl, StatsColors.HeaderShade, modifier.fillMaxWidth().heightIn(min = 204.dp).clip(StatsShapes.Header)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 26.dp)) {
            val stacked = maxWidth < 860.dp
            val identity: @Composable () -> Unit = {
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    HeroTile(header.hero, StatsDimens.MatchHeroTile, type.name.resized(type.name.fontSize * (30f / 32f)).copy(fontWeight = type.result.fontWeight, letterSpacing = type.kpiUnit.letterSpacing))
                    Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ResultPill(header.outcome)
                            Text(
                                listOfNotNull(StatsLabels.kind(header.kind), header.durationS?.let(StatsFormat::duration), header.team?.let { StatsLabels.team(it) })
                                    .joinToString(StatsLabels.Separator),
                                style = type.meta, color = StatsColors.OnArtVariant, maxLines = 1,
                            )
                        }
                        Text(
                            StatsLabels.heroName(header.hero), style = type.name.copy(lineHeight = type.name.fontSize * 1.35f), color = StatsColors.OnArt,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp),
                        )
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                listOfNotNull(
                                    header.level?.let { stringResource(Res.string.stats_level, it) },
                                    header.finished?.let { finishedLabel(it) } ?: header.lobbyRank?.let { stringResource(Res.string.stats_lobby_average, it.label) },
                                    stringResource(Res.string.stats_most_assists).takeIf { header.mostAssists },
                                ).joinToString(StatsLabels.Separator),
                                style = type.meta, color = StatsColors.OnArtVariant, maxLines = 1,
                            )
                            when (val change = header.rankChange) {
                                is RankChange.Up -> StatChip(stringResource(Res.string.stats_ranked_up_to, change.to.label), ChipTone.Up, icon = HideoutIcons.ArrowUp)
                                is RankChange.Down -> StatChip(stringResource(Res.string.stats_ranked_down_to, change.to.label), ChipTone.Neutral, icon = HideoutIcons.ArrowDown)
                                is RankChange.Placed -> StatChip(stringResource(Res.string.stats_placed, change.to.label), ChipTone.Gold)
                                RankChange.Calibrating -> StatChip(stringResource(Res.string.stats_calibrating_match), ChipTone.Neutral)
                                else -> Unit
                            }
                        }
                    }
                }
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    identity()
                    GlassTiles(header, phase)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { identity() }
                    GlassTiles(header, phase)
                }
            }
        }
    }
}

@Composable
private fun ResultPill(outcome: Outcome) {
    val color = when (outcome) {
        Outcome.Win -> StatsColors.Win
        Outcome.Loss -> StatsColors.Loss
        Outcome.Other -> StatsColors.OnArtVariant
    }
    Row(
        Modifier.height(28.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (outcome != Outcome.Other) HideoutIcon(if (outcome == Outcome.Win) HideoutIcons.Check else HideoutIcons.Close, null, size = 16.dp, tint = color)
        Text(StatsLabels.outcome(outcome), style = statsType().chip.resized(statsType().caption.fontSize).copy(fontWeight = statsType().result.fontWeight), color = color, maxLines = 1)
    }
}

@Composable
private fun finishedLabel(moment: Moment): String {
    val text = StatsLabels.moment(moment)
    return if (moment is Moment.ClockTime) stringResource(Res.string.stats_match_finished_at, text) else stringResource(Res.string.stats_match_finished_relative, text)
}

private data class GlassCell(val value: String?, val label: String, val pending: Boolean = false)

@Composable
private fun GlassTiles(header: MatchHeader, phase: MatchPendingPhase?) {
    val none = StatsLabels.none()
    val pendingTiles = phase != null && phase != MatchPendingPhase.Unavailable
    val cells = listOf(
        GlassCell(null, stringResource(Res.string.stats_tile_kda, StatsLabels.decimal(header.kda))),
        GlassCell(header.souls?.let(StatsFormat::count) ?: none, stringResource(Res.string.stats_tile_souls)),
        GlassCell(
            header.heroDamage?.let(StatsFormat::count) ?: none,
            header.heroDamagePlace?.let { stringResource(Res.string.stats_tile_damage_place, StatsFormat.ordinal(it)) } ?: stringResource(Res.string.stats_tile_damage),
            pending = pendingTiles && header.heroDamage == null,
        ),
        GlassCell("${header.lastHits?.let(StatsFormat::count) ?: none} / ${header.denies?.let(StatsFormat::count) ?: none}", stringResource(Res.string.stats_tile_last_hits)),
        GlassCell(header.objectiveDamage?.let(StatsFormat::count) ?: none, stringResource(Res.string.stats_tile_objective), pending = pendingTiles && header.objectiveDamage == null),
        GlassCell(header.healing?.let(StatsFormat::count) ?: none, stringResource(Res.string.stats_tile_healing), pending = pendingTiles && header.healing == null),
    )
    val sweep = rememberShimmer(active = phase == MatchPendingPhase.OnTheWay)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        (0 until 3).forEach { column ->
            Column(Modifier.width(IntrinsicSize.Max), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(cells[column], cells[column + 3]).forEach { cell -> GlassTile(cell, header, sweep) }
            }
        }
    }
}

@Composable
private fun GlassTile(cell: GlassCell, header: MatchHeader, sweep: State<Float>) {
    val type = statsType()
    Column(
        Modifier.fillMaxWidth().widthIn(min = 118.dp).clip(StatsShapes.GlassTile).background(StatsColors.ArtGlass).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        when {
            cell.value == null -> {
                val none = StatsLabels.none()
                Text(
                    listOf(header.kills, header.deaths, header.assists).joinToString(" / ") { it?.toString() ?: none },
                    style = type.kda.resized(type.featuredValue.fontSize * 0.9f), color = StatsColors.OnArt, maxLines = 1,
                )
            }
            cell.pending -> Box(Modifier.width(84.dp).height(20.dp).skeleton(sweep, RoundedCornerShape(7.dp), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.22f)))
            else -> Text(cell.value, style = type.featuredValue, color = StatsColors.OnArt, maxLines = 1)
        }
        Text(cell.label, style = type.note, color = StatsColors.OnArt.copy(alpha = 0.62f), maxLines = 1)
    }
}
