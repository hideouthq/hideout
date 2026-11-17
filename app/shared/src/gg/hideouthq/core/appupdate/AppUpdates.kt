package gg.hideouthq.core.appupdate

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.quietly
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

internal const val RecordFile = "app-update-record.json"

/**
 * The owner of updating Hideout itself. It checks the signed feed on start and every six hours, keeps the package on
 * offer on disk, and installs it through [method] on Restart or on the way out. Every move of the phase happens under
 * one lock and starts from the state it finds, so each verb is safe to call at any time and a second call does nothing.
 * The next start judges whether an install worked, from a record written before it began, so a crash at any point
 * ends in the toast, the Failed chip, or nothing.
 *
 * With no [feed] this run never checks, and [state] stays [AppUpdatePhase.Idle].
 */
class AppUpdates(
    private val method: AppUpdateMethod?,
    private val running: AppVersion,
    private val feed: ReleaseFeedSource?,
    private val packages: UpdatePackages,
    private val records: CacheStorage,
    private val preferences: Preferences,
    private val modsBusy: Flow<Boolean>,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) {
    private val shown = MutableStateFlow(AppUpdateState(running))
    val state: StateFlow<AppUpdateState> = shown.asStateFlow()

    /** A Restart has been asked for and not called off, so nothing new may start that it would end. */
    val restartBegun: Boolean get() = (shown.value.phase as? AppUpdatePhase.Ready)?.restart.let { it != null && it != RestartStage.Idle }

    private val exits = Channel<Unit>(Channel.CONFLATED)

    /**
     * Asks the app to close, which ends in [leave]. It never asks about a custom game first: a Restart either waited for the
     * game to end or came from Restart now, which the player chose after the warning.
     */
    val exitRequests: Flow<Unit> = exits.receiveAsFlow()

    private val mutex = Mutex()
    private var download: Job? = null
    private var waiting: Job? = null
    private var leaving: Deferred<Boolean>? = null

    private var cancelled: AppVersion? = null

    private var failedInstall: AppVersion? = null

    private val opening = scope.launch(start = CoroutineStart.LAZY) {
        preferences.readDownloadAppUpdates()
        val opened = preferences.appUpdateOpened()?.let(AppVersion::parse)
        val record = takeRecord()
        val known = feed?.lastGood()
        mutex.withLock {
            failedInstall = record.takeIf { it != running }
            record?.let { log(if (it == running) "Updated to Hideout $running" else "Hideout opened on $running after installing $it") }
            shown.update { it.copy(justUpdatedTo = record.takeIf { to -> to == running }, feed = known?.feed, lastChecked = known?.fetchedAt, openedFor = it.openedFor ?: opened) }
            known?.let { offer(it.feed, fresh = false) }
        }
    }

    private val checks = scope.launch(start = CoroutineStart.LAZY) {
        opening.join()
        while (true) {
            check()
            delay(CheckEvery)
        }
    }

    private val watchingPreference = scope.launch(start = CoroutineStart.LAZY) {
        opening.join()
        var was = preferences.downloadAppUpdates.value
        preferences.downloadAppUpdates.collect { now ->
            if (was == false && now == true) moving {
                val phase = shown.value.phase
                if (phase is AppUpdatePhase.Available) {
                    cancelled = null
                    fetch(phase.pkg)
                }
            }
            was = now
        }
    }

    /** Reads what the last run left and starts checking. Only the first call does anything. */
    fun start() {
        if (feed == null) return
        opening.start()
        checks.start()
        watchingPreference.start()
    }

    /** Settings' Check for Hideout updates. Does nothing while a check is under way, or in a run that never checks. */
    fun checkNow() {
        if (feed == null) return
        scope.launch {
            opening.join()
            check()
        }
    }

    /** Update and Try again. Gets the package on offer, which may already be on disk. */
    fun update() = moving {
        val phase = shown.value.phase
        val pkg = when (phase) {
            is AppUpdatePhase.Available -> phase.pkg
            is AppUpdatePhase.Failed -> phase.pkg
            else -> return@moving
        }
        cancelled = null
        when {
            pkg != null -> fetch(pkg)
            phase is AppUpdatePhase.Failed -> move(AppUpdatePhase.GitHubOnly(phase.version, phase.releasesUrl))
        }
    }

    /** Back to Available, where it stays until the user asks, the next start, or a newer version. The partial file stays for the next try. */
    fun cancelDownload() = moving {
        val phase = shown.value.phase as? AppUpdatePhase.Downloading ?: return@moving
        download?.cancel()
        cancelled = phase.version
        move(AppUpdatePhase.Available(phase.pkg))
    }

    /** The update panel of the version on offer was opened and has closed: its chip stops asking, from now on. */
    fun opened() {
        val version = shown.value.phase.version ?: return
        if (shown.getAndUpdate { it.copy(openedFor = version) }.openedFor == version) return
        scope.launch { preferences.setAppUpdateOpened(version.toString()) }
    }

    fun cancelRestart() = moving {
        val phase = shown.value.phase as? AppUpdatePhase.Ready ?: return@moving
        if (phase.restart != RestartStage.Waiting) return@moving
        waiting?.cancel()
        move(phase.copy(restart = RestartStage.Idle))
    }

    /**
     * Installs the ready package and reopens Hideout, once mod changes have finished. A custom game holds the mods, so
     * while one runs this waits for it to end: that is "Restart after your game".
     */
    fun restart() = moving {
        val phase = shown.value.phase as? AppUpdatePhase.Ready ?: return@moving
        if (phase.restart != RestartStage.Idle) return@moving
        // Before reading modsBusy, so a custom game either sees this and stays out, or holds in time for this to see it.
        move(phase.copy(restart = RestartStage.Waiting))
        if (!modsBusy.first()) return@moving install(phase.pkg)
        waiting = scope.launch {
            modsBusy.first { !it }
            mutex.withLock {
                ensureActive()
                install(phase.pkg)
            }
        }
    }

    /** Restart now, after the player was told it ends their game: installs without waiting for mods or the game. */
    fun restartNow() = moving {
        val phase = shown.value.phase as? AppUpdatePhase.Ready ?: return@moving
        if (phase.restart != RestartStage.Waiting) return@moving
        waiting?.cancel()
        install(phase.pkg)
    }

    /** The toast after an update has been seen, or its time ran out. */
    fun acknowledgeUpdated() = shown.update { it.copy(justUpdatedTo = null) }

    /**
     * What the app does on its way out, whichever way it is leaving. False means stay open, because the install a Restart asked
     * for failed and the chip says so. At most [LeaveWait] passes before the answer is true regardless. A second call
     * joins the first.
     */
    suspend fun leave(): Boolean = withTimeoutOrNull(LeaveWait) {
        mutex.withLock { leaving ?: scope.async { depart() }.also { leaving = it } }.await()
    } ?: true

    private suspend fun depart(): Boolean {
        val ready = shown.value.phase as? AppUpdatePhase.Ready ?: return true
        val exiting = ready.restart == RestartStage.Exiting
        when (method) {
            null -> return true
            is AppUpdateMethod.WhileOpen -> {
                if (exiting) (attempt { method.relaunch() } as? InstallOutcome.Failed)?.let { log("Hideout updated, but didn't reopen: ${it.detail}") }
                return true
            }
            is AppUpdateMethod.AtExit -> {
                val outcome = when {
                    !packages.probe(ready.pkg) -> PackageChanged
                    writeRecord(ready.pkg) -> attempt { method.replace(ready.pkg, exiting) }
                    else -> NoRecord
                }
                if (outcome == InstallOutcome.Done) return true
                log("Installing Hideout ${ready.version} failed: $outcome")
                if (!exiting) return true
                mutex.withLock {
                    leaving = null
                    clearRecord()
                    fail(ready.version, ready.pkg, if (outcome == PackageChanged) UpdateFailure.Mismatch else (outcome as? InstallOutcome.Failed)?.why ?: UpdateFailure.Install)
                }
                return false
            }
        }
    }

    private fun moving(block: suspend () -> Unit) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { mutex.withLock { block() } }
    }

    private fun move(phase: AppUpdatePhase) {
        step(shown.getAndUpdate { it.copy(phase = phase) }.phase, phase)?.let(log)
    }

    private fun fail(version: AppVersion, pkg: ReleasePackage?, why: UpdateFailure) =
        shown.update { it.copy(phase = AppUpdatePhase.Failed(version, pkg, why, it.feed!!.notesUrl)) }

    private suspend fun check() {
        val source = feed ?: return
        if (shown.getAndUpdate { it.copy(checking = true) }.checking) return
        val fetched = source.fetch()
        mutex.withLock {
            when (fetched) {
                is FeedFetch.Fresh -> {
                    shown.update { it.copy(checking = false, lastChecked = fetched.known.fetchedAt, lastCheckFailed = false) }
                    if (!committed) {
                        shown.update { it.copy(feed = fetched.known.feed) }
                        offer(fetched.known.feed, fresh = true)
                    }
                }
                is FeedFetch.Unreachable -> checkFailed(fetched.detail)
                is FeedFetch.Refused -> checkFailed(fetched.detail)
            }
        }
    }

    private fun checkFailed(detail: String) {
        log("Checking for Hideout updates failed: $detail")
        shown.update { it.copy(checking = false, lastCheckFailed = true) }
    }

    private val committed: Boolean
        get() = (shown.value.phase as? AppUpdatePhase.Ready)?.restart.let { it == RestartStage.Installing || it == RestartStage.Exiting }

    private suspend fun offer(feed: ReleaseFeed, fresh: Boolean) {
        val phase = shown.value.phase
        if (feed.latest <= running) {
            stopInProgress()
            move(AppUpdatePhase.Idle)
            packages.keepOnly(null)
            return
        }
        val pkg = method?.let { feed.packages[it.format] }
        if (phase.version == feed.latest && (pkg == null || pkg == phase.pkg)) {
            if (automatic(feed.latest)) when {
                phase is AppUpdatePhase.Available -> fetch(phase.pkg)
                phase is AppUpdatePhase.Failed && phase.pkg != null && phase.why in RetriedByItself -> fetch(phase.pkg)
            }
            return
        }
        stopInProgress()
        packages.keepOnly(pkg)
        when {
            failedInstall == feed.latest -> fail(feed.latest, pkg, UpdateFailure.NotReplaced)
            pkg == null -> move(AppUpdatePhase.GitHubOnly(feed.latest, feed.notesUrl))
            packages.probe(pkg) -> move(AppUpdatePhase.Ready(pkg, RestartStage.Idle))
            fresh && automatic(feed.latest) -> startDownload(pkg)
            else -> move(AppUpdatePhase.Available(pkg))
        }
    }

    private fun automatic(version: AppVersion) = preferences.downloadAppUpdates.value != false && cancelled != version

    private fun stopInProgress() {
        download?.cancel()
        waiting?.cancel()
    }

    private suspend fun fetch(pkg: ReleasePackage) {
        if (packages.probe(pkg)) move(AppUpdatePhase.Ready(pkg, RestartStage.Idle)) else startDownload(pkg)
    }

    private fun startDownload(pkg: ReleasePackage) {
        move(AppUpdatePhase.Downloading(pkg, 0))
        val previous = download
        previous?.cancel()
        download = scope.launch {
            previous?.join()
            val result = packages.download(pkg) { done ->
                shown.update { state ->
                    val phase = state.phase
                    if (phase is AppUpdatePhase.Downloading && phase.pkg == pkg) state.copy(phase = phase.copy(doneBytes = done)) else state
                }
            }
            mutex.withLock {
                ensureActive()
                when (result) {
                    PackageDownload.Verified -> move(AppUpdatePhase.Ready(pkg, RestartStage.Idle))
                    is PackageDownload.Failed -> {
                        log("Downloading Hideout ${pkg.version} failed: ${result.why}")
                        fail(pkg.version, pkg, result.why)
                    }
                }
            }
        }
    }

    private suspend fun install(pkg: ReleasePackage) {
        when (method) {
            null -> Unit
            is AppUpdateMethod.AtExit -> requestExit(pkg)
            is AppUpdateMethod.WhileOpen -> {
                if (!packages.probe(pkg)) {
                    log("Installing Hideout ${pkg.version} failed: $PackageChanged")
                    return fail(pkg.version, pkg, UpdateFailure.Mismatch)
                }
                if (!writeRecord(pkg)) {
                    log("Installing Hideout ${pkg.version} failed: $NoRecord")
                    return fail(pkg.version, pkg, UpdateFailure.Install)
                }
                move(AppUpdatePhase.Ready(pkg, RestartStage.Installing))
                scope.launch {
                    val outcome = attempt { method.install(pkg) }
                    mutex.withLock {
                        when (outcome) {
                            InstallOutcome.Done -> requestExit(pkg)
                            InstallOutcome.Cancelled -> {
                                clearRecord()
                                move(AppUpdatePhase.Ready(pkg, RestartStage.Idle))
                            }
                            is InstallOutcome.Failed -> {
                                log("Installing Hideout ${pkg.version} failed: ${outcome.detail}")
                                clearRecord()
                                fail(pkg.version, pkg, outcome.why)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestExit(pkg: ReleasePackage) {
        move(AppUpdatePhase.Ready(pkg, RestartStage.Exiting))
        exits.trySend(Unit)
    }

    private suspend fun attempt(install: suspend () -> InstallOutcome): InstallOutcome = try {
        install()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        InstallOutcome.Failed("$e")
    }

    private suspend fun takeRecord(): AppVersion? {
        val text = quietly { records.read(RecordFile) }
        if (text.isNullOrEmpty()) return null
        clearRecord()
        return try {
            AppVersion.parse(RecordJson.decodeFromString<InstallRecord>(text).to)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private suspend fun writeRecord(pkg: ReleasePackage): Boolean =
        quietly { records.write(RecordFile, RecordJson.encodeToString(InstallRecord(pkg.version.toString()))) } != null

    private suspend fun clearRecord() {
        if (quietly { records.write(RecordFile, "") } == null) log("The Hideout update record couldn't be cleared")
    }

    private companion object {
        val RecordJson = Json { ignoreUnknownKeys = true }
        val RetriedByItself = setOf(UpdateFailure.Connection, UpdateFailure.Server)
        val NoRecord = InstallOutcome.Failed("The install record couldn't be written")
        val PackageChanged = InstallOutcome.Failed("The downloaded package no longer matches the feed")
        val CheckEvery = 6.hours
        val LeaveWait = 2.minutes
    }
}

@Serializable
private data class InstallRecord(val to: String)

/** The log's line for an update moving from [before] to [after]; none for a check that finds nothing new, or a failure, which says why where it happens. */
private fun step(before: AppUpdatePhase, after: AppUpdatePhase): String? {
    if (before::class == after::class && before.version == after.version && (after !is AppUpdatePhase.Ready || after.restart == (before as AppUpdatePhase.Ready).restart)) return null
    return when (after) {
        AppUpdatePhase.Idle, is AppUpdatePhase.Failed -> null
        is AppUpdatePhase.GitHubOnly -> "Hideout ${after.version} is out, and this copy can only point at GitHub for it"
        is AppUpdatePhase.Available -> if (before is AppUpdatePhase.Downloading) "Stopped downloading Hideout ${after.version}" else "Hideout ${after.version} is available"
        is AppUpdatePhase.Downloading -> "Downloading Hideout ${after.version}"
        is AppUpdatePhase.Ready -> when (after.restart) {
            RestartStage.Idle -> if (before is AppUpdatePhase.Ready && before.version == after.version) "Restart into Hideout ${after.version} called off" else "Hideout ${after.version} is downloaded and checked"
            RestartStage.Waiting -> "Restart into Hideout ${after.version} waits for mod changes and any custom game to end"
            RestartStage.Installing -> "Installing Hideout ${after.version}"
            RestartStage.Exiting -> "Closing to finish updating to Hideout ${after.version}"
        }
    }
}
