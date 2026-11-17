package gg.hideouthq.feature.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.AppContainer

@Composable
fun OnboardingRoute(container: AppContainer, onImported: () -> Unit) {
    val viewModel = viewModel { OnboardingViewModel(container.modLibrary, container.migration, container.preferences, container.folderPicker, onImported, container.analytics) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingScreen(state, viewModel, viewModel.imports)
}
