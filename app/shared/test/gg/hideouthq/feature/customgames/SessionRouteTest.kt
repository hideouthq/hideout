package gg.hideouthq.feature.customgames

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.DeadlineToken
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.EntryProblem
import gg.hideouthq.core.customgames.OwnGame
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.GuestLink
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.navigation.Route
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class SessionRouteTest {
    private val host = GameRecord(Role.Host(FakeCustomGamesFeed.BebopWars, Choices.None), null)
    private val guest = GameRecord(Role.Guest(checkNotNull(InviteCode.parse("ABC-DEF-GH2")), null, null), null)
    private val link = GuestLink()

    @Test
    fun hostingFromAnywhereOpensTheGameScreenOnce() {
        val preparing = LiveSession(Session.HostPreparing(DeadlineToken(1)), host)
        assertEquals(Route.CustomGame, sessionRoute(LiveSession(), preparing))
        assertNull(sessionRoute(preparing, preparing.copy(session = Session.HostPreparing(DeadlineToken(2)))))
    }

    @Test
    fun aHostWhoseStartOutranTheCollectorStillLandsOnTheGameScreen() {
        val hosting = LiveSession(Session.Hosting(checkNotNull(InviteCode.parse("P95-MAN-26D")), "street", own = OwnGame.In), host.copy(startedAt = Instant.fromEpochSeconds(0)))
        assertEquals(Route.CustomGame, sessionRoute(LiveSession(), hosting))
        assertEquals(Route.CustomGame, sessionRoute(LiveSession(), LiveSession(Session.Ended(EndReason.ServerDied), host.copy(endedAt = Instant.fromEpochSeconds(1)))))
    }

    @Test
    fun anEndStaysAndHostingAgainLandsAgain() {
        val preparing = LiveSession(Session.HostPreparing(DeadlineToken(1)), host)
        val ended = LiveSession(Session.Ended(EndReason.PlayerStopped), host.copy(startedAt = Instant.fromEpochSeconds(0), endedAt = Instant.fromEpochSeconds(1)))
        assertNull(sessionRoute(preparing.copy(game = host.copy(startedAt = Instant.fromEpochSeconds(0))), ended))
        assertNull(sessionRoute(ended, ended.copy(game = ended.game?.copy(reported = emptyMap()))))
        assertEquals(Route.CustomGame, sessionRoute(ended, preparing))
    }

    @Test
    fun aHostWhoEndsBeforeTheGameStartsGoesBackToTheLibraryOnce() {
        val preparing = LiveSession(Session.HostPreparing(DeadlineToken(1)), host)
        val cancelled = LiveSession(Session.Ended(EndReason.PlayerStopped), host.copy(endedAt = Instant.fromEpochSeconds(1)))
        assertEquals(Route.CustomGames, sessionRoute(preparing, cancelled))
        assertNull(sessionRoute(cancelled, cancelled.copy(game = cancelled.game?.copy(reported = emptyMap()))))
        assertNull(sessionRoute(preparing, cancelled.copy(session = Session.Ended(EndReason.ServerDied))), "a failed start stays to say why")
    }

    @Test
    fun aGuestGoesToTheGameScreenWhenTheGameIsIn() {
        val connecting = LiveSession(Session.Playing(link, OwnGame.Joining(DeadlineToken(1))), guest)
        val connected = LiveSession(Session.Playing(link, OwnGame.In), guest.copy(startedAt = Instant.fromEpochSeconds(0)))
        assertNull(sessionRoute(LiveSession(), connecting))
        assertEquals(Route.CustomGame, sessionRoute(connecting, connected))
        assertNull(sessionRoute(connected, connected.copy(session = Session.Playing(link, OwnGame.Out(EntryProblem.Dropped)))))
    }

    @Test
    fun theQuitGuardAsksOnlyWithAWarningAndLeavesOnConfirm() {
        val guard = QuitGuard()
        var left = 0
        assertFalse(guard.ask(null) { left++ })
        assertTrue(guard.ask(QuitWarning.EndsHostedGame) { left++ })
        assertEquals(QuitWarning.EndsHostedGame, guard.asking)
        guard.cancel()
        assertEquals(0, left)
        guard.ask(QuitWarning.LeavesGame) { left++ }
        guard.confirm()
        assertEquals(1, left)
        assertNull(guard.asking)
    }
}
