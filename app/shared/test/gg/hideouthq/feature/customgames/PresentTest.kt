package gg.hideouthq.feature.customgames

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.DeadlineToken
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.FileHold
import gg.hideouthq.core.customgames.FileRelease
import gg.hideouthq.core.customgames.EntryProblem
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.GuestLink
import gg.hideouthq.core.customgames.InstalledMode
import gg.hideouthq.core.customgames.Invite
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.LateStage
import gg.hideouthq.core.customgames.LinkEvent
import gg.hideouthq.core.customgames.LinkFailure
import gg.hideouthq.core.customgames.LinkPath
import gg.hideouthq.core.customgames.LinkStop
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.ModeContent
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.OwnGame
import gg.hideouthq.core.customgames.OfferContent
import gg.hideouthq.core.customgames.Player
import gg.hideouthq.core.customgames.PreflightProblem
import gg.hideouthq.core.customgames.PreparedHost
import gg.hideouthq.core.customgames.Prepared
import gg.hideouthq.core.customgames.RecordContext
import gg.hideouthq.core.customgames.Refusal
import gg.hideouthq.core.customgames.RestartReason
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.ScoreSide
import gg.hideouthq.core.customgames.SectionKind
import gg.hideouthq.core.customgames.SectionState
import gg.hideouthq.core.customgames.ServerEvent
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SessionEvent
import gg.hideouthq.core.customgames.SettingValue
import gg.hideouthq.core.customgames.SetupAnswer
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.download
import gg.hideouthq.core.customgames.recordAfter
import gg.hideouthq.core.customgames.reduce
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.designsystem.component.StepStatus
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.LibraryProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PresentTest {
    private val bebop = FakeCustomGamesFeed.BebopWars
    private val me = LocalPlayer("76561198000000001", DisplayName.parse("Mugi"))
    private val nori = Player("nori", "76561198000000002")
    private val self = Player("Mugi", me.steamId)
    private val token = DeadlineToken(1)
    private val at = Instant.fromEpochSeconds(1_800_000_000)
    private val site = checkNotNull(SiteOrigin.parse("https://hideout.example"))
    private val modes = ModeList(feed = FakeCustomGamesFeed.Placeholder)

    private fun picks(vararg pairs: Pair<String, String>): Choices = pairs.fold(Choices.defaultsOf(bebop.listing)) { choices, (key, value) ->
        choices.with(bebop.listing.settings.first { it.key.value == key }, checkNotNull(SettingValue.parse(value)))
    }

    private val oneVsOne = picks("teams" to "1v1")
    private val offer = InviteOffer.of(bebop, oneVsOne, me)
    private val code = checkNotNull(InviteCode.parse("P95-MAN-26D"))
    private val friendsOffer = InviteOffer(DisplayName.parse("nori"), bebop.id, OfferContent.Feed((bebop.origin as ModeOrigin.Feed).client?.sha256), oneVsOne, 2)
    private val friendsCode = checkNotNull(InviteCode.parse("ABC-DEF-GH2"))
    private val friendsInvite = Invite(friendsCode, friendsOffer)
    private val content = ModeContent(emptyList())
    private val link = GuestLink(LinkPath.Direct)

    private fun hostRecord(choices: Choices = oneVsOne, started: Boolean = false, reported: Map<SectionKind, SectionState> = emptyMap()) =
        GameRecord(Role.Host(bebop, choices), me, at.takeIf { started }, reported)

    private fun guestRecord(started: Boolean = false) = GameRecord(Role.Guest(friendsCode, friendsOffer, bebop.listing), me, at.takeIf { started })

    private fun present(session: Session, record: GameRecord?, transfer: Transfer? = null, modes: ModeList = this.modes, restarts: RestartReason? = null) =
        present(PresentInputs(LiveSession(session, record), restarts, transfer, modes, site))

    private fun hosting(vararg players: Player, own: OwnGame = OwnGame.In) =
        Session.Hosting(code, "street", players.withIndex().associate { it.index to it.value }, own)

    private fun starting(code: InviteCode? = null, map: String? = null) = Session.HostStarting(code, map, token)

    private fun game(session: Session, record: GameRecord?) = assertNotNull(present(session, record).game)

    private fun steps(body: Body) = assertIs<Body.Starting>(body).steps.map { it.name to it.status }

    @Test
    fun preparingDownloadsTheModeWithItsBytesAndShowsTheHostGettingReady() {
        val view = assertNotNull(present(Session.HostPreparing(token), hostRecord(), Transfer(7_700_000, 12_000_000)).game)
        assertEquals(Status.Starting, view.status)
        val first = assertIs<Body.Starting>(view.body).steps.first()
        assertEquals(StepNote.Downloading(Transfer(7_700_000, 12_000_000)), first.note)
        assertEquals(StepStatus.Running(7_700_000f / 12_000_000), first.status)
        assertEquals(listOf(Presence.GettingReady), view.players?.rows?.map { it.presence })
        assertEquals(1, view.players?.openSpots)
        assertNull(view.invite?.code)
    }

    @Test
    fun admittingSetsUpTheGameFiles() {
        val prepared = PreparedHost("m", "s", emptyList(), content, offer, me)
        val view = game(Session.HostAdmitting(prepared, 1, FakeCustomGamesPlatform.SampleSetup, token), hostRecord())
        assertEquals(StepNote.SettingUpFiles, assertIs<Body.Starting>(view.body).steps.first().note)
    }

    @Test
    fun startingShowsTheCodeOnceItExists() {
        val waiting = game(starting(), hostRecord())
        assertEquals(listOf(StepStatus.Done, StepStatus.Running(), StepStatus.Waiting, StepStatus.Waiting), steps(waiting.body).map { it.second })
        assertEquals(StepNote.Installed("0.1.0"), assertIs<Body.Starting>(waiting.body).steps.first().note)
        assertNull(waiting.invite?.code)
        val coded = game(starting(code, "street"), hostRecord())
        assertEquals(StepStatus.Done, steps(coded.body)[2].second)
        assertEquals("P95-MAN-26D", coded.invite?.code)
        assertEquals(code.webLink(site), coded.invite?.link)
        assertFalse(assertNotNull(coded.invite).live, "the code shows before the game is ready")
    }

    @Test
    fun aHostWhoseGameIsOnItsWayInIsStillStartingAndSaysWhetherDeadlockIsOpening() {
        val view = game(hosting(own = OwnGame.Sending(startingDeadlock = true)), hostRecord(started = true))
        assertEquals(Status.Starting, view.status)
        assertEquals(StepView(StepName.SendGameIn, StepStatus.Running(), StepNote.OpeningDeadlock), assertIs<Body.Starting>(view.body).steps.last())
        assertTrue(assertNotNull(view.invite).live)
        assertNull(assertIs<Body.Starting>(game(hosting(own = OwnGame.Sending(startingDeadlock = false)), hostRecord(started = true)).body).steps.last().note, "a running Deadlock isn't opening")
        assertEquals(StepNote.JoiningGame, assertIs<Body.Starting>(game(hosting(own = OwnGame.Joining(token)), hostRecord(started = true)).body).steps.last().note)
    }

    @Test
    fun aHostWhoseGameWasInAndPressesPlaySeesItGoInWhileTheMatchStaysReady() {
        val inBefore = hostRecord(started = true).copy(players = mapOf(0 to self))
        val sending = game(hosting(own = OwnGame.Sending(startingDeadlock = true)), inBefore)
        assertEquals(Body.Entering(joining = false), sending.body)
        assertEquals(Status.Ready, sending.status, "the match runs on for everyone in it")
        assertEquals(Body.Entering(joining = true), game(hosting(own = OwnGame.Joining(token)), inBefore).body)
    }

    @Test
    fun anOpenGameWithRoomIsReadyWithOpenSpots() {
        val view = game(hosting(self), hostRecord(choices = picks("teams" to "2v2"), started = true))
        assertEquals(Status.Ready, view.status)
        assertEquals(Body.Open(friends = 0), view.body)
        assertEquals(3, view.players?.openSpots)
        assertEquals(4, view.players?.capacity)
        assertTrue(assertNotNull(view.players).rows.single().person.you)
        assertEquals(emptyList(), view.endCutsOff, "alone, End game doesn't ask")
    }

    @Test
    fun anOpenGameWithAFriendInItStopsSayingItWaitsForOne() {
        val view = game(hosting(self, nori), hostRecord(choices = picks("teams" to "2v2"), started = true))
        assertEquals(Body.Open(friends = 1), view.body)
        assertEquals(2, view.players?.openSpots)
    }

    @Test
    fun theHostsOwnRowKeepsItsKeyAsItsClientSignsOn() {
        val before = assertNotNull(game(starting(code, "street"), hostRecord()).players).rows.single()
        val after = assertNotNull(game(hosting(nori, self), hostRecord(started = true)).players).rows.first()
        assertTrue(after.person.you)
        assertEquals(before.key, after.key)
    }

    @Test
    fun aReportedScoreShowsTheMatchBeforeTheGameIsFull() {
        val score = SectionState.Scoreboard(listOf(ScoreSide(3, listOf(0)), ScoreSide(2, listOf(1))), listOf("First to 5"))
        val view = game(hosting(self), hostRecord(choices = picks("teams" to "2v2"), started = true, reported = mapOf(SectionKind.Scoreboard to score)))
        assertEquals(Status.Ready, view.status)
        val board = assertIs<SectionView.Scoreboard>(assertIs<Body.Match>(view.body).sections.single())
        assertEquals(listOf(true, false), board.sides.map { it.yours })
    }

    @Test
    fun aFull1v1IsInMatchAndPausesTheInvite() {
        val view = game(hosting(self, nori), hostRecord(started = true))
        assertEquals(Status.InMatch, view.status)
        assertIs<Body.Match>(view.body)
        assertTrue(assertNotNull(view.invite).paused)
        assertEquals(0, view.players?.openSpots)
        assertEquals(listOf("nori"), view.endCutsOff)
    }

    @Test
    fun aHostWhoseGameIsOutCanPlayAndKeepsTheStatusByCount() {
        val view = game(hosting(self, nori, own = OwnGame.Out()), hostRecord(started = true))
        assertEquals(Body.NotIn(null, canPlay = true), view.body)
        assertEquals(Status.InMatch, view.status)
    }

    @Test
    fun aHostWhoseGameDidNotGetInSaysWhy() {
        val view = game(hosting(own = OwnGame.Out(EntryProblem.GameUpdatePending)), hostRecord(started = true))
        assertEquals(Body.NotIn(EntryProblem.GameUpdatePending, canPlay = true), view.body)
        assertEquals(Status.Ready, view.status)
        assertEquals(listOf(Presence.NotIn), view.players?.rows?.map { it.presence }, "the host's own row while its Deadlock is out")
    }

    @Test
    fun endingFollowsTheReleaseInOneColumn() {
        val record = hostRecord(started = true)
        val stopping = game(Session.Stopping(EndReason.PlayerStopped, FileHold.Held, token), record)
        assertEquals(Body.Ending(EndingStage.Stopping, EndingOf.YourGame), stopping.body)
        assertEquals(Status.Ending, stopping.status)
        assertNull(stopping.players)
        assertEquals(EndingStage.PuttingFilesBack, assertIs<Body.Ending>(game(Session.ReleasingFiles(EndReason.ServerDied, FileRelease.Running), record).body).stage)
        assertIs<EndingStage.Failed>(assertIs<Body.Ending>(game(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Failed("held")), record).body).stage)
    }

    @Test
    fun aGameThatRanEndsWithWhyAndWhen() {
        val record = hostRecord(started = true).copy(endedAt = at)
        val ended = game(Session.Ended(EndReason.PlayerStopped), record)
        assertEquals(Status.Ended, ended.status)
        assertEquals(Outcome.YouEnded(at, Duration.ZERO, emptyList()), assertIs<Body.Ended>(ended.body).outcome)
        assertEquals(Outcome.GameStopped, assertIs<Body.Ended>(game(Session.Ended(EndReason.ServerDied), record).body).outcome)
        assertEquals(Outcome.DeadlockUpdated, assertIs<Body.Ended>(game(Session.Ended(EndReason.GameUpdated), record).body).outcome)
    }

    @Test
    fun aGameThatNeverRanCouldNotStart() {
        val failed = game(Session.Ended(EndReason.Refused(Refusal.MenusUnavailable)), hostRecord())
        assertEquals(Status.CouldNotStart, failed.status)
        assertEquals(Outcome.CouldNotStart(Problem.MenusUnavailable, StartTrace(emptyList(), HostStep.GetMode, bebop.download)), assertIs<Body.Ended>(failed.body).outcome)
        assertNull(failed.players)
    }

    @Test
    fun aHostWhoEndsBeforeTheStartIsBackInTheLibraryWithNothingToSay() {
        assertEquals(Present(null, null, null), present(Session.Ended(EndReason.PlayerStopped), hostRecord()))
    }

    @Test
    fun theProblemTableNamesEachRefusal() {
        assertEquals(Problem.FilesDamaged, problemOf(EndReason.Refused(Refusal.Preflight(PreflightProblem.Download(FetchProblem.Mismatch)))))
        assertEquals(Problem.CouldNotReach, problemOf(EndReason.TimedOut(LateStage.Linking)))
        assertEquals(Problem.TookTooLong, problemOf(EndReason.TimedOut(LateStage.Admitting)))
        assertEquals(Problem.InviteNotWorking, problemOf(EndReason.LinkFailed(LinkFailure.BadInvite)))
        assertEquals(Problem.ArenaFailed, problemOf(EndReason.HostFailed("boom")))
        assertEquals(Problem.ContentNotStaged, problemOf(EndReason.Refused(Refusal.ContentNotStaged)))
        assertEquals(Problem.Unexpected, problemOf(EndReason.Refused(Refusal.Other("odd"))))
        assertEquals(Problem.Unexpected, problemOf(EndReason.Refused(Refusal.Library(LibraryProblem.ProcessesUnknown("no list")))))
        assertNull(problemOf(EndReason.PlayerStopped))
    }

    @Test
    fun confirmingReviewsTheDownloadUnlessTheModeIsInstalledAndSaysWhenJoiningRestartsDeadlock() {
        val review = assertNotNull(present(Session.Confirming(friendsInvite), guestRecord()).join)
        assertEquals(JoinStep.Review(JoinDownload.Bytes(12_000_000), restarts = null), review.step)
        assertEquals("nori", review.host?.value)
        assertEquals(2, review.capacity)
        assertEquals("Bebop Wars", review.face.title)
        assertNull(present(Session.Confirming(friendsInvite), guestRecord()).game)
        val installed = modes.copy(installed = mapOf(bebop.id to InstalledMode(bebop, 12_048_000, null)))
        assertEquals(JoinStep.Review(JoinDownload.OnThisPc, restarts = null), present(Session.Confirming(friendsInvite), guestRecord(), modes = installed).join?.step)
        assertEquals(JoinStep.Review(JoinDownload.OnThisPc, restarts = RestartReason.MenusDidNotStart), present(Session.Confirming(friendsInvite), guestRecord(), modes = installed, restarts = RestartReason.MenusDidNotStart).join?.step)
    }

    @Test
    fun aGuestTurnedAwayForItsFriendsVersionIsToldWhatIsMissingAndWhatTheyCanDo() {
        val noList = problemOf(EndReason.Refused(Refusal.Preflight(PreflightProblem.ListUnreachable)))
        assertEquals(Problem.ModeListUnreachable, noList, "a list it couldn't read, not a version")
        assertTrue(tryAgainHelps(Problem.ModeListUnreachable))
        assertEquals(Problem.DifferentVersion, problemOf(EndReason.Refused(Refusal.Preflight(PreflightProblem.ModeNotListed))))
        assertFalse(tryAgainHelps(Problem.DifferentVersion), "only the friend's new invite helps")
    }

    @Test
    fun theJoinReviewNeverSaysInstalledForADownloadItCannotSizeYet() {
        fun review(offer: InviteOffer, modes: ModeList) =
            (present(Session.Confirming(Invite(friendsCode, offer)), GameRecord(Role.Guest(friendsCode, offer, bebop.listing), me), modes = modes).join?.step as? JoinStep.Review)?.download
        assertEquals(JoinDownload.Unknown, review(friendsOffer, ModeList()), "no list read yet")
        assertEquals(JoinDownload.Bytes(12_000_000), review(friendsOffer, modes), "until the list names it")
        assertEquals(JoinDownload.None, review(friendsOffer.copy(content = OfferContent.Feed(null)), ModeList()))
        val pack = FakeCustomGamesPlatform.SampleShared
        assertEquals(JoinDownload.Bytes(pack.bytes), review(friendsOffer.copy(content = OfferContent.Host(pack)), ModeList()), "the host's pack, which no list names")
    }

    @Test
    fun aDeadlockThatMustRestartAsksTheHostOnTheGameScreenAndTheGuestInTheDialog() {
        val host = Prepared.Host(PreparedHost("m", "s", emptyList(), content, offer, me), 1)
        assertEquals(Body.RestartNeeded(RestartReason.GameUpdated), game(Session.RestartNeeded(host, RestartReason.GameUpdated), hostRecord()).body)
        assertEquals(Body.Restarting(closeFailed = true), game(Session.Restarting(host, closeFailed = true), hostRecord()).body)
        assertEquals(Status.RestartNeeded, game(Session.RestartNeeded(host, RestartReason.NotSetUp), hostRecord()).status, "nothing starts until the player says so")
        assertEquals(Status.Restarting, game(Session.Restarting(host), hostRecord()).status)
        val guest = Prepared.Guest(content, null)
        assertEquals(JoinStep.RestartNeeded(RestartReason.NotSetUp), present(Session.RestartNeeded(guest, RestartReason.NotSetUp), guestRecord()).join?.step)
        assertEquals(JoinStep.Restarting(closeFailed = false), present(Session.Restarting(guest, deadline = token), guestRecord()).join?.step)
    }

    @Test
    fun aStartedGuestIsToldWhatEndedTheirGame() {
        val outcomes = mapOf(
            EndReason.HostEnded to Outcome.FriendEnded,
            EndReason.HostsGameStopped to Outcome.FriendsGameStopped,
            EndReason.LinkFailed(LinkFailure.LinkLost) to Outcome.ConnectionLost,
            EndReason.Interrupted to Outcome.UnknownEnd,
        )
        for ((reason, outcome) in outcomes) {
            assertEquals(outcome, assertIs<Body.Ended>(game(Session.Ended(reason), guestRecord(started = true)).body).outcome, "$reason")
        }
    }

    @Test
    fun aGuestWhoseHostEndedBeforeTheyGotInIsToldSoAndNotOfferedTheSameCode() {
        for (session in listOf(Session.Ended(EndReason.HostEnded), Session.ReleasingFiles(EndReason.HostEnded, FileRelease.Running))) {
            val step = present(session, guestRecord()).join?.step
            assertEquals(Problem.FriendEnded, (step as? JoinStep.Failed)?.problem ?: assertIs<JoinStep.Stopping>(step).problem, "$session")
        }
        assertFalse(tryAgainHelps(Problem.FriendEnded), "the code died with the game")
    }

    @Test
    fun aCleanupThatFailedCanBeOpenedWithOrWithoutARecordOrTheDialog() {
        fun walk(vararg steps: Pair<SessionEvent, GameActivity>) = steps.fold(LiveSession()) { live, (event, client) ->
            val next = reduce(live.session, event, client, token).session
            LiveSession(next, recordAfter(live, event, next, RecordContext(me, { bebop.listing }, at)))
        }
        val closed = GameActivity.Closed
        val crashed = walk(SessionEvent.CrashFound to closed, SessionEvent.Recovered to closed, SessionEvent.ReleaseFailed("held") to closed)
        assertEquals(LiveSession(Session.ReleasingFiles(EndReason.Interrupted, FileRelease.Failed("held")), null), crashed, "a restart's cleanup that failed")
        val recovery = present(PresentInputs(crashed))
        val view = assertNotNull(recovery.game, "a crash's cleanup has a screen")
        assertEquals(Body.Ending(EndingStage.Failed("Putting files back failed: held"), EndingOf.LastGame), view.body)
        assertEquals(Status.CleanupFailed, view.status)
        val chip = assertNotNull(recovery.chip)
        assertTrue(chip.opens, "and the chip opens it")
        assertEquals(Status.CleanupFailed, chip.status)
        val retrying = present(Session.ReleasingFiles(EndReason.Interrupted, FileRelease.Running), null)
        assertEquals(Body.Ending(EndingStage.PuttingFilesBack, EndingOf.LastGame), retrying.game?.body, "Try again keeps the screen")

        val cancelled = walk(
            SessionEvent.InviteOpened(friendsCode) to closed, LinkEvent.Offer(friendsOffer) to closed, SessionEvent.JoinConfirmed() to closed, SessionEvent.GuestPrepared(content, null) to closed,
            SessionEvent.SetupRead(SetupAnswer.Ready(FakeCustomGamesPlatform.SampleSetup)) to closed, LinkEvent.Linked to closed,
            SessionEvent.Admitted(GameRoot("/g")) to closed, SessionEvent.ClientActivity(GameActivity.Running) to GameActivity.Running,
            SessionEvent.StopRequested to GameActivity.Running, LinkEvent.Stopped(LinkStop.StdinClosed) to closed, SessionEvent.ReleaseFailed("held") to closed,
        )
        assertEquals(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Failed("held")), cancelled.session, "a join cancelled before it connected")
        assertFalse(assertNotNull(cancelled.game).started)
        val join = present(PresentInputs(cancelled))
        assertIs<EndingStage.Failed>(assertIs<JoinStep.Stopping>(join.join?.step).stage)
        val behind = assertNotNull(join.game, "a put-away join dialog has the screen behind it")
        assertEquals(Body.Ending(EndingStage.Failed("Putting files back failed: held"), EndingOf.LastGame), behind.body)
        assertTrue(assertNotNull(join.chip).opens)

        assertFalse(assertNotNull(present(Session.Recovering, null).chip).opens, "the old helper going has nothing to show")
    }

    @Test
    fun theChipCountsThePlayersTheGameScreenShows() {
        val present = present(Session.Hosting(code, "street", own = OwnGame.Joining(token)), hostRecord(started = true))
        val players = assertNotNull(present.game?.players)
        assertEquals(listOf(Presence.GettingReady), players.rows.map { it.presence }, "the host's own row while its Deadlock loads")
        assertEquals(1, players.openSpots)
        assertEquals(1 to 2, present.chip?.count)
    }

    @Test
    fun theEndedScoreKeepsYourSideFirstAsTheLiveViewDid() {
        val context = RecordContext(me, { null }, at)
        fun after(live: LiveSession, event: SessionEvent): LiveSession {
            val next = reduce(live.session, event, GameActivity.Running, token).session
            return LiveSession(next, recordAfter(live, event, next, context))
        }
        val score = SectionState.Scoreboard(listOf(ScoreSide(2, listOf(0)), ScoreSide(3, listOf(1))), emptyList())
        val events = listOf(LinkEvent.Server(ServerEvent.Joined(0, nori)), LinkEvent.Server(ServerEvent.Joined(1, self)), LinkEvent.Reported(listOf(score)))
        val hosting = events.fold(LiveSession(Session.Hosting(code, "street", own = OwnGame.In), hostRecord(started = true)), ::after)
        assertEquals(3 to 2, present(PresentInputs(hosting)).chip?.score, "live, yours first")
        val ended = LiveSession(Session.Ended(EndReason.PlayerStopped), recordAfter(hosting, SessionEvent.FilesReleased, Session.Ended(EndReason.PlayerStopped), context))
        val rows = assertNotNull(assertIs<Body.Ended>(assertNotNull(present(PresentInputs(ended)).game).body).lastMatch).rows
        assertEquals(listOf(true to 3, false to 2), rows.map { it.person.you to it.score }, "and after the end")
        assertNull(scoreOf(emptyList()))
    }

    private var clock = at
    private val prepared = PreparedHost("m", "s", emptyList(), content, offer, me)
    private val toStarting = arrayOf(
        SessionEvent.HostRequested(bebop, oneVsOne),
        SessionEvent.RuntimePrepared(prepared, 1),
        SessionEvent.SetupRead(SetupAnswer.Ready(FakeCustomGamesPlatform.SampleSetup)),
        SessionEvent.Admitted(GameRoot("/g")),
    )
    private val arenaUp = LinkEvent.Server(ServerEvent.Ready("street"))
    private val toHosting = toStarting + arenaUp + LinkEvent.InviteReady(code)
    private val hostEnds = arrayOf(SessionEvent.StopRequested, LinkEvent.Stopped(LinkStop.StdinClosed), SessionEvent.FilesReleased)

    private fun walk(vararg events: SessionEvent, start: LiveSession = LiveSession()) = events.fold(start) { live, event ->
        val next = reduce(live.session, event, GameActivity.Closed, token).session
        LiveSession(next, recordAfter(live, event, next, RecordContext(me, { bebop.listing }, clock)))
    }

    private fun joined(slot: Int, player: Player) = LinkEvent.Server(ServerEvent.Joined(slot, player))

    private fun board(vararg sides: Pair<Int, Int>) =
        LinkEvent.Reported(listOf(SectionState.Scoreboard(sides.map { (score, slot) -> ScoreSide(score, listOf(slot)) }, listOf("First to 25"))))

    private fun ended(live: LiveSession, modes: ModeList = this.modes) = assertIs<Body.Ended>(assertNotNull(present(PresentInputs(live, modes = modes)).game).body)

    @Test
    fun theLastMatchNamesEveryoneWhoPlayedBestFirstEvenAfterTheyLeft() {
        val hosting = walk(*toHosting, joined(0, self), joined(1, nori), board(25 to 0, 18 to 1), LinkEvent.Server(ServerEvent.Left(1)))
        val match = assertNotNull(ended(walk(*hostEnds, start = hosting)).lastMatch)
        assertEquals("First to 25", match.note, "the mode's own words, never a target Hideout made up")
        assertEquals(listOf(Triple("Mugi", 25, Finish.Won), Triple("nori", 18, Finish.Behind)), match.rows.map { Triple(it.person.name, it.score, it.finish) })
        assertEquals(listOf(true, false), match.rows.map { it.person.host })
        val level = walk(*toHosting, joined(0, self), joined(1, nori), board(5 to 0, 5 to 1))
        assertEquals(listOf(Finish.Level, Finish.Level), ended(walk(*hostEnds, start = level)).lastMatch?.rows?.map { it.finish }, "a tie has no winner")
    }

    @Test
    fun theLastMatchHidesWhenNobodyElsePlayedOrNoScoreCame() {
        assertNull(ended(walk(*toHosting, joined(0, self), board(3 to 0), *hostEnds)).lastMatch, "nobody joined")
        assertNull(ended(walk(*toHosting, joined(0, self), joined(1, nori), *hostEnds)).lastMatch, "the mode reported no score")
        assertNull(assertIs<Body.Ended>(game(Session.Ended(EndReason.PlayerStopped), guestRecord(started = true)).body).lastMatch, "a guest sees no score")
    }

    @Test
    fun youEndedSaysHowLongItRanAndWhoItSentBack() {
        val ann = Player("Ann", "76561198000000003")
        val hosting = walk(*toHosting, joined(0, self), joined(1, nori), joined(2, ann), LinkEvent.Server(ServerEvent.Left(2)))
        clock = at + 14.minutes
        val outcome = ended(walk(*hostEnds, start = hosting)).outcome
        assertEquals(Outcome.YouEnded(at + 14.minutes, 14.minutes, listOf("nori")), outcome, "Ann had left, and the host isn't sent anywhere")
    }

    @Test
    fun aStartThatFailedShowsItsStepsUpToTheFailedOne() {
        fun trace(vararg events: SessionEvent) = assertNotNull(assertIs<Outcome.CouldNotStart>(ended(walk(*events)).outcome).trace).let { it.worked to it.failed }
        val refused = arrayOf(SessionEvent.HostRequested(bebop, oneVsOne), SessionEvent.RuntimeFailed(Refusal.Preflight(PreflightProblem.SteamNotFound)))
        assertEquals(emptyList<HostStep>() to HostStep.GetMode, trace(*refused))
        val released = SessionEvent.FilesReleased
        assertEquals(listOf(HostStep.GetMode) to HostStep.StartServer, trace(*toStarting, LinkEvent.Stopped(LinkStop.ServerStartFailed), released))
        assertEquals(listOf(HostStep.GetMode, HostStep.StartServer) to HostStep.OpenToFriends, trace(*toStarting, arenaUp, LinkEvent.Stopped(LinkStop.RelayUnavailable), released))
        assertEquals(listOf(HostStep.GetMode) to HostStep.OpenToFriends, trace(*toStarting, LinkEvent.Stopped(LinkStop.LinkLost), released), "an arena that never loaded isn't shown as done")
        val late = arrayOf(SessionEvent.DeadlineElapsed(token), LinkEvent.Stopped(LinkStop.StdinClosed), released)
        assertEquals(listOf(HostStep.GetMode) to HostStep.StartServer, trace(*toStarting, *late), "a late start waited on the arena")
        assertEquals(listOf(HostStep.GetMode, HostStep.StartServer) to HostStep.OpenToFriends, trace(*toStarting, arenaUp, *late), "or, once it loaded, on friends")
    }

    @Test
    fun theSideColumnOffersOtherModesInstalledFirstOrAWayToReportAFailure() {
        fun other(id: String) = bebop.copy(id = ModeId(id), listing = bebop.listing.copy(name = id))
        val installed = other("d")
        val feed = FakeCustomGamesFeed.Placeholder.copy(modes = listOf(bebop, other("a"), other("b"), other("c"), installed))
        val modes = ModeList(feed = feed, installed = mapOf(installed.id to InstalledMode(installed, 1, null)))
        val played = assertIs<EndedSide.PlaySomethingElse>(ended(walk(*toHosting, *hostEnds), modes).side)
        assertEquals(listOf("d", "a", "b"), played.modes.map { it.mode.id.value }, "installed first, then the feed's order, never the mode just played or one only announced")
        val reason = EndReason.Refused(Refusal.Preflight(PreflightProblem.SteamNotFound))
        val failed = ended(walk(SessionEvent.HostRequested(bebop, oneVsOne), SessionEvent.RuntimeFailed(reason.problem)), modes)
        assertEquals(EndedSide.StillNotWorking(details(reason)), failed.side)
    }

    @Test
    fun aCodeWaitingOnACrashCleanupOpensAfterIt() {
        val present = present(Session.ReleasingFiles(EndReason.Interrupted, FileRelease.Running, friendsCode), null)
        assertEquals(JoinStep.AfterCleanup, present.join?.step)
        assertEquals(HostingTitle.CleaningUp, present.chip?.title)
    }

    @Test
    fun aCodeIsLookedForBeforeAnythingIsShownOfTheGame() {
        val record = GameRecord(Role.Guest(friendsCode, null, null), me)
        val view = assertNotNull(present(Session.Finding(friendsCode, token), record).join)
        assertEquals(JoinStep.Finding, view.step)
        assertNull(view.host)
        assertNull(view.capacity)
        assertNull(quitWarning(LiveSession(Session.Finding(friendsCode, token), record)), "nothing is held yet")
        val notFound = assertIs<JoinStep.Failed>(present(Session.Ended(EndReason.LinkFailed(LinkFailure.GameNotFound)), record).join?.step)
        assertEquals(Problem.GameNotFound, notFound.problem)
        assertTrue(tryAgainHelps(Problem.GameNotFound), "a mistyped code can be typed again")
        val unanswered = assertIs<JoinStep.Failed>(present(Session.Ended(EndReason.TimedOut(LateStage.Linking)), record).join?.step)
        assertEquals(Problem.CouldNotReach, unanswered.problem, "a relay that never said no game is not a missing game")
    }

    @Test
    fun joiningShowsItsStepsWithBytesTheRouteAndTheGameOnItsWayIn() {
        val preparing = assertIs<JoinStep.Progress>(present(Session.GuestPreparing(friendsInvite, token), guestRecord(), Transfer(1, 2)).join?.step)
        assertEquals(StepNote.Downloading(Transfer(1, 2)), preparing.steps.first().note)
        val fetching = assertIs<JoinStep.Progress>(present(Session.Fetching(content.copy(shared = FakeCustomGamesPlatform.SampleShared), link, Transfer(3, 4)), guestRecord()).join?.step)
        assertEquals(listOf(StepStatus.Running(0.75f), StepStatus.Done), fetching.steps.take(2).map { it.status }, "the host's pack comes once linked")
        assertEquals(StepNote.Downloading(Transfer(3, 4)), fetching.steps.first().note)
        val relayed = assertIs<JoinStep.Progress>(present(Session.GuestAdmitting(content, GuestLink(LinkPath.Relay), token), guestRecord()).join?.step)
        assertEquals(StepNote.BackupRoute, relayed.steps[1].note)
        val unknown = assertIs<JoinStep.Progress>(present(Session.GuestAdmitting(content, GuestLink(), token), guestRecord()).join?.step)
        assertNull(unknown.steps[1].note, "no route is named until the link knows it")
        val opening = assertIs<JoinStep.Progress>(present(Session.Playing(link, OwnGame.Sending(startingDeadlock = true)), guestRecord()).join?.step)
        assertEquals(StepNote.OpeningDeadlock, opening.steps.last().note)
        val connecting = assertIs<JoinStep.Progress>(present(Session.Playing(link, OwnGame.Joining(token)), guestRecord()).join?.step)
        assertEquals(StepNote.JoiningGame, connecting.steps.last().note)
        assertNull(present(Session.Playing(link, OwnGame.Joining(token)), guestRecord()).chip, "a guest who hasn’t started has no chip")
    }

    @Test
    fun aJoinWhoseGameNeverGotInOffersPlayOnlyWhileTheHostAnswers() {
        val answering = present(Session.Playing(link, OwnGame.Out(EntryProblem.DidNotJoin)), guestRecord())
        assertEquals(JoinStep.NotIn(EntryProblem.DidNotJoin, canPlay = true), answering.join?.step)
        assertNull(answering.game)
        val dropped = present(Session.Playing(link, OwnGame.Out()), guestRecord(started = true)).game
        assertEquals(Presence.NotIn, dropped?.players?.rows?.single { it.person.you }?.presence, "a guest whose Deadlock dropped out")
        assertEquals(JoinStep.NotIn(EntryProblem.DidNotJoin, canPlay = false), present(Session.Playing(GuestLink(), OwnGame.Out(EntryProblem.DidNotJoin)), guestRecord()).join?.step)
    }

    @Test
    fun cancellingAJoinSaysSoThenClosesQuietly() {
        val stopping = present(Session.Stopping(EndReason.PlayerStopped, FileHold.Free, token), guestRecord())
        assertEquals(JoinStep.Stopping(EndingStage.Stopping, null), stopping.join?.step)
        assertEquals(HostingTitle.CleaningUp, stopping.chip?.title, "the chip says so once the dialog is put away")
        assertEquals(HostingTitle.CleaningUp, present(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Failed("held")), guestRecord()).chip?.title)
        assertNull(present(Session.Ended(EndReason.PlayerStopped), guestRecord()).join)
    }

    @Test
    fun aJoinThatFailsSaysWhyWhileItsFilesGoBack() {
        val step = present(Session.ReleasingFiles(EndReason.LinkFailed(LinkFailure.LinkLost), FileRelease.Running), guestRecord()).join?.step
        assertEquals(JoinStep.Stopping(EndingStage.PuttingFilesBack, Problem.CouldNotReach), step)
    }

    @Test
    fun aJoinThatFailsSaysWhy() {
        val failed = assertIs<JoinStep.Failed>(present(Session.Ended(EndReason.LinkFailed(LinkFailure.LinkLost)), guestRecord()).join?.step)
        assertEquals(Problem.CouldNotReach, failed.problem)
        assertFalse(friendsCode.value in failed.details || friendsCode.shown in failed.details, "details never hold the code")
    }

    @Test
    fun aConnectedGuestIsInMatchWithTheHostFirst() {
        val present = present(Session.Playing(link, OwnGame.In), guestRecord(started = true))
        val view = assertNotNull(present.game)
        assertNull(present.join)
        assertEquals(Status.InMatch, view.status)
        assertEquals(HostedBy.Friend(DisplayName.parse("nori")), view.host)
        assertEquals(listOf(true, false), view.players?.rows?.map { it.person.host })
        assertEquals(LinkPath.Direct, view.players?.rows?.last()?.path)
        assertNull(view.invite)
        assertEquals(HostingTitle.InGame(DisplayName.parse("nori")), present.chip?.title)
    }

    @Test
    fun aDroppedGuestCanPlayOnlyWhileTheHostAnswersAndFollowsItsGameBackIn() {
        val answering = game(Session.Playing(link, OwnGame.Out(EntryProblem.Dropped)), guestRecord(started = true))
        assertEquals(Status.LostConnection, answering.status)
        assertEquals(Body.NotIn(EntryProblem.Dropped, canPlay = true), answering.body)
        assertEquals(Body.NotIn(EntryProblem.Dropped, canPlay = false), game(Session.Playing(GuestLink(), OwnGame.Out(EntryProblem.Dropped)), guestRecord(started = true)).body)
        val sending = game(Session.Playing(link, OwnGame.Sending(startingDeadlock = false)), guestRecord(started = true))
        assertEquals(Body.Entering(joining = false), sending.body)
        assertEquals(Status.Joining, sending.status)
        assertEquals(Body.Entering(joining = true), game(Session.Playing(link, OwnGame.Joining(token)), guestRecord(started = true)).body)
    }

    @Test
    fun aGuestWhoLeftIsNeverToldThatTheGameEnded() {
        val leaving = game(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running), guestRecord(started = true))
        assertEquals(Status.Leaving, leaving.status)
        assertEquals(Body.Ending(EndingStage.PuttingFilesBack, EndingOf.YouLeft), leaving.body)
        assertEquals(Outcome.YouLeft, assertIs<Body.Ended>(game(Session.Ended(EndReason.PlayerStopped), guestRecord(started = true)).body).outcome)
        assertEquals(Outcome.DeadlockClosed, assertIs<Body.Ended>(game(Session.Ended(EndReason.GameClosed), guestRecord(started = true)).body).outcome)
    }

    @Test
    fun theChipCleansUpAfterACrashAndHidesWhenIdle() {
        assertEquals(Status.CleaningUp, present(Session.Recovering, null).chip?.status)
        assertEquals(Present(null, null, null), present(Session.Idle, null))
    }

    @Test
    fun theHostChipCountsOnlyWhileHostingAndOffersTheInviteWhileThereIsRoom() {
        assertNull(present(starting(), hostRecord()).chip?.count)
        assertNull(present(starting(code), hostRecord()).chip?.invite, "nobody can join a game that is still starting")
        val open = assertNotNull(present(hosting(self), hostRecord(started = true)).chip)
        assertEquals(1 to 2, open.count)
        assertEquals(code.webLink(site), open.invite?.link)
        assertEquals(HostingTitle.Hosting("Bebop Wars"), open.title)
        assertNull(present(hosting(self, nori), hostRecord(started = true)).chip?.invite?.link, "a full game has no room")
    }

    @Test
    fun theChipScorePutsThePlayersSideFirst() {
        val score = SectionState.Scoreboard(listOf(ScoreSide(2, listOf(1)), ScoreSide(3, listOf(0))), emptyList())
        val chip = present(hosting(self, nori), hostRecord(started = true, reported = mapOf(SectionKind.Scoreboard to score))).chip
        assertEquals(3 to 2, chip?.score)
        assertEquals(Status.CouldNotStart, present(Session.Ended(EndReason.ServerDied), hostRecord()).chip?.status)
    }

    @Test
    fun anEndedGamesChipStaysUntilTheGameScreenShowedThatEnd() {
        val ended = present(Session.Ended(EndReason.PlayerStopped), hostRecord(started = true))
        assertNotNull(ended.game)
        assertEquals(Status.Ended, ended.chip?.status)
        assertEquals(Status.Ended, present(Session.Ended(EndReason.ServerDied), guestRecord(started = true)).chip?.status, "a friend’s game that stopped says so from any page")
        assertEquals(Status.CouldNotStart, present(Session.Ended(EndReason.ServerDied), hostRecord()).chip?.status)
        val at = Instant.fromEpochSeconds(100)
        assertTrue(assertNotNull(chipAway(ended, endedAt = at, seenEnd = null)).over, "it ended while the player was on another page")
        assertNull(chipAway(ended, endedAt = at, seenEnd = at), "the game screen showed it")
        assertNotNull(chipAway(ended, endedAt = Instant.fromEpochSeconds(200), seenEnd = at), "a later game’s end is news again")
        val running = present(hosting(self, nori), hostRecord(started = true))
        assertFalse(assertNotNull(chipAway(running, endedAt = null, seenEnd = at)).over)
    }

    @Test
    fun aGameInProgressIsWhatARestartWouldEndAndNamesWhoItCutsOff() {
        assertEquals(GameInProgress.Hosting("Bebop Wars", listOf("nori")), gameInProgress(LiveSession(hosting(self, nori), hostRecord(started = true))))
        assertEquals(GameInProgress.Hosting("Bebop Wars", emptyList()), gameInProgress(LiveSession(starting(code), hostRecord())))
        assertEquals(GameInProgress.Guest(DisplayName.parse("nori")), gameInProgress(LiveSession(Session.Playing(link, OwnGame.In), guestRecord(started = true))))
        assertNull(gameInProgress(LiveSession(Session.Ended(EndReason.PlayerStopped), hostRecord(started = true))))
        assertNull(gameInProgress(LiveSession()))
    }

    @Test
    fun quittingAsksAHostAGuestAndDuringCleanup() {
        assertEquals(QuitWarning.EndsHostedGame, quitWarning(LiveSession(hosting(self), hostRecord(started = true))))
        assertEquals(QuitWarning.LeavesGame, quitWarning(LiveSession(Session.Playing(link, OwnGame.Joining(token)), guestRecord())))
        assertEquals(QuitWarning.LeavesGame, quitWarning(LiveSession(Session.RestartNeeded(Prepared.Guest(content, null), RestartReason.NotSetUp), guestRecord())))
        assertEquals(QuitWarning.ModsComeBackLater, quitWarning(LiveSession(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running), hostRecord())))
        assertNull(quitWarning(LiveSession(Session.Confirming(friendsInvite), guestRecord())))
        assertNull(quitWarning(LiveSession()))
    }
}
