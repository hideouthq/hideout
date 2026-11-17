package gg.hideouthq.feature.crosshairs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.PageHeader
import gg.hideouthq.core.designsystem.component.PillTabs
import gg.hideouthq.core.designsystem.component.TabContent
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.navigation.CrosshairsTab
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.crosshairs.components.BrowseTab
import gg.hideouthq.feature.crosshairs.components.HeaderButton
import gg.hideouthq.feature.crosshairs.components.ImportDialog
import gg.hideouthq.feature.crosshairs.components.InUsePill
import gg.hideouthq.feature.crosshairs.components.YoursTab
import gg.hideouthq.feature.crosshairs.components.rememberCopied
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.crosshairs_copied
import gg.hideouthq.resources.crosshairs_import
import gg.hideouthq.resources.crosshairs_share
import gg.hideouthq.resources.crosshairs_subtitle
import gg.hideouthq.resources.crosshairs_tab_browse
import gg.hideouthq.resources.crosshairs_tab_yours
import gg.hideouthq.resources.crosshairs_title
import gg.hideouthq.resources.search_catalogue
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CrosshairsScreen(state: CrosshairsUiState, actions: CrosshairsActions, modal: WindowModal, onSearch: (String) -> Unit) {
    val title = stringResource(Res.string.crosshairs_title)
    val tabs = mapOf(CrosshairsTab.Browse to stringResource(Res.string.crosshairs_tab_browse), CrosshairsTab.Yours to stringResource(Res.string.crosshairs_tab_yours))
    PaneScaffold(
        topBar = { SearchTopBar("", onSearch, stringResource(Res.string.search_catalogue)) },
        staggered = true,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PageHeader(title, stringResource(Res.string.crosshairs_subtitle), Modifier.fillMaxWidth().staggeredEnter(0)) {
                HeaderActions(state, actions)
            }
            PillTabs(
                CrosshairsTab.entries, state.tab, actions::selectTab,
                label = tabs::getValue,
                icon = { if (it == CrosshairsTab.Browse) HideoutIcons.Grid else HideoutIcons.Crosshairs },
                groupLabel = title,
                modifier = Modifier.padding(vertical = 2.dp).staggeredEnter(1),
                count = { if (it == CrosshairsTab.Yours) state.yours.cards.size.toString() else null },
            )
            TabContent(state.tab, { it.ordinal }, Modifier.weight(1f).fillMaxWidth().staggeredEnter(2)) { tab ->
                when (tab) {
                    CrosshairsTab.Browse -> BrowseTab(state, actions)
                    CrosshairsTab.Yours -> YoursTab(state, actions, modal)
                }
            }
        }
    }
    ImportDialog(state.importing, modal, actions)
}

@Composable
private fun HeaderActions(state: CrosshairsUiState, actions: CrosshairsActions) {
    if (state.tab == CrosshairsTab.Browse) state.inUse?.let { InUsePill(it, actions::editInUse) }
    HeaderButton(stringResource(Res.string.crosshairs_import), HideoutIcons.Import, actions::openImport)
    if (state.tab == CrosshairsTab.Yours) {
        var copied by rememberCopied()
        HeaderButton(
            stringResource(if (copied) Res.string.crosshairs_copied else Res.string.crosshairs_share),
            if (copied) HideoutIcons.Check else HideoutIcons.Share,
        ) {
            if (state.yours.selected != null) actions.share { copied = true }
        }
    }
}
