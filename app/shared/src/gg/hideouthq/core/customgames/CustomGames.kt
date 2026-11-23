package gg.hideouthq.core.customgames

import gg.hideouthq.core.analytics.*

import gg.hideouthq.core.customgames.SessionCommand.*
import gg.hideouthq.core.customgames.SessionEvent.*
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GamesLines
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.SessionAdmission
import gg.hideouthq.core.library.reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * [feed] is the newest accepted, kept through whatever [problem] says went wrong since. [noFeed] says this build has no
 * feed to ask, so no answer is coming, which a null [feed] alone can't tell from one not read yet.
 */
data class ModeList(
    val feed: CustomGamesFeed? = null,
    val refreshing: Boolean = false,
    val problem: CustomGamesFetch.Problem? = null,
    val noFeed: Boolean = false,
    val installed: Map<ModeId, InstalledMode> = emptyMap(),
    val updating: Pair<ModeId, Transfer?>? = null,
    val failed: Map<ModeId, FetchProblem> = emptyMap(),
    /** Null while hosting mode folders is off, so no screen shows them or a way to add one. */
    val folders: List<LibraryEntry.Folder>? = null,
) {
    val modes: List<Mode> get() = feed?.modes.orEmpty()

    fun installOf(mode: Mode): Install {
        updating?.let { (id, transfer) -> if (id == mode.id) return Install.Updating(transfer) }
        return installed[mode.id]?.let { Install.Installed(it, updateOf(it)) } ?: Install.NotInstalled
    }

    fun updateOf(record: InstalledMode): Mode? = feed?.mode(record.mode.id)?.takeIf { it.packages != record.mode.packages }

    /**
     * The one mode Host means for [id], wherever the player presses it: the feed's while a read feed lists it, since that
     * is the version a friend's list names, and otherwise the installed one, with no network or after the feed dropped it.
     */
    fun hostable(id: ModeId): Mode? = feed?.mode(id) ?: installed[id]?.mode

    fun downloadToHost(mode: Mode): Long? = mode.download?.takeIf { !onDisk.containsAll(mode.packages) }

    /** Installed first, so a guest who has the offer's client needs no list. Null for content the host shares, which no list here names. */
    fun forGuest(offer: InviteOffer): Mode? {
        val content = offer.content as? OfferContent.Feed ?: return null
        return listOfNotNull(installed[offer.mode]?.mode, feed?.mode(offer.mode)).firstOrNull { it.clientDigest == content.client }
    }

    fun shownFor(offer: InviteOffer): Mode? = if (offer.content is OfferContent.Feed) forGuest(offer) ?: hostable(offer.mode) else null

    val onDisk: Set<Sha256> get() = installed.values.flatMapTo(mutableSetOf()) { it.mode.packages }

    /** Two reads of the feed can overlap, and whichever answers last may be the older. */
    fun withNewest(found: CustomGamesFeed?): ModeList = copy(feed = listOfNotNull(found, feed).maxByOrNull { it.sequence })
}

/** Why a clicked link did not reach [Session.Confirming]. */
enum class LinkProblem {
    Unreadable,

    /** A running session is never interrupted by a link, which is dropped. */
    SessionRunning,
}

/**
 * The owner of a custom game. One coroutine reads events, runs [reduce] and [recordAfter], publishes [live] and carries
 * out the commands, which post their results back as events. No state but [live] says where a session is or what it is for.
 *
 * Admitting, launching, releasing and the journal run one at a time in the order issued, on a queue of their own, so the
 * helper can still be stopped while the library takes its time. [start] ends what a crash left before any event is read.
 */
class CustomGames(
    private val platform: CustomGamesPlatform,
    private val files: SessionFiles,
    private val feed: CustomGamesFeedSource?,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val hostFolders: Flow<Boolean>,
    private val analytics: UsageAnalytics? = null,
) : AutoCloseable {
    private class UsageRun(val attempt: UsageAttempt, var stage: UsageStage = UsageStage.Prepare, var play: UsagePlay? = null)
    private var usage: UsageRun? = null
    val firewallMayAsk: Boolean get() = platform.firewallMayAsk

    private val publishedLive = MutableStateFlow(LiveSession())
    val live: StateFlow<LiveSession> = publishedLive.asStateFlow()

    private val publishedModes = MutableStateFlow(ModeList(noFeed = feed == null))
    val modes: StateFlow<ModeList> = publishedModes.asStateFlow()

    private val publishedProgress = MutableStateFlow<Transfer?>(null)

    /** How far a download has got, once [Session.HostPreparing] or [Session.GuestPreparing] has started on it. */
    val transfer: StateFlow<Transfer?> = publishedProgress.asStateFlow()

    private val publishedRestarts = MutableStateFlow<RestartReason?>(null)

    /** Why Host or Join would restart the running Deadlock first, or null when they wouldn't. */
    val restartsDeadlock: StateFlow<RestartReason?> = publishedRestarts.asStateFlow()

    private val publishedLink = MutableStateFlow<Long?>(null)

    /** The serial of the newest link, kept so one that started Hideout still gets the page once first-run setup is out of the way. */
    val lastLink: StateFlow<Long?> = publishedLink.asStateFlow()

    private val publishedLinkProblem = MutableStateFlow<LinkProblem?>(null)

    /** What the newest link came to, until the player has read it. */
    val linkProblem: StateFlow<LinkProblem?> = publishedLinkProblem.asStateFlow()

    private sealed interface Input {
        class Event(val event: SessionEvent) : Input

        class Link(val code: InviteCode?) : Input

        class FromLink(val process: LinkProcess, val event: SessionEvent) : Input

        class Prepared(val job: Job, val answer: PrepareAnswer) : Input

        data object Again : Input
        data object CloseUsage : Input
    }

    private sealed interface ModeUpkeep : Input {
        /** Every installed mode with an update when [id] is null. */
        class Update(val id: ModeId?) : ModeUpkeep

        class Remove(val id: ModeId) : ModeUpkeep
    }

    private val inbox = Channel<Input>(Channel.UNLIMITED)
    private val fileWork = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    @Volatile
    private var client = GameActivity.Unread

    @Volatile
    private var menusFailedThisRun = false

    @Volatile
    private var me: LocalPlayer? = null
    private var lastToken = 0L
    private var lastLinkSerial = 0L
    private var timer: Job? = null

    /** Runs from Hideout's launch of Deadlock for [EnterWindow.Launched], ended early by its menus taking a connect or it closing. */
    private var booting: Job? = null
    private var prepareJob: Job? = null
    private var upkeepJob: Job? = null
    private val folderList = Mutex()

    @Volatile
    private var foldersShown = false

    private val storeChanges = Mutex()

    /** The first read lists what is installed, and only later reads log what changed. */
    private val installedRead = AtomicBoolean(false)
    private var revisionWatch: Job? = null

    @Volatile
    private var staging: Job? = null

    @Volatile
    private var link: LinkProcess? = null

    private var entry: JournalEntry? = null

    fun start() {
        scope.launch { for (work in fileWork) work() }
        scope.launch {
            recover()
            for (input in inbox) take(input)
        }
        scope.launch { files.clientActivity.collect { post(ClientActivity(it)) } }
        readLocalPlayer()
        scope.launch { readInstalled() }
        scope.launch {
            hostFolders.collect {
                foldersShown = it
                readFolders()
            }
        }
    }

    /** [restartAccepted] is the [restartsDeadlock] the player was shown as they pressed, so the session restarts Deadlock for it without asking again. */
    fun host(mode: Mode, choices: Choices, restartAccepted: RestartReason?, outreach: Outreach = Outreach.Private) = post(HostRequested(mode, choices, restartAccepted, outreach))

    /** Ignored when no game is hosted. */
    fun invite(to: Set<SteamId>) = post(SessionEvent.Invited(to))

    /** Lists or unlists the hosted game in Servers. Ignored when no game is hosted. */
    fun list(listing: Listing) = post(SessionEvent.Listed(listing))

    /**
     * Joins a game already known by its code and offer. A feed mode's offer is accepted ahead, so the join skips the
     * review when the host still serves it, as Join again does. A folder mode's always reviews before its download.
     */
    fun join(ticket: JoinTicket) = post(InviteOpened(ticket.code, acceptedBefore = ticket.offer.takeIf { it.content is OfferContent.Feed }))

    /** Who plays on this computer, as Steam last said, once read. */
    val localPlayer: LocalPlayer? get() = me

    fun openInvite(pasted: String): Boolean {
        val code = InviteCode.parse(pasted) ?: return false
        inbox.trySend(Input.Link(code))
        return true
    }

    /**
     * A link from outside Hideout, safe to call from any thread. It goes no further than [Session.Confirming], and a link
     * Hideout can't read or can't act on is said in [linkProblem]. [text] is secret and goes in no log.
     */
    fun openLink(text: String) {
        inbox.trySend(Input.Link(InviteCode.parse(text)))
    }

    fun confirmJoin(restartAccepted: RestartReason?) = post(JoinConfirmed(restartAccepted))

    /** The one button of [Session.RestartNeeded]: closes Deadlock, sets it up, then goes on. */
    fun restartGame() = post(RestartConfirmed)

    /** Sends this computer's Deadlock into the session's server, starting it first if it is closed. Never closes it. */
    fun play() = post(PlayRequested)

    fun stop() = post(StopRequested)

    fun dismissEnded() = post(Dismissed)

    /**
     * Does nothing unless the session has ended.
     */
    fun again() {
        inbox.trySend(Input.Again)
    }

    fun dismissLinkProblem() {
        publishedLinkProblem.value = null
    }

    /** Fetches the feed's newest packages of [id] and records them. Ignored while a session is held or another fetch runs. */
    fun update(id: ModeId) {
        inbox.trySend(ModeUpkeep.Update(id))
    }

    fun updateAll() {
        inbox.trySend(ModeUpkeep.Update(null))
    }

    /** Deletes a mode's packages and its record. Ignored while a session is held, so nothing a helper runs from goes. */
    fun remove(id: ModeId) {
        inbox.trySend(ModeUpkeep.Remove(id))
    }

    /** Adds a mode's build folder, the one holding its `mod.json`, so it stays in the library until removed. Custom games are in use from then on. */
    fun addFolder(folder: String) = changeFolders(using = true) { if (folder in it) it else it + folder }

    fun removeFolder(path: String) = changeFolders(using = false) { it - path }

    private fun changeFolders(using: Boolean, change: (List<String>) -> List<String>) {
        scope.launch {
            folderList.withLock { logged("save the mode folders") { platform.folders.write(change(platform.folders.read())) } }
            readFolders()
            if (using) usingCustomGames() else lastRemovalEndsTheUse()
            upkeepGame()
        }
    }

    private suspend fun usingCustomGames() {
        logged("record that custom games are in use") { files.useCustomGames(true) }
    }

    private suspend fun lastRemovalEndsTheUse() {
        logged("record that custom games are not in use") { if (platform.installed().isEmpty() && platform.folders.read().isEmpty()) files.useCustomGames(false) }
    }

    private suspend fun readFolders() {
        if (!foldersShown) return publishedModes.update { it.copy(folders = null) }
        val paths = folderList.withLock { logged("read the mode folders") { platform.folders.read() } } ?: return
        val read = paths.map { LibraryEntry.Folder(it, logged("read a mode folder") { platform.readFolder(it) } ?: FolderRead.NotThere) }
        publishedModes.update { it.copy(folders = read) }
    }

    /**
     * Asks the feed, unless it is being asked already. The page calls this as it opens, so a player who never turns custom
     * games on makes no request. The answer goes into [modes], and the last good feed stays through any problem.
     */
    fun refreshModes() {
        readLocalPlayer()
        readRestartAhead()
        scope.launch { readFolders() }
        val source = feed ?: return
        if (publishedModes.getAndUpdate { it.copy(refreshing = true) }.refreshing) return
        scope.launch {
            if (publishedModes.value.feed == null) source.lastGood().let { kept -> changeModes { it.withNewest(kept) } }
            val fetched = source.fetch()
            changeModes { it.withNewest((fetched as? CustomGamesFetch.Fresh)?.feed).copy(refreshing = false, problem = fetched as? CustomGamesFetch.Problem) }
            upkeepGame()
        }
    }

    /** Asks the helper to stop as Hideout exits. The journal stays, so the next start checks that it did. */
    override fun close() {
        inbox.trySend(Input.CloseUsage)
        val running = link ?: return
        runBlocking { withTimeoutOrNull(ExitStopLimit) { running.stop() } }
    }

    private fun GameRecord.playedMode(): ModeId? = when (role) {
        is Role.Host -> role.mode.id.takeIf { role.mode.origin is ModeOrigin.Feed }
        is Role.Guest -> role.offer?.takeIf { it.content is OfferContent.Feed }?.mode
    }

    private fun stampPlayed(id: ModeId) {
        scope.launch {
            logged("note when a mode was played") { platform.played(id) }
            readInstalled()
        }
    }

    private fun readLocalPlayer() {
        scope.launch { logged("read who plays here") { platform.localPlayer() }?.let { me = it } }
    }

    private fun post(event: SessionEvent) {
        inbox.trySend(Input.Event(event))
    }

    private suspend fun recover() {
        val left = logged("read the journal") { platform.journal.read() } ?: return
        advance(CrashFound)
        logged("end what a crash left") { platform.endLeftover(left) }
        queued {
            entry = left
            left.game?.let { files.resume(it) }
            null
        }
        advance(Recovered)
    }

    private suspend fun take(input: Input) {
        when (input) {
            is Input.Event -> advance(input.event)
            is Input.Link -> openLink(input.code)
            is Input.FromLink -> if (input.process === link) advance(input.event)
            is Input.Prepared -> if (input.job === prepareJob) {
                prepareJob = null
                publishedProgress.value = null
                advance(input.answer)
                scope.launch { readInstalled() }
            }
            Input.Again -> again(publishedLive.value)
            Input.CloseUsage -> { usage?.play?.close(); usage = null }
            is ModeUpkeep -> upkeep(input)
        }
    }

    private suspend fun again(live: LiveSession) {
        if (live.session !is Session.Ended) return
        when (val role = live.game?.role) {
            is Role.Host -> advance(HostRequested(role.mode, role.choices, outreach = role.outreach))
            is Role.Guest -> advance(InviteOpened(role.code, acceptedBefore = role.offer))
            null -> Unit
        }
    }

    private fun upkeep(work: ModeUpkeep) {
        if (!noSession() || upkeepJob?.isActive == true) return
        upkeepJob = scope.launch {
            when (work) {
                is ModeUpkeep.Remove -> outsideSession {
                    logged("remove a mode") { platform.remove(work.id, publishedModes.value.feed) }
                    lastRemovalEndsTheUse()
                }
                is ModeUpkeep.Update -> {
                    val modes = publishedModes.value
                    val feed = modes.feed ?: return@launch
                    val wanted = work.id?.let { id -> listOfNotNull(feed.mode(id)) } ?: modes.installed.values.mapNotNull(modes::updateOf)
                    for (mode in wanted) outsideSession {
                        publishedModes.update { it.copy(updating = mode.id to null) }
                        val problem = try {
                            platform.install(mode, feed) { transfer -> publishedModes.update { it.copy(updating = mode.id to transfer) } }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("Custom games: could not update a mode: ${e::class.simpleName}")
                            FetchProblem.Disk
                        }
                        publishedModes.update { it.copy(updating = null, failed = if (problem == null) it.failed - mode.id else it.failed + (mode.id to problem)) }
                        if (problem == null) usingCustomGames() else log(updateFailed(mode, problem))
                    } ?: break
                }
            }
            readInstalled()
            upkeepGame()
        }
    }

    private fun noSession(): Boolean = publishedLive.value.session.startsHost

    private suspend fun <T> outsideSession(change: suspend () -> T): T? = storeChanges.withLock { if (noSession()) change() else null }

    private fun changeModes(change: (ModeList) -> ModeList) {
        val before = publishedModes.getAndUpdate(change)
        feedLog(before, change(before)).forEach(log)
    }

    private suspend fun readInstalled() {
        val found = logged("read the installed modes") { platform.installed() } ?: return
        val before = publishedModes.getAndUpdate { it.copy(installed = found) }.installed
        installedLog(before.takeIf { installedRead.getAndSet(true) }, found).forEach(log)
    }

    private suspend fun openLink(code: InviteCode?) {
        code?.let { advance(InviteOpened(it)) }
        val taken = when (val now = publishedLive.value.session) {
            is Session.Finding -> now.code
            is Session.Confirming -> now.invite.code
            is Session.ReleasingFiles -> now.next
            else -> null
        }
        publishedLinkProblem.value = when {
            code == null -> LinkProblem.Unreadable
            taken == code -> null
            else -> LinkProblem.SessionRunning
        }
        publishedLink.value = ++lastLinkSerial
    }

    private suspend fun advance(event: SessionEvent) {
        if (event is LinkEvent.Stopped) link = null
        if (event is ClientActivity) {
            client = event.activity
            if (event.activity == GameActivity.Closed) menusFailedThisRun = false
        }
        if (event == LinkEvent.Entered || event == ClientActivity(GameActivity.Closed)) booting?.cancel()
        val before = publishedLive.value
        val step = reduce(before.session, event, client, DeadlineToken(++lastToken), booting?.isActive == true)
        val context = RecordContext(me, { offer -> publishedModes.value.shownFor(offer)?.listing }, Clock.System.now())
        val record = recordAfter(before.game, event, step.session, context)
        if (step.session is Session.HostPreparing && before.session !is Session.HostPreparing || step.session is Session.Finding && before.session !is Session.Finding) {
            usage?.attempt?.finish(UsageOutcome.Cancelled, UsageStage.Prepare)
            usage?.play?.close()
            usage = analytics?.begin(if (step.session is Session.HostPreparing) UsageOperation.CustomGameHost else UsageOperation.CustomGameJoin)?.let(::UsageRun)
        }
        usage?.let { run ->
            val playing = step.session.own == OwnGame.In
            if (playing && run.play == null) {
                val role = record?.role
                val mode = when (role) {
                    is Role.Host -> role.mode
                    is Role.Guest -> role.offer?.let(publishedModes.value::forGuest)
                    null -> null
                }?.let(PublicUsageMode::from)
                if (mode != null) run.play = analytics?.beginPlay(mode, if (role is Role.Host) UsagePlayRole.Host else UsagePlayRole.Guest)
            }
            run.play?.setPlaying(playing)
            run.stage = when (step.session) {
                is Session.HostPreparing, is Session.GuestPreparing, is Session.SettingUp,
                is Session.RestartNeeded, is Session.Restarting, is Session.HostAdmitting, is Session.GuestAdmitting -> UsageStage.Prepare
                is Session.Fetching -> UsageStage.Download
                is Session.HostStarting, is Session.Hosting, is Session.Finding, is Session.Linking, is Session.Playing -> UsageStage.Connect
                else -> run.stage
            }
        }
        if (before.game?.started != true && record?.started == true) usage?.attempt?.finish(UsageOutcome.Completed, UsageStage.Connect)
        if (step.session is Session.Ended && before.session !is Session.Ended) {
            usage?.attempt?.finish(if (step.session.reason == EndReason.PlayerStopped) UsageOutcome.Cancelled else UsageOutcome.Failed,
                usage?.stage ?: UsageStage.Unknown,
                when (step.session.reason) { is EndReason.TimedOut -> UsageFailure.Timeout; is EndReason.LinkFailed -> UsageFailure.Network; is EndReason.Refused -> UsageFailure.Unavailable; EndReason.PlayerStopped -> null; else -> UsageFailure.Other })
            usage?.play?.close()
            usage = null
        }
        val after = LiveSession(step.session, record)
        publishedLive.value = after
        customGamesLog(before, after).forEach(log)
        if (before.game?.started != true && record?.started == true) record.playedMode()?.let(::stampPlayed)
        val menusFailed = step.session.own == OwnGame.Out(EntryProblem.MenusDidNotStart)
        if (menusFailed) menusFailedThisRun = true
        step.commands.forEach { execute(it) }
        if (step.session !is Session.HostStarting && step.session !is Session.Hosting) revisionWatch?.cancel()
        if (!step.session.admitting) staging?.cancel()
        if (event is ClientActivity || menusFailed) readRestartAhead()
        if (event == ClientActivity(GameActivity.Closed) || (step.session is Session.Ended && before.session !is Session.Ended)) upkeepGame()
    }

    private fun upkeepGame() = queued {
        if (noSession() && client == GameActivity.Closed) logged("look after the game's custom games files") {
            val game = files.game() ?: return@logged
            when (files.gamesLines()) {
                GamesLines.Pending, GamesLines.Removed -> files.useCustomGames(true)
                GamesLines.Leaving -> files.useCustomGames(false)
                GamesLines.Mounted, GamesLines.Off, is GamesLines.Unreadable -> Unit
            }
            when (files.gamesLines()) {
                GamesLines.Off -> platform.removeGamesFolder(game)
                is GamesLines.Unreadable -> Unit
                GamesLines.Mounted, GamesLines.Pending, GamesLines.Removed, GamesLines.Leaving -> {
                    platform.menus(game, listedRenderer(), rebuild = true)
                    platform.stage(game, null)
                }
            }
        }
        readRestartAhead()
        null
    }

    private fun readRestartAhead() = queued {
        publishedRestarts.value = if (client != GameActivity.Running) null else logged("read whether Deadlock can take a mode") {
            files.game()?.let { game -> (setupOf(client, files.gamesLines(), platform.menus(game, null, rebuild = false), listedRenderer(), menusFailedThisRun, menusBlockedBy(game)) as? SetupAnswer.NeedsRestart)?.why }
        }
        null
    }

    private suspend fun listedRenderer(): Sha256? = (publishedModes.value.feed ?: feed?.lastGood())?.runtime?.renderer?.sha256

    private suspend fun setUp(renderer: Sha256?, seen: GameActivity): SessionEvent {
        val game = files.game() ?: return SetupRead(SetupAnswer.Failed(Refusal.Library(LibraryProblem.GameNotFound)))
        val written = files.useCustomGames(true)
        val client = if (written == LibraryProblem.GameRunning) GameActivity.Running else seen
        if (client == GameActivity.Closed && written != null) return SetupRead(SetupAnswer.Failed(Refusal.Library(written)))
        if (client == GameActivity.Closed) logged("clear an ended Modlock dev session") { platform.clearEndedDevSession(game) }
        val menus = platform.menus(game, renderer, rebuild = client == GameActivity.Closed)
        return SetupRead(setupOf(client, files.gamesLines(), menus, renderer, menusFailedThisRun, menusBlockedBy(game)))
    }

    private suspend fun menusBlockedBy(game: GameRoot): String? = logged("read what loads ahead of the menus") { platform.menusBlockedBy(game) }

    private suspend fun execute(command: SessionCommand) {
        when (command) {
            is Arm -> {
                timer?.cancel()
                timer = scope.launch {
                    delay(command.deadline.after)
                    post(DeadlineElapsed(command.token))
                }
            }
            JournalBegin -> {
                val process = link?.identity
                queued { journalled(JournalEntry(process = process)) }
            }
            JournalEnd -> queued {
                entry = null
                logged("clear the journal") { platform.journal.clear() }
                null
            }
            is PrepareRuntime -> prepare(command.mode, command.choices)
            is PrepareGuest -> prepareGuest(command.offer)
            CancelPrepare -> {
                prepareJob?.cancel()
                prepareJob = null
                publishedProgress.value = null
            }
            is SetUpGame -> {
                val seen = client
                queued({ SetupRead(SetupAnswer.Failed(Refusal.Other(it))) }) { setUp(command.renderer, seen) }
            }
            is Admit -> queued({ AdmissionRefused(Refusal.Other(it)) }) admit@{
                command.content.shared?.let { platform.placeShared(it) }?.let { return@admit AdmissionRefused(Refusal.Preflight(PreflightProblem.Download(it))) }
                when (val admission = files.admit()) {
                    is SessionAdmission.Held -> {
                        val owned = logged("read what the game lacks") { platform.ownedBeforeServer(admission.root) } ?: SessionOwned()
                        entry?.let { journalled(it.copy(game = admission.root, owned = owned)) }
                        if (stoppableStage(command.content, admission.root) is StageResult.Staged) Admitted(admission.root) else StageFailed(Refusal.ContentNotStaged)
                    }
                    is SessionAdmission.Refused -> AdmissionRefused(Refusal.Library(admission.problem))
                }
            }
            is SpawnHost -> {
                spawn { platform.startHost(command.host, command.game, command.port, command.setup) }
                watchRevision(command.game)
            }
            is FindGame -> spawn { platform.startJoin(command.code) }
            is JoinLink -> link?.let { running -> scope.launch { logged("tell the helper to join") { running.join(command.setup, command.shared) } } }
            StopLink -> link?.let { running -> scope.launch { logged("stop the helper") { running.stop() } } }
            AbandonLink -> link?.let { running ->
                link = null
                scope.launch { logged("stop the helper") { running.stop() } }
            }
            KillLink -> link?.let { running -> scope.launch { logged("kill the helper") { running.kill() } } }
            LaunchClient -> {
                booting?.cancel()
                booting = scope.launch { delay(EnterWindow.Launched) }
                queued({ LaunchFailed(Refusal.Other(it)) }) { files.launchClient()?.let { LaunchFailed(Refusal.Library(it)) } }
            }
            is EnterGame -> link?.let { running -> scope.launch { logged("send the game in") { running.enter(command.window) } } }
            CloseClient -> queued({ ClientCloseFailed }) {
                files.closeClient()
                null
            }
            ReleaseFiles -> queued(::ReleaseFailed) { releaseProblem()?.let(::ReleaseFailed) ?: FilesReleased }
        }
    }

    private fun queued(failed: (String) -> SessionEvent? = { null }, work: suspend () -> SessionEvent?) {
        fileWork.trySend {
            val answer = try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e.described())
            }
            answer?.let(::post)
        }
    }

    private suspend fun journalled(next: JournalEntry): SessionEvent? {
        entry = next
        logged("write the journal") { platform.journal.write(next) }
        return null
    }

    private fun prepare(mode: Mode, choices: Choices) {
        val newest = publishedModes.value.feed
        preparing { progress ->
            platform.prepareHost(mode, choices, newest, progress).also { if (it is RuntimePrepared) me = it.host.me }
        }
    }

    private fun prepareGuest(offer: InviteOffer) = preparing { progress ->
        val known = publishedModes.value.let { modes -> if (modes.feed == null) modes.withNewest(feed?.lastGood()) else modes }
        val found = known.forGuest(offer)
        val client = (offer.content as? OfferContent.Feed)?.client
        if (found != null || client == null) return@preparing platform.prepareGuest(found, (offer.content as? OfferContent.Host)?.pack, known.feed, progress)
        when (val fetched = feed?.fetch()) {
            null -> RuntimeFailed(Refusal.Preflight(PreflightProblem.NoModeList))
            is CustomGamesFetch.Problem -> RuntimeFailed(Refusal.Preflight(PreflightProblem.ListUnreachable))
            is CustomGamesFetch.Fresh -> {
                val fresh = publishedModes.updateAndGet { it.withNewest(fetched.feed).copy(problem = null) }
                fresh.forGuest(offer)?.let { platform.prepareGuest(it, null, fresh.feed, progress) } ?: RuntimeFailed(Refusal.Preflight(PreflightProblem.ModeNotListed))
            }
        }
    }

    private fun preparing(work: suspend (progress: (Transfer) -> Unit) -> PrepareAnswer) {
        prepareJob = scope.launch {
            val job = currentCoroutineContext().job
            storeChanges.withLock {}
            val answer = try {
                work { transfer -> if (job.isActive) publishedProgress.value = transfer }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RuntimeFailed(Refusal.Other(e.described()))
            }
            inbox.trySend(Input.Prepared(job, answer))
        }
    }

    private suspend fun stoppableStage(content: ModeContent, game: GameRoot): StageResult? {
        val work = scope.async(start = CoroutineStart.LAZY) { logged("stage the mode") { platform.stage(game, content) } }
        staging = work
        if (!publishedLive.value.session.admitting) work.cancel()
        return try {
            work.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            null
        }
    }

    private suspend fun releaseProblem(): String? {
        val game = entry?.game
        val owned = entry?.owned
        game?.let { files.resume(it) }
        files.release()?.let { return it.reason() }
        if (game == null) return null
        (platform.stage(game, null) as? StageResult.Failed)?.let { return it.problem }
        if (owned != null && !platform.removeLeftovers(game, owned)) return LeftoversStand
        return null
    }

    /** Steam says nothing to a program it refuses, so a client that never ran is explained by what Steam's own record of the game says. */
    private suspend fun expired(event: LinkEvent.NotEntered): SessionEvent {
        val game = entry?.game
        val updating = client != GameActivity.Running && game != null && logged("read the game's update state") { platform.gameUpdatePending(game) } == true
        return EnterExpired(event.siteUnreachable, updating)
    }

    private fun spawn(start: () -> LinkProcess) {
        val process = try {
            start()
        } catch (e: Exception) {
            log("Custom games: the helper did not start: ${e::class.simpleName}")
            post(LinkEvent.Stopped(LinkStop.Unknown, e.described()))
            return
        }
        link = process
        queued { entry?.let { journalled(it.copy(process = process.identity)) } }
        scope.launch { follow(process) }
    }

    private suspend fun follow(process: LinkProcess) {
        val last = logged("read the helper") {
            process.events.onEach { event ->
                when (event) {
                    is LinkEvent.Stopped -> Unit
                    is LinkEvent.NotEntered -> queued { expired(event) }
                    else -> inbox.trySend(Input.FromLink(process, event))
                }
            }.firstOrNull { it is LinkEvent.Stopped }
        }
        inbox.trySend(Input.FromLink(process, last ?: LinkEvent.Stopped(LinkStop.Unknown)))
    }

    private fun watchRevision(game: GameRoot) {
        revisionWatch = scope.launch {
            var started = logged("read the game's revision") { platform.gameRevision(game) }
            while (isActive) {
                delay(RevisionCheck)
                val now = logged("read the game's revision") { platform.gameRevision(game) } ?: continue
                if (started == null) started = now
                if (now != started) post(GameUpdated)
            }
        }
    }

    private suspend fun <T> logged(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("Custom games: could not $what: ${e::class.simpleName}")
        null
    }

    private fun Exception.described(): String = message ?: this::class.simpleName.orEmpty()

    private val Session.admitting: Boolean get() = this is Session.HostAdmitting || this is Session.GuestAdmitting

    private companion object {
        const val LeftoversStand = "some of the files this game left could not be removed"
        val RevisionCheck = 1.minutes
        val ExitStopLimit = 2.seconds
    }
}
