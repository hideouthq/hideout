package gg.hideouthq.feature.collections

import gg.hideouthq.core.designsystem.component.gaps
import gg.hideouthq.core.designsystem.component.LocalModCardStyle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.ui.AdaptiveGrid
import gg.hideouthq.core.ui.catalog.CardTransition.card
import gg.hideouthq.core.ui.catalog.CardTransition.cover
import gg.hideouthq.core.ui.catalog.CardTransition.decoration
import gg.hideouthq.core.ui.catalog.CardTransition.title
import gg.hideouthq.core.ui.catalog.CatalogCard
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.collections.components.CollectionBanner
import gg.hideouthq.feature.collections.components.CollectionFilters
import gg.hideouthq.feature.collections.components.CollectionPane
import gg.hideouthq.feature.collections.components.ShowMore
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.collection_empty_message
import gg.hideouthq.resources.collection_empty_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun CollectionScreen(
    state: CollectionUiState,
    actions: CollectionActions,
    copied: Boolean,
    onSearch: (String) -> Unit,
    onDiscover: () -> Unit,
    onCollections: () -> Unit,
    onOpenMod: (String) -> Unit,
    onShare: () -> Unit,
) {
    val page = state.page
    CollectionPane(onSearch, onDiscover, page?.summary?.title, onCollections) {
        if (state.loading) return@CollectionPane
        if (page == null) {
            EmptyState(
                icon = HideoutIcons.Layers,
                title = stringResource(Res.string.collection_empty_title),
                message = stringResource(Res.string.collection_empty_message),
                modifier = Modifier.fillMaxWidth().padding(top = HideoutDimens.MainPane.ContentPadding),
            )
            return@CollectionPane
        }
        CollectionBanner(page.summary, copied, actions::openProfileSheet, onShare, Modifier.staggeredEnter(0))
        CollectionFilters(page.summary.skins, page.heroes, page.hero, page.sort, actions::filterHero, actions::sort, Modifier.staggeredEnter(1))
        val gaps = LocalModCardStyle.current.gaps
        AdaptiveGrid(HideoutDimens.Card.MinColumnDiscover, Modifier.staggeredEnter(2), columnGap = gaps.column, rowGap = gaps.row) {
            page.cards.forEach { item ->
                CatalogCard(
                    item = item,
                    onClick = { onOpenMod(item.id) },
                    modifier = Modifier.card(),
                    height = HideoutDimens.Card.HeightBrowse,
                    coverModifier = Modifier.cover(item.id),
                    titleModifier = Modifier.title(item.id),
                    decorationModifier = Modifier.decoration(item.id),
                )
            }
        }
        if (page.cards.size < page.matching) ShowMore(page.cards.size, page.matching, actions::showMore)
    }
}
