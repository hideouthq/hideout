package gg.hideouthq.feature.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.ScrollToTopEffect
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.ui.OpenPanelButton
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.catalog.CardTransition.card
import gg.hideouthq.core.ui.catalog.CardTransition.cover
import gg.hideouthq.core.ui.catalog.CardTransition.decoration
import gg.hideouthq.core.ui.catalog.CardTransition.title
import gg.hideouthq.core.ui.catalog.CatalogCard
import gg.hideouthq.core.ui.catalog.CatalogFreshnessBar
import gg.hideouthq.core.ui.catalog.CatalogBusy
import gg.hideouthq.core.ui.catalog.CatalogLoadError
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.browse.components.ActiveFilter
import gg.hideouthq.feature.browse.components.BrowseControls
import gg.hideouthq.feature.browse.components.BrowseFilterPanel
import gg.hideouthq.feature.browse.components.BrowseGrid
import gg.hideouthq.feature.browse.components.ResultLine
import gg.hideouthq.feature.browse.components.SuggestionStrip
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.filters_3d_preview
import gg.hideouthq.resources.browse_open_filters
import gg.hideouthq.resources.search_catalogue
import org.jetbrains.compose.resources.stringResource

interface BrowseActions {
    fun setQuery(value: String)
    fun setCategory(value: ModCategory?)
    fun setSort(value: BrowseSort)
    fun applySuggestion(suggestion: BrowseSuggestion)
    fun toggleHero(heroId: String, selected: Boolean)
    fun setHeroQuery(value: String)
    fun setShowAllHeroes(value: Boolean)
    fun setOnly3d(value: Boolean)
    fun setCurrentPatch(value: Boolean)
    fun setBlurMature(value: Boolean)
    fun setUpdated(value: UpdatedWithin)
    fun clearPanel()
    fun clearAll()
    fun refresh()
}

@Composable
fun BrowseScreen(state: BrowseUiState, actions: BrowseActions, onOpenMod: (String) -> Unit, modifier: Modifier = Modifier) {
    val gridState = rememberLazyGridState()
    ScrollToTopEffect(gridState)
    val filters = state.filters
    val motion = HideoutTheme.motion
    PaneScaffold(
        modifier = modifier,
        topBar = {
            SearchTopBar(
                query = filters.query,
                onQueryChange = actions::setQuery,
                placeholder = stringResource(Res.string.search_catalogue),
                focusOnArrival = true,
                actions = { OpenPanelButton(stringResource(Res.string.browse_open_filters), HideoutIcons.Filter) },
            )
        },
        scrolled = gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0,
        staggered = true,
        sidePanel = { BrowseFilterPanel(filters, state.heroChips, state.heroCount, state.viewerFilters, state.mature, actions) },
    ) {
        state.loadError?.let { error ->
            CatalogLoadError(error, actions::refresh, Modifier.fillMaxWidth().padding(HideoutDimens.MainPane.ContentPadding))
            return@PaneScaffold
        }
        state.freshness.busyUntil?.takeIf { state.loading }?.let { until ->
            CatalogBusy(until, Modifier.fillMaxWidth().padding(HideoutDimens.MainPane.ContentPadding))
            return@PaneScaffold
        }
        BrowseGrid(
            results = state.results,
            loading = state.loading,
            state = gridState,
            onClearFilters = actions::clearAll,
            search = filters.query.trim().takeIf { it.isNotEmpty() && !filters.narrowed },
            header = {
                Column {
                    BrowseControls(filters.category, filters.sort, actions::setCategory, actions::setSort, Modifier.staggeredEnter(0))
                    AnimatedVisibility(
                        visible = state.suggestions.isNotEmpty(),
                        enter = expandVertically(motion.spatialDefault()) + fadeIn(motion.effectsDefault()),
                        exit = shrinkVertically(motion.spatialDefault()) + fadeOut(motion.exitFade()),
                    ) {
                        Column {
                            Spacer(Modifier.height(HideoutDimens.MainPane.BlockGap))
                            SuggestionStrip(state.suggestions, actions::applySuggestion)
                        }
                    }
                    Spacer(Modifier.height(HideoutDimens.MainPane.BlockGap))
                    Row(Modifier.staggeredEnter(0), verticalAlignment = Alignment.CenterVertically) {
                        ResultLine(
                            count = state.results.size,
                            category = filters.category,
                            query = filters.query,
                            active = activeFilters(state, actions),
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            CatalogFreshnessBar(state.freshness, actions::refresh)
                        }
                    }
                }
            },
            card = { item, cardModifier ->
                CatalogCard(
                    item = item,
                    onClick = { onOpenMod(item.id) },
                    modifier = cardModifier.card(),
                    height = HideoutDimens.Card.HeightBrowse,
                    coverModifier = Modifier.cover(item.id),
                    titleModifier = Modifier.title(item.id),
                    decorationModifier = Modifier.decoration(item.id),
                )
            },
        )
    }
}

@Composable
private fun activeFilters(state: BrowseUiState, actions: BrowseActions): List<ActiveFilter> {
    val heroes = state.selectedHeroes.map { ActiveFilter("hero-${it.id}", it.name) { actions.toggleHero(it.id, false) } }
    val preview = stringResource(Res.string.filters_3d_preview)
    return heroes + listOfNotNull(ActiveFilter("only3d", preview) { actions.setOnly3d(false) }.takeIf { state.filters.only3d })
}
