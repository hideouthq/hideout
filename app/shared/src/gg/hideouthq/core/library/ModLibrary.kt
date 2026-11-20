package gg.hideouthq.core.library

import gg.hideouthq.core.analytics.*

import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.autoexec.takeOver
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.HeroSettings
import gg.hideouthq.core.data.HeldFile
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.ReticleRead
import gg.hideouthq.core.data.readAll
import gg.hideouthq.core.data.ConfigRead
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.LocalArchiveSnapshot
import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.data.CitadelFile
import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadProgress
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.FolderChoice
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.GameLocator
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.LocalImportResult
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ModInstallations
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.UnfinishedInstall
import gg.hideouthq.core.data.UnfinishedReplacement
import gg.hideouthq.core.data.UnpackResult
import gg.hideouthq.core.data.VpkLook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class LibraryState(
    val game: GameStatus = GameStatus.Searching,
    val library: LibraryStatus = LibraryStatus.Loading,
    val loading: ModLoading = ModLoading.Unknown,
    /** Installs in progress, waiting for a choice, or failed, by mod id: at most one per mod. A waiting stage carries its [ChoiceId]. */
    val operations: Map<String, Operation> = emptyMap(),
    /** Sets being uninstalled or whose uninstall was refused, by install id: at most one per set. */
    val changes: Map<String, SetChange> = emptyMap(),
    /** Mods the user uninstalled whose files have not gone yet, while Undo may bring them back or they wait to leave the game. */
    val leaving: Set<String> = emptySet(),
    /** The restore or Clear asked for from Settings, running or with its result, until the next one is asked for. */
    val maintenance: Maintenance? = null,
    /**
     * Whether Deadlock's client ran when the running programs were last read. A custom game's server does not count. This
     * is the one place to ask, so nothing else scans for the client.
     */
    val deadlock: GameActivity = GameActivity.Unread,
    /**
     * The custom game holding the game's files, from its admission until its release, whether or not the client runs.
     * Not saved, since the session's own journal covers a crash.
     */
    val sessionHold: SessionHold? = null,
    /** Which other mod managers were open when the running programs were last read. */
    val managers: ManagerActivity = ManagerActivity.Unknown,
    /**
     * The profiles as the user last saved them: what is chosen, whether or not the files have reached it yet. What
     * files exist, where, and whether they are intact stays in [LibraryStatus.Ready.records]; [choiceSync] says how the
     * two stand.
     */
    val profiles: ProfileBook? = null,
    /**
     * [profiles] as the admitted edits still [saving] will leave them, so counts and selections follow a click at once;
     * the same book as [profiles] when nothing is saving. It is not saved yet and must not be called saved.
     */
    val shownProfiles: ProfileBook? = null,
    /** The active profile's order Library shows and a drag starts from, issued by [ModLibrary]; null without a game or an install list. */
    val orderView: OrderView? = null,
    val choiceSync: ChoiceSync = ChoiceSync.Settled,
    /**
     * The mods the saved choices turn on that stay off, by install id, as the files were last planned: they come after
     * the most the game's mods folder mounts, so the ones above them load instead. A mod turned off since may still be named.
     */
    val pastLimit: Set<String> = emptySet(),
    /**
     * Why the last edit asked for without waiting for an answer, a drag or [ModLibrary.setEnabled], was not saved, until
     * [ModLibrary.dismissChoiceProblem] or another such edit is accepted.
     */
    val choiceProblem: LibraryProblem? = null,
    /** Play is being checked and handed to Steam; choice edits are refused until it ends. */
    val launching: Boolean = false,
    /** The one Undo that can still succeed, from the last switch, delete or mods turned on or off together; null once a later edit ended it. */
    val undo: UndoToken? = null,
    /** Admitted choice edits not yet confirmed saved, oldest first. */
    internal val queued: List<QueuedChoice<*>> = emptyList(),
    val compatibility: CompatibilityState = CompatibilityState(),
    /** The backups kept next to the found game, newest first; none without a game, or while they cannot be listed. */
    val backups: List<BackupInfo> = emptyList(),
    /** GameBanana did not answer the last time Hideout asked who the [unasked] mods are; it asks again later. */
    val findingLater: Boolean = false,
    /** The last time something other than Hideout took the lines that load its mods out of `gameinfo.gi`. */
    val rewrite: GameInfoRewrite? = null,
    /** When GameBanana said it may be asked again, while a request waits for it; an install that waits on it says so. */
    val gameBananaBusy: Instant? = null,
) {
    /** The found game's mods an import brought that GameBanana has not yet said anything of, while the library can change. */
    val unasked: List<ManifestRecord>
        get() {
            val root = (game as? GameStatus.Ready)?.root ?: return emptyList()
            val ready = library as? LibraryStatus.Ready ?: return emptyList()
            return if (ready.interrupted != null) emptyList() else ready.records.filter { it.gameRoot == root && it.unasked }
        }

    val saving: Boolean get() = queued.isNotEmpty()

    /**
     * Profiles, memberships, the active profile and its order may be edited: each edit is saved at once and the
     * files follow once Deadlock is known closed, so this holds while it runs or may run. Not while the install list is
     * in doubt, while an interrupted change waits to be finished, or while Play is being admitted.
     */
    val canEditChoices: Boolean get() = library is LibraryStatus.Ready && library.interrupted == null && profiles != null && !launching

    /** A choice edit is saving, or files are moving toward the saved choices. Never true only because Deadlock runs. */
    val applying: Boolean get() = saving || choiceSync == ChoiceSync.Applying

    /**
     * Mods may be installed, from GameBanana or a file, and failed installs retried, whatever Deadlock is doing: while
     * it may run, a new set is placed in its off folder and turned on once Deadlock is known closed.
     */
    val canInstall: Boolean get() = library is LibraryStatus.Ready && library.interrupted == null && profiles != null && !launching

    val filesFree: Boolean get() = deadlock == GameActivity.Closed && sessionHold == null

    /** Files the game loads may change now: uninstalling, `gameinfo.gi` repairs and restores. */
    val canModify: Boolean get() = canEditChoices && filesFree

    /** Hideout must not restart while an install is under way or a custom game holds the game's files. */
    val modsBusy: Boolean get() = sessionHold != null || operations.values.any { it.stage !is OperationStage.Failed }

    /**
     * Play may start the game, modded or vanilla: the install list is read, Deadlock is known closed and no custom game
     * holds its files. Whatever Hideout itself is doing with the files never holds it: Play takes the files as they are.
     */
    val canPlay: Boolean get() = library is LibraryStatus.Ready && filesFree && !launching
}

sealed interface GameStatus {
    data object Searching : GameStatus

    data class Ready(val root: GameRoot, val via: FoundVia) : GameStatus

    /** Steam has no Deadlock and no chosen folder works. [rejected] is a remembered choice that stopped being Deadlock's. */
    data class Missing(val rejected: FolderCheck.Invalid? = null) : GameStatus
}

enum class FoundVia { Steam, Chosen }

/** What the manifest says Hideout owns, reconciled against the real disk through [GameInventory]. */
sealed interface LibraryStatus {
    data object Loading : LibraryStatus

    data class Ready(
        val records: List<ManifestRecord>,
        /** By installId, how each record with an owned file missing, replaced, or changed since Hideout wrote it is damaged: missing when any file is gone. */
        val damaged: Map<String, FilesDamage> = emptyMap(),
        /** installIds of records with an owned file that could not be read, so whether it is intact is unknown. */
        val unchecked: Set<String> = emptySet(),
        /** installIds of [damaged] records whose damaged sets the game's backups still hold as recorded, which a reinstall puts back with no download. */
        val stored: Set<String> = emptySet(),
        /** Why an interrupted change is still waiting to be finished or undone; nothing else changes until it is. */
        val interrupted: String? = null,
        /** Every download the manifest registers, in every game. */
        val downloads: List<OwnedDownload> = emptyList(),
        /**
         * The [downloads] [LibraryLedger.removable] allows Clear to delete now, whatever their bytes: none an install
         * reads, a record in any game names or the pending change touches. Published whenever any of those changes.
         */
        val clearable: List<OwnedDownload> = emptyList(),
        /** What the found game's loading folders held when Hideout last listed them; null when one could not be listed. */
        val loading: List<OnEntry>? = null,
    ) : LibraryStatus

    /** The manifest, or the game files it describes, cannot be trusted. Read-only: never an empty, writable library. */
    data class Blocked(val reason: String) : LibraryStatus
}

/** How an installation's files differ from what Hideout wrote: one of them is gone, or holds other bytes. */
enum class FilesDamage { Missing, Changed }

/**
 * Where [ModLibrary.reinstall] gets a mod's original files: the same GameBanana file, the file the user installed it from,
 * or the game's backups, which still hold them.
 */
enum class ReinstallFrom { Source, File, Backups }

/**
 * Where [ModLibrary.reinstall] gets this mod's original files, or null when nothing says what they were, as for files
 * taken over from another manager.
 */
val ManifestRecord.reinstallFrom: ReinstallFrom?
    get() = when {
        sets.first().source is InstalledSource.Remote -> ReinstallFrom.Source
        sets.all { it.origin is SetOrigin.Imported } -> ReinstallFrom.File
        else -> null
    }

class ModLibrary(
    private val locator: GameLocator,
    private val preferences: Preferences,
    private val libraryStore: LibraryStore,
    private val inventory: GameInventory,
    private val ports: InstallPorts,
    private val scope: CoroutineScope,
    private val ids: () -> String = ::randomId,
    private val watch: WatchTiming = WatchTiming(),
    private val compatibility: CompatibilityPort = CompatibilityPort.Unavailable,
    private val resourceIndexes: ResourceIndexCache? = null,
    private val clock: Clock = Clock.System,
    private val gameBananaBusy: StateFlow<Instant?> = MutableStateFlow(null),
    private val analytics: UsageAnalytics? = null,
) : ModInstallations {
    private val mutex = Mutex()
    private val startup = scope.launch(start = CoroutineStart.LAZY) { mutex.withLock { resolve(preferSteam = false) } }
    private val libraryMutex = Mutex()
    private val ledger = LibraryLedger(
        store = libraryStore,
        inventory = inventory,
        ports = ports,
        ids = ids,
        preserve = { preserved ->
            leftInPlace++
            logLeftInPlace(preserved.files, preserved.conflict)
        },
        fence = ::untouchable,
        gameIdentity = ::gameIdentity,
    )

    @Volatile
    private var restartBegun: () -> Boolean = { false }

    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val librarySetup = scope.launch(start = CoroutineStart.LAZY) {
        underLock {
            loadLibrary()
            if (state.value.library is LibraryStatus.Ready) {
                attempt { ports.installer.discardAllStaging() }
                if (ledger.manifest.pending == null) {
                    discardLocked()
                    tidyAllLocked()
                }
            }
        }
        wake.trySend(Unit)
    }
    private val watcher = scope.launch(start = CoroutineStart.LAZY) { watchModLoading() }
    private val gameWatcher = scope.launch(start = CoroutineStart.LAZY) {
        librarySetup.join()
        while (true) {
            processProblem()
            watchGameFolder()
            delay(watch.processPoll)
        }
    }
    private val choices = scope.launch(start = CoroutineStart.LAZY) {
        librarySetup.join()
        for (signal in wake) work()
    }
    private val compatWake = Channel<Unit>(Channel.CONFLATED)
    private val compatWorker = scope.launch(start = CoroutineStart.LAZY) {
        librarySetup.join()
        if (compatibility.unavailable != null) {
            publishCompat()
            return@launch
        }
        underLock { if (state.value.library is LibraryStatus.Ready && ledger.uncertainty == null && !ledger.listWasMissing) attempt { compatibility.sweep(ledger.manifest.originals.map { it.id }.toSet()) } }
        preferences.readAutoRepair()
        launch {
            preferences.autoRepair.collect {
                publishCompat()
                compatWake.trySend(Unit)
            }
        }
        for (signal in compatWake) {
            (attempt { compatPass() } as? Attempt.Failed)?.let { failed ->
                compatProblem = failed.reason
                abandonWork(failed.reason)
                publishCompat()
            }
        }
    }
    private val compatTicker = scope.launch(start = CoroutineStart.LAZY) {
        librarySetup.join()
        if (compatibility.unavailable != null) return@launch
        while (true) {
            delay(watch.compatibilityPoll)
            reconfirm = true
            compatWake.trySend(Unit)
        }
    }
    private val activityLog = scope.launch(start = CoroutineStart.LAZY) {
        state.fold(LibraryState()) { before, after ->
            libraryLog(before, after).forEach(ports.log)
            after
        }
    }
    private val slots = Semaphore(MaxTransfers)
    private val listings = Semaphore(MaxListings)

    @Volatile
    private var identifying: Job? = null
    private val identifyLock = Mutex()
    private val finder = scope.launch(start = CoroutineStart.LAZY) {
        librarySetup.join()
        while (true) {
            state.first { it.unasked.isNotEmpty() }
            identifyLock.withLock { identifyLocalMods(unaskedOnly = true) }
            if (state.value.unasked.isNotEmpty()) delay(watch.findAgain)
        }
    }
    private val runs = MutableStateFlow<Map<String, InstallRun>>(emptyMap())

    private val putAways = MutableStateFlow<Map<String, Job>>(emptyMap())

    private val busyWatcher = scope.launch(start = CoroutineStart.LAZY) {
        gameBananaBusy.collect { until -> mutableState.update { it.copy(gameBananaBusy = until) } }
    }

    private val resumer = scope.launch(start = CoroutineStart.LAZY) {
        startup.join()
        librarySetup.join()
        resumeUnfinished()
    }
    private val loadingMutex = Mutex()

    private val importedManagers = MutableStateFlow<Map<GameRoot, Set<Manager>>>(emptyMap())
    private val questions = MutableStateFlow<Map<ChoiceId, CompletableDeferred<ChoiceAnswer>>>(emptyMap())
    private val asking = Mutex()
    private var lastChoice = 0L
    private val mutableState = MutableStateFlow(LibraryState())

    @Volatile
    private var failure: LibraryProblem? = null

    @Volatile
    private var working = false

    @Volatile
    private var pastLimit: Set<String> = emptySet()

    private var findGameAgain = false

    private var lostGame: GameRoot? = null

    @Volatile
    private var parking: GameRoot? = null

    @Volatile
    private var leftInPlace = 0

    private var settingsBackedUp = false

    val state: StateFlow<LibraryState> = mutableState.asStateFlow()

    /** Every GameBanana file an installation holds, in any game, once the library has been read. */
    val installedSources: Flow<Set<SubmissionIdentity>> = state.mapNotNull { (it.library as? LibraryStatus.Ready)?.records }
        .map { records -> records.flatMap { it.sets }.mapNotNullTo(HashSet()) { (it.source as? InstalledSource.Remote)?.identity } }
        .distinctUntilChanged()

    val separator: Char get() = locator.separator

    /** Where downloads go while Settings › Mods names no folder. */
    val defaultDownloadFolder: DownloadFolder get() = ports.downloader.defaultFolder

    /** Whether [path] can take new downloads: a plain writable folder outside every game Hideout knows and outside its own data. */
    suspend fun checkDownloadFolder(path: String): FolderChoice {
        val current = state.value
        val games = (current.library as? LibraryStatus.Ready)?.records.orEmpty().map { it.gameRoot } + listOfNotNull((current.game as? GameStatus.Ready)?.root)
        return ports.downloader.checkFolder(path, games.toSet())
    }

    /**
     * Finds the game, reconciles the manifest with the disk, settles any interrupted change and starts watching
     * `gameinfo.gi`. Only the first call does anything; the manifest load is independent of game detection, since
     * each record carries its own root.
     */
    fun start() {
        startup.start()
        librarySetup.start()
        watcher.start()
        gameWatcher.start()
        choices.start()
        compatWorker.start()
        compatTicker.start()
        activityLog.start()
        finder.start()
        resumer.start()
        busyWatcher.start()
    }

    override fun status(modId: String): Flow<InstallStatus> = state.map { it.installStatus(modId) }.distinctUntilChanged()

    /** Asks Steam again. A folder Steam knows replaces the one the user chose; otherwise a working choice stays. */
    suspend fun detectGame(): GameStatus = mutex.withLock { resolve(preferSteam = true) }

    /** Uses and remembers [path] if it is Deadlock's. */
    suspend fun useGameFolder(path: String): FolderCheck = mutex.withLock {
        val check = locator.check(path)
        if (check is FolderCheck.Valid) {
            val viaSteam = check.root in locator.detect()
            preferences.setGameFolder(check.root.path)
            publish(GameStatus.Ready(check.root, if (viaSteam) FoundVia.Steam else FoundVia.Chosen))
        }
        check
    }

    /** Captures the active profile for catalogue installs. Version questions retain that selection. */
    override fun install(modId: String) {
        val submission = SubmissionRef.parse(modId)
        if (submission != null) {
            install(InstallRequest(InstallInput.Remote(submission), setOfNotNull(state.value.shownProfiles?.active ?: state.value.profiles?.active)))
            return
        }
        mutableState.update { it.copy(operations = it.operations + (modId to Operation(modId, OperationStage.Failed(LibraryProblem.NotAGameBananaMod)))) }
    }

    /** Captures the request's targets and file choice. A second request while one is running is ignored. */
    fun install(request: InstallRequest) {
        val key = when (val input = request.source) {
            is InstallInput.Remote -> input.submission.modId
            is InstallInput.Local -> "local-${ids()}"
        }
        start(Asked(request.source, key, request.profiles.toSet(), chooseVersion = request.chooseVersion))
    }

    /**
     * Asks which files installed mod [installId] is to have, starting from the ones it has, and changes them to the
     * answer as one change: files it keeps stay as they are, only new ones are downloaded and unpacked, and the mod
     * keeps its memberships and its place in the load order. A local mod known to be from a submission becomes the
     * files chosen from it; one known to be from none has no other files to choose.
     */
    fun changeFiles(installId: String) {
        val record = (state.value.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == installId } ?: return
        val submission = record.submission ?: return
        val profiles = state.value.profiles?.profiles.orEmpty().filter { installId in it.enabled }.map { it.id }.toSet()
        start(Asked(InstallInput.Remote(submission), submission.modId, profiles, replacement = record, chooseVersion = true))
    }

    /**
     * Replaces, in installation [UpdatePlan.installId], each file [plan] names with its pinned target as one change, in
     * the places the old ones had: memberships, load order and every file it does not name stay as they are. A new
     * archive keeps exactly the components installed from the old one, in their order; only when one of them is gone is
     * the component chosen again, starting from those left. Before anything is downloaded, GameBanana's complete
     * listing must still offer each target with its MD5, and for a sole successor nothing else and not the file it
     * replaces, and the installation must still hold exactly what the plan was made for in this game; otherwise it is
     * refused with [LibraryProblem.UpdateChanged], or [LibraryProblem.ListingIncomplete], and nothing changes. A mod
     * that is on waits for Deadlock to close; one that is off does not. False when the installation is gone or the mod
     * already has an install running.
     */
    fun update(plan: UpdatePlan): Boolean {
        val record = (state.value.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == plan.installId } ?: return false
        val submission = SubmissionRef.parse(record.modId) ?: return false
        val fileIds = record.sets.mapNotNullTo(LinkedHashSet()) { set -> set.fileId?.let { id -> plan.replacements.firstOrNull { it.from.fileId == id }?.to?.fileId ?: id } }
        return start(Asked(InstallInput.Remote(submission), record.modId, emptySet(), fileIds = fileIds, replacement = record, update = plan))
    }

    /**
     * Puts back the original files of installed mod [installId]'s sets whose files are missing or changed, as one change
     * that keeps every other set, its numbers, memberships and on or off. A set comes back from the game's backups while
     * they still hold its recorded files ([LibraryStatus.Ready.stored]), with nothing downloaded or asked for; any other
     * from the kept download while it holds its recorded bytes, else from the same GameBanana file downloaded again, else,
     * for a local mod, from [file], once it is the very file the mod was installed from. What the user edited moves to
     * [CitadelPath.HideoutEdits] as the original takes its place. A mod that is on waits for Deadlock to close; one that
     * is off does not. Nothing starts for a mod with nowhere to get its files from.
     */
    fun reinstall(installId: String, file: LocalFile? = null) {
        val ready = state.value.library as? LibraryStatus.Ready ?: return
        val record = ready.records.firstOrNull { it.installId == installId } ?: return
        val input = when (record.reinstallFrom) {
            ReinstallFrom.Source -> record.submission?.let(InstallInput::Remote)
            ReinstallFrom.File -> file?.let(InstallInput::Local)
            ReinstallFrom.Backups, null -> null
        }
        if (input == null && installId !in ready.stored) return
        start(Asked(input, record.modId, emptySet(), replacement = record, reinstall = true))
    }

    /**
     * Gives installed mod [installId]'s parts the order [order], by their keys with the winner first, as one change that
     * a crash leaves whole, as before or as asked. A mod that is on renames them inside its own numbers, so it is refused
     * while Deadlock may run; one that is off only saves the order. False when the mod is gone, [order] is not a new order
     * of its parts, or the mod has an install running.
     */
    fun reorderParts(installId: String, order: List<SetKey>): Boolean {
        val record = (state.value.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == installId } ?: return false
        val keys = record.sets.map { it.key }
        if (order == keys || order.size != keys.size || order.toSet() != keys.toSet()) return false
        return start(Asked(null, record.modId, emptySet(), replacement = record, parts = order))
    }

    /**
     * Keeps what the user edited in installed mod [installId]'s files outside Hideout as the mod's own, as when another
     * program edits them on purpose: nothing moves, and the install list records the files as they are, each read once.
     * The mod is held no more, so its saved choices then reach it. A refusal, as while one of its files is missing or
     * cannot be read or a change waits to be finished, is shown as [LibraryState.choiceProblem].
     */
    fun keepEdits(installId: String) {
        scope.launch {
            librarySetup.join()
            val kept = underLock {
                val waiting = recoverLocked()
                val record = ledger.manifest.records.firstOrNull { it.installId == installId } ?: return@underLock null
                val problem = waiting?.let { LibraryProblem.Interrupted(it) } ?: ledger.keepEdits(record)
                publishRecords(ledger.uncertainty, recheck = setOf(installId))
                if (problem != null) mutableState.update { it.copy(choiceProblem = problem) }
                record.takeIf { problem == null }
            } ?: return@launch
            refreshLoading(kept.gameRoot)
            wake.trySend(Unit)
        }
    }

    /**
     * Keeps installed mod [installId]'s files as they are over a review: while its GameBanana submission offers exactly
     * [offered], its files are not offered for review again. Only the install list changes, so Deadlock may run.
     */
    fun keepReviewed(installId: String, offered: Set<Long>) {
        scope.launch {
            librarySetup.join()
            underLock {
                if (reloadLocked() != null) return@underLock
                val record = ledger.manifest.records.firstOrNull { it.installId == installId } ?: return@underLock
                val saved = ledger.keepReviewed(record, offered)
                if (saved !is SaveResult.Refused) publishRecords(interrupted = (saved as? SaveResult.Unconfirmed)?.reason)
            }
        }
    }

    /**
     * Records that installed mods [installId] and [other] overlap as the user means them to, as Keep both at the install
     * question does, so the models one hides of the other count as meant; or forgets it when not [keep]. Only the install
     * list changes, so Deadlock may run.
     */
    fun keepBeside(installId: String, other: String, keep: Boolean) {
        scope.launch {
            librarySetup.join()
            underLock {
                if (reloadLocked() != null) return@underLock
                val saved = ledger.keepBeside(installId, other, keep)
                if (saved !is SaveResult.Refused) publishRecords(interrupted = (saved as? SaveResult.Unconfirmed)?.reason)
            }
        }
    }

    /**
     * Cancels a question through [answer], or stops a cancellable install at its next step and releases extraction staging.
     * Returns false after file placement starts or when no cancellable operation remains. Download retention still applies.
     */
    override fun cancel(modId: String): Boolean {
        if (cancellationBlock(modId) != null) return false
        val stage = state.value.operations[modId]?.stage ?: return false
        stage.choice?.let { return answer(it, ChoiceAnswer.Cancel) }
        var cancelled = false
        mutableState.update { state ->
            val operation = state.operations[modId]
            cancelled = operation != null && operation.stage.cancellable && operation.stage.choice == null
            if (cancelled) state.copy(operations = state.operations + (modId to operation!!.copy(stage = OperationStage.Cancelling))) else state
        }
        if (cancelled) runs.value[modId]?.job?.cancel()
        return cancelled
    }

    /**
     * Starts a failed install of [modId] again with the files, sets and on or off it was given, asking only what it was
     * not. A change of an installed mod's files starts from the mod as it is now, and installs them anew once it is gone.
     */
    fun retry(modId: String): Boolean {
        if (state.value.operations[modId]?.stage !is OperationStage.Failed) return false
        val failed = runs.value[modId] ?: return false
        val replacement = failed.asked.replacement?.let { old -> ledger.manifest.records.firstOrNull { it.installId == old.installId } }
        return start(failed.asked.copy(replacement = replacement), failed.unfinished)
    }

    /**
     * Settles question [id] with [answer] when it is still open and the answer fits it: one or more files it offered for
     * a file question, a set it offered for a set question, or a cancel. True when accepted. The install leaves its waiting
     * stage at once, so the question is never shown or answered again, and its own coroutine does the rest, including
     * putting away what a cancelled set question unpacked.
     */
    fun answer(id: ChoiceId, answer: ChoiceAnswer): Boolean {
        val captured = when (answer) {
            is ChoiceAnswer.Files -> answer.copy(fileIds = answer.fileIds.toSet(), profiles = answer.profiles.toSet())
            is ChoiceAnswer.Sets -> answer.copy(keys = answer.keys.toList(), profiles = answer.profiles.toSet())
            ChoiceAnswer.Swap, ChoiceAnswer.KeepBoth, ChoiceAnswer.Cancel -> answer
        }
        var accepted: CompletableDeferred<ChoiceAnswer>? = null
        mutableState.update { state ->
            accepted = null
            val operation = state.operations.values.firstOrNull { it.stage.choice == id } ?: return@update state
            val next = operation.stage.answeredBy(captured) ?: return@update state
            accepted = questions.value[id] ?: return@update state
            val profiles = when (captured) {
                is ChoiceAnswer.Files -> captured.profiles
                is ChoiceAnswer.Sets -> captured.profiles
                ChoiceAnswer.Swap, ChoiceAnswer.KeepBoth, ChoiceAnswer.Cancel -> operation.profiles
            }
            state.copy(operations = state.operations + (operation.modId to operation.copy(stage = next, profiles = profiles)))
        }
        return accepted?.complete(captured) == true
    }

    /** Clears a failed install of [modId]. A question is only ever settled through [answer]. */
    override fun dismiss(modId: String) {
        val failed = runs.value[modId]
        var dismissed = false
        mutableState.update { state ->
            dismissed = state.operations[modId]?.stage is OperationStage.Failed
            if (dismissed) state.copy(operations = state.operations - modId) else state
        }
        if (!dismissed) return
        runs.update { if (it[modId] === failed) it - modId else it }
        failed?.unfinished?.let { unfinished ->
            val putAway = scope.launch(start = CoroutineStart.LAZY) { putAwayUnfinished(unfinished) }
            putAways.update { it + (modId to putAway) }
            putAway.invokeOnCompletion { putAways.update { if (it[modId] === putAway) it - modId else it } }
            putAway.start()
        }
    }

    /**
     * Turns [installId] on or off in [profile], by default the profile shown active now, as a saved choice, exactly as
     * [setMembership] would; no caller waits, so a refusal is shown as [LibraryState.choiceProblem]. Saved while Deadlock
     * runs or may run.
     */
    fun setEnabled(installId: String, enabled: Boolean, profile: ProfileId? = null) {
        val chosen = profile ?: state.value.shownProfiles?.active ?: return
        tell(QueuedChoice(membership(chosen, setOf(installId), enabled), awaited = false))
    }

    /**
     * Turns [installIds] on or off together in [profile], by default the profile shown active now: one saved choice, one
     * change of files, and an Undo. Mods no longer installed are left out.
     */
    fun setEnabledTogether(installIds: Set<String>, enabled: Boolean, profile: ProfileId? = null) {
        val chosen = profile ?: state.value.shownProfiles?.active ?: return
        tell(QueuedChoice(membership(chosen, installIds, enabled, undoable = true), awaited = false))
    }

    /** Asks again for the refused uninstall of [installId] exactly as it was asked. */
    fun retryChange(installId: String) {
        val change = state.value.changes[installId]?.takeIf { it.stage is ChangeStage.Refused } ?: return
        when (val request = change.request) {
            is ChangeRequest.Uninstall -> claim(installId, request)
        }
    }

    /**
     * Uninstalls [installIds] at once, as one change the user can undo, whatever Deadlock is doing: each is turned off
     * in every profile as a saved choice and shows as [LibraryState.leaving] at once. Once the undo window has passed
     * and its files are out of the game, which waits for Deadlock to close, its files go, and its downloads too unless
     * Settings › Mods keeps downloads. [undo] within the window brings them back as they were. A mod whose window has
     * not passed when Hideout quits stays installed, off.
     */
    fun uninstall(installIds: Set<String>) {
        val records = ledger.manifest.records.filter { it.installId in installIds }
        val ids = records.mapTo(LinkedHashSet()) { it.installId }
        if (ids.isEmpty()) return
        val undoes = Undoable.Uninstalled(ids, records.singleOrNull()?.title)
        val edit: (ProfileBook) -> ChoiceEdit<Unit> = { book ->
            val gone = ids intersect book.installed
            if (gone.isEmpty()) ChoiceEdit.Refused(LibraryProblem.NotInstalled)
            else ChoiceEdit.Next(book.profiles.fold(book) { next, profile -> next.membership(profile.id, gone, false) }, Unit, undoes = undoes)
        }
        val saved = CompletableDeferred<Unit>()
        tell(QueuedChoice(edit, awaited = false) { saved.complete(Unit) }, accepted = { it.copy(leaving = it.leaving + ids) })
        if (ids.any { it in state.value.leaving }) scope.launch { leave(ids, saved) }
    }

    private suspend fun leave(ids: Set<String>, saved: Deferred<Unit>) {
        saved.await()
        delay(UndoSeconds.seconds + LeaveMargin)
        val discard = !preferences.installDefaults().keepDownloads
        for (id in ids) {
            val left = state.first { current -> id !in current.leaving || current.filesFree || (current.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == id }?.enabled != true }
            if (id !in left.leaving) continue
            val confirmation = uninstallConfirmation(id)
            if (confirmation == null) {
                mutableState.update { it.copy(leaving = it.leaving - id) }
                continue
            }
            runs.value.values.filter { it.asked.replacement?.installId == id }.forEach { run ->
                val modId = run.asked.modId
                if (state.value.operations[modId]?.stage is OperationStage.Failed) dismiss(modId) else cancel(modId)
            }
            uninstall(confirmation, deleteDownload = discard)
        }
    }

    /** What uninstalling [installId] would remove, as the manifest says now, or null when no such mod is installed. */
    fun uninstallConfirmation(installId: String): UninstallConfirmation? {
        val record = ledger.manifest.records.firstOrNull { it.installId == installId } ?: return null
        return UninstallConfirmation(record, ledger.deletableDownloads(record))
    }

    /**
     * Uninstalls the mod [confirmation] names as the install list holds it then, every file of every set, and the
     * downloads that may go with it when [deleteDownload], now. One that is on waits for Deadlock to be known closed.
     */
    fun uninstall(confirmation: UninstallConfirmation, deleteDownload: Boolean) =
        claim(confirmation.record.installId, ChangeRequest.Uninstall(confirmation, deleteDownload && confirmation.downloads.isNotEmpty()))

    /** Forgets a refused uninstall of [installId]; a running one stays. */
    fun dismissChange(installId: String) {
        mutableState.update { state -> if (state.changes[installId]?.stage == ChangeStage.Running) state else state.copy(changes = state.changes - installId) }
    }

    private fun claim(installId: String, request: ChangeRequest.Uninstall) {
        val title = ledger.manifest.records.firstOrNull { it.installId == installId }?.title ?: request.confirmation.record.title
        var claimed = false
        mutableState.update { state ->
            claimed = state.changes[installId]?.stage != ChangeStage.Running
            if (claimed) state.copy(changes = state.changes + (installId to SetChange(installId, title, request, ChangeStage.Running))) else state
        }
        if (claimed) scope.launch { runChange(installId, request) }
    }

    private suspend fun runChange(installId: String, request: ChangeRequest.Uninstall) {
        librarySetup.join()
        underLock {
            val outcome = changing { changeLocked(installId, request) }
            if (outcome is ChangeOutcome.Done) {
                tidyLocked(request.confirmation.record.gameRoot)
                val had = request.confirmation.record
                had.sets.mapNotNull { it.fileId }.distinct().takeIf { it.isNotEmpty() }?.let { files -> attempt { preferences.rememberFiles(had.modId, files) } }
            }
            publishRecords((outcome as? ChangeOutcome.Refused)?.interrupted, recheck = setOf(installId)) { changes ->
                when {
                    changes[installId]?.request != request -> changes
                    outcome is ChangeOutcome.Refused -> changes + (installId to changes.getValue(installId).copy(stage = ChangeStage.Refused(outcome.problem)))
                    else -> changes - installId
                }
            }
            mutableState.update { it.copy(leaving = it.leaving - installId) }
        }
        refreshLoading(request.confirmation.record.gameRoot)
    }

    private suspend fun changeLocked(installId: String, request: ChangeRequest.Uninstall): ChangeOutcome {
        val library = state.value.library
        if (library !is LibraryStatus.Ready) return ChangeOutcome.Refused(LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason))
        saveAdmittedLocked()
        recoverLocked()?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        val record = ledger.manifest.records.firstOrNull { it.installId == installId } ?: return ChangeOutcome.Done
        val root = record.gameRoot
        if (attempt { locator.check(root.path) } != Attempt.Done(FolderCheck.Valid(root))) return ChangeOutcome.Refused(LibraryProblem.GameNotFound)
        if (record.enabled) processProblem()?.let { return ChangeOutcome.Refused(it) }
        return parked(root, !record.enabled) { ledger.uninstall(record, if (request.deleteDownload) ledger.deletableDownloads(record) else emptyList()) }
    }

    /**
     * Saves [order], install ids with the winner first, as the active profile's order of [view]'s game. [view] must be
     * the one shown now, [LibraryState.orderView], for the profile shown active now, and [order] a rearrangement of its
     * sets; it is refused when another profile is active by the time it is saved. Accepted at once, while Deadlock runs
     * or may run too, and shown as the new [LibraryState.orderView], so the next drag can start from it before this one
     * is saved. The files follow once the save is confirmed and Deadlock is known closed. Asking for the order [view]
     * shows asks for nothing. A refusal, here or when it is saved, is shown as [LibraryState.choiceProblem]; a refused
     * save also refuses the drags started from it and shows the saved order.
     */
    fun reorder(view: OrderView, order: List<String>) {
        if (order == view.sets) return
        val root = view.root
        val profile = state.value.shownProfiles?.active ?: return
        val choice = QueuedChoice(arrange(profile, root, order), awaited = false, reorder = root)
        val stale = order.size != view.sets.size || order.toSet() != view.sets.toSet()
        tell(choice, accepted = { it.copy(orderView = OrderView(root, order)) }) { state ->
            LibraryProblem.LoadOrderChanged.takeIf { stale || view !== state.orderView }
        }
    }

    fun dismissChoiceProblem() {
        mutableState.update { if (it.choiceProblem == null) it else it.copy(choiceProblem = null) }
    }

    private class UndoEntry(
        val token: UndoToken,
        val before: ProfileBook,
        val expected: ProfileBook,
        val created: TimeMark = TimeSource.Monotonic.markNow(),
    )

    @Volatile
    private var undoEntry: UndoEntry? = null

    suspend fun createProfile(spec: NewProfile): MutationResult<ProfileId> {
        val name = spec.name.trim()
        val id = ProfileId(ids())
        return choose { book ->
            profileNameProblem(name, book.profiles)?.let { return@choose ChoiceEdit.Refused(it) }
            val (enabled, orders) = when (val seed = spec.seed) {
                ProfileSeed.Empty -> emptySet<String>() to book.activeProfile.orders
                is ProfileSeed.Copy -> book.profiles.firstOrNull { it.id == seed.id }?.let { it.enabled to it.orders } ?: return@choose ChoiceEdit.Refused(LibraryProblem.ProfileNotFound)
            }
            val created = book.copy(active = if (spec.activate) id else book.active, profiles = book.profiles + Profile(id, name, spec.colour, enabled, orders))
            ChoiceEdit.Next(created, id)
        }
    }

    /**
     * Saves [id] as the active profile; [SwitchSummary] counts what the saved choices turn off and on, not what the files
     * did. A switch offers no Undo: the profile left is one click away.
     */
    suspend fun switchProfile(id: ProfileId): MutationResult<SwitchSummary> = choose { book ->
        val next = book.profiles.firstOrNull { it.id == id } ?: return@choose ChoiceEdit.Refused(LibraryProblem.ProfileNotFound)
        val before = book.activeProfile.enabled
        val off = (before - next.enabled).size
        val on = (next.enabled - before).size
        ChoiceEdit.Next(book.copy(active = id), SwitchSummary(id, off, on))
    }

    suspend fun renameProfile(id: ProfileId, name: String): MutationResult<Unit> {
        val trimmed = name.trim()
        return choose { book ->
            profileNameProblem(trimmed, book.profiles, id)?.let { return@choose ChoiceEdit.Refused(it) }
            edited(book, id) { it.copy(name = trimmed) }
        }
    }

    suspend fun recolourProfile(id: ProfileId, colour: ProfileColour): MutationResult<Unit> = choose { book -> edited(book, id) { it.copy(colour = colour) } }

    private fun edited(book: ProfileBook, id: ProfileId, edit: (Profile) -> Profile): ChoiceEdit<Unit> {
        if (book.profiles.none { it.id == id }) return ChoiceEdit.Refused(LibraryProblem.ProfileNotFound)
        return ChoiceEdit.Next(book.copy(profiles = book.profiles.map { if (it.id == id) edit(it) else it }), Unit)
    }

    suspend fun duplicateProfile(id: ProfileId): MutationResult<ProfileId> {
        val copyId = ProfileId(ids())
        return choose { book ->
            val source = book.profiles.firstOrNull { it.id == id } ?: return@choose ChoiceEdit.Refused(LibraryProblem.ProfileNotFound)
            var name = "${source.name} copy"
            var suffix = 2
            while (profileNameProblem(name, book.profiles) != null) name = "${source.name} copy ${suffix++}"
            ChoiceEdit.Next(book.copy(profiles = book.profiles + source.copy(id = copyId, name = name, colour = book.nextColour)), copyId)
        }
    }

    /**
     * Deletes [id] from the saved choices. The active profile hands over to the one above it, or below when it is first,
     * in the same edit, so one Undo puts back both; the files follow that switch like any other.
     */
    suspend fun deleteProfile(id: ProfileId): MutationResult<Unit> = choose { book ->
        val index = book.profiles.indexOfFirst { it.id == id }
        when {
            index < 0 -> ChoiceEdit.Refused(LibraryProblem.ProfileNotFound)
            book.profiles.size == 1 -> ChoiceEdit.Refused(LibraryProblem.LastProfile)
            else -> {
                val deleted = book.profiles[index]
                val next = if (book.active != id) null else book.profiles[if (index > 0) index - 1 else 1]
                ChoiceEdit.Next(book.copy(active = next?.id ?: book.active, profiles = book.profiles - deleted), Unit, undoes = Undoable.Deleted(deleted.name, next?.name))
            }
        }
    }

    suspend fun setMembership(profile: ProfileId, installId: String, enabled: Boolean): MutationResult<Unit> = choose(membership(profile, setOf(installId), enabled))

    /** Saves [profile]'s orders with [winners] first in the order given and the rest after them as they were; ids an order lacks are skipped. */
    suspend fun putFirst(profile: ProfileId, winners: List<String>): MutationResult<Unit> = choose { book ->
        edited(book, profile) { it.copy(orders = it.orders.mapValues { (_, ids) -> winners.distinct().filter { id -> id in ids } + ids.filter { id -> id !in winners } }) }
    }

    /**
     * Saves the book from before the edit [token] came from, while that edit is still the last one saved
     * and within a few seconds; once. The files follow like any saved choice. The library's own saves while the files
     * follow do not end it; another edit does ([LibraryProblem.UndoExpired]), and so does an install or uninstall in
     * between ([LibraryProblem.UndoConflict]).
     */
    suspend fun undo(token: UndoToken): MutationResult<Unit> {
        if (!undoable(token)) return MutationResult.Refused(LibraryProblem.UndoExpired)
        (token.undoes as? Undoable.Uninstalled)?.let { back -> mutableState.update { it.copy(leaving = it.leaving - back.installIds) } }
        return choose { book ->
            val entry = undoEntry
            when {
                entry == null || !undoable(token) -> ChoiceEdit.Refused(LibraryProblem.UndoExpired)
                book != entry.expected -> ChoiceEdit.Refused(LibraryProblem.UndoConflict)
                else -> ChoiceEdit.Next(entry.before, Unit)
            }
        }
    }

    private fun undoable(token: UndoToken): Boolean =
        undoEntry?.let { token === it.token && it.created.elapsedNow().inWholeSeconds < UndoSeconds } == true

    private fun membership(profile: ProfileId, installIds: Set<String>, enabled: Boolean, undoable: Boolean = false): (ProfileBook) -> ChoiceEdit<Unit> = { book ->
        val chosen = book.profiles.firstOrNull { it.id == profile }
        val ids = installIds intersect book.installed
        when {
            chosen == null -> ChoiceEdit.Refused(LibraryProblem.ProfileNotFound)
            ids.isEmpty() -> ChoiceEdit.Refused(LibraryProblem.NotInstalled)
            else -> {
                val turned = Undoable.Turned(enabled, ids.count { (it in chosen.enabled) != enabled }, chosen.name.takeIf { profile != book.active })
                ChoiceEdit.Next(book.membership(profile, ids, enabled), Unit, undoes = turned.takeIf { undoable })
            }
        }
    }

    private fun arrange(profile: ProfileId, root: GameRoot, order: List<String>): (ProfileBook) -> ChoiceEdit<Unit> = { book ->
        val held = book.activeProfile.orders[root].orEmpty()
        if (book.active != profile || held.size != order.size || held.toSet() != order.toSet()) ChoiceEdit.Refused(LibraryProblem.LoadOrderChanged)
        else ChoiceEdit.Next(book.withOrder(root, order), Unit)
    }

    private suspend fun <T> choose(edit: (ProfileBook) -> ChoiceEdit<T>): MutationResult<T> {
        val answer = CompletableDeferred<MutationResult<T>>()
        admit(QueuedChoice(edit, awaited = true) { answer.complete(it) })?.let { return MutationResult.Refused(it) }
        return answer.await()
    }

    private fun tell(choice: QueuedChoice<Unit>, accepted: (LibraryState) -> LibraryState = { it }, check: (LibraryState) -> LibraryProblem? = { null }) {
        val refusal = admit(choice, { accepted(it).copy(choiceProblem = null) }, check)
        if (refusal != null) mutableState.update { it.copy(choiceProblem = refusal) }
    }

    private fun admit(choice: QueuedChoice<*>, accepted: (LibraryState) -> LibraryState = { it }, check: (LibraryState) -> LibraryProblem? = { null }): LibraryProblem? {
        var refusal: LibraryProblem? = null
        mutableState.update { state ->
            refusal = state.editBlock() ?: check(state)
            if (refusal != null) state else accepted(state.copy(queued = state.queued + choice)).projected()
        }
        if (refusal == null) wake.trySend(Unit)
        return refusal
    }

    private suspend fun work() {
        while (true) {
            if (saveNext()) continue
            val step = attempt { underLock { reconcileLocked() } }.orElse { reason ->
                failure = LibraryProblem.Unexpected(reason)
                publishChoices()
                Step.Stopped
            }
            if (findGameAgain) {
                findGameAgain = false
                mutex.withLock { resolve(preferSteam = false) }
            }
            if (step != Step.Again && state.value.queued.isEmpty()) return
        }
    }

    private suspend fun saveNext(): Boolean = underLock {
        val head = state.value.queued.firstOrNull() ?: return@underLock false
        saveLocked(head)
        true
    }

    private suspend fun <T> saveLocked(head: QueuedChoice<T>) {
        val doubt = reloadLocked()
        var before: ProfileBook? = null
        val edit = when {
            doubt != null -> ChoiceEdit.Refused(LibraryProblem.Interrupted(doubt))
            ledger.manifest.pending != null -> ChoiceEdit.Refused(LibraryProblem.Interrupted((state.value.library as? LibraryStatus.Ready)?.interrupted ?: SettlingInterruption))
            else -> try {
                val input = ledger.manifest.chosen()
                before = input
                head.edit(input)
            } catch (e: IllegalArgumentException) {
                ChoiceEdit.Refused(LibraryProblem.Unexpected(e.message ?: "the choice is not valid"))
            }
        }
        val answer: MutationResult<T> = attempt {
            when (edit) {
                is ChoiceEdit.Refused -> MutationResult.Refused(edit.problem)
                is ChoiceEdit.Next -> when (val saved = ledger.saveChoices(edit.target)) {
                    SaveResult.Saved -> {
                        val book = before?.takeIf { it != edit.target || edit.undoes is Undoable.Uninstalled }
                        undoEntry = edit.undoes?.let { undoes -> book?.let { UndoEntry(UndoToken(undoes), it, ledger.manifest.chosen()) } }
                        MutationResult.Done(edit.value)
                    }
                    is SaveResult.Refused -> MutationResult.Refused(LibraryProblem.ManifestSave(saved.reason))
                    is SaveResult.Unconfirmed -> MutationResult.Refused(LibraryProblem.Interrupted(saved.reason))
                }
            }
        }.orElse { MutationResult.Refused(LibraryProblem.Unexpected(it)) }
        val refused = (answer as? MutationResult.Refused)?.problem
        val dragged = head.reorder?.takeIf { refused != null }
        var dropped = emptyList<QueuedChoice<*>>()
        val doubtNow = ledger.uncertainty
        mutableState.update { state ->
            val rest = state.queued.filter { it !== head }
            dropped = if (dragged == null) emptyList() else rest.filter { it.reorder == dragged }
            val ready = state.library as? LibraryStatus.Ready
            state.copy(
                undo = undoEntry?.token,
                queued = rest.filter { it !in dropped },
                choiceProblem = if (refused != null && !head.awaited) refused else state.choiceProblem,
                library = if (doubtNow != null && ready != null) ready.copy(interrupted = doubtNow) else state.library,
            ).confirmed(renew = dragged != null)
        }
        head.complete(answer)
        if (refused != null) dropped.forEach { it.complete(MutationResult.Refused(refused)) }
    }

    private enum class Step { Again, Done, Stopped }

    private suspend fun reconcileLocked(): Step {
        if (state.value.library !is LibraryStatus.Ready) return Step.Stopped
        if (reloadLocked() != null) return Step.Stopped
        saveAdmittedLocked()
        val manifest = ledger.manifest
        if (manifest.reached(pastLimit)) return reachedLocked()
        if (manifest.pending?.whileGameRuns != true) processProblem()?.let { problem ->
            if (manifest.pending == null) manifest.savedChoices?.let { planLocked(it.target) }
            if (manifest.reached(pastLimit)) return reachedLocked()
            manifest.pending?.let { pending -> publishRecords(interrupted = fenceReason(pending, problem), recheck = pending.installIds) }
            mutableState.update { it.withSync() }
            return Step.Stopped
        }
        working = true
        mutableState.update { it.withSync() }
        try {
            return stepLocked()
        } finally {
            working = false
            mutableState.update { it.withSync() }
        }
    }

    private suspend fun reachedLocked(): Step {
        failure = null
        ledger.clearReached()
        publishChoices()
        return Step.Done
    }

    private sealed interface Plan {
        data class Moves(val moves: List<Move>, val pastLimit: Set<String>) : Plan

        data class Failed(val problem: LibraryProblem) : Plan
    }

    private suspend fun planLocked(target: ProfileBook): Plan {
        val wanted = target.activeProfile
        val moves = mutableListOf<Move>()
        val past = LinkedHashSet<String>()
        for ((root, held) in ledger.manifest.records.groupBy { it.gameRoot }.toSortedMap(compareBy { it.path })) {
            if (wanted.onOrder(root) == onLoadOrder(held)) continue
            if (attempt { locator.check(root.path) } != Attempt.Done(FolderCheck.Valid(root))) return Plan.Failed(LibraryProblem.GameNotFound)
            val loading = when (val listed = onListing(root)) {
                is OnListing.Unreadable -> return Plan.Failed(LibraryProblem.AddonsUnreadable(listed.reason))
                is OnListing.Listed -> listed.entries
            }
            val planned = planPlacement(held, wanted, foreignNumbers(loading, held), stuck = stuck())
            moves += movesBetween(held, planned.records)
            past += planned.pastLimit
        }
        pastLimit = past
        mutableState.update { if (it.pastLimit == past) it else it.copy(pastLimit = past) }
        return Plan.Moves(moves, past)
    }

    private suspend fun stepLocked(): Step {
        ledger.manifest.pending?.let { pending ->
            val left = leftInPlace
            val reason = recoverLocked() ?: run {
                if (pending is PendingChange.ConfigEdit) findGameAgain = true
                discardLocked()
                val moved = pending is PendingChange.Arrange || pending is PendingChange.Relocate
                if (moved && leftInPlace != left && ledger.manifest.savedChoices != null) return failedLocked(LibraryProblem.PlacementFailed(LeftInPlace))
                return Step.Again
            }
            return failedLocked(LibraryProblem.Interrupted(reason))
        }
        val target = ledger.manifest.savedChoices?.target ?: return Step.Done
        val edited = ledger.manifest.edited
        val (moves, past) = when (val plan = planLocked(target)) {
            is Plan.Failed -> return failedLocked(plan.problem)
            is Plan.Moves -> plan
        }
        val outcome = changing { ledger.arrange(moves, target.membership(target.active, past, false)) }
        ((outcome as? ChangeOutcome.Refused)?.problem as? LibraryProblem.FileChanged)?.let { changed ->
            attempt { checkLocked(ledger.manifest.records.filter { record -> record.files.any { it.relativePath(record) == changed.path } }, read = true) }
        }
        publishRecords(ledger.uncertainty ?: (outcome as? ChangeOutcome.Refused)?.interrupted, moves.map { it.source.installId }.toSet())
        if (outcome !is ChangeOutcome.Refused) return Step.Again
        if (outcome.interrupted == null && ledger.manifest.edited != edited) return Step.Again
        return failedLocked(outcome.problem)
    }

    private suspend fun changing(change: suspend () -> ChangeOutcome): ChangeOutcome =
        attempt { change() }.orElse { ChangeOutcome.Refused(LibraryProblem.Unexpected(it)) }

    private suspend fun failedLocked(problem: LibraryProblem): Step {
        if (processProblem() == null) failure = problem
        publishChoices()
        return Step.Stopped
    }

    private suspend fun saveAdmittedLocked() {
        while (true) saveLocked(state.value.queued.firstOrNull() ?: return)
    }

    /**
     * Tries again at once to reach the saved choices: the install list is read again first if it is in doubt, and the
     * files are touched only once Deadlock is known closed. The library also tries again by itself after a failure on
     * every new choice or install, when Deadlock closes, when the game folder comes back and at start; the failure
     * stays shown until an attempt reaches them.
     */
    fun retryChoices() {
        scope.launch {
            librarySetup.join()
            underLock {
                if (reloadLocked() != null) return@underLock
                publishChoices()
            }
            wake.trySend(Unit)
        }
    }

    private fun publishChoices() {
        val doubt = ledger.uncertainty
        mutableState.update { state ->
            val ready = state.library as? LibraryStatus.Ready
            state.copy(library = if (doubt != null && ready != null) ready.copy(interrupted = doubt) else state.library).confirmed()
        }
    }

    private fun LibraryState.confirmed(renew: Boolean = false): LibraryState =
        copy(profiles = if (library is LibraryStatus.Ready) ledger.manifest.chosen() else null).projected(renew).withSync()

    private fun LibraryState.withSync(): LibraryState {
        val sync = syncOf(this)
        return if (sync == choiceSync) this else copy(choiceSync = sync)
    }

    private fun syncOf(state: LibraryState): ChoiceSync {
        val manifest = ledger.manifest
        if (manifest.savedChoices == null || manifest.reached(pastLimit)) return ChoiceSync.Settled
        ledger.uncertainty?.let { return ChoiceSync.Failed(LibraryProblem.Interrupted(it)) }
        failure?.let { return ChoiceSync.Failed(it) }
        return if (working || state.filesFree) ChoiceSync.Applying else ChoiceSync.Waiting(state.deadlock)
    }

    private suspend fun untouchable(root: GameRoot, gameMayRun: Boolean): String? {
        if (attempt { locator.check(root.path) } != Attempt.Done(FolderCheck.Valid(root))) return "the game folder ${root.path} is not available"
        if (root == parking || gameMayRun) return null
        return processProblem()?.fenceReason()
    }

    private fun LibraryProblem.fenceReason(): String = when (this) {
        LibraryProblem.GameRunning -> GameRunningReason
        LibraryProblem.CustomGameRunning -> CustomGameRunningReason
        else -> ProcessesUnknownReason
    }

    private fun fenceReason(pending: PendingChange, problem: LibraryProblem): String =
        if (pending is PendingChange.ConfigEdit) "the game may be running" else problem.fenceReason()

    /**
     * Puts Hideout's lines back in the found game's `gameinfo.gi` after Steam or another tool removed them, recording
     * that mods load, while Deadlock is closed, and says in [LibraryState.rewrite] what took them out when it was not
     * Hideout. Null only once mods verifiably load.
     */
    suspend fun reapply(): LibraryProblem? {
        val result = underLock {
            when (val writable = writableLocked()) {
                is Writable.Refused -> writable.problem to null
                is Writable.Ok -> putBackLocked(writable.root) to writable.root
            }
        }
        result.second?.let { refreshLoading(it) }
        val problem = result.first ?: LibraryProblem.LoadingNotVerified.takeIf { state.value.loading !is ModLoading.Loads }
        // Deadlock starting again keeps a rewrite waiting for it; any other failure is told in place of the wait.
        if (problem != null && problem != LibraryProblem.GameRunning) {
            mutableState.update { state -> state.copy(rewrite = state.rewrite?.takeUnless { it.outcome == RewriteOutcome.WaitingForGame }) }
        }
        return problem
    }

    private suspend fun putBackLocked(root: GameRoot): LibraryProblem? {
        val by = rewriteOf(root, gameInfoBytes(root) { return it })
        ledger.ensureLoading(root)?.let { return it }
        by?.let(::putBack)
        return null
    }

    /** The user has read the last [LibraryState.rewrite]; putting its lines back later says nothing more. */
    fun dismissRewrite() = mutableState.update { it.copy(rewrite = it.rewrite?.copy(seen = true)) }

    /**
     * Records that the found game loads mods, adding nothing to `gameinfo.gi`: Hideout's block is left to an import's
     * takeover, the shell's reapply and Play. Setup chooses with this, so nothing it does makes the game load more than
     * another manager left it loading before an import has read it.
     */
    suspend fun chooseLoading(): LibraryProblem? {
        val result = underLock {
            val root = (state.value.game as? GameStatus.Ready)?.root ?: return@underLock LibraryProblem.GameNotFound to null
            if (state.value.library !is LibraryStatus.Ready) return@underLock LibraryProblem.LibraryUnavailable(null) to null
            ledger.recordIntent(root, ledger.linesFor(root).copy(mods = true)) to root
        }
        result.second?.let { refreshLoading(it) }
        return result.first
    }

    /**
     * Records whether custom games are in use for the found game. Hideout's block follows at once while Deadlock is
     * closed and nothing else waits, and otherwise the next time it is; the problem says why it did not follow now.
     */
    suspend fun useCustomGames(on: Boolean): LibraryProblem? {
        librarySetup.join()
        val root = (state.value.game as? GameStatus.Ready)?.root ?: return LibraryProblem.GameNotFound
        val problem = underLock {
            ledger.recordIntent(root, ledger.linesFor(root).copy(customGames = on)) ?: when (val writable = writableLocked()) {
                is Writable.Refused -> writable.problem
                is Writable.Ok -> ledger.writeBlock(writable.root, ledger.linesFor(writable.root))
            }
        }
        refreshLoading(root)
        return problem
    }

    fun customGamesRoot(): GameRoot? = (state.value.game as? GameStatus.Ready)?.root

    /** Whether the found game's `gameinfo.gi` names the custom games paths now, which for a running Deadlock is what it started with. */
    suspend fun gamesLines(): GamesLines {
        librarySetup.join()
        val root = customGamesRoot() ?: return GamesLines.Unreadable(GameInfoProblem.Missing)
        val config = when (val read = attempt { ports.gameConfig.read(root) }) {
            is Attempt.Failed -> return GamesLines.Unreadable(GameInfoProblem.Unreadable)
            is Attempt.Done -> when (val config = read.value) {
                ConfigRead.Missing -> return GamesLines.Unreadable(GameInfoProblem.Missing)
                is ConfigRead.Unreadable -> return GamesLines.Unreadable(GameInfoProblem.Unreadable)
                is ConfigRead.Present -> config
            }
        }
        val receipt = ledger.manifest.receipt(root)
        return when (val check = GameInfoPatch.check(config.bytes)) {
            is SearchPathsCheck.Problem -> GamesLines.Unreadable(check.problem)
            is SearchPathsCheck.Read -> when {
                check.customGames -> if (receipt?.lines?.customGames == true) GamesLines.Mounted else GamesLines.Leaving
                receipt?.lines?.customGames != true -> GamesLines.Off
                receipt.written != null && receipt.written != config.image -> GamesLines.Removed
                else -> GamesLines.Pending
            }
        }
    }

    /**
     * Starts the game through [dispatch], with Hideout's mods loading ([modded]) or not, in one admission: with the files
     * as they are placed now, whether or not they have reached the saved choices, after any step of the worker under way,
     * and no choice edit is admitted until it ends. The folder is checked again, `gameinfo.gi` is made to load Hideout's mods or not, the running programs are read once
     * more, and [dispatch], which must only hand the game to Steam and not use this library, runs while the lock is
     * still held. Playing with mods writes Hideout's block as the receipt asks, custom games paths too; playing without
     * removes the whole block, and when another tool's entry loads the mods folder vanilla cannot be guaranteed and is
     * refused rather than pretended. Null once dispatched.
     *
     * Best effort after that: Steam starts the game a moment later. A choice saved in that moment may start moving
     * files before Deadlock shows as running; each step's process fence then stops the change, and its journal waits
     * for Deadlock to close.
     */
    suspend fun launch(modded: Boolean, dispatch: suspend (GameRoot) -> Unit): LibraryProblem? = admitted({ prepareLaunchLocked(it, modded) }) { root ->
        processProblem() ?: (attempt { dispatch(root) } as? Attempt.Failed)?.let { LibraryProblem.Unexpected(it.reason) }
    }

    /**
     * Holds the game's files for a custom game until [releaseSession], whether or not Deadlock runs, and writes nothing.
     * While it holds, every fence and everything that waits on one acts as it does for a running game, without saying
     * Deadlock runs; autoexec.cfg, the crosshair, downloads into off folders and saved choices go on as they do then, and
     * saved choices not reached yet wait for the release. Refused while another hold stands, and once a restart of
     * Hideout has begun, which would end it.
     */
    suspend fun admitSession(): SessionAdmission {
        if (restartBegun()) return SessionAdmission.Refused(LibraryProblem.Busy)
        var held: GameRoot? = null
        val problem = admitted(::prepareSessionLocked) { root ->
            mutableState.update { it.copy(sessionHold = SessionHold(root)).withSync() }
            if (restartBegun()) {
                mutableState.update { it.copy(sessionHold = null).withSync() }
                LibraryProblem.Busy
            } else {
                held = root
                null
            }
        }
        return problem?.let { SessionAdmission.Refused(it) } ?: SessionAdmission.Held(checkNotNull(held))
    }

    fun refuseSessionsWhen(begun: () -> Boolean) {
        restartBegun = begun
    }

    /**
     * Starts the client through [dispatch] for the game the hold was admitted on, whichever is found now, and edits no
     * file, since the session's setup came before the hold. Refused while another launch or the release is under way.
     * Null once dispatched, and the client shows in [LibraryState.deadlock] when it is up.
     */
    suspend fun launchSessionClient(dispatch: suspend (GameRoot) -> Unit): LibraryProblem? {
        var refusal: LibraryProblem? = null
        var root: GameRoot? = null
        mutableState.update { state ->
            val hold = state.sessionHold
            root = hold?.root
            refusal = when (hold?.step) {
                null -> LibraryProblem.Unexpected(NoSessionHolds)
                SessionStep.Steady -> null
                SessionStep.LaunchingClient, SessionStep.Releasing -> LibraryProblem.Busy
            }
            if (refusal == null) state.copy(sessionHold = hold?.copy(step = SessionStep.LaunchingClient)) else state
        }
        refusal?.let { return it }
        try {
            return (attempt { dispatch(checkNotNull(root)) } as? Attempt.Failed)?.let { LibraryProblem.Unexpected(it.reason) }
        } finally {
            mutableState.update { state -> state.copy(sessionHold = state.sessionHold?.copy(step = SessionStep.Steady)) }
        }
    }

    /** Ends Deadlock's client for the restart a custom game asked the player for. Throws when it would not end. */
    suspend fun closeSessionClient() = ports.processes.endGame()

    /**
     * Ends the hold, whether or not Deadlock runs. It finishes what a fence stopped once the client allows and lets
     * everything that waited go on. Any problem is returned and the hold stays, so this can be asked again. Null without a hold.
     */
    suspend fun releaseSession(): LibraryProblem? {
        var refusal: LibraryProblem? = null
        var root: GameRoot? = null
        mutableState.update { state ->
            val hold = state.sessionHold
            root = hold?.root
            refusal = LibraryProblem.Busy.takeIf { hold != null && hold.step != SessionStep.Steady }
            if (hold != null && refusal == null) state.copy(sessionHold = hold.copy(step = SessionStep.Releasing)) else state
        }
        refusal?.let { return it }
        val held = root ?: return null
        val problem = underLock {
            val stands = recoverLocked()?.let { LibraryProblem.Interrupted(it) }
            mutableState.update { state ->
                state.copy(sessionHold = if (stands == null) null else state.sessionHold?.copy(step = SessionStep.Steady)).withSync()
            }
            stands
        }
        if (problem == null) filesFreed()
        refreshLoading(held)
        return problem
    }

    /**
     * Holds [root], if nothing does, for a custom game whose hold a crash may have lost, so [releaseSession] can end it.
     * A folder that is no longer the game's has nothing to hold.
     */
    suspend fun resumeSession(root: GameRoot) {
        librarySetup.join()
        if ((attempt { locator.check(root.path) } as? Attempt.Done)?.value !is FolderCheck.Valid) return
        mutableState.update { it.copy(sessionHold = it.sessionHold ?: SessionHold(root)).withSync() }
    }

    private suspend fun admitted(prepare: suspend (GameRoot) -> LibraryProblem?, then: suspend (GameRoot) -> LibraryProblem?): LibraryProblem? {
        var refusal: LibraryProblem? = null
        mutableState.update { state ->
            refusal = state.launchBlock()
            if (refusal == null) state.copy(launching = true) else state
        }
        refusal?.let { return it }
        try {
            librarySetup.join()
            val game = mutex.withLock {
                val current = state.value.game as? GameStatus.Ready
                if (current != null && locator.check(current.root.path) == FolderCheck.Valid(current.root)) current
                else resolve(preferSteam = false) as? GameStatus.Ready
            } ?: return LibraryProblem.GameNotFound
            val problem = underLock { prepare(game.root) ?: then(game.root) }
            refreshLoading(game.root)
            return problem
        } finally {
            mutableState.update { it.copy(launching = false) }
        }
    }

    private suspend fun prepareSessionLocked(root: GameRoot): LibraryProblem? {
        if (state.value.sessionHold != null) return LibraryProblem.CustomGameRunning
        recoverLocked()?.let { return LibraryProblem.Interrupted(it) }
        reloadLocked()?.let { return LibraryProblem.Interrupted(it) }
        val library = state.value.library
        return if (library is LibraryStatus.Ready) null else LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason)
    }

    private suspend inline fun gameInfoBytes(root: GameRoot, onProblem: (LibraryProblem) -> Nothing): ByteArray =
        when (val read = attempt { ports.gameConfig.read(root) }) {
            is Attempt.Failed -> onProblem(LibraryProblem.GameInfo(GameInfoProblem.Unreadable))
            is Attempt.Done -> when (val config = read.value) {
                ConfigRead.Missing -> onProblem(LibraryProblem.GameInfo(GameInfoProblem.Missing))
                is ConfigRead.Unreadable -> onProblem(LibraryProblem.GameInfo(GameInfoProblem.Unreadable))
                is ConfigRead.Present -> config.bytes
            }
        }

    private suspend fun prepareLaunchLocked(root: GameRoot, modded: Boolean): LibraryProblem? {
        processProblem()?.let { return it }
        recoverLocked()?.let { return LibraryProblem.Interrupted(it) }
        if (modded) reclaimLocked(root, ledger.linesFor(root).copy(mods = true))?.let { return it }
        val bytes = gameInfoBytes(root) { return it }
        val coveredBy = when (val check = GameInfoPatch.check(bytes)) {
            is SearchPathsCheck.Problem -> return LibraryProblem.GameInfo(check.problem)
            is SearchPathsCheck.Read -> check.coveredBy
        }
        if (!modded && coveredBy == LoadingOwner.AnotherTool) return LibraryProblem.AnotherToolLoadsMods
        val lines = if (modded) ledger.linesFor(root).copy(mods = true) else HideoutLines(mods = false, customGames = false)
        val needsEdit = when (val edit = GameInfoPatch.withBlock(bytes, lines, ledger.manifest.records.overflowIn(root))) {
            is GameInfoEdit.Refused -> return LibraryProblem.GameInfo(edit.problem)
            GameInfoEdit.Unchanged -> false
            is GameInfoEdit.Patched -> true
        }
        if (!needsEdit) {
            reloadLocked()?.let { return LibraryProblem.Interrupted(it) }
            val library = state.value.library
            if (library !is LibraryStatus.Ready) return LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason)
            return if (ledger.manifest.pending == null) ledger.recordIntent(root, lines) else null
        }
        return when (val writable = writableLocked()) {
            is Writable.Refused -> writable.problem
            is Writable.Ok -> ledger.writeBlock(root, lines)
        }
    }

    /** What restoring `gameinfo.gi` would affect now: the found game and how many of Hideout's sets are on there. Null without both. */
    fun restoreConfirmation(): RestoreConfirmation? {
        val current = state.value
        val root = (current.game as? GameStatus.Ready)?.root ?: return null
        val library = current.library as? LibraryStatus.Ready ?: return null
        return RestoreConfirmation(root, library.records.count { it.gameRoot == root && it.enabled })
    }

    /**
     * Removes Hideout's block from `gameinfo.gi` in the confirmed game, as Play vanilla does, without starting the game.
     * Every set stays installed, on or off as it was. An interrupted change is finished first. Refused when Deadlock
     * may be running, or when another tool's entry loads the mods folder.
     */
    fun restoreOriginal(confirmation: RestoreConfirmation) =
        maintain(Maintenance.Restore(confirmation, RestoreStage.Running)) { Maintenance.Restore(confirmation, restore(confirmation.root)) }

    private suspend fun restore(root: GameRoot): RestoreStage {
        librarySetup.join()
        val problem = underLock {
            recoverLocked()?.let { return@underLock LibraryProblem.Interrupted(it) }
            if (attempt { locator.check(root.path) } != Attempt.Done(FolderCheck.Valid(root))) LibraryProblem.GameNotFound
            else processProblem() ?: prepareLaunchLocked(root, modded = false)
        }
        refreshLoading(root)
        return problem?.let { RestoreStage.Refused(it) } ?: RestoreStage.Restored
    }

    /**
     * What the registered downloads take up, read from each one's own path. Clear's share is the downloads
     * [LibraryLedger.removable] allows with no exception that still hold their recorded bytes. Null until the manifest
     * can be read.
     */
    suspend fun measureDownloads(): DownloadSpace? {
        librarySetup.join()
        val (downloads, clearable) = underLock {
            if (state.value.library !is LibraryStatus.Ready) return@underLock null
            ledger.manifest.downloads to ledger.clearable().toSet()
        } ?: return null
        var matching = 0
        var bytes = 0L
        val clear = mutableListOf<OwnedDownload>()
        for (download in downloads) {
            val entry = DownloadEntry(download.source, download.archive)
            val found = (attempt { ports.downloader.inspect(entry, entry) } as? Attempt.Done)?.value as? EntryState.Present ?: continue
            if (found.image != FileImage(download.archive.sizeBytes, download.archive.sha256)) continue
            matching++
            bytes += found.image.sizeBytes
            if (download in clearable) clear += download
        }
        return DownloadSpace(matching, bytes, ClearConfirmation(clear).takeIf { clear.isNotEmpty() })
    }

    /**
     * Deletes the downloads [confirmation] lists, one archive journal at a time, so other work can take the lock in
     * between. A download that something named or changed since then stays; one no longer registered had already gone
     * and is never looked at. A Clear that stops keeps what it had done before.
     */
    fun clearDownloads(confirmation: ClearConfirmation) =
        maintain(Maintenance.Clear(confirmation, ClearStage.Running)) { Maintenance.Clear(confirmation, clear(confirmation)) }

    private suspend fun clear(confirmation: ClearConfirmation): ClearStage {
        librarySetup.join()
        var done = ClearStage.Cleared(removed = 0, freedBytes = 0, kept = 0)
        for (download in confirmation.downloads) {
            done = when (val result = underLock { clearLocked(download) }) {
                is ChangeOutcome.Refused -> return ClearStage.Refused(result.problem)
                ArchiveResult.Kept -> done.copy(kept = done.kept + 1)
                ArchiveResult.Unregistered -> done.copy(alreadyGone = done.alreadyGone + 1)
                is ArchiveResult.Settled -> done.after(result.outcome, download)
            }
        }
        return done
    }

    private suspend fun clearLocked(download: OwnedDownload): ArchiveResult {
        val library = state.value.library
        if (library !is LibraryStatus.Ready) return ChangeOutcome.Refused(LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason))
        recoverLocked()?.let { return ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
        val result = attempt { ledger.removeArchive(download, installedInto = null) }.orElse { ChangeOutcome.Refused(LibraryProblem.Unexpected(it)) }
        publishRecords(interrupted = (result as? ChangeOutcome.Refused)?.interrupted)
        return result
    }

    /** Backs up the found game's mods and their files, on or off, load order, profiles and autoexec.cfg now, while Deadlock runs too. */
    fun backUp() = maintain(Maintenance.Backups(BackupWork.BackUp, BackupStage.Running)) {
        librarySetup.join()
        val problem = underLock {
            when (val found = settledLocked()) {
                is Writable.Refused -> found.problem
                is Writable.Ok -> takeBackupLocked(found.root, BackupReason.Manual, BackupId(ids()))
            }.also { publishBackups() }
        }
        Maintenance.Backups(BackupWork.BackUp, problem.staged())
    }

    /**
     * Makes the found game's Library as [backup] holds it, in one change: its mods and their files, on or off, load
     * order and profiles, and autoexec.cfg when it holds one; mods installed since are removed. The mods that are on
     * take the numbers their order plans around the files as they are now, so one on now with the same bytes keeps its
     * name. Refused while Deadlock may run, and when a file of a mod changed or cannot be read, since it would go and no
     * backup could hold it. The Library is backed up first, and it is refused when that fails. A set whose files the
     * store no longer holds comes back missing, for Reinstall.
     */
    fun restoreBackup(backup: BackupInfo) = maintain(Maintenance.Backups(BackupWork.Revert(backup), BackupStage.Running)) {
        librarySetup.join()
        val problem = underLock { revertLocked(backup) }
        (state.value.game as? GameStatus.Ready)?.root?.let { refreshLoading(it) }
        Maintenance.Backups(BackupWork.Revert(backup), problem.staged())
    }

    /** Deletes [backup], and the stored files no backup left names. */
    fun deleteBackup(backup: BackupInfo) = maintain(Maintenance.Backups(BackupWork.Delete(backup), BackupStage.Running)) {
        librarySetup.join()
        val problem = underLock {
            val root = foundLocked() ?: return@underLock LibraryProblem.GameNotFound
            val deleted = attempt { ports.backups.delete(root, backup.id) }
            if (deleted is Attempt.Done) pruneLocked(root)
            publishBackups()
            (deleted as? Attempt.Failed)?.let { LibraryProblem.BackupFailed(it.reason) }
        }
        Maintenance.Backups(BackupWork.Delete(backup), problem.staged())
    }

    /** Starts each of [plans] as [update] does, once the found game is backed up; a backup that fails is logged, and they start all the same. */
    fun updateAll(plans: List<UpdatePlan>) {
        if (plans.isEmpty()) return
        scope.launch {
            librarySetup.join()
            underLock {
                val id = BackupId(ids())
                when (val found = settledLocked()) {
                    is Writable.Refused -> found.problem
                    is Writable.Ok -> takeBackupLocked(found.root, BackupReason.BeforeUpdates, id).also { if (it == null) pruneLocked(found.root, keep = setOf(id)) }
                }?.let { ports.log("Update all went on without a backup: ${it.reason()}") }
                publishBackups()
            }
            plans.forEach(::update)
        }
    }

    /**
     * Writes autoexec.cfg as [change] makes it from the file as it is now, read under the library's lock, through the
     * ConfigEdit journal. Deadlock may be running: it reads the file only when it starts. Before the session's first
     * write that changes the game's settings the found game is backed up ([BackupReason.BeforeGameSettings]); a backup
     * that fails is logged and the write goes on, as Update all does. Refused while the Library cannot take a change or
     * the game is not found.
     */
    suspend fun writeAutoexec(change: (AutoexecDocument) -> AutoexecDocument): AutoexecWrite {
        librarySetup.join()
        return underLock {
            val root = when (val found = settledLocked()) {
                is Writable.Refused -> return@underLock AutoexecWrite.Refused(found.problem)
                is Writable.Ok -> found.root
            }
            writeAutoexecLocked(root, { backUpSettingsLocked(root, "autoexec.cfg") }, change = change)
        }
    }

    /**
     * Makes [crosshair] the reticle the found game has saved: the `base` block of each copy of its
     * `citadel_hero_settings.lst`, read again under the library's lock, every other byte kept. The game folder's copy,
     * when not saved yet, is made from Steam Cloud's, or in the game's format without Steam; Steam Cloud's is never
     * made. Only while Deadlock is known closed, since it saves the file again from what it holds as it exits; refused
     * with why otherwise, and while the Library cannot take a change, the game is not found, a copy cannot be read, or
     * Steam Cloud has none for the account. The session's backup is taken first, as for autoexec.cfg. Only the client
     * saves this file, so a custom game's hold does not stop it.
     */
    suspend fun writeReticle(crosshair: Crosshair): ReticleWrite {
        librarySetup.join()
        return underLock {
            val root = when (val found = settledLocked()) {
                is Writable.Refused -> return@underLock ReticleWrite.Refused(found.problem)
                is Writable.Ok -> found.root
            }
            clientProblem()?.let { return@underLock ReticleWrite.Refused(it) }
            val read = when (val now = attempt { ports.heroSettings.readAll(root) }.orElse { ReticleRead.Unreadable(it) }) {
                is ReticleRead.Unreadable -> return@underLock ReticleWrite.Refused(LibraryProblem.ReticleWriteFailed(now.reason))
                ReticleRead.NotSavedYet -> return@underLock ReticleWrite.Refused(LibraryProblem.ReticleWriteFailed("Steam Cloud has no copy of Deadlock's settings for this account yet"))
                is ReticleRead.Read -> now
            }
            val made = read.settings ?: HeroSettings.New
            val next = read.copies.mapValues { (_, settings) -> (settings ?: made).with(crosshair) }
            val changed = next.filter { (copy, settings) -> settings != read.copies[copy] }
            if (changed.isNotEmpty()) backUpSettingsLocked(root, "the game's saved crosshair")
            // The game's own folder first: Steam Cloud's copy is the one the game reads, so it changes last.
            for ((copy, settings) in changed.entries.sortedBy { it.key is HeroSettingsCopy.Cloud }) {
                (attempt { ports.heroSettings.write(root, copy, settings.render()) } as? Attempt.Failed)?.let { return@underLock ReticleWrite.Refused(LibraryProblem.ReticleWriteFailed(it.reason)) }
            }
            ReticleWrite.Written(ReticleRead.Read(next))
        }
    }

    suspend fun readReticle(root: GameRoot): ReticleRead = attempt { ports.heroSettings.readAll(root) }.orElse { ReticleRead.Unreadable(it) }

    /**
     * Emits at once, and whenever a copy of [root]'s saved settings may have changed on disk. A copy whose watch fails,
     * as on a folder not made yet, is watched again a period later on its own and emits then, so a change made while
     * it was down is read; the other copy's watch goes on meanwhile.
     */
    internal fun reticleChanges(root: GameRoot): Flow<Unit> = flow {
        emit(Unit)
        val copies = attempt { ports.heroSettings.copies(root) }.orElse { emptyList() }
        val watches = copies.map { copy ->
            ports.heroSettings.changes(root, copy).retryWhen { cause, _ ->
                val retry = cause is Exception && cause !is CancellationException
                if (retry) {
                    delay(watch.period)
                    emit(Unit)
                }
                retry
            }
        }
        emitAll(watches.merge().debounce(watch.debounce))
    }

    private suspend fun backUpSettingsLocked(root: GameRoot, what: String) {
        if (settingsBackedUp) return
        settingsBackedUp = true
        val id = BackupId(ids())
        takeBackupLocked(root, BackupReason.BeforeGameSettings, id)?.let { ports.log("$what was written without a backup: ${it.reason()}") } ?: pruneLocked(root, keep = setOf(id))
        publishBackups()
    }

    private suspend fun writeAutoexecLocked(
        root: GameRoot,
        beforeWrite: suspend () -> Unit = {},
        bytes: (AutoexecDocument) -> ByteArray = AutoexecDocument::writable,
        change: (AutoexecDocument) -> AutoexecDocument,
    ): AutoexecWrite {
        val path = ConfigTarget.Autoexec.path
        repeat(2) {
            val current = when (val file = (attempt { inventory.read(root, path, MaxConfigBytes) } as? Attempt.Done)?.value) {
                is FileRead.Present -> file.bytes
                FileRead.Absent -> null
                else -> return AutoexecWrite.Refused(LibraryProblem.FileUnreadable(path.value))
            }
            val document = AutoexecDocument.parse(current)
            val next = change(document)
            if (next.render().contentEquals(current ?: ByteArray(0))) return AutoexecWrite.Written(document)
            beforeWrite()
            val written = bytes(next)
            when (val problem = ledger.writeConfig(root, ConfigTarget.Autoexec, current?.let(Sha256::image), written)) {
                null -> return AutoexecWrite.Written(AutoexecDocument.parse(written))
                is LibraryProblem.FileChanged -> Unit
                else -> return AutoexecWrite.Refused(problem)
            }
        }
        return AutoexecWrite.Refused(LibraryProblem.FileChanged(path.value))
    }

    private suspend fun settledLocked(): Writable {
        val library = state.value.library
        if (library !is LibraryStatus.Ready) return Writable.Refused(LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason))
        saveAdmittedLocked()
        recoverLocked()?.let { return Writable.Refused(LibraryProblem.Interrupted(it)) }
        return foundLocked()?.let { Writable.Ok(it) } ?: Writable.Refused(LibraryProblem.GameNotFound)
    }

    private suspend fun takeBackupLocked(root: GameRoot, reason: BackupReason, id: BackupId): LibraryProblem? {
        val backup = ledger.manifest.backupOf(root)
        for (record in backup.records) for (file in record.files) {
            (attempt { ports.backups.keep(GameEntry(root, record.setFolder(), file.name), file.image) } as? Attempt.Failed)?.let { return LibraryProblem.BackupFailed(it.reason) }
        }
        for (original in backup.originals) {
            (attempt { ports.backups.keepOriginal(root, original) } as? Attempt.Failed)?.let { return LibraryProblem.BackupFailed(it.reason) }
        }
        val held = mutableMapOf<HeldFile, ByteArray>()
        when (val file = (attempt { inventory.read(root, ConfigTarget.Autoexec.path, MaxConfigBytes) } as? Attempt.Done)?.value) {
            is FileRead.Present -> held[HeldFile.Autoexec] = file.bytes
            FileRead.Absent -> held[HeldFile.Autoexec] = ByteArray(0)
            else -> Unit
        }
        for (copy in attempt { ports.heroSettings.copies(root) }.orElse { emptyList() }) {
            ((attempt { ports.heroSettings.read(root, copy) } as? Attempt.Done)?.value as? FileRead.Present)?.let { held[copy.held] = it.bytes }
        }
        val info = BackupInfo(id, clock.now().toEpochMilliseconds(), reason, backup.records.size)
        return (attempt { ports.backups.publish(root, info, backup, held) } as? Attempt.Failed)?.let { LibraryProblem.BackupFailed(it.reason) }
    }

    private suspend fun revertLocked(backup: BackupInfo): LibraryProblem? {
        processProblem()?.let { return it }
        val root = when (val found = settledLocked()) {
            is Writable.Refused -> return found.problem
            is Writable.Ok -> found.root
        }
        val held = attempt { ports.backups.read(root, backup.id) }.orElse { return LibraryProblem.BackupUnreadable(it) }
        val files = attempt { ports.backups.held(root, backup.id) }.orElse { return LibraryProblem.BackupUnreadable(it) }
        val change = when (val planned = ledger.planRevert(root, held)) {
            is MutationResult.Refused -> return planned.problem
            is MutationResult.Done -> planned.value
        }
        val safety = BackupId(ids())
        takeBackupLocked(root, BackupReason.BeforeRestore, safety)?.let { return it }
        val outcome = changing { ledger.revert(change) }
        if (outcome == ChangeOutcome.Done) pruneLocked(root, keep = setOf(backup.id, safety))
        undoEntry = null
        mutableState.update { it.copy(undo = null) }
        publishRecords(ledger.uncertainty ?: (outcome as? ChangeOutcome.Refused)?.interrupted, recheck = change.installIds)
        publishBackups()
        if (ledger.manifest.savedChoices != null) wake.trySend(Unit)
        (outcome as? ChangeOutcome.Refused)?.let { return it.problem }
        files[HeldFile.Autoexec]?.let { autoexec ->
            val written = writeAutoexecLocked(root, bytes = { it.render().takeIf(ByteArray::isNotEmpty) ?: it.writable() }) { AutoexecDocument.parse(autoexec) }
            (written as? AutoexecWrite.Refused)?.let { return it.problem }
        }
        // Deadlock may have started since the check above, and would save its own reticle over these as it exits.
        processProblem()?.let { return it }
        for (copy in attempt { ports.heroSettings.copies(root) }.orElse { emptyList() }) {
            val bytes = files[copy.held] ?: continue
            val now = (attempt { ports.heroSettings.read(root, copy) } as? Attempt.Done)?.value as? FileRead.Present
            if (now?.bytes?.contentEquals(bytes) == true) continue
            (attempt { ports.heroSettings.write(root, copy, bytes) } as? Attempt.Failed)?.let { return LibraryProblem.ReticleWriteFailed(it.reason) }
        }
        return null
    }

    private suspend fun pruneLocked(root: GameRoot, keep: Set<BackupId> = emptySet()) {
        val listed = (attempt { ports.backups.list(root) } as? Attempt.Done)?.value ?: return
        val past = listed.filter { it.reason != BackupReason.Manual }.groupBy { it.reason }.values
            .flatMap { reason -> reason.sortedByDescending { it.createdAt }.drop(KeptAutomatic) }.filter { it.id !in keep }
        val deleted = past.filter { attempt { ports.backups.delete(root, it.id) } is Attempt.Done }.toSet()
        val named = (listed - deleted).flatMap { info -> (attempt { ports.backups.read(root, info.id) } as? Attempt.Done)?.value?.contents ?: return }.toSet()
        attempt { ports.backups.prune(root, named + ledger.manifest.contents) }
    }

    private suspend fun tidyLocked(root: GameRoot) {
        if (ledger.manifest.pending != null) return
        pruneLocked(root)
        attempt { ports.installer.tidy(root) }
    }

    private suspend fun tidyAllLocked() {
        val roots = ledger.manifest.records.map { it.gameRoot } + listOfNotNull((state.value.game as? GameStatus.Ready)?.root)
        roots.distinct().forEach { tidyLocked(it) }
    }

    private suspend fun publishBackups() {
        val root = (state.value.game as? GameStatus.Ready)?.root
        val listed = root?.let { (attempt { ports.backups.list(it) } as? Attempt.Done)?.value?.sortedByDescending { backup -> backup.createdAt } }.orEmpty()
        mutableState.update { if ((it.game as? GameStatus.Ready)?.root == root) it.copy(backups = listed) else it }
    }

    private suspend fun foundLocked(): GameRoot? = (state.value.game as? GameStatus.Ready)?.root?.takeIf { attempt { locator.check(it.path) } == Attempt.Done(FolderCheck.Valid(it)) }

    private fun maintain(running: Maintenance, run: suspend () -> Maintenance) {
        fun refused(problem: LibraryProblem): Maintenance = when (running) {
            is Maintenance.Restore -> running.copy(stage = RestoreStage.Refused(problem))
            is Maintenance.Clear -> running.copy(stage = ClearStage.Refused(problem))
            is Maintenance.Backups -> running.copy(stage = BackupStage.Refused(problem))
        }
        var claimed = false
        mutableState.update { state ->
            claimed = state.maintenance?.running != true
            if (claimed) state.copy(maintenance = running) else state
        }
        if (!claimed) return
        scope.launch {
            val done = attempt { run() }.orElse { refused(LibraryProblem.Unexpected(it)) }
            mutableState.update { it.copy(maintenance = done) }
        }
    }

    private fun start(asked: Asked, unfinished: UnfinishedInstall? = null): Boolean {
        val modId = asked.modId
        val run = InstallRun(asked)
        run.unfinished = unfinished
        run.job = scope.launch(start = CoroutineStart.LAZY) { install(run) }
        var claimed = false
        mutableState.update { state ->
            claimed = state.operations[modId]?.let { it.stage !is OperationStage.Failed } != true
            if (claimed) state.copy(operations = state.operations + (modId to Operation(modId, OperationStage.Preparing, profiles = asked.profiles, installId = asked.replacement?.installId))) else state
        }
        if (!claimed) {
            run.job.cancel()
            return false
        }
        runs.update { it + (modId to run) }
        run.job.start()
        return true
    }

    private suspend fun install(run: InstallRun) {
        val usage = analytics?.begin(UsageOperation.ModInstall)
        val modId = run.asked.modId
        var failure: OperationStage.Failed? = null
        var ended = false
        var cancelled = false
        try {
            putAways.value[modId]?.join()
            failure = attempt { runInstall(run) }.orElse { OperationStage.Failed(LibraryProblem.Unexpected(it)) }
            ended = true
        } finally {
            val failed = failure
            val usageStage = when (mutableState.value.operations[modId]?.stage) {
                is OperationStage.Downloading, is OperationStage.Waiting -> UsageStage.Download
                OperationStage.Installing, OperationStage.Unpacking, is OperationStage.WaitingForGame -> UsageStage.Install
                else -> UsageStage.Prepare
            }
            var kept = false
            mutableState.update { state ->
                val operation = state.operations[modId]
                cancelled = operation?.stage == OperationStage.Cancelling
                kept = failed != null && operation?.stage != OperationStage.Cancelling
                when {
                    runs.value[modId] !== run || operation == null -> state
                    kept -> state.copy(operations = state.operations + (modId to operation.copy(stage = failed!!)))
                    else -> state.copy(operations = state.operations - modId)
                }
            }
            usage?.finish(if (cancelled || !ended) UsageOutcome.Cancelled else if (failed != null) UsageOutcome.Failed else UsageOutcome.Completed, usageStage, failed?.problem?.usageFailure())
            if (!kept) runs.update { if (it[modId] === run) it - modId else it }
            val unfinished = run.unfinished
            val putAway = cancelled || ended && !(kept && failed!!.problem.goesOnLater())
            if (unfinished != null && putAway) withContext(NonCancellable) { putAwayUnfinished(unfinished) }
        }
    }

    private suspend fun keepUnfinished(run: InstallRun, folder: DownloadFolder, fileId: Long) {
        val asked = run.asked
        val earlier = run.unfinished?.takeIf { it.folder == folder.path }?.fileIds.orEmpty()
        val kept = UnfinishedInstall(
            modId = asked.modId,
            folder = folder.path,
            fileIds = (earlier + asked.fileIds + fileId).distinct(),
            profiles = asked.profiles.map { it.value },
            installId = asked.replacement?.installId,
            reinstall = asked.reinstall,
            update = asked.update?.replacements?.map { UnfinishedReplacement(it.from.fileId, it.from.fileMd5, it.to.fileId, it.to.fileMd5) }.orEmpty(),
        )
        run.unfinished?.takeIf { it.folder != folder.path }?.let { putAwayUnfinished(it) }
        run.unfinished = kept
        attempt { preferences.keepUnfinished(kept) }
    }

    private suspend fun downloaded(run: InstallRun) {
        run.unfinished?.let { attempt { preferences.forgetUnfinished(it.modId) } }
        run.unfinished = null
    }

    private suspend fun putAwayUnfinished(unfinished: UnfinishedInstall) {
        attempt { preferences.forgetUnfinished(unfinished.modId) }
        val folder = runCatching { DownloadFolder(unfinished.folder) }.getOrNull() ?: return
        val submission = SubmissionRef.parse(unfinished.modId)
            ?: unfinished.installId?.let { id -> ledger.manifest.records.firstOrNull { it.installId == id }?.submission }
            ?: return
        unfinished.fileIds.forEach { fileId -> attempt { ports.downloader.discardUnfinished(folder, submission, fileId) } }
    }

    private suspend fun resumeUnfinished() {
        val records = (state.value.library as? LibraryStatus.Ready)?.records ?: return
        val kept = (attempt { preferences.unfinishedInstalls() } as? Attempt.Done)?.value.orEmpty()
        for (unfinished in kept) {
            val asked = askedFrom(unfinished, records)
            if (asked == null) putAwayUnfinished(unfinished) else start(asked, unfinished)
        }
    }

    private fun askedFrom(unfinished: UnfinishedInstall, records: List<ManifestRecord>): Asked? {
        val record = unfinished.installId?.let { id -> records.firstOrNull { it.installId == id } ?: return null }
        val submission = record?.submission ?: SubmissionRef.parse(unfinished.modId) ?: return null
        val input = InstallInput.Remote(submission)
        val profiles = unfinished.profiles.mapNotNullTo(HashSet()) { runCatching { ProfileId(it) }.getOrNull() }
        return when {
            unfinished.reinstall -> record?.let { Asked(input, it.modId, emptySet(), replacement = it, reinstall = true) }
            unfinished.update.isNotEmpty() -> record?.let {
                val replacements = unfinished.update.map { kept ->
                    Replacement(
                        SubmissionIdentity(submission.kind, submission.id, kept.fromFileId, kept.fromMd5),
                        SubmissionIdentity(submission.kind, submission.id, kept.toFileId, kept.toMd5),
                    )
                }
                Asked(input, it.modId, emptySet(), fileIds = unfinished.fileIds.toSet(), replacement = it, update = UpdatePlan(it.gameRoot, it.installId, it.provenance, replacements))
            }
            else -> Asked(input, unfinished.modId, profiles, fileIds = unfinished.fileIds.toSet(), replacement = record)
        }
    }

    private suspend fun runInstall(run: InstallRun): OperationStage.Failed? {
        librarySetup.join()
        underLock { reloadLocked() }?.let { return OperationStage.Failed(LibraryProblem.Interrupted(it)) }
        if (run.asked.reinstall) return runReinstall(run)
        run.asked.parts?.let { parts -> return runReorder(run, parts) }
        val input = run.asked.input
        if (input is InstallInput.Local) return runLocalInstall(run, input.file)
        val submission = (input as InstallInput.Remote).submission
        if (state.value.library is LibraryStatus.Ready) settleEarlier(run.asked.modId)?.let { return OperationStage.Failed(LibraryProblem.Interrupted(it)) }
        val current = state.value
        readiness(current, submission, run.asked.replacement != null)?.let { return OperationStage.Failed(it) }
        val target = (current.game as GameStatus.Ready).root
        val defaults = preferences.installDefaults()
        val folder = defaults.downloadFolder ?: ports.downloader.defaultFolder
        val retention = if (defaults.keepDownloads) DownloadRetention.Keep else DownloadRetention.DiscardAfterInstall
        var profiles = run.asked.profiles
        val listed = when (val listing = listings.withPermit { attempt { ports.gameBanana.files(submission) } }.orElse { FileListing.Unavailable(it) }) {
            is FileListing.Withdrawn -> return OperationStage.Failed(LibraryProblem.Withdrawn(listing.why))
            is FileListing.Unavailable -> return OperationStage.Failed(LibraryProblem.MetadataUnavailable(listing.reason))
            is FileListing.Available -> listing
        }
        if (listed.files.isEmpty() && run.asked.replacement == null) return OperationStage.Failed(LibraryProblem.NoFiles)
        val update = run.asked.update
        val targets = update?.let { plan ->
            if (!listed.complete) return OperationStage.Failed(LibraryProblem.ListingIncomplete)
            if (plan.gameRoot != target || plan.installed != run.asked.replacement?.provenance) return OperationStage.Failed(LibraryProblem.UpdateChanged)
            plan.targetsIn(listed.files) ?: return OperationStage.Failed(LibraryProblem.UpdateChanged)
        }
        if (!advance(run, OperationStage.Preparing, listed.title)) return null
        val ordered = orderVersions(listed.files)
        val names = versionNames(listed.title, ordered.map { VersionSource(it.id, it.description, it.fileName) })
        val installed = run.asked.replacement?.sets.orEmpty()
        val declined = run.asked.replacement?.declined.orEmpty()
        val delisted = installed.filter { set -> ordered.none { it.id == set.fileId } }
        val had = (attempt { preferences.lastFiles(run.asked.modId) } as? Attempt.Done)?.value.orEmpty()
        val fresh = had.filterTo(LinkedHashSet()) { id -> ordered.any { it.id == id } }.ifEmpty { if (ordered.isEmpty()) emptySet() else setOf(defaultVersion(ordered).id) }
        val chosen = when {
            run.asked.fileIds.isNotEmpty() -> run.asked.fileIds
            run.asked.replacement == null && (ordered.size == 1 || !run.asked.chooseVersion && !defaults.askWhichVersion) -> if (ordered.size == 1) setOf(ordered.first().id) else fresh
            else -> {
                val preselected = if (run.asked.replacement != null) installed.mapNotNull { it.fileId }.toSet() else fresh
                val kept = underLock { ordered.filter { it.id in had && keptDownload(it.identity(submission)) != null }.mapTo(HashSet()) { it.id } }
                val answer = ask(run) { OperationStage.ChoosingFile(it, ordered, preselected, profiles, delisted, kept) } as? ChoiceAnswer.Files ?: return null
                profiles = answer.profiles.toSet()
                answer.fileIds
            }
        }
        if (chosen.any { id -> ordered.none { it.id == id } && delisted.none { it.fileId == id } }) return OperationStage.Failed(LibraryProblem.FileNotOffered)
        run.asked = run.asked.copy(fileIds = chosen, profiles = profiles)
        val selectedFiles = ordered.filter { it.id in chosen }
        val sources = selectedFiles.associate { it.id to InstalledSource.Remote(it.identity(submission)) }
        val unchanged = update == null && installed.isNotEmpty() && installed.mapNotNullTo(HashSet()) { it.fileId } == chosen &&
            installed.all { set -> set.fileId?.let(sources::get) == set.source }
        val reopened = if (unchanged) chosen.filterTo(HashSet()) { it in declined } else emptySet()
        val replaced: Map<InstalledSource, RemoteFile> = if (update != null && targets != null) {
            update.replacements.zip(targets).associate { (replacement, file) -> InstalledSource.Remote(replacement.from) to file }
        } else {
            selectedFiles.mapNotNull { file -> installed.firstOrNull { it.fileId == file.id && (it.source != sources.getValue(file.id) || file.id in reopened) }?.let { it.source to file } }.toMap()
        }
        val kept = if (update != null) installed.filter { it.source !in replaced }
            else installed.filter { set -> set.fileId in chosen && set.fileId !in reopened && (set.fileId !in sources || sources[set.fileId] == set.source) }
        val fetching = targets?.distinct() ?: selectedFiles.filter { file -> kept.none { it.source == sources.getValue(file.id) } }
        if (run.asked.replacement != null && fetching.isEmpty() && kept == installed) return null
        val total = fetching.sumOf { it.sizeBytes }
        if (!advance(run, OperationStage.Waiting(total))) return null
        val pinned = mutableListOf<ArchiveIdentity>()
        val stagings = mutableListOf<StagingId>()
        try {
            val unpacked = mutableMapOf<RemoteFile, List<SetPlan>>()
            val leftOut = mutableMapOf<Long, Set<String>>()
            var done = 0L
            for (file in fetching) {
                val source = sources.getValue(file.id)
                val own = (installed.firstOrNull { it.source == source }?.download ?: underLock { keptDownload(source.identity) }.takeIf { file.id in had })?.takeIf { intact(source.identity, it, pinned) }
                val archive = own ?: when (val fetched = fetch(run, folder, submission, file, retention, pinned, last = file == fetching.last()) { it.copy(doneBytes = done + it.doneBytes.coerceAtMost(file.sizeBytes), totalBytes = total) }) {
                    is Fetched.Done -> fetched.archive
                    is Fetched.Failed -> return OperationStage.Failed(fetched.problem, file = file.fileName)
                    Fetched.Stopped -> return null
                }
                done += file.sizeBytes
                if (!advance(run, OperationStage.Unpacking)) return null
                val result = unpack(archive) { return OperationStage.Failed(it, file = file.fileName) }.also { stagings += it.staging }
                val grouping = groupVpkSets(result.vpks, pairStrays = true)
                val remembered = run.asked.setKeys[file.id]
                    ?.takeIf { keys -> keys.isNotEmpty() && keys.all { key -> grouping.sets.any { it.key == key } } }
                val before = replaced.filterValues { it == file }.keys
                val known = before.flatMap { source -> installed.filter { it.source == source }.map { it.componentKey } }
                val left = before.flatMap { source -> (source as? InstalledSource.Remote)?.identity?.fileId?.let(declined::get).orEmpty() }.toSet()
                val surviving = known.filter { key -> grouping.sets.any { it.key == key } }
                val options = setOptions(grouping.sets).let { all -> if (file.id in reopened) all.sortedBy { known.indexOf(it.key).takeIf { at -> at >= 0 } ?: Int.MAX_VALUE } else all }
                val preselected = preselect(options, surviving) - left
                val sets = when {
                    grouping.sets.isEmpty() -> return OperationStage.Failed(LibraryProblem.NoCompleteSet(grouping.rejected), file = file.fileName)
                    remembered != null -> remembered.map { key -> grouping.sets.first { it.key == key } }
                    file.id !in reopened && known.isNotEmpty() && surviving.size == known.size && preselected.size == surviving.size -> surviving.map { key -> grouping.sets.first { it.key == key } }
                    known.isEmpty() && grouping.sets.size == 1 && grouping.rejected.isEmpty() -> grouping.sets
                    else -> {
                        val answer = ask(run) { OperationStage.ChoosingSet(it, file.fileName, file.sizeBytes, options, profiles, preselected, grouping.rejected, result.notes) } as? ChoiceAnswer.Sets ?: return null
                        profiles = answer.profiles.toSet()
                        answer.keys.map { key -> grouping.sets.first { it.key == key } }
                    }
                }
                run.asked = run.asked.copy(profiles = profiles, setKeys = run.asked.setKeys + (file.id to sets.map { it.key }))
                leftOut[file.id] = options.map { it.key }.toSet() - sets.map { it.key }.toSet()
                val version = if (listed.complete) VersionPosition(ordered.indexOf(file) + 1, ordered.size) else null
                unpacked[file] = sets.map { set ->
                    if (installed.any { it.source == source && it.componentKey == set.key }) SetPlan.Kept(source, set.key)
                    else SetPlan.Staged(source, file.fileName, names.getValue(file.id), SetOrigin.Downloaded(archive), version, set)
                }
            }
            val placed = mutableSetOf<RemoteFile>()
            val current = buildList {
                for (set in installed) {
                    if (set in kept) add(FileParts(set.fileId, listOf(SetPlan.Kept(set.source, set.componentKey))))
                    else replaced[set.source]?.let { file -> if (placed.add(file)) add(FileParts(file.id, unpacked.getValue(file))) }
                }
            }
            val incoming = fetching.filter { it !in placed }.map { FileParts(it.id, unpacked.getValue(it)) }
            val plan = arrangeParts(current, incoming)
            val files = plan.mapNotNullTo(HashSet()) { (it.source as? InstalledSource.Remote)?.identity?.fileId }
            val nowDeclined = (declined + leftOut).filter { (id, parts) -> id in files && parts.isNotEmpty() }
            if (run.asked.replacement != null && plan == installed.map { SetPlan.Kept(it.source, it.componentKey) } && nowDeclined == declined) return null
            if (!askSwap(run, target, plan, profiles)) return null
            return settle(run, target, listed.title, plan, profiles, nowDeclined)
        } finally {
            putAway(stagings, pinned, retention)
        }
    }

    private suspend fun runReorder(run: InstallRun, parts: List<SetKey>): OperationStage.Failed? {
        val asked = run.asked.replacement ?: return null
        if (!advance(run, OperationStage.Installing, asked.title)) return null
        val outcome = underLock {
            val library = state.value.library
            if (library !is LibraryStatus.Ready) return@underLock ChangeOutcome.Refused(LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason))
            saveAdmittedLocked()
            recoverLocked()?.let { return@underLock ChangeOutcome.Refused(LibraryProblem.Interrupted(it), it) }
            val record = ledger.manifest.records.firstOrNull { it.installId == asked.installId }
            if (record == null || record.sets.map { it.key }.toSet() != parts.toSet()) return@underLock ChangeOutcome.Refused(LibraryProblem.VersionChanged)
            val reordered = record.placed(record.placement, parts.map(record::set))
            if (reordered == record) return@underLock ChangeOutcome.Done
            if (record.enabled) processProblem()?.let { return@underLock ChangeOutcome.Refused(it) }
            changing { ledger.arrange(listOf(Move(record, reordered)), ledger.manifest.profiles) }.also { outcome ->
                publishRecords((outcome as? ChangeOutcome.Refused)?.interrupted, setOf(record.installId))
            }
        }
        return (outcome as? ChangeOutcome.Refused)?.let { OperationStage.Failed(it.problem) }
    }

    private suspend fun runReinstall(run: InstallRun): OperationStage.Failed? {
        val record = run.asked.replacement ?: return null
        if ((state.value.game as? GameStatus.Ready)?.root != record.gameRoot) return OperationStage.Failed(LibraryProblem.GameNotFound)
        if (!advance(run, OperationStage.Preparing, record.title)) return null
        val looked = attempt { underLock { ledger.look(record) } }.orElse { return OperationStage.Failed(LibraryProblem.Unexpected(it)) }
        val damaged = record.sets.filter { set -> record.filesOf(set).any { looked.files[it]?.damage != null } }
        if (damaged.isEmpty()) return null
        val stored = underLock { damaged.filter { set -> storedLocked(record, set) } }
        val defaults = preferences.installDefaults()
        val folder = defaults.downloadFolder ?: ports.downloader.defaultFolder
        val retention = if (defaults.keepDownloads) DownloadRetention.Keep else DownloadRetention.DiscardAfterInstall
        val pinned = mutableListOf<ArchiveIdentity>()
        val stagings = mutableListOf<StagingId>()
        try {
            val rebuilt = mutableMapOf<InstalledSet, SetPlan>()
            for (set in stored) rebuilt[set] = SetPlan.Stored(set.source, set.componentKey)
            for ((source, sets) in (damaged - stored.toSet()).groupBy { it.source }) {
                val name = sets.first().archiveName
                val (unpacked, origin) = when (source) {
                    is InstalledSource.Remote -> {
                        val archive = sets.first().download?.takeIf { intact(source.identity, it, pinned) } ?: when (val fetched = fetchAgain(run, source.identity, folder, retention, pinned)) {
                            is Fetched.Done -> fetched.archive
                            is Fetched.Failed -> return OperationStage.Failed(fetched.problem, file = name)
                            Fetched.Stopped -> return null
                        }
                        if (!advance(run, OperationStage.Unpacking)) return null
                        unpack(archive) { return OperationStage.Failed(it, file = name) } to SetOrigin.Downloaded(archive)
                    }
                    is InstalledSource.Local -> {
                        val picked = (run.asked.input as? InstallInput.Local)?.file ?: return null
                        val snapshot = when (val taken = ports.localImport.snapshot(picked)) {
                            is LocalImportResult.Refused -> return OperationStage.Failed(LibraryProblem.LocalInputRefused(taken.reason))
                            is LocalImportResult.Ready -> taken.snapshot
                        }
                        try {
                            val origin = SetOrigin.Imported(FileImage(snapshot.archive.sizeBytes, snapshot.archive.sha256))
                            if (sets.any { it.origin != origin }) return OperationStage.Failed(LibraryProblem.NotTheInstalledFile)
                            if (!advance(run, OperationStage.Unpacking)) return null
                            unpack(snapshot.archive, snapshot.originalName) { return OperationStage.Failed(it, file = name) } to origin
                        } finally {
                            withContext(NonCancellable) { ports.localImport.release(snapshot) }
                        }
                    }
                }
                stagings += unpacked.staging
                val grouping = groupVpkSets(unpacked.vpks, pairStrays = true)
                for (set in sets) {
                    val vpks = grouping.sets.firstOrNull { it.key == set.componentKey } ?: return OperationStage.Failed(LibraryProblem.NoCompleteSet(grouping.rejected), file = name)
                    rebuilt[set] = SetPlan.Staged.replacing(set, vpks, derivation = null).copy(origin = origin)
                }
            }
            val plan = record.sets.map { set -> rebuilt[set] ?: SetPlan.Kept(set.source, set.componentKey) }
            return settle(run, record.gameRoot, record.title, plan, emptySet())
        } finally {
            putAway(stagings, pinned, retention)
        }
    }

    private fun keptDownload(identity: SubmissionIdentity): ArchiveIdentity? =
        ledger.manifest.downloads.lastOrNull { it.source == identity && it.retention == DownloadRetention.Keep }?.archive

    private suspend fun intact(identity: SubmissionIdentity, archive: ArchiveIdentity, pinned: MutableList<ArchiveIdentity>): Boolean {
        underLock { ledger.pin(archive.path) }
        pinned += archive
        val entry = DownloadEntry(identity, archive)
        val seen = (attempt { ports.downloader.inspect(entry, entry) } as? Attempt.Done)?.value
        return seen is EntryState.Present && seen.image == FileImage(archive.sizeBytes, archive.sha256)
    }

    private suspend fun fetchAgain(run: InstallRun, identity: SubmissionIdentity, folder: DownloadFolder, retention: DownloadRetention, pinned: MutableList<ArchiveIdentity>): Fetched {
        val file = when (val listing = listings.withPermit { attempt { ports.gameBanana.files(identity.ref) } }.orElse { FileListing.Unavailable(it) }) {
            is FileListing.Withdrawn -> return Fetched.Failed(LibraryProblem.Withdrawn(listing.why))
            is FileListing.Unavailable -> return Fetched.Failed(LibraryProblem.MetadataUnavailable(listing.reason))
            is FileListing.Available -> listing.files.firstOrNull { it.id == identity.fileId && (identity.fileMd5 == null || it.md5 == identity.fileMd5) }
                ?: return Fetched.Failed(LibraryProblem.FileNotOffered)
        }
        if (!advance(run, OperationStage.Waiting(file.sizeBytes))) return Fetched.Stopped
        return fetch(run, folder, identity.ref, file, retention, pinned, last = true) { it }
    }

    private suspend fun putAway(stagings: List<StagingId>, pinned: List<ArchiveIdentity>, retention: DownloadRetention) = withContext(NonCancellable) {
        stagings.forEach { attempt { ports.installer.discard(it) } }
        if (pinned.isNotEmpty()) underLock {
            pinned.forEach { ledger.unpin(it.path) }
            if (retention == DownloadRetention.DiscardAfterInstall) discardLocked()
            publishDownloads()
        }
    }

    private sealed interface Fetched {
        data class Done(val archive: ArchiveIdentity) : Fetched

        data class Failed(val problem: LibraryProblem) : Fetched

        data object Stopped : Fetched
    }

    private suspend fun fetch(
        run: InstallRun,
        folder: DownloadFolder,
        submission: SubmissionRef,
        file: RemoteFile,
        retention: DownloadRetention,
        pinned: MutableList<ArchiveIdentity>,
        last: Boolean,
        progress: (DownloadProgress) -> DownloadProgress,
    ): Fetched {
        val published = MutableStateFlow<ArchiveIdentity?>(null)
        var unregistered: LibraryProblem? = null
        var registered: ArchiveIdentity? = null
        keepUnfinished(run, folder, file.id)
        val downloaded = try {
            slots.withPermit {
                if (!advance(run, OperationStage.Downloading(progress(DownloadProgress(0, file.sizeBytes))))) return Fetched.Stopped
                val result = attempt {
                    ports.downloader.download(folder, submission, file, onPublished = { published.value = it }) { advance(run, OperationStage.Downloading(progress(it))) }
                }
                ((result as? Attempt.Done)?.value as? DownloadResult.Done)?.let { published.compareAndSet(null, it.archive) }
                result
            }
        } finally {
            published.value?.let { archive ->
                withContext(NonCancellable) {
                    if (last) downloaded(run)
                    unregistered = register(OwnedDownload(file.identity(submission), archive, retention))
                    if (unregistered == null) {
                        registered = archive
                        pinned += archive
                    }
                }
            }
        }
        return when (downloaded) {
            is Attempt.Failed -> Fetched.Failed(LibraryProblem.Unexpected(downloaded.reason))
            is Attempt.Done -> when (val result = downloaded.value) {
                is DownloadResult.Failed -> Fetched.Failed(LibraryProblem.Download(result.problem))
                is DownloadResult.Done -> registered?.let { Fetched.Done(it) } ?: Fetched.Failed(unregistered ?: LibraryProblem.Unexpected("the download was not registered"))
            }
        }
    }

    private suspend fun runLocalInstall(run: InstallRun, file: LocalFile): OperationStage.Failed? {
        val root = (state.value.game as? GameStatus.Ready)?.root ?: return OperationStage.Failed(LibraryProblem.GameNotFound)
        val snapshot = when (val imported = ports.localImport.snapshot(file)) {
            is LocalImportResult.Refused -> return OperationStage.Failed(LibraryProblem.LocalInputRefused(imported.reason))
            is LocalImportResult.Ready -> imported.snapshot
        }
        try {
            val title = file.name.substringBeforeLast('.')
            if (!advance(run, OperationStage.Unpacking, title)) return null
            val unpacked = unpack(snapshot.archive, snapshot.originalName) { return OperationStage.Failed(it) }
            try {
                val grouping = groupVpkSets(unpacked.vpks, pairStrays = true)
                if (grouping.sets.isEmpty()) return OperationStage.Failed(LibraryProblem.NoCompleteSet(grouping.rejected))
                installedFrom(root, snapshot.archive)?.takeIf { (_, keys) -> grouping.sets.all { it.key in keys } }?.let { (record) ->
                    return OperationStage.Failed(LibraryProblem.SameFileInstalled(record.title, record.installId))
                }
                val targets = run.asked.profiles
                val options = setOptions(grouping.sets)
                val preselected = run.asked.localKeys.filter { key -> options.any { it.key == key } }.ifEmpty { preselect(options, emptyList()) }
                val answer = if (grouping.sets.size == 1 && grouping.rejected.isEmpty()) ChoiceAnswer.Sets(listOf(grouping.sets.single().key), targets)
                    else ask(run) { OperationStage.ChoosingSet(it, snapshot.originalName, snapshot.archive.sizeBytes, options, targets, preselected, grouping.rejected, unpacked.notes) } as? ChoiceAnswer.Sets ?: return null
                val profiles = answer.profiles.toSet()
                val sets = answer.keys.map { key -> grouping.sets.first { it.key == key } }
                run.asked = run.asked.copy(profiles = profiles, localKeys = answer.keys)
                val source = InstalledSource.Local(run.asked.modId.removePrefix("local-"), snapshot.originalName)
                val imported = SetOrigin.Imported(FileImage(snapshot.archive.sizeBytes, snapshot.archive.sha256))
                val plan = sets.map { set -> SetPlan.Staged(source, originalName = null, variant = null, imported, version = null, set) }
                if (!askSwap(run, root, plan, profiles)) return null
                return settle(run, root, title, plan, profiles)
            } finally {
                withContext(NonCancellable) { ports.installer.discard(unpacked.staging) }
            }
        } finally {
            withContext(NonCancellable) { ports.localImport.release(snapshot) }
        }
    }

    private fun installedFrom(root: GameRoot, archive: ArchiveIdentity): Pair<ManifestRecord, Set<String>>? {
        val origin = SetOrigin.Imported(FileImage(archive.sizeBytes, archive.sha256))
        return ledger.manifest.records.filter { it.gameRoot == root }.firstNotNullOfOrNull { record ->
            record.sets.filter { it.origin == origin }.map { it.componentKey }.toSet().takeIf { it.isNotEmpty() }?.let { record to it }
        }
    }

    private suspend fun register(download: OwnedDownload): LibraryProblem? = underLock {
        val problem = reloadLocked()?.let { LibraryProblem.Interrupted(it) } ?: ledger.registerDownload(download).problem
        if (problem == null) ledger.pin(download.archive.path)
        publishDownloads()
        problem
    }

    private suspend fun settle(
        run: InstallRun, target: GameRoot, title: String, plan: List<SetPlan>, profiles: Set<ProfileId>,
        declined: Map<Long, Set<String>> = run.asked.replacement?.declined.orEmpty(),
    ): OperationStage.Failed? {
        if (!advance(run, OperationStage.Installing)) return null
        var placedAgain = false
        while (true) {
            val wait = when (val result = commit(target, run.asked, title, plan, profiles, declined)) {
                Committed.Done -> {
                    if (run.asked.reinstall && failure != null) wake.trySend(Unit)
                    return null
                }
                is Committed.Failed -> return result.stage
                Committed.Fenced -> placedAgain || ledger.manifest.pending != null
                Committed.WaitForGame -> true
            }
            placedAgain = true
            if (wait) {
                if (!advance(run, OperationStage.WaitingForGame(canCancel = cancellationBlock(run.asked.modId) == null))) return null
                state.first { it.filesFree }
                if (!advance(run, OperationStage.Installing)) return null
                placedAgain = false
            }
        }
    }

    private suspend fun askSwap(run: InstallRun, target: GameRoot, plan: List<SetPlan>, profiles: Set<ProfileId>): Boolean {
        if (run.asked.replacement != null || run.asked.swap != null) return true
        val active = state.value.shownProfiles?.activeProfile?.takeIf { it.id in profiles } ?: return true
        val cache = resourceIndexes ?: return true
        val ready = state.value.library as? LibraryStatus.Ready ?: return true
        val records = ready.records.filter { it.gameRoot == target }
        val loading = (onListing(target) as? OnListing.Listed)?.entries ?: return true
        val unreadable = ready.damaged.keys + ready.unchecked
        val mounts = profileMounts(records, active, loading, unreadable)
        val files = mounts.mapNotNull { mount ->
            val by = mount.by as? Mounted.Managed ?: return@mapNotNull null
            val record = records.first { it.installId == by.installId }
            if (record.installId in unreadable) null else by to cache.read(record, record.filesOf(record.set(by.set)).first())
        }.toMap<Mounted, ResourceIndex>()
        val others = modelRivals(plan.filterIsInstance<SetPlan.Staged>().map { it.vpks.members.first().vpk.resources }, mounts, files)
        if (others.isEmpty()) return true
        val off = when (ask(run) { OperationStage.ChoosingSwap(it, others.toList()) }) {
            ChoiceAnswer.Swap -> true
            ChoiceAnswer.KeepBoth -> false
            else -> return false
        }
        run.asked = run.asked.copy(swap = Swap(active.id, others, off))
        return true
    }

    private fun cancellationBlock(modId: String): String? = ledger.uncertainty ?: placementWaits(modId)

    private fun placementWaits(modId: String): String? {
        val placed = ledger.manifest.pending as? PendingChange.Install ?: return null
        if (placed.record.modId != modId) return null
        return when (placed.step) {
            InstallStep.Staging, is InstallStep.RollingBack -> null
            InstallStep.Publishing, InstallStep.Releasing -> PlacementWaits
        }
    }

    private suspend fun settleEarlier(modId: String): String? {
        if (placementWaits(modId) == null) return null
        return underLock {
            val waiting = recoverLocked()
            placementWaits(modId)?.let { waiting ?: it }
        }
    }

    private suspend fun discardLocked() {
        if (state.value.library !is LibraryStatus.Ready || recoverLocked() != null) return
        for ((download, installedInto) in ledger.discards()) {
            val result = ledger.removeArchive(download, installedInto)
            publishRecords(interrupted = (result as? ChangeOutcome.Refused)?.interrupted)
            if (result is ChangeOutcome.Refused) return
        }
    }

    private suspend fun commit(target: GameRoot, asked: Asked, title: String, plan: List<SetPlan>, profiles: Set<ProfileId>, declined: Map<Long, Set<String>>): Committed {
        val (outcome, touched) = underLock<Pair<Committed, Boolean>> {
            val library = state.value.library
            if (library !is LibraryStatus.Ready) return@underLock Committed.Failed(OperationStage.Failed(LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason))) to false
            saveAdmittedLocked()
            recoverLocked()?.let { return@underLock waitingOr(LibraryProblem.Interrupted(it)) to false }
            if ((state.value.game as? GameStatus.Ready)?.root != target || attempt { locator.check(target.path) } != Attempt.Done(FolderCheck.Valid(target))) {
                return@underLock Committed.Failed(OperationStage.Failed(LibraryProblem.GameNotFound)) to false
            }
            val closed = processProblem() == null
            asked.replacement?.let { expected ->
                val current = ledger.manifest.records.firstOrNull { it.installId == expected.installId }
                if (current == null || current.gameRoot != expected.gameRoot || current.provenance != expected.provenance) {
                    val changed = if (asked.update != null) LibraryProblem.UpdateChanged else LibraryProblem.VersionChanged
                    return@underLock Committed.Failed(OperationStage.Failed(changed)) to false
                }
                if (ledger.manifest.records.any { it.gameRoot == target && it.modId == asked.modId && it.installId != current.installId }) return@underLock Committed.Failed(OperationStage.Failed(LibraryProblem.AlreadyInstalled)) to false
                if (current.enabled && !closed) return@underLock Committed.WaitForGame to false
                when (val room = roomFor(current, plan.size, closed)) {
                    Room.Ready -> Unit
                    Room.None -> {
                        val on = ledger.manifest.records.filter { it.gameRoot == target && it.enabled && it.installId != current.installId }.sumOf { it.sets.size }
                        return@underLock Committed.Failed(OperationStage.Failed(LibraryProblem.NoRoomInOrder(on + plan.size))) to false
                    }
                    Room.WaitForGame -> return@underLock Committed.WaitForGame to false
                    is Room.Unknown -> return@underLock waitingOr(room.problem) to true
                }
                val spaced = ledger.manifest.records.single { it.installId == current.installId }
                val result = parked(target, !current.enabled) { ledger.replace(spaced, title, plan, reinstalling = asked.reinstall, declined = declined, aside = asideName(current.title)) }
                publishRecords((result as? InstallOutcome.Refused)?.interrupted, setOf(current.installId))
                return@underLock when (result) {
                    is InstallOutcome.Installed -> Committed.Done to true
                    is InstallOutcome.Refused -> waitingOr(result.problem, result.leftInPlace, plan.nameOf(result.source, current)) to true
                }
            }
            val members = profiles.filterTo(HashSet()) { id -> ledger.manifest.chosen().profiles.any { it.id == id } }
            val held = ledger.manifest.records.filter { it.gameRoot == target && it.modId == asked.modId }
            if (held.any { record -> record.sets.map { it.source to it.componentKey } == plan.map { it.source to it.componentKey } }) return@underLock Committed.Done to true
            if (held.isNotEmpty()) return@underLock Committed.Failed(OperationStage.Failed(LibraryProblem.AlreadyInstalled)) to true
            val kept = asked.swap?.takeIf { !it.off }?.rivals.orEmpty()
            val installed = parked(target, off = true) { ledger.install(target, title, plan.filterIsInstance<SetPlan.Staged>(), members, declined, kept) }
            val committed = when (installed) {
                is InstallOutcome.Installed -> Committed.Done.also {
                    asked.swap?.takeIf { it.off && it.profile in members }?.let { tell(QueuedChoice(membership(it.profile, it.rivals, false, undoable = true), awaited = false)) }
                }
                is InstallOutcome.Refused -> waitingOr(installed.problem, installed.leftInPlace, plan.nameOf(installed.source))
            }
            publishRecords(interrupted = (installed as? InstallOutcome.Refused)?.interrupted, recheck = setOfNotNull((installed as? InstallOutcome.Installed)?.record?.installId))
            committed to true
        }
        if (touched) {
            if (ledger.manifest.savedChoices != null) wake.trySend(Unit)
            refreshLoading(target)
        }
        return outcome
    }

    private fun asideName(title: String): String {
        val name = title.replace(UnsafeInFolderNames, " ").take(MaxAsideTitle).trim('.', ' ')
        val today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(clock.now().toEpochMilliseconds()), ZoneId.systemDefault())
        return "${name.ifEmpty { "mod" }} $today"
    }

    private sealed interface Room {
        data object Ready : Room

        data object None : Room

        data object WaitForGame : Room

        data class Unknown(val problem: LibraryProblem) : Room
    }

    private suspend fun roomFor(record: ManifestRecord, count: Int, closed: Boolean): Room {
        if (!record.enabled) return Room.Ready
        val held = ledger.manifest.records.filter { it.gameRoot == record.gameRoot }
        val loading = when (val listed = onListing(record.gameRoot)) {
            is OnListing.Unreadable -> return Room.Unknown(LibraryProblem.AddonsUnreadable(listed.reason))
            is OnListing.Listed -> listed.entries
        }
        val on = held.filter { it.enabled }.sortedBy { it.numbers.first() }
        val stuck = stuck() - record.installId
        val wanted = placeAround(on, emptyList(), foreignNumbers(loading, held)) { mod ->
            val width = if (mod.installId == record.installId) maxOf(mod.sets.size, count) else mod.sets.size
            Block(mod.sets.map(mod::numberOf), mod.sets.map { it.files.size }, width = width, stuck = mod.installId in stuck, fixed = mod.edited && mod.installId != record.installId)
        } ?: return Room.None
        val moves = movesBetween(on, wanted)
        if (moves.isEmpty()) return Room.Ready
        if (!closed) return Room.WaitForGame
        val result = changing { ledger.arrange(moves, ledger.manifest.profiles) }
        publishRecords(ledger.uncertainty ?: (result as? ChangeOutcome.Refused)?.interrupted, moves.map { it.source.installId }.toSet())
        return if (result is ChangeOutcome.Refused) Room.Unknown(result.problem) else Room.Ready
    }

    private suspend inline fun unpack(archive: ArchiveIdentity, originalName: String = archive.fileName, refused: (LibraryProblem) -> Nothing): UnpackResult.Unpacked =
        when (val result = attempt { ports.installer.unpack(archive, ports.limits, originalName) }.orElse { UnpackResult.Failed(it) }) {
            is UnpackResult.Unpacked -> result
            is UnpackResult.Rejected -> refused(LibraryProblem.ArchiveRefused(result.rejection))
            is UnpackResult.Failed -> refused(LibraryProblem.ExtractionFailed(result.reason))
        }

    private suspend fun onListing(root: GameRoot): OnListing = ledger.onListing(root).also { listing ->
        mutableState.update { state ->
            val ready = state.library as? LibraryStatus.Ready
            if (ready == null || (state.game as? GameStatus.Ready)?.root != root) state else state.copy(library = ready.copy(loading = (listing as? OnListing.Listed)?.entries))
        }
    }

    private fun stuck(): Set<String> = (state.value.library as? LibraryStatus.Ready)?.let { it.damaged.keys + it.unchecked }.orEmpty()

    private suspend fun <T> parked(root: GameRoot, off: Boolean, place: suspend () -> T): T {
        if (!off) return place()
        parking = root
        try {
            return place()
        } finally {
            parking = null
        }
    }

    private suspend fun waitingOr(problem: LibraryProblem, leftInPlace: List<String> = emptyList(), file: String? = null): Committed {
        logLeftInPlace(leftInPlace)
        return when {
            problem == LibraryProblem.GameRunning || problem == LibraryProblem.CustomGameRunning || problem is LibraryProblem.ProcessesUnknown -> Committed.Fenced
            problem is LibraryProblem.Interrupted && ledger.uncertainty == null && processProblem() != null -> Committed.Fenced
            else -> Committed.Failed(OperationStage.Failed(problem, file))
        }
    }

    private fun logLeftInPlace(files: List<String>, conflict: String? = null) {
        if (files.isNotEmpty()) ports.log("Left in place, as Hideout couldn't prove they are its own unchanged copies: ${files.joinToString()}")
        if (conflict != null) ports.log("Left gameinfo.gi in place: $conflict")
    }

    private suspend fun ask(run: InstallRun, question: (ChoiceId) -> OperationStage): ChoiceAnswer {
        val answer = CompletableDeferred<ChoiceAnswer>()
        val id = asking.withLock {
            ChoiceId(++lastChoice).also { id ->
                questions.update { it + (id to answer) }
                if (!advance(run, question(id))) answer.complete(ChoiceAnswer.Cancel)
            }
        }
        return try {
            answer.await()
        } finally {
            questions.update { it - id }
        }
    }

    private fun advance(run: InstallRun, next: OperationStage, title: String? = null): Boolean {
        val modId = run.asked.modId
        var moved = false
        mutableState.update { state ->
            val operation = state.operations[modId]
            moved = runs.value[modId] === run && operation != null && operation.stage != OperationStage.Cancelling && operation.stage !is OperationStage.Failed
            if (moved) state.copy(operations = state.operations + (modId to operation!!.copy(stage = next, title = title ?: operation.title))) else state
        }
        return moved
    }

    /** The install list as the library last read or saved it: what an import's preview places against, as the import will. */
    internal val manifest: LibraryManifest get() = ledger.manifest

    /** A new [Matching] of mods to GameBanana, asking under the limit installs ask under, whose answers last as long as it does. */
    internal fun matching() = Matching(ports.gameBanana, ports.archives, listings)

    /**
     * Carries out [takeover], the only writer of the game while an import runs. [takeover] describes the game as it was
     * read, so after a wait it is read again before this is asked again. [configuring] hears when the mods are committed.
     */
    internal suspend fun takeOver(takeover: Takeover, configuring: () -> Unit = {}): TakeoverOutcome {
        librarySetup.join()
        importWait(takeover.manager)?.let { return TakeoverOutcome.Waiting(it) }
        val staged = mutableListOf<List<VpkSet<StagedVpk>>>()
        val stagings = mutableListOf<StagingId>()
        val snapshots = mutableListOf<LocalArchiveSnapshot>()
        try {
            for (copy in takeover.copies) {
                val vpks = mutableListOf<StagedVpk>()
                for (file in copy.files) {
                    val snapshot = when (val taken = ports.localImport.snapshot(file)) {
                        is LocalImportResult.Refused -> return TakeoverOutcome.Refused(ImportProblem.Library(LibraryProblem.LocalInputRefused(taken.reason)))
                        is LocalImportResult.Ready -> taken.snapshot.also { snapshots += it }
                    }
                    val unpacked = unpack(snapshot.archive, snapshot.originalName) { return TakeoverOutcome.Refused(ImportProblem.Library(it)) }
                    stagings += unpacked.staging
                    vpks += unpacked.vpks
                }
                val grouping = groupVpkSets(vpks)
                if (grouping.sets.isEmpty() || grouping.rejected.isNotEmpty()) return TakeoverOutcome.Refused(ImportProblem.Library(LibraryProblem.NoCompleteSet(grouping.rejected)))
                staged += grouping.sets
            }
            val outcome = underLock { takeOverLocked(takeover, staged, configuring) }
            if (ledger.manifest.savedChoices != null) wake.trySend(Unit)
            refreshLoading(takeover.root)
            return outcome
        } finally {
            withContext(NonCancellable) {
                stagings.forEach { ports.installer.discard(it) }
                snapshots.forEach { ports.localImport.release(it) }
            }
        }
    }

    private suspend fun takeOverLocked(takeover: Takeover, staged: List<List<VpkSet<StagedVpk>>>, configuring: () -> Unit): TakeoverOutcome {
        importWait(takeover.manager)?.let { return TakeoverOutcome.Waiting(it) }
        val library = state.value.library
        if (library !is LibraryStatus.Ready) return TakeoverOutcome.Refused(ImportProblem.LibraryBusy((library as? LibraryStatus.Blocked)?.reason))
        val root = takeover.root
        if ((state.value.game as? GameStatus.Ready)?.root != root || attempt { locator.check(root.path) } != Attempt.Done(FolderCheck.Valid(root))) return TakeoverOutcome.Refused(ImportProblem.GameMissing)
        saveAdmittedLocked()
        recoverLocked()?.let { return interruptedOr(LibraryProblem.Interrupted(it)) }
        val copied = mutableListOf<ManifestRecord>()
        for ((copy, sets) in takeover.copies.zip(staged)) {
            val (sources, title) = sourcesAndTitle(copy.matched, sets.size, copy.title, copy.fileName, ledger.manifest.records.filter { it.gameRoot == root }, ids)
            val plan = sets.mapIndexed { index, set -> SetPlan.Staged(sources[index], null, null, SetOrigin.Imported(set.members.first().vpk.image), null, set) }
            val members = if (copy.enabled) setOf(ledger.manifest.chosen().active) else emptySet()
            when (val installed = parked(root, off = true) { ledger.install(root, title, plan, members, unasked = sources.first() is InstalledSource.Local) }) {
                is InstallOutcome.Installed -> copied += installed.record
                is InstallOutcome.Refused -> return interruptedOr(installed.problem).also { publishRecords(installed.interrupted) }
            }
        }
        val addons = when (val listed = onListing(root)) {
            is OnListing.Unreadable -> return TakeoverOutcome.Refused(ImportProblem.GameUnreadable(listed.reason))
            is OnListing.Listed -> listed.entries.filter { it.folder == SetFolder.Addons }.map { it.entry.name }
        }
        val placed = placeTakeover(takeover, ledger.manifest, addons, copied.map { it.installId }, ids)
        val plan = placed.plan
        if (plan.adopted.isNotEmpty() || plan.book != ledger.manifest.profiles || plan.target != ledger.manifest.chosen()) {
            val outcome = changing { ledger.adopt(plan) }
            publishRecords(ledger.uncertainty ?: (outcome as? ChangeOutcome.Refused)?.interrupted)
            if (outcome is ChangeOutcome.Refused) return interruptedOr(outcome.problem)
        }
        configuring()
        if (takeover.unmount.isNotEmpty()) ledger.writeBlock(root, ledger.linesFor(root), takeover.unmount)?.let { return interruptedOr(it) }
        var commands = 0
        val autoexec = takeover.autoexec
        if (autoexec != null) {
            val written = writeAutoexecLocked(root) { document ->
                commands = when (autoexec) {
                    is AutoexecTakeover.Sections -> document.foreignLines(autoexec.sections).commands.size
                    is AutoexecTakeover.Lines -> autoexec.commands.size
                }
                document.takeOver(autoexec) ?: document
            }
            (written as? AutoexecWrite.Refused)?.let { return interruptedOr(it.problem) }
        }
        val owned = ledger.manifest.records.filter { it.gameRoot == root }.flatMap { record -> record.files.map { it.citadelPath(record) } }.toSet()
        for (kept in takeover.duplicates.filter { it.path !in owned } + takeover.manifests) {
            when (val removed = attempt { ports.gameFiles.removeExact(CitadelFile(root, kept.path), kept.image) }) {
                is Attempt.Failed -> return interruptedOr(LibraryProblem.PlacementFailed(removed.reason))
                is Attempt.Done -> (removed.value as? Removal.Failed)?.let { return interruptedOr(LibraryProblem.PlacementFailed(it.reason)) }
            }
        }
        publishRecords(interrupted = null)
        val adopted = SetOrigin.Adopted(takeover.migration)
        val records = ledger.manifest.records.filter { record -> record.gameRoot == root && record.sets.all { it.origin == adopted } } + copied
        val on = ledger.manifest.chosen().activeProfile.enabled
        return TakeoverOutcome.Done(on = records.count { it.installId in on }, off = records.count { it.installId !in on }, profiles = placed.profiles, commands = commands)
    }

    private suspend fun importWait(manager: Manager?): ImportWait? {
        processProblem()
        return gateOf(state.value, manager)
    }

    private suspend fun interruptedOr(problem: LibraryProblem): TakeoverOutcome =
        if (processProblem() != null) TakeoverOutcome.Waiting(gateOf(state.value, null) ?: ImportWait.GameOpen) else TakeoverOutcome.Refused(ImportProblem.Library(problem))

    private fun readiness(state: LibraryState, submission: SubmissionRef, replacement: Boolean): LibraryProblem? = when {
        state.library is LibraryStatus.Blocked -> LibraryProblem.LibraryUnavailable(state.library.reason)
        state.library !is LibraryStatus.Ready -> LibraryProblem.LibraryUnavailable(null)
        state.game !is GameStatus.Ready -> LibraryProblem.GameNotFound
        !replacement && state.library.records.any { it.gameRoot == state.game.root && it.submission == submission } -> LibraryProblem.AlreadyInstalled
        else -> null
    }

    private suspend fun <T> underLock(block: suspend () -> T): T = libraryMutex.withLock { withContext(NonCancellable) { block() } }

    private suspend fun writableLocked(): Writable {
        val library = state.value.library
        if (library !is LibraryStatus.Ready) return Writable.Refused(LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason))
        val root = (state.value.game as? GameStatus.Ready)?.root ?: return Writable.Refused(LibraryProblem.GameNotFound)
        processProblem()?.let { return Writable.Refused(it) }
        recoverLocked()?.let { return Writable.Refused(LibraryProblem.Interrupted(it)) }
        return Writable.Ok(root)
    }

    private suspend fun processProblem(): LibraryProblem? {
        val client = clientProblem()
        val hold = state.value.sessionHold ?: return client
        return if (hold.step == SessionStep.Releasing) client else LibraryProblem.CustomGameRunning
    }

    private suspend fun clientProblem(): LibraryProblem? {
        val (problem, managers) = when (val scan = attempt { ports.processes.scan() }) {
            is Attempt.Failed -> LibraryProblem.ProcessesUnknown(scan.reason) to ManagerActivity.Unknown
            is Attempt.Done -> when (val result = scan.value) {
                is ProcessScan.Seen -> (if (result.game) LibraryProblem.GameRunning else null) to ManagerActivity.Seen(result.managers)
                is ProcessScan.Unknown -> LibraryProblem.ProcessesUnknown(result.reason) to ManagerActivity.Unknown
            }
        }
        val seen = when (problem) {
            null -> GameActivity.Closed
            LibraryProblem.GameRunning -> GameActivity.Running
            else -> GameActivity.Unknown
        }
        var closed = false
        mutableState.update { state ->
            closed = seen == GameActivity.Closed && state.deadlock != GameActivity.Closed
            val withManagers = if (state.managers == managers) state else state.copy(managers = managers)
            if (withManagers.deadlock == seen) withManagers else withManagers.copy(deadlock = seen).withSync()
        }
        if (closed) filesFreed()
        return problem
    }

    private suspend fun watchGameFolder() {
        val game = state.value.game
        val root = (game as? GameStatus.Ready)?.root ?: lostGame ?: return
        val there = (attempt { locator.check(root.path) } as? Attempt.Done)?.value == FolderCheck.Valid(root)
        if (there == (game is GameStatus.Ready)) return
        val found = mutex.withLock { resolve(preferSteam = false) }
        lostGame = if (found is GameStatus.Ready) null else root
        if (found is GameStatus.Ready) wake.trySend(Unit)
    }

    private fun filesFreed() {
        if (automatic) repairFailures.value = emptyMap()
        wake.trySend(Unit)
        compatWake.trySend(Unit)
    }

    private suspend fun recoverLocked(): String? {
        if (state.value.library !is LibraryStatus.Ready) return null
        reloadLocked()?.let { return it }
        val pending = ledger.manifest.pending ?: return null
        var fenced: String? = null
        for (root in pending.gameRoots) {
            fenced = when {
                pending !is PendingChange.ConfigEdit -> untouchable(root, gameMayRun = pending.whileGameRuns)
                pending.target.whileGameRuns -> null
                else -> processProblem()?.let { "the game may be running" }
            }
            if (fenced != null) break
        }
        val parkedIn = pending.gameRoots.singleOrNull()?.takeIf { pending.whileGameRuns }
        val reason = fenced ?: when (val recovered = if (parkedIn == null) ledger.recover() else parked(parkedIn, off = true) { ledger.recover() }) {
            is Recovery.Waiting -> recovered.reason
            Recovery.Settled -> null
        }
        publishRecords(interrupted = reason, recheck = pending.installIds)
        return reason
    }

    private suspend fun reloadLocked(): String? {
        val doubt = ledger.uncertainty ?: return null
        if (ledger.reload() is ManifestLoad.Unreadable) return doubt.also { publishRecords(interrupted = it) }
        val reconciled = reconcile().withInterruption(SettlingInterruption.takeIf { ledger.manifest.pending != null })
        mutableState.update { it.copy(library = reconciled).confirmed(renew = true) }
        return null
    }

    private suspend fun publishRecords(
        interrupted: String?,
        recheck: Set<String> = emptySet(),
        changes: (Map<String, SetChange>) -> Map<String, SetChange> = { it },
    ) {
        val ready = state.value.library as? LibraryStatus.Ready
        val checked = attempt { checkLocked(ledger.manifest.records.filter { it.installId in recheck }) }.orElse { recheck.associateWith { Checked(FilesCheck.Unknown, stored = false) } }
        val records = ledger.manifest.records
        val ids = records.map { it.installId }.toSet()
        (state.value.game as? GameStatus.Ready)?.let { onListing(it.root) }
        mutableState.update { state ->
            val current = state.library as? LibraryStatus.Ready
            val library = if (current == null || ready == null) state.library else current.copy(
                records = records,
                damaged = (current.damaged - recheck + checked.mapNotNull { (id, found) -> found.check.damage?.let { id to it } }).filterKeys { it in ids },
                unchecked = (current.unchecked - recheck + checked.filterValues { it.check == FilesCheck.Unknown }.keys) intersect ids,
                stored = (current.stored - recheck + checked.filterValues { it.stored }.keys) intersect ids,
                interrupted = interrupted,
                downloads = ledger.manifest.downloads,
                clearable = ledger.clearable(),
            )
            state.copy(library = library, changes = changes(state.changes)).confirmed()
        }
        publishCompat()
        compatWake.trySend(Unit)
    }

    private fun publishDownloads() {
        mutableState.update { state ->
            val ready = state.library as? LibraryStatus.Ready ?: return@update state
            state.copy(library = ready.copy(downloads = ledger.manifest.downloads, clearable = ledger.clearable()))
        }
    }

    private suspend fun watchModLoading() {
        state.map { (it.game as? GameStatus.Ready)?.root }.distinctUntilChanged().collectLatest { root ->
            if (root == null) {
                mutableState.update { it.copy(loading = ModLoading.Unknown) }
                return@collectLatest
            }
            val imported = importedManagers.map { it[root] }.distinctUntilChanged()
            val activity = state.map { it.filesFree to it.deadlock }.distinctUntilChanged()
            merge(configChanges(root, ConfigTarget.GameInfo.Path), ticks(), imported.map {}, activity.map {}).collect {
                refreshLoading(root)
                reclaim(root)
            }
        }
    }

    /**
     * From now on [root]'s `gameinfo.gi` is Hideout's against [manager], whose mods an import brought: whenever the
     * file is read, before Play, and when Deadlock closes, [manager]'s mounts come out again as the import took them.
     */
    internal fun importedFrom(root: GameRoot, manager: Manager) {
        importedManagers.update { it + (root to it[root].orEmpty() + manager) }
    }

    private suspend fun reclaim(root: GameRoot) {
        val managers = importedManagers.value[root].orEmpty()
        val lines = ledger.linesFor(root)
        if (managers.isEmpty() || !lines.mods || !state.value.filesFree) return
        val config = (attempt { ports.gameConfig.read(root) } as? Attempt.Done)?.value as? ConfigRead.Present ?: return
        if (GameInfoPatch.takeOver(config.bytes, lines, ledger.manifest.records.overflowIn(root), managers.flatMap(ForeignRoot::keptBy).toSet()) !is GameInfoEdit.Patched) return
        val problem = underLock {
            when (val writable = writableLocked()) {
                is Writable.Refused -> null
                is Writable.Ok -> if (writable.root == root) reclaimLocked(root, ledger.linesFor(root)) else null
            }
        }
        problem?.let { ports.log("Couldn't put gameinfo.gi back after another manager changed it: ${it.reason()}") }
        refreshLoading(root)
    }

    private suspend fun reclaimLocked(root: GameRoot, lines: HideoutLines): LibraryProblem? {
        val managers = importedManagers.value[root].orEmpty()
        if (managers.isEmpty() || !lines.mods) return null
        val bytes = gameInfoBytes(root) { return it }
        val unmount = managers.flatMap(ForeignRoot::keptBy).toSet()
        if (GameInfoPatch.takeOver(bytes, lines, ledger.manifest.records.overflowIn(root), unmount) !is GameInfoEdit.Patched) return null
        ledger.writeBlock(root, lines, unmount)?.let { return it }
        GameInfoPatch.heldBy(bytes).firstOrNull { it in managers }?.let { putBack(RewrittenBy.AnotherProgram(it)) }
        return null
    }

    /**
     * What took Hideout's lines out of [bytes], [root]'s `gameinfo.gi`, while its mods are wanted there and Hideout did
     * not write the file as it is now: an update when the game's build changed since Hideout last wrote it, else another
     * program, named when its own lines are there or it alone is open. Null when the file is Hideout's or keeps its lines.
     */
    private suspend fun rewriteOf(root: GameRoot, bytes: ByteArray): RewrittenBy? {
        val receipt = ledger.manifest.receipt(root)?.takeIf { it.lines.mods && it.written != null } ?: return null
        if (receipt.written == Sha256.image(bytes)) return null
        if (GameInfoPatch.withBlock(bytes, receipt.lines, ledger.manifest.records.overflowIn(root)) !is GameInfoEdit.Patched) return null
        GameInfoPatch.heldBy(bytes).firstOrNull()?.let { return RewrittenBy.AnotherProgram(it) }
        if (receipt.game != null && gameIdentity(root).let { it != null && it != receipt.game }) return RewrittenBy.GameUpdate
        return RewrittenBy.AnotherProgram((state.value.managers as? ManagerActivity.Seen)?.open?.singleOrNull())
    }

    /** Hideout put back the lines [by] took out, finishing the rewrite that waited for Deadlock to close if there was one. */
    private fun putBack(by: RewrittenBy) = mutableState.update { state ->
        val waiting = state.rewrite?.takeIf { it.outcome == RewriteOutcome.WaitingForGame }
        state.copy(rewrite = waiting?.copy(outcome = RewriteOutcome.PutBack) ?: GameInfoRewrite(by, RewriteOutcome.PutBack))
    }

    /**
     * Emits at once, then whenever [path], a config file under `game/citadel`, may have changed on disk, once a burst of
     * changes has settled for [WatchTiming.debounce]. A failed watch starts again a period later and emits at once again,
     * so a change made while it was down is read then.
     */
    @OptIn(FlowPreview::class)
    internal fun configChanges(root: GameRoot, path: CitadelPath): Flow<Unit> = flow {
        emit(Unit)
        emitAll(ports.gameConfig.changes(root, path).debounce(watch.debounce))
    }.retryWhen { cause, _ ->
        val retry = cause is Exception && cause !is CancellationException
        if (retry) delay(watch.period)
        retry
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            delay(watch.period)
            emit(Unit)
        }
    }

    private suspend fun refreshLoading(root: GameRoot) = loadingMutex.withLock {
        librarySetup.join()
        val library = state.value.library as? LibraryStatus.Ready
        val intact = library?.records.orEmpty().filter {
            it.gameRoot == root && it.enabled && it.installId !in library?.damaged.orEmpty() && it.installId !in library?.unchecked.orEmpty()
        }
        val read = attempt { ports.gameConfig.read(root) }
        val loading = when (read) {
            is Attempt.Failed -> ModLoading.Unreadable(GameInfoProblem.Unreadable)
            is Attempt.Done -> when (val config = read.value) {
                ConfigRead.Missing -> ModLoading.Unreadable(GameInfoProblem.Missing)
                is ConfigRead.Unreadable -> ModLoading.Unreadable(GameInfoProblem.Unreadable)
                is ConfigRead.Present -> when (val check = GameInfoPatch.check(config.bytes)) {
                    is SearchPathsCheck.Problem -> ModLoading.Unreadable(check.problem)
                    is SearchPathsCheck.Read -> {
                        val unmounted = intact.count { it.numbers.first().folder.index - 1 > check.overflow }
                        check.coveredBy?.takeIf { unmounted == 0 }?.let { ModLoading.Loads(it) }
                            ?: ModLoading.Off(if (check.coveredBy == null) intact.size else unmounted, vanilla = !ledger.linesFor(root).mods)
                    }
                }
            }
        }
        val waits = (loading as? ModLoading.Off)?.alerts == true && state.value.deadlock == GameActivity.Running &&
            state.value.rewrite?.outcome != RewriteOutcome.WaitingForGame
        val waiting = ((read as? Attempt.Done)?.value as? ConfigRead.Present)?.takeIf { waits }?.let { rewriteOf(root, it.bytes) }
        mutableState.update { state ->
            if ((state.game as? GameStatus.Ready)?.root != root) state
            else state.copy(loading = loading, rewrite = waiting?.let { GameInfoRewrite(it, RewriteOutcome.WaitingForGame) } ?: state.rewrite)
        }
    }

    private suspend fun resolve(preferSteam: Boolean): GameStatus {
        val steam = locator.detect()
        val chosen = preferences.gameFolder()?.let { locator.check(it) }
        val status = when {
            preferSteam && steam.isNotEmpty() -> {
                if (chosen != null) preferences.setGameFolder(null)
                GameStatus.Ready(steam.first(), FoundVia.Steam)
            }
            chosen is FolderCheck.Valid -> GameStatus.Ready(chosen.root, if (chosen.root in steam) FoundVia.Steam else FoundVia.Chosen)
            steam.isNotEmpty() -> GameStatus.Ready(steam.first(), FoundVia.Steam)
            else -> GameStatus.Missing(chosen as FolderCheck.Invalid?)
        }
        publish(status)
        return status
    }

    private suspend fun publish(game: GameStatus) {
        mutableState.update { it.copy(game = game).projected() }
        underLock {
            (state.value.library as? LibraryStatus.Ready)?.let { publishRecords(it.interrupted, recheck = it.unchecked + it.damaged.keys) }
            publishBackups()
        }
        publishCompat()
        compatWake.trySend(Unit)
    }

    private suspend fun loadLibrary() {
        val status = when (val loaded = ledger.reload()) {
            ManifestLoad.Absent -> reconcile()
            is ManifestLoad.Loaded -> reconcile().withInterruption(SettlingInterruption.takeIf { loaded.manifest.pending != null })
            is ManifestLoad.Unreadable -> LibraryStatus.Blocked(loaded.reason)
        }
        mutableState.update { it.copy(library = status).confirmed(renew = true) }
        (state.value.game as? GameStatus.Ready)?.let { onListing(it.root) }
    }

    private suspend fun reconcile(): LibraryStatus = attempt {
        val checks = checkLocked(ledger.manifest.records)
        LibraryStatus.Ready(
            records = ledger.manifest.records,
            damaged = checks.mapNotNull { (id, checked) -> checked.check.damage?.let { id to it } }.toMap(),
            unchecked = checks.filterValues { it.check == FilesCheck.Unknown }.keys,
            stored = checks.filterValues { it.stored }.keys,
            downloads = ledger.manifest.downloads,
            clearable = ledger.clearable(),
        )
    }.orElse { LibraryStatus.Blocked("checking the game's files failed: $it") }

    private suspend fun checkLocked(records: List<ManifestRecord>, read: Boolean = false): Map<String, Checked> {
        val looked = records.associateWith { ledger.look(it, read = read) }
        val seen = looked.mapNotNull { (record, look) -> look.record.takeIf { it != record }?.let { record to it } }.toMap()
        val edited = ledger.manifest.edited
        if (ledger.noteEdits(seen) == SaveResult.Saved && ledger.manifest.edited != edited) wake.trySend(Unit)
        return looked.entries.associate { (record, look) ->
            val damaged = record.sets.filter { set -> record.filesOf(set).any { look.files[it]?.damage != null } }
            record.installId to Checked(look.check, look.check.damage != null && damaged.all { storedLocked(record, it) })
        }
    }

    private class Checked(val check: FilesCheck, val stored: Boolean)

    private suspend fun storedLocked(record: ManifestRecord, set: InstalledSet): Boolean =
        set.files.all { attempt { ports.backups.has(record.gameRoot, it.image) } == Attempt.Done(true) }

    private class CheckEntry(val key: CheckKey, val result: CheckResult) {
        val evidence: Set<FileEvidence> get() = (result as? CheckResult.Checked)?.check?.basis?.evidence.orEmpty()
    }

    private class RepairFailure(val reason: String, val record: ManifestRecord, val key: CheckKey?, val restoring: Boolean) {
        fun holdsFor(now: CheckKey?): Boolean = now != null && now.record == record && (key == null || key == now)
    }

    private class RepairEntry(val work: RepairWork, val record: ManifestRecord, val choice: RepairChoice) {
        val restoring: Boolean get() = choice == RepairChoice.Original

        val unsettled: Boolean get() = work !is RepairWork.WaitingForGame

        fun becomes(work: RepairWork, record: ManifestRecord = this.record) = RepairEntry(work, record, choice)
    }

    private sealed interface RepairPlacement {
        data object Placed : RepairPlacement

        data object Recheck : RepairPlacement

        data object Unchanged : RepairPlacement

        data class Waits(val reason: String) : RepairPlacement

        data class Failed(val reason: String) : RepairPlacement
    }

    private sealed interface Due {
        data object Later : Due

        data object Settled : Due

        data class Change(val choice: RepairChoice) : Due
    }

    private val checks = MutableStateFlow<Map<String, CheckEntry>>(emptyMap())

    private val setAside = MutableStateFlow<Map<String, CheckEntry>>(emptyMap())

    private val checking = MutableStateFlow<Set<String>>(emptySet())

    private val repairWork = MutableStateFlow<Map<String, RepairEntry>>(emptyMap())

    private val repairFailures = MutableStateFlow<Map<String, RepairFailure>>(emptyMap())

    @Volatile
    private var compatProblem: String? = null

    private val automatic: Boolean get() = preferences.autoRepair.value == true

    @Volatile
    private var reconfirm = false

    /**
     * Saves that [installIds] are to use repaired files, made from their retained originals; the files follow once
     * Deadlock is known closed. Saved while it runs too: it changes no file then. Nothing is repaired by choosing it
     * when no repair is available. Each one shows queued as soon as its choice is saved.
     */
    suspend fun useRepairedFiles(installIds: Set<String>) = chooseRepair(installIds, RepairChoice.Repaired)

    /** Saves that [installId] is to use its original files, put back if it was repaired, and never repaired on its own again. */
    suspend fun useOriginalFiles(installId: String) = chooseRepair(setOf(installId), RepairChoice.Original)

    /**
     * Asks GameBanana who each local mod in the game is, as Check for updates does, those an import brought and
     * GameBanana has not answered for first. The [unasked] ones are also found on their own, in the background, from
     * the moment the library is read and whenever an import brings more. Asked while it runs, it goes on with the run
     * under way.
     */
    fun identifyLocal(): Job = identifying?.takeIf { it.isActive } ?: scope.launch { identifyLock.withLock { identifyLocalMods(unaskedOnly = false) } }.also { identifying = it }

    private suspend fun identifyLocalMods(unaskedOnly: Boolean) {
        librarySetup.join()
        val root = (state.value.game as? GameStatus.Ready)?.root ?: return
        val matching = matching()
        val local = ledger.manifest.records.filter { it.gameRoot == root && it.sets.first().source is InstalledSource.Local }
        var answered = true
        for (record in local.filter { it.unasked } + if (unaskedOnly) emptyList() else local.filterNot { it.unasked }) {
            val own = record.sets.first().source as InstalledSource.Local
            val matched = matching.identify(own.submission, withoutFileName(record.title, own.fileName), record.sets.map { set -> set.files.map { it.image.sizeBytes } }) {
                record.sets.map { set -> record.filesOf(set).map { file -> (inventory.vpk(root, file.citadelPath(record)) as? VpkLook.Present)?.takeIf { it.image == file.image }?.print ?: return@identify null } }
            }
            if (matched == null) {
                answered = false
                break
            }
            val known = matched.takeIf { it.submission != null } ?: Matched.Unpinned(own.submission)
            if (!record.unasked && known == Matched.Unpinned(own.submission)) continue
            underLock {
                if (reloadLocked() != null) return@underLock
                val saved = parked(root, !record.enabled) { ledger.identify(record, known) }
                if (saved !is SaveResult.Refused) publishRecords(interrupted = (saved as? SaveResult.Unconfirmed)?.reason)
            }
        }
        mutableState.update { it.copy(findingLater = !answered && it.unasked.isNotEmpty()) }
    }

    /**
     * Checks the current game's mods again now, whatever earlier checks found, and lets failed repairs try again. What
     * the earlier checks found stays shown until each mod is checked again; no repair rests on it meanwhile.
     */
    fun checkCompatibility() {
        setAside.update { it + checks.value }
        checks.value = emptyMap()
        repairFailures.value = emptyMap()
        publishCompat()
        compatWake.trySend(Unit)
    }

    private suspend fun chooseRepair(installIds: Set<String>, choice: RepairChoice) {
        librarySetup.join()
        underLock {
            if (reloadLocked() != null) return@underLock
            for (id in installIds) {
                val saved = ledger.saveRepairChoice(id, choice)
                val record = ledger.manifest.records.firstOrNull { it.installId == id }
                repairFailures.update { failures ->
                    if (saved == SaveResult.Saved || record == null) failures - id
                    else failures + (id to RepairFailure(saved.problem?.reason() ?: "the choice was not saved", record, key = null, restoring = choice == RepairChoice.Original))
                }
                if (saved == SaveResult.Saved && record != null) accept(record, choice)
            }
            publishCompat()
        }
        compatWake.trySend(Unit)
    }

    private fun accept(record: ManifestRecord, choice: RepairChoice) {
        if (compatibility.unavailable != null) return
        val id = record.installId
        val view = viewOf(record)
        val due = !record.edited && when (choice) {
            RepairChoice.Original -> record.sets.any { it.derivation != null }
            RepairChoice.Repaired -> view.repairable.isNotEmpty() || view.check == null && view.failed == null
        }
        repairWork.update { work ->
            when {
                work[id]?.choice == choice -> work
                due -> work + (id to RepairEntry(RepairWork.Queued(restoring = choice == RepairChoice.Original), record, choice))
                else -> work - id
            }
        }
    }

    private suspend fun compatPass() {
        compatProblem = null
        val root = (state.value.game as? GameStatus.Ready)?.root
        val library = state.value.library as? LibraryStatus.Ready
        if (root == null || library == null || library.interrupted != null || ledger.uncertainty != null) {
            holdWork(waits = root != null && library?.interrupted != null && ledger.uncertainty == null)
            publishCompat()
            return
        }
        val keys = keysFor(root)
        checks.update { current -> current.filterKeys { it in keys } }
        setAside.update { current -> current.filterKeys { it in keys } }
        retireWork(keys)
        if (reconfirm) {
            reconfirm = false
            confirm(keys.keys, retryFailed = true)
        }
        for ((id, key) in keys) {
            if (checks.value[id]?.key == key) continue
            checking.update { it + id }
            publishCompat()
            val result = checkOne(key, state.value.library as? LibraryStatus.Ready ?: library)
            checks.update { it + (id to CheckEntry(key, result)) }
            setAside.update { it - id }
            repairWork.update { work -> if (work[id]?.let { it.work is RepairWork.Verifying && it.record == key.record } == true) work - id else work }
            repairFailures.update { failures -> if (failures[id]?.holdsFor(key) == false) failures - id else failures }
            plan(ledger.manifest.records.filter { it.installId == id }, keys)
            checking.update { it - id }
            publishCompat()
        }
        repairFailures.update { failures -> failures.filter { (id, failure) -> failure.holdsFor(keys[id]) } }
        converge(root, keys)
        collectOriginals()
        publishCompat()
    }

    private suspend fun checkOne(key: CheckKey, library: LibraryStatus.Ready): CheckResult {
        val id = key.record.installId
        if (id in library.damaged) return CheckResult.Failed("its files changed after Hideout placed them")
        if (id in library.unchecked) return CheckResult.Failed("its files could not be read")
        if (key.game == null) return CheckResult.Failed("the game's own files could not be read")
        return when (val result = attempt { compatibility.check(key.record, key.originals, key.mounted) }) {
            is Attempt.Done -> result.value
            is Attempt.Failed -> CheckResult.Failed(result.reason)
        }
    }

    private suspend fun confirm(ids: Collection<String>, retryFailed: Boolean): Boolean {
        val entries = ids.mapNotNull { checks.value[it] }
        val evidence = entries.flatMap { it.evidence }.toSet()
        val changed = if (evidence.isEmpty()) emptySet() else when (val read = attempt { compatibility.changed(evidence) }) {
            is Attempt.Done -> read.value
            is Attempt.Failed -> evidence
        }
        val stale = entries.filter { entry -> (retryFailed && entry.result is CheckResult.Failed) || entry.evidence.any { it in changed } }
        if (stale.isEmpty()) return true
        checks.update { current -> current.filterValues { entry -> stale.none { it === entry } } }
        return false
    }

    private fun forget(installId: String) {
        checks.update { it - installId }
        compatWake.trySend(Unit)
    }

    private suspend fun gameIdentity(root: GameRoot): String? = (attempt { compatibility.game(root) } as? Attempt.Done)?.value

    private suspend fun keysFor(root: GameRoot): Map<String, CheckKey> {
        val manifest = ledger.manifest
        val records = manifest.records.filter { it.gameRoot == root }
        if (records.isEmpty()) return emptyMap()
        val game = gameIdentity(root)
        val enabled = records.filter { it.enabled }
        val owned = enabled.flatMap { record -> record.files.map { it.relativePath(record) } }.toSet()
        val foreign = (onListing(root) as? OnListing.Listed)?.entries?.filter { it.relativePath !in owned }?.mapNotNull { entry ->
            entry.mounted?.let { Provider(it.value, entry.relativePath) }
        }
        val mounted = enabled.flatMap(::providersOf) + (foreign ?: listOf(Provider.Unreadable))
        return records.associate { record ->
            val last = record.sets.maxOf { record.rank(it) }
            val above = (if (record.enabled) mounted else providersOf(record)).filter { it.rank < last }
            val originals = record.sets.mapNotNull { it.derivation?.original }.mapNotNull { id -> manifest.originals.firstOrNull { it.id == id } }
            record.installId to CheckKey(record, originals, above.sortedWith(compareBy({ it.rank }, { it.path })), game)
        }
    }

    private fun providersOf(record: ManifestRecord): List<Provider> = record.sets.map { set ->
        val dir = record.filesOf(set).first()
        Provider(record.rank(set), dir.relativePath(record), record.installId, dir.image)
    }

    private fun isCurrent(derivation: SetDerivation, set: SetCheck, basis: CheckBasis): Boolean =
        derivation.codec == compatibility.codec && derivation.context == basis.game && derivation.originalFiles == set.inputs &&
            compatibility.rules.containsAll(derivation.rules)

    private fun viewOf(record: ManifestRecord, shown: Boolean = false): InstallCompatibility {
        val entry = (checks.value[record.installId] ?: setAside.value[record.installId]?.takeIf { shown })?.takeIf { it.key.record == record }
        val check = (entry?.result as? CheckResult.Checked)?.check
        val applied = record.sets.mapNotNull { set ->
            set.derivation?.let { derivation ->
                val checked = check?.sets?.firstOrNull { it.source == set.source && it.componentKey == set.componentKey }
                AppliedRepair(set, derivation, current = checked != null && isCurrent(derivation, checked, check.basis))
            }
        }
        val failure = repairFailures.value[record.installId]?.takeIf { it.record == record }
        return InstallCompatibility(
            installId = record.installId,
            check = check,
            failed = (entry?.result as? CheckResult.Failed)?.reason,
            checking = record.installId in checking.value,
            applied = applied,
            choice = ledger.manifest.repairChoices[record.installId],
            held = record.edited,
            pending = repairWork.value[record.installId]?.work,
            problem = failure?.reason,
            problemRestoring = failure?.restoring == true,
        )
    }

    private fun publishCompat() {
        val root = (state.value.game as? GameStatus.Ready)?.root
        val records = if (state.value.library is LibraryStatus.Ready) ledger.manifest.records.filter { it.gameRoot == root } else emptyList()
        val view = CompatibilityState(
            installs = records.associate { it.installId to viewOf(it, shown = true) },
            unavailable = compatibility.unavailable ?: compatProblem,
            checking = checking.value.isNotEmpty(),
            automatic = automatic,
        )
        mutableState.update { if (it.compatibility == view) it else it.copy(compatibility = view) }
    }

    private suspend fun converge(root: GameRoot, keys: Map<String, CheckKey>) {
        val records = ledger.manifest.records.filter { it.gameRoot == root }
        plan(records, keys)
        publishCompat()
        for (record in records) {
            val id = record.installId
            val key = keys[id]?.takeIf { it.record == record } ?: continue
            val seen = repairWork.value[id]
            val change = when (val now = dueFor(record, keys)) {
                Due.Later -> continue
                Due.Settled -> {
                    repairWork.update { work -> if (work[id] === seen) work - id else work }
                    publishCompat()
                    continue
                }
                is Due.Change -> now.choice
            }
            val restoring = change == RepairChoice.Original
            if (processProblem() != null) {
                repairWork.update { work -> if (work[id] === seen) work + (id to RepairEntry(RepairWork.WaitingForGame(restoring), record, change)) else work }
                publishCompat()
                continue
            }
            val mine = RepairEntry(if (restoring) RepairWork.Restoring else RepairWork.Repairing, record, change)
            var started = false
            repairWork.update { work -> if (work[id] === seen) work + (id to mine).also { started = true } else work.also { started = false } }
            if (!started) continue
            publishCompat()
            val entry = checks.value[id]
            val placed = attempt {
                if (restoring) restoreOriginals(record, mine) else if (entry != null) repairRecord(record, entry, mine) else RepairPlacement.Unchanged
            }.orElse { RepairPlacement.Failed(it) }
            underLock { settleWork(id, mine, placed, key) }
            publishCompat()
            refreshLoading(root)
        }
    }

    private suspend fun plan(records: List<ManifestRecord>, keys: Map<String, CheckKey>) {
        val due = records.associate { record -> record.installId to (repairWork.value[record.installId] to dueFor(record, keys)) }
        val closed = due.values.none { (_, it) -> it is Due.Change } || processProblem() == null
        repairWork.update { work ->
            val next = work.toMutableMap()
            for ((id, planned) in due) {
                val (seen, change) = planned
                val current = work[id]
                if (current !== seen) continue
                when (change) {
                    Due.Later -> Unit
                    Due.Settled -> next.remove(id)
                    is Due.Change -> {
                        val restoring = change.choice == RepairChoice.Original
                        val shown = if (closed) RepairWork.Queued(restoring) else RepairWork.WaitingForGame(restoring)
                        val same = current != null && current.choice == change.choice && (current.work == shown || current.work is RepairWork.Verifying)
                        if (!same) next[id] = RepairEntry(shown, records.first { it.installId == id }, change.choice)
                    }
                }
            }
            next
        }
    }

    private fun dueFor(record: ManifestRecord, keys: Map<String, CheckKey>): Due {
        val id = record.installId
        if (keys[id]?.record != record) return Due.Later
        if (id in repairFailures.value || record.edited) return Due.Settled
        val view = viewOf(record)
        if (view.choice == RepairChoice.Original) return if (record.sets.any { it.derivation != null }) Due.Change(RepairChoice.Original) else Due.Settled
        val wanted = view.check != null && view.repairable.isNotEmpty() && (view.choice == RepairChoice.Repaired || view.choice == null && automatic)
        return if (wanted) Due.Change(RepairChoice.Repaired) else Due.Settled
    }

    private fun settleWork(id: String, mine: RepairEntry, placed: RepairPlacement, key: CheckKey) {
        if (repairWork.value[id] !== mine) return
        when (placed) {
            RepairPlacement.Placed, RepairPlacement.Unchanged -> repairWork.update { it - id }
            RepairPlacement.Recheck -> repairWork.update { it + (id to mine.becomes(RepairWork.Queued(mine.restoring))) }
            is RepairPlacement.Waits -> repairWork.update { it + (id to mine.becomes(RepairWork.WaitingForGame(mine.restoring))) }
            is RepairPlacement.Failed -> {
                repairFailures.update { it + (id to RepairFailure(placed.reason, mine.record, key, mine.restoring)) }
                repairWork.update { it - id }
            }
        }
    }

    private fun retireWork(keys: Map<String, CheckKey>) {
        repairWork.update { work ->
            work.mapNotNull { (id, entry) ->
                val record = keys[id]?.record ?: return@mapNotNull null
                id to if (entry.record == record) entry else entry.becomes(entry.work, record)
            }.toMap()
        }
    }

    private fun holdWork(waits: Boolean) {
        repairWork.update { work ->
            work.mapNotNull { (id, entry) ->
                when {
                    !entry.unsettled -> id to entry
                    waits && entry.work !is RepairWork.Verifying -> id to entry.becomes(RepairWork.WaitingForGame(entry.restoring))
                    else -> null
                }
            }.toMap()
        }
    }

    private suspend fun abandonWork(reason: String) = underLock {
        val left = repairWork.value.filterValues { it.unsettled }
        repairFailures.update { failures ->
            failures + left.filterValues { it.work !is RepairWork.Verifying }.mapValues { (_, entry) -> RepairFailure(reason, entry.record, key = null, entry.restoring) }
        }
        repairWork.update { work -> work.filter { (id, entry) -> left[id] !== entry } }
    }

    private suspend fun repairRecord(record: ManifestRecord, entry: CheckEntry, mine: RepairEntry): RepairPlacement {
        val check = (entry.result as? CheckResult.Checked)?.check ?: return RepairPlacement.Unchanged
        val staged = mutableListOf<StagingId>()
        try {
            val derived = linkedMapOf<InstalledSet, DeriveResult.Derived>()
            for (setCheck in check.sets) {
                val set = record.sets.single { it.source == setCheck.source && it.componentKey == setCheck.componentKey }
                val repairable = setCheck.findings.filter { it.kind == FindingKind.Repairable }
                if (repairable.isEmpty()) continue
                val derivation = set.derivation
                if (derivation != null && isCurrent(derivation, setCheck, check.basis) && repairable.all { it.rule in derivation.rules }) continue
                val original = derivation?.let { d -> ledger.manifest.originals.firstOrNull { it.id == d.original } ?: return RepairPlacement.Failed("the original files of a repaired set are not kept") }
                    ?: when (val retained = retainOriginal(record, set)) {
                        is RetainResult.Retained -> retained.original
                        is RetainResult.Failed -> return RepairPlacement.Failed(retained.reason)
                    }
                when (val result = attempt { compatibility.derive(record, set, original, setCheck, check.basis, entry.key.mounted) }) {
                    is Attempt.Failed -> return RepairPlacement.Failed(result.reason)
                    is Attempt.Done -> when (val value = result.value) {
                        is DeriveResult.Derived -> {
                            staged += value.staging
                            derived[set] = value
                        }
                        is DeriveResult.Stale -> {
                            forget(record.installId)
                            return RepairPlacement.Recheck
                        }
                        is DeriveResult.Refused -> return RepairPlacement.Failed(value.reason)
                    }
                }
            }
            if (derived.isEmpty()) return RepairPlacement.Unchanged
            if (!confirm(listOf(record.installId), retryFailed = false)) {
                compatWake.trySend(Unit)
                return RepairPlacement.Recheck
            }
            return underLock { placeLocked(record, entry, wanted = RepairChoice.Repaired, mine) { set -> derived[set]?.let { SetPlan.Staged.replacing(set, it.vpks, it.derivation) } } }
        } finally {
            withContext(NonCancellable) { staged.forEach { attempt { compatibility.discard(it) } } }
        }
    }

    private suspend fun restoreOriginals(record: ManifestRecord, mine: RepairEntry): RepairPlacement {
        val staged = mutableListOf<StagingId>()
        try {
            val restored = linkedMapOf<InstalledSet, StagedOriginal.Staged>()
            for (set in record.sets) {
                val derivation = set.derivation ?: continue
                val original = ledger.manifest.originals.firstOrNull { it.id == derivation.original } ?: return RepairPlacement.Failed("the original files are not kept")
                when (val result = attempt { compatibility.stageOriginal(original, set) }) {
                    is Attempt.Failed -> return RepairPlacement.Failed(result.reason)
                    is Attempt.Done -> when (val value = result.value) {
                        is StagedOriginal.Staged -> {
                            staged += value.staging
                            if (value.vpks.members.map { FileImage(it.vpk.sizeBytes, it.vpk.sha256) } != derivation.originalFiles) return RepairPlacement.Failed("the kept original files do not match their record")
                            restored[set] = value
                        }
                        is StagedOriginal.Failed -> return RepairPlacement.Failed(value.reason)
                    }
                }
            }
            if (restored.isEmpty()) return RepairPlacement.Unchanged
            return underLock { placeLocked(record, entry = null, wanted = RepairChoice.Original, mine) { set -> restored[set]?.let { SetPlan.Staged.replacing(set, it.vpks, null) } } }
        } finally {
            withContext(NonCancellable) { staged.forEach { attempt { compatibility.discard(it) } } }
        }
    }

    private suspend fun placeLocked(record: ManifestRecord, entry: CheckEntry?, wanted: RepairChoice, mine: RepairEntry, replacing: (InstalledSet) -> SetPlan.Staged?): RepairPlacement {
        val library = state.value.library
        if (library !is LibraryStatus.Ready) return RepairPlacement.Failed(LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason).reason())
        processProblem()?.let { return RepairPlacement.Waits(it.reason()) }
        recoverLocked()?.let { return RepairPlacement.Waits(it) }
        val current = ledger.manifest.records.firstOrNull { it.installId == record.installId }
        val choice = ledger.manifest.repairChoices[record.installId]
        val asked = current == record && (choice == wanted || wanted == RepairChoice.Repaired && choice == null && automatic)
        val holds = entry == null || checks.value[record.installId] === entry && keysFor(record.gameRoot)[record.installId] == entry.key
        if (!asked || !holds) {
            forget(record.installId)
            return RepairPlacement.Recheck
        }
        if (attempt { locator.check(record.gameRoot.path) } != Attempt.Done(FolderCheck.Valid(record.gameRoot))) return RepairPlacement.Failed(LibraryProblem.GameNotFound.reason())
        val plan = record.sets.map { set -> replacing(set) ?: SetPlan.Kept(set.source, set.componentKey) }
        val outcome = attempt { ledger.replace(record, record.title, plan) }.orElse { InstallOutcome.Refused(LibraryProblem.Unexpected(it)) }
        if (outcome is InstallOutcome.Installed) {
            val id = record.installId
            ledger.manifest.records.firstOrNull { it.installId == id }?.let { placed ->
                repairWork.update { work -> if (work[id] === mine) work + (id to mine.becomes(RepairWork.Verifying(mine.restoring), placed)) else work }
            }
        }
        publishRecords((outcome as? InstallOutcome.Refused)?.interrupted, setOf(record.installId))
        return when (outcome) {
            is InstallOutcome.Installed -> RepairPlacement.Placed
            is InstallOutcome.Refused -> outcome.interrupted?.let { RepairPlacement.Waits(it) } ?: RepairPlacement.Failed(outcome.problem.reason())
        }
    }

    private suspend fun retainOriginal(record: ManifestRecord, set: InstalledSet): RetainResult {
        val id = ids()
        val original = when (val result = attempt { compatibility.retain(record, set, id) }) {
            is Attempt.Failed -> return RetainResult.Failed(result.reason)
            is Attempt.Done -> when (val value = result.value) {
                is RetainResult.Retained -> value.original
                is RetainResult.Failed -> return value
            }
        }
        val saved = underLock {
            when {
                ledger.manifest.records.none { it == record } -> SaveResult.Refused("the mod changed while its original files were kept")
                original.imagesFor(set) != set.files.map { it.image } -> SaveResult.Refused("the kept copy does not match the mod's files")
                else -> ledger.registerOriginal(original)
            }
        }
        return when (saved) {
            SaveResult.Saved -> RetainResult.Retained(original)
            is SaveResult.Refused -> {
                attempt { compatibility.release(id) }
                RetainResult.Failed(saved.reason)
            }
            is SaveResult.Unconfirmed -> RetainResult.Failed(saved.reason)
        }
    }

    private suspend fun collectOriginals() = underLock {
        if (state.value.library !is LibraryStatus.Ready || ledger.uncertainty != null || ledger.manifest.pending != null) return@underLock
        val ids = ledger.manifest.originals.map { it.id }.toSet()
        val (saved, forgotten) = if (ids.isEmpty()) SaveResult.Saved to emptySet() else ledger.forgetOriginals(ids)
        if (saved != SaveResult.Saved) return@underLock
        forgotten.forEach { attempt { compatibility.release(it) } }
        if (!ledger.listWasMissing) attempt { compatibility.sweep(ledger.manifest.originals.map { it.id }.toSet()) }
    }

    private val FilesCheck.damage: FilesDamage?
        get() = when (this) {
            FilesCheck.Changed -> FilesDamage.Changed
            FilesCheck.Missing -> FilesDamage.Missing
            FilesCheck.Intact, FilesCheck.Unknown -> null
        }

    private sealed interface Writable {
        data class Ok(val root: GameRoot) : Writable

        data class Refused(val problem: LibraryProblem) : Writable
    }

    private data class Asked(
        val input: InstallInput?,
        val modId: String,
        val profiles: Set<ProfileId>,
        val fileIds: Set<Long> = (input as? InstallInput.Remote)?.fileIds.orEmpty(),
        val setKeys: Map<Long, List<String>> = emptyMap(),
        val localKeys: List<String> = emptyList(),
        val replacement: ManifestRecord? = null,
        val chooseVersion: Boolean = false,
        val update: UpdatePlan? = null,
        val swap: Swap? = null,
        val reinstall: Boolean = false,
        val parts: List<SetKey>? = null,
    )

    private data class Swap(val profile: ProfileId, val rivals: Set<String>, val off: Boolean)

    private class InstallRun(@Volatile var asked: Asked) {
        lateinit var job: Job

        @Volatile
        var unfinished: UnfinishedInstall? = null
    }

    private sealed interface Committed {
        data object Done : Committed

        data class Failed(val stage: OperationStage.Failed) : Committed

        data object Fenced : Committed

        data object WaitForGame : Committed
    }
}

private fun LibraryStatus.withInterruption(interrupted: String?): LibraryStatus = if (this is LibraryStatus.Ready) copy(interrupted = interrupted) else this

private fun LibraryState.projected(renew: Boolean = false): LibraryState {
    val saved = profiles ?: return copy(shownProfiles = null, orderView = null)
    val shown = queued.fold(saved) { book, queued -> (runCatching { queued.edit(book) }.getOrNull() as? ChoiceEdit.Next<*>)?.target ?: book }
    val root = (game as? GameStatus.Ready)?.root
    val sets = root?.let { shown.activeProfile.orders[it].orEmpty() }
    val view = orderView?.takeIf { !renew && it.root == root && it.sets == sets } ?: root?.let { OrderView(it, sets.orEmpty()) }
    return copy(shownProfiles = shown, orderView = view)
}

/** Why a choice edit would be refused now; null while [LibraryState.canEditChoices]. */
internal fun LibraryState.editBlock(): LibraryProblem? {
    val library = library
    return when {
        library !is LibraryStatus.Ready -> LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason)
        library.interrupted != null -> LibraryProblem.Interrupted(library.interrupted)
        profiles == null -> LibraryProblem.LibraryUnavailable(null)
        launching -> LibraryProblem.Busy
        else -> null
    }
}

private fun LibraryState.launchBlock(): LibraryProblem? {
    val library = library
    return when {
        library !is LibraryStatus.Ready -> LibraryProblem.LibraryUnavailable((library as? LibraryStatus.Blocked)?.reason)
        launching -> LibraryProblem.Busy
        else -> null
    }
}

internal fun LibraryProblem.reason(): String = when (this) {
    is LibraryProblem.PlacementFailed -> reason
    is LibraryProblem.Interrupted -> reason
    is LibraryProblem.ManifestSave -> reason
    is LibraryProblem.Unexpected -> reason
    is LibraryProblem.GameInfoWriteFailed -> reason
    is LibraryProblem.AutoexecWriteFailed -> reason
    is LibraryProblem.ReticleWriteFailed -> reason
    is LibraryProblem.ProcessesUnknown -> reason
    is LibraryProblem.NameTaken -> "another file is at $name"
    is LibraryProblem.NumberTaken -> "files Hideout did not put there use number ${number.pak.value.toString().padStart(2, '0')}: ${names.joinToString()}"
    is LibraryProblem.FileMissing -> "$path is missing"
    is LibraryProblem.FileChanged -> "$path changed after Hideout put it there"
    is LibraryProblem.FileUnreadable -> "$path could not be read"
    is LibraryProblem.AddonsUnreadable -> "the addons folder could not be read: $reason"
    is LibraryProblem.GameInfo -> "gameinfo.gi could not be used: ${problem.name}"
    LibraryProblem.GameNotFound -> "the game folder is not available"
    LibraryProblem.GameRunning -> "Deadlock was running"
    LibraryProblem.CustomGameRunning -> "a custom game was running"
    LibraryProblem.LoadOrderChanged -> "the installed sets changed"
    is LibraryProblem.NoRoomInOrder -> "there is no room for $count sets without moving files Hideout did not put there"
    is LibraryProblem.LibraryUnavailable -> reason ?: "the install list cannot be read"
    is LibraryProblem.BackupFailed -> reason
    is LibraryProblem.BackupUnreadable -> "the backup could not be read: $reason"
    else -> toString()
}

private fun LibraryProblem?.staged(): BackupStage = this?.let { BackupStage.Refused(it) } ?: BackupStage.Done

private fun ClearStage.Cleared.after(outcome: RemovalOutcome, download: OwnedDownload): ClearStage.Cleared = when (outcome) {
    RemovalOutcome.Removed -> copy(removed = removed + 1, freedBytes = freedBytes + download.archive.sizeBytes)
    RemovalOutcome.Absent -> copy(alreadyGone = alreadyGone + 1)
    is RemovalOutcome.Preserved -> copy(kept = kept + 1)
}

private val PendingChange.installIds: Set<String>
    get() = when (this) {
        is PendingChange.Replace -> setOf(source.installId)
        is PendingChange.Install -> setOf(record.installId)
        is PendingChange.Relocate -> setOf(source.installId)
        is PendingChange.Uninstall -> setOf(record.installId)
        is PendingChange.Arrange -> moves.map { it.source.installId }.toSet()
        is PendingChange.Adopt -> plan.adopted.map { it.record.installId }.toSet()
        is PendingChange.Revert -> (source + target).map { it.installId }.toSet()
        is PendingChange.ConfigEdit, is PendingChange.ArchiveRemoval -> emptySet()
    }

/** [modId]'s install as one state shows it: its operation, else whether the library has it, unknown while the library cannot be read. */
internal fun LibraryState.installStatus(modId: String): InstallStatus = operations[modId]?.status(gameBananaBusy) ?: library.statusOf(modId)

private fun LibraryProblem.goesOnLater(): Boolean = this is LibraryProblem.MetadataUnavailable ||
    this is LibraryProblem.Download && problem.let { it is DownloadProblem.Network || it is DownloadProblem.Http && (it.status == 408 || it.status == 429 || it.status >= 500) }

private fun Operation.status(busy: Instant?): InstallStatus = when (val stage = stage) {
    OperationStage.Preparing -> busy?.takeIf { SubmissionRef.parse(modId) != null }?.let(InstallStatus::Busy) ?: InstallStatus.Preparing
    is OperationStage.ChoosingFile, is OperationStage.ChoosingSet, is OperationStage.ChoosingSwap -> InstallStatus.NeedsChoice
    is OperationStage.Waiting -> InstallStatus.Waiting
    is OperationStage.Downloading -> stage.progress.busyUntil?.let { InstallStatus.Busy(it, started = stage.progress.doneBytes > 0) }
        ?: InstallStatus.Downloading(stage.progress.percent, stage.progress.stalled)
    OperationStage.Unpacking, OperationStage.Installing -> InstallStatus.Installing
    is OperationStage.WaitingForGame -> InstallStatus.WaitingForGame
    OperationStage.Cancelling -> InstallStatus.Cancelling
    is OperationStage.Failed -> InstallStatus.Failed(stage.problem)
}

/** The installs [modId]'s page is about: GameBanana's own of it, else local ones known to be from it. */
internal fun List<ManifestRecord>.pageRecords(modId: String): List<ManifestRecord> = filter { it.modId == modId }.ifEmpty { filter { it.submission?.modId == modId } }

private fun LibraryStatus.statusOf(modId: String): InstallStatus = when (this) {
    LibraryStatus.Loading, is LibraryStatus.Blocked -> InstallStatus.Unknown
    is LibraryStatus.Ready -> records.pageRecords(modId)
        .takeIf { it.isNotEmpty() }
        ?.let { sets -> InstallStatus.Installed(enabled = sets.any { it.enabled }) }
        ?: InstallStatus.NotInstalled
}

private fun List<SetPlan>.nameOf(source: InstalledSource?, installed: ManifestRecord? = null): String? = when (val set = firstOrNull { it.source == source }) {
    null -> null
    is SetPlan.Staged -> (set.source as? InstalledSource.Local)?.fileName ?: set.originalName ?: (set.origin as? SetOrigin.Downloaded)?.archive?.fileName
    is SetPlan.Kept, is SetPlan.Stored -> installed?.sets?.firstOrNull { it.source == set.source }?.archiveName
}

private fun UpdatePlan.targetsIn(files: List<RemoteFile>): List<RemoteFile>? = replacements.map { (from, to) ->
    val target = files.singleOrNull { it.id == to.fileId && it.md5 == to.fileMd5 } ?: return null
    if (to.fileId != from.fileId && (files.size != 1 || files.any { it.id == from.fileId })) return null
    target
}

private const val MaxTransfers = 2

private const val MaxListings = 2

private const val PlacementWaits = "an install of this mod that Deadlock interrupted is waiting to be finished or undone"

private const val SettlingInterruption = "an interrupted change is being finished or undone"

private const val UndoSeconds = 6

private val LeaveMargin = 500.milliseconds

private const val KeptAutomatic = 5

private const val MaxAsideTitle = 80

private val UnsafeInFolderNames = Regex("""[\s\p{Cntrl}/\\:*?"<>|]+""")

private const val GameRunningReason = "Deadlock is running"

private const val CustomGameRunningReason = "a custom game is running"

private const val NoSessionHolds = "no custom game holds the game's files"

private const val ProcessesUnknownReason = "the running programs could not be read"

private const val LeftInPlace = "an interrupted change left files in place that Hideout could not prove were its own"

private const val IdAlphabet = "abcdefghijklmnopqrstuvwxyz234567"

internal fun randomId(): String = (1..20).map { IdAlphabet[Random.nextInt(IdAlphabet.length)] }.joinToString("")

private fun LibraryProblem.usageFailure(): UsageFailure = when (this) {
    is LibraryProblem.Download, is LibraryProblem.MetadataUnavailable -> UsageFailure.Network
    is LibraryProblem.ArchiveRefused, is LibraryProblem.NoCompleteSet, LibraryProblem.NoFiles, LibraryProblem.FileNotOffered -> UsageFailure.InvalidContent
    is LibraryProblem.PlacementFailed, is LibraryProblem.ManifestSave, is LibraryProblem.GameInfoWriteFailed, is LibraryProblem.ExtractionFailed -> UsageFailure.Storage
    LibraryProblem.GameNotFound, LibraryProblem.GameRunning, LibraryProblem.CustomGameRunning, LibraryProblem.Busy -> UsageFailure.Unavailable
    else -> UsageFailure.Other
}
