package gg.hideouthq.feature.discover.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AdaptiveGrid
import gg.hideouthq.feature.collections.CollectionSummary
import gg.hideouthq.feature.collections.components.CollectionGrid
import gg.hideouthq.feature.collections.components.CollectionsInARow
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.collections_all
import gg.hideouthq.resources.discover_collections
import gg.hideouthq.resources.discover_keyword_collections
import org.jetbrains.compose.resources.stringResource

private val CollectionCardHeight = 222.dp

@Composable
internal fun CollectionsSection(collections: List<CollectionSummary>, filling: Boolean, onOpen: (String) -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    Section(
        stringResource(Res.string.discover_collections), stringResource(Res.string.discover_keyword_collections),
        HideoutIcons.SectionCollections, HideoutTheme.colors.sectionCollections, modifier,
        trailing = { if (collections.size > CollectionsInARow) LinkButton(stringResource(Res.string.collections_all, collections.size), onAll) },
    ) {
        if (collections.isEmpty() && filling) {
            AdaptiveGrid(HideoutDimens.Collection.MinCardWidth) { CardsSkeleton(CollectionsInARow, CollectionCardHeight) }
        } else {
            CollectionGrid(collections.take(CollectionsInARow), onOpen)
        }
    }
}
