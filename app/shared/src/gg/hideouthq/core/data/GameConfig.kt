package gg.hideouthq.core.data

import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.PendingChange
import kotlinx.coroutines.flow.Flow

const val MaxConfigBytes: Int = 4 * 1024 * 1024

/**
 * A game's config files, replaced as whole files through the edit the journal saved and watched for changes:
 * `game/citadel/gameinfo.gi`, which is also read, and `game/citadel/cfg/autoexec.cfg`. A replacement never overwrites a
 * newer file someone else wrote: the current file is moved aside first and the new one is published only where no file
 * is.
 */
interface GameConfig {
    suspend fun read(root: GameRoot): ConfigRead

    /**
     * Readies [target]'s folder under [root] for a [replace] before the journal saves the edit. Every folder on the way
     * must be plain, never a link or a junction, and `cfg` is made where a fresh install has none. Returns why the
     * folder cannot be used, or null.
     */
    suspend fun prepare(root: GameRoot, target: ConfigTarget): String?

    /**
     * Carries out [edit] with [next], the bytes of its `to`: the file must still be exactly its `from`, or not be there
     * when that is null. Its token names this attempt's own files.
     */
    suspend fun replace(edit: PendingChange.ConfigEdit, next: ByteArray): ReplaceResult

    /** Settles a [replace] of [edit] that was interrupted, keeping whatever someone else wrote. */
    suspend fun recover(edit: PendingChange.ConfigEdit): ConfigRecovery

    /** Emits whenever the file at [path] under `game/citadel`, `gameinfo.gi` or `cfg/autoexec.cfg`, may have changed on disk; fails when it cannot watch. */
    fun changes(root: GameRoot, path: CitadelPath): Flow<Unit>
}

sealed interface ConfigRead {
    class Present(val bytes: ByteArray, val image: FileImage) : ConfigRead

    data object Missing : ConfigRead

    data class Unreadable(val reason: String) : ConfigRead
}

sealed interface ReplaceResult {
    data object Replaced : ReplaceResult

    /** The file is no longer the one [GameConfig.replace] expected; nothing was written. */
    data object Changed : ReplaceResult

    /** Someone else wrote the file while it was being replaced; theirs stays, and the displaced file is kept. */
    data class Conflict(val detail: String) : ReplaceResult

    data class Failed(val reason: String) : ReplaceResult
}

/** How an interrupted [GameConfig.replace] settled. [kept] names files beside the edited one that could not be shown safe to delete. */
sealed interface ConfigRecovery {
    /** The new file is in place. */
    data class Applied(val kept: List<String> = emptyList()) : ConfigRecovery

    /** The original file is in place, or no file is where none was. */
    data class Undone(val kept: List<String> = emptyList()) : ConfigRecovery

    data class Conflict(val detail: String, val kept: List<String> = emptyList()) : ConfigRecovery

    data class Failed(val reason: String) : ConfigRecovery
}
