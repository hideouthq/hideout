package gg.hideouthq.feature.discover

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.ModInstallations
import gg.hideouthq.core.data.NewsRepository
import gg.hideouthq.core.data.SoundSamplePlayer
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.data.news.SteamNewsPage
import gg.hideouthq.core.model.NewsPost
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.discover.components.NewsReader
import kotlinx.coroutines.flow.Flow

@Composable
fun DiscoverRoute(
    catalog: CatalogRepository,
    installations: ModInstallations,
    player: SoundSamplePlayer,
    navigation: DiscoverNavigation,
    news: NewsRepository,
    modal: WindowModal,
    preferredHeroes: Flow<List<String>>,
    collections: CollectionsRepository,
    customGames: @Composable (Modifier) -> Unit = {},
) {
    val viewModel = viewModel { DiscoverViewModel(catalog, installations, player, news, preferredHeroes, collections) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.stopSample() } }
    var reading by remember { mutableStateOf<NewsPost?>(null) }
    val uriHandler = LocalUriHandler.current
    val actions = remember(viewModel, navigation, uriHandler) {
        object : DiscoverActions, DiscoverNavigation by navigation, DiscoverPageActions by viewModel {
            override fun install(modId: String) = viewModel.install(modId, navigation::openMod)
            override fun readNews(post: NewsPost) { reading = post }
            override fun openAllNews() = uriHandler.openUri(SteamNewsPage)
        }
    }
    DiscoverScreen(state, actions, customGames = customGames)
    NewsReader(reading, state.now, modal, onOpen = { uriHandler.openUri(it.url) }, onDismiss = { reading = null })
}
