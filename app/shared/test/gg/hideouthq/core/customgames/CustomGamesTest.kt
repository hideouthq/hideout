package gg.hideouthq.core.customgames

import gg.hideouthq.core.analytics.*
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.FakeLinkProcess
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameInfoProblem
import gg.hideouthq.core.library.GamesLines
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.SessionAdmission
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

private class FakeSessionFiles(initial: GameActivity = GameActivity.Closed) : SessionFiles {
    override val clientActivity = MutableStateFlow(initial)
    val calls = mutableListOf<String>()
    var admission: SessionAdmission = SessionAdmission.Held(Game)
    var admitGate: CompletableDeferred<Unit>? = null
    var launchProblem: LibraryProblem? = null
    var releaseProblem: LibraryProblem? = null
    var releaseGate: CompletableDeferred<Unit>? = null
    var closeFails = false

    var held = false

    var lines: GamesLines = GamesLines.Off
    val uses = mutableListOf<Boolean>()
    var useProblem: LibraryProblem? = null
    var startsOnWrite = false
    var useGate: CompletableDeferred<Unit>? = null

    override suspend fun game(): GameRoot = Game

    override suspend fun gamesLines(): GamesLines = lines

    override suspend fun useCustomGames(on: Boolean): LibraryProblem? = answer(on).also { useGate?.await() }

    private fun answer(on: Boolean): LibraryProblem? {
        uses += on
        if (startsOnWrite) clientActivity.value = GameActivity.Running
        if (clientActivity.value == GameActivity.Closed) {
            useProblem?.let { return it }
            lines = if (on) GamesLines.Mounted else GamesLines.Off
            return null
        }
        lines = when {
            on && lines == GamesLines.Off -> GamesLines.Pending
            on && lines == GamesLines.Leaving -> GamesLines.Mounted
            !on && (lines == GamesLines.Pending || lines == GamesLines.Removed) -> GamesLines.Off
            !on && lines == GamesLines.Mounted -> GamesLines.Leaving
            else -> lines
        }
        return if (clientActivity.value == GameActivity.Running) LibraryProblem.GameRunning else LibraryProblem.ProcessesUnknown("unread")
    }

    override suspend fun admit(): SessionAdmission {
        calls += "admit"
        admitGate?.await()
        calls += "admitted"
        held = admission is SessionAdmission.Held
        return admission
    }

    override suspend fun launchClient(): LibraryProblem? {
        calls += "launch"
        return launchProblem
    }

    override suspend fun closeClient() {
        calls += "close"
        if (closeFails) throw IOException("it would not close")
    }

    override suspend fun resume(game: GameRoot) {
        if (!held) calls += "resume ${game.path}"
        held = true
    }

    override suspend fun release(): LibraryProblem? {
        calls += "release"
        releaseGate?.await()
        if (releaseProblem == null) held = false
        return releaseProblem
    }
}

private val Game = GameRoot("/games/Deadlock")
private val Source = FakeCustomGamesFeed.SampleFolder
private val Friend = InviteCode.parse("P95-MAN-26D")!!
private val Other = InviteCode.parse("ABC-DEF-GH2")!!
private val Arena = InviteOffer(null, ModeId("arena"), OfferContent.Feed(null), Choices.None, 8)
private val Port = FakeCustomGamesPlatform.SamplePort
private val Prepared = FakeCustomGamesPlatform.SamplePrepared

class CustomGamesTest {
    private val storage = MemoryStorage()
    private val journal = SessionJournal(storage)
    private val platform = FakeCustomGamesPlatform(journal, chatty = false)
    private val files = FakeSessionFiles()
    private val logs = mutableListOf<String>()

    private val runs = mutableListOf<Run>()

    private inner class Run(val test: TestScope, feed: CustomGamesFeedSource? = null, computer: CustomGamesPlatform = platform, hostFolders: Flow<Boolean> = flowOf(false), analytics: UsageAnalytics? = null) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler)).also { runs += this }
        val games = CustomGames(computer, files, feed, scope, logs::add, hostFolders, analytics)
        val session get() = games.live.value.session
        val link: FakeLinkProcess get() = platform.links.last()

        fun settle() = test.testScheduler.runCurrent()

        fun opened() {
            games.start()
            games.refreshModes()
            settle()
        }

        fun wait(time: Duration) {
            test.testScheduler.advanceTimeBy(time)
            settle()
        }

        fun hostUntilStarting() {
            games.host(Source, Choices.None, null)
            settle()
            assertIs<Session.HostStarting>(session)
        }

        fun hostUntilHosting() {
            hostUntilStarting()
            link.say(LinkEvent.InviteReady(Friend))
            link.say(LinkEvent.Server(ServerEvent.Ready("street")))
            settle()
            assertEquals(OwnGame.In, assertIs<Session.Hosting>(session).own)
        }

        fun found(offer: InviteOffer = Arena, code: InviteCode = Friend) {
            assertTrue(games.openInvite(code.shown))
            settle()
            link.say(LinkEvent.Offer(offer))
            settle()
            assertEquals(Session.Confirming(Invite(code, offer)), session)
        }

        fun joinUntilLinked() {
            found()
            games.confirmJoin(null)
            settle()
            assertIs<Session.Linking>(session)
        }

        fun joinUntilPlaying() {
            joinUntilLinked()
            link.say(LinkEvent.Linked)
            link.say(LinkEvent.Path(LinkPath.Direct))
            settle()
            assertEquals(OwnGame.In, assertIs<Session.Playing>(session).own)
        }

        fun runningSetUp() {
            files.lines = GamesLines.Mounted
            files.clientActivity.value = GameActivity.Running
            settle()
        }
    }

    @Test
    fun analyticsCountsHostReadyOnceWithoutSendingTheInvite() = analyticsSession(host = true)

    @Test
    fun analyticsCountsTheGuestsGameJoiningOnceWithoutSendingTheInvite() = analyticsSession(host = false)

    @Test
    fun analyticsKeepsPreparationFailureStageWhenTheGuestHelperIsCleanedUp() = scenario {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.load(); analytics.choose(true)
        platform.prepareGuest = { throw IOException("a private file path must not leave the app") }
        val run = Run(this, analytics = analytics)
        run.opened()
        run.found()
        run.games.confirmJoin(null)
        run.settle()
        assertIs<Session.Ended>(run.session)
        val result = sent.filterIsInstance<UsageEvent.Result>().single()
        assertEquals(UsageOutcome.Failed, result.outcome)
        assertEquals(UsageStage.Prepare, result.stage)
        assertFalse(sent.toString().contains("private file path"))
    }

    private fun analyticsSession(host: Boolean) = scenario {
        val sent = mutableListOf<UsageEvent>()
        val analytics = UsageAnalytics(MemoryStorage(), UsageTransport { _, event -> sent += event }, backgroundScope)
        analytics.load(); analytics.choose(true)
        val run = Run(this, analytics = analytics)
        run.opened()
        if (host) run.hostUntilHosting() else run.joinUntilPlaying()
        run.settle()
        val operation = if (host) UsageOperation.CustomGameHost else UsageOperation.CustomGameJoin
        assertEquals(1, sent.filterIsInstance<UsageEvent.Started>().count { it.operation == operation })
        val result = sent.filterIsInstance<UsageEvent.Result>().single()
        assertEquals(operation, result.operation)
        assertEquals(UsageOutcome.Completed, result.outcome)
        assertFalse(sent.toString().contains(Friend.shown))
    }

    private fun scenario(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            runs.forEach { it.scope.cancel() }
        }
    }

    private fun TestScope.started(feed: CustomGamesFeedSource? = null) = Run(this, feed).apply {
        files.held = false
        games.start()
        settle()
    }

    private fun game(body: suspend Run.() -> Unit) = scenario { started().body() }

    @Test
    fun aHostWalksFromDownloadToReleaseWithTheHoldOnlyAfterTheDownload() = scenario {
        val download = CompletableDeferred<SessionEvent.PrepareAnswer>()
        platform.prepare = { _, _, _, _ -> download.await() }
        val run = started()

        run.games.host(Source, Choices.None, null)
        run.settle()
        assertIs<Session.HostPreparing>(run.session)
        assertEquals(emptyList(), files.calls, "a download holds nothing")
        assertNull(journal.read(), "a download leaves nothing to recover")

        download.complete(SessionEvent.RuntimePrepared(Prepared, Port))
        run.settle()
        assertIs<Session.HostStarting>(run.session)
        assertEquals(listOf("admit", "admitted"), files.calls)
        assertEquals(listOf(Triple(Prepared, Game, Port)), platform.hosted)
        assertEquals(listOf(FakeCustomGamesPlatform.SampleSetup), platform.hostSetups)
        assertEquals(JournalEntry(Game, run.link.identity, FakeCustomGamesPlatform.SampleOwned), journal.read())

        run.link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        run.link.say(LinkEvent.InviteReady(Friend))
        run.settle()
        assertEquals("launch", files.calls.last(), "the host's own Deadlock starts by itself")
        assertEquals(listOf(EnterWindow.Launched), run.link.enters)
        assertEquals(OwnGame.In, assertIs<Session.Hosting>(run.session).own)
        files.clientActivity.value = GameActivity.Running
        run.link.say(LinkEvent.Server(ServerEvent.Joined(1, Player("Ann", "7656"))))
        run.settle()
        assertEquals(setOf(1), assertIs<Session.Hosting>(run.session).players.keys)

        run.games.stop()
        run.settle()
        assertEquals(1, run.link.stops)
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session, "with Deadlock still running")
        assertEquals("release", files.calls.last())
        assertNull(journal.read())

        run.games.dismissEnded()
        run.settle()
        assertEquals(Session.Idle, run.session)
    }

    @Test
    fun aClickedLinkFindsTheGameButHoldsNothingBeforeThePlayerConfirms() = game {
        games.openLink("hideout://join/${Friend.shown}")
        settle()
        assertEquals(Friend, assertIs<Session.Finding>(session).code)
        assertNull(games.linkProblem.value)
        assertEquals(listOf(Friend), platform.joined, "the helper starts to find the game")

        link.say(LinkEvent.Offer(Arena))
        settle()
        assertEquals(Session.Confirming(Invite(Friend, Arena)), session)
        assertEquals(emptyList(), link.joins, "the helper joins nothing before the player confirms")
        assertEquals(emptyList(), files.calls, "the game files stay free until the player confirms")
        assertNull(journal.read())

        games.openLink("https://example.test/join#${Friend.value.lowercase()}")
        settle()
        assertEquals(Session.Confirming(Invite(Friend, Arena)), session, "the same link twice asks once")
        assertEquals(listOf(Friend), platform.joined, "and finds once")
        assertNull(games.linkProblem.value)
    }

    @Test
    fun aBadLinkIsSaidAndTheSessionStaysWhereItWas() = game {
        games.openLink("hideout://join/tc-not-a-code")
        settle()
        assertEquals(Session.Idle, session)
        assertEquals(LinkProblem.Unreadable, games.linkProblem.value)

        val first = games.lastLink.value
        games.openLink("hideout:")
        settle()
        assertNotEquals(first, games.lastLink.value, "a second bad link is news too")
    }

    @Test
    fun aLinkDoesNotInterruptARunningSession() = game {
        hostUntilHosting()
        val hosting = session
        games.openLink("hideout://join/${Other.shown}")
        settle()
        assertEquals(hosting, session)
        assertEquals(LinkProblem.SessionRunning, games.linkProblem.value)
    }

    @Test
    fun aLinkFollowsAFinishedGameWithoutWaitingForItsDismissal() = game {
        joinUntilPlaying()
        games.stop()
        settle()
        link.say(LinkEvent.Stopped(LinkStop.StdinClosed))
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertIs<Session.Ended>(session)

        games.openLink("hideout://join/${Other.shown}")
        settle()
        assertEquals(Other, assertIs<Session.Finding>(session).code)
    }

    @Test
    fun aLinkDuringAGuestsPreparationIsDroppedAndTheJoinCarriesOn() = game {
        val gate = CompletableDeferred<Unit>()
        platform.prepareGuest = { gate.await() }
        found()
        games.confirmJoin(null)
        settle()
        val preparing = assertIs<Session.GuestPreparing>(session)

        games.openLink("hideout://join/${Other.shown}")
        settle()
        assertEquals(preparing, session)
        assertEquals(LinkProblem.SessionRunning, games.linkProblem.value)
        assertEquals(listOf(Friend), platform.joined, "no second helper starts")

        gate.complete(Unit)
        settle()
        assertIs<Session.Linking>(session)
        assertEquals(1, link.joins.size)
        assertEquals(0, link.stops)
    }

    @Test
    fun aNewerLinkWhileFindingReplacesTheHelperAndTheOldOnesLinesAreIgnored() = game {
        games.openLink("hideout://join/${Other.shown}")
        settle()
        val replaced = link
        replaced.stopAnswers = false
        games.openLink("hideout://join/${Friend.shown}")
        settle()
        assertNull(games.linkProblem.value)
        assertEquals(1, replaced.stops)
        assertEquals(listOf(Other, Friend), platform.joined)
        assertEquals(Friend, assertIs<Session.Finding>(session).code)

        replaced.say(LinkEvent.Offer(Arena))
        replaced.say(LinkEvent.Stopped(LinkStop.LinkLost))
        settle()
        assertEquals(Friend, assertIs<Session.Finding>(session).code, "the replaced helper's answer is not the newest link's")

        val offer = InviteOffer(null, ModeId("second"), OfferContent.Feed(null), Choices.None, 8)
        link.say(LinkEvent.Offer(offer))
        settle()
        games.confirmJoin(null)
        settle()
        assertEquals(Role.Guest(Friend, offer, null), games.live.value.game?.role)
        assertEquals(emptyList(), replaced.joins)
        assertEquals(1, link.joins.size)
    }

    @Test
    fun aDeclinedJoinDropsTheHelperAndItsLateLinesMoveNoNewSession() = game {
        found()
        val declined = link
        declined.stopAnswers = false
        games.stop()
        settle()
        assertEquals(Session.Idle, session)
        assertEquals(1, declined.stops)
        assertEquals(emptyList(), declined.joins)
        assertNull(games.live.value.game)

        assertTrue(games.openInvite(Other.shown))
        settle()
        declined.say(LinkEvent.Offer(Arena))
        declined.say(LinkEvent.Stopped(LinkStop.LinkLost))
        settle()
        assertEquals(Other, assertIs<Session.Finding>(session).code)
        assertEquals(listOf(Friend, Other), platform.joined)
    }

    @Test
    fun aCancelledFindDropsTheHelperAndLandsAtIdle() = game {
        assertTrue(games.openInvite(Friend.shown))
        settle()
        assertIs<Session.Finding>(session)
        games.stop()
        settle()
        assertEquals(Session.Idle, session)
        assertEquals(1, link.stops)
        assertEquals(emptyList(), files.calls)
        assertNull(journal.read())
    }

    @Test
    fun anEarlierPreparationCannotAnswerForTheLinkThatFollowedIt() = game {
        val answers = ArrayDeque(listOf(CompletableDeferred<Unit>(), CompletableDeferred()))
        val stale = answers.first()
        val fresh = answers.last()
        platform.prepareGuest = {
            val answer = answers.removeFirst()
            withContext(NonCancellable) { answer.await() }
        }
        found()
        games.confirmJoin(null)
        settle()
        val first = link
        games.stop()
        settle()
        assertIs<Session.Ended>(session)

        found(code = Other)
        games.confirmJoin(null)
        settle()
        val preparing = assertIs<Session.GuestPreparing>(session)
        assertEquals(Invite(Other, Arena), preparing.invite)

        stale.completeExceptionally(IOException("the first download gave up"))
        settle()
        assertEquals(preparing, session)

        fresh.complete(Unit)
        settle()
        assertIs<Session.Linking>(session)
        assertEquals(emptyList(), first.joins)
        assertEquals(1, link.joins.size)
    }

    @Test
    fun aClickedLinkNeverReachesTheLog() = game {
        games.openLink("hideout://join/${Friend.shown}")
        games.openLink("hideout://join/${Friend.shown}x")
        settle()
        link.say(LinkEvent.Offer(Arena))
        settle()
        games.confirmJoin(null)
        settle()
        assertTrue(logs.none { Friend.shown in it || Friend.value in it }, "$logs")
    }

    @Test
    fun aGuestWalksFromTheInviteToTheGameClosing() = game {
        assertFalse(games.openInvite("tc-not-a-code"))
        settle()
        assertEquals(Session.Idle, session)

        assertTrue(games.openInvite(" ${Friend.shown.lowercase()}\n"))
        settle()
        assertEquals(listOf(Friend), platform.joined)
        link.say(LinkEvent.Offer(Arena))
        settle()
        assertEquals(Session.Confirming(Invite(Friend, Arena)), session)
        assertEquals(emptyList(), link.joins, "the helper joins nothing before the player confirms")

        games.confirmJoin(null)
        settle()
        assertEquals(listOf(Friend), platform.joined, "the helper that found the game joins it")
        assertEquals(listOf(FakeCustomGamesPlatform.SampleSetup), link.joins.map { it.first })
        assertEquals(emptyList(), files.calls, "a guest takes the hold only once linked")

        link.say(LinkEvent.Linked)
        link.say(LinkEvent.Path(LinkPath.Relay))
        settle()
        assertEquals(listOf("admit", "admitted", "launch"), files.calls)
        assertEquals(listOf(EnterWindow.Launched), link.enters)
        assertEquals(JournalEntry(Game, link.identity, FakeCustomGamesPlatform.SampleOwned), journal.read())
        val playing = assertIs<Session.Playing>(session)
        assertEquals(OwnGame.In, playing.own)
        assertEquals(LinkPath.Relay, playing.link.path)

        files.clientActivity.value = GameActivity.Running
        settle()
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertEquals(Session.Ended(EndReason.GameClosed), session)
        assertEquals(1, link.stops)
        assertNull(journal.read())
    }

    @Test
    fun aGuestInARunningSetUpDeadlockJoinsWithoutItClosingOrStarting() = game {
        runningSetUp()
        assertNull(games.restartsDeadlock.value)
        joinUntilLinked()
        link.say(LinkEvent.Linked)
        settle()
        assertEquals(listOf("admit", "admitted"), files.calls, "nothing closes it and nothing starts it")
        assertEquals(listOf(EnterWindow.Running), link.enters)
        assertEquals(OwnGame.In, assertIs<Session.Playing>(session).own)
        assertEquals(emptyList(), platform.menusRebuilds, "a running Deadlock's pack is left as it is")
    }

    private fun Run.joinWhileItStarts(): FakeLinkProcess {
        joinUntilLinked()
        link.enterAnswer = emptyList()
        link.say(LinkEvent.Linked)
        settle()
        assertEquals(listOf("admit", "admitted", "launch"), files.calls)
        files.clientActivity.value = GameActivity.Running
        settle()
        return link
    }

    @Test
    fun aJoinWhileTheDeadlockHideoutStartedIsStillLoadingWaitsForItsMenusAsLongAsTheLaunchDid() = game {
        joinWhileItStarts()
        wait(54.seconds)
        games.stop()
        settle()
        assertIs<Session.Ended>(session)

        wait(7.seconds)
        joinUntilLinked()
        link.say(LinkEvent.Linked)
        settle()
        assertEquals(listOf(EnterWindow.Launched), link.enters, "Deadlock is still on its way to its menus")
        assertEquals(OwnGame.In, assertIs<Session.Playing>(session).own)
    }

    @Test
    fun aDeadlockHideoutStartedGetsTheShortWaitOnceItsMenusTookAConnectOrItHasHadTheLaunchsWholeWindow() = game {
        joinWhileItStarts().say(LinkEvent.Entered)
        settle()
        games.stop()
        settle()
        joinUntilLinked()
        link.say(LinkEvent.Linked)
        settle()
        assertEquals(listOf(EnterWindow.Running), link.enters, "its menus took a connect")
        games.stop()
        settle()

        files.clientActivity.value = GameActivity.Closed
        settle()
        files.calls.clear()
        joinWhileItStarts()
        wait(EnterWindow.Launched + 1.seconds)
        games.stop()
        settle()
        joinUntilLinked()
        link.say(LinkEvent.Linked)
        settle()
        assertEquals(listOf(EnterWindow.Running), link.enters, "a launch's whole window has passed")
    }

    @Test
    fun aStopDuringTheDownloadCancelsItAndHoldsNothing() = game {
        var cancelled = false
        platform.prepare = { _, _, _, progress ->
            progress(Transfer(6, 12))
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(Transfer(6, 12), games.transfer.value)

        games.stop()
        settle()
        assertTrue(cancelled)
        assertEquals(Session.Ended(EndReason.PlayerStopped), session)
        assertNull(games.transfer.value)
        assertEquals(emptyList(), files.calls)
        assertEquals(emptyList(), platform.links)
        assertNull(journal.read())
    }

    @Test
    fun aGuestsDownloadShowsHowFarItHasGot() = game {
        val finish = CompletableDeferred<Unit>()
        platform.prepareGuest = { progress ->
            progress(Transfer(6, 12))
            finish.await()
        }
        found()
        games.confirmJoin(null)
        settle()
        assertIs<Session.GuestPreparing>(session)
        assertEquals(Transfer(6, 12), games.transfer.value)

        finish.complete(Unit)
        settle()
        assertNull(games.transfer.value)
    }

    @Test
    fun aDownloadThatFinishesAfterItsStopIsNoAnswerForTheNextSession() = game {
        val first = CompletableDeferred<SessionEvent.PrepareAnswer>()
        val second = CompletableDeferred<SessionEvent.PrepareAnswer>()
        val answers = ArrayDeque(listOf(first, second))
        platform.prepare = { _, _, _, _ ->
            val answer = answers.removeFirst()
            withContext(NonCancellable) { answer.await() }
        }
        games.host(Source, Choices.None, null)
        settle()
        games.stop()
        games.dismissEnded()
        games.host(Source, Choices.None, null)
        settle()
        assertIs<Session.HostPreparing>(session)

        val stale = Prepared.copy(modlock = "stale")
        first.complete(SessionEvent.RuntimePrepared(stale, 1))
        settle()
        assertIs<Session.HostPreparing>(session)
        assertEquals(emptyList(), files.calls)

        second.complete(SessionEvent.RuntimePrepared(Prepared, Port))
        settle()
        assertEquals(listOf(Triple(Prepared, Game, Port)), platform.hosted)
    }

    @Test
    fun aDownloadThatFailsOrThrowsEndsTheSessionHoldingNothing() {
        val failures: List<suspend () -> SessionEvent.PrepareAnswer> = listOf(
            { SessionEvent.RuntimeFailed(Refusal.Other("disk full")) },
            { throw IOException("disk full") },
        )
        for (failure in failures) game {
            platform.prepare = { _, _, _, _ -> failure() }
            games.host(Source, Choices.None, null)
            settle()
            assertEquals(Session.Ended(EndReason.Refused(Refusal.Other("disk full"))), session)
            assertEquals(emptyList(), files.calls)
            games.dismissEnded()
            settle()
        }
    }

    @Test
    fun aRefusedAdmissionEndsWithNoHelper() = game {
        files.admission = SessionAdmission.Refused(LibraryProblem.Busy)
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.Library(LibraryProblem.Busy))), session)
        assertEquals(emptyList(), platform.links)
        assertNull(journal.read())
    }

    @Test
    fun aStopDuringAdmissionReleasesOnlyAfterTheAdmissionEnds() = game {
        val gate = CompletableDeferred<Unit>()
        files.admitGate = gate
        games.host(Source, Choices.None, null)
        settle()
        assertIs<Session.HostAdmitting>(session)

        games.stop()
        settle()
        assertEquals(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running), session)
        assertEquals(listOf("admit"), files.calls, "the release waits its turn")

        gate.complete(Unit)
        settle()
        assertEquals(listOf("admit", "admitted", "release"), files.calls)
        assertEquals(Session.Ended(EndReason.PlayerStopped), session)
        assertEquals(emptyList(), platform.links)
        assertNull(journal.read())
    }

    @Test
    fun aStuckAdmissionTimesOutAndStillReleasesInOrder() = game {
        val gate = CompletableDeferred<Unit>()
        files.admitGate = gate
        games.host(Source, Choices.None, null)
        settle()
        wait(Deadline.HostAdmitting.after + 1.seconds)
        assertEquals(EndReason.TimedOut(LateStage.Admitting), assertIs<Session.ReleasingFiles>(session).reason)
        gate.complete(Unit)
        settle()
        assertEquals(listOf("admit", "admitted", "release"), files.calls)
        assertIs<Session.Ended>(session)
    }

    @Test
    fun theHelperDyingReleasesWithTheReasonItGave() = game {
        hostUntilHosting()
        link.say(LinkEvent.Stopped(LinkStop.ServerExited, "crashed"))
        settle()
        assertEquals(Session.Ended(EndReason.HostFailed("crashed")), session)
        assertEquals("release", files.calls.last())
    }

    @Test
    fun aServerThatDiedWithItsProcessAliveEndsTheGameAndGivesTheFilesBack() = game {
        hostUntilHosting()
        link.say(LinkEvent.Stopped(LinkStop.ServerDead))
        settle()
        assertEquals(Session.Ended(EndReason.ServerDied), session)
        assertEquals("release", files.calls.last())
    }

    private fun Run.droppedGuest() {
        joinUntilPlaying()
        link.say(LinkEvent.Game(GameTraffic.Lost))
        settle()
        assertEquals(OwnGame.Out(EntryProblem.Dropped), assertIs<Session.Playing>(session).own)
    }

    @Test
    fun aDroppedGuestPlaysBackInThroughTheSameLinkWithoutTouchingARunningDeadlock() = game {
        runningSetUp()
        droppedGuest()
        val before = platform.links.size
        files.calls.clear()

        games.play()
        settle()
        assertEquals(OwnGame.In, assertIs<Session.Playing>(session).own)
        assertEquals(emptyList(), files.calls, "nothing closed, started or released")
        assertEquals(listOf(EnterWindow.Running, EnterWindow.Running), link.enters, "one enter for each time it went in")
        assertEquals(before, platform.links.size, "the same helper carries the game")
        assertEquals(0, link.stops)
    }

    @Test
    fun aDroppedGuestWhoseDeadlockIsClosedIsStartedBackIn() = game {
        droppedGuest()
        files.calls.clear()
        games.play()
        settle()
        assertEquals(listOf("launch"), files.calls)
        assertEquals(EnterWindow.Launched, link.enters.last())
        assertEquals(OwnGame.In, assertIs<Session.Playing>(session).own)
    }

    @Test
    fun aBuildWithNoFeedSaysSoAndOneWithAFeedDoesNot() = scenario {
        val without = started()
        without.games.refreshModes()
        without.settle()
        assertTrue(without.games.modes.value.noFeed)
        assertFalse(without.games.modes.value.refreshing)

        val with = started(FakeCustomGamesFeed())
        assertFalse(with.games.modes.value.noFeed, "not answered yet is a different thing")
        with.games.refreshModes()
        with.settle()
        assertFalse(with.games.modes.value.noFeed)
    }

    @Test
    fun aRecoveryReleasesWithoutWaitingToReadTheGame() = scenario {
        journal.write(JournalEntry(Game, ProcessIdentity(77, 5)))
        files.clientActivity.value = GameActivity.Unread
        val run = started()
        assertEquals(Session.Ended(EndReason.Interrupted), run.session)
        assertNull(journal.read())
    }

    @Test
    fun aLinkThatStartsHideoutAfterAnInterruptedGameOpensOnceTheFilesAreBack() = scenario {
        journal.write(JournalEntry(Game, ProcessIdentity(77, 5)))
        val run = Run(this)
        run.games.start()
        run.games.openLink(Other.shown)
        run.games.openLink(Friend.shown)
        run.settle()
        assertEquals(Friend, assertIs<Session.Finding>(run.session).code, "the newest link")
        assertEquals(listOf(Friend), platform.joined, "only the newest link is found")
        assertNull(run.games.linkProblem.value)
        assertTrue("release" in files.calls, "${files.calls}")
        assertNull(journal.read())
    }

    @Test
    fun aLinkWaitsThroughAnInterruptedGamesRelease() = scenario {
        journal.write(JournalEntry(Game, ProcessIdentity(77, 5)))
        val gate = CompletableDeferred<Unit>().also { files.releaseGate = it }
        val run = started()
        assertIs<Session.ReleasingFiles>(run.session)
        run.games.openLink(Friend.shown)
        run.settle()
        assertIs<Session.ReleasingFiles>(run.session)
        assertNull(run.games.linkProblem.value, "nothing the player chose is running, so there is nothing to stop first")
        assertNotNull(run.games.lastLink.value, "the page opens on what is going on")
        assertEquals(emptyList(), platform.joined, "nothing is found while the files are released")
        gate.complete(Unit)
        run.settle()
        assertEquals(Friend, assertIs<Session.Finding>(run.session).code)
        assertNull(journal.read())
    }

    @Test
    fun aLinkDuringARecoveredReleaseCanStillBeUnreadable() = scenario {
        journal.write(JournalEntry(Game, ProcessIdentity(77, 5)))
        val gate = CompletableDeferred<Unit>().also { files.releaseGate = it }
        val run = started()
        run.games.openLink("not an invite")
        run.settle()
        assertEquals(LinkProblem.Unreadable, run.games.linkProblem.value)
        gate.complete(Unit)
        run.settle()
        assertEquals(Session.Ended(EndReason.Interrupted), run.session)
    }

    @Test
    fun aLinkDuringTheReleaseOfAGameThePlayerStoppedIsStillDropped() = game {
        hostUntilHosting()
        val gate = CompletableDeferred<Unit>().also { files.releaseGate = it }
        games.stop()
        settle()
        assertIs<Session.ReleasingFiles>(session)
        games.openLink(Friend.shown)
        settle()
        assertEquals(LinkProblem.SessionRunning, games.linkProblem.value)
        gate.complete(Unit)
        settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), session)
    }

    @Test
    fun aPastedCodeASessionCannotTakeSaysSoLikeAClickedLink() = scenario {
        val run = started()
        run.hostUntilHosting()
        assertTrue(run.games.openInvite(Friend.shown))
        run.settle()
        assertIs<Session.Hosting>(run.session)
        assertEquals(LinkProblem.SessionRunning, run.games.linkProblem.value)
    }

    @Test
    fun aPastedCodeWithNothingRunningFindsTheGameAndClearsAnOldProblem() = scenario {
        val run = started()
        run.games.openLink("not an invite")
        run.settle()
        assertEquals(LinkProblem.Unreadable, run.games.linkProblem.value)
        assertTrue(run.games.openInvite(Friend.shown))
        run.settle()
        assertEquals(Friend, assertIs<Session.Finding>(run.session).code)
        assertNull(run.games.linkProblem.value)
    }

    @Test
    fun aSessionWhoseProcessListCannotBeReadIsRefusedBeforeItHoldsAnything() = scenario {
        val run = started()
        files.clientActivity.value = GameActivity.Unknown
        run.settle()
        run.found()
        run.games.confirmJoin(null)
        run.settle()
        assertIs<Refusal.Library>(assertIs<EndReason.Refused>(assertIs<Session.Ended>(run.session).reason).problem)
        assertEquals(emptyList(), files.calls)
        assertEquals(emptyList(), run.link.joins)
        assertEquals(1, run.link.stops)
        assertNull(journal.read())
    }

    @Test
    fun aGuestsStopDuringAnAdmissionThatIsRefusedStillClearsTheJournal() = scenario {
        val run = started()
        run.joinUntilLinked()
        files.admission = SessionAdmission.Refused(LibraryProblem.Busy)
        val gate = CompletableDeferred<Unit>().also { files.admitGate = it }
        run.link.say(LinkEvent.Linked)
        run.settle()
        assertIs<Session.GuestAdmitting>(run.session)
        run.games.stop()
        run.settle()
        gate.complete(Unit)
        run.settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session)
        assertNull(journal.read())
    }

    @Test
    fun aStopDuringAnAdmissionThatIsRefusedWithTheGameClosedStillClearsTheJournal() = scenario {
        val run = started()
        files.admission = SessionAdmission.Refused(LibraryProblem.Busy)
        val gate = CompletableDeferred<Unit>().also { files.admitGate = it }
        run.games.host(Source, Choices.None, null)
        run.settle()
        run.games.stop()
        run.settle()
        assertEquals(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Running), run.session)
        gate.complete(Unit)
        run.settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session)
        assertNull(journal.read())
    }

    @Test
    fun aGameThePageSentInStaysJoiningThroughQuietUntilItsOwnTrafficFlows() = game {
        joinUntilLinked()
        link.enterAnswer = listOf(LinkEvent.Entered)
        link.say(LinkEvent.Linked)
        settle()
        assertIs<OwnGame.Joining>(assertIs<Session.Playing>(session).own)
        link.say(LinkEvent.Game(GameTraffic.Quiet))
        settle()
        assertIs<OwnGame.Joining>(assertIs<Session.Playing>(session).own)
        assertNotNull(session.deadline, "its wait is still running")

        link.say(LinkEvent.Game(GameTraffic.Flowing))
        settle()
        assertEquals(OwnGame.In, assertIs<Session.Playing>(session).own)
    }

    @Test
    fun playDoesNothingWhileTheHostIsNotAnswering() = game {
        droppedGuest()
        link.say(LinkEvent.Path(null))
        settle()
        files.calls.clear()
        val enters = link.enters.size
        games.play()
        settle()
        assertEquals(OwnGame.Out(EntryProblem.Dropped), assertIs<Session.Playing>(session).own)
        assertEquals(emptyList(), files.calls)
        assertEquals(enters, link.enters.size)
    }

    @Test
    fun aGameThePageSentInThatNeverShowsUpIsOutOnceItsWaitPasses() = game {
        joinUntilLinked()
        link.enterAnswer = listOf(LinkEvent.Entered)
        link.say(LinkEvent.Linked)
        settle()
        wait(Deadline.Joining.after + 1.seconds)
        assertEquals(OwnGame.Out(EntryProblem.DidNotJoin), assertIs<Session.Playing>(session).own)
        assertEquals(0, link.stops, "the session waits on Play")
    }

    @Test
    fun aHelperWhoseOutputEndsOrThrowsStillMovesTheSessionOn() {
        for (cause in listOf(null, IOException("pipe broke"))) game {
            hostUntilHosting()
            link.endOutput(cause)
            settle()
            assertEquals(Session.Ended(EndReason.LinkFailed(LinkFailure.Unknown)), session)
        }
    }

    @Test
    fun aHelperThatCannotStartEndsAsAFailedLink() = game {
        platform.startFails = IOException("no helper here")
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(Session.Ended(EndReason.LinkFailed(LinkFailure.Unknown)), session)
        assertEquals("release", files.calls.last(), "the hold was taken before the helper was tried")
    }

    @Test
    fun aHelperThatIgnoresTheStopIsKilledAfterTheDeadline() = game {
        hostUntilHosting()
        link.stopAnswers = false
        games.stop()
        settle()
        assertEquals(1, link.stops)
        assertEquals(0, link.kills)

        wait(Deadline.Stopping.after + 1.seconds)
        assertEquals(1, link.kills)
        assertEquals(Session.Ended(EndReason.PlayerStopped), session)
    }

    @Test
    fun aServerThatNeverComesUpTimesOutThroughAStop() = game {
        hostUntilStarting()
        wait(Deadline.HostStarting.after + 1.seconds)
        assertEquals(1, link.stops)
        assertEquals(Session.Ended(EndReason.TimedOut(LateStage.HostStarting)), session)
    }

    @Test
    fun aDeadlineFromAnEarlierStateDoesNothingLater() = game {
        hostUntilHosting()
        files.clientActivity.value = GameActivity.Running
        settle()
        val hosting = session
        wait(Deadline.HostStarting.after + Deadline.Linking.after)
        assertEquals(hosting, session)
        assertEquals(0, link.stops)
    }

    @Test
    fun aGuestWhoNeverLinksTimesOutAndHoldsNothing() = game {
        joinUntilLinked()
        wait(Deadline.Linking.after + 1.seconds)
        assertEquals(Session.Ended(EndReason.TimedOut(LateStage.Linking)), session)
        assertEquals(emptyList(), files.calls)
    }

    @Test
    fun aReleaseTheLibraryRefusesIsTriedAgainOnStopAndOnTheClientClosing() = game {
        hostUntilHosting()
        files.releaseProblem = LibraryProblem.ProcessesUnknown("no /proc")
        games.stop()
        settle()
        assertEquals(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Failed("no /proc")), session)
        assertNotNull(journal.read(), "nothing is finished, so a restart would look again")

        games.stop()
        settle()
        assertEquals(Session.ReleasingFiles(EndReason.PlayerStopped, FileRelease.Failed("no /proc")), session)

        files.releaseProblem = null
        files.clientActivity.value = GameActivity.Running
        settle()
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), session)
        assertNull(journal.read())
    }

    @Test
    fun aRecoveredJournalEndsTheRecordedProcessThenGoesThroughReleaseWithDeadlockStillRunning() = scenario {
        val left = JournalEntry(Game, ProcessIdentity(77, 5))
        journal.write(left)
        files.clientActivity.value = GameActivity.Running
        val run = started()
        assertEquals(listOf(left), platform.leftoversEnded)
        assertEquals(Session.Ended(EndReason.Interrupted), run.session)
        assertNull(journal.read())
    }

    @Test
    fun noJournalMeansNothingToEnd() = game {
        assertEquals(Session.Idle, session)
        assertEquals(emptyList(), platform.leftoversEnded)
    }

    @Test
    fun anUnreadableJournalCountsAsNone() = scenario {
        storage.write("custom-game-journal.json", "not json")
        val run = started()
        assertEquals(Session.Idle, run.session)
    }

    @Test
    fun aHostAskedForDuringRecoveryWaitsAndLosesToTheRecovery() = scenario {
        journal.write(JournalEntry())
        val run = Run(this)
        run.games.host(Source, Choices.None, null)
        run.games.start()
        run.settle()
        assertEquals(EndReason.Interrupted, assertIs<Session.Ended>(run.session).reason)
        assertEquals(emptyList(), platform.hosted)
    }

    @Test
    fun hideoutKilledInHostStarting() = killedThenRestarted(process = true, game = true) { hostUntilStarting() }

    @Test
    fun hideoutKilledInHosting() = killedThenRestarted(process = true, game = true) { hostUntilHosting() }

    @Test
    fun hideoutKilledInStopping() = killedThenRestarted(process = true, game = true) {
        hostUntilHosting()
        link.stopAnswers = false
        games.stop()
        settle()
        assertIs<Session.Stopping>(session)
    }

    @Test
    fun hideoutKilledInReleasingFiles() = killedThenRestarted(process = true, game = true) {
        hostUntilHosting()
        files.releaseGate = CompletableDeferred()
        games.stop()
        settle()
        assertIs<Session.ReleasingFiles>(session)
    }

    @Test
    fun hideoutKilledInHostAdmitting() = killedThenRestarted(process = false, game = false) {
        files.admitGate = CompletableDeferred()
        games.host(Source, Choices.None, null)
        settle()
        assertIs<Session.HostAdmitting>(session)
    }

    @Test
    fun hideoutKilledInLinking() = killedThenRestarted(process = true, game = false) { joinUntilLinked() }

    @Test
    fun hideoutKilledInPlaying() = killedThenRestarted(process = true, game = true) { joinUntilPlaying() }

    private fun killedThenRestarted(process: Boolean, game: Boolean, walk: Run.() -> Unit) = scenario {
        val before = started()
        before.walk()
        val link = platform.links.lastOrNull()
        before.scope.cancel()

        files.clientActivity.value = GameActivity.Closed
        files.admitGate = null
        files.releaseGate = null
        val after = started()

        val left = platform.leftoversEnded.single()
        assertEquals(if (process) link?.identity else null, left.process)
        assertEquals(if (game) Game else null, left.game)
        assertEquals(Session.Ended(EndReason.Interrupted), after.session)
    }

    @Test
    fun whatACrashLeftInTheGameIsReleasedByItsRootAndTheJournalWaitsForThat() = scenario {
        journal.write(JournalEntry(Game, ProcessIdentity(1, 2)))
        files.releaseProblem = LibraryProblem.Busy
        val run = started()
        assertIs<FileRelease.Failed>(assertIs<Session.ReleasingFiles>(run.session).release)
        assertEquals(listOf("resume ${Game.path}", "release"), files.calls)
        assertNotNull(journal.read(), "a release that failed keeps the journal")

        files.releaseProblem = null
        run.games.stop()
        run.settle()
        assertEquals(Session.Ended(EndReason.Interrupted), run.session)
        assertEquals(listOf("resume ${Game.path}", "release", "release"), files.calls)
        assertNull(journal.read())
    }

    @Test
    fun aSessionAfterARecoveredOneReleasesItsOwnHold() = scenario {
        journal.write(JournalEntry(Game))
        val run = started()
        assertEquals(Session.Ended(EndReason.Interrupted), run.session)
        run.games.dismissEnded()
        run.settle()
        files.calls.clear()

        run.hostUntilHosting()
        run.games.stop()
        run.settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session)
        assertTrue("release" in files.calls)
    }

    @Test
    fun hideoutKilledDuringADownloadLeavesNothingToCleanUp() = scenario {
        platform.prepare = { _, _, _, _ -> awaitCancellation() }
        val before = started()
        before.games.host(Source, Choices.None, null)
        before.settle()
        before.scope.cancel()

        val after = started()
        assertEquals(Session.Idle, after.session)
        assertEquals(emptyList(), platform.leftoversEnded)
    }

    @Test
    fun aGameUpdateWhileHostingStopsTheSession() = game {
        hostUntilHosting()
        wait(2.minutes)
        assertIs<Session.Hosting>(session)

        platform.revision = GameRevision(2)
        wait(1.minutes + 1.seconds)
        assertEquals(1, link.stops)
        assertEquals(Session.Ended(EndReason.GameUpdated), session)

        val reads = platform.revisionReads
        wait(10.minutes)
        assertEquals(reads, platform.revisionReads, "the watch ends with the session")
    }

    @Test
    fun anUpdateWhileTheServerComesUpIsSeenOnceHosting() = game {
        hostUntilStarting()
        platform.revision = GameRevision(2)
        wait(1.minutes + 1.seconds)
        assertIs<Session.HostStarting>(session)
        link.say(LinkEvent.InviteReady(Friend))
        link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        settle()
        wait(1.minutes + 1.seconds)
        assertEquals(EndReason.GameUpdated, assertIs<Session.Ended>(session).reason)
    }

    @Test
    fun anUnreadableRevisionNeverLooksLikeAnUpdate() = game {
        hostUntilHosting()
        platform.revision = null
        wait(5.minutes)
        assertIs<Session.Hosting>(session)
    }

    @Test
    fun aGuestsSessionReadsNoRevision() = game {
        joinUntilPlaying()
        wait(5.minutes)
        assertEquals(0, platform.revisionReads)
    }

    @Test
    fun startingAsksNoFeedUntilThePageOpens() = scenario {
        val feed = FakeCustomGamesFeed()
        val run = started(feed)
        assertEquals(0, feed.fetches)
        assertEquals(ModeList(), run.games.modes.value)

        run.games.refreshModes()
        run.settle()
        assertEquals(1, feed.fetches)
    }

    private class SlowFeed(val kept: CustomGamesFeed?, vararg answers: CompletableDeferred<CustomGamesFetch>) : CustomGamesFeedSource {
        private val answers = ArrayDeque(answers.toList())

        override suspend fun lastGood() = kept

        override suspend fun fetch() = answers.removeFirst().await()
    }

    private fun TestScope.slowCheckWhileAGuestFindsANewerFeed(slowAnswer: CustomGamesFetch) {
        val old = FakeCustomGamesFeed.SampleModes.copy(sequence = 1)
        val new = FakeCustomGamesFeed.SampleModes.copy(sequence = 2)
        val slow = CompletableDeferred<CustomGamesFetch>()
        val run = Run(this, SlowFeed(old, slow, CompletableDeferred(CustomGamesFetch.Fresh(new))))
        run.opened()
        val digest = assertNotNull(Sha256.parse("b".repeat(64)))
        run.found(InviteOffer(null, ModeId("not-listed"), OfferContent.Feed(digest), Choices.None, 8))
        run.games.confirmJoin(null)
        run.settle()
        assertEquals(new, run.games.modes.value.feed, "the guest's own read found the newer list")
        slow.complete(slowAnswer)
        run.settle()
        assertEquals(new, run.games.modes.value.feed)
        assertFalse(run.games.modes.value.refreshing)
    }

    @Test
    fun aSlowCheckThatFailsDoesNotBringBackAnOlderFeed() = scenario {
        slowCheckWhileAGuestFindsANewerFeed(CustomGamesFetch.Unreachable("offline"))
    }

    @Test
    fun aSlowCheckThatAnswersWithAnOlderFeedDoesNotReplaceANewerOne() = scenario {
        slowCheckWhileAGuestFindsANewerFeed(CustomGamesFetch.Fresh(FakeCustomGamesFeed.SampleModes.copy(sequence = 1)))
    }

    @Test
    fun theModesComeFromTheLastGoodFeedThenTheFreshOne() = scenario {
        val old = FakeCustomGamesFeed.SampleModes.copy(sequence = 1)
        val new = FakeCustomGamesFeed.SampleModes.copy(sequence = 2)
        val feed = FakeCustomGamesFeed(kept = old, answer = CustomGamesFetch.Fresh(new))
        val run = Run(this, feed)
        run.opened()
        assertEquals(ModeList(new), run.games.modes.value)
    }

    @Test
    fun beingOfflineOrARollbackKeepsTheLastGoodFeedAndSaysSo() = scenario {
        val kept = FakeCustomGamesFeed.SampleModes
        val problems = listOf(CustomGamesFetch.Unreachable("offline"), CustomGamesFetch.Refused(FeedRefusal.Rollback))
        for (problem in problems) {
            val run = Run(this, FakeCustomGamesFeed(kept = kept, answer = problem))
            run.opened()
            assertEquals(ModeList(kept, problem = problem), run.games.modes.value)
        }
    }

    @Test
    fun refreshingAsksTheFeedAgainOnDemand() = scenario {
        val feed = FakeCustomGamesFeed(answer = CustomGamesFetch.Unreachable("offline"))
        val run = Run(this, feed)
        run.opened()
        assertEquals(emptyList(), run.games.modes.value.modes)

        feed.answer = CustomGamesFetch.Fresh(FakeCustomGamesFeed.SampleModes)
        run.games.refreshModes()
        run.settle()
        assertEquals(ModeList(FakeCustomGamesFeed.SampleModes), run.games.modes.value)
    }

    @Test
    fun aHostGetsTheRuntimeOfTheNewestFeed() = scenario {
        var runtime: ModeRuntime? = null
        platform.prepare = { _, _, given, _ ->
            runtime = given?.runtime
            awaitCancellation()
        }
        val run = Run(this, FakeCustomGamesFeed())
        run.opened()
        run.games.host(Source, Choices.None, null)
        run.settle()
        assertEquals(FakeCustomGamesFeed.SampleModes.runtime, runtime)
    }

    @Test
    fun exitAsksTheHelperToStopAndLeavesTheJournalForTheNextStart() = game {
        hostUntilHosting()
        games.close()
        assertEquals(1, link.stops)
        assertNotNull(journal.read())
    }

    @Test
    fun exitWithNoSessionDoesNothing() = game {
        games.close()
        assertEquals(emptyList(), platform.links)
    }

    @Test
    fun theInviteNeverReachesTheLog() = game {
        platform.startFails = IOException("failed for ${Friend.shown} ${Friend.value}")
        assertTrue(games.openInvite(Friend.shown))
        settle()
        assertIs<Session.Ended>(session)
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.none { Friend.shown in it || Friend.value in it }, logs.toString())
    }

    @Test
    fun theModeIsStagedOnceTheJournalNamesTheGameAndBeforeTheServerStarts() = scenario {
        val gate = CompletableDeferred<Unit>().also { platform.stageGate = it }
        val run = started()
        run.games.host(Source, Choices.None, null)
        run.settle()
        assertIs<Session.HostAdmitting>(run.session)
        assertEquals(listOf<Pair<GameRoot, ModeContent?>>(Game to Prepared.content), platform.staged)
        assertEquals(JournalEntry(Game, null, FakeCustomGamesPlatform.SampleOwned), journal.read())
        assertEquals(emptyList(), platform.hosted)
        gate.complete(Unit)
        run.settle()
        assertIs<Session.HostStarting>(run.session)
    }

    @Test
    fun aHostWhoseModeCannotGoInReleasesAndSaysSo() = scenario {
        platform.stageResult = StageResult.Failed("no room")
        val run = started()
        run.games.host(Source, Choices.None, null)
        run.settle()
        assertEquals(emptyList(), platform.hosted, "no server starts on a map that is not there")
        assertEquals(Session.Ended(EndReason.Refused(Refusal.ContentNotStaged)), run.session)
        assertTrue("release" in files.calls, "calls ${files.calls}")
        assertNull(journal.read())
    }

    @Test
    fun aGuestWhoseModeCannotGoInStopsBeforeTheGameStarts() = scenario {
        platform.stageResult = StageResult.Failed("no room")
        val run = started()
        run.joinUntilLinked()
        run.link.say(LinkEvent.Linked)
        run.settle()
        assertTrue(files.calls.none { it.startsWith("launch") }, "calls ${files.calls}")
        assertEquals(emptyList(), run.link.enters)
        assertEquals(Session.Ended(EndReason.Refused(Refusal.ContentNotStaged)), run.session)
        assertTrue("release" in files.calls, "calls ${files.calls}")
    }

    @Test
    fun aMissingMapReachesTheGuestAsTheProblemAndStopsTheHelperUnjoined() = scenario {
        platform.guestResult = SessionEvent.RuntimeFailed(Refusal.Other("no map"))
        val run = started()
        run.found()
        run.games.confirmJoin(null)
        run.settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.Other("no map"))), run.session)
        assertEquals(emptyList(), run.link.joins)
        assertEquals(1, run.link.stops)
    }

    @Test
    fun anInviteFromANewerListAsksForThatList() = scenario {
        val digest = Sha256.parse("a".repeat(64))!!
        val offer = InviteOffer(null, ModeId("arena"), OfferContent.Feed(digest), Choices.None, 8)
        val artifact = Artifact("https://site/client.zip", 1, digest)
        val runtime = ModeRuntime(emptyMap(), artifact, artifact, artifact)
        val listed = CustomGamesFeed(2, runtime, listOf(FakeCustomGamesFeed.SampleMode.copy(origin = ModeOrigin.Feed(artifact, artifact))))
        val source = FakeCustomGamesFeed(answer = CustomGamesFetch.Fresh(listed))
        val run = Run(this, source)
        run.games.start()
        run.settle()
        run.found(offer)
        run.games.confirmJoin(null)
        run.settle()
        assertEquals(1, source.fetches)
        assertEquals(listOf<CustomGamesFeed?>(listed), platform.guestsFeeds)
        assertEquals(listed, run.games.modes.value.feed)
    }

    private fun clientVersion(version: String, digit: Char) = FakeCustomGamesFeed.SampleMode.copy(
        version = version,
        origin = ModeOrigin.Feed(Artifact("https://example.test/$version-rules.zip", 1, Sha256.parse("$digit".repeat(63) + "0")!!), Artifact("https://example.test/$version.zip", 2, Sha256.parse("$digit".repeat(64))!!)),
    )

    private fun TestScope.joining(offer: InviteOffer, source: CustomGamesFeedSource) = Run(this, source).apply {
        files.held = false
        games.start()
        settle()
        found(offer)
        games.confirmJoin(null)
        settle()
    }

    @Test
    fun aGuestWhoHasTheInvitesVersionPlaysItWithNoListAsked() = scenario {
        val mine = clientVersion("0.9.0", 'd')
        platform.installedModes[mine.id] = InstalledMode(mine, 3, null)
        val offline = FakeCustomGamesFeed(kept = FakeCustomGamesFeed.SampleModes, answer = CustomGamesFetch.Unreachable("offline"))
        val run = joining(InviteOffer(null, mine.id, OfferContent.Feed(mine.clientDigest), Choices.None, 2), offline)
        assertIs<Session.Linking>(run.session)
        assertEquals(listOf<Mode?>(mine), platform.guestsPrepared, "the installed version, though the list names another")
        assertEquals(0, offline.fetches)
    }

    @Test
    fun aGuestWithoutTheInvitesVersionIsToldWhetherTheListOrTheVersionIsMissing() = scenario {
        val friends = InviteOffer(null, ModeId("arena"), OfferContent.Feed(clientVersion("1.1.0", 'e').clientDigest), Choices.None, 2)
        fun answer(fetched: CustomGamesFetch) = (joining(friends, FakeCustomGamesFeed(kept = FakeCustomGamesFeed.SampleModes, answer = fetched)).session as Session.Ended).reason
        assertEquals(EndReason.Refused(Refusal.Preflight(PreflightProblem.ListUnreachable)), answer(CustomGamesFetch.Unreachable("offline")))
        assertEquals(EndReason.Refused(Refusal.Preflight(PreflightProblem.ModeNotListed)), answer(CustomGamesFetch.Fresh(FakeCustomGamesFeed.SampleModes)))
        assertEquals(emptyList(), platform.guestsPrepared, "nothing is fetched for a version no list here names")
    }

    @Test
    fun aReleaseRemovesWhatTheSessionProvesItOwns() = scenario {
        val run = started()
        run.hostUntilHosting()
        assertEquals(FakeCustomGamesPlatform.SampleOwned, assertNotNull(journal.read()).owned)
        run.games.stop()
        run.settle()
        run.link.say(LinkEvent.Stopped(LinkStop.StdinClosed))
        run.settle()
        assertEquals(listOf(Game to FakeCustomGamesPlatform.SampleOwned), platform.removed)
    }

    @Test
    fun whatARecoveryCouldNotRemoveKeepsTheJournalAndIsTriedAgainOnTheNextStart() = scenario {
        val left = JournalEntry(Game, ProcessIdentity(1, 2), FakeCustomGamesPlatform.SampleOwned)
        journal.write(left)
        platform.leftoversStand = true
        val run = started()
        assertIs<FileRelease.Failed>(assertIs<Session.ReleasingFiles>(run.session).release, "the player is not told the files are clean")
        assertEquals(left, journal.read())
        run.scope.cancel()

        platform.leftoversStand = false
        val again = started()
        assertEquals(Session.Ended(EndReason.Interrupted), again.session)
        assertNull(journal.read())
    }

    @Test
    fun whatAStopCouldNotRemoveIsTriedAgainOnTheNextStop() = scenario {
        val run = started()
        run.hostUntilHosting()
        platform.leftoversStand = true
        run.games.stop()
        run.settle()
        run.link.say(LinkEvent.Stopped(LinkStop.StdinClosed))
        run.settle()
        assertIs<FileRelease.Failed>(assertIs<Session.ReleasingFiles>(run.session).release)
        assertNotNull(journal.read())

        platform.leftoversStand = false
        run.games.stop()
        run.settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session)
        assertNull(journal.read())
    }

    @Test
    fun whileACrashIsBeingEndedNothingStartsOrStops() = scenario {
        journal.write(JournalEntry(Game, ProcessIdentity(1, 1)))
        val ending = CompletableDeferred<Unit>()
        val computer = object : CustomGamesPlatform by platform {
            override suspend fun endLeftover(entry: JournalEntry) = ending.await()
        }
        val run = Run(this, computer = computer).apply { games.start(); settle() }
        assertEquals(Session.Recovering, run.session)
        run.games.host(Source, Choices.None, null)
        run.games.stop()
        run.settle()
        assertEquals(Session.Recovering, run.session, "nothing starts or stops while a crash is being ended")

        ending.complete(Unit)
        run.settle()
        assertEquals(Session.Ended(EndReason.Interrupted), run.session)
    }

    @Test
    fun aCrashStillRemovesWhatTheJournalSaysItOwned() = scenario {
        val first = started()
        first.joinUntilPlaying()
        first.scope.cancel()
        files.clientActivity.value = GameActivity.Closed
        started()
        assertEquals(listOf(Game to FakeCustomGamesPlatform.SampleOwned), platform.removed)
    }

    @Test
    fun aHostsStopDuringTheStageEndsItAndReleasesAtOnce() = scenario {
        platform.stageGate = CompletableDeferred()
        val run = started()
        run.games.host(Source, Choices.None, null)
        run.settle()
        assertIs<Session.HostAdmitting>(run.session)
        run.games.stop()
        run.settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session)
        assertNull(journal.read())
    }

    @Test
    fun aGuestsStopDuringTheStageEndsItAndReleasesAtOnce() = scenario {
        platform.stageGate = CompletableDeferred()
        val run = started()
        run.joinUntilLinked()
        run.link.say(LinkEvent.Linked)
        run.settle()
        assertIs<Session.GuestAdmitting>(run.session)
        run.games.stop()
        run.settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session)
        assertNull(journal.read())
    }

    @Test
    fun aStopBeforeTheStageBeginsSkipsIt() = scenario {
        val gate = CompletableDeferred<Unit>().also { files.admitGate = it }
        val run = started()
        run.games.host(Source, Choices.None, null)
        run.settle()
        run.games.stop()
        run.settle()
        gate.complete(Unit)
        run.settle()
        assertEquals(emptyList(), platform.staged.filter { it.second != null })
        assertEquals(Session.Ended(EndReason.PlayerStopped), run.session)
    }

    @Test
    fun aStoppedStageLeavesTheFileQueueWorkingForTheNextSession() = scenario {
        platform.stageGate = CompletableDeferred()
        val run = started()
        run.games.host(Source, Choices.None, null)
        run.settle()
        run.games.stop()
        run.settle()
        platform.stageGate = null
        run.games.dismissEnded()
        run.hostUntilHosting()
    }

    @Test
    fun aKillDuringTheStageStillRemovesWhatTheSessionOwned() = scenario {
        platform.stageGate = CompletableDeferred()
        val first = started()
        first.games.host(Source, Choices.None, null)
        first.settle()
        assertEquals(FakeCustomGamesPlatform.SampleOwned, assertNotNull(journal.read()).owned, "journalled before the stage starts")
        first.scope.cancel()

        platform.stageGate = null
        started()
        assertEquals(listOf(Game to FakeCustomGamesPlatform.SampleOwned), platform.removed)
    }

    @Test
    fun aHostsGameThatNeverReachesThePageWhileSteamUpdatesSaysThat() = scenario {
        platform.updatePending = true
        val run = started()
        run.hostUntilStarting()
        run.link.enterAnswer = listOf(LinkEvent.NotEntered(siteUnreachable = false))
        run.link.say(LinkEvent.InviteReady(Friend))
        run.link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        run.settle()
        assertEquals(OwnGame.Out(EntryProblem.GameUpdatePending), assertIs<Session.Hosting>(run.session).own)
    }

    @Test
    fun aHostsGameThatNeverReachesThePageSaysWhetherDeadlockOrTheSiteIsWhy() = scenario {
        val run = started()
        run.hostUntilStarting()
        run.link.enterAnswer = listOf(LinkEvent.NotEntered(siteUnreachable = false))
        run.link.say(LinkEvent.InviteReady(Friend))
        run.link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        run.settle()
        assertEquals(OwnGame.Out(EntryProblem.ClientDidNotStart), assertIs<Session.Hosting>(run.session).own)

        run.link.enterAnswer = listOf(LinkEvent.NotEntered(siteUnreachable = true))
        run.games.play()
        run.settle()
        assertEquals(OwnGame.Out(EntryProblem.SiteUnreachable), assertIs<Session.Hosting>(run.session).own)
        assertEquals(0, run.link.stops, "the server stays up for the others")
    }

    @Test
    fun aGuestsGameThatNeverReachesThePageWhileSteamUpdatesSaysThat() = scenario {
        platform.updatePending = true
        val run = started()
        run.joinUntilLinked()
        run.link.enterAnswer = listOf(LinkEvent.NotEntered(siteUnreachable = false))
        run.link.say(LinkEvent.Linked)
        run.settle()
        assertEquals(OwnGame.Out(EntryProblem.GameUpdatePending), assertIs<Session.Playing>(run.session).own)
    }

    @Test
    fun theOfferThatArrivesOverTheLinkIsWhatTheGuestIsAskedAboutAndPrepares() = scenario {
        val run = listed()
        val offer = InviteOffer.of(FakeCustomGamesFeed.SampleMode, Choices.None, LocalPlayer("7657", DisplayName.parse("Ann")))
        assertTrue(run.games.openInvite(Friend.shown))
        run.settle()
        assertEquals(Role.Guest(Friend, null, null), run.games.live.value.game?.role, "nothing to show until the host answers")

        run.link.say(LinkEvent.Offer(offer))
        run.settle()
        assertEquals(Session.Confirming(Invite(Friend, offer)), run.session)
        assertEquals(Role.Guest(Friend, offer, FakeCustomGamesFeed.SampleMode.listing), run.games.live.value.game?.role)

        run.games.confirmJoin(null)
        run.settle()
        assertIs<Session.Linking>(run.session)
        assertEquals(listOf<Mode?>(FakeCustomGamesFeed.SampleMode), platform.guestsPrepared)
        assertEquals(1, run.link.joins.size)
    }

    @Test
    fun anOfferHideoutCannotReadStopsTheJoin() = game {
        assertTrue(games.openInvite(Friend.shown))
        settle()
        link.say(LinkEvent.BadOffer)
        settle()
        assertEquals(Session.Ended(EndReason.LinkFailed(LinkFailure.BadInvite)), session)
        assertEquals(1, link.stops)
        assertEquals(emptyList(), link.joins)
    }

    @Test
    fun aHostsOfferNamesTheModeTheChoicesTheSizeAndWhoHosts() = game {
        games.host(FakeCustomGamesFeed.SampleMode, Choices.None, null)
        settle()
        val offer = platform.hosted.single().first.offer
        assertEquals(InviteOffer(DisplayName.parse("nori"), ModeId("arena"), OfferContent.Feed(null), Choices.None, 8), offer)
    }

    @Test
    fun aHostWithNoSteamNameStillInvites() = game {
        platform.player = LocalPlayer("7656", name = null)
        hostUntilHosting()
        assertNull(platform.hosted.single().first.offer.host)
    }

    @Test
    fun hostAgainHostsTheSameModeWithTheSameChoicesAndNoDismiss() = game {
        val choices = Choices.of(mapOf(SettingKey.parse("teams")!! to SettingValue.parse("2v2")!!))!!
        games.host(FakeCustomGamesFeed.SampleMode, choices, null)
        settle()
        games.stop()
        settle()
        assertIs<Session.Ended>(session)
        assertEquals(GameRecord(Role.Host(FakeCustomGamesFeed.SampleMode, choices), FakeCustomGamesPlatform.SamplePlayer, endedAt = games.live.value.game?.endedAt), games.live.value.game)
        games.again()
        settle()
        assertIs<Session.HostStarting>(session)
        val request = FakeCustomGamesFeed.SampleMode to InviteOffer.of(FakeCustomGamesFeed.SampleMode, choices, FakeCustomGamesPlatform.SamplePlayer)
        assertEquals(listOf(request, request), platform.hostRequests)
    }

    @Test
    fun joinAgainFindsTheSameCodeAndJoinsWithNoSecondConfirmWhenTheHostOffersTheSameGame() = game {
        joinUntilLinked()
        link.say(LinkEvent.Stopped(LinkStop.LinkLost))
        settle()
        assertIs<Session.Ended>(session)
        games.again()
        settle()
        assertEquals(Friend, assertIs<Session.Finding>(session).code)
        assertEquals(listOf(Friend, Friend), platform.joined)

        link.say(LinkEvent.Offer(Arena))
        settle()
        assertIs<Session.Linking>(session)
        assertEquals(1, link.joins.size)
        assertEquals(Role.Guest(Friend, Arena, null), games.live.value.game?.role)
    }

    @Test
    fun joinAgainAsksAgainWhenTheHostNowOffersAnotherGame() = game {
        joinUntilLinked()
        link.say(LinkEvent.Stopped(LinkStop.LinkLost))
        settle()
        games.again()
        settle()
        val other = Arena.copy(mode = ModeId("other"))
        link.say(LinkEvent.Offer(other))
        settle()
        assertEquals(Session.Confirming(Invite(Friend, other)), session)
        assertEquals(emptyList(), link.joins)
    }

    @Test
    fun againDoesNothingUntilTheGameHasEnded() = game {
        hostUntilHosting()
        val hosting = games.live.value
        games.again()
        settle()
        assertEquals(hosting, games.live.value)
        assertEquals(1, platform.hostRequests.size)
    }

    @Test
    fun aHostWhoseGameRanIsToldItEndedNotThatItFailedToStart() = game {
        hostUntilHosting()
        link.say(LinkEvent.Stopped(LinkStop.ServerExited, "gone"))
        files.clientActivity.value = GameActivity.Closed
        settle()
        val record = assertNotNull(games.live.value.game)
        assertTrue(record.started)
        assertNotNull(record.endedAt)
    }

    private val older = InstalledMode(
        FakeCustomGamesFeed.SampleMode.copy(version = "0.9.0", origin = ModeOrigin.Feed(Artifact("https://example.test/old.zip", 3, Sha256.parse("0".repeat(64))!!), null)),
        3,
        null,
    )

    private fun TestScope.listed(vararg installed: InstalledMode) = Run(this, FakeCustomGamesFeed()).apply {
        installed.forEach { platform.installedModes[it.mode.id] = it }
        files.held = false
        opened()
    }

    @Test
    fun aHostedFeedModeIsInstalledAndPlayedOnceItsGameIsUp() = scenario {
        val run = listed()
        assertEquals(Install.NotInstalled, run.games.modes.value.installOf(FakeCustomGamesFeed.SampleMode))
        run.games.host(FakeCustomGamesFeed.SampleMode, Choices.None, null)
        run.settle()
        assertIs<Session.HostStarting>(run.session)
        val installed = assertIs<Install.Installed>(run.games.modes.value.installOf(FakeCustomGamesFeed.SampleMode))
        assertNull(installed.update)
        assertNull(installed.record.lastPlayed, "not played until the game is up")
        run.link.say(LinkEvent.InviteReady(Friend))
        run.link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        run.settle()
        assertEquals(FakeCustomGamesPlatform.SamplePlayedAt, run.games.modes.value.installed.getValue(ModeId("arena")).lastPlayed)
    }

    @Test
    fun aFolderModeIsNeverInstalled() = game {
        hostUntilHosting()
        assertEquals(emptyMap(), games.modes.value.installed)
    }

    @Test
    fun anUpdateShowsItsProgressAndThenTheNewVersion() = scenario {
        val run = listed(older)
        val modes = run.games.modes
        assertEquals(Install.Installed(older, FakeCustomGamesFeed.SampleMode), modes.value.installOf(FakeCustomGamesFeed.SampleMode))
        val gate = CompletableDeferred<Unit>()
        platform.installGate = gate
        run.games.update(ModeId("arena"))
        run.settle()
        assertEquals(Install.Updating(Transfer(0, 5)), modes.value.installOf(FakeCustomGamesFeed.SampleMode))
        gate.complete(Unit)
        run.settle()
        val now = assertIs<Install.Installed>(modes.value.installOf(FakeCustomGamesFeed.SampleMode))
        assertEquals("1.0.0", now.record.mode.version)
        assertNull(now.update)
    }

    @Test
    fun updateAllFetchesOnlyWhatHasAnUpdate() = scenario {
        val run = listed(older, InstalledMode(FakeCustomGamesFeed.SampleMode.copy(id = ModeId("gone")), 5, null))
        val retired = library(run.games.modes.value).filterIsInstance<LibraryEntry.Listed>().filterNot { it.inFeed }
        assertEquals(listOf(ModeId("gone")), retired.map { it.mode.id }, "a mode the list dropped stays, with no update")
        run.games.updateAll()
        run.settle()
        assertEquals(listOf(FakeCustomGamesFeed.SampleMode), platform.installs)
    }

    @Test
    fun aFailedUpdateSaysWhyAndKeepsWhatWasThere() = scenario {
        val run = listed(older)
        platform.installProblem = FetchProblem.Connection
        run.games.update(ModeId("arena"))
        run.settle()
        assertEquals(mapOf(ModeId("arena") to FetchProblem.Connection), run.games.modes.value.failed)
        assertEquals(Install.Installed(older, FakeCustomGamesFeed.SampleMode), run.games.modes.value.installOf(FakeCustomGamesFeed.SampleMode))
    }

    @Test
    fun aRemovedModeIsGone() = scenario {
        val run = listed(older)
        run.games.remove(ModeId("arena"))
        run.settle()
        assertEquals(Install.NotInstalled, run.games.modes.value.installOf(FakeCustomGamesFeed.SampleMode))
    }

    @Test
    fun nothingIsUpdatedOrRemovedWhileASessionHoldsTheFiles() = scenario {
        val run = listed(older)
        run.hostUntilHosting()
        run.games.remove(ModeId("arena"))
        run.games.update(ModeId("arena"))
        run.settle()
        assertEquals(older, run.games.modes.value.installed[ModeId("arena")])
        assertEquals(emptyList(), platform.installs)
    }

    @Test
    fun updateAllStopsOnceASessionStartsAndNoInstallRunsBesideItsPreparation() = scenario {
        val duel = ModeId("duel")
        val listing = FakeCustomGamesFeed.SampleModes.copy(modes = listOf(FakeCustomGamesFeed.SampleMode, FakeCustomGamesFeed.SampleMode.copy(id = duel)))
        val run = Run(this, FakeCustomGamesFeed(answer = CustomGamesFetch.Fresh(listing))).apply {
            platform.installedModes[older.mode.id] = older
            platform.installedModes[duel] = older.copy(mode = older.mode.copy(id = duel))
            files.held = false
            opened()
        }
        val gate = CompletableDeferred<Unit>()
        platform.installGate = gate
        run.games.updateAll()
        run.settle()
        assertEquals(listOf(ModeId("arena")), platform.installs.map { it.id })
        run.games.host(FakeCustomGamesFeed.SampleMode, Choices.None, null)
        run.settle()
        assertIs<Session.HostPreparing>(run.session)
        assertEquals(emptyList(), platform.hostRequests, "the preparation waits for the install already running")
        gate.complete(Unit)
        run.settle()
        assertEquals(1, platform.hostRequests.size)
        assertEquals(listOf(ModeId("arena")), platform.installs.map { it.id }, "no install starts once a session has")
    }

    @Test
    fun anAddedFolderIsFoundAndHostedAfterARestartWithNoNetwork() = scenario {
        val first = Run(this, FakeCustomGamesFeed(), hostFolders = flowOf(true)).apply { opened() }
        first.games.addFolder("/built/arena")
        first.settle()
        assertEquals(listOf(LibraryEntry.Folder("/built/arena", FolderRead.Read(Source))), first.games.modes.value.folders)
        first.scope.cancel()

        val offline = FakeCustomGamesFeed(kept = FakeCustomGamesFeed.SampleModes, answer = CustomGamesFetch.Unreachable("offline"))
        val again = Run(this, offline, hostFolders = flowOf(true)).apply {
            files.held = false
            opened()
        }
        val entry = library(again.games.modes.value).filterIsInstance<LibraryEntry.Folder>().single()
        again.games.host(assertIs<FolderRead.Read>(entry.read).mode, Choices.None, null)
        again.settle()
        assertIs<Session.HostStarting>(again.session)
    }

    @Test
    fun foldersShowOnlyWhileHostingModeFoldersIsOn() = scenario {
        val on = MutableStateFlow(false)
        val run = Run(this, FakeCustomGamesFeed(), hostFolders = on).apply { opened() }
        run.games.addFolder("/built/arena")
        run.settle()
        assertNull(run.games.modes.value.folders, "off: no rows and no way to add one")
        assertEquals(emptyList(), library(run.games.modes.value).filterIsInstance<LibraryEntry.Folder>())
        on.value = true
        run.settle()
        assertEquals(listOf("/built/arena"), run.games.modes.value.folders?.map { it.path })
        on.value = false
        run.settle()
        assertNull(run.games.modes.value.folders)
    }

    @Test
    fun aGuestOfAFolderModeFetchesTheHostsPackAfterLinkingAndPlacesItBeforeTheStage() = scenario {
        val pack = FakeCustomGamesPlatform.SampleShared
        val source = FakeCustomGamesFeed()
        val run = Run(this, source).apply { opened() }
        val fetchesBefore = source.fetches
        run.found(InviteOffer(null, ModeId("arena"), OfferContent.Host(pack), Choices.None, 2))
        run.games.confirmJoin(null)
        run.settle()
        assertEquals(fetchesBefore, source.fetches, "no list is asked for content the host shares")
        run.link.say(LinkEvent.Linked)
        run.settle()
        assertIs<Session.Fetching>(run.session)
        run.link.say(LinkEvent.Fetch(Transfer(10, pack.bytes)))
        run.settle()
        assertEquals(Transfer(10, pack.bytes), assertIs<Session.Fetching>(run.session).transfer)
        assertEquals(emptyList(), platform.placed)
        run.link.say(LinkEvent.Fetched)
        run.settle()
        assertEquals(listOf(pack), platform.placed, "placed as admission starts")
        assertEquals(pack, platform.staged.single { it.second != null }.second?.shared)
        assertIs<Session.Playing>(run.session)
    }

    @Test
    fun aHostsPackThatWillNotUnpackStopsTheJoin() = scenario {
        platform.placeProblem = FetchProblem.Unpack
        val run = Run(this, FakeCustomGamesFeed()).apply { opened() }
        run.found(InviteOffer(null, ModeId("arena"), OfferContent.Host(FakeCustomGamesPlatform.SampleShared), Choices.None, 2))
        run.games.confirmJoin(null)
        run.settle()
        run.link.say(LinkEvent.Linked)
        run.link.say(LinkEvent.Fetched)
        run.settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.Preflight(PreflightProblem.Download(FetchProblem.Unpack)))), run.session)
        assertEquals(emptyList(), platform.staged.filter { it.second != null })
    }

    @Test
    fun aFolderThatCanNotBeReadSaysWhyOnItsRowAndCanBeTakenOff() = scenario {
        platform.folderReads["/gone"] = FolderRead.NotThere
        val run = Run(this, FakeCustomGamesFeed(), hostFolders = flowOf(true)).apply { opened() }
        run.games.addFolder("/gone")
        run.games.addFolder("/gone")
        run.settle()
        assertEquals(listOf(LibraryEntry.Folder("/gone", FolderRead.NotThere)), run.games.modes.value.folders, "added once")
        run.games.removeFolder("/gone")
        run.settle()
        assertEquals(emptyList(), run.games.modes.value.folders)
        assertEquals(emptyList(), platform.folders.read())
    }

    @Test
    fun aRunningDeadlockThatIsNotSetUpIsSaidBeforeThePressAndThatPressRestartsIt() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        assertEquals(RestartReason.NotSetUp, games.restartsDeadlock.value, "Host and Join say so before the press")

        games.host(Source, Choices.None, games.restartsDeadlock.value)
        settle()
        assertIs<Session.Restarting>(session)
        assertEquals(listOf("close"), files.calls, "nothing is held, and no second question is asked")
        assertNull(journal.read())
        assertEquals(GamesLines.Pending, files.lines, "the use is recorded for the close")

        files.clientActivity.value = GameActivity.Closed
        settle()
        assertEquals(GamesLines.Mounted, files.lines)
        assertEquals(listOf(FakeCustomGamesPlatform.SampleRenderer), platform.menusRebuilds, "the pack is built for the mode's renderer")
        assertIs<Session.HostStarting>(session)
        assertNull(games.restartsDeadlock.value)
    }

    @Test
    fun aGuestToldOfTheRestartAtJoinIsRestartedWithNoSecondQuestion() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        found()
        games.confirmJoin(RestartReason.NotSetUp)
        settle()
        assertIs<Session.Restarting>(session)
        assertEquals(listOf("close"), files.calls)
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertIs<Session.Linking>(session)
    }

    @Test
    fun aRestartThePlayerWasNotToldOfAtThePressIsAskedForAndWaitsForThem() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(RestartReason.NotSetUp, assertIs<Session.RestartNeeded>(session).why)
        assertEquals(emptyList(), files.calls, "nothing is held or closed while it asks")
        assertNull(journal.read())

        games.restartGame()
        settle()
        assertEquals(listOf("close"), files.calls)
        assertIs<Session.Restarting>(session)
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertIs<Session.HostStarting>(session)
    }

    @Test
    fun aRestartForAnotherReasonThanTheOneToldIsAskedFor() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        files.lines = GamesLines.Removed
        found()
        games.confirmJoin(RestartReason.NotSetUp)
        settle()
        assertEquals(RestartReason.GameUpdated, assertIs<Session.RestartNeeded>(session).why)
        assertEquals(emptyList(), files.calls)
    }

    @Test
    fun aDeadlockThatStartsAgainAfterTheToldRestartIsAskedToRestartAgain() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        games.host(Source, Choices.None, RestartReason.NotSetUp)
        settle()
        assertIs<Session.Restarting>(session)
        files.startsOnWrite = true
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertEquals(RestartReason.NotSetUp, assertIs<Session.RestartNeeded>(session).why, "Deadlock restarts at most once untold")
        assertEquals(listOf("close"), files.calls)
    }

    @Test
    fun hostingAgainAfterAToldRestartAsksForTheNextOne() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        games.host(Source, Choices.None, RestartReason.NotSetUp)
        settle()
        assertIs<Session.Restarting>(session)
        games.stop()
        settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), session)

        games.again()
        settle()
        assertEquals(RestartReason.NotSetUp, assertIs<Session.RestartNeeded>(session).why, "Host again has no dialog to tell of it")
        assertEquals(listOf("close"), files.calls)
    }

    @Test
    fun aRestartThatCannotCloseDeadlockLeavesTheCloseToThePlayerAndGoesOnOnceItIs() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        files.closeFails = true
        joinUntilRestartNeeded()
        games.restartGame()
        settle()
        assertEquals(Session.Restarting(assertIs<Session.Restarting>(session).next, closeFailed = true), session)
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertIs<Session.Linking>(session)
    }

    @Test
    fun aRestartWhoseDeadlockTakesTooLongToCloseLeavesTheCloseToThePlayer() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        joinUntilRestartNeeded()
        games.restartGame()
        settle()
        wait(Deadline.ClientClosing.after + 1.seconds)
        assertTrue(assertIs<Session.Restarting>(session).closeFailed)
    }

    @Test
    fun aDeadlockThePlayerClosesThemselvesGoesOnWithNoRestartPressed() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        joinUntilRestartNeeded()
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertIs<Session.Linking>(session)
        assertFalse("close" in files.calls)
    }

    @Test
    fun aStopWhileARestartIsAskedForEndsWithNothingToCleanUp() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        joinUntilRestartNeeded()
        games.stop()
        settle()
        assertEquals(Session.Ended(EndReason.PlayerStopped), session)
        assertEquals(emptyList(), files.calls)
        assertEquals(emptyList(), link.joins)
        assertEquals(1, link.stops)
        assertNull(journal.read())
    }

    @Test
    fun aDeadlockThatClosesWhileItsSetupIsReadIsSetUpClosedWithNoRestartAsked() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        files.useGate = CompletableDeferred()
        games.host(Source, Choices.None, null)
        settle()
        assertIs<Session.SettingUp>(session)
        files.clientActivity.value = GameActivity.Closed
        settle()
        files.useGate!!.complete(Unit)
        settle()
        assertIs<Session.HostStarting>(session)
        assertEquals(GamesLines.Mounted, files.lines)
        assertFalse("close" in files.calls)
    }

    @Test
    fun aDeadlockThatStartsAsAClosedSetupWritesAsksForTheRestart() = game {
        files.startsOnWrite = true
        found()
        games.confirmJoin(null)
        settle()
        assertEquals(RestartReason.NotSetUp, assertIs<Session.RestartNeeded>(session).why)
    }

    private fun Run.joinUntilRestartNeeded() {
        found()
        games.confirmJoin(null)
        settle()
        assertIs<Session.RestartNeeded>(session)
    }

    @Test
    fun aGameUpdateThatTookTheLinesOutAsksForARestartThatPutsThemBack() = game {
        files.clientActivity.value = GameActivity.Running
        settle()
        files.lines = GamesLines.Removed
        joinUntilRestartNeeded()
        assertEquals(RestartReason.GameUpdated, assertIs<Session.RestartNeeded>(session).why)
        games.restartGame()
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertEquals(GamesLines.Mounted, files.lines)
        assertIs<Session.Linking>(session)
    }

    @Test
    fun menusThatNeverTookTheGameThisRunAskTheNextSessionForARestart() = game {
        runningSetUp()
        hostUntilStarting()
        link.enterAnswer = listOf(LinkEvent.NotEntered(siteUnreachable = false))
        link.say(LinkEvent.InviteReady(Friend))
        link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        settle()
        assertEquals(OwnGame.Out(EntryProblem.MenusDidNotStart), assertIs<Session.Hosting>(session).own)
        assertEquals(RestartReason.MenusDidNotStart, games.restartsDeadlock.value)
        games.stop()
        settle()

        games.host(Source, Choices.None, null)
        settle()
        assertEquals(RestartReason.MenusDidNotStart, assertIs<Session.RestartNeeded>(session).why)
        games.stop()
        settle()
        files.clientActivity.value = GameActivity.Closed
        settle()
        assertNull(games.restartsDeadlock.value, "a new run of Deadlock starts over")
    }

    @Test
    fun anEndedDevSessionsBlockIsClearedAsAClosedDeadlockIsSetUpAndARunningOneIsLeftAsItStarted() = game {
        hostUntilStarting()
        assertEquals(listOf(Game), platform.devSessionsCleared)
        games.stop()
        settle()

        runningSetUp()
        joinUntilLinked()
        assertEquals(listOf(Game), platform.devSessionsCleared, "nothing is written while Deadlock runs")
    }

    @Test
    fun aFolderWhoseMenusLoadFirstRefusesTheJoinBeforeDeadlockStartsOrAnythingIsHeld() = game {
        platform.aheadOfMenus = "citadel/modlock_dev/31bb65da30bd1ed1"
        found()
        games.confirmJoin(null)
        settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.MenusBlocked("citadel/modlock_dev/31bb65da30bd1ed1"))), session)
        assertEquals(emptyList(), files.calls, "nothing held and nothing started")
        assertEquals(emptyList(), link.enters, "no wait for menus that can't load")
        assertTrue(logs.any { "citadel/modlock_dev/31bb65da30bd1ed1" in it }, "$logs")
    }

    @Test
    fun menusAnotherFolderTakesEveryRunAreSaidInsteadOfARestartForEachSession() = game {
        runningSetUp()
        hostUntilStarting()
        link.enterAnswer = listOf(LinkEvent.NotEntered(siteUnreachable = false))
        link.say(LinkEvent.InviteReady(Friend))
        link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        settle()
        assertEquals(OwnGame.Out(EntryProblem.MenusDidNotStart), assertIs<Session.Hosting>(session).own)
        games.stop()
        settle()

        platform.aheadOfMenus = "citadel/modlock_dev/31bb65da30bd1ed1"
        games.refreshModes()
        settle()
        assertNull(games.restartsDeadlock.value, "a restart would load the same menus")
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.MenusBlocked("citadel/modlock_dev/31bb65da30bd1ed1"))), session)
        assertFalse("close" in files.calls)
    }

    @Test
    fun aClosedDeadlockWithNoPackToBeHadRefusesTheSessionHoldingNothing() = game {
        platform.menusStamp = null
        platform.rebuildGives = null
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.MenusUnavailable)), session)
        assertEquals(emptyList(), files.calls)
        assertNull(journal.read())
    }

    @Test
    fun aRunningDeadlockWithNoPackAndNoRendererToBuildOneIsNotAskedToRestart() = game {
        platform.menusStamp = null
        platform.guestResult = SessionEvent.GuestPrepared(FakeCustomGamesPlatform.SampleContent, renderer = null)
        files.clientActivity.value = GameActivity.Running
        settle()
        assertNull(games.restartsDeadlock.value, "no list names a renderer, so a restart can't build a pack")
        found()
        games.confirmJoin(null)
        settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.MenusUnavailable)), session)
        assertFalse("close" in files.calls)
    }

    @Test
    fun aHostWithNoSteamAccountIsRefusedBeforeAnythingIsSetUpOrHeld() = game {
        platform.player = null
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.Preflight(PreflightProblem.NoSteamAccount))), session)
        assertEquals(emptyList(), files.uses)
        assertEquals(emptyList(), files.calls)
        assertNull(journal.read())
    }

    @Test
    fun aClosedDeadlockWhoseLinesCannotBeWrittenRefusesTheSessionAndSaysWhy() = game {
        files.useProblem = LibraryProblem.Busy
        games.host(Source, Choices.None, null)
        settle()
        assertEquals(Session.Ended(EndReason.Refused(Refusal.Library(LibraryProblem.Busy))), session)
        assertEquals(emptyList(), files.calls)
    }

    @Test
    fun aGuestWithNoModeOrFolderKeepsTheLinesThroughEveryClose() = scenario {
        files.lines = GamesLines.Mounted
        files.clientActivity.value = GameActivity.Running
        val run = started()
        files.clientActivity.value = GameActivity.Closed
        run.settle()
        assertEquals(GamesLines.Mounted, files.lines, "a friend's folder mode installs nothing, and the next join needs no restart")
        assertEquals(emptyList(), files.uses)
        assertEquals(emptyList(), platform.gamesFoldersRemoved)
    }

    @Test
    fun removingTheLastModeWhileDeadlockRunsTakesTheLinesOutOnceItCloses() = scenario {
        platform.installedModes[older.mode.id] = older
        files.lines = GamesLines.Mounted
        files.clientActivity.value = GameActivity.Running
        val run = started()
        run.games.remove(older.mode.id)
        run.settle()
        assertEquals(GamesLines.Leaving, files.lines, "nothing is written while Deadlock runs")
        assertEquals(emptyList(), platform.gamesFoldersRemoved)
        files.clientActivity.value = GameActivity.Closed
        run.settle()
        assertEquals(GamesLines.Off, files.lines)
        assertEquals(listOf(Game), platform.gamesFoldersRemoved)
    }

    @Test
    fun anUnreadableGameinfoLeavesTheGameFolderAlone() = scenario {
        files.lines = GamesLines.Unreadable(GameInfoProblem.NoBaseGameEntry)
        started()
        assertEquals(emptyList(), platform.menusRebuilds)
        assertEquals(emptyList(), platform.staged)
        assertEquals(emptyList(), platform.gamesFoldersRemoved)
    }

    @Test
    fun aComputerWithAModeKeepsTheLinesAndHasItsPackAndFolderSeenTo() = scenario {
        platform.installedModes[older.mode.id] = older
        files.lines = GamesLines.Mounted
        started()
        assertEquals(GamesLines.Mounted, files.lines)
        assertEquals(emptyList(), files.uses)
        assertEquals(listOf<Sha256?>(null), platform.menusRebuilds, "no list read yet, so the pack keeps its renderer")
        assertEquals(listOf<Pair<GameRoot, ModeContent?>>(Game to null), platform.staged, "the last mode's files go")
        assertEquals(emptyList(), platform.gamesFoldersRemoved)
    }

    @Test
    fun linesADeadlockUpdateTookOutGoBackOnceItCloses() = scenario {
        platform.installedModes[older.mode.id] = older
        files.lines = GamesLines.Removed
        started()
        assertEquals(GamesLines.Mounted, files.lines)
    }

    @Test
    fun aNewRendererIsBuiltIntoThePackOnlyOnceDeadlockIsClosed() = scenario {
        platform.installedModes[older.mode.id] = older
        files.lines = GamesLines.Mounted
        files.clientActivity.value = GameActivity.Running
        val run = Run(this, FakeCustomGamesFeed()).apply { opened() }
        assertEquals(emptyList(), platform.menusRebuilds)
        files.clientActivity.value = GameActivity.Closed
        run.settle()
        assertEquals(listOf<Sha256?>(FakeCustomGamesFeed.SampleModes.runtime.renderer.sha256), platform.menusRebuilds)
    }

    @Test
    fun openingThePageRecordsNoUseButAFolderOrAnUpdateDoes() = scenario {
        val run = Run(this, FakeCustomGamesFeed(), hostFolders = flowOf(true)).apply { opened() }
        assertEquals(emptyList(), files.uses)
        assertEquals(GamesLines.Off, files.lines)

        run.games.addFolder("/built/arena")
        run.settle()
        assertEquals(GamesLines.Mounted, files.lines)
        run.games.removeFolder("/built/arena")
        run.settle()
        assertEquals(GamesLines.Off, files.lines, "no mode and no folder left")

        platform.installedModes[older.mode.id] = older
        run.games.update(ModeId("arena"))
        run.settle()
        assertEquals(GamesLines.Mounted, files.lines)
    }

    @Test
    fun theHostsOwnGameIsSentInOnceAndNeverPulledBackAfterItLeaves() = game {
        hostUntilHosting()
        link.say(LinkEvent.Game(GameTraffic.Quiet))
        settle()
        assertEquals(OwnGame.Out(), assertIs<Session.Hosting>(session).own)
        wait(10.minutes)
        assertEquals(1, link.enters.size)
        assertEquals(OwnGame.Out(), assertIs<Session.Hosting>(session).own)
    }

    @Test
    fun theLogTellsTheListTheInstallAndEachPhaseOfAHostedGame() = scenario {
        val run = started(FakeCustomGamesFeed())
        run.games.refreshModes()
        run.settle()
        run.games.host(FakeCustomGamesFeed.SampleMode, Choices.None, null)
        run.settle()
        run.link.say(LinkEvent.InviteReady(Friend))
        run.link.say(LinkEvent.Server(ServerEvent.Ready("street")))
        run.link.say(LinkEvent.Entered)
        run.link.say(LinkEvent.Game(GameTraffic.Flowing))
        run.settle()
        run.games.stop()
        run.settle()

        val runtime = "runtime Modlock linux aaaaaaaaaaaa, windows aaaaaaaaaaaa, host aaaaaaaaaaaa, platform aaaaaaaaaaaa, renderer aaaaaaaaaaaa"
        assertEquals(
            listOf(
                "Custom games: read the list of modes: sequence 1, 1 mode, $runtime",
                "Custom games: hosting Arena (arena 1.0.0, from the list), getting it ready",
                "Custom games: setting up Deadlock",
                "Custom games: installed Arena (arena 1.0.0, from the list)",
                "Custom games: holding the game's files and staging the mode",
                "Custom games: starting the server",
                "Custom games: the invite is ready",
                "Custom games: live as host on street",
                "Custom games: sending this computer's game in, starting Deadlock first",
                "Custom games: this computer's game took the connect and is loading the mode",
                "Custom games: this computer's game is in the match",
                "Custom games: stopping, the player stopped it",
                "Custom games: giving the game's files back",
                "Custom games: ended, the player stopped it",
            ),
            logs,
        )
    }
}
