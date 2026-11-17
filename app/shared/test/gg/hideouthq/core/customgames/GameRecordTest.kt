package gg.hideouthq.core.customgames

import gg.hideouthq.core.customgames.SessionEvent.*
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.library.GameActivity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class GameRecordTest {
    private val me = LocalPlayer("7656", DisplayName.parse("nori"))
    private val mode = FakeCustomGamesFeed.SampleMode
    private val at = Instant.fromEpochSeconds(1_800_000_000)
    private val context = RecordContext(me, { offer -> FakeCustomGamesFeed.SampleModes.mode(offer.mode)?.listing }, at)
    private val token = DeadlineToken(1)
    private val offer = InviteOffer(DisplayName.parse("ann"), mode.id, OfferContent.Feed(null), Choices.None, 4)
    private val code = InviteCode.parse("P95-MAN-26D")!!

    private fun play(vararg events: SessionEvent, start: LiveSession = LiveSession(), client: GameActivity = GameActivity.Closed, with: RecordContext = context): LiveSession =
        events.fold(start) { live, event ->
            val next = reduce(live.session, event, client, token).session
            LiveSession(next, recordAfter(live.game, event, next, with))
        }

    private val prepared = PreparedHost("m", "s", emptyList(), ModeContent(emptyList()), InviteOffer.of(mode, Choices.None, me), me)
    private val ready = SetupRead(SetupAnswer.Ready(GameSetup(MenusScreenPort, "https://hideouthq.gg/bridge/")))
    private val hostToHosting = arrayOf(
        HostRequested(mode, Choices.None), RuntimePrepared(prepared, 2), ready, Admitted(gg.hideouthq.core.library.GameRoot("/g")),
        LinkEvent.InviteReady(code), LinkEvent.Server(ServerEvent.Ready("street")),
    )

    @Test
    fun aHostRecordBeginsWithTheRequestAndKnowsItsModeBeforeAnythingElseDoes() {
        val preparing = play(HostRequested(mode, Choices.None))
        assertEquals(GameRecord(Role.Host(mode, Choices.None), me), preparing.game)
        assertEquals(mode.listing, preparing.game?.listing)
        assertEquals(8, preparing.game?.capacity)
    }

    @Test
    fun aHostWhoReachedHostingStartedAndKeepsThatThroughTheEnd() {
        val hosting = play(*hostToHosting)
        assertTrue(assertNotNull(hosting.game).started)
        val ended = play(StopRequested, LinkEvent.Stopped(LinkStop.StdinClosed), FilesReleased, start = hosting)
        assertEquals(Session.Ended(EndReason.PlayerStopped), ended.session)
        val record = assertNotNull(ended.game)
        assertTrue(record.started, "a game that ran ended, it didn't fail to start")
        assertEquals(at, record.endedAt)
        assertNull(play(Dismissed, start = ended).game)
    }

    @Test
    fun aHostWhoNeverReachedHostingDidNotStart() {
        val failed = play(HostRequested(mode, Choices.None), RuntimeFailed(Refusal.Preflight(PreflightProblem.SteamNotFound)))
        assertEquals(Session.Ended(EndReason.Refused(Refusal.Preflight(PreflightProblem.SteamNotFound))), failed.session)
        assertFalse(assertNotNull(failed.game).started)
        assertEquals(at, failed.game.endedAt)
    }

    @Test
    fun theFirstEndKeepsItsTime() {
        val ended = play(HostRequested(mode, Choices.None), StopRequested)
        val later = RecordContext(me, { null }, at + kotlin.time.Duration.parse("1h"))
        assertEquals(at, play(ClientActivity(GameActivity.Running), start = ended, with = later).game?.endedAt)
    }

    @Test
    fun hostingAgainFromAnEndedGameStartsAFreshRecord() {
        val ended = play(HostRequested(mode, Choices.None), StopRequested)
        val folder = FakeCustomGamesFeed.SampleFolder
        val again = play(HostRequested(folder, Choices.None), start = ended)
        assertEquals(GameRecord(Role.Host(folder, Choices.None), me), again.game)
    }

    @Test
    fun aGuestRecordBeginsWithTheCodeAndTakesTheHostsOfferAndSize() {
        val finding = play(InviteOpened(code))
        assertEquals(GameRecord(Role.Guest(code, null, null), me), finding.game)
        assertNull(finding.game?.capacity, "no size until the host answers")
        val confirming = play(LinkEvent.Offer(offer), start = finding)
        val record = assertNotNull(confirming.game)
        assertEquals(Role.Guest(code, offer, mode.listing), record.role)
        assertEquals(4, record.capacity, "from the host's offer, not the guest's listing")
        assertFalse(record.started)
    }

    @Test
    fun aNewerLinkReplacesTheGuestRecordAndTheSameOneKeepsIt() {
        val other = InviteCode.parse("ABC-DEF-GH2")!!
        val confirming = play(InviteOpened(code), LinkEvent.Offer(offer))
        assertEquals(confirming.game, play(InviteOpened(code), start = confirming).game)
        assertEquals(Role.Guest(other, null, null), play(InviteOpened(other), start = confirming).game?.role)
    }

    @Test
    fun theSameCodeOpenedAfterAnEndIsANewGameThatKnowsWhatWasAccepted() {
        val ended = play(InviteOpened(code), LinkEvent.Offer(offer), JoinConfirmed(), LinkEvent.Stopped(LinkStop.LinkLost))
        assertNotNull(ended.game?.endedAt)
        assertEquals(GameRecord(Role.Guest(code, offer, mode.listing), me), play(InviteOpened(code, acceptedBefore = offer), start = ended).game)
    }

    @Test
    fun anOfferOtherThanTheOneAcceptedReplacesItInTheRecord() {
        val other = offer.copy(capacity = 2)
        val confirming = play(InviteOpened(code, acceptedBefore = offer), LinkEvent.Offer(other))
        assertIs<Session.Confirming>(confirming.session)
        assertEquals(Role.Guest(code, other, mode.listing), confirming.game?.role)
    }

    @Test
    fun aGuestStartsOnlyOnceItsGameIsInTheMatch() {
        val sending = play(InviteOpened(code), LinkEvent.Offer(offer), JoinConfirmed(), GuestPrepared(ModeContent(emptyList()), null), ready, LinkEvent.Linked, Admitted(gg.hideouthq.core.library.GameRoot("/g")), client = GameActivity.Running)
        assertEquals(OwnGame.Sending(startingDeadlock = false), assertIs<Session.Playing>(sending.session).own)
        val joining = play(LinkEvent.Entered, start = sending, client = GameActivity.Running)
        assertFalse(assertNotNull(joining.game).started, "a game told to join hasn't started")
        val inGame = play(LinkEvent.Game(GameTraffic.Flowing), start = joining, client = GameActivity.Running)
        assertTrue(assertNotNull(inGame.game).started)
        val dropped = play(LinkEvent.Game(GameTraffic.Lost), start = inGame, client = GameActivity.Running)
        assertTrue(assertNotNull(dropped.game).started, "nothing unmarks it")
    }

    @Test
    fun aGuestListingFoundLaterIsTakenUp() {
        val nothingKnown = RecordContext(null, { null }, at)
        val confirming = play(InviteOpened(code), LinkEvent.Offer(offer), with = nothingKnown)
        assertNull(confirming.game?.listing)
        val preparing = play(JoinConfirmed(), start = confirming)
        assertEquals(mode.listing, preparing.game?.listing)
        assertEquals(me, preparing.game?.me)
    }

    @Test
    fun theNewestReportOfEachSectionIsKeptThroughTheEnd() {
        val first = SectionState.Scoreboard(listOf(ScoreSide(1, listOf(0)), ScoreSide(0, listOf(1))), emptyList())
        val second = first.copy(sides = listOf(ScoreSide(2, listOf(0)), ScoreSide(0, listOf(1))))
        val hosting = play(*hostToHosting)
        val scored = play(LinkEvent.Reported(listOf(first)), LinkEvent.Reported(listOf(second)), start = hosting)
        assertEquals(hosting.session, scored.session, "a score moves no stage")
        assertEquals(mapOf(SectionKind.Scoreboard to second), scored.game?.reported)
        val ended = play(StopRequested, LinkEvent.Stopped(LinkStop.StdinClosed), FilesReleased, start = scored)
        assertEquals(mapOf(SectionKind.Scoreboard to second), ended.game?.reported, "the last score stays for the result")
    }

    @Test
    fun recoveringHasNoRecord() {
        assertNull(play(CrashFound).game)
    }
}
