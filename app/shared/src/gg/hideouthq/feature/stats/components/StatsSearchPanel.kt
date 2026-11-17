package gg.hideouthq.feature.stats.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.stats.LookupFailure
import gg.hideouthq.feature.stats.SearchResult
import gg.hideouthq.feature.stats.SearchState
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_lookup_names
import gg.hideouthq.resources.stats_lookup_offline
import gg.hideouthq.resources.stats_lookup_unavailable
import gg.hideouthq.resources.stats_search_match
import gg.hideouthq.resources.stats_search_none
import gg.hideouthq.resources.stats_search_off
import gg.hideouthq.resources.stats_search_open_match
import gg.hideouthq.resources.stats_search_results
import gg.hideouthq.resources.stats_search_searching
import gg.hideouthq.resources.stats_setup_steam_id
import org.jetbrains.compose.resources.stringResource

private val PanelShape = RoundedCornerShape(16.dp)

@Composable
internal fun StatsSearchPanel(search: SearchState, onMatch: (Long) -> Unit, onPlayer: (Long) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = statsType()
    val visible = search.query.isNotBlank()
    val label = stringResource(Res.string.stats_search_results)
    AnimatedVisibility(
        visible, modifier,
        enter = fadeIn(HideoutTheme.motion.effectsFast()) + scaleIn(HideoutTheme.motion.spatialFast(), 0.96f, TransformOrigin(0.5f, 0f)),
        exit = fadeOut(HideoutTheme.motion.exitFade()),
    ) {
        Column(
            Modifier
                .widthIn(max = HideoutDimens.Search.MaxWidth)
                .fillMaxWidth()
                .dropShadow(PanelShape, Shadow(radius = 24.dp, color = HideoutTheme.colors.dialogShadow, offset = DpOffset(0.dp, 10.dp)))
                .background(scheme.surfaceContainerHigh, PanelShape)
                .padding(6.dp)
                .semantics { contentDescription = label },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            search.results.forEach { result ->
                when (result) {
                    is SearchResult.Match -> ResultRow(
                        title = stringResource(Res.string.stats_search_match, StatsFormat.matchId(result.matchId)),
                        sub = stringResource(Res.string.stats_search_open_match),
                        onClick = { onMatch(result.matchId) },
                    ) { HideoutIcon(HideoutIcons.StatsMatches, null, tint = scheme.onSurfaceVariant) }
                    is SearchResult.Player -> ResultRow(
                        title = StatsLabels.playerName(result.player),
                        sub = stringResource(Res.string.stats_setup_steam_id, StatsFormat.steamId(result.player.accountId)),
                        onClick = { onPlayer(result.player.accountId) },
                    ) { PlayerAvatar(result.player, 32.dp, type.chip) }
                }
            }
            val status = when {
                search.off -> stringResource(Res.string.stats_search_off)
                search.searching -> stringResource(Res.string.stats_search_searching)
                search.failed == LookupFailure.NameSearchUnavailable -> stringResource(Res.string.stats_lookup_names)
                search.failed == LookupFailure.Offline -> stringResource(Res.string.stats_lookup_offline)
                search.failed == LookupFailure.Unavailable -> stringResource(Res.string.stats_lookup_unavailable)
                search.results.none { it is SearchResult.Player } -> stringResource(Res.string.stats_search_none)
                else -> null
            }
            if (status != null) Text(status, style = type.caption, color = scheme.outline, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun ResultRow(title: String, sub: String, onClick: () -> Unit, leading: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    StatsRow(onClick, Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp), hoverFill = scheme.surfaceContainerHighest) {
        Box(Modifier.padding(start = 10.dp).size(32.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = statsType().rowTitle, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = statsType().rowSub, color = scheme.outline, maxLines = 1)
        }
        HideoutIcon(HideoutIcons.ChevronRight, null, size = 16.dp, tint = scheme.outline, modifier = Modifier.padding(end = 12.dp))
    }
}
