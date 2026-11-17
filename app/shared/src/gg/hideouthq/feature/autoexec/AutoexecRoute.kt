package gg.hideouthq.feature.autoexec

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.autoexec.Autoexec
import gg.hideouthq.core.data.ConfigFiles
import gg.hideouthq.core.library.ModLibrary

@Composable
fun AutoexecRoute(autoexec: Autoexec, library: ModLibrary, files: ConfigFiles, onSearch: (String) -> Unit, onChooseGame: () -> Unit) {
    val viewModel = viewModel { AutoexecViewModel(autoexec, library, files) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AutoexecScreen(state, viewModel.editor, viewModel, onSearch, onChooseGame)
}
