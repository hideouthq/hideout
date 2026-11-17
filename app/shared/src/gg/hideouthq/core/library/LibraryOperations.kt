package gg.hideouthq.core.library

import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.data.ArchiveIndex
import gg.hideouthq.core.data.BackupStore
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadProgress
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.GameConfig
import gg.hideouthq.core.data.GameFiles
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.HeroSettingsFiles
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.LocalImport
import gg.hideouthq.core.data.LocalRefusal
import gg.hideouthq.core.data.ModInstaller
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.ReticleRead
import gg.hideouthq.core.data.Withdrawal
import gg.hideouthq.core.model.isPartialFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class InstallPorts(
    val gameBanana: GameBananaClient,
    val downloader: Downloader,
    val installer: ModInstaller,
    val gameConfig: GameConfig,
    val processes: GameProcesses,
    val limits: UnpackLimits = UnpackLimits(),
    val localImport: LocalImport = LocalImport.Unavailable,
    val gameFiles: GameFiles = GameFiles.Unavailable,
    val archives: ArchiveIndex = ArchiveIndex.Unavailable,
    val backups: BackupStore = BackupStore.Unavailable,
    val heroSettings: HeroSettingsFiles = HeroSettingsFiles.Unavailable,
    val log: (String) -> Unit = {},
)

/**
 * How `gameinfo.gi` is watched while the app is open: a burst of changes settles for [debounce], and it is read anyway
 * every [period]. Whether Deadlock runs is read every [processPoll].
 */
data class WatchTiming(
    val debounce: Duration = 750.milliseconds,
    val period: Duration = 30.seconds,
    val processPoll: Duration = 2.seconds,
    /** How often the game's own files and `addons` are looked at again for changes that make compatibility checks stale. */
    val compatibilityPoll: Duration = 2.minutes,
    /** How long finding the mods an import brought waits before asking GameBanana again, after it did not answer. */
    val findAgain: Duration = 5.minutes,
)

/** What the last look at the running programs found: [Unread] before the first look, [Unknown] when they could not be read. */
enum class GameActivity { Unread, Unknown, Closed, Running }

sealed interface ManagerActivity {
    /** Before the first look, or when the programs could not be read: any of them may be open. */
    data object Unknown : ManagerActivity

    data class Seen(val open: Set<Manager>) : ManagerActivity
}

enum class SessionStep { Steady, LaunchingClient, Releasing }

/** A custom game holds the files of [root], which stays the game it runs from whichever folder is chosen meanwhile. */
data class SessionHold(val root: GameRoot, val step: SessionStep = SessionStep.Steady)

sealed interface SessionAdmission {
    data class Held(val root: GameRoot) : SessionAdmission

    data class Refused(val problem: LibraryProblem) : SessionAdmission
}

/** How the game's files stand against the saved choices. Saved choices stay saved in every case. */
sealed interface ChoiceSync {
    data object Settled : ChoiceSync

    /** The files wait for Deadlock to be known closed, [activity] being how it was last seen, and for any custom game to end. */
    data class Waiting(val activity: GameActivity) : ChoiceSync

    /** Deadlock is closed and the files are moving toward the saved choices, or are about to. */
    data object Applying : ChoiceSync

    /**
     * The last attempt to reach the saved choices failed for [problem]. It stays until an attempt reaches them: one is
     * made on every new choice or install, when Deadlock closes, when the game folder comes back, at start, and on
     * [ModLibrary.retryChoices].
     */
    data class Failed(val problem: LibraryProblem) : ChoiceSync
}

/**
 * A choice edit admitted and not yet confirmed saved, in the order edits were admitted. Only [ModLibrary] makes one;
 * screens see what the queue will do through [LibraryState.shownProfiles] and [LibraryState.orderView].
 */
class QueuedChoice<T> internal constructor(
    internal val edit: (ProfileBook) -> ChoiceEdit<T>,
    internal val awaited: Boolean,
    internal val reorder: GameRoot? = null,
    /** Hears the answer once, after the saved choices it leaves are published. */
    internal val complete: (MutationResult<T>) -> Unit = {},
)

internal sealed interface ChoiceEdit<out T> {
    data class Next<out T>(val target: ProfileBook, val value: T, val undoes: Undoable? = null) : ChoiceEdit<T>

    data class Refused(val problem: LibraryProblem) : ChoiceEdit<Nothing>
}

/** Whether the found game's `gameinfo.gi` names the custom games paths, read at once and never kept. */
sealed interface GamesLines {
    data object Mounted : GamesLines

    /** Custom games are not in use here, so nothing asks for the paths. */
    data object Off : GamesLines

    /** In use, and not written yet: they go in the next time Deadlock is closed. */
    data object Pending : GamesLines

    /** Hideout wrote them and something else, such as a Deadlock update, took them out. They go back in the next time Deadlock is closed. */
    data object Removed : GamesLines

    /** Still there, and no longer in use: they go the next time Deadlock is closed. */
    data object Leaving : GamesLines

    data class Unreadable(val problem: GameInfoProblem) : GamesLines
}

/** Whether Deadlock will load the game's `addons` folder, from the active entries in `gameinfo.gi`. */
sealed interface ModLoading {
    /** There is no game, or it has not been read yet. */
    data object Unknown : ModLoading

    data class Loads(val by: LoadingOwner) : ModLoading

    /** [notLoading] of Hideout's sets are on and intact in this game but will not load; [vanilla] when the user chose to play without mods. */
    data class Off(val notLoading: Int, val vanilla: Boolean) : ModLoading {
        val alerts: Boolean get() = notLoading > 0 && !vanilla
    }

    data class Unreadable(val problem: GameInfoProblem) : ModLoading
}

/**
 * What finishing or undoing a change left as it found it: [files] Hideout could not prove were its own unchanged
 * copies, so they stay, and [conflict] when another program's `gameinfo.gi` was kept instead of Hideout's edit.
 */
data class Preserved(val files: List<String>, val conflict: String? = null)

/**
 * One install attempt. [profiles] holds its captured targets, updated only by an accepted file or set choice.
 * [installId] is the installation it changes, its files or an update of them; null for a new install.
 */
data class Operation(val modId: String, val stage: OperationStage, val title: String? = null, val profiles: Set<ProfileId> = emptySet(), val installId: String? = null)

/**
 * One question an install waits on, numbered in the order installs asked them. An answer names its question, so it
 * can never reach another install or a later question of the same one.
 */
data class ChoiceId(val value: Long)

sealed interface ChoiceAnswer {
    /** The files the mod is to have, one or more; the numbers their authors gave the parts decide which wins. */
    data class Files(val fileIds: Set<Long>, val profiles: Set<ProfileId>) : ChoiceAnswer

    /** The components an archive is to have installed, one or more, unique, winning in the order listed. */
    data class Sets(val keys: List<String>, val profiles: Set<ProfileId>) : ChoiceAnswer {
        init {
            require(keys.isNotEmpty()) { "at least one component must be chosen" }
            require(keys.distinct().size == keys.size) { "a component is chosen once" }
        }
    }

    /** Turn the mods whose models the new one shows instead off, in the same change that turns it on. */
    data object Swap : ChoiceAnswer

    /** Leave those mods on beside the new one. */
    data object KeepBoth : ChoiceAnswer

    data object Cancel : ChoiceAnswer
}

sealed interface OperationStage {
    data object Preparing : OperationStage

    /**
     * Which of the mod's current [files], most downloaded first, it is to have; nothing is downloaded until that is
     * chosen. Changing an installed mod's files also offers [delisted], the sets it has from files GameBanana no longer
     * lists, after them, and [preselected] is what it has; otherwise [preselected] is what it had when it was last
     * uninstalled, else [defaultVersion]. [kept] are the files Hideout still holds its own download of, which choosing
     * downloads nothing for. The files chosen win as [ChoiceAnswer.Files] says. [profiles] is the captured selection.
     */
    data class ChoosingFile(
        val id: ChoiceId,
        val files: List<RemoteFile>,
        val preselected: Set<Long>,
        val profiles: Set<ProfileId>,
        val delisted: List<InstalledSet> = emptyList(),
        val kept: Set<Long> = emptySet(),
    ) : OperationStage

    /** The file is chosen and waits for one of the transfer slots; [sizeBytes] is what GameBanana lists for it. */
    data class Waiting(val sizeBytes: Long) : OperationStage

    data class Downloading(val progress: DownloadProgress) : OperationStage

    data object Unpacking : OperationStage

    /**
     * Complete VPK sets from an archive or local file, any number of which may be installed as one mod. Local input
     * asks unless it holds one complete set and nothing else that could be one. [preselected] is what starts ticked, every component for a fresh archive or a
     * retry's remembered choice; [profiles] carries the captured selection from the request or file answer.
     * [leftOut] are the archive's VPKs that make no complete set, which nothing installs, and [notes] its readmes.
     */
    data class ChoosingSet(
        val id: ChoiceId,
        val fileName: String,
        val sizeBytes: Long,
        val options: List<SetOption>,
        val profiles: Set<ProfileId>,
        val preselected: List<String> = options.map { it.key },
        val leftOut: List<RejectedSet> = emptyList(),
        val notes: List<ArchiveNote> = emptyList(),
    ) : OperationStage

    /**
     * The new mod's chosen sets are unpacked and hold models that [others], mods on in the active profile, show with
     * other bytes: swap them out for it, or keep them all on.
     */
    data class ChoosingSwap(val id: ChoiceId, val others: List<String>) : OperationStage

    data object Installing : OperationStage

    /**
     * Downloaded and unpacked, and waiting for Deadlock to be known closed: a new version of a set that is on replaces
     * files the game loads, or a placement Deadlock interrupted must be finished or undone first. It goes on by itself
     * then. [canCancel] is false while that placement may still commit, so cancelling could not undo it. Not kept
     * across a restart; an interrupted placement is still settled by recovery.
     */
    data class WaitingForGame(val canCancel: Boolean) : OperationStage

    /** The install was cancelled and is putting away what it downloaded or unpacked. */
    data object Cancelling : OperationStage

    /** [file] is the name of the file, of several, whose download, unpacking or placing failed, when one did. */
    data class Failed(val problem: LibraryProblem, val file: String? = null) : OperationStage
}

/** Whether [ModLibrary.cancel] can stop an install in this stage: every stage before its files are placed. */
val OperationStage.cancellable: Boolean
    get() = when (this) {
        OperationStage.Preparing, is OperationStage.ChoosingFile, is OperationStage.ChoosingSet, is OperationStage.ChoosingSwap, is OperationStage.Waiting,
        is OperationStage.Downloading, OperationStage.Unpacking -> true
        is OperationStage.WaitingForGame -> canCancel
        OperationStage.Installing, OperationStage.Cancelling, is OperationStage.Failed -> false
    }

/** Every install that is getting, waiting for or downloading its file, and the bytes of those whose size is known. */
data class TransferSummary(val count: Int, val doneBytes: Long, val totalBytes: Long) {
    val percent: Int get() = DownloadProgress(doneBytes, totalBytes).percent
}

/** The downloads under way or queued, or null when there are none. An install still preparing counts but adds no bytes. */
fun LibraryState.transfers(): TransferSummary? {
    var count = 0
    var done = 0L
    var total = 0L
    for (operation in operations.values) {
        when (val stage = operation.stage) {
            OperationStage.Preparing -> count++
            is OperationStage.Waiting -> {
                count++
                total += stage.sizeBytes
            }
            is OperationStage.Downloading -> {
                count++
                done += stage.progress.doneBytes
                total += stage.progress.totalBytes
            }
            else -> Unit
        }
    }
    return if (count == 0) null else TransferSummary(count, done, total)
}

val OperationStage.choice: ChoiceId?
    get() = when (this) {
        is OperationStage.ChoosingFile -> id
        is OperationStage.ChoosingSet -> id
        is OperationStage.ChoosingSwap -> id
        else -> null
    }

internal fun OperationStage.answeredBy(answer: ChoiceAnswer): OperationStage? = when {
    answer == ChoiceAnswer.Cancel && choice != null -> OperationStage.Cancelling
    this is OperationStage.ChoosingFile && answer is ChoiceAnswer.Files && answer.fileIds.isNotEmpty() &&
        answer.fileIds.all { id -> files.any { it.id == id } || delisted.any { it.fileId == id } } -> OperationStage.Preparing
    this is OperationStage.ChoosingSet && answer is ChoiceAnswer.Sets && answer.keys.all { key -> options.any { it.key == key } } -> OperationStage.Preparing
    this is OperationStage.ChoosingSwap && (answer == ChoiceAnswer.Swap || answer == ChoiceAnswer.KeepBoth) -> OperationStage.Preparing
    else -> null
}

/**
 * One set a download offers: its key (the archive path of its directory file), its files' archive paths, their total
 * size, and [files], what its tree changes by path and CRC-32, empty when its names could not be read.
 */
data class SetOption(val key: String, val paths: List<String>, val sizeBytes: Long, val files: Map<String, Long> = emptyMap()) {
    /** It replaces fonts, which the game loads only through `fonts.conf`. */
    val needsFonts: Boolean = files.keys.any { it.startsWith("panorama/fonts/") && it.endsWith(".ttf") }
}

sealed interface ReticleWrite {
    /** Every copy holds the crosshair, as [read] shows them now. */
    data class Written(val read: ReticleRead.Read) : ReticleWrite

    data class Refused(val problem: LibraryProblem) : ReticleWrite
}

sealed interface AutoexecWrite {
    /** [document] is on disk now: what was written, or what was there when the change left it as it was. */
    data class Written(val document: AutoexecDocument) : AutoexecWrite

    data class Refused(val problem: LibraryProblem) : AutoexecWrite
}

/** What to install: a GameBanana mod, with the exact files when they are known so nothing is asked, or a local file. */
sealed interface InstallInput {
    data class Remote(val submission: SubmissionRef, val fileIds: Set<Long> = emptySet()) : InstallInput
    data class Local(val file: LocalFile) : InstallInput
}

data class InstallRequest(
    val source: InstallInput,
    val profiles: Set<ProfileId>,
    /** Ask which file even when the saved preference is off; a mod of one file is still no question. Explicit file IDs still win. */
    val chooseVersion: Boolean = false,
)

sealed interface LibraryProblem {
    data object InvalidProfileName : LibraryProblem
    data object ProfileNameTaken : LibraryProblem
    data object ProfileNotFound : LibraryProblem
    data object LastProfile : LibraryProblem

    data object Busy : LibraryProblem

    data object UndoExpired : LibraryProblem
    data object UndoConflict : LibraryProblem
    data object VersionChanged : LibraryProblem

    /**
     * What an update was confirmed for changed before it could be carried out: the installation's files, its game, or
     * what GameBanana offers. Nothing was replaced; checking again shows what is current.
     */
    data object UpdateChanged : LibraryProblem

    /** GameBanana listed a current file Hideout could not read, so an update could not prove what it would replace. */
    data object ListingIncomplete : LibraryProblem
    data class LocalInputRefused(val reason: LocalRefusal) : LibraryProblem

    /** Every complete part of the file handed to Hideout is installed already, from the very same file, as [title], mod [installId]. */
    data class SameFileInstalled(val title: String, val installId: String) : LibraryProblem
    data object GameNotFound : LibraryProblem
    data object GameRunning : LibraryProblem

    /** A custom game holds the game's files, whether or not Deadlock's client is open. */
    data object CustomGameRunning : LibraryProblem
    data class ProcessesUnknown(val reason: String) : LibraryProblem
    data class LibraryUnavailable(val reason: String?) : LibraryProblem
    data class Interrupted(val reason: String) : LibraryProblem
    data object NotAGameBananaMod : LibraryProblem
    data object AlreadyInstalled : LibraryProblem
    data class Withdrawn(val why: Withdrawal) : LibraryProblem
    data class MetadataUnavailable(val reason: String) : LibraryProblem
    data object NoFiles : LibraryProblem
    data object FileNotOffered : LibraryProblem
    data class Download(val problem: DownloadProblem) : LibraryProblem
    data class ArchiveRefused(val rejection: ArchiveRejection) : LibraryProblem
    data class ExtractionFailed(val reason: String) : LibraryProblem
    data class NoCompleteSet(val rejected: List<RejectedSet>) : LibraryProblem

    /** No arrangement fits the [count] sets that are on in the current order without taking unmanaged files' numbers. */
    data class NoRoomInOrder(val count: Int) : LibraryProblem
    data class AddonsUnreadable(val reason: String) : LibraryProblem
    data class NameTaken(val name: String) : LibraryProblem
    data class PlacementFailed(val reason: String) : LibraryProblem
    data class GameInfo(val problem: GameInfoProblem) : LibraryProblem
    data object GameInfoChanged : LibraryProblem
    data class GameInfoConflict(val detail: String) : LibraryProblem
    data class GameInfoWriteFailed(val reason: String) : LibraryProblem
    data class AutoexecWriteFailed(val reason: String) : LibraryProblem

    data class ReticleWriteFailed(val reason: String) : LibraryProblem
    data object AnotherToolLoadsMods : LibraryProblem

    /** Hideout's lines were written, but reading `gameinfo.gi` again does not show mods loading. */
    data object LoadingNotVerified : LibraryProblem
    data class ManifestSave(val reason: String) : LibraryProblem
    data class Unexpected(val reason: String) : LibraryProblem

    data object NotInstalled : LibraryProblem

    /** The file picked to reinstall a local mod from is not the one it was installed from. */
    data object NotTheInstalledFile : LibraryProblem

    /** A file of the set, at [path] under the game folder, is not there. */
    data class FileMissing(val path: String) : LibraryProblem

    /** A file of the set, at [path] under the game folder, no longer holds what Hideout wrote. */
    data class FileChanged(val path: String) : LibraryProblem

    /** A file at [path] under the game folder could not be read. */
    data class FileUnreadable(val path: String) : LibraryProblem

    /** Files Hideout did not put in `addons`, [names], appeared at [number] while a change was planned, so placing it would load two mods under one number. */
    data class NumberTaken(val number: Int, val names: List<String>) : LibraryProblem

    /**
     * The sets in the game changed since the order was shown, the new order is not a rearrangement of them, or another
     * profile is active by the time it is saved, so nothing was saved.
     */
    data object LoadOrderChanged : LibraryProblem

    /** Hideout could not write a backup next to the game, so none was made. */
    data class BackupFailed(val reason: String) : LibraryProblem

    /** The backup could not be read, or no longer fits this Library, so nothing was restored. */
    data class BackupUnreadable(val reason: String) : LibraryProblem
}

/**
 * An uninstall as it was asked for: the mod as the install list held it, and the downloads of its files that may go with
 * it, none when none can be deleted. Only [ModLibrary] makes one.
 */
@ConsistentCopyVisibility
data class UninstallConfirmation internal constructor(val record: ManifestRecord, val downloads: List<ArchiveIdentity>)

/**
 * Restoring `gameinfo.gi` in [root] as the user confirmed it, when [enabledMods] of Hideout's sets there were on and
 * would stop loading. Only [ModLibrary] makes one.
 */
@ConsistentCopyVisibility
data class RestoreConfirmation internal constructor(val root: GameRoot, val enabledMods: Int)

/**
 * The downloads Clear deletes, exactly as Space used measured them: registered, made by Hideout, holding their recorded
 * bytes, and named by no installed set in any game. Only [ModLibrary] makes one.
 */
@ConsistentCopyVisibility
data class ClearConfirmation internal constructor(val downloads: List<OwnedDownload>) {
    val bytes: Long get() = downloads.sumOf { it.archive.sizeBytes }
}

/**
 * What the registered downloads take up, read from their own paths and never from a folder listing. [matching]
 * downloads still hold the bytes Hideout recorded, [matchingBytes] in all; one that holds something else or could not
 * be read is not counted. [clear] is what Clear would delete, or null for nothing.
 */
data class DownloadSpace(
    val matching: Int,
    val matchingBytes: Long,
    val clear: ClearConfirmation?,
)

/** Restoring `gameinfo.gi`, clearing downloads or working on backups from Settings: one at a time, in the library's own scope. */
sealed interface Maintenance {
    data class Restore(val confirmation: RestoreConfirmation, val stage: RestoreStage) : Maintenance

    data class Clear(val confirmation: ClearConfirmation, val stage: ClearStage) : Maintenance

    data class Backups(val work: BackupWork, val stage: BackupStage) : Maintenance
}

/** What was asked of the backups: one made now, the Library made exactly as [Revert.backup] holds it, or one deleted. */
sealed interface BackupWork {
    data object BackUp : BackupWork

    data class Revert(val backup: BackupInfo) : BackupWork

    data class Delete(val backup: BackupInfo) : BackupWork
}

sealed interface BackupStage {
    data object Running : BackupStage

    data object Done : BackupStage

    data class Refused(val problem: LibraryProblem) : BackupStage
}

sealed interface RestoreStage {
    data object Running : RestoreStage

    data object Restored : RestoreStage

    data class Refused(val problem: LibraryProblem) : RestoreStage
}

sealed interface ClearStage {
    data object Running : ClearStage

    /**
     * [removed] downloads went, freeing [freedBytes]; [alreadyGone] had already gone, unregistered by an earlier
     * removal or not there when looked for; [kept] stayed because something named them or they changed.
     */
    data class Cleared(val removed: Int, val freedBytes: Long, val kept: Int, val alreadyGone: Int = 0) : ClearStage

    /** Clear stopped at [problem]. What it had done stays done; the downloads after the one it stopped on were not touched. */
    data class Refused(val problem: LibraryProblem) : ClearStage
}

val Maintenance.running: Boolean
    get() = when (this) {
        is Maintenance.Restore -> stage == RestoreStage.Running
        is Maintenance.Clear -> stage == ClearStage.Running
        is Maintenance.Backups -> stage == BackupStage.Running
    }

data class SetChange(val installId: String, val title: String, val request: ChangeRequest, val stage: ChangeStage)

sealed interface ChangeRequest {
    data class Uninstall(val confirmation: UninstallConfirmation, val deleteDownload: Boolean) : ChangeRequest
}

sealed interface ChangeStage {
    data object Running : ChangeStage

    data class Refused(val problem: LibraryProblem) : ChangeStage
}

/**
 * The active profile's order Library shows for the sets of [root], winner first, and that a drag starts from: as
 * admitted edits will leave it, else as last saved, else as the files' numbers give it. Only [ModLibrary] issues one,
 * as [LibraryState.orderView], and it is the same view only while it is the same object. It stays the same object
 * while the game and the order shown stay the same: through its own save, the files reaching it, process changes,
 * switches to a profile with the same order and sets turned on or off. An accepted drop, a refused one, a switch to a
 * profile with another order, another game, an install or uninstall, or the install list read again after doubt issues
 * a new one, even when the same ids come back.
 */
class OrderView internal constructor(val root: GameRoot, val sets: List<String>) {
    override fun toString(): String = "OrderView(${root.path}, $sets)"
}

/** Most downloaded first, then newest, then the lowest id, so the order never depends on how GameBanana listed them. */
fun orderVersions(files: List<RemoteFile>): List<RemoteFile> =
    files.sortedWith(compareByDescending<RemoteFile> { it.downloads }.thenByDescending { it.addedAt }.thenBy { it.id })

/** Of [ordered], [orderVersions]' order, the file Install takes unasked: the first its author does not call a part or a makers' kit ([isPartialFile]), else the first. */
fun defaultVersion(ordered: List<RemoteFile>): RemoteFile = ordered.firstOrNull { !isPartialFile(it.description, it.fileName) } ?: ordered.first()
