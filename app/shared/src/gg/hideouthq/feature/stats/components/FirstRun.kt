package gg.hideouthq.feature.stats.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.stats.AccountLookup
import gg.hideouthq.feature.stats.LocalAccount
import gg.hideouthq.feature.stats.LookupFailure
import gg.hideouthq.feature.stats.PlayerVisual
import gg.hideouthq.feature.stats.StatsActions
import gg.hideouthq.feature.stats.StatsFormat
import gg.hideouthq.feature.stats.data.valueOrNull
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_lookup_find
import gg.hideouthq.resources.stats_lookup_hint
import gg.hideouthq.resources.stats_lookup_names
import gg.hideouthq.resources.stats_lookup_not_found
import gg.hideouthq.resources.stats_lookup_offline
import gg.hideouthq.resources.stats_lookup_searching
import gg.hideouthq.resources.stats_lookup_unavailable
import gg.hideouthq.resources.stats_setup_intro
import gg.hideouthq.resources.stats_setup_signed_in
import gg.hideouthq.resources.stats_setup_steam_id
import gg.hideouthq.resources.stats_setup_title
import gg.hideouthq.resources.stats_use
import gg.hideouthq.resources.stats_use_different
import gg.hideouthq.resources.stats_use_found
import gg.hideouthq.resources.stats_use_this
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FirstRun(suggestion: Loadable<LocalAccount?>, lookup: AccountLookup, actions: StatsActions, modifier: Modifier = Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    var chosenOther by remember { mutableStateOf(false) }
    val found = suggestion.valueOrNull
    Box(modifier.fillMaxWidth().padding(top = 24.dp, bottom = 40.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 600.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(132.dp).clip(Cookie.Twelve.shape).background(scheme.primaryContainer).staggeredEnter(0), contentAlignment = Alignment.Center) {
                HideoutIcon(HideoutIcons.NavStats, null, size = 52.dp, tint = scheme.primary)
            }
            Text(stringResource(Res.string.stats_setup_title), style = type.setupTitle, color = scheme.onSurface, modifier = Modifier.padding(top = 22.dp).staggeredEnter(1))
            Text(
                stringResource(Res.string.stats_setup_intro),
                style = HideoutTheme.typography.bodyProse.copy(lineHeight = HideoutTheme.typography.bodyProse.fontSize * 1.55f), color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 470.dp).padding(top = 10.dp).staggeredEnter(1),
            )
            val motion = HideoutTheme.motion
            val shown = when {
                suggestion is Loadable.Loading -> AccountSlot.Reading
                found != null && !chosenOther -> AccountSlot.Found
                else -> AccountSlot.Search
            }
            AnimatedContent(shown, transitionSpec = { fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.exitFade()) }, label = "setupAccount") { slot ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (slot) {
                        AccountSlot.Reading -> LoadingLine(null, Modifier.padding(top = 26.dp), height = 80.dp, shape = StatsShapes.Tile)
                        AccountSlot.Found -> if (found != null) {
                            SuggestedAccount(found) { actions.useAccount(found.accountId) }
                            HideoutButton(stringResource(Res.string.stats_use_different), { chosenOther = true }, variant = ButtonVariant.Text, size = ButtonSize.Small, modifier = Modifier.padding(top = 8.dp))
                        }
                        AccountSlot.Search -> {
                            AccountSearch(lookup, actions::lookUp, actions::clearLookup, actions::useAccount, Modifier.padding(top = 26.dp))
                            if (found != null) {
                                HideoutButton(stringResource(Res.string.stats_use_found), { chosenOther = false }, variant = ButtonVariant.Text, size = ButtonSize.Small, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestedAccount(account: LocalAccount, onUse: () -> Unit) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(top = 26.dp).clip(StatsShapes.Tile).background(scheme.surfaceContainer).padding(start = 14.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayerAvatar(PlayerVisual(account.accountId, account.name.orEmpty()), 52.dp, type.rankName.resized(type.featuredValue.fontSize * 0.95f), fill = StatsColors.SelfAvatar, initialColor = StatsColors.OnSelfAvatar)
        Column(Modifier.weight(1f)) {
            Text(stringResource(Res.string.stats_setup_signed_in), style = type.note.copy(fontWeight = HideoutTheme.typography.labelSection.fontWeight), color = scheme.outline, maxLines = 1)
            Text(account.name ?: account.steamId64.toString(), style = type.cardTitle, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Text(stringResource(Res.string.stats_setup_steam_id, account.steamId64.toString()), style = HideoutTheme.typography.monoCount.copy(fontWeight = HideoutTheme.typography.mono.fontWeight), color = scheme.outline, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        }
        HideoutButton(stringResource(Res.string.stats_use_this), onUse)
    }
}

@Composable
internal fun AccountSearch(lookup: AccountLookup, onLookUp: (String) -> Unit, onClear: () -> Unit, onUse: (Long) -> Unit, modifier: Modifier = Modifier) {
    val type = statsType()
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                query = query,
                onQueryChange = {
                    query = it
                    onClear()
                },
                placeholder = stringResource(Res.string.stats_lookup_hint),
                modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    val enter = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) && query.isNotBlank()
                    if (enter) onLookUp(query)
                    enter
                },
            )
            HideoutButton(stringResource(Res.string.stats_lookup_find), { onLookUp(query) }, variant = ButtonVariant.Tonal, enabled = query.isNotBlank())
        }
        when (lookup) {
            AccountLookup.Idle -> Unit
            AccountLookup.Searching -> Note(stringResource(Res.string.stats_lookup_searching))
            AccountLookup.NotFound -> Note(stringResource(Res.string.stats_lookup_not_found))
            is AccountLookup.Failed -> Note(
                stringResource(
                    when (lookup.reason) {
                        LookupFailure.Offline -> Res.string.stats_lookup_offline
                        LookupFailure.Unavailable -> Res.string.stats_lookup_unavailable
                        LookupFailure.NameSearchUnavailable -> Res.string.stats_lookup_names
                    },
                ),
            )
            is AccountLookup.Found -> lookup.players.take(6).forEach { player ->
                Row(
                    Modifier.fillMaxWidth().clip(StatsShapes.SmallRow).background(scheme.surfaceContainer).padding(start = 10.dp, top = 8.dp, end = 10.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerAvatar(player, 36.dp, type.result.resized(type.kda.fontSize))
                    Column(Modifier.weight(1f)) {
                        Text(StatsLabels.playerName(player), style = type.rowTitle, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(Res.string.stats_setup_steam_id, StatsFormat.steamId(player.accountId)),
                            style = HideoutTheme.typography.mono, color = scheme.outline, maxLines = 1,
                        )
                    }
                    HideoutButton(stringResource(Res.string.stats_use), { onUse(player.accountId) }, size = ButtonSize.Small)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = statsType().caption, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

private enum class AccountSlot { Reading, Found, Search }
