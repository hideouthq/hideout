package gg.hideouthq.core.customgames

import gg.hideouthq.core.customgames.SessionEvent.*
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CustomGamesLogTest {
    private val code = InviteCode.parse("P95-MAN-26D")!!
    private val bebop = FakeCustomGamesFeed.BebopWars
    private val teams = Choices.defaultsOf(bebop.listing)
    private val offer = InviteOffer(null, bebop.id, OfferContent.Feed(Sha256.parse("c".repeat(64))), teams, 4)
    private val game = GameRoot("/games/Deadlock")
    private val setup = FakeCustomGamesPlatform.SampleSetup

    /** Drives the real session machine and record, as the owner does, and keeps every line the log gains. */
    private inner class Walk(var client: GameActivity) {
        private var live = LiveSession()
        private var token = 0L
        val lines = mutableListOf<String>()

        fun on(vararg events: SessionEvent) = events.forEach { event ->
            if (event is ClientActivity) client = event.activity
            val step = reduce(live.session, event, client, DeadlineToken(++token))
            val record = recordAfter(live.game, event, step.session, RecordContext(null, { bebop.listing }, Instant.fromEpochSeconds(1)))
            val after = LiveSession(step.session, record)
            lines += customGamesLog(live, after)
            live = after
        }
    }

    private fun hosted() = Walk(GameActivity.Running).apply {
        on(
            HostRequested(bebop, teams, RestartReason.NotSetUp),
            RuntimePrepared(FakeCustomGamesPlatform.SamplePrepared, FakeCustomGamesPlatform.SamplePort),
            SetupRead(SetupAnswer.NeedsRestart(RestartReason.NotSetUp)),
            ClientActivity(GameActivity.Closed),
            SetupRead(SetupAnswer.Ready(setup)),
            Admitted(game),
            LinkEvent.InviteReady(code),
            LinkEvent.Server(ServerEvent.Ready("street")),
            LinkEvent.Guests(GuestPaths(direct = 1)),
            LinkEvent.Entered,
            LinkEvent.Game(GameTraffic.Flowing),
            LinkEvent.Server(ServerEvent.Joined(0, Player("nori", "7656"))),
            LinkEvent.Server(ServerEvent.Joined(1, Player("Ann", "7657"))),
            StopRequested,
            LinkEvent.Stopped(LinkStop.StdinClosed),
            FilesReleased,
            Dismissed,
        )
    }

    private fun joined() = Walk(GameActivity.Running).apply {
        on(
            InviteOpened(code),
            LinkEvent.Offer(offer),
            JoinConfirmed(),
            GuestPrepared(FakeCustomGamesPlatform.SampleContent, FakeCustomGamesPlatform.SampleRenderer),
            SetupRead(SetupAnswer.Ready(setup)),
            LinkEvent.Linked,
            LinkEvent.Path(LinkPath.Relay),
            Admitted(game),
            LinkEvent.Entered,
            LinkEvent.Game(GameTraffic.Flowing),
            LinkEvent.Path(LinkPath.Direct),
            LinkEvent.Stopped(LinkStop.HostEnded),
            FilesReleased,
        )
    }

    @Test
    fun aHostedSessionReadsAsItsPhases() {
        assertEquals(
            listOf(
                "Custom games: hosting Bebop Wars (bebop-wars 0.1.0, from the list) with teams=2v2, getting it ready, the player agreed to restart Deadlock as it started before custom games were set up",
                "Custom games: setting up Deadlock",
                "Custom games: restarting Deadlock, it started before custom games were set up",
                "Custom games: setting up Deadlock",
                "Custom games: holding the game's files and staging the mode",
                "Custom games: starting the server",
                "Custom games: the invite is ready",
                "Custom games: live as host on street",
                "Custom games: sending this computer's game in, starting Deadlock first",
                "Custom games: guests linked: 1 direct, 0 through the relay",
                "Custom games: this computer's game took the connect and is loading the mode",
                "Custom games: this computer's game is in the match",
                "Custom games: 1 player signed on",
                "Custom games: 2 players signed on",
                "Custom games: stopping, the player stopped it",
                "Custom games: giving the game's files back",
                "Custom games: ended, the player stopped it",
            ),
            hosted().lines,
        )
    }

    @Test
    fun aJoinedSessionReadsAsItsPhases() {
        assertEquals(
            listOf(
                "Custom games: joining a friend's game, looking for the host",
                "Custom games: found the host's game, Bebop Wars (bebop-wars), client cccccccccccc with teams=2v2, for 4 players, waiting for the player to confirm",
                "Custom games: joining, getting the mode ready",
                "Custom games: setting up Deadlock",
                "Custom games: linking to the host's game",
                "Custom games: holding the game's files and staging the mode",
                "Custom games: the link goes through the relay",
                "Custom games: connected to the host's game",
                "Custom games: sending this computer's game in",
                "Custom games: this computer's game took the connect and is loading the mode",
                "Custom games: this computer's game is in the match",
                "Custom games: the link is direct",
                "Custom games: giving the game's files back, the host ended the game",
                "Custom games: ended, the host ended the game",
            ),
            joined().lines,
        )
    }

    @Test
    fun noLineHoldsTheInviteCode() {
        val lines = hosted().lines + joined().lines
        val pieces = listOf(code.value, code.shown) + code.shown.split("-")
        assertTrue(lines.none { line -> pieces.any { it in line } }, lines.joinToString("\n"))
    }

    @Test
    fun anInviteQuotedInAnErrorIsRedacted() {
        val lines = feedLog(ModeList(), ModeList(problem = CustomGamesFetch.Unreachable("no route for ${code.shown}")))

        assertEquals(listOf("Custom games: the list of modes couldn't be read: unreachable, no route for [invite]"), lines)
    }

    @Test
    fun aSessionThatNeverStartedSaysWhyAndAnUnannouncedRestartWaitsForThePlayer() {
        val refused = Walk(GameActivity.Running).apply { on(HostRequested(bebop, Choices.None), RuntimeFailed(Refusal.Preflight(PreflightProblem.NoSteamAccount))) }
        val unannounced = Walk(GameActivity.Running).apply {
            on(InviteOpened(code, acceptedBefore = offer), LinkEvent.Offer(offer), GuestPrepared(FakeCustomGamesPlatform.SampleContent, null), SetupRead(SetupAnswer.NeedsRestart(RestartReason.GameUpdated)))
        }

        assertEquals(
            listOf(
                "Custom games: hosting Bebop Wars (bebop-wars 0.1.0, from the list), getting it ready",
                "Custom games: couldn't start, no Steam account is signed in",
            ),
            refused.lines,
        )
        assertEquals(
            listOf(
                "Custom games: joining the same game again, looking for the host",
                "Custom games: joining Bebop Wars (bebop-wars), client cccccccccccc with teams=2v2, for 4 players, getting the mode ready",
                "Custom games: setting up Deadlock",
                "Custom games: Deadlock needs a restart, something such as a Deadlock update took Hideout's custom games lines out, waiting for the player",
            ),
            unannounced.lines,
        )
    }

    @Test
    fun theFirstReadListsTheInstalledModesAndLaterReadsSayWhatChanged() {
        val arena = InstalledMode(FakeCustomGamesFeed.SampleMode, 5, null)
        val old = InstalledMode(bebop.copy(version = "0.0.9", origin = FakeCustomGamesFeed.SampleMode.origin), 5, null)
        val new = InstalledMode(bebop, 5, null)

        assertEquals(listOf("Custom games: installed modes: Arena (arena 1.0.0, from the list)"), installedLog(null, mapOf(arena.mode.id to arena)))
        assertEquals(emptyList(), installedLog(null, emptyMap()))
        assertEquals(
            listOf("Custom games: installed Bebop Wars (bebop-wars 0.0.9, from the list)"),
            installedLog(mapOf(arena.mode.id to arena), mapOf(arena.mode.id to arena, bebop.id to old)),
        )
        assertEquals(
            listOf("Custom games: updated Bebop Wars (bebop-wars 0.1.0, from the list), was version 0.0.9", "Custom games: removed Arena (arena 1.0.0, from the list)"),
            installedLog(mapOf(arena.mode.id to arena, bebop.id to old), mapOf(bebop.id to new)),
        )
    }

    @Test
    fun aNewerListAndAFailedReadAreEachLoggedOnce() {
        val read = ModeList(feed = FakeCustomGamesFeed.SampleModes)
        val failed = read.copy(problem = CustomGamesFetch.Refused(FeedRefusal.BadSignature))
        val aaaa = "a".repeat(12)

        assertEquals(
            listOf("Custom games: read the list of modes: sequence 1, 1 mode, runtime Modlock linux $aaaa, windows $aaaa, host $aaaa, platform $aaaa, renderer $aaaa"),
            feedLog(ModeList(), read),
        )
        assertEquals(listOf("Custom games: the list of modes couldn't be read: its signature didn't check out"), feedLog(read, failed))
        assertEquals(emptyList(), feedLog(failed, failed))
    }
}
