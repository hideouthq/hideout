package gg.hideouthq.core.customgames

import gg.hideouthq.core.library.counted
import gg.hideouthq.core.library.reason
import gg.hideouthq.core.library.sizeText

/**
 * The lines the log gains as a custom game moves from [before] to [after]: one per phase, not one per state, so a session
 * reads as what was asked, the setup and any restart, the files held, the game live or joined, and how it ended. No line
 * holds an invite code or a player's name.
 */
internal fun customGamesLog(before: LiveSession, after: LiveSession): List<String> = buildList {
    val was = before.session
    val now = after.session
    if (now::class != was::class) stageEntered(was, now, after.game)?.let(::add) else addAll(stageChanged(was, now))
    val players = (now as? Session.Hosting)?.players
    if (players != null && players.size != ((was as? Session.Hosting)?.players?.size ?: 0)) add("${counted(players.size, "player")} signed on")
    val guests = now.guests
    if (guests != null && guests != (was.guests ?: GuestPaths())) add("guests linked: ${guests.direct} direct, ${guests.relay} through the relay")
    val link = now.link
    if (link != null && link.path != was.link?.path) add(link.said)
    val own = now.own
    if (own != null && own != was.own) add(own.said)
}.map(::logged)

/** The lines the log gains as the modes on this computer go from [before] to [after]. A null [before] is the first read, which lists them. */
internal fun installedLog(before: Map<ModeId, InstalledMode>?, after: Map<ModeId, InstalledMode>): List<String> = buildList {
    if (before == null) {
        if (after.isNotEmpty()) add("installed modes: ${after.values.joinToString { it.mode.said }}")
        return@buildList
    }
    after.values.filter { it.mode.id !in before }.forEach { add("installed ${it.mode.said}") }
    after.values.forEach { now ->
        before[now.mode.id]?.takeIf { it.mode.packages != now.mode.packages }?.let { add("updated ${now.mode.said}, was version ${it.mode.version}") }
    }
    before.values.filter { it.mode.id !in after }.forEach { add("removed ${it.mode.said}") }
}.map(::logged)

/** The lines the log gains as the list of modes goes from [before] to [after]: a newer list taken, or a read that failed. */
internal fun feedLog(before: ModeList, after: ModeList): List<String> = buildList {
    val feed = after.feed
    if (feed != null && feed.sequence != before.feed?.sequence) add("read the list of modes: ${feed.said}")
    val problem = after.problem
    if (problem != null && problem != before.problem) add("the list of modes couldn't be read: ${problem.said}")
}.map(::logged)

/** A failed update of [mode], as the log says it. */
internal fun updateFailed(mode: Mode, problem: FetchProblem): String = logged("could not update ${mode.said}: ${problem.said}")

/** Every line goes through [redactInvites], since some carry text from the helper or an error that may quote an invite. */
private fun logged(line: String) = redactInvites("Custom games: $line")

private fun stageEntered(was: Session, now: Session, game: GameRecord?): String? = when (now) {
    Session.Idle -> "the join was called off".takeIf { was is Session.Finding || was is Session.Confirming }
    Session.Recovering -> "ending the session a crash left"
    is Session.HostPreparing -> "hosting ${(game?.role as? Role.Host)?.said ?: "a mode"}, getting it ready" + accepted(now.restartAccepted)
    is Session.SettingUp -> "setting up Deadlock"
    is Session.RestartNeeded -> "Deadlock needs a restart, ${now.why.said}, waiting for the player"
    is Session.Restarting -> "restarting Deadlock" + ((was as? Session.RestartNeeded)?.why ?: (was as? Session.SettingUp)?.restartAccepted)?.let { ", ${it.said}" }.orEmpty()
    is Session.HostAdmitting, is Session.GuestAdmitting -> "holding the game's files and staging the mode"
    is Session.HostStarting -> "starting the server"
    is Session.Hosting -> "live as host on ${now.map}"
    is Session.Finding -> if (now.acceptedBefore != null) "joining the same game again, looking for the host" else "joining a friend's game, looking for the host"
    is Session.Confirming -> "found the host's game, ${now.invite.offer.said(game)}, waiting for the player to confirm"
    is Session.GuestPreparing -> "joining" + (if (was is Session.Confirming) "" else " ${now.invite.offer.said(game)}") + ", getting the mode ready" + accepted(now.restartAccepted)
    is Session.Linking -> "linking to the host's game"
    is Session.Fetching -> "fetching the host's build" + now.content.shared?.let { ", ${sizeText(it.bytes)}" }.orEmpty()
    is Session.Playing -> "connected to the host's game"
    is Session.Stopping -> "stopping, ${now.reason.said}"
    is Session.ReleasingFiles -> "giving the game's files back" + if (was is Session.Stopping) "" else ", ${now.reason.said}"
    is Session.Ended -> if (game?.started == true || now.reason == EndReason.PlayerStopped) "ended, ${now.reason.said}" else "couldn't start, ${now.reason.said}"
}

private fun stageChanged(was: Session, now: Session): List<String> = buildList {
    when (now) {
        is Session.Restarting -> if (now.closeFailed && !(was as Session.Restarting).closeFailed) add("Deadlock didn't close in time, waiting for the player to close it")
        is Session.HostStarting -> {
            was as Session.HostStarting
            if (now.code != null && was.code == null) add("the invite is ready")
            if (now.map != null && now.map != was.map) add("the server loaded ${now.map}")
        }
        is Session.Hosting -> if (now.map != (was as Session.Hosting).map) add("the server loaded ${now.map}")
        is Session.Finding -> if (now.code != (was as Session.Finding).code) add("looking for a newer invite's host instead")
        is Session.Stopping -> if (now.deadline != (was as Session.Stopping).deadline) add("the helper didn't stop in time, ending it")
        is Session.ReleasingFiles -> {
            val release = now.release
            if (release != (was as Session.ReleasingFiles).release) {
                add(if (release is FileRelease.Failed) "giving the files back failed: ${release.problem}, trying again on the next stop or Deadlock close" else "giving the game's files back again")
            }
        }
        else -> Unit
    }
}

private fun accepted(why: RestartReason?): String = why?.let { ", the player agreed to restart Deadlock as ${it.said}" }.orEmpty()

/** Where [this] is, in the words a bug report shows it. */
internal val Session.phase: String
    get() = when (this) {
        Session.Idle -> "none"
        Session.Recovering -> "ending the session a crash left"
        is Session.HostPreparing -> "hosting, getting the mode ready"
        is Session.SettingUp -> "setting up Deadlock"
        is Session.RestartNeeded -> "waiting for the player to restart Deadlock, as ${why.said}"
        is Session.Restarting -> if (closeFailed) "waiting for the player to close Deadlock" else "restarting Deadlock"
        is Session.HostAdmitting -> "hosting, staging the mode"
        is Session.HostStarting -> "hosting, starting the server"
        is Session.Hosting -> "hosting on $map, ${counted(players.size, "player")}, guests ${guests.direct} direct and ${guests.relay} through the relay; ${own.said}"
        is Session.Finding -> "joining, looking for the host"
        is Session.Confirming -> "joining, waiting for the player to confirm"
        is Session.GuestPreparing -> "joining, getting the mode ready"
        is Session.Linking -> "joining, linking to the host"
        is Session.Fetching -> "joining, fetching the host's build" + transfer?.let { ", ${sizeText(it.done)} of ${sizeText(it.total)}" }.orEmpty()
        is Session.GuestAdmitting -> "joining, staging the mode"
        is Session.Playing -> "playing; ${link.said}; ${own.said}"
        is Session.Stopping -> "stopping, ${reason.said}"
        is Session.ReleasingFiles -> "giving the game's files back, ${reason.said}" + (release as? FileRelease.Failed)?.let { "; that failed: ${it.problem}" }.orEmpty()
        is Session.Ended -> "ended, ${reason.said}"
    }

internal val Session.own: OwnGame?
    get() = when (this) {
        is Session.Hosting -> own
        is Session.Playing -> own
        else -> null
    }

private val Session.guests: GuestPaths?
    get() = when (this) {
        is Session.HostStarting -> guests
        is Session.Hosting -> guests
        else -> null
    }

/** A mode as the log and a report name it, such as `Arena (arena 1.0.0, from the list)`. */
internal val Mode.said: String
    get() = "${listing.name} (${id.value} $version, " + when (val origin = origin) {
        is ModeOrigin.Feed -> "from the list)"
        is ModeOrigin.Folder -> "from the folder ${origin.builtDir})"
    }

/** What the game is, without its invite code: the role, the mode and the host's picks. */
internal val GameRecord.said: String
    get() = when (role) {
        is Role.Host -> "hosting ${role.said}"
        is Role.Guest -> "joining " + (role.offer?.said(this) ?: "a game whose offer hasn't arrived")
    } + (startedAt?.let { ", started $it" } ?: ", never started") + endedAt?.let { ", ended $it" }.orEmpty()

private val Role.Host.said: String get() = mode.said + choices.said

private val Choices.said: String get() = args.takeIf { it.isNotEmpty() }?.let { " with ${it.joinToString()}" }.orEmpty()

private fun InviteOffer.said(game: GameRecord?): String {
    val name = (game?.role as? Role.Guest)?.listing?.name
    val from = when (val content = content) {
        is OfferContent.Feed -> content.client?.let { "client ${it.short}" } ?: "stock assets"
        is OfferContent.Host -> "the host's own build of ${sizeText(content.pack.bytes)}"
    }
    return (name?.let { "$it (${mode.value})" } ?: mode.value) + ", $from" + choices.said + ", for ${counted(capacity, "player")}"
}

private val GuestLink.said: String
    get() = when (path) {
        LinkPath.Direct -> "the link is direct"
        LinkPath.Relay -> "the link goes through the relay"
        null -> "the link isn't answering"
    }

private val OwnGame.said: String
    get() = when (this) {
        is OwnGame.Out -> problem?.let { "this computer's game is out, ${it.said}" } ?: "this computer's game is off the server"
        is OwnGame.Sending -> "sending this computer's game in" + if (startingDeadlock) ", starting Deadlock first" else ""
        is OwnGame.Joining -> "this computer's game took the connect and is loading the mode"
        OwnGame.In -> "this computer's game is in the match"
    }

private val EntryProblem.said: String
    get() = when (this) {
        EntryProblem.MenusDidNotStart -> "Deadlock's menus never took the connect"
        EntryProblem.SiteUnreachable -> "the helper couldn't load the bridge page"
        EntryProblem.ClientDidNotStart -> "Deadlock never started"
        EntryProblem.GameUpdatePending -> "Steam has a Deadlock update waiting"
        EntryProblem.DidNotJoin -> "it took the connect and never showed up"
        EntryProblem.Dropped -> "it dropped"
        is EntryProblem.Launch -> "it couldn't be started: ${problem.said}"
    }

private val RestartReason.said: String
    get() = when (this) {
        RestartReason.NotSetUp -> "it started before custom games were set up"
        RestartReason.GameUpdated -> "something such as a Deadlock update took Hideout's custom games lines out"
        RestartReason.MenusDidNotStart -> "its menus never took a connect this run"
    }

internal val EndReason.said: String
    get() = when (this) {
        EndReason.PlayerStopped -> "the player stopped it"
        EndReason.GameClosed -> "Deadlock closed in the match"
        EndReason.GameUpdated -> "Deadlock updated"
        is EndReason.HostFailed -> "the server failed" + message?.let { ": $it" }.orEmpty()
        EndReason.HostEnded -> "the host ended the game"
        EndReason.HostsGameStopped -> "the host's server stopped"
        EndReason.ServerDied -> "the server died, as when a map fails to load"
        is EndReason.LinkFailed -> when (why) {
            LinkFailure.Signal -> "the helper was signalled to stop"
            LinkFailure.GameNotFound -> "no host holds that invite"
            LinkFailure.LinkLost -> "the link to the host was lost"
            LinkFailure.BadInvite -> "the host's offer couldn't be read"
            LinkFailure.RelayUnavailable -> "the relay couldn't be reached"
            LinkFailure.ListenFailed -> "a local port couldn't be opened"
            LinkFailure.Unknown -> "the helper stopped without saying why"
        }
        is EndReason.Refused -> problem.said
        is EndReason.TimedOut -> "timed out " + when (waitingFor) {
            LateStage.HostPreparing, LateStage.GuestPreparing -> "getting the mode ready"
            LateStage.Admitting -> "staging the mode"
            LateStage.HostStarting -> "starting the server"
            LateStage.Linking -> "reaching the host"
        }
        EndReason.Interrupted -> "Hideout closed or crashed during it"
    }

private val Refusal.said: String
    get() = when (this) {
        Refusal.ContentNotStaged -> "the mode's content couldn't go in the game's folder"
        Refusal.MenusUnavailable -> "no menus pack could be built"
        is Refusal.MenusBlocked -> "gameinfo.gi loads $by ahead of Hideout's menus, and it has menus files of its own"
        is Refusal.Library -> problem.reason()
        is Refusal.Preflight -> when (val problem = problem) {
            PreflightProblem.SteamNotFound -> "Steam isn't where Modlock looks for it"
            PreflightProblem.SteamUnsupported -> "this Steam, Flatpak or Snap, can't host yet"
            PreflightProblem.NoSteamAccount -> "no Steam account is signed in"
            PreflightProblem.FolderMissing -> "the mode's folder is missing"
            PreflightProblem.NoModeList -> "this build has no list of modes"
            PreflightProblem.NothingToHostWithHere -> "the list has no Modlock for this system"
            PreflightProblem.ModeNotListed -> "neither the list nor this computer has the invite's version"
            PreflightProblem.ListUnreachable -> "the list couldn't be read to find the invite's version"
            is PreflightProblem.Download -> "a download failed, ${problem.why.said}"
        }
        is Refusal.Other -> text
    }

internal val FetchProblem.said: String
    get() = when (this) {
        FetchProblem.Server -> "the server refused it"
        FetchProblem.Connection -> "the connection failed"
        FetchProblem.Disk -> "it couldn't be written"
        FetchProblem.Mismatch -> "it wasn't the file the list names"
        FetchProblem.Unpack -> "it couldn't be unpacked"
    }

internal val CustomGamesFetch.Problem.said: String
    get() = when (this) {
        is CustomGamesFetch.Unreachable -> "unreachable, $detail"
        is CustomGamesFetch.Refused -> when (why) {
            FeedRefusal.BadSignature -> "its signature didn't check out"
            FeedRefusal.Unreadable -> "it couldn't be read"
            FeedRefusal.Rollback -> "it was older than one already taken"
        }
    }

/** A list as the log and a report name it: its sequence, its modes, and the Modlock it runs. */
internal val CustomGamesFeed.said: String
    get() = "sequence $sequence, ${counted(modes.size, "mode")}" + upcoming.size.takeIf { it > 0 }?.let { ", $it upcoming" }.orEmpty() + ", runtime ${runtime.said}"

private val ModeRuntime.said: String
    get() = "Modlock " + cli.entries.sortedBy { it.key }.joinToString { (os, artifact) -> "${os.feedKey} ${artifact.sha256.short}" } +
        ", host ${host.sha256.short}, platform ${platform.sha256.short}, renderer ${renderer.sha256.short}"

private val Sha256.short: String get() = hex.take(12)
