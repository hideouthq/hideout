package gg.hideouthq.core.customgames

import gg.hideouthq.core.customgames.SessionCommand.*
import gg.hideouthq.core.customgames.SessionEvent.*
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.LibraryProblem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A player is in one session at a time, and the way out is the same for both roles, so host and guest share one machine. */
sealed interface Session {
    /** Set while a deadline is armed for this state. An elapsed deadline with any other token is stale. */
    val deadline: DeadlineToken? get() = null

    /** Set from when a guest's helper has linked. */
    val link: GuestLink? get() = null

    data object Idle : Session

    /** Ending what a crash left, while the old helper goes. Nothing can start, stop or be dismissed until that is done. */
    data object Recovering : Session

    /** Downloading what the mode needs. This can take long, so nothing is held yet and the player's mods stay free. */
    data class HostPreparing(override val deadline: DeadlineToken, val restartAccepted: RestartReason? = null) : Session

    /**
     * Making this computer's Deadlock able to take a mode: the pack and the lines written while it is closed, or read while it runs. Holds nothing.
     * A running Deadlock that needs the restart the player was told of as they started, [restartAccepted], is closed without asking again.
     */
    data class SettingUp(val next: Prepared, val restartAccepted: RestartReason? = null) : Session

    /** The running Deadlock can't take a mode for a reason the player wasn't told of. Waits for the player, as [Confirming] does, so it has no deadline. */
    data class RestartNeeded(val next: Prepared, val why: RestartReason) : Session

    /** Closing Deadlock for the restart the player accepted. [closeFailed] leaves the close to the player, and the setup follows any close. */
    data class Restarting(val next: Prepared, val closeFailed: Boolean = false, override val deadline: DeadlineToken? = null) : Session

    /** Holding the files and staging the mode, before the server reads it. */
    data class HostAdmitting(val host: PreparedHost, val port: Int, val setup: GameSetup, override val deadline: DeadlineToken) : Session

    data class HostStarting(
        val code: InviteCode?,
        val map: String?,
        override val deadline: DeadlineToken,
        val guests: GuestPaths = GuestPaths(),
    ) : Session

    /**
     * [players] holds who signed on, by slot. Humans only, the host's own game among them, since `joined` cannot tell
     * them apart. [own] is this computer's own game.
     */
    data class Hosting(
        val code: InviteCode,
        val map: String,
        val players: Map<Int, Player> = emptyMap(),
        val own: OwnGame,
        val guests: GuestPaths = GuestPaths(),
    ) : Session {
        override val deadline: DeadlineToken? get() = own.deadline
    }

    data class Finding(val code: InviteCode, override val deadline: DeadlineToken, val acceptedBefore: InviteOffer? = null) : Session

    data class Confirming(val invite: Invite) : Session

    data class GuestPreparing(val invite: Invite, override val deadline: DeadlineToken, val restartAccepted: RestartReason? = null) : Session

    data class Linking(val content: ModeContent, override val deadline: DeadlineToken) : Session

    /** Linked, with the host's pack on its way. [transfer] is how much has arrived. */
    data class Fetching(val content: ModeContent, override val link: GuestLink, val transfer: Transfer? = null) : Session

    /** Holding the files and staging the mode. The running game is fine here: nothing it holds is written. */
    data class GuestAdmitting(val content: ModeContent, override val link: GuestLink, override val deadline: DeadlineToken) : Session

    data class Playing(override val link: GuestLink, val own: OwnGame) : Session {
        override val deadline: DeadlineToken? get() = own.deadline
    }

    data class Stopping(val reason: EndReason, val hold: FileHold, override val deadline: DeadlineToken) : Session

    /**
     * Drops the hold and empties the mode's folder after the helper stopped. Never waits for the client, since nothing it
     * writes is one a game holds. [next] is the newest code to open once it is done, kept only while this cleans up
     * after an [EndReason.Interrupted] game.
     */
    data class ReleasingFiles(val reason: EndReason, val release: FileRelease, val next: InviteCode? = null) : Session

    data class Ended(val reason: EndReason) : Session
}

/** What a preparation produced, kept through the setup and any restart. [renderer] is what the menus are built from. */
sealed interface Prepared {
    val renderer: Sha256?

    data class Host(val host: PreparedHost, val port: Int) : Prepared {
        override val renderer: Sha256? get() = host.renderer
    }

    data class Guest(val content: ModeContent, override val renderer: Sha256?) : Prepared
}

/**
 * This computer's own Deadlock relative to the session's server, the same for both roles. Proof of [In] is
 * [LinkEvent.Game] flowing: the host's helper sees its own account on the server, a guest's sees its game's packets.
 */
sealed interface OwnGame {
    val deadline: DeadlineToken? get() = null

    /** Not on the session's server. [problem] says why the last try failed. */
    data class Out(val problem: EntryProblem? = null) : OwnGame

    /** The helper holds the game's `enter` until its page takes it. [startingDeadlock] says Hideout started Deadlock for it. */
    data class Sending(val startingDeadlock: Boolean) : OwnGame

    /** The page took the connect, and the game is loading the mode. */
    data class Joining(override val deadline: DeadlineToken) : OwnGame

    data object In : OwnGame
}

sealed interface EntryProblem {
    /** The page never took the game in while Deadlock ran, so this run of Deadlock has no menus. The next session asks for a restart. */
    data object MenusDidNotStart : EntryProblem

    /** The helper could not load the bridge page from the site, so the game's page can't load either. A restart doesn't help. */
    data object SiteUnreachable : EntryProblem

    data object ClientDidNotStart : EntryProblem

    data object GameUpdatePending : EntryProblem

    /** The page took the connect and the game never showed up, as when a found match wins the race. */
    data object DidNotJoin : EntryProblem

    data object Dropped : EntryProblem

    data class Launch(val problem: Refusal) : EntryProblem
}

/**
 * The helper's window for `enter`. A running game's page asks again every 500 ms. A launched one first has to start, about
 * 15 s to the hub (measured) and over a minute on a slower computer, so one Hideout launched gets [Launched] until its menus
 * take a connect, even in a later session.
 */
object EnterWindow {
    val Running: Duration = 15.seconds
    val Launched: Duration = 3.minutes
}

/** The sidebar offers the page with the setting on, or while a session runs that only the page can stop. */
fun customGamesInSidebar(settingOn: Boolean?, session: Session): Boolean =
    settingOn == true || (session != Session.Idle && session !is Session.Ended)

/** A guest's helper once it has linked. [path] is null while the tunnel isn't answering. */
data class GuestLink(val path: LinkPath? = null) {
    val answering: Boolean get() = path != null
}

/** Whether the session was admitted to the game files, so its end has something to release. A guest admits late. */
enum class FileHold { Free, Held }

sealed interface FileRelease {
    data object Running : FileRelease

    /** The journal stays, so this is retried on the next stop or client close, or after a restart. */
    data class Failed(val problem: String) : FileRelease
}

sealed interface EndReason {
    data object PlayerStopped : EndReason

    data object GameClosed : EndReason

    data object GameUpdated : EndReason

    data class HostFailed(val message: String?) : EndReason

    data object HostEnded : EndReason

    data object HostsGameStopped : EndReason

    /** The server stopped running while its process stayed up, as it does when a map fails to load. */
    data object ServerDied : EndReason

    data class LinkFailed(val why: LinkFailure) : EndReason

    data class Refused(val problem: Refusal) : EndReason

    data class TimedOut(val waitingFor: LateStage) : EndReason

    /** Recovered from the journal after a crash. */
    data object Interrupted : EndReason
}

/** Why a session or a client launch could not go ahead, in the terms a screen can act on. [Other] is one that only has words. */
sealed interface Refusal {
    /** A mode's own content, which its map needs, could not go in the game's folder. */
    data object ContentNotStaged : Refusal

    /** No menus pack could be built for Deadlock, as when the renderer never downloaded, so no mode can be entered. */
    data object MenusUnavailable : Refusal

    /**
     * `gameinfo.gi` mounts [by], its search path as written, ahead of Hideout's menus, and a pack in it holds the menus'
     * own files, so Deadlock loads those in their place and never takes a connect. It stays until something takes it out
     * of `gameinfo.gi`: its own tool, the player, or Hideout for a Modlock dev session that ended, once Deadlock is closed.
     */
    data class MenusBlocked(val by: String) : Refusal

    data class Library(val problem: LibraryProblem) : Refusal

    data class Preflight(val problem: PreflightProblem) : Refusal

    /** Only for the unexpected, which a screen shows through its bug report details. */
    data class Other(val text: String) : Refusal
}

sealed interface PreflightProblem {
    data object SteamNotFound : PreflightProblem

    /** Flatpak or Snap Steam, which hosting can't use yet. Joining still works. */
    data object SteamUnsupported : PreflightProblem

    /** The host's own game is found on its server by its Steam account. */
    data object NoSteamAccount : PreflightProblem

    data object FolderMissing : PreflightProblem

    /** This build has no list of modes, so no runtime to host with. */
    data object NoModeList : PreflightProblem

    /** The list has no Modlock for this operating system. */
    data object NothingToHostWithHere : PreflightProblem

    /** A fresh list was read, and neither it nor this computer has the version of the mode the invite names. */
    data object ModeNotListed : PreflightProblem

    /** The invite names a version this computer doesn't have, and no fresh list could be read to look for it. */
    data object ListUnreachable : PreflightProblem

    data class Download(val why: FetchProblem) : PreflightProblem
}

enum class FetchProblem { Server, Connection, Disk, Mismatch, Unpack }

/** Why a link ended a session, of what the helper can stop for. */
enum class LinkFailure { Signal, GameNotFound, LinkLost, BadInvite, RelayUnavailable, ListenFailed, Unknown }

/** What a session gave up waiting for. The deadlines of the other waits don't end it. */
enum class LateStage { HostPreparing, GuestPreparing, Admitting, HostStarting, Linking }

/** How long `hideout-link stage` may run. A stop ends a stage at once, whatever this says. */
val StageLimit: Duration = 6.minutes

enum class Deadline(val after: Duration) {
    HostPreparing(1.hours),
    HostAdmitting(StageLimit + 1.minutes),
    HostStarting(5.minutes),
    GuestPreparing(1.hours),
    Linking(1.minutes),
    GuestAdmitting(StageLimit + 1.minutes),
    ClientClosing(20.seconds),
    /** A game loads a mode about 2 s after the page takes the connect (measured), and one that never shows up has been pulled elsewhere. */
    Joining(30.seconds),
    /** Outlasts the helper's own full stop, which takes up to 22 s, so [SessionCommand.KillLink] is only for a stuck helper. */
    Stopping(30.seconds),
}

@JvmInline
value class DeadlineToken(val value: Long)

sealed interface SessionEvent {
    /** [restartAccepted] is the restart of the running Deadlock the player was told of as they pressed, if any. */
    data class HostRequested(val mode: Mode, val choices: Choices, val restartAccepted: RestartReason? = null, val outreach: Outreach = Outreach.Private) : SessionEvent

    data class InviteOpened(val code: InviteCode, val acceptedBefore: InviteOffer? = null) : SessionEvent

    /** [restartAccepted] is as for [HostRequested]. */
    data class JoinConfirmed(val restartAccepted: RestartReason? = null) : SessionEvent

    data object RestartConfirmed : SessionEvent

    data object PlayRequested : SessionEvent

    data object ClientCloseFailed : SessionEvent

    data object StopRequested : SessionEvent

    data object Dismissed : SessionEvent

    /** A journal was left by a crash. */
    data object CrashFound : SessionEvent

    /** The helper a crash left is gone. */
    data object Recovered : SessionEvent

    /** The library holds the files of [game], which stays the game this session runs from. */
    data class Admitted(val game: GameRoot) : SessionEvent

    data class AdmissionRefused(val problem: Refusal) : SessionEvent

    /** The library holds the files, but the mode's content could not go in. */
    data class StageFailed(val problem: Refusal) : SessionEvent

    data class SetupRead(val answer: SetupAnswer) : SessionEvent

    /** What a preparation comes to, as the platform says it. */
    sealed interface PrepareAnswer : SessionEvent

    data class RuntimePrepared(val host: PreparedHost, val port: Int) : PrepareAnswer

    data class GuestPrepared(val content: ModeContent, val renderer: Sha256?) : PrepareAnswer

    data class RuntimeFailed(val problem: Refusal) : PrepareAnswer

    /** The library's scan-derived activity. Anything but `Closed` and `Running` says nothing new, so it changes nothing. */
    data class ClientActivity(val activity: GameActivity) : SessionEvent

    data class LaunchFailed(val problem: Refusal) : SessionEvent

    /**
     * The helper's window for the game's `enter` passed. [siteUnreachable] says its own fetch of the bridge page failed.
     * [updatePending] says Steam has an update it has not finished, which explains a client that never ran.
     */
    data class EnterExpired(val siteUnreachable: Boolean, val updatePending: Boolean) : SessionEvent

    data object GameUpdated : SessionEvent

    data class DeadlineElapsed(val token: DeadlineToken) : SessionEvent

    data object FilesReleased : SessionEvent

    data class ReleaseFailed(val problem: String) : SessionEvent
}

sealed interface SessionCommand {
    data object JournalBegin : SessionCommand

    data object JournalEnd : SessionCommand

    /**
     * Holds the files, unpacks a shared pack, and stages [content] into the game. Answered by [Admitted],
     * [AdmissionRefused] or [StageFailed]. The admit and release commands run in the order issued, so a stop during
     * admission releases what it took.
     */
    data class Admit(val content: ModeContent) : SessionCommand

    /** Answered by [RuntimePrepared] with a free port, or [RuntimeFailed]. */
    data class PrepareRuntime(val mode: Mode, val choices: Choices) : SessionCommand

    /** Answered by [GuestPrepared], or [RuntimeFailed] when the join is refused or something unexpected stops it. */
    data class PrepareGuest(val offer: InviteOffer) : SessionCommand

    data object CancelPrepare : SessionCommand

    /** Builds the pack when its stamp is stale and Deadlock is closed, records that custom games are in use, and answers [SetupRead]. */
    data class SetUpGame(val renderer: Sha256?) : SessionCommand

    data class SpawnHost(val host: PreparedHost, val port: Int, val game: GameRoot, val setup: GameSetup) : SessionCommand

    data class FindGame(val code: InviteCode) : SessionCommand

    data class JoinLink(val setup: GameSetup, val shared: SharedPack?) : SessionCommand

    data object StopLink : SessionCommand

    data object AbandonLink : SessionCommand

    /** Force-ends the helper after [StopLink] ran out of time. */
    data object KillLink : SessionCommand

    /** A plain Steam launch under the hold. The connect comes over the page. */
    data object LaunchClient : SessionCommand

    /** `enter <seconds>` on the helper's stdin, answered by [LinkEvent.Entered] or [EnterExpired]. */
    data class EnterGame(val window: Duration) : SessionCommand

    /** Answered by the client's activity going [GameActivity.Closed], or [ClientCloseFailed]. */
    data object CloseClient : SessionCommand

    data object ReleaseFiles : SessionCommand

    data class Arm(val deadline: Deadline, val token: DeadlineToken) : SessionCommand
}

data class Step(val session: Session, val commands: List<SessionCommand> = emptyList())

/** Whether a [HostRequested] starts a game now. [reduce] reads it too, so a caller that answers before posting agrees with it. */
val Session.startsHost: Boolean get() = this == Session.Idle || this is Session.Ended

/**
 * The next state and what to do about [event]. Pure and total. An event a state doesn't expect leaves it unchanged.
 * [client] is the library's newest word on the game, since a state can start on what it says now. [token] arms any
 * deadline this step starts. The owner passes a value it has never passed before, because a counter kept in the session
 * would restart with each one and let an old timer match. [booting] says a running [client] is one Hideout launched that
 * may still be on its way to its menus.
 */
fun reduce(session: Session, event: SessionEvent, client: GameActivity, token: DeadlineToken, booting: Boolean = false): Step {
    if (event is DeadlineElapsed && event.token != session.deadline) return Step(session)
    if (event is HostRequested) {
        if (!session.startsHost) return Step(session)
        return Step(
            Session.HostPreparing(token, event.restartAccepted),
            listOf(Arm(Deadline.HostPreparing, token), PrepareRuntime(event.mode, event.choices)),
        )
    }
    return when (session) {
        Session.Idle -> when (event) {
            is InviteOpened -> finding(event, token)
            CrashFound -> Step(Session.Recovering)
            else -> Step(session)
        }

        Session.Recovering -> if (event == Recovered) releasing(EndReason.Interrupted) else Step(session)

        is Session.HostPreparing -> when (event) {
            is RuntimePrepared -> settingUp(Prepared.Host(event.host, event.port), session.restartAccepted)
            is RuntimeFailed -> ended(EndReason.Refused(event.problem))
            StopRequested -> ended(EndReason.PlayerStopped, CancelPrepare)
            is DeadlineElapsed -> ended(EndReason.TimedOut(LateStage.HostPreparing), CancelPrepare)
            else -> Step(session)
        }

        is Session.SettingUp -> when (event) {
            is SetupRead -> when (val answer = event.answer) {
                is SetupAnswer.Ready -> setUp(session.next, answer.setup, token)
                is SetupAnswer.NeedsRestart -> when {
                    client == GameActivity.Closed -> settingUp(session.next)
                    answer.why == session.restartAccepted -> restarting(session.next, token)
                    else -> Step(Session.RestartNeeded(session.next, answer.why))
                }
                is SetupAnswer.Failed -> session.next.stopOrEnd(EndReason.Refused(answer.problem), token)
            }
            StopRequested -> session.next.stopOrEnd(EndReason.PlayerStopped, token)
            is LinkEvent.Stopped -> ended(event.endReason())
            else -> Step(session)
        }

        is Session.RestartNeeded -> when (event) {
            RestartConfirmed -> if (client == GameActivity.Closed) settingUp(session.next) else restarting(session.next, token)
            is ClientActivity -> if (event.activity == GameActivity.Closed) settingUp(session.next) else Step(session)
            StopRequested -> session.next.stopOrEnd(EndReason.PlayerStopped, token)
            is LinkEvent.Stopped -> ended(event.endReason())
            else -> Step(session)
        }

        is Session.Restarting -> when (event) {
            is ClientActivity -> if (event.activity == GameActivity.Closed) settingUp(session.next) else Step(session)
            ClientCloseFailed, is DeadlineElapsed -> Step(session.copy(closeFailed = true, deadline = null))
            StopRequested -> session.next.stopOrEnd(EndReason.PlayerStopped, token)
            is LinkEvent.Stopped -> ended(event.endReason())
            else -> Step(session)
        }

        is Session.HostAdmitting -> when (event) {
            is Admitted -> Step(
                Session.HostStarting(code = null, map = null, token),
                listOf(Arm(Deadline.HostStarting, token), SpawnHost(session.host, session.port, event.game, session.setup)),
            )
            is AdmissionRefused -> ended(EndReason.Refused(event.problem))
            is StageFailed -> releasing(EndReason.Refused(event.problem))
            StopRequested -> releasing(EndReason.PlayerStopped)
            is DeadlineElapsed -> releasing(EndReason.TimedOut(LateStage.Admitting))
            else -> Step(session)
        }

        is Session.HostStarting -> when (event) {
            is LinkEvent.InviteReady -> session.arrived(event.code, session.map, client, booting)
            is LinkEvent.Guests -> Step(session.copy(guests = event.paths))
            is LinkEvent.Server -> when (val server = event.event) {
                is ServerEvent.Ready -> session.arrived(session.code, server.map, client, booting)
                is ServerEvent.Failed -> stopping(EndReason.HostFailed(server.error), FileHold.Held, token)
                else -> Step(session)
            }
            is LinkEvent.Stopped -> releasing(event.endReason())
            StopRequested -> stopping(EndReason.PlayerStopped, FileHold.Held, token)
            is DeadlineElapsed -> stopping(EndReason.TimedOut(LateStage.HostStarting), FileHold.Held, token)
            else -> Step(session)
        }

        is Session.Hosting -> when (event) {
            is LinkEvent.Guests -> Step(session.copy(guests = event.paths))
            is LinkEvent.Server -> when (val server = event.event) {
                is ServerEvent.Ready -> Step(session.copy(map = server.map))
                is ServerEvent.Failed -> stopping(EndReason.HostFailed(server.error), FileHold.Held, token)
                is ServerEvent.Joined -> Step(session.copy(players = session.players.afterJoin(server.slot, server.player)))
                is ServerEvent.Left -> Step(session.copy(players = session.players - server.slot))
            }
            GameUpdated -> stopping(EndReason.GameUpdated, FileHold.Held, token)
            is LinkEvent.Stopped -> releasing(event.endReason())
            StopRequested -> stopping(EndReason.PlayerStopped, FileHold.Held, token)
            else -> session.own.after(event, client, token, booting)?.let { (own, commands) -> Step(session.copy(own = own), commands) } ?: Step(session)
        }

        is Session.Finding -> when (event) {
            is LinkEvent.Offer ->
                if (event.offer == session.acceptedBefore) joining(Invite(session.code, event.offer), token) else Step(Session.Confirming(Invite(session.code, event.offer)))
            LinkEvent.BadOffer -> stopping(EndReason.LinkFailed(LinkFailure.BadInvite), FileHold.Free, token)
            is LinkEvent.Stopped -> ended(event.endReason())
            is DeadlineElapsed -> stopping(EndReason.TimedOut(LateStage.Linking), FileHold.Free, token)
            StopRequested -> Step(Session.Idle, listOf(AbandonLink))
            is InviteOpened -> if (event.code == session.code) Step(session) else finding(event, token, AbandonLink)
            else -> Step(session)
        }

        is Session.Confirming -> when (event) {
            is JoinConfirmed -> joining(session.invite, token, event.restartAccepted)
            StopRequested -> Step(Session.Idle, listOf(AbandonLink))
            is InviteOpened -> if (event.code == session.invite.code) Step(session) else finding(event, token, AbandonLink)
            is LinkEvent.Stopped -> ended(event.endReason())
            else -> Step(session)
        }

        is Session.GuestPreparing -> when (event) {
            is GuestPrepared -> settingUp(Prepared.Guest(event.content, event.renderer), session.restartAccepted)
            is RuntimeFailed -> stopping(EndReason.Refused(event.problem), FileHold.Free, token)
            StopRequested -> stopping(EndReason.PlayerStopped, FileHold.Free, token, CancelPrepare)
            is DeadlineElapsed -> stopping(EndReason.TimedOut(LateStage.GuestPreparing), FileHold.Free, token, CancelPrepare)
            is LinkEvent.Stopped -> ended(event.endReason(), CancelPrepare)
            else -> Step(session)
        }

        is Session.Linking -> when (event) {
            LinkEvent.Linked -> if (session.content.shared != null) Step(Session.Fetching(session.content, GuestLink())) else admitting(session.content, GuestLink(), token)
            is LinkEvent.Stopped -> ended(event.endReason())
            StopRequested -> stopping(EndReason.PlayerStopped, FileHold.Free, token)
            is DeadlineElapsed -> stopping(EndReason.TimedOut(LateStage.Linking), FileHold.Free, token)
            else -> Step(session)
        }

        is Session.Fetching -> when (event) {
            is LinkEvent.Fetch -> Step(session.copy(transfer = event.transfer))
            LinkEvent.Fetched -> admitting(session.content, session.link, token)
            is LinkEvent.Path -> Step(session.copy(link = session.link.copy(path = event.kind)))
            is LinkEvent.Stopped -> ended(event.endReason())
            StopRequested -> stopping(EndReason.PlayerStopped, FileHold.Free, token)
            else -> Step(session)
        }

        is Session.GuestAdmitting -> when (event) {
            is Admitted -> entering(client, booting).let { (own, commands) -> Step(Session.Playing(session.link, own), commands) }
            is AdmissionRefused -> stopping(EndReason.Refused(event.problem), FileHold.Free, token)
            is StageFailed -> stopping(EndReason.Refused(event.problem), FileHold.Held, token)
            is LinkEvent.Path -> Step(session.copy(link = session.link.copy(path = event.kind)))
            is LinkEvent.Stopped -> releasing(event.endReason())
            StopRequested -> stopping(EndReason.PlayerStopped, FileHold.Held, token)
            is DeadlineElapsed -> stopping(EndReason.TimedOut(LateStage.Admitting), FileHold.Held, token)
            else -> Step(session)
        }

        is Session.Playing -> when {
            event is LinkEvent.Path -> Step(session.copy(link = session.link.copy(path = event.kind)))
            event == ClientActivity(GameActivity.Closed) && session.own == OwnGame.In -> stopping(EndReason.GameClosed, FileHold.Held, token)
            event == PlayRequested && !session.link.answering -> Step(session)
            event is LinkEvent.Stopped -> releasing(event.endReason())
            event == StopRequested -> stopping(EndReason.PlayerStopped, FileHold.Held, token)
            else -> session.own.after(event, client, token, booting)?.let { (own, commands) -> Step(session.copy(own = own), commands) } ?: Step(session)
        }

        is Session.Stopping -> when (event) {
            is LinkEvent.Stopped -> when (session.hold) {
                FileHold.Held -> releasing(session.reason)
                FileHold.Free -> ended(session.reason)
            }
            is AdmissionRefused -> Step(session.copy(hold = FileHold.Free))
            is DeadlineElapsed -> Step(session.copy(deadline = token), listOf(Arm(Deadline.Stopping, token), KillLink))
            else -> Step(session)
        }

        is Session.ReleasingFiles -> when {
            event is InviteOpened && session.reason == EndReason.Interrupted -> Step(session.copy(next = event.code))
            session.release == FileRelease.Running -> when (event) {
                FilesReleased -> session.next?.let { finding(InviteOpened(it), token, JournalEnd) } ?: ended(session.reason)
                is ReleaseFailed -> Step(session.copy(release = FileRelease.Failed(event.problem)))
                else -> Step(session)
            }
            event == StopRequested || event == ClientActivity(GameActivity.Closed) -> Step(session.copy(release = FileRelease.Running), listOf(ReleaseFiles))
            else -> Step(session)
        }

        is Session.Ended -> when (event) {
            Dismissed -> Step(Session.Idle)
            is InviteOpened -> finding(event, token)
            else -> Step(session)
        }
    }
}

private fun OwnGame.after(event: SessionEvent, client: GameActivity, token: DeadlineToken, booting: Boolean): Pair<OwnGame, List<SessionCommand>>? = when {
    this is OwnGame.Out && event == PlayRequested -> entering(client, booting)
    this is OwnGame.Sending && event == LinkEvent.Entered -> OwnGame.Joining(token) to listOf(Arm(Deadline.Joining, token))
    this is OwnGame.Sending && event is EnterExpired -> OwnGame.Out(
        when {
            event.siteUnreachable -> EntryProblem.SiteUnreachable
            client == GameActivity.Running -> EntryProblem.MenusDidNotStart
            event.updatePending -> EntryProblem.GameUpdatePending
            else -> EntryProblem.ClientDidNotStart
        },
    ) to emptyList()
    this is OwnGame.Sending && event is LaunchFailed -> OwnGame.Out(EntryProblem.Launch(event.problem)) to emptyList()
    this is OwnGame.Joining && event is DeadlineElapsed -> OwnGame.Out(EntryProblem.DidNotJoin) to emptyList()
    this != OwnGame.In && event == LinkEvent.Game(GameTraffic.Flowing) -> OwnGame.In to emptyList()
    this == OwnGame.In && event == LinkEvent.Game(GameTraffic.Lost) -> OwnGame.Out(EntryProblem.Dropped) to emptyList()
    this == OwnGame.In && event == LinkEvent.Game(GameTraffic.Quiet) -> OwnGame.Out() to emptyList()
    event == ClientActivity(GameActivity.Closed) && this != OwnGame.Sending(startingDeadlock = true) -> OwnGame.Out() to emptyList()
    else -> null
}

private fun entering(client: GameActivity, booting: Boolean): Pair<OwnGame, List<SessionCommand>> =
    if (client == GameActivity.Running) {
        OwnGame.Sending(startingDeadlock = false) to listOf(EnterGame(if (booting) EnterWindow.Launched else EnterWindow.Running))
    } else {
        OwnGame.Sending(startingDeadlock = true) to listOf(LaunchClient, EnterGame(EnterWindow.Launched))
    }

private fun Session.HostStarting.arrived(code: InviteCode?, map: String?, client: GameActivity, booting: Boolean): Step =
    if (code == null || map == null) {
        Step(copy(code = code, map = map))
    } else {
        entering(client, booting).let { (own, commands) -> Step(Session.Hosting(code, map, own = own, guests = guests), commands) }
    }

private fun settingUp(next: Prepared, restartAccepted: RestartReason? = null) = Step(Session.SettingUp(next, restartAccepted), listOf(SetUpGame(next.renderer)))

private fun restarting(next: Prepared, token: DeadlineToken) =
    Step(Session.Restarting(next, deadline = token), listOf(Arm(Deadline.ClientClosing, token), CloseClient))

private fun setUp(next: Prepared, setup: GameSetup, token: DeadlineToken): Step = when (next) {
    is Prepared.Host -> Step(
        Session.HostAdmitting(next.host, next.port, setup, token),
        listOf(JournalBegin, Arm(Deadline.HostAdmitting, token), Admit(next.host.content)),
    )
    is Prepared.Guest -> Step(
        Session.Linking(next.content, token),
        listOf(Arm(Deadline.Linking, token), JoinLink(setup, next.content.shared)),
    )
}

private fun Prepared.stopOrEnd(reason: EndReason, token: DeadlineToken): Step = when (this) {
    is Prepared.Host -> ended(reason)
    is Prepared.Guest -> stopping(reason, FileHold.Free, token)
}

private fun finding(event: InviteOpened, token: DeadlineToken, vararg first: SessionCommand) =
    Step(Session.Finding(event.code, token, event.acceptedBefore), first.toList() + listOf(Arm(Deadline.Linking, token), FindGame(event.code)))

private fun joining(invite: Invite, token: DeadlineToken, restartAccepted: RestartReason? = null) =
    Step(Session.GuestPreparing(invite, token, restartAccepted), listOf(JournalBegin, Arm(Deadline.GuestPreparing, token), PrepareGuest(invite.offer)))

private fun Map<Int, Player>.afterJoin(slot: Int, player: Player?): Map<Int, Player> =
    if (player == null) this - slot else filterValues { it.steamId != player.steamId } + (slot to player)

private fun admitting(content: ModeContent, link: GuestLink, token: DeadlineToken) =
    Step(Session.GuestAdmitting(content, link, token), listOf(Arm(Deadline.GuestAdmitting, token), Admit(content)))

private fun stopping(reason: EndReason, hold: FileHold, token: DeadlineToken, vararg first: SessionCommand) =
    Step(Session.Stopping(reason, hold, token), first.toList() + listOf(Arm(Deadline.Stopping, token), StopLink))

private fun releasing(reason: EndReason) = Step(Session.ReleasingFiles(reason, FileRelease.Running), listOf(ReleaseFiles))

private fun ended(reason: EndReason, vararg first: SessionCommand) = Step(Session.Ended(reason), first.toList() + JournalEnd)

private fun LinkEvent.Stopped.endReason(): EndReason = when (reason) {
    LinkStop.StdinClosed -> EndReason.PlayerStopped
    LinkStop.ServerExited, LinkStop.ServerStartFailed -> EndReason.HostFailed(message)
    LinkStop.HostFailed -> EndReason.HostsGameStopped
    LinkStop.ServerDead -> EndReason.ServerDied
    LinkStop.HostEnded -> EndReason.HostEnded
    LinkStop.Signal -> EndReason.LinkFailed(LinkFailure.Signal)
    LinkStop.LinkTimeout -> EndReason.TimedOut(LateStage.Linking)
    LinkStop.NotFound -> EndReason.LinkFailed(LinkFailure.GameNotFound)
    LinkStop.LinkLost -> EndReason.LinkFailed(LinkFailure.LinkLost)
    LinkStop.BadInvite -> EndReason.LinkFailed(LinkFailure.BadInvite)
    LinkStop.RelayUnavailable -> EndReason.LinkFailed(LinkFailure.RelayUnavailable)
    LinkStop.ListenFailed -> EndReason.LinkFailed(LinkFailure.ListenFailed)
    LinkStop.FetchFailed -> EndReason.Refused(Refusal.Preflight(PreflightProblem.Download(FetchProblem.Connection)))
    LinkStop.FetchMismatch -> EndReason.Refused(Refusal.Preflight(PreflightProblem.Download(FetchProblem.Mismatch)))
    LinkStop.Unknown -> EndReason.LinkFailed(LinkFailure.Unknown)
}
