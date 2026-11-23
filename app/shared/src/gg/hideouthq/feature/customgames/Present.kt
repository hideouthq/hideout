package gg.hideouthq.feature.customgames

import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.EntryProblem
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.FileRelease
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.Install
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.LateStage
import gg.hideouthq.core.customgames.LibraryEntry
import gg.hideouthq.core.customgames.LinkFailure
import gg.hideouthq.core.customgames.LinkPath
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.ModeArt
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.OfferContent
import gg.hideouthq.core.customgames.OwnGame
import gg.hideouthq.core.customgames.Player
import gg.hideouthq.core.customgames.PreflightProblem
import gg.hideouthq.core.customgames.Refusal
import gg.hideouthq.core.customgames.RestartReason
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.SectionKind
import gg.hideouthq.core.customgames.SectionState
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.download
import gg.hideouthq.core.customgames.library
import gg.hideouthq.core.customgames.slotOf
import gg.hideouthq.core.designsystem.component.StepStatus
import gg.hideouthq.core.library.LibraryProblem
import kotlin.time.Duration
import kotlin.time.Instant

/** What the session shows outside the library, from one snapshot. */
data class Present(val game: GameView?, val join: JoinView?, val chip: HostingChipView?)

data class PresentInputs(
    val live: LiveSession,
    val restartsDeadlock: RestartReason? = null,
    val transfer: Transfer? = null,
    val modes: ModeList = ModeList(),
    val firewallMayAsk: Boolean = false,
    val site: SiteOrigin? = null,
)

/**
 * The game screen once there is a game to follow, the join dialog while a guest's game hasn't connected, and the top
 * bar's hosting chip while a session runs and, once it has ended, until [chipAway] says the game screen showed that
 * end. A guest moves from [Present.join] to [Present.game] as
 * [GameRecord.started] turns true. Files going back are the exception: the dialog can be put away while they do, and a
 * crash's cleanup has no dialog, so the game screen shows them too and the chip opens it, and a cleanup that fails stays
 * in reach. An end before the start that nothing went wrong in, as a cancel, shows nothing.
 */
fun present(inputs: PresentInputs): Present {
    val (session, record) = inputs.live
    if (record == null) return cleanupOnly(leftoverView(session), afterCleanup(session, inputs.modes), session)
    if (record.role is Role.Guest && !record.started) {
        val cleanup = ending(session)?.let { gameView(session, record, inputs) }
        return cleanupOnly(cleanup, joinView(session, record, inputs), session)
    }
    val game = gameView(session, record, inputs) ?: return Present(null, null, null)
    return Present(game, null, chipView(game, session, record))
}

/**
 * The chip on a page that isn't the game's. A game that ended while the player was elsewhere keeps its chip, which
 * opens the result, until the game screen has shown the end stamped [seenEnd].
 */
fun chipAway(present: Present, endedAt: Instant?, seenEnd: Instant?): HostingChipView? = present.chip?.takeIf { endedAt == null || endedAt != seenEnd }

private fun cleanupOnly(game: GameView?, join: JoinView?, session: Session): Present {
    val cleaning = session == Session.Recovering || session is Session.ReleasingFiles || session is Session.Stopping
    val chip = if (cleaning) HostingChipView(HostingTitle.CleaningUp, null, game?.status ?: Status.CleaningUp, null, null, null, opens = game != null) else null
    return Present(game, join, chip)
}

private fun leftoverView(session: Session): GameView? {
    if (session !is Session.ReleasingFiles) return null
    val (_, stage) = ending(session) ?: return null
    val face = ModeFace(null, null, null, SeedOfUnknown, emptyList())
    return GameView(face, null, endingStatus(stage, Status.CleaningUp), Body.Ending(stage, EndingOf.LastGame), null, null, emptyList())
}

private fun endingStatus(stage: EndingStage, going: Status) = if (stage is EndingStage.Failed) Status.CleanupFailed else going

enum class Tone { Starting, Ready, Match, Warn, Fail, Ended }

/** The pill and the hosting chip's dot and status line. */
enum class Status(val tone: Tone, val busy: Boolean = false) {
    CleaningUp(Tone.Starting, busy = true),

    /** Files that couldn't go back wait on the player's Try again. */
    CleanupFailed(Tone.Fail),
    Starting(Tone.Starting, busy = true),
    RestartNeeded(Tone.Warn),
    Restarting(Tone.Starting, busy = true),
    Joining(Tone.Starting, busy = true),
    Ready(Tone.Ready),
    InMatch(Tone.Match),
    LostConnection(Tone.Warn),
    Ending(Tone.Starting, busy = true),
    Leaving(Tone.Starting, busy = true),
    CouldNotStart(Tone.Fail),
    Ended(Tone.Ended),
}

/** The mode as a screen names it. [title], [version] and [art] are null for a guest whose list doesn't name it; [seed] tints placeholder art. */
data class ModeFace(val title: String?, val version: String?, val art: ModeArt?, val seed: String, val picks: List<String>)

enum class StepName { GetMode, StartServer, OpenToFriends, Connect, SendGameIn }

sealed interface StepNote {
    data class Downloading(val transfer: Transfer) : StepNote
    data class Installed(val version: String) : StepNote
    data object SettingUpFiles : StepNote
    data object Direct : StepNote
    data object BackupRoute : StepNote
    data object OpeningDeadlock : StepNote
    data object JoiningGame : StepNote
}

data class StepView(val name: StepName, val status: StepStatus, val note: StepNote? = null)

/**
 * [players] null means a game that is ending, ended or never started. With players shown, the right column has the invite
 * for a host and the note for either role, whose copy follows [host], which is null for a crash's cleanup.
 */
data class GameView(
    val face: ModeFace,
    val host: HostedBy?,
    val status: Status,
    val body: Body,
    val players: PlayersView?,
    val invite: InviteView?,
    /** Who End game would cut off, asked about first. Empty ends at once, since nobody else is there. */
    val endCutsOff: List<String>,
)

sealed interface HostedBy {
    data object ThisPc : HostedBy
    data class Friend(val name: DisplayName?) : HostedBy
}

sealed interface Body {
    data class Starting(val steps: List<StepView>, val firewallHint: Boolean) : Body

    data class RestartNeeded(val why: RestartReason) : Body

    data class Restarting(val closeFailed: Boolean) : Body

    /** Open and not full, with [friends] already in it. */
    data class Open(val friends: Int) : Body

    /** Full, or the mode reported something to show. [sections] are the listed sections with state, in listing order. */
    data class Match(val sections: List<SectionView>) : Body

    data class Entering(val joining: Boolean) : Body

    /** [canPlay] is false while the host isn't answering. */
    data class NotIn(val problem: EntryProblem?, val canPlay: Boolean) : Body

    data class Ending(val stage: EndingStage, val of: EndingOf) : Body

    /** [lastMatch] is null when nobody joined or the mode reported no score. */
    data class Ended(val outcome: Outcome, val lastMatch: LastMatch?, val side: EndedSide) : Body
}

/** The scoreboard as the game left it, a row per player, best first. [note] is the mode's own first line, such as "First to 25". */
data class LastMatch(val note: String?, val rows: List<MatchRow>)

data class MatchRow(val person: Person, val score: Int, val finish: Finish)

/** Hideout knows no mode's rules, so the one side ahead when the game ended won it, and sides level at the top tie. */
enum class Finish { Won, Level, Behind }

sealed interface EndedSide {
    /** Installed modes first, then the feed's order. Empty when there is no other mode to host. */
    data class PlaySomethingElse(val modes: List<LibraryEntry.Listed>) : EndedSide

    /** [details] go to the clipboard for a bug report, never on screen. */
    data class StillNotWorking(val details: String) : EndedSide
}

/** A host's start, in order. A guest's start has its own dialog. */
enum class HostStep { GetMode, StartServer, OpenToFriends }

/** The steps that worked, in order, then the one that failed. [download] is null for a folder's mode. */
data class StartTrace(val worked: List<HostStep>, val failed: HostStep, val download: Long?)

/** Whose end a closing game's title names. [LastGame] is a cleanup with no game to name: a crash's, or a join's that never connected. */
enum class EndingOf { YourGame, YourGameStopped, YouLeft, FriendsGame, LastGame }

sealed interface EndingStage {
    data object Stopping : EndingStage
    data object PuttingFilesBack : EndingStage

    data class Failed(val details: String) : EndingStage
}

sealed interface Outcome {
    /** [lasted] is from the start to [at]. [sentBack] are the others signed on when it ended. */
    data class YouEnded(val at: Instant?, val lasted: Duration?, val sentBack: List<String>) : Outcome

    data object GameStopped : Outcome

    data object DeadlockUpdated : Outcome

    data object FriendEnded : Outcome

    data object FriendsGameStopped : Outcome

    data object ConnectionLost : Outcome

    data object UnknownEnd : Outcome

    /** A guest who pressed Leave game. Only the host ends a game for everyone. */
    data object YouLeft : Outcome

    /** A guest whose own Deadlock closed in the match. Join again is `again()` with the same invite. */
    data object DeadlockClosed : Outcome

    /** [trace] is null for an end no host's start comes to. */
    data class CouldNotStart(val problem: Problem, val trace: StartTrace?) : Outcome
}

/**
 * Why a game never started, for a host on the game screen and a guest in the join dialog alike, so one copy table serves
 * both, the role picking a word where they differ.
 */
enum class Problem {
    DeadlockNotInstalled,
    SteamUnsupported,
    NoSteamAccount,
    DeadlockUpdated,
    NoModeList,
    ModeListUnreachable,
    NothingToHostWithHere,
    FolderMissing,
    DifferentVersion,
    DownloadStopped,
    DiskFull,
    FilesDamaged,
    ContentNotStaged,
    MenusUnavailable,
    MenusBlocked,
    TookTooLong,
    CouldNotReach,
    GameNotFound,
    FriendEnded,
    InviteNotWorking,
    ArenaFailed,
    Unexpected,
}

/** The one map from an end before the start to what the player is told. Null closes quietly. */
fun problemOf(reason: EndReason): Problem? = when (reason) {
    EndReason.PlayerStopped, EndReason.Interrupted, EndReason.GameClosed -> null
    EndReason.GameUpdated -> Problem.DeadlockUpdated
    EndReason.HostEnded -> Problem.FriendEnded
    is EndReason.HostFailed, EndReason.ServerDied, EndReason.HostsGameStopped -> Problem.ArenaFailed
    is EndReason.TimedOut -> if (reason.waitingFor == LateStage.Linking) Problem.CouldNotReach else Problem.TookTooLong
    is EndReason.LinkFailed -> when (reason.why) {
        LinkFailure.GameNotFound -> Problem.GameNotFound
        LinkFailure.RelayUnavailable, LinkFailure.LinkLost -> Problem.CouldNotReach
        LinkFailure.BadInvite -> Problem.InviteNotWorking
        LinkFailure.Signal, LinkFailure.ListenFailed, LinkFailure.Unknown -> Problem.Unexpected
    }
    is EndReason.Refused -> problemOf(reason.problem)
}

private fun problemOf(refusal: Refusal): Problem = when (refusal) {
    Refusal.ContentNotStaged -> Problem.ContentNotStaged
    Refusal.MenusUnavailable -> Problem.MenusUnavailable
    is Refusal.MenusBlocked -> Problem.MenusBlocked
    is Refusal.Other -> Problem.Unexpected
    is Refusal.Library -> if (refusal.problem == LibraryProblem.GameNotFound) Problem.DeadlockNotInstalled else Problem.Unexpected
    is Refusal.Preflight -> when (val problem = refusal.problem) {
        PreflightProblem.SteamNotFound -> Problem.DeadlockNotInstalled
        PreflightProblem.SteamUnsupported -> Problem.SteamUnsupported
        PreflightProblem.NoSteamAccount -> Problem.NoSteamAccount
        PreflightProblem.FolderMissing -> Problem.FolderMissing
        PreflightProblem.NoModeList -> Problem.NoModeList
        PreflightProblem.NothingToHostWithHere -> Problem.NothingToHostWithHere
        PreflightProblem.ModeNotListed -> Problem.DifferentVersion
        PreflightProblem.ListUnreachable -> Problem.ModeListUnreachable
        is PreflightProblem.Download -> when (problem.why) {
            FetchProblem.Server, FetchProblem.Connection -> Problem.DownloadStopped
            FetchProblem.Disk -> Problem.DiskFull
            FetchProblem.Mismatch, FetchProblem.Unpack -> Problem.FilesDamaged
        }
    }
}

/** For the clipboard: what ended the game, and nothing secret. [EndReason] holds no invite, so its text is safe. */
fun details(reason: EndReason): String = "Custom game ended: $reason"

/** One section as the game screen draws it. */
sealed interface SectionView {
    data class Scoreboard(val sides: List<BoardSide>, val notes: List<String>) : SectionView
}

fun scoreOf(sections: List<SectionView>): Pair<Int, Int>? {
    val sides = sections.filterIsInstance<SectionView.Scoreboard>().firstOrNull()?.sides?.takeIf { it.size >= 2 } ?: return null
    val (mine, theirs) = yoursFirst(sides)
    return mine.score to theirs.score
}

fun yoursFirst(sides: List<BoardSide>): List<BoardSide> = sides.sortedByDescending { it.yours }

/** A slot nobody holds is left out of [people]. */
data class BoardSide(val score: Int, val people: List<Person>, val yours: Boolean)

data class Person(val name: String?, val you: Boolean, val host: Boolean)

/** [capacity] null hides the count, for a guest, who can't see who else is in. */
data class PlayersView(val rows: List<PlayerRow>, val capacity: Int?, val openSpots: Int)

/** [key] keeps a row's identity across updates, for its enter and exit motion. */
data class PlayerRow(val key: String, val person: Person, val presence: Presence, val path: LinkPath? = null)

enum class Presence { GettingReady, InGame, NotIn }

/** [code] is null until the helper gives one. [paused] dims it while the game is full. [live] is false while the server is still coming up. */
data class InviteView(val code: String?, val link: String?, val live: Boolean, val paused: Boolean)

data class JoinView(val host: DisplayName?, val face: ModeFace, val capacity: Int?, val step: JoinStep)

sealed interface JoinStep {
    data object Finding : JoinStep

    data class Review(val download: JoinDownload, val restarts: RestartReason?) : JoinStep

    /** A crash's cleanup runs first, and the dialog opens on Review once the files are back. */
    data object AfterCleanup : JoinStep

    data class Progress(val steps: List<StepView>) : JoinStep

    /**
     * The join is over and the files go back first. [problem] is why it failed, or null when the player cancelled, so a
     * failure says so at once and never waits behind "Cancelling".
     */
    data class Stopping(val stage: EndingStage, val problem: Problem?) : JoinStep

    data class RestartNeeded(val why: RestartReason) : JoinStep

    data class Restarting(val closeFailed: Boolean) : JoinStep

    /** [canPlay] is false while the host isn't answering. */
    data class NotIn(val problem: EntryProblem?, val canPlay: Boolean) : JoinStep

    data class Failed(val problem: Problem, val details: String) : JoinStep
}

sealed interface JoinDownload {
    data object None : JoinDownload

    data object OnThisPc : JoinDownload

    data class Bytes(val bytes: Long) : JoinDownload

    /** No list read here names the friend's version yet, so its size isn't known. */
    data object Unknown : JoinDownload
}

/**
 * [count] is "1 of 2" for a host. [score] is the first two scoreboard sides, the player's first. [invite] is there only
 * while someone can join with it.
 */
data class HostingChipView(val title: HostingTitle, val face: ModeFace?, val status: Status, val count: Pair<Int, Int>?, val score: Pair<Int, Int>?, val invite: InviteView?, val opens: Boolean)

sealed interface HostingTitle {
    data class Hosting(val mode: String?) : HostingTitle
    data class InGame(val host: DisplayName?) : HostingTitle
    data object CleaningUp : HostingTitle
}

private fun afterCleanup(session: Session, modes: ModeList): JoinView? {
    (session as? Session.ReleasingFiles)?.next ?: return null
    return JoinView(null, faceOf(null, modes), null, JoinStep.AfterCleanup)
}

internal fun faceOf(offer: InviteOffer?, modes: ModeList): ModeFace {
    val mode = offer?.let(modes::shownFor)
    return ModeFace(mode?.listing?.name, mode?.version, mode?.listing?.art, mode?.id?.value ?: SeedOfUnknown, offer?.choices?.labels(mode?.listing).orEmpty())
}

private fun faceOf(record: GameRecord, modes: ModeList): ModeFace = when (val role = record.role) {
    is Role.Host -> ModeFace(role.mode.listing.name, role.mode.version, role.mode.listing.art, role.mode.id.value, record.choices.labels(role.mode.listing))
    is Role.Guest -> faceOf(role.offer, modes).let { face ->
        role.listing?.let { face.copy(title = it.name, art = it.art, picks = record.choices.labels(it)) } ?: face
    }
}

internal const val SeedOfUnknown = "custom-game"

private fun gameView(session: Session, record: GameRecord, inputs: PresentInputs): GameView? {
    val face = faceOf(record, inputs.modes)
    val role = record.role
    ending(session)?.let { (reason, stage) ->
        val byYou = reason == EndReason.PlayerStopped
        val of = when {
            role is Role.Host -> if (byYou) EndingOf.YourGame else EndingOf.YourGameStopped
            !record.started -> EndingOf.LastGame
            else -> if (byYou) EndingOf.YouLeft else EndingOf.FriendsGame
        }
        val status = endingStatus(stage, if (role is Role.Host) Status.Ending else Status.Leaving)
        return GameView(face, hostedBy(role), status, Body.Ending(stage, of), null, null, emptyList())
    }
    if (session is Session.Ended) return endedView(session.reason, record, face, inputs.modes)
    return when (role) {
        is Role.Host -> hostGame(session, record, face, inputs)
        is Role.Guest -> guestGame(session, record, role, face)
    }
}

private fun ending(session: Session): Pair<EndReason, EndingStage>? = when (session) {
    is Session.Stopping -> session.reason to EndingStage.Stopping
    is Session.ReleasingFiles -> session.reason to when (val release = session.release) {
        FileRelease.Running -> EndingStage.PuttingFilesBack
        is FileRelease.Failed -> EndingStage.Failed("Putting files back failed: ${release.problem}")
    }
    else -> null
}

private fun hostedBy(role: Role): HostedBy = when (role) {
    is Role.Host -> HostedBy.ThisPc
    is Role.Guest -> HostedBy.Friend(role.offer?.host)
}

private fun endedView(reason: EndReason, record: GameRecord, face: ModeFace, modes: ModeList): GameView? {
    val role = record.role
    val host = role is Role.Host
    val outcome = when {
        !record.started -> problemOf(reason)?.let { Outcome.CouldNotStart(it, startTrace(reason, record)) } ?: return null
        reason == EndReason.GameUpdated -> Outcome.DeadlockUpdated
        reason == EndReason.PlayerStopped -> if (host) youEnded(record) else Outcome.YouLeft
        host -> Outcome.GameStopped
        reason == EndReason.GameClosed -> Outcome.DeadlockClosed
        reason == EndReason.HostEnded -> Outcome.FriendEnded
        reason == EndReason.HostsGameStopped -> Outcome.FriendsGameStopped
        reason == EndReason.LinkFailed(LinkFailure.LinkLost) -> Outcome.ConnectionLost
        else -> Outcome.UnknownEnd
    }
    val status = if (outcome is Outcome.CouldNotStart) Status.CouldNotStart else Status.Ended
    val side = when (outcome) {
        is Outcome.CouldNotStart, Outcome.GameStopped -> EndedSide.StillNotWorking(details(reason))
        else -> EndedSide.PlaySomethingElse(
            otherModes(
                modes,
                when (role) {
                    is Role.Host -> role.mode.id
                    is Role.Guest -> role.offer?.mode
                },
            ),
        )
    }
    return GameView(face, hostedBy(role), status, Body.Ended(outcome, lastMatch(record), side), null, null, emptyList())
}

private fun youEnded(record: GameRecord): Outcome.YouEnded {
    val lasted = record.startedAt?.let { start -> record.endedAt?.let { it - start } }
    val sentBack = record.players.filterKeys { it in record.signedOn }.toSortedMap().values.filter { it.steamId != record.me?.steamId }.map { it.name }
    return Outcome.YouEnded(record.endedAt, lasted, sentBack)
}

private fun lastMatch(record: GameRecord): LastMatch? {
    val board = sections(record, record.players).filterIsInstance<SectionView.Scoreboard>().firstOrNull() ?: return null
    val top = board.sides.maxOf { it.score }
    val alone = board.sides.count { it.score == top } == 1
    val rows = yoursFirst(board.sides).sortedByDescending { it.score }.flatMap { side ->
        val finish = when {
            side.score < top -> Finish.Behind
            alone -> Finish.Won
            else -> Finish.Level
        }
        side.people.map { MatchRow(it, side.score, finish) }
    }
    return LastMatch(board.notes.firstOrNull(), rows).takeIf { rows.any { !it.person.you } }
}

private fun otherModes(modes: ModeList, current: ModeId?): List<LibraryEntry.Listed> =
    library(modes).filterIsInstance<LibraryEntry.Listed>().filter { it.mode.id != current }
        .sortedByDescending { it.install != Install.NotInstalled }.take(OtherModes)

private const val OtherModes = 3

private fun startTrace(reason: EndReason, record: GameRecord): StartTrace? {
    val failed = when (reason) {
        is EndReason.Refused -> HostStep.GetMode
        is EndReason.HostFailed, EndReason.ServerDied -> HostStep.StartServer
        is EndReason.LinkFailed -> HostStep.OpenToFriends
        is EndReason.TimedOut -> when (reason.waitingFor) {
            LateStage.HostPreparing, LateStage.Admitting, LateStage.GuestPreparing -> HostStep.GetMode
            LateStage.HostStarting -> if (record.arenaLoaded) HostStep.OpenToFriends else HostStep.StartServer
            LateStage.Linking -> HostStep.OpenToFriends
        }
        EndReason.PlayerStopped, EndReason.Interrupted, EndReason.GameClosed, EndReason.GameUpdated, EndReason.HostEnded, EndReason.HostsGameStopped -> return null
    }
    val worked = HostStep.entries.takeWhile { it != failed }.filter { it != HostStep.StartServer || record.arenaLoaded }
    return StartTrace(worked, failed, (record.role as? Role.Host)?.mode?.download)
}

private fun hostGame(session: Session, record: GameRecord, face: ModeFace, inputs: PresentInputs): GameView {
    val capacity = record.capacity
    val hosting = session as? Session.Hosting
    val own = hosting?.own
    val players = players(hosting?.players.orEmpty(), record.me, capacity, own)
    val full = hosting != null && capacity != null && hosting.players.size >= capacity
    val code = when (session) {
        is Session.HostStarting -> session.code
        is Session.Hosting -> session.code
        else -> null
    }
    val invite = InviteView(code?.shown, inputs.site?.let { code?.webLink(it) }, live = hosting != null, paused = full)
    val entering = own is OwnGame.Sending || own is OwnGame.Joining
    val starting = own == null || (entering && record.players.slotOf(record.me) == null)
    val sections = sections(record, hosting?.players.orEmpty())
    val friends = others(session, record.me)
    val body = when {
        session is Session.RestartNeeded -> Body.RestartNeeded(session.why)
        session is Session.Restarting -> Body.Restarting(session.closeFailed)
        starting -> Body.Starting(hostSteps(session, inputs.transfer, face.version), inputs.firewallMayAsk && session is Session.HostStarting)
        entering -> Body.Entering(joining = own is OwnGame.Joining)
        own is OwnGame.Out -> Body.NotIn(own.problem, canPlay = true)
        full || sections.isNotEmpty() -> Body.Match(sections)
        else -> Body.Open(friends.size)
    }
    val status = when {
        session is Session.RestartNeeded -> Status.RestartNeeded
        session is Session.Restarting -> Status.Restarting
        starting -> Status.Starting
        full -> Status.InMatch
        else -> Status.Ready
    }
    return GameView(face, HostedBy.ThisPc, status, body, players, invite, friends)
}

private fun guestGame(session: Session, record: GameRecord, role: Role.Guest, face: ModeFace): GameView {
    val own = (session as? Session.Playing)?.own
    val body = when (own) {
        is OwnGame.Out -> Body.NotIn(own.problem, canPlay = session.link.answering)
        is OwnGame.Sending -> Body.Entering(joining = false)
        is OwnGame.Joining -> Body.Entering(joining = true)
        OwnGame.In, null -> Body.Match(sections(record, emptyMap()))
    }
    val rows = listOf(
        PlayerRow("host", Person(role.offer?.host?.value, you = false, host = true), Presence.InGame),
        PlayerRow("you", Person(record.me?.name?.value, you = true, host = false), presenceOf(own), session.link?.path),
    )
    val status = when (body) {
        is Body.Match -> Status.InMatch
        is Body.Entering -> Status.Joining
        else -> Status.LostConnection
    }
    return GameView(face, HostedBy.Friend(role.offer?.host), status, body, PlayersView(rows, null, 0), null, emptyList())
}

internal fun players(signedOn: Map<Int, Player>, me: LocalPlayer?, capacity: Int?, own: OwnGame?): PlayersView {
    val mine = signedOn.entries.firstOrNull { me != null && it.value.steamId == me.steamId }
    val own = when {
        mine != null -> PlayerRow("me", Person(mine.value.name, you = true, host = true), Presence.InGame)
        me != null -> PlayerRow("me", Person(me.name?.value, you = true, host = true), presenceOf(own))
        else -> null
    }
    val others = signedOn.entries.sortedBy { it.key }.filter { it != mine }
        .map { PlayerRow("slot:${it.key}", Person(it.value.name, you = false, host = false), Presence.InGame) }
    val rows = listOfNotNull(own) + others
    return PlayersView(rows, capacity, ((capacity ?: rows.size) - rows.size).coerceAtLeast(0))
}

private fun presenceOf(own: OwnGame?): Presence = when (own) {
    OwnGame.In -> Presence.InGame
    is OwnGame.Out -> Presence.NotIn
    else -> Presence.GettingReady
}

internal fun hostSteps(session: Session, transfer: Transfer?, version: String?): List<StepView> {
    val hosting = session as? Session.Hosting
    val starting = session as? Session.HostStarting
    val installed = version?.let(StepNote::Installed)
    val getMode = when (session) {
        is Session.HostPreparing -> StepView(StepName.GetMode, StepStatus.Running(transfer?.fraction), transfer?.let(StepNote::Downloading))
        is Session.SettingUp, is Session.HostAdmitting -> StepView(StepName.GetMode, StepStatus.Running(), StepNote.SettingUpFiles)
        else -> StepView(StepName.GetMode, StepStatus.Done, installed)
    }
    val server = when {
        hosting != null || starting?.map != null -> StepStatus.Done
        starting != null -> StepStatus.Running()
        else -> StepStatus.Waiting
    }
    val open = when {
        hosting != null || starting?.code != null -> StepStatus.Done
        starting?.map != null -> StepStatus.Running()
        else -> StepStatus.Waiting
    }
    return listOf(getMode, StepView(StepName.StartServer, server), StepView(StepName.OpenToFriends, open), sendGameIn(hosting?.own))
}

private fun sendGameIn(own: OwnGame?): StepView = when (own) {
    OwnGame.In -> StepView(StepName.SendGameIn, StepStatus.Done)
    is OwnGame.Sending -> StepView(StepName.SendGameIn, StepStatus.Running(), StepNote.OpeningDeadlock.takeIf { own.startingDeadlock })
    is OwnGame.Joining -> StepView(StepName.SendGameIn, StepStatus.Running(), StepNote.JoiningGame)
    is OwnGame.Out, null -> StepView(StepName.SendGameIn, StepStatus.Waiting)
}

internal fun guestSteps(session: Session, transfer: Transfer?): List<StepView> {
    val got = StepView(StepName.GetMode, StepStatus.Done)
    val route = when (session.link?.path) {
        LinkPath.Direct -> StepNote.Direct
        LinkPath.Relay -> StepNote.BackupRoute
        null -> null
    }
    val linked = StepView(StepName.Connect, StepStatus.Done, route)
    val waiting = StepView(StepName.SendGameIn, StepStatus.Waiting)
    return when (session) {
        is Session.GuestPreparing -> listOf(
            StepView(StepName.GetMode, StepStatus.Running(transfer?.fraction), transfer?.let(StepNote::Downloading)),
            StepView(StepName.Connect, StepStatus.Waiting),
            waiting,
        )
        is Session.SettingUp -> listOf(StepView(StepName.GetMode, StepStatus.Running(), StepNote.SettingUpFiles), StepView(StepName.Connect, StepStatus.Waiting), waiting)
        is Session.Linking -> {
            val mode = if (session.content.shared != null) StepView(StepName.GetMode, StepStatus.Running()) else got
            listOf(mode, StepView(StepName.Connect, StepStatus.Running()), waiting)
        }
        is Session.Fetching -> listOf(StepView(StepName.GetMode, StepStatus.Running(session.transfer?.fraction), session.transfer?.let(StepNote::Downloading)), linked, waiting)
        is Session.GuestAdmitting -> listOf(got, linked, StepView(StepName.SendGameIn, StepStatus.Running(), StepNote.SettingUpFiles))
        is Session.Playing -> listOf(got, linked, sendGameIn(session.own))
        else -> listOf(got, linked, StepView(StepName.SendGameIn, StepStatus.Running()))
    }
}

internal val Transfer.fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

private fun joinView(session: Session, record: GameRecord, inputs: PresentInputs): JoinView? {
    val role = record.role as Role.Guest
    val offer = role.offer
    val own = (session as? Session.Playing)?.own
    val step = ending(session)?.let { (reason, stage) -> JoinStep.Stopping(stage, problemOf(reason)) } ?: when {
        session is Session.Finding -> JoinStep.Finding
        session is Session.Confirming -> JoinStep.Review(download(session.invite.offer, inputs.modes), inputs.restartsDeadlock)
        session is Session.Ended -> problemOf(session.reason)?.let { JoinStep.Failed(it, details(session.reason)) } ?: return null
        session is Session.RestartNeeded -> JoinStep.RestartNeeded(session.why)
        session is Session.Restarting -> JoinStep.Restarting(session.closeFailed)
        own is OwnGame.Out -> JoinStep.NotIn(own.problem, canPlay = session.link.answering)
        else -> JoinStep.Progress(guestSteps(session, inputs.transfer))
    }
    return JoinView(offer?.host, faceOf(record, inputs.modes), offer?.capacity, step)
}

private fun download(offer: InviteOffer, modes: ModeList): JoinDownload {
    val client = when (val content = offer.content) {
        is OfferContent.Host -> return JoinDownload.Bytes(content.pack.bytes)
        is OfferContent.Feed -> content.client ?: return JoinDownload.None
    }
    if (client in modes.onDisk) return JoinDownload.OnThisPc
    val listed = (modes.forGuest(offer)?.origin as? ModeOrigin.Feed)?.client
    return listed?.let { JoinDownload.Bytes(it.bytes) } ?: JoinDownload.Unknown
}

internal fun sections(record: GameRecord, players: Map<Int, Player>): List<SectionView> =
    record.listing?.sections.orEmpty().mapNotNull { kind ->
        when (kind) {
            SectionKind.Scoreboard -> (record.reported[kind] as? SectionState.Scoreboard)?.let { board -> scoreboard(board, players, record.me, players.slotOf(record.me) ?: record.players.slotOf(record.me)) }
        }
    }

private fun scoreboard(board: SectionState.Scoreboard, players: Map<Int, Player>, me: LocalPlayer?, mine: Int?): SectionView.Scoreboard {
    val sides = board.sides.map { side ->
        val people = side.slots.mapNotNull { slot -> players[slot]?.let { Person(it.name, you = it.steamId == me?.steamId, host = it.steamId == me?.steamId) } }
        BoardSide(side.score, people, yours = mine != null && mine in side.slots)
    }
    return SectionView.Scoreboard(sides, board.notes)
}

private fun chipView(game: GameView, session: Session, record: GameRecord): HostingChipView {
    val title = when (val role = record.role) {
        is Role.Host -> HostingTitle.Hosting(game.face.title)
        is Role.Guest -> HostingTitle.InGame(role.offer?.host)
    }
    val count = game.players?.takeIf { session is Session.Hosting }?.let { players -> players.capacity?.let { players.rows.size to it } }
    val score = (game.body as? Body.Match)?.sections?.let(::scoreOf)
    val invite = game.invite?.takeIf { it.live && !it.paused && it.code != null }
    return HostingChipView(title, game.face, game.status, count, score, invite, opens = true)
}

/** Null quits at once. */
enum class QuitWarning { EndsHostedGame, LeavesGame, ModsComeBackLater }

fun quitWarning(live: LiveSession): QuitWarning? = when (live.session) {
    Session.Idle, Session.Recovering, is Session.Ended, is Session.Finding, is Session.Confirming -> null
    is Session.Stopping, is Session.ReleasingFiles -> QuitWarning.ModsComeBackLater
    else -> if (live.game?.role is Role.Host) QuitWarning.EndsHostedGame else QuitWarning.LeavesGame
}

/** The custom game a restart of Hideout would end, which an update waits behind and warns about. */
sealed interface GameInProgress {
    data class Hosting(val mode: String?, val others: List<String>) : GameInProgress
    data class Guest(val host: DisplayName?) : GameInProgress
}

fun gameInProgress(live: LiveSession): GameInProgress? = when (quitWarning(live)) {
    QuitWarning.EndsHostedGame -> GameInProgress.Hosting((live.game?.role as? Role.Host)?.mode?.listing?.name, others(live.session, live.game?.me))
    QuitWarning.LeavesGame -> GameInProgress.Guest((live.game?.role as? Role.Guest)?.offer?.host)
    QuitWarning.ModsComeBackLater, null -> null
}

private fun others(session: Session, me: LocalPlayer?): List<String> =
    (session as? Session.Hosting)?.players.orEmpty().values.filter { it.steamId != me?.steamId }.map { it.name }
