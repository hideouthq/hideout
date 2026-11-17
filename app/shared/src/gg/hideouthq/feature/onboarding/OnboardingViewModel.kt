package gg.hideouthq.feature.onboarding

import gg.hideouthq.core.analytics.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.migration.Migration
import gg.hideouthq.feature.imports.ImportActions
import gg.hideouthq.feature.imports.ImportPicker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val StopTimeoutMillis = 5_000L

class OnboardingViewModel(
    private val library: ModLibrary,
    private val migration: Migration,
    private val preferences: Preferences,
    private val folderPicker: FolderPicker,
    private val onImported: () -> Unit = {},
    analytics: UsageAnalytics? = null,
) : ViewModel(), OnboardingActions {

    private data class Local(val step: SetupStep? = null, val rejected: FolderCheck.Invalid? = null, val folderBusy: Boolean = false)

    private val usage = analytics?.begin(UsageOperation.Setup)
    private val local = MutableStateFlow(Local())
    private val picker = ImportPicker(migration, folderPicker, viewModelScope)

    val imports: ImportActions get() = picker

    val uiState: StateFlow<OnboardingUiState> =
        combine(local, picker.ui) { local, mods -> OnboardingUiState(local.step, GameUi(local.rejected, local.folderBusy, library.separator), mods) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), OnboardingUiState())

    init {
        viewModelScope.launch {
            library.state.first { it.game !is GameStatus.Searching }
            decide()
        }
        viewModelScope.launch {
            picker.ui.first { it.settled }
            onImported()
            finish()
        }
    }

    override fun chooseFolder(title: String) {
        if (local.value.folderBusy) return
        local.update { it.copy(folderBusy = true) }
        viewModelScope.launch {
            try {
                val picked = folderPicker.pick(title, local.value.rejected?.path) ?: return@launch
                val check = library.useGameFolder(picked)
                local.update { it.copy(rejected = check as? FolderCheck.Invalid) }
                if (check !is FolderCheck.Invalid) {
                    picker.lookAgain()
                    decide()
                }
            } finally {
                local.update { it.copy(folderBusy = false) }
            }
        }
    }

    override fun skip() = finish(UsageOutcome.Skipped)

    private suspend fun decide() {
        if (library.state.value.game !is GameStatus.Ready) return local.update { it.copy(step = SetupStep.Game) }
        library.chooseLoading()
        val mods = picker.ui.first { !it.looking }
        if (mods.offers || mods.importing || mods.stopped) local.update { it.copy(step = SetupStep.Mods) } else finish()
    }

    private fun finish(outcome: UsageOutcome = UsageOutcome.Completed) {
        migration.dismiss()
        viewModelScope.launch {
            preferences.finishSetup()
            usage?.finish(outcome, UsageStage.Setup)
        }
    }
}
