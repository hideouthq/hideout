package gg.hideouthq.feature.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.collections.CollectionsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

private const val StopTimeoutMillis = 5_000L

/** Every collection's card for the Collections page, in Discover's order; null until the catalogue has arrived. */
class CollectionsViewModel(catalog: CatalogRepository, collections: CollectionsRepository) : ViewModel() {
    val collections: StateFlow<List<CollectionSummary>?> =
        collectionCards(catalog, collections).stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), null)
}
