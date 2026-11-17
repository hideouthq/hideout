package gg.hideouthq.feature.shell

import gg.hideouthq.core.library.ChangeRequest
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.TransferSummary

data class ShellUiState(
    /** Active and waiting transfers, aggregated; null when nothing is downloading. */
    val transfers: TransferSummary? = null,
    /** Every one of [transfers] waits for GameBanana, so no percent moves until it lets them go on. */
    val transfersHeld: Boolean = false,
    val game: GameStatus = GameStatus.Searching,
    /** Sets on in [profiles]' active profile, as asked. */
    val modsOn: Int = 0,
    /** Whether Play starts the game: it is found and this app can launch it. */
    val canPlay: Boolean = false,
    /** What the library needs the user to know, oldest first. */
    val notices: List<ShellNotice> = emptyList(),
    /** The profiles as accepted requests will leave them, shown before their files settle. */
    val profiles: ProfileBook? = null,
    val canEditChoices: Boolean = false,
    val choiceSync: ChoiceSync = ChoiceSync.Settled,
    val saving: Boolean = false,
    /** Profile or toggle work is settling: shown once, and controls that only wait for it keep their full look. */
    val applying: Boolean = false,
    val activity: GameActivity = GameActivity.Unknown,
    val profileEditor: ProfileEditor? = null,
    /** [profileEditor]'s own request is not answered yet; other edits saving do not count. */
    val profileSubmitting: Boolean = false,
    /** Why [profileEditor]'s own request was refused. */
    val profileEditorProblem: LibraryProblem? = null,
    val profileProblem: ProfileFailure? = null,
    val snackbar: ShellSnackbar? = null,
    /** A share link waiting to be put on the clipboard. */
    val shareLink: ShareLink? = null,
)

sealed interface ShellNotice {
    data class InstallFailed(val modId: String, val title: String?, val problem: LibraryProblem) : ShellNotice

    data class LaunchFailed(val modded: Boolean, val problem: LibraryProblem) : ShellNotice

    /** Hideout's lines went from gameinfo.gi and putting them back failed. */
    data class ReapplyFailed(val problem: LibraryProblem) : ShellNotice

    /** Stays until the files reach the saved choices; Try again tries at once, whatever Deadlock is doing, and waits for it to close. */
    data class SavedChoicesFailed(val problem: LibraryProblem) : ShellNotice

    data class ChoiceSaveFailed(val problem: LibraryProblem) : ShellNotice

    /** Uninstalling [installId] was refused. Try again asks for the same captured files. */
    data class ChangeFailed(val installId: String, val title: String, val request: ChangeRequest, val problem: LibraryProblem) : ShellNotice
}

interface NoticeActions {
    fun retryInstall(modId: String)
    fun dismissInstall(modId: String)
    fun play(modded: Boolean)
    fun reapply()
    fun dismissFailure()
    fun retrySavedChoices()
    fun dismissChoiceSaveFailure()
    fun retryChange(installId: String)
    fun dismissChange(installId: String)
}
