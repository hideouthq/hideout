package gg.hideouthq.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutTabs
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.TabContent
import gg.hideouthq.core.designsystem.component.TabItem
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.running
import gg.hideouthq.core.model.Accent
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.ui.LocalSearchFocus
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.ScrollToTopEffect
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.settings.components.BackupsDialog
import gg.hideouthq.feature.settings.components.DeadlockCard
import gg.hideouthq.feature.settings.components.GroupHeading
import gg.hideouthq.feature.settings.components.LicencesDialog
import gg.hideouthq.feature.settings.components.RestoreDialog
import gg.hideouthq.feature.settings.components.SettingsGroup
import gg.hideouthq.feature.settings.components.SteamTrackingCard
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.settings_search_back
import gg.hideouthq.resources.settings_search_empty
import gg.hideouthq.resources.settings_search_placeholder
import gg.hideouthq.resources.settings_search_sections
import gg.hideouthq.resources.settings_search_settings
import gg.hideouthq.resources.settings_search_summary
import gg.hideouthq.resources.settings_tab_attention
import gg.hideouthq.resources.settings_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

internal interface SettingsActions {
    fun inspectSteamTracking()
    fun selectSteamInstallation(id: String)
    fun installSteamTracking()
    fun disableSteamTracking()
    fun backfillSteamHistory()
    fun toggle(setting: SettingId, on: Boolean)
    fun pickAccent(accent: Accent)
    fun pickModCards(style: ModCardStyle)
    fun search(query: String)
    fun detectAgain()
    fun confirmRestore()
    fun cancelRestore()
    fun closeLicences()
    fun showBackups(pane: BackupsPane?)
    fun confirmBackup()
}

@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    tab: SettingsTab,
    onTab: (SettingsTab) -> Unit,
    actions: SettingsActions,
    onAction: (SettingAction) -> Unit,
    modal: WindowModal,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    ScrollToTopEffect(scrollState)
    val rows = settingRows(state)
    val searching = searchWords(state.query).isNotEmpty()
    val search = searchSettings(rows.map { it.text }, state.query)
    val searchFocus = LocalSearchFocus.current
    PaneScaffold(
        modifier = modifier.onKeyEvent { event ->
            val clear = event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.query.isNotEmpty()
            if (clear) {
                actions.search("")
                searchFocus.request()
            }
            clear
        },
        topBar = { SearchTopBar(query = state.query, onQueryChange = actions::search, placeholder = stringResource(Res.string.settings_search_placeholder)) },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = HideoutDimens.MainPane.ContentPadding), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = HideoutDimens.Settings.ContentMaxWidth).fillMaxWidth().padding(top = HideoutDimens.Settings.HeaderTopPadding)) {
                Text(
                    text = stringResource(Res.string.settings_title),
                    style = HideoutTheme.typography.headlinePage,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = HideoutDimens.Settings.TitleHorizontalPadding).semantics { heading() },
                )
                if (searching) {
                    ResultsHeader(search, backTo = tab, onBack = { actions.search("") })
                } else {
                    val attention = stringResource(Res.string.settings_tab_attention)
                    val tabs = shownTabs(state.statsAvailable, state.customGamesAvailable).map { TabItem(it, stringResource(it.label), it == SettingsTab.Game && state.game is GameStatus.Missing, attention) }
                    HideoutTabs(tabs, tab, onTab, Modifier.padding(top = HideoutDimens.Settings.TabsTopMargin))
                }
            }
            ScrollColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = scrollState,
                contentPadding = PaddingValues(bottom = HideoutDimens.Settings.BottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val content = Modifier.widthIn(max = HideoutDimens.Settings.ContentMaxWidth).fillMaxWidth()
                if (searching) {
                    SearchResults(search, rows, state.query, actions, onAction, content)
                } else {
                    TabContent(tab, order = SettingsTab::ordinal, modifier = content) { shown ->
                        TabRows(rows.filter { it.setting.tab == shown }, state, actions, onAction, shown == SettingsTab.Developer && state.statsAvailable)
                    }
                }
            }
        }
    }
    RestoreDialog(state.restore, modal, onConfirm = actions::confirmRestore, onCancel = actions::cancelRestore)
    LicencesDialog(state.licences, modal, onClose = actions::closeLicences)
    BackupsDialog(state.backupsPane, state.backups, state.maintenance?.running != true, modal, onShow = actions::showBackups, onConfirm = actions::confirmBackup)
}

@Composable
private fun TabRows(rows: List<SettingRow>, state: SettingsUiState, actions: SettingsActions, onAction: (SettingAction) -> Unit, showSteam: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = HideoutDimens.Settings.ListTopPadding, bottom = HideoutDimens.Settings.ListBottomPadding)) {
        rows.groupBy { it.setting.group }.entries.forEachIndexed { index, (group, grouped) ->
            group.heading?.let { GroupHeading(stringResource(it), first = index == 0) }
            if (grouped.all { it.setting.control == SettingControl.GameCard }) {
                DeadlockCard(state, onChooseFolder = { onAction(SettingAction.ChooseGameFolder) }, onDetectAgain = actions::detectAgain)
            } else {
                SettingsGroup(grouped, onAction, actions)
            }
        }
        if (showSteam) SteamTrackingCard(state, actions)
    }
}

@Composable
private fun ResultsHeader(search: SettingsSearch, backTo: SettingsTab, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hairline = scheme.outlineVariant
    Row(
        modifier = Modifier
            .padding(top = HideoutDimens.Settings.TabsTopMargin)
            .fillMaxWidth()
            .height(HideoutDimens.Settings.ResultsHeight)
            .drawBehind {
                val y = size.height - HideoutDimens.Hairline.toPx() / 2
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), HideoutDimens.Hairline.toPx())
            }
            .padding(horizontal = HideoutDimens.Settings.HeadingHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val summary = if (search.count == 0) "" else stringResource(
            Res.string.settings_search_summary,
            pluralStringResource(Res.plurals.settings_search_settings, search.count, search.count),
            pluralStringResource(Res.plurals.settings_search_sections, search.sections.size, search.sections.size),
        )
        Text(
            summary,
            style = HideoutTheme.typography.bodyStrong,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        HideoutButton(stringResource(Res.string.settings_search_back, stringResource(backTo.label)), onBack, variant = ButtonVariant.Text, size = ButtonSize.Small)
    }
}

@Composable
private fun SearchResults(search: SettingsSearch, rows: List<SettingRow>, query: String, actions: SettingsActions, onAction: (SettingAction) -> Unit, modifier: Modifier) {
    Column(modifier.padding(top = HideoutDimens.Settings.ListTopPadding, bottom = HideoutDimens.Settings.ListBottomPadding)) {
        if (search.count == 0) {
            Text(
                stringResource(Res.string.settings_search_empty, query.trim()),
                style = HideoutTheme.typography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = HideoutDimens.Settings.EmptyTopPadding),
            )
        }
        val byId = rows.associateBy { it.setting.id }
        search.sections.forEachIndexed { index, section ->
            GroupHeading(stringResource(section.tab.label), first = index == 0)
            val matches = section.matches.associateBy { it.text.setting.id }
            SettingsGroup(
                rows = section.matches.map { byId.getValue(it.text.setting.id) },
                onAction = onAction,
                actions = actions,
                titleRanges = { matches[it]?.title.orEmpty() },
                descriptionRanges = { matches[it]?.description.orEmpty() },
            )
        }
    }
}
