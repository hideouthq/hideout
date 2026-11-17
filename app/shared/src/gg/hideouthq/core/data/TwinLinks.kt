package gg.hideouthq.core.data

import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ChangeToken
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.SubmissionIdentity

/**
 * Second names for files Hideout placed, and deletion that checks which file a name still is. A move or an uninstall
 * keeps an anchor, another name of the same file, until its outcome is saved, so a retry can tell Hideout's file from
 * one someone put back at the same path with the same bytes. Equal bytes never make two names one file. Which names
 * to link or delete, and when, is [gg.hideouthq.core.library.ModLibrary]'s.
 */
interface TwinLinks<E> {
    /** Gives the plain file at [existing] the second name [created], never replacing anything already at [created]. */
    suspend fun link(existing: E, created: E): LinkResult

    /** What is at [entry] now: its bytes, and whether it is one plain file with [witness]. */
    suspend fun inspect(entry: E, witness: E): EntryState

    /** Deletes [entry] only while it is one plain file with [witness] and holds [expected]. */
    suspend fun removeTwin(entry: E, witness: E, expected: FileImage): Removal

    /** Deletes [entry] only while it is a plain file holding [expected], whatever other names its file has. */
    suspend fun removeExact(entry: E, expected: FileImage): Removal
}

/** A name Hideout may hold under a game: one of a set's files, or an anchor of one, directly in a [SetFolder]. */
data class GameEntry(val root: GameRoot, val folder: SetFolder, val name: String)

/**
 * A downloaded archive of [source] or, with [anchor], the anchor named with that token beside it. The archive's path
 * says which download folder it is in: `<folder>/<mod id>/<file id>/<name>`.
 */
data class DownloadEntry(val source: SubmissionIdentity, val archive: ArchiveIdentity, val anchor: ChangeToken? = null)

sealed interface LinkResult {
    /** The new name is another name of the same file. */
    data object Linked : LinkResult

    /** Something already has the new name, whatever its bytes; it was left as it is. */
    data object Taken : LinkResult

    /** There is no plain file at the existing name. */
    data object NotThere : LinkResult

    data class Failed(val reason: String) : LinkResult
}

sealed interface EntryState {
    data object Absent : EntryState

    /** A folder, a link, a junction or anything else that is not a plain file. */
    data object NotPlain : EntryState

    /** A plain file holding [image]; [twin] when it is one file with the witness now. */
    data class Present(val image: FileImage, val twin: Boolean) : EntryState

    data class Unreadable(val reason: String) : EntryState
}

/** Opens a game folder in the system's file manager. Read-only: it never creates, moves or deletes anything. */
fun interface FolderOpener {
    /** True when the folder was handed to the file manager; false when it is not there or is not a plain folder. */
    suspend fun open(root: GameRoot, folder: SetFolder): Boolean
}
