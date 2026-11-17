package gg.hideouthq.core.data

import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ArchiveNote
import gg.hideouthq.core.library.ArchiveRejection
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.StagedVpk
import gg.hideouthq.core.library.StagingId
import gg.hideouthq.core.library.UnpackLimits

/**
 * The physical half of installing: extracting archives into Hideout's own staging, and placing files into a game's
 * [SetFolder], `game/citadel/addons` or a mod's `game/citadel/hideout_off/<modId>`. Every name it is given there is
 * one Hideout generates for that folder ([SetFolder.admits]: `pakNN_dir.vpk` in `addons`, `set-<hash>_dir.vpk` in an
 * off folder), or that name plus `.hideout-<id>` for a temporary copy or an anchor ([SetFolder.admitsTemporary]); it
 * refuses any other. Nothing here replaces an existing entry, follows a link or junction below the game folder, or
 * deletes a file whose bytes it cannot match. The order of these steps, and what is saved between them, is
 * [gg.hideouthq.core.library.ModLibrary]'s. Its [TwinLinks] may create a mod's `hideout_off` folder for a new
 * name, as [stage] may, and never any other folder.
 */
interface ModInstaller : TwinLinks<GameEntry> {
    /**
     * Screens every entry of [archive], then extracts only its VPKs, under generated names, into a new staging folder,
     * and reads the start of its notes.
     * A call that ends cancelled leaves no staging folder, even when it was cancelled after extracting and before
     * returning, since no caller owns that folder.
     */
    suspend fun unpack(archive: ArchiveIdentity, limits: UnpackLimits, originalName: String = archive.fileName): UnpackResult

    suspend fun discard(staging: StagingId)

    /** Removes every staging folder; none outlives the run that made it. */
    suspend fun discardAllStaging()

    /** Removes [root]'s `hideout_off` and `hideout_backups` folders, and every folder in them, that hold nothing but empty folders. */
    suspend fun tidy(root: GameRoot) {}

    /**
     * Copies [vpk] into [folder] as [temporary], a name nothing else uses, and reports what is there afterwards. Only
     * here may the folders of [folder] below `game/citadel` be created.
     */
    suspend fun stage(root: GameRoot, folder: SetFolder, vpk: StagedVpk, temporary: String): StageResult

    /**
     * Makes [name] a second name of the verified copy [temporary] in [folder], never replacing an entry already called
     * [name]. [temporary] stays: until the ledger saves [gg.hideouthq.core.library.InstallStep.Releasing], the
     * two being one file is the only proof that [name] is Hideout's. A drive that cannot make hard links gets a copy
     * checked against [temporary]'s bytes, and the two then count as one file while they hold the same bytes.
     */
    suspend fun publish(root: GameRoot, folder: SetFolder, temporary: String, name: String): PublishResult

    /** Whether [name] in [folder] is one file with [temporary] now: the only proof that Hideout published [name]. */
    suspend fun sameFile(root: GameRoot, folder: SetFolder, temporary: String, name: String): Boolean
}

sealed interface UnpackResult {
    /** [vpks] extracted into [staging], and the [notes] the archive holds beside them. */
    data class Unpacked(val staging: StagingId, val vpks: List<StagedVpk>, val notes: List<ArchiveNote> = emptyList()) : UnpackResult

    data class Rejected(val rejection: ArchiveRejection) : UnpackResult

    data class Failed(val reason: String) : UnpackResult
}

sealed interface StageResult {
    data class Written(val image: FileImage) : StageResult

    data class Failed(val reason: String) : StageResult
}

sealed interface PublishResult {
    data object Published : PublishResult

    /** Something already has the name; it was left as it is. */
    data object Taken : PublishResult

    data class Failed(val reason: String) : PublishResult
}

sealed interface Removal {
    data object Removed : Removal

    data object Absent : Removal

    /** Something is there that is not the expected file, so it stays. */
    data class Kept(val reason: String) : Removal

    data class Failed(val reason: String) : Removal
}
