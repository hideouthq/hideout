package gg.hideouthq.feature.stats.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.component.PillTabs
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.core.designsystem.component.SearchFieldSize
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.brightened
import gg.hideouthq.feature.stats.StatsFilter
import gg.hideouthq.feature.stats.StatsMode
import gg.hideouthq.feature.stats.StatsPeriod
import gg.hideouthq.feature.stats.StatsTab
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.stats_mode_all
import gg.hideouthq.resources.stats_mode_normal
import gg.hideouthq.resources.stats_mode_ranked
import gg.hideouthq.resources.stats_period
import gg.hideouthq.resources.stats_search_no_option
import gg.hideouthq.resources.stats_tab_heroes
import gg.hideouthq.resources.stats_tab_matches
import gg.hideouthq.resources.stats_tab_overview
import gg.hideouthq.resources.stats_tab_teammates
import gg.hideouthq.resources.stats_tabs
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StatsTabBar(
    tab: StatsTab,
    filter: StatsFilter,
    matchCount: Int?,
    heroCount: Int?,
    seasonOffered: Boolean,
    onTab: (StatsTab) -> Unit,
    onMode: (StatsMode) -> Unit,
    onPeriod: (StatsPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val labels = mapOf(
            StatsTab.Overview to stringResource(Res.string.stats_tab_overview),
            StatsTab.Matches to stringResource(Res.string.stats_tab_matches),
            StatsTab.Heroes to stringResource(Res.string.stats_tab_heroes),
            StatsTab.Teammates to stringResource(Res.string.stats_tab_teammates),
        )
        val matches = matchCount?.let { StatsLabels.count(it) }
        val heroes = heroCount?.let { StatsLabels.count(it) }
        PillTabs(
            StatsTab.entries, tab, onTab, { labels.getValue(it) },
            icon = {
                when (it) {
                    StatsTab.Overview -> HideoutIcons.StatsOverview
                    StatsTab.Matches -> HideoutIcons.StatsMatches
                    StatsTab.Heroes -> HideoutIcons.Person
                    StatsTab.Teammates -> HideoutIcons.StatsTeammates
                }
            },
            groupLabel = stringResource(Res.string.stats_tabs),
            count = {
                when (it) {
                    StatsTab.Matches -> matches
                    StatsTab.Heroes -> heroes
                    else -> null
                }
            },
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val modes = mapOf(
                StatsMode.All to stringResource(Res.string.stats_mode_all),
                StatsMode.Ranked to stringResource(Res.string.stats_mode_ranked),
                StatsMode.Normal to stringResource(Res.string.stats_mode_normal),
            )
            SegmentedControl(StatsMode.entries, filter.mode, onMode, { modes.getValue(it) }, segmentHeight = 34.dp, segmentPadding = 14.dp)
            PeriodMenu(filter.period, seasonOffered, onPeriod)
        }
    }
}

@Composable
private fun PeriodMenu(period: StatsPeriod, seasonOffered: Boolean, onPeriod: (StatsPeriod) -> Unit) {
    val entries = StatsPeriod.entries.map { option ->
        MenuOption(StatsLabels.period(option), option == period, enabled = option != StatsPeriod.Season || seasonOffered) { onPeriod(option) }
    }
    MenuPill(StatsLabels.period(period), HideoutIcons.Period, entries, stringResource(Res.string.stats_period), height = 40.dp)
}

internal class MenuOption(val label: String, val selected: Boolean, val enabled: Boolean = true, val searchable: Boolean = true, val onClick: () -> Unit)

internal fun searchOptions(options: List<MenuOption>, query: String): List<MenuOption> {
    fun plain(text: String) = text.filter(Char::isLetterOrDigit).lowercase()
    val wanted = plain(query)
    if (wanted.isEmpty()) return options
    fun rank(label: String): Int? = when {
        plain(label).startsWith(wanted) || label.split(' ', '-').any { plain(it).startsWith(wanted) } -> 0
        plain(label).contains(wanted) -> 1
        else -> null
    }
    return options.filter { it.searchable }.mapNotNull { option -> rank(option.label)?.let { option to it } }.sortedBy { it.second }.map { it.first }
}

@Composable
internal fun MenuPill(
    label: String,
    icon: ImageVector,
    options: List<MenuOption>,
    menuLabel: String,
    height: Dp = 36.dp,
    fill: Color = MaterialTheme.colorScheme.surfaceContainer,
    search: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val trigger = remember { FocusRequester() }
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (states.hovered) fill.brightened(1.12f) else fill, HideoutTheme.motion.effectsDefault(), label = "menuPill")
    Box {
        Row(
            Modifier
                .height(height)
                .drawBehind { drawRoundRect(background, cornerRadius = CornerRadius(size.height / 2)) }
                .focusRing(states.focusVisible, HideoutShapes.Full)
                .focusRequester(trigger)
                .hideoutClickable(source, role = Role.DropdownList, onClickLabel = menuLabel) { open = true }
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HideoutIcon(icon, null, size = 16.dp, tint = scheme.onSurfaceVariant)
            Text(label, style = HideoutTheme.typography.labelPill, color = scheme.onSurfaceVariant, maxLines = 1)
            HideoutIcon(HideoutIcons.ChevronDown, null, size = 16.dp, tint = scheme.onSurfaceVariant)
        }
        if (open) {
            val shown = searchOptions(options, query)
            val close = { byKey: Boolean ->
                open = false
                query = ""
                if (byKey) trigger.requestFocus()
            }
            HideoutMenu(
                entries = shown.map { option ->
                    MenuEntry.Item(option.label, if (option.selected) HideoutIcons.Check else HideoutIcons.Blank, option.onClick, enabled = option.enabled)
                }.ifEmpty {
                    listOf(MenuEntry.Content { Text(stringResource(Res.string.stats_search_no_option), style = statsType().caption, color = scheme.outline, modifier = Modifier.padding(12.dp)) })
                },
                onClose = close,
                label = menuLabel,
                header = search?.let { placeholder ->
                    { focus ->
                        SearchField(
                            query, { query = it }, placeholder, size = SearchFieldSize.Small, focusRequester = focus,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp).onPreviewKeyEvent { event ->
                                val pick = event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)
                                if (pick) shown.firstOrNull { it.enabled }?.let { option ->
                                    close(true)
                                    option.onClick()
                                }
                                pick
                            },
                        )
                    }
                },
            )
        }
    }
}
