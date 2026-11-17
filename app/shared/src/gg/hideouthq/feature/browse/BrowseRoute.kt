package gg.hideouthq.feature.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.ModLabels

@Composable
fun BrowseRoute(catalog: CatalogRepository, library: ModLibrary, preferences: Preferences, start: BrowseStart, onOpenMod: (String) -> Unit) {
    val categories = ModCategory.entries.associateWith { ModLabels.plural(it) }
    val viewModel = viewModel { BrowseViewModel(catalog, start, categories, library, preferences) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BrowseScreen(state, viewModel, onOpenMod)
}
