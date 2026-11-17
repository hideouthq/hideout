package gg.hideouthq.core.data

import gg.hideouthq.core.data.deadlockstats.SteamAccountId
import gg.hideouthq.core.library.BackupId
import gg.hideouthq.core.library.BackupInfo
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.RetainedOriginal
import kotlin.time.Instant

/**
 * Whole-setup backups, kept next to each game in `game/citadel/hideout_backups`: what each backup is, the Library it
 * holds with copies of the game's settings files, and one stored copy of each content its files had, shared by every
 * backup and found by its SHA-256. Content goes in before the backup that names it is published, and a backup is
 * published and deleted in one step each, so a stop at any point leaves only content no backup names, which [prune]
 * removes. Content is put back only through a temporary name that is read again first. Which content to keep and when
 * to prune is [gg.hideouthq.core.library.ModLibrary]'s.
 */
interface BackupStore {
    /** Every backup kept for the game at [root], in no particular order; none when it has none. */
    suspend fun list(root: GameRoot): List<BackupInfo>

    /** The Library backup [id] of [root] holds; it throws when that cannot be read. */
    suspend fun read(root: GameRoot, id: BackupId): LibraryManifest

    /**
     * Stores the content of [entry], which holds [image]; content stored already as that very file is not read again. A
     * file that is missing or does not hold [image] is not stored; it throws when the store cannot be written. With
     * [modifiedAt], what [modified] said just before [entry] was read and found to hold [image], the store keeps that
     * time too, so [holds] vouches for [entry] until it is modified again, and one not modified since is not read again
     * to be stored. Without it, nothing is vouched for.
     */
    suspend fun keep(entry: GameEntry, image: FileImage, modifiedAt: Instant? = null)

    /** When [entry] was last modified, as [holds] tells one write from the next; null when it is no plain file. */
    suspend fun modified(entry: GameEntry): Instant?

    /**
     * Whether [entry] is one file with the stored content [image], has its size and was not modified since it was read
     * and kept, which shows it holds [image] without reading it: what the store keeps of a game file is a second name of
     * that very file, and an edit in place changes its modified time. False when the store cannot tell.
     */
    suspend fun holds(entry: GameEntry, image: FileImage): Boolean

    /**
     * Deletes the stored content [image] while it is one file with [entry], which was read and holds other bytes: an
     * edit in place changed both names, so the store holds [image] no more, and [entry] keeps those bytes.
     */
    suspend fun forget(entry: GameEntry, image: FileImage)

    /** Whether the store has a content of [image]'s size under its SHA-256, which [place] reads before it is put back. */
    suspend fun has(root: GameRoot, image: FileImage): Boolean

    /**
     * Gives [entry]'s file a second name, its own, in the folder [folder] of
     * [gg.hideouthq.core.library.CitadelPath.HideoutEdits]: what the user edited, kept there as Restore original
     * puts the original back, and never deleted or rotated by Hideout. Done already when that name is the very file;
     * it throws when another file has it or it cannot be made.
     */
    suspend fun setAside(entry: GameEntry, folder: String)

    /**
     * Stores the content of retained [original]'s files, which Hideout's data folder keeps, as copies of them: a file
     * the game gets back from the store then never shares its bytes on disk with a retained original. A copy that is
     * missing or changed is not stored; it throws when the store cannot be written.
     */
    suspend fun keepOriginal(root: GameRoot, original: RetainedOriginal)

    /** Writes [info], [library] and [held], each settings file's bytes, as a new backup of [root], all of it or none. */
    suspend fun publish(root: GameRoot, info: BackupInfo, library: LibraryManifest, held: Map<HeldFile, ByteArray>)

    /** The settings files backup [id] of [root] holds; it throws when one cannot be read. */
    suspend fun held(root: GameRoot, id: BackupId): Map<HeldFile, ByteArray>

    suspend fun delete(root: GameRoot, id: BackupId)

    /** Deletes the content of [root]'s store whose SHA-256 is not in [named], and whatever a stopped write or delete left. */
    suspend fun prune(root: GameRoot, named: Set<String>)

    /** Gives the temporary [entry] the stored content [image] as a second name of it, and takes it away again when it does not read as [image]. */
    suspend fun place(image: FileImage, entry: GameEntry): PlaceResult

    /** Copies [original]'s stored files into Hideout's data folder as that original, all of it or none. */
    suspend fun placeOriginal(root: GameRoot, original: RetainedOriginal): PlaceResult

    companion object {
        val Unavailable: BackupStore = object : BackupStore {
            private val reason = "backups are not available here"

            override suspend fun list(root: GameRoot): List<BackupInfo> = emptyList()

            override suspend fun read(root: GameRoot, id: BackupId): LibraryManifest = throw IllegalStateException(reason)

            override suspend fun keep(entry: GameEntry, image: FileImage, modifiedAt: Instant?) = throw IllegalStateException(reason)

            override suspend fun modified(entry: GameEntry): Instant? = null

            override suspend fun holds(entry: GameEntry, image: FileImage): Boolean = false

            override suspend fun forget(entry: GameEntry, image: FileImage) = Unit

            override suspend fun has(root: GameRoot, image: FileImage): Boolean = false

            override suspend fun setAside(entry: GameEntry, folder: String) = throw IllegalStateException(reason)

            override suspend fun keepOriginal(root: GameRoot, original: RetainedOriginal) = throw IllegalStateException(reason)

            override suspend fun publish(root: GameRoot, info: BackupInfo, library: LibraryManifest, held: Map<HeldFile, ByteArray>) = throw IllegalStateException(reason)

            override suspend fun held(root: GameRoot, id: BackupId): Map<HeldFile, ByteArray> = throw IllegalStateException(reason)

            override suspend fun delete(root: GameRoot, id: BackupId) = Unit

            override suspend fun prune(root: GameRoot, named: Set<String>) = Unit

            override suspend fun place(image: FileImage, entry: GameEntry): PlaceResult = PlaceResult.Failed(reason)

            override suspend fun placeOriginal(root: GameRoot, original: RetainedOriginal): PlaceResult = PlaceResult.Failed(reason)
        }
    }
}

/**
 * A game settings file a backup keeps a copy of, never a link, by its [fileName] in the backup's folder: autoexec.cfg,
 * empty when the game had none, and each copy of the saved reticle the game had, Steam Cloud's by its account.
 */
sealed interface HeldFile {
    val fileName: String

    data object Autoexec : HeldFile {
        override val fileName: String get() = "autoexec.cfg"
    }

    data object HeroSettings : HeldFile {
        override val fileName: String get() = "citadel_hero_settings.lst"
    }

    data class CloudHeroSettings(val account: SteamAccountId) : HeldFile {
        override val fileName: String get() = "citadel_hero_settings.cloud.$account.lst"
    }

    companion object {
        private val CloudName = Regex("""citadel_hero_settings\.cloud\.(\d{1,10})\.lst""")

        /** The held file a backup's folder keeps as [fileName]; null for its other files. */
        fun named(fileName: String): HeldFile? = listOf(Autoexec, HeroSettings).firstOrNull { it.fileName == fileName }
            ?: CloudName.matchEntire(fileName)?.let { SteamAccountId.ofAccountId(it.groupValues[1].toLong()) }?.let(::CloudHeroSettings)
    }
}

sealed interface PlaceResult {
    data object Placed : PlaceResult

    /** The store does not hold that content as it should; nothing was left behind. */
    data object Unavailable : PlaceResult

    data class Failed(val reason: String) : PlaceResult
}
