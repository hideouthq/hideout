package gg.hideouthq.feature.shell

import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.UndoToken
import gg.hideouthq.core.library.Undoable
import gg.hideouthq.core.sharing.InstallLink

sealed interface ProfileEditor {
    data class New(
        val name: String = "",
        val colour: ProfileColour,
        val copyFrom: ProfileId?,
        val activate: Boolean = true,
    ) : ProfileEditor
    data class Rename(val id: ProfileId, val name: String) : ProfileEditor
}

interface ProfileActions {
    fun newProfile()
    fun switchProfile(id: ProfileId)
    fun renameProfile(id: ProfileId)
    fun duplicateProfile(id: ProfileId)
    fun recolourProfile(id: ProfileId, colour: ProfileColour)
    fun deleteProfile(id: ProfileId)
    fun setProfileName(name: String)
    fun setProfileColour(colour: ProfileColour)
    fun setProfileSeed(id: ProfileId?)
    fun setActivateProfile(activate: Boolean)
    fun confirmProfile()
    fun dismissProfile()
    /** Focus left the sidebar's inline rename of [id]: a name the library takes is saved, any other is dropped. */
    fun leaveRename(id: ProfileId)
    /** Makes a link to [id]'s mods and hands it to [ShellUiState.shareLink] to copy. */
    fun shareProfile(id: ProfileId)
    fun importProfile()
}

data class ProfileFailure(val problem: LibraryProblem, val switchTarget: ProfileId? = null)

data class ShellSnackbar(val id: Long, val message: SnackbarMessage, val undo: UndoToken?)

/** A link to [profile] to copy: a short one when the site kept it, else the long one; [leftOut] mods it turns on are not in it. */
data class ShareLink(val text: String, val profile: String, val leftOut: Int, val short: Boolean)

sealed interface SnackbarMessage {
    data class Edit(val undoes: Undoable) : SnackbarMessage

    data class LinesPutBack(val manager: Manager) : SnackbarMessage

    data class Sharing(val profile: String) : SnackbarMessage

    data class Shared(val link: ShareLink) : SnackbarMessage

    data class NothingToShare(val profile: String) : SnackbarMessage

    data object CopyFailed : SnackbarMessage

    data class LinkRefused(val why: InstallLink.Refused) : SnackbarMessage
}
