package gg.hideouthq.feature.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.navigation.HideoutNavigator
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.copyText
import kotlinx.coroutines.launch

class StatsNavigation(private val navigator: HideoutNavigator, private val own: () -> Long?) {
    fun openMatch(matchId: Long, visiting: Long?) = navigator.navigate(Route.StatsMatch(matchId, visiting?.takeIf { it != own() }))
    fun openPlayer(accountId: Long) = navigator.navigate(Route.Stats(visiting = accountId.takeIf { it != own() }))
    fun browseSkins(catalogId: String) = navigator.navigate(Route.Browse(heroId = catalogId, category = ModCategory.Skins))
    fun backToYou() = navigator.navigate(Route.Stats())
    fun back() {
        navigator.back()
    }
}

@Composable
fun StatsRoute(source: StatsSource, visiting: Long?, navigation: StatsNavigation) {
    val viewModel = viewModel { StatsViewModel(source, visiting) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, navigation) { RouteActions(viewModel, navigation, visiting) }
    StatsScreen(state, actions)
}

private class RouteActions(
    private val vm: StatsViewModel,
    private val navigation: StatsNavigation,
    private val visiting: Long?,
) : StatsActions, StatsPageActions by vm {
    override fun browseSkins(catalogId: String) = navigation.browseSkins(catalogId)
    override fun backToYou() = navigation.backToYou()

    override fun openMatch(matchId: Long) {
        vm.setQuery("")
        navigation.openMatch(matchId, visiting)
    }

    override fun openPlayer(accountId: Long) {
        vm.setQuery("")
        navigation.openPlayer(accountId)
    }
}

@Composable
fun StatsMatchRoute(source: StatsSource, matchId: Long, visiting: Long?, navigation: StatsNavigation) {
    val viewModel = viewModel { StatsMatchViewModel(source, matchId, visiting) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val uris = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel, navigation, clipboard, uris, scope) {
        object : StatsMatchActions by viewModel {
            override fun back() = navigation.back()
            override fun chooseAccount() = navigation.backToYou()

            override fun copy(text: String, onCopied: () -> Unit) {
                scope.launch { if (clipboard.copyText(text)) onCopied() }
            }

            override fun openOnApi(matchId: Long) = uris.openUri(deadlockApiMatchUrl(matchId))

            override fun openMatch(matchId: Long) {
                viewModel.setQuery("")
                navigation.openMatch(matchId, visiting)
            }

            override fun openPlayer(accountId: Long) {
                viewModel.setQuery("")
                navigation.openPlayer(accountId)
            }
        }
    }
    StatsMatchScreen(state, matchId, actions)
}
