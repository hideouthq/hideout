package gg.hideouthq.feature.onboarding

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.library.FolderCheck
import gg.hideouthq.feature.imports.ImportUi

enum class SetupStep { Game, Mods }

@Immutable
data class OnboardingUiState(
    val step: SetupStep? = null,
    val game: GameUi = GameUi(),
    val mods: ImportUi = ImportUi(),
)

@Immutable
data class GameUi(
    /** A folder the user picked that is not Deadlock's, and why. */
    val rejected: FolderCheck.Invalid? = null,
    /** A folder is being chosen. */
    val busy: Boolean = false,
    val separator: Char = '/',
)

interface OnboardingActions {
    fun chooseFolder(title: String)

    /** Opens Hideout as it stands: without the game from the Game step, without an import from the Mods step. */
    fun skip()
}
