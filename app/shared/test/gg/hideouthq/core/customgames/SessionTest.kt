package gg.hideouthq.core.customgames

import gg.hideouthq.core.customgames.SessionCommand.*
import gg.hideouthq.core.customgames.SessionEvent.*
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.LibraryProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionTest {
    private val folder = FakeCustomGamesFeed.SampleFolder
    private val pack = SharedPack(Sha256.parse("5".repeat(64))!!, 64)
    private val offer = InviteOffer.of(folder, Choices.None, FakeCustomGamesPlatform.SamplePlayer, pack)

    private val code = InviteCode.parse("P95-MAN-26D")!!
    private val invite = Invite(code, offer)
    private val port = 27015
    private val game = GameRoot("/games/Deadlock")
    private val renderer = Sha256.parse("e".repeat(64))
    private val content = ModeContent(listOf("content"))
    private val fetching = content.copy(shared = pack)
    private val prepared = PreparedHost("modlock", "modlock-host", listOf("platform"), content, offer, FakeCustomGamesPlatform.SamplePlayer, renderer = renderer)
    private val setup = GameSetup(MenusScreenPort, "https://hideouthq.gg/bridge/")
    private val hostNext = Prepared.Host(prepared, port)
    private val guestNext = Prepared.Guest(content, renderer)

    private fun t(n: Long) = DeadlineToken(n)

    private val fresh = t(9000)
    private val closed = ClientActivity(GameActivity.Closed)
    private val running = ClientActivity(GameActivity.Running)
    private val unknown = ClientActivity(GameActivity.Unknown)
    private val stdinClosed = LinkEvent.Stopped(LinkStop.StdinClosed)
    private val flowing = LinkEvent.Game(GameTraffic.Flowing)
    private val lost = LinkEvent.Game(GameTraffic.Lost)
    private val quiet = LinkEvent.Game(GameTraffic.Quiet)
    private val ready = SetupRead(SetupAnswer.Ready(setup))
    private val sendRunning = listOf(EnterGame(EnterWindow.Running))
    private val launchAndSend = listOf(LaunchClient, EnterGame(EnterWindow.Launched))

    private fun server(event: ServerEvent) = LinkEvent.Server(event)

    private fun hosting(own: OwnGame = OwnGame.In, players: Map<Int, Player> = emptyMap(), map: String = "street") = Session.Hosting(code, map, players, own)

    private fun playing(own: OwnGame = OwnGame.In, path: LinkPath? = LinkPath.Relay) = Session.Playing(GuestLink(path), own)

    private fun step(session: Session, event: SessionEvent, client: GameActivity = GameActivity.Unknown) = reduce(session, event, client, fresh)

    private fun assertUnchanged(session: Session, event: SessionEvent, client: GameActivity = GameActivity.Unknown) =
        assertEquals(Step(session), step(session, event, client), "$session on $event")

    private class Row(val event: SessionEvent, val session: Session, val commands: List<SessionCommand> = emptyList())

    private fun walk(rows: List<Row>, startingClient: GameActivity = GameActivity.Unknown) {
        var session: Session = Session.Idle
        var client = startingClient
        rows.forEachIndexed { index, row ->
            (row.event as? ClientActivity)?.let { client = it.activity }
            val next = reduce(session, row.event, client, t(index + 1L))
            assertEquals(Step(row.session, row.commands), next, "row ${index + 1}: ${row.event}")
            session = next.session
        }
    }

    private val everyState: List<Session> = listOf(
        Session.Idle,
        Session.Recovering,
        Session.HostPreparing(t(1)),
        Session.HostPreparing(t(1), RestartReason.NotSetUp),
        Session.SettingUp(hostNext),
        Session.SettingUp(guestNext),
        Session.SettingUp(guestNext, RestartReason.GameUpdated),
        Session.RestartNeeded(hostNext, RestartReason.NotSetUp),
        Session.RestartNeeded(guestNext, RestartReason.MenusDidNotStart),
        Session.Restarting(hostNext, deadline = t(1)),
        Session.Restarting(guestNext, closeFailed = true),
        Session.HostAdmitting(prepared, port, setup, t(1)),
        Session.HostStarting(null, null, t(1)),
        Session.HostStarting(code, null, t(1)),
        Session.HostStarting(null, "street", t(1)),
        hosting(OwnGame.Sending(startingDeadlock = false)),
        hosting(OwnGame.Sending(startingDeadlock = true)),
        hosting(OwnGame.Joining(t(1))),
        hosting(OwnGame.In, mapOf(1 to Player("Ann", "7656"))),
        hosting(OwnGame.Out(EntryProblem.Dropped)),
        Session.Finding(code, t(1)),
        Session.Finding(code, t(1), acceptedBefore = offer),
        Session.Confirming(invite),
        Session.GuestPreparing(invite, t(1)),
        Session.GuestPreparing(invite, t(1), RestartReason.NotSetUp),
        Session.Linking(content, t(1)),
        Session.Fetching(fetching, GuestLink(), Transfer(1, 64)),
        Session.GuestAdmitting(content, GuestLink(), t(1)),
        playing(OwnGame.Sending(startingDeadlock = false)),
        playing(OwnGame.Sending(startingDeadlock = true)),
        playing(OwnGame.Joining(t(1))),
        playing(OwnGame.In),
        playing(OwnGame.Out(EntryProblem.MenusDidNotStart)),
        playing(OwnGame.Out(), path = null),
        Session.Stopping(EndReason.PlayerStopped, FileHold.Held, t(1)),
        Session.Stopping(EndReason.PlayerStopped, FileHold.Free, t(1)),
        Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running),
        Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Failed("locked")),
        Session.ReleasingFiles(EndReason.Interrupted, FileRelease.Running, next = code),
        Session.Ended(EndReason.PlayerStopped),
    )

    private val everyEvent: List<SessionEvent> = listOf(
        HostRequested(folder, Choices.None), HostRequested(folder, Choices.None, RestartReason.NotSetUp), InviteOpened(code), InviteOpened(code, acceptedBefore = offer),
        JoinConfirmed(), JoinConfirmed(RestartReason.GameUpdated), RestartConfirmed, PlayRequested, StopRequested, Dismissed, CrashFound, Recovered,
        Admitted(game), AdmissionRefused(Refusal.Other("no")), StageFailed(Refusal.ContentNotStaged), RuntimePrepared(prepared, port), GuestPrepared(content, renderer), RuntimeFailed(Refusal.Other("no")),
        ready, SetupRead(SetupAnswer.NeedsRestart(RestartReason.GameUpdated)), SetupRead(SetupAnswer.Failed(Refusal.MenusUnavailable)),
        closed, running, unknown, ClientActivity(GameActivity.Unread), LaunchFailed(Refusal.Other("no")), SessionEvent.GameUpdated, ClientCloseFailed,
        EnterExpired(siteUnreachable = false, updatePending = false), EnterExpired(siteUnreachable = true, updatePending = false), EnterExpired(siteUnreachable = false, updatePending = true),
        DeadlineElapsed(t(1)), DeadlineElapsed(t(2)), FilesReleased, ReleaseFailed("no"),
        LinkEvent.InviteReady(code), LinkEvent.Offer(offer), LinkEvent.BadOffer, LinkEvent.Linked, LinkEvent.Path(LinkPath.Relay), LinkEvent.Path(null), LinkEvent.Entered, LinkEvent.NotEntered(siteUnreachable = false),
        flowing, lost, quiet, LinkEvent.Guests(GuestPaths(1, 1)),
        stdinClosed, LinkEvent.Stopped(LinkStop.LinkLost), LinkEvent.Stopped(LinkStop.ServerExited),
        server(ServerEvent.Ready("other")), server(ServerEvent.Failed("boom")), server(ServerEvent.Joined(2, Player("Ann", "7"))),
        server(ServerEvent.Joined(3, null)), server(ServerEvent.Left(2)),
        LinkEvent.Reported(listOf(SectionState.Scoreboard(listOf(ScoreSide(1, listOf(0))), emptyList()))),
        LinkEvent.Fetch(Transfer(2, 64)), LinkEvent.Fetched, LinkEvent.Stopped(LinkStop.FetchMismatch), LinkEvent.Stopped(LinkStop.LinkTimeout),
    )

    @Test
    fun aHostInARunningSetUpDeadlockPlaysWithoutItEverClosing() = walk(
        startingClient = GameActivity.Running,
        rows = listOf(
            Row(HostRequested(folder, Choices.None), Session.HostPreparing(t(1)), listOf(Arm(Deadline.HostPreparing, t(1)), PrepareRuntime(folder, Choices.None))),
            Row(RuntimePrepared(prepared, port), Session.SettingUp(hostNext), listOf(SetUpGame(renderer))),
            Row(ready, Session.HostAdmitting(prepared, port, setup, t(3)), listOf(JournalBegin, Arm(Deadline.HostAdmitting, t(3)), Admit(content))),
            Row(Admitted(game), Session.HostStarting(null, null, t(4)), listOf(Arm(Deadline.HostStarting, t(4)), SpawnHost(prepared, port, game, setup))),
            Row(LinkEvent.InviteReady(code), Session.HostStarting(code, null, t(4))),
            Row(server(ServerEvent.Ready("street")), hosting(OwnGame.Sending(startingDeadlock = false)), sendRunning),
            Row(PlayRequested, hosting(OwnGame.Sending(startingDeadlock = false))),
            Row(LinkEvent.Entered, hosting(OwnGame.Joining(t(8))), listOf(Arm(Deadline.Joining, t(8)))),
            Row(flowing, hosting(OwnGame.In)),
            Row(server(ServerEvent.Joined(1, Player("Ann", "7656"))), hosting(OwnGame.In, mapOf(1 to Player("Ann", "7656")))),
            Row(quiet, hosting(OwnGame.Out(), mapOf(1 to Player("Ann", "7656")))),
            Row(PlayRequested, hosting(OwnGame.Sending(startingDeadlock = false), mapOf(1 to Player("Ann", "7656"))), sendRunning),
            Row(LinkEvent.Entered, hosting(OwnGame.Joining(t(13)), mapOf(1 to Player("Ann", "7656"))), listOf(Arm(Deadline.Joining, t(13)))),
            Row(flowing, hosting(OwnGame.In, mapOf(1 to Player("Ann", "7656")))),
            Row(StopRequested, Session.Stopping(EndReason.PlayerStopped, FileHold.Held, t(15)), listOf(Arm(Deadline.Stopping, t(15)), StopLink)),
            Row(stdinClosed, Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running), listOf(ReleaseFiles)),
            Row(FilesReleased, Session.Ended(EndReason.PlayerStopped), listOf(JournalEnd)),
            Row(Dismissed, Session.Idle),
        ),
    )

    @Test
    fun aHostWithDeadlockClosedSetsItUpAndStartsItIntoTheGame() = walk(
        startingClient = GameActivity.Closed,
        rows = listOf(
            Row(HostRequested(folder, Choices.None), Session.HostPreparing(t(1)), listOf(Arm(Deadline.HostPreparing, t(1)), PrepareRuntime(folder, Choices.None))),
            Row(RuntimePrepared(prepared, port), Session.SettingUp(hostNext), listOf(SetUpGame(renderer))),
            Row(ready, Session.HostAdmitting(prepared, port, setup, t(3)), listOf(JournalBegin, Arm(Deadline.HostAdmitting, t(3)), Admit(content))),
            Row(Admitted(game), Session.HostStarting(null, null, t(4)), listOf(Arm(Deadline.HostStarting, t(4)), SpawnHost(prepared, port, game, setup))),
            Row(server(ServerEvent.Ready("street")), Session.HostStarting(null, "street", t(4))),
            Row(LinkEvent.InviteReady(code), hosting(OwnGame.Sending(startingDeadlock = true)), launchAndSend),
            Row(running, hosting(OwnGame.Sending(startingDeadlock = true))),
            Row(LinkEvent.Entered, hosting(OwnGame.Joining(t(8))), listOf(Arm(Deadline.Joining, t(8)))),
            Row(flowing, hosting(OwnGame.In)),
        ),
    )

    @Test
    fun aRestartThePlayerWasNotToldOfAtThePressWaitsForThemToSaySo() = walk(
        startingClient = GameActivity.Running,
        rows = listOf(
            Row(InviteOpened(code), Session.Finding(code, t(1)), listOf(Arm(Deadline.Linking, t(1)), FindGame(code))),
            Row(LinkEvent.Offer(offer), Session.Confirming(invite)),
            Row(JoinConfirmed(), Session.GuestPreparing(invite, t(3)), listOf(JournalBegin, Arm(Deadline.GuestPreparing, t(3)), PrepareGuest(offer))),
            Row(GuestPrepared(content, renderer), Session.SettingUp(guestNext), listOf(SetUpGame(renderer))),
            Row(SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)), Session.RestartNeeded(guestNext, RestartReason.NotSetUp)),
            Row(DeadlineElapsed(t(3)), Session.RestartNeeded(guestNext, RestartReason.NotSetUp)),
            Row(RestartConfirmed, Session.Restarting(guestNext, deadline = t(7)), listOf(Arm(Deadline.ClientClosing, t(7)), CloseClient)),
            Row(closed, Session.SettingUp(guestNext), listOf(SetUpGame(renderer))),
            Row(ready, Session.Linking(content, t(9)), listOf(Arm(Deadline.Linking, t(9)), JoinLink(setup, null))),
            Row(LinkEvent.Linked, Session.GuestAdmitting(content, GuestLink(), t(10)), listOf(Arm(Deadline.GuestAdmitting, t(10)), Admit(content))),
            Row(LinkEvent.Path(LinkPath.Relay), Session.GuestAdmitting(content, GuestLink(LinkPath.Relay), t(10))),
            Row(Admitted(game), playing(OwnGame.Sending(startingDeadlock = true)), launchAndSend),
            Row(running, playing(OwnGame.Sending(startingDeadlock = true))),
            Row(LinkEvent.Entered, playing(OwnGame.Joining(t(14))), listOf(Arm(Deadline.Joining, t(14)))),
            Row(flowing, playing(OwnGame.In)),
            Row(closed, Session.Stopping(EndReason.GameClosed, FileHold.Held, t(16)), listOf(Arm(Deadline.Stopping, t(16)), StopLink)),
            Row(stdinClosed, Session.ReleasingFiles(EndReason.GameClosed, FileRelease.Running), listOf(ReleaseFiles)),
            Row(FilesReleased, Session.Ended(EndReason.GameClosed), listOf(JournalEnd)),
        ),
    )

    @Test
    fun aHostToldOfTheRestartAtThePressRestartsDeadlockWithoutAskingAgain() = walk(
        startingClient = GameActivity.Running,
        rows = listOf(
            Row(
                HostRequested(folder, Choices.None, RestartReason.NotSetUp),
                Session.HostPreparing(t(1), RestartReason.NotSetUp),
                listOf(Arm(Deadline.HostPreparing, t(1)), PrepareRuntime(folder, Choices.None)),
            ),
            Row(RuntimePrepared(prepared, port), Session.SettingUp(hostNext, RestartReason.NotSetUp), listOf(SetUpGame(renderer))),
            Row(SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)), Session.Restarting(hostNext, deadline = t(3)), listOf(Arm(Deadline.ClientClosing, t(3)), CloseClient)),
            Row(closed, Session.SettingUp(hostNext), listOf(SetUpGame(renderer))),
            Row(ready, Session.HostAdmitting(prepared, port, setup, t(5)), listOf(JournalBegin, Arm(Deadline.HostAdmitting, t(5)), Admit(content))),
        ),
    )

    @Test
    fun aGuestToldOfTheRestartAtJoinRestartsDeadlockWithoutAskingAgain() = walk(
        startingClient = GameActivity.Running,
        rows = listOf(
            Row(InviteOpened(code), Session.Finding(code, t(1)), listOf(Arm(Deadline.Linking, t(1)), FindGame(code))),
            Row(LinkEvent.Offer(offer), Session.Confirming(invite)),
            Row(
                JoinConfirmed(RestartReason.NotSetUp),
                Session.GuestPreparing(invite, t(3), RestartReason.NotSetUp),
                listOf(JournalBegin, Arm(Deadline.GuestPreparing, t(3)), PrepareGuest(offer)),
            ),
            Row(GuestPrepared(content, renderer), Session.SettingUp(guestNext, RestartReason.NotSetUp), listOf(SetUpGame(renderer))),
            Row(SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)), Session.Restarting(guestNext, deadline = t(5)), listOf(Arm(Deadline.ClientClosing, t(5)), CloseClient)),
            Row(closed, Session.SettingUp(guestNext), listOf(SetUpGame(renderer))),
            Row(ready, Session.Linking(content, t(7)), listOf(Arm(Deadline.Linking, t(7)), JoinLink(setup, null))),
        ),
    )

    @Test
    fun aDeadlockThatStillNeedsARestartAfterTheToldOneIsAskedAbout() = walk(
        startingClient = GameActivity.Running,
        rows = listOf(
            Row(
                HostRequested(folder, Choices.None, RestartReason.NotSetUp),
                Session.HostPreparing(t(1), RestartReason.NotSetUp),
                listOf(Arm(Deadline.HostPreparing, t(1)), PrepareRuntime(folder, Choices.None)),
            ),
            Row(RuntimePrepared(prepared, port), Session.SettingUp(hostNext, RestartReason.NotSetUp), listOf(SetUpGame(renderer))),
            Row(SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)), Session.Restarting(hostNext, deadline = t(3)), listOf(Arm(Deadline.ClientClosing, t(3)), CloseClient)),
            Row(closed, Session.SettingUp(hostNext), listOf(SetUpGame(renderer))),
            Row(running, Session.SettingUp(hostNext)),
            Row(SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)), Session.RestartNeeded(hostNext, RestartReason.NotSetUp)),
        ),
    )

    @Test
    fun aRestartForAnotherReasonThanTheOneToldIsAskedAbout() {
        val told = Session.SettingUp(guestNext, RestartReason.NotSetUp)
        assertEquals(Step(Session.RestartNeeded(guestNext, RestartReason.GameUpdated)), step(told, SetupRead(SetupAnswer.NeedsRestart(RestartReason.GameUpdated)), GameActivity.Running))
        assertEquals(
            Step(Session.SettingUp(guestNext), listOf(SetUpGame(renderer))),
            step(told, SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)), GameActivity.Closed),
            "a Deadlock that closed meanwhile is set up closed, and the restart is no longer the player's",
        )
    }

    @Test
    fun aGuestInARunningSetUpDeadlockJoinsWithoutItEverClosing() = walk(
        startingClient = GameActivity.Running,
        rows = listOf(
            Row(InviteOpened(code), Session.Finding(code, t(1)), listOf(Arm(Deadline.Linking, t(1)), FindGame(code))),
            Row(LinkEvent.Offer(offer), Session.Confirming(invite)),
            Row(JoinConfirmed(), Session.GuestPreparing(invite, t(3)), listOf(JournalBegin, Arm(Deadline.GuestPreparing, t(3)), PrepareGuest(offer))),
            Row(GuestPrepared(content, renderer), Session.SettingUp(guestNext), listOf(SetUpGame(renderer))),
            Row(ready, Session.Linking(content, t(5)), listOf(Arm(Deadline.Linking, t(5)), JoinLink(setup, null))),
            Row(LinkEvent.Linked, Session.GuestAdmitting(content, GuestLink(), t(6)), listOf(Arm(Deadline.GuestAdmitting, t(6)), Admit(content))),
            Row(Admitted(game), playing(OwnGame.Sending(startingDeadlock = false), path = null), sendRunning),
            Row(LinkEvent.Entered, playing(OwnGame.Joining(t(8)), path = null), listOf(Arm(Deadline.Joining, t(8)))),
            Row(flowing, playing(OwnGame.In, path = null)),
            Row(lost, playing(OwnGame.Out(EntryProblem.Dropped), path = null)),
            Row(PlayRequested, playing(OwnGame.Out(EntryProblem.Dropped), path = null)),
            Row(LinkEvent.Path(LinkPath.Direct), playing(OwnGame.Out(EntryProblem.Dropped), path = LinkPath.Direct)),
            Row(PlayRequested, playing(OwnGame.Sending(startingDeadlock = false), path = LinkPath.Direct), sendRunning),
        ),
    )

    @Test
    fun aRunningDeadlockHideoutLaunchedIsSentInWithALaunchsWindowAndNotStartedAgain() {
        val admitting = Session.GuestAdmitting(content, GuestLink(), t(1))
        val sendBooting = listOf(EnterGame(EnterWindow.Launched))
        assertEquals(Step(playing(OwnGame.Sending(startingDeadlock = false), path = null), sendBooting), reduce(admitting, Admitted(game), GameActivity.Running, fresh, booting = true))
        assertEquals(Step(hosting(OwnGame.Sending(startingDeadlock = false)), sendBooting), reduce(hosting(OwnGame.Out()), PlayRequested, GameActivity.Running, fresh, booting = true))
        assertEquals(Step(playing(OwnGame.Sending(startingDeadlock = true), path = null), launchAndSend), reduce(admitting, Admitted(game), GameActivity.Closed, fresh, booting = true))
    }

    @Test
    fun aGuestOfAFolderModeFetchesTheHostsPackBetweenLinkingAndAdmission() = walk(
        listOf(
            Row(InviteOpened(code), Session.Finding(code, t(1)), listOf(Arm(Deadline.Linking, t(1)), FindGame(code))),
            Row(LinkEvent.Offer(offer), Session.Confirming(invite)),
            Row(JoinConfirmed(), Session.GuestPreparing(invite, t(3)), listOf(JournalBegin, Arm(Deadline.GuestPreparing, t(3)), PrepareGuest(offer))),
            Row(GuestPrepared(fetching, renderer), Session.SettingUp(Prepared.Guest(fetching, renderer)), listOf(SetUpGame(renderer))),
            Row(ready, Session.Linking(fetching, t(5)), listOf(Arm(Deadline.Linking, t(5)), JoinLink(setup, pack))),
            Row(LinkEvent.Linked, Session.Fetching(fetching, GuestLink())),
            Row(DeadlineElapsed(t(5)), Session.Fetching(fetching, GuestLink())),
            Row(LinkEvent.Fetch(Transfer(32, 64)), Session.Fetching(fetching, GuestLink(), Transfer(32, 64))),
            Row(LinkEvent.Path(LinkPath.Relay), Session.Fetching(fetching, GuestLink(LinkPath.Relay), Transfer(32, 64))),
            Row(LinkEvent.Fetched, Session.GuestAdmitting(fetching, GuestLink(LinkPath.Relay), t(10)), listOf(Arm(Deadline.GuestAdmitting, t(10)), Admit(fetching))),
        ),
    )

    @Test
    fun joiningTheSameGameAgainNeedsNoSecondYes() = walk(
        listOf(
            Row(InviteOpened(code, acceptedBefore = offer), Session.Finding(code, t(1), acceptedBefore = offer), listOf(Arm(Deadline.Linking, t(1)), FindGame(code))),
            Row(LinkEvent.Offer(offer), Session.GuestPreparing(invite, t(2)), listOf(JournalBegin, Arm(Deadline.GuestPreparing, t(2)), PrepareGuest(offer))),
        ),
    )

    @Test
    fun aHostOfferingAnotherGameUnderTheSameCodeIsAskedAboutAgain() {
        val other = offer.copy(capacity = 2)
        assertEquals(Step(Session.Confirming(Invite(code, other))), step(Session.Finding(code, t(1), acceptedBefore = offer), LinkEvent.Offer(other)))
    }

    @Test
    fun aCodeTheRelayKnowsNoHostForEndsAsNoGameFound() {
        assertEquals(Step(Session.Ended(EndReason.LinkFailed(LinkFailure.GameNotFound)), listOf(JournalEnd)), step(Session.Finding(code, t(1)), LinkEvent.Stopped(LinkStop.NotFound)))
    }

    @Test
    fun aCodeNothingAnswersInTimeEndsAsUnreachable() {
        val finding = Session.Finding(code, t(1))
        assertEquals(Step(Session.Ended(EndReason.TimedOut(LateStage.Linking)), listOf(JournalEnd)), step(finding, LinkEvent.Stopped(LinkStop.LinkTimeout)))
        assertEquals(Step(Session.Stopping(EndReason.TimedOut(LateStage.Linking), FileHold.Free, fresh), listOf(Arm(Deadline.Stopping, fresh), StopLink)), step(finding, DeadlineElapsed(t(1))))
        assertEquals(Session.Ended(EndReason.LinkFailed(LinkFailure.RelayUnavailable)), step(finding, LinkEvent.Stopped(LinkStop.RelayUnavailable)).session)
    }

    @Test
    fun whatAGuestsHostSaidAsItEndedIsTheReasonAtEveryStage() {
        val free = listOf(
            Session.Confirming(invite), Session.GuestPreparing(invite, t(1)), Session.SettingUp(guestNext), Session.RestartNeeded(guestNext, RestartReason.NotSetUp),
            Session.Restarting(guestNext, deadline = t(1)), Session.Linking(content, t(1)), Session.Fetching(content, GuestLink()),
        )
        val held = listOf(Session.GuestAdmitting(content, GuestLink(), t(1)), playing(), playing(OwnGame.Out()))
        for ((said, reason) in mapOf(LinkStop.HostEnded to EndReason.HostEnded, LinkStop.HostFailed to EndReason.HostsGameStopped)) {
            for (state in free) assertEquals(Session.Ended(reason), step(state, LinkEvent.Stopped(said)).session, "$state")
            for (state in held) assertEquals(Session.ReleasingFiles(reason, FileRelease.Running), step(state, LinkEvent.Stopped(said)).session, "$state")
        }
    }

    @Test
    fun anOfferHideoutCannotReadStopsTheJoin() {
        assertEquals(
            Step(Session.Stopping(EndReason.LinkFailed(LinkFailure.BadInvite), FileHold.Free, fresh), listOf(Arm(Deadline.Stopping, fresh), StopLink)),
            step(Session.Finding(code, t(1)), LinkEvent.BadOffer),
        )
    }

    @Test
    fun cancellingOrDecliningBeforeAnythingIsHeldDropsTheHelperAndLeavesNothingToDismiss() {
        assertEquals(Step(Session.Idle, listOf(AbandonLink)), step(Session.Finding(code, t(1)), StopRequested))
        assertEquals(Step(Session.Idle, listOf(AbandonLink)), step(Session.Confirming(invite), StopRequested))
    }

    @Test
    fun aHostThatGoesWhileThePlayerReadsTheOfferEndsTheJoin() {
        assertEquals(Step(Session.Ended(EndReason.LinkFailed(LinkFailure.LinkLost)), listOf(JournalEnd)), step(Session.Confirming(invite), LinkEvent.Stopped(LinkStop.LinkLost)))
    }

    @Test
    fun aGuestsEndBeforeLinkingWaitsForTheHelperItAlreadyRuns() {
        for (state in listOf(Session.SettingUp(guestNext), Session.RestartNeeded(guestNext, RestartReason.NotSetUp), Session.Restarting(guestNext, deadline = t(1)))) {
            assertEquals(Step(Session.Stopping(EndReason.PlayerStopped, FileHold.Free, fresh), listOf(Arm(Deadline.Stopping, fresh), StopLink)), step(state, StopRequested), "$state")
            assertEquals(Step(Session.Ended(EndReason.LinkFailed(LinkFailure.LinkLost)), listOf(JournalEnd)), step(state, LinkEvent.Stopped(LinkStop.LinkLost)), "$state")
        }
        for (state in listOf(Session.SettingUp(hostNext), Session.RestartNeeded(hostNext, RestartReason.NotSetUp), Session.Restarting(hostNext, deadline = t(1)))) {
            assertEquals(Step(Session.Ended(EndReason.PlayerStopped), listOf(JournalEnd)), step(state, StopRequested), "$state")
        }
        assertEquals(
            Session.Stopping(EndReason.Refused(Refusal.MenusUnavailable), FileHold.Free, fresh),
            step(Session.SettingUp(guestNext), SetupRead(SetupAnswer.Failed(Refusal.MenusUnavailable))).session,
        )
    }

    @Test
    fun aFetchThatFailsEndsTheJoinAsADownloadThatFailed() {
        val state = Session.Fetching(fetching, GuestLink())
        for ((stop, why) in listOf(LinkStop.FetchFailed to FetchProblem.Connection, LinkStop.FetchMismatch to FetchProblem.Mismatch)) {
            val ended = reduce(state, LinkEvent.Stopped(stop), GameActivity.Closed, fresh)
            assertEquals(Session.Ended(EndReason.Refused(Refusal.Preflight(PreflightProblem.Download(why)))), ended.session)
        }
        assertEquals(Session.Stopping(EndReason.PlayerStopped, FileHold.Free, fresh), reduce(state, StopRequested, GameActivity.Closed, fresh).session)
    }

    @Test
    fun theFixturesCoverEveryKindOfState() {
        val kinds = Session::class.java.declaredClasses.filter { Session::class.java.isAssignableFrom(it) && !it.isInterface }.toSet()
        assertEquals(kinds, everyState.map { it.javaClass }.toSet())
        val owns = OwnGame::class.java.declaredClasses.filter { OwnGame::class.java.isAssignableFrom(it) && !it.isInterface }.toSet()
        assertEquals(owns, everyState.mapNotNull { (it as? Session.Hosting)?.own ?: (it as? Session.Playing)?.own }.map { it.javaClass }.toSet())
    }

    @Test
    fun noStateCanStrandASession() {
        for (state in everyState - Session.Idle) {
            val end = listOf(Recovered, StopRequested, stdinClosed, closed, FilesReleased, StopRequested).fold(state) { session, event -> step(session, event).session }
            assertTrue(end is Session.Ended || end == Session.Idle, "from $state: $end")
        }
    }

    @Test
    fun noStateFailsOnAnyEventAndTheJournalOnlyEndsWithTheSessionOver() {
        for (state in everyState) {
            for (event in everyEvent) {
                val next = step(state, event)
                if (JournalEnd in next.commands) assertTrue(next.session is Session.Ended || (state as? Session.ReleasingFiles)?.next != null, "$state on $event")
                if (ReleaseFiles in next.commands) assertIs<Session.ReleasingFiles>(next.session, "$state on $event")
                next.commands.filterIsInstance<Arm>().forEach { assertEquals(next.session.deadline, it.token, "$state on $event") }
            }
        }
    }

    @Test
    fun everyWaitingStateArmsItsOwnDeadline() {
        val entries = listOf(
            Triple(Session.Idle, HostRequested(folder, Choices.None), Deadline.HostPreparing),
            Triple(Session.SettingUp(hostNext), ready, Deadline.HostAdmitting),
            Triple(Session.HostAdmitting(prepared, port, setup, t(1)), Admitted(game), Deadline.HostStarting),
            Triple(Session.Confirming(invite), JoinConfirmed(), Deadline.GuestPreparing),
            Triple(Session.SettingUp(guestNext), ready, Deadline.Linking),
            Triple(Session.Linking(content, t(1)), LinkEvent.Linked, Deadline.GuestAdmitting),
            Triple(Session.RestartNeeded(hostNext, RestartReason.NotSetUp), RestartConfirmed, Deadline.ClientClosing),
            Triple(hosting(OwnGame.Sending(startingDeadlock = false)), LinkEvent.Entered, Deadline.Joining),
            Triple(hosting(), StopRequested, Deadline.Stopping),
        )
        assertEquals(Deadline.entries.toSet(), entries.map { it.third }.toSet())
        for ((state, event, deadline) in entries) {
            val next = step(state, event)
            assertTrue(Arm(deadline, fresh) in next.commands, "$state on $event")
            assertEquals(fresh, next.session.deadline)
        }
    }

    @Test
    fun anElapsedDeadlineStopsAStateWithAHelperAndEndsOneWithout() {
        val reason = { late: LateStage -> EndReason.TimedOut(late) }
        val held = listOf(
            Session.HostStarting(null, null, t(1)) to LateStage.HostStarting,
            Session.GuestAdmitting(content, GuestLink(), t(1)) to LateStage.Admitting,
        )
        for ((state, late) in held) {
            assertEquals(Session.Stopping(reason(late), FileHold.Held, fresh), step(state, DeadlineElapsed(t(1))).session)
        }
        assertEquals(Session.Stopping(reason(LateStage.Linking), FileHold.Free, fresh), step(Session.Linking(content, t(1)), DeadlineElapsed(t(1))).session)
        assertEquals(Step(Session.Ended(reason(LateStage.HostPreparing)), listOf(CancelPrepare, JournalEnd)), step(Session.HostPreparing(t(1)), DeadlineElapsed(t(1))))
        assertEquals(
            Step(Session.ReleasingFiles(reason(LateStage.Admitting), FileRelease.Running), listOf(ReleaseFiles)),
            step(Session.HostAdmitting(prepared, port, setup, t(1)), DeadlineElapsed(t(1))),
        )
        assertEquals(
            Step(Session.Stopping(reason(LateStage.GuestPreparing), FileHold.Free, fresh), listOf(CancelPrepare, Arm(Deadline.Stopping, fresh), StopLink)),
            step(Session.GuestPreparing(invite, t(1)), DeadlineElapsed(t(1))),
        )
    }

    @Test
    fun aStaleTokenChangesNothingInAnyState() {
        for (state in everyState) assertUnchanged(state, DeadlineElapsed(t(777)))
    }

    @Test
    fun aDeadlineFromAnEarlierStateCannotFireIntoALaterOne() {
        val joining = hosting(OwnGame.Joining(t(5)))
        val inGame = step(joining, flowing).session
        assertUnchanged(inGame, DeadlineElapsed(t(5)))
        val preparing = step(Session.Idle, HostRequested(folder, Choices.None)).session
        val settingUp = reduce(preparing, RuntimePrepared(prepared, port), GameActivity.Unknown, t(9001)).session
        assertUnchanged(settingUp, DeadlineElapsed(fresh))
    }

    @Test
    fun aStoppingDeadlineKillsTheHelperAndWaitsOn() {
        val state = Session.Stopping(EndReason.GameUpdated, FileHold.Held, t(1))
        assertEquals(
            Step(Session.Stopping(EndReason.GameUpdated, FileHold.Held, fresh), listOf(Arm(Deadline.Stopping, fresh), KillLink)),
            step(state, DeadlineElapsed(t(1))),
        )
    }

    @Test
    fun aHostsSetupThatCannotGoAheadEndsBeforeAnythingIsHeld() {
        assertEquals(
            Step(Session.Ended(EndReason.Refused(Refusal.MenusUnavailable)), listOf(JournalEnd)),
            step(Session.SettingUp(hostNext), SetupRead(SetupAnswer.Failed(Refusal.MenusUnavailable))),
        )
    }

    @Test
    fun aRestartForAGameThatAlreadyClosedGoesStraightToTheSetup() {
        val asking = Session.RestartNeeded(hostNext, RestartReason.MenusDidNotStart)
        assertEquals(Step(Session.SettingUp(hostNext), listOf(SetUpGame(renderer))), step(asking, RestartConfirmed, GameActivity.Closed))
        assertEquals(Step(Session.SettingUp(hostNext), listOf(SetUpGame(renderer))), step(asking, closed), "the player closed it")
        assertUnchanged(asking, running, GameActivity.Running)
    }

    @Test
    fun aCloseHideoutCannotDoIsLeftToThePlayerAndTheSetupFollowsAnyClose() {
        val closing = Session.Restarting(guestNext, deadline = t(1))
        val waiting = Session.Restarting(guestNext, closeFailed = true)
        assertEquals(waiting, step(closing, ClientCloseFailed).session)
        assertEquals(waiting, step(closing, DeadlineElapsed(t(1))).session)
        assertUnchanged(closing, DeadlineElapsed(t(2)))
        assertUnchanged(waiting, running)
        assertEquals(Step(Session.SettingUp(guestNext), listOf(SetUpGame(renderer))), step(waiting, closed))
    }

    @Test
    fun anEnterThePageNeverTookSaysWhyInTermsThePlayerCanActOn() {
        val sending = hosting(OwnGame.Sending(startingDeadlock = false))
        fun out(expired: EnterExpired, client: GameActivity) = assertIs<Session.Hosting>(step(sending, expired, client).session).own
        assertEquals(OwnGame.Out(EntryProblem.SiteUnreachable), out(EnterExpired(siteUnreachable = true, updatePending = false), GameActivity.Running))
        assertEquals(OwnGame.Out(EntryProblem.MenusDidNotStart), out(EnterExpired(siteUnreachable = false, updatePending = false), GameActivity.Running))
        assertEquals(OwnGame.Out(EntryProblem.GameUpdatePending), out(EnterExpired(siteUnreachable = false, updatePending = true), GameActivity.Closed))
        assertEquals(OwnGame.Out(EntryProblem.ClientDidNotStart), out(EnterExpired(siteUnreachable = false, updatePending = false), GameActivity.Closed))
        assertEquals(OwnGame.Out(EntryProblem.Launch(Refusal.Other("no steam"))), assertIs<Session.Hosting>(step(sending, LaunchFailed(Refusal.Other("no steam"))).session).own)
        for (own in listOf(OwnGame.In, OwnGame.Out(), OwnGame.Joining(t(1)))) assertUnchanged(hosting(own), EnterExpired(siteUnreachable = false, updatePending = false))
    }

    @Test
    fun aGameThatTookTheConnectAndNeverShowedUpIsOutOnceItsDeadlinePasses() {
        val joining = playing(OwnGame.Joining(t(1)))
        assertEquals(playing(OwnGame.Out(EntryProblem.DidNotJoin)), step(joining, DeadlineElapsed(t(1))).session)
        assertUnchanged(joining, quiet)
        assertUnchanged(joining, lost)
    }

    @Test
    fun theGamesOwnTrafficSaysWhetherItIsInAndNothingSendsItInButPlay() {
        assertEquals(playing(OwnGame.Out(EntryProblem.Dropped)), step(playing(OwnGame.In), lost).session)
        assertEquals(hosting(OwnGame.Out()), step(hosting(OwnGame.In), quiet).session, "the host left for the hub")
        assertEquals(playing(OwnGame.In), step(playing(OwnGame.Out(EntryProblem.Dropped)), flowing).session, "it came back by itself")
        for (own in listOf(OwnGame.Out(), OwnGame.Out(EntryProblem.Dropped))) {
            assertUnchanged(playing(own), quiet)
            assertUnchanged(playing(own), lost)
            assertUnchanged(playing(own), running, GameActivity.Running)
        }
    }

    @Test
    fun playSendsTheGameInOnlyFromOutAndStartsDeadlockOnlyWhenItIsClosed() {
        for (state in listOf(hosting(OwnGame.Out()), playing(OwnGame.Out(EntryProblem.DidNotJoin)))) {
            assertEquals(sendRunning, step(state, PlayRequested, GameActivity.Running).commands)
            for (client in listOf(GameActivity.Closed, GameActivity.Unknown, GameActivity.Unread)) assertEquals(launchAndSend, step(state, PlayRequested, client).commands, "$client")
        }
        for (own in listOf(OwnGame.Sending(startingDeadlock = false), OwnGame.Sending(startingDeadlock = true), OwnGame.Joining(t(1)), OwnGame.In)) {
            assertUnchanged(hosting(own), PlayRequested, GameActivity.Running)
            assertUnchanged(playing(own), PlayRequested, GameActivity.Running)
        }
        assertUnchanged(playing(OwnGame.Out(), path = null), PlayRequested, GameActivity.Running)
    }

    @Test
    fun aClosedDeadlockIsOutOfTheMatchAndOnlyAGuestInItLeavesTheSession() {
        for (own in listOf(OwnGame.Sending(startingDeadlock = false), OwnGame.Joining(t(1)), OwnGame.In, OwnGame.Out(EntryProblem.MenusDidNotStart))) {
            assertEquals(hosting(OwnGame.Out()), step(hosting(own), closed).session, "$own")
        }
        for (own in listOf(OwnGame.Sending(startingDeadlock = false), OwnGame.Joining(t(1)), OwnGame.Out(EntryProblem.Dropped))) {
            assertEquals(playing(OwnGame.Out()), step(playing(own), closed).session, "$own")
        }
        assertEquals(Session.Stopping(EndReason.GameClosed, FileHold.Held, fresh), step(playing(OwnGame.In), closed).session)
        assertUnchanged(hosting(OwnGame.Sending(startingDeadlock = true)), closed, GameActivity.Closed)
        assertUnchanged(playing(OwnGame.Sending(startingDeadlock = true)), closed, GameActivity.Closed)
    }

    @Test
    fun aServerFailureMovesToStoppingNeverStraightToEnded() {
        val failed = server(ServerEvent.Failed("boom"))
        val stopping = Session.Stopping(EndReason.HostFailed("boom"), FileHold.Held, fresh)
        assertEquals(Step(stopping, listOf(Arm(Deadline.Stopping, fresh), StopLink)), step(hosting(), failed))
        assertEquals(stopping, step(Session.HostStarting(null, null, t(1)), failed).session)
        assertUnchanged(stopping, failed)
    }

    @Test
    fun aReleaseNeverWaitsForTheClientAndKeepsTheJournalUntilEnded() {
        val working = Session.ReleasingFiles(EndReason.GameUpdated, FileRelease.Running)
        assertEquals(Step(working, listOf(ReleaseFiles)), step(Session.Stopping(EndReason.GameUpdated, FileHold.Held, t(1)), stdinClosed, GameActivity.Running))
        assertUnchanged(working, running)
        assertUnchanged(working, closed)
        assertEquals(Step(Session.Ended(EndReason.GameUpdated), listOf(JournalEnd)), step(working, FilesReleased))
    }

    @Test
    fun aFailedReleaseStaysWithTheJournalInPlaceUntilARetryWorks() {
        val working = Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running)
        val failed = Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Failed("locked"))
        assertEquals(Step(failed), step(working, ReleaseFailed("locked")))
        assertUnchanged(failed, FilesReleased)
        assertUnchanged(failed, running)
        assertEquals(Step(working, listOf(ReleaseFiles)), step(failed, StopRequested))
        assertEquals(Step(working, listOf(ReleaseFiles)), step(failed, closed))
    }

    @Test
    fun hostingNeedsBothFactsInEitherOrder() {
        val waiting = Session.HostStarting(null, null, t(1))
        val serverReady = server(ServerEvent.Ready("street"))
        val inviteReady = LinkEvent.InviteReady(code)
        val readyFirst = step(waiting, serverReady).session
        assertEquals(Session.HostStarting(null, "street", t(1)), readyFirst)
        val entering = Step(hosting(OwnGame.Sending(startingDeadlock = false)), sendRunning)
        assertEquals(entering, step(readyFirst, inviteReady, GameActivity.Running))
        val inviteFirst = step(waiting, inviteReady).session
        assertEquals(Session.HostStarting(code, null, t(1)), inviteFirst)
        assertEquals(entering, step(inviteFirst, serverReady, GameActivity.Running))
    }

    @Test
    fun anEmptyMapStillCountsAsReady() {
        val readyFirst = step(Session.HostStarting(code, null, t(1)), server(ServerEvent.Ready(""))).session
        assertEquals("", assertIs<Session.Hosting>(readyFirst).map)
    }

    @Test
    fun aGameUpdateWhileHostingStops() {
        assertEquals(
            Step(Session.Stopping(EndReason.GameUpdated, FileHold.Held, fresh), listOf(Arm(Deadline.Stopping, fresh), StopLink)),
            step(hosting(), SessionEvent.GameUpdated),
        )
    }

    @Test
    fun anUnknownActivityChangesNothing() {
        for (state in everyState) {
            assertUnchanged(state, unknown)
            assertUnchanged(state, ClientActivity(GameActivity.Unread))
        }
    }

    @Test
    fun aHostKnowsWhoIsInWhichSlotUntilTheyLeave() {
        val friend = Player("Ann", "76561198000000001")
        val joined = step(hosting(), server(ServerEvent.Joined(1, friend))).session
        assertEquals(mapOf(1 to friend), assertIs<Session.Hosting>(joined).players)
        assertFalse(friend.steamId in joined.toString() || friend.name in joined.toString(), "who it is goes in no log: $joined")
        assertEquals(hosting(), step(joined, server(ServerEvent.Left(1))).session)
    }

    @Test
    fun onlyHumansCountAsPlayersAndAReJoinIsNotCountedTwice() {
        val ann = Player("Ann", "7")
        val one = hosting(players = mapOf(1 to ann))
        assertEquals(one, step(one, server(ServerEvent.Joined(1, ann))).session, "the same slot")
        assertEquals(hosting(players = mapOf(3 to ann)), step(one, server(ServerEvent.Joined(3, ann))).session, "a new slot")
        assertEquals(one, step(one, server(ServerEvent.Joined(5, null))).session, "a bot in a free slot")
        assertEquals(one, step(one, server(ServerEvent.Left(5))).session, "a bot left")
    }

    @Test
    fun aServerFailureWithNoWordsEndsWithNoMessage() {
        val silent = listOf(
            """{"type":"server","event":{"host":{"failed":{}}}}""",
            """{"type":"server","event":{"host":{"failed":{"error":"  "}}}}""",
            """{"type":"server","event":{"sandbox":{}}}""",
            """{"type":"server","event":{"sandbox":{"reason":""}}}""",
        )
        for (line in silent) {
            val stopping = step(hosting(), checkNotNull(parseLinkEvent(line))).session
            assertEquals(EndReason.HostFailed(null), assertIs<Session.Stopping>(stopping).reason, line)
        }
        val exited = checkNotNull(parseLinkEvent("""{"type":"stopped","reason":"server-exited","message":" "}"""))
        assertEquals(EndReason.HostFailed(null), assertIs<Session.ReleasingFiles>(step(hosting(), exited).session).reason)
    }

    @Test
    fun aJoinTakesTheSlotFromWhoeverHeldIt() {
        val one = hosting(players = mapOf(1 to Player("Ann", "7")))
        assertEquals(hosting(), step(one, server(ServerEvent.Joined(1, null))).session, "a bot is no one")
        val bob = Player("Bob", "8")
        assertEquals(hosting(players = mapOf(1 to bob)), step(one, server(ServerEvent.Joined(1, bob))).session)
    }

    @Test
    fun aHelperStopInAnAdmittedStateReleasesWithTheReasonItMapsTo() {
        val admitted = listOf(Session.HostStarting(null, null, t(1)), hosting(), Session.GuestAdmitting(content, GuestLink(), t(1)), playing(), playing(OwnGame.Out()))
        val reasons = mapOf(
            LinkEvent.Stopped(LinkStop.StdinClosed) to EndReason.PlayerStopped,
            LinkEvent.Stopped(LinkStop.ServerExited, "crashed") to EndReason.HostFailed("crashed"),
            LinkEvent.Stopped(LinkStop.ServerStartFailed) to EndReason.HostFailed(null),
            LinkEvent.Stopped(LinkStop.ServerDead) to EndReason.ServerDied,
            LinkEvent.Stopped(LinkStop.Signal) to EndReason.LinkFailed(LinkFailure.Signal),
            LinkEvent.Stopped(LinkStop.LinkLost) to EndReason.LinkFailed(LinkFailure.LinkLost),
            LinkEvent.Stopped(LinkStop.BadInvite) to EndReason.LinkFailed(LinkFailure.BadInvite),
            LinkEvent.Stopped(LinkStop.Unknown) to EndReason.LinkFailed(LinkFailure.Unknown),
        )
        for (state in admitted) {
            for ((stopped, reason) in reasons) {
                assertEquals(Step(Session.ReleasingFiles(reason, FileRelease.Running), listOf(ReleaseFiles)), step(state, stopped), "$state on $stopped")
            }
        }
    }

    @Test
    fun aHostHearsWhichPathItsGuestsTakeWheneverTheyArrive() {
        val paths = GuestPaths(direct = 1, relay = 2)
        val heard = LinkEvent.Guests(paths)
        assertEquals(hosting().copy(guests = paths), step(hosting(), heard).session)

        val starting = Session.HostStarting(code, null, t(1))
        val earlyGuest = step(starting, heard).session
        assertEquals(starting.copy(guests = paths), earlyGuest)
        assertEquals(paths, assertIs<Session.Hosting>(step(earlyGuest, server(ServerEvent.Ready("street"))).session).guests)
    }

    @Test
    fun aHelperStopKeepsAReasonAlreadyInStopping() {
        val stopping = Session.Stopping(EndReason.GameUpdated, FileHold.Held, t(1))
        for (stopped in listOf(stdinClosed, LinkEvent.Stopped(LinkStop.Signal), LinkEvent.Stopped(LinkStop.ServerExited))) {
            assertEquals(Session.ReleasingFiles(EndReason.GameUpdated, FileRelease.Running), step(stopping, stopped).session, "$stopped")
        }
    }

    @Test
    fun aLinkingGuestWaitsOnlyForTheLinkWhateverTheGameDoes() {
        val linking = Session.Linking(content, t(1))
        assertUnchanged(linking, closed)
        assertUnchanged(linking, running)
        assertUnchanged(linking, LinkEvent.Path(LinkPath.Direct))
    }

    @Test
    fun aGuestsRefusedAdmissionStopsTheHelperThenEndsWithoutWaitingForTheGame() {
        val stopping = step(Session.GuestAdmitting(content, GuestLink(), t(1)), AdmissionRefused(Refusal.Other("locked")))
        assertEquals(Session.Stopping(EndReason.Refused(Refusal.Other("locked")), FileHold.Free, fresh), stopping.session)
        assertEquals(listOf(Arm(Deadline.Stopping, fresh), StopLink), stopping.commands)
        assertEquals(Step(Session.Ended(EndReason.Refused(Refusal.Other("locked"))), listOf(JournalEnd)), step(stopping.session, stdinClosed))
    }

    @Test
    fun aGuestNotYetAdmittedLeavesWithoutWaitingForTheirOwnGame() {
        val linking = Session.Linking(content, t(1))
        val stopping = step(linking, StopRequested).session
        assertEquals(Session.Stopping(EndReason.PlayerStopped, FileHold.Free, fresh), stopping)
        assertEquals(Step(Session.Ended(EndReason.PlayerStopped), listOf(JournalEnd)), step(stopping, stdinClosed))
        assertEquals(Step(Session.Ended(EndReason.LinkFailed(LinkFailure.LinkLost)), listOf(JournalEnd)), step(linking, LinkEvent.Stopped(LinkStop.LinkLost)))
    }

    @Test
    fun aHostsRefusedAdmissionEndsTheSession() {
        assertEquals(
            Step(Session.Ended(EndReason.Refused(Refusal.Library(LibraryProblem.CustomGameRunning))), listOf(JournalEnd)),
            step(Session.HostAdmitting(prepared, port, setup, t(1)), AdmissionRefused(Refusal.Library(LibraryProblem.CustomGameRunning))),
        )
    }

    @Test
    fun preparingHoldsNothingSoAHostEndsAtOnceAndAGuestOnceItsHelperStops() {
        assertEquals(Step(Session.Ended(EndReason.PlayerStopped), listOf(CancelPrepare, JournalEnd)), step(Session.HostPreparing(t(1)), StopRequested))
        assertEquals(Step(Session.Ended(EndReason.Refused(Refusal.Other("disk full"))), listOf(JournalEnd)), step(Session.HostPreparing(t(1)), RuntimeFailed(Refusal.Other("disk full"))))
        assertEquals(
            Step(Session.Stopping(EndReason.PlayerStopped, FileHold.Free, fresh), listOf(CancelPrepare, Arm(Deadline.Stopping, fresh), StopLink)),
            step(Session.GuestPreparing(invite, t(1)), StopRequested),
        )
        assertEquals(
            Step(Session.Stopping(EndReason.Refused(Refusal.Other("no helper")), FileHold.Free, fresh), listOf(Arm(Deadline.Stopping, fresh), StopLink)),
            step(Session.GuestPreparing(invite, t(1)), RuntimeFailed(Refusal.Other("no helper"))),
        )
        assertEquals(Step(Session.Ended(EndReason.LinkFailed(LinkFailure.LinkLost)), listOf(CancelPrepare, JournalEnd)), step(Session.GuestPreparing(invite, t(1)), LinkEvent.Stopped(LinkStop.LinkLost)))
    }

    @Test
    fun aStopDuringHostAdmissionReleasesWhatTheAdmissionMayTake() {
        val stopped = step(Session.HostAdmitting(prepared, port, setup, t(1)), StopRequested)
        assertEquals(Step(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running), listOf(ReleaseFiles)), stopped)
        assertEquals(Step(stopped.session), step(stopped.session, Admitted(game)))
    }

    @Test
    fun anAdmissionRefusedWhileTheHelperStopsLeavesNothingToRelease() {
        val stopping = Session.Stopping(EndReason.PlayerStopped, FileHold.Held, t(1))
        val refused = step(stopping, AdmissionRefused(Refusal.Other("no"))).session
        assertEquals(Step(Session.Ended(EndReason.PlayerStopped), listOf(JournalEnd)), step(refused, stdinClosed))
    }

    @Test
    fun onlyAnInterruptedGamesReleaseKeepsACodeForAfter() {
        val working = Session.ReleasingFiles(EndReason.Interrupted, FileRelease.Running)
        val kept = step(working, InviteOpened(code)).session
        assertEquals(working.copy(next = code), kept)
        assertEquals(Step(Session.Finding(code, fresh), listOf(JournalEnd, Arm(Deadline.Linking, fresh), FindGame(code))), step(kept, FilesReleased))
        assertUnchanged(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running), InviteOpened(code))
        assertUnchanged(Session.Recovering, InviteOpened(code))
    }

    @Test
    fun aJournalLeftByACrashGoesThroughRelease() {
        assertEquals(Session.Recovering, step(Session.Idle, CrashFound).session)
        assertEquals(Step(Session.ReleasingFiles(EndReason.Interrupted, FileRelease.Running), listOf(ReleaseFiles)), step(Session.Recovering, Recovered))
    }

    @Test
    fun aSessionOrCommandPrintedToALogHidesTheInvite() {
        val texts = listOf(Session.Finding(code, t(1)), Session.Confirming(invite), FindGame(code), LinkEvent.InviteReady(code), hosting(), InviteOpened(code)).map { it.toString() }
        for (text in texts) assertFalse(code.value in text || code.shown in text, text)
    }

    @Test
    fun aHostRequestStartsFromIdleOrReplacesAnEndedGame() {
        for (state in everyState.filterNot { it == Session.Idle || it is Session.Ended }) assertUnchanged(state, HostRequested(folder, Choices.None))
        val again = HostRequested(FakeCustomGamesFeed.SampleMode, Choices.None)
        assertEquals(step(Session.Idle, again), step(Session.Ended(EndReason.GameClosed), again), "no dismiss needed first")
        assertEquals(Session.Idle, step(Session.Ended(EndReason.GameClosed), Dismissed).session)
    }

    @Test
    fun aCodeOnlyEverLooksForAGameAndOnlyWhenNothingIsHeld() {
        val other = assertNotNull(InviteCode.parse("ABC-DEF-GH2"))
        val looking = listOf(Arm(Deadline.Linking, fresh), FindGame(other))
        for (state in listOf(Session.Idle, Session.Ended(EndReason.GameClosed))) {
            assertEquals(Step(Session.Finding(other, fresh), looking), step(state, InviteOpened(other)), "from $state")
        }
        for (state in listOf(Session.Finding(code, t(1)), Session.Confirming(invite))) {
            assertEquals(Step(Session.Finding(other, fresh), listOf(AbandonLink) + looking), step(state, InviteOpened(other)), "the newest link replaces $state")
            assertUnchanged(state, InviteOpened(code))
        }
        val atRest = { state: Session -> state is Session.Idle || state is Session.Ended || state is Session.Finding || state is Session.Confirming || (state as? Session.ReleasingFiles)?.reason == EndReason.Interrupted }
        for (state in everyState.filterNot(atRest)) assertUnchanged(state, InviteOpened(other))
    }

    @Test
    fun theSidebarKeepsThePageWhileASessionRunsWithTheSettingOff() {
        val running = listOf(
            Session.HostPreparing(t(1)),
            Session.RestartNeeded(hostNext, RestartReason.NotSetUp),
            Session.HostAdmitting(prepared, port, setup, t(1)),
            Session.Finding(code, t(1)),
            Session.Confirming(invite),
            Session.GuestPreparing(invite, t(1)),
            playing(OwnGame.Out()),
            Session.Stopping(EndReason.PlayerStopped, FileHold.Held, t(1)),
            Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running),
        )
        for (session in running) assertTrue(customGamesInSidebar(false, session), "$session")
        for (setting in listOf(false, null)) {
            assertFalse(customGamesInSidebar(setting, Session.Idle))
            assertFalse(customGamesInSidebar(setting, Session.Ended(EndReason.PlayerStopped)))
        }
        for (session in running + Session.Idle) assertTrue(customGamesInSidebar(true, session), "$session")
    }

    @Test
    fun aStageThatFailsReleasesTheHoldItAlreadyTook() {
        assertEquals(
            Step(Session.ReleasingFiles(EndReason.Refused(Refusal.ContentNotStaged), FileRelease.Running), listOf(ReleaseFiles)),
            step(Session.HostAdmitting(prepared, port, setup, t(1)), StageFailed(Refusal.ContentNotStaged)),
        )
        val stopping = assertIs<Session.Stopping>(step(Session.GuestAdmitting(content, GuestLink(), t(1)), StageFailed(Refusal.ContentNotStaged)).session)
        assertEquals(EndReason.Refused(Refusal.ContentNotStaged), stopping.reason)
        assertEquals(FileHold.Held, stopping.hold)
    }
}
