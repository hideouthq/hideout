package gg.hideouthq.feature.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.SkeletonBlock
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.component.rememberShimmer
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.ScrollToTopEffect
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.catalog.CatalogBusy
import gg.hideouthq.core.ui.catalog.CatalogFreshnessBar
import gg.hideouthq.core.ui.catalog.CatalogLoadError
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.discover.components.CollectionsSection
import gg.hideouthq.feature.discover.components.DiscoverSkeleton
import gg.hideouthq.feature.discover.components.HeroPanel
import gg.hideouthq.feature.discover.components.HeroStrip
import gg.hideouthq.feature.discover.components.HudAndSounds
import gg.hideouthq.feature.discover.components.NewsSection
import gg.hideouthq.feature.discover.components.TrendingCards
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.discover_keyword_gameplay
import gg.hideouthq.resources.discover_keyword_skins
import gg.hideouthq.resources.discover_trending_gameplay
import gg.hideouthq.resources.discover_trending_skins
import gg.hideouthq.resources.search_catalogue
import org.jetbrains.compose.resources.stringResource

@Composable
fun DiscoverScreen(state: DiscoverUiState, actions: DiscoverActions, modifier: Modifier = Modifier, customGames: @Composable (Modifier) -> Unit = {}) {
    val scroll = rememberScrollState()
    ScrollToTopEffect(scroll)
    val padding = HideoutDimens.MainPane.ContentPadding
    PaneScaffold(
        modifier = modifier,
        topBar = { SearchTopBar(query = "", onQueryChange = actions::search, placeholder = stringResource(Res.string.search_catalogue)) },
        scrolled = scroll.isScrolled,
        staggered = true,
    ) {
        state.loadError?.let { error ->
            CatalogLoadError(error, actions::refresh, Modifier.fillMaxWidth().padding(padding))
            return@PaneScaffold
        }
        state.freshness.busyUntil?.takeIf { state.loading }?.let { until ->
            CatalogBusy(until, Modifier.fillMaxWidth().padding(padding))
            return@PaneScaffold
        }
        if (state.loading) {
            DiscoverSkeleton(state.freshness, actions::refresh, Modifier.padding(horizontal = padding))
            return@PaneScaffold
        }
        ScrollColumn(
            modifier = Modifier.fillMaxSize(),
            state = scroll,
            contentPadding = PaddingValues(start = padding, end = padding, bottom = padding),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.SectionGap),
        ) {
            Column(Modifier.fillMaxWidth().staggeredEnter(0)) {
                CatalogFreshnessBar(state.freshness, actions::refresh, Modifier.align(Alignment.End))
                if (state.current == null && state.filling) {
                    SkeletonBlock(rememberShimmer(), null, HideoutDimens.Hero.Height, HideoutShapes.Hero)
                } else {
                    HeroPanel(state, actions, Modifier.fillMaxWidth())
                }
            }
            HeroStrip(state.heroes, state.heroCount, actions::browseHero, actions::browseAllHeroes, Modifier.staggeredEnter(1))
            customGames(Modifier.staggeredEnter(2))
            TrendingCards(
                stringResource(Res.string.discover_trending_skins), stringResource(Res.string.discover_keyword_skins), HideoutIcons.SectionSkins, HideoutTheme.colors.sectionSkins,
                state.trendingSkins, HideoutDimens.Card.HeightDiscover, state.filling, actions::openMod, { actions.browseCategory(ModCategory.Skins) }, Modifier.staggeredEnter(3),
            )
            if (state.collections.isNotEmpty() || state.filling) {
                CollectionsSection(state.collections, state.filling, actions::openCollection, actions::openCollections, Modifier.staggeredEnter(4))
            }
            if (state.trendingGameplay.isNotEmpty() || state.filling) {
                TrendingCards(
                    stringResource(Res.string.discover_trending_gameplay), stringResource(Res.string.discover_keyword_gameplay), HideoutIcons.SectionGameplay, HideoutTheme.colors.sectionGameplay,
                    state.trendingGameplay, HideoutDimens.Card.HeightGameplay, state.filling, actions::openMod, { actions.browseCategory(ModCategory.Gameplay) }, Modifier.staggeredEnter(5),
                )
            }
            HudAndSounds(
                hud = state.trendingHud,
                sounds = state.sounds,
                filling = state.filling,
                onOpen = actions::openMod,
                onSeeAllHud = { actions.browseCategory(ModCategory.Hud) },
                onSeeAllSounds = { actions.browseCategory(ModCategory.Sounds) },
                onToggleSample = actions::toggleSample,
                onInstall = actions::install,
                modifier = Modifier.staggeredEnter(5),
            )
            NewsSection(state.news, state.now, actions::readNews, actions::openAllNews, Modifier.staggeredEnter(6))
        }
    }
}
