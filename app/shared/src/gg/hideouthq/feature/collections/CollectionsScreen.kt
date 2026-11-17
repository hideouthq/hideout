package gg.hideouthq.feature.collections

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.collections.components.CollectionGrid
import gg.hideouthq.feature.collections.components.CollectionPane

@Composable
fun CollectionsRoute(
    catalog: CatalogRepository,
    collections: CollectionsRepository,
    onSearch: (String) -> Unit,
    onDiscover: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val viewModel = viewModel { CollectionsViewModel(catalog, collections) }
    val shown by viewModel.collections.collectAsStateWithLifecycle()
    CollectionPane(onSearch, onDiscover, current = null, onCollections = null) {
        shown?.let { CollectionGrid(it, onOpen, Modifier.staggeredEnter(0)) }
    }
}
