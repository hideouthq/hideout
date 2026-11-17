package gg.hideouthq.core.data

import gg.hideouthq.core.library.LibraryProblem
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * What a mod page knows about installing a mod, without seeing the manifest, and how it asks for one. The desktop app
 * backs it with [gg.hideouthq.core.library.ModLibrary]; a mod with several current files, or a download with
 * several sets, waits in [InstallStatus.NeedsChoice] and is never installed by guessing. The question itself is
 * answered in the shell's install dialog, not on the page.
 */
interface ModInstallations {
    fun status(modId: String): Flow<InstallStatus>

    fun install(modId: String)

    /** Stops an install that is still getting or unpacking its files, and puts away what it got; false when it cannot be stopped now. */
    fun cancel(modId: String): Boolean

    /** Clears a failed install. */
    fun dismiss(modId: String)
}

sealed interface InstallStatus {
    /** The manifest is still loading or cannot be trusted, so whether the mod is installed is not known. */
    data object Unknown : InstallStatus

    data object NotInstalled : InstallStatus

    data object Preparing : InstallStatus

    /** The install waits for the user to choose a file or a set. */
    data object NeedsChoice : InstallStatus

    /** GameBanana asked Hideout to wait: the install starts, or goes on once [started], by itself at [until]. */
    data class Busy(val until: Instant, val started: Boolean = false) : InstallStatus

    /** The file waits for another download to finish. */
    data object Waiting : InstallStatus

    /** [percent] is 0..100. [stalled] while no bytes come and the download tries again by itself. */
    data class Downloading(val percent: Int, val stalled: Boolean = false) : InstallStatus

    /** Downloaded and unpacked; it replaces files Deadlock loads, so it goes on once Deadlock closes. */
    data object WaitingForGame : InstallStatus

    data object Installing : InstallStatus

    /** The install was cancelled and is putting away what it downloaded or unpacked. */
    data object Cancelling : InstallStatus

    /** [enabled] is whether a set of the mod is on in the game. */
    data class Installed(val enabled: Boolean) : InstallStatus

    data class Failed(val problem: LibraryProblem) : InstallStatus
}
