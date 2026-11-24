package gg.hideouthq.core.customgames.social

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.DeadlineToken
import gg.hideouthq.core.customgames.EndReason
import gg.hideouthq.core.customgames.GameRecord
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.Listing
import gg.hideouthq.core.customgames.LiveSession
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.ModeOrigin
import gg.hideouthq.core.customgames.OwnGame
import gg.hideouthq.core.customgames.Outreach
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.GameSetup
import gg.hideouthq.core.customgames.LinkEvent
import gg.hideouthq.core.customgames.MenusScreenPort
import gg.hideouthq.core.customgames.ModeContent
import gg.hideouthq.core.customgames.PreparedHost
import gg.hideouthq.core.customgames.RecordContext
import gg.hideouthq.core.customgames.ServerEvent
import gg.hideouthq.core.customgames.SessionEvent
import gg.hideouthq.core.customgames.SetupAnswer
import gg.hideouthq.core.customgames.recordAfter
import gg.hideouthq.core.customgames.reduce
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private fun steam(last: Int) = checkNotNull(SteamId.parse("765611980000000%02d".format(last)))

private val Me = steam(1)
private val Kestrel = steam(7)
private val Nori = steam(8)
private val Code = checkNotNull(InviteCode.parse("P95-MAN-26D"))
private val Later = checkNotNull(InviteCode.parse("Q22-RST-33E"))
private val Bebop = FakeCustomGamesFeed.BebopWars
private val Offer = InviteOffer.of(Bebop, Choices.None, LocalPlayer(Me.value, null))
private val Fra = RelayRegion(4, "fra")
private val Now = Instant.fromEpochSeconds(1_800_000_000)

class SocialTest {
    private var tokens = 0
    private val fresh = { "%032x".format(++tokens) }

    private fun record(invitees: Set<SteamId> = setOf(Kestrel), listing: Listing = Listing.InviteOnly) =
        GameRecord(Role.Host(Bebop, Choices.None, Outreach(listing, invitees), Offer), LocalPlayer(Me.value, null))

    private fun hosting(code: InviteCode = Code) = Session.Hosting(code, "street", own = OwnGame.In, region = Fra)

    private fun SocialState.after(vararg inputs: SocialInput, at: Instant = Now): Pair<SocialState, List<SocialEffect>> =
        inputs.fold(this to emptyList()) { (state, effects), input -> reduceSocial(state, input, at, fresh).let { (next, more) -> next to effects + more } }

    private fun invitation(id: String, from: SteamId = Kestrel, expires: Instant = Now + 60.seconds) =
        Invitation(checkNotNull(InvitationId.parse(id.padEnd(32, '0'))), from, JoinTicket(Code, Offer), 2, expires)

    @Test
    fun eachInviteeGetsOneDeliveryOnceTheGameHostsWithACodeAndNeverASecond() {
        val starting = LiveSession(Session.HostStarting(null, null, DeadlineToken(1)), record(setOf(Kestrel, Nori)))
        val (before, none) = SocialState().after(SocialInput.Live(starting))
        assertEquals(emptyList(), none, "nothing goes out before there is a code")

        val live = LiveSession(hosting(), record(setOf(Kestrel, Nori)))
        val (sent, effects) = before.after(SocialInput.Live(live))
        assertEquals(setOf(Kestrel, Nori), effects.filterIsInstance<SocialEffect.Deliver>().map { it.to }.toSet())
        assertEquals(JoinTicket(Code, Offer), effects.filterIsInstance<SocialEffect.Deliver>().first().ticket, "the offer the helper serves")
        assertEquals(2, effects.filterIsInstance<SocialEffect.Deliver>().map { it.id }.toSet().size, "each invitee's invite has its own id")

        val (answered, again) = sent.after(SocialInput.Live(live), SocialInput.Answered(Code, Kestrel, Delivery.Delivered), SocialInput.Live(live))
        assertEquals(emptyList(), again, "a repeated Live and an answer send nothing twice")
        assertEquals(mapOf(Kestrel to Delivery.Delivered, Nori to Delivery.Sending), answered.hosted?.deliveries)

        val wren = steam(9)
        val (_, added) = answered.after(SocialInput.Live(live.copy(game = record(setOf(Kestrel, Nori, wren)))))
        assertEquals(listOf(wren), added.filterIsInstance<SocialEffect.Deliver>().map { it.to }, "a friend invited later gets only theirs")
    }

    @Test
    fun aSecondHostPressThatTheSessionRefusesNeitherPublishesNorInvites() {
        val me = LocalPlayer(Me.value, null)
        val prepared = PreparedHost("m", "s", emptyList(), ModeContent(emptyList()), Offer, me)
        val events = listOf(
            SessionEvent.HostRequested(Bebop, Choices.None),
            SessionEvent.HostRequested(Bebop, Choices.None, outreach = Outreach(Listing.Public, setOf(Nori))),
            SessionEvent.RuntimePrepared(prepared, 2),
            SessionEvent.SetupRead(SetupAnswer.Ready(GameSetup(MenusScreenPort, "https://hideouthq.gg/bridge/"))),
            SessionEvent.Admitted(GameRoot("/g")),
            LinkEvent.InviteReady(Code, Fra),
            LinkEvent.Server(ServerEvent.Ready("street")),
        )
        val context = RecordContext(me, { null }, Now)
        val live = events.fold(LiveSession()) { live, event ->
            val next = reduce(live.session, event, GameActivity.Closed, DeadlineToken(1)).session
            LiveSession(next, recordAfter(live, event, next, context))
        }
        assertIs<Session.Hosting>(live.session)
        assertEquals(Outreach.Private, (live.game?.role as? Role.Host)?.outreach, "the game that started is the first press's")

        val (state, effects) = SocialState().after(SocialInput.Live(live))
        assertEquals(emptyList(), effects, "nobody is invited")
        assertNull(listingWanted(state, live), "and the game is not listed")
    }

    @Test
    fun aNewGameStartsWithNoDeliveriesAndAnAnswerForTheOldCodeIsDropped() {
        val (first, _) = SocialState().after(SocialInput.Live(LiveSession(hosting(), record())))
        val (ended, _) = first.after(SocialInput.Live(LiveSession(Session.Ended(EndReason.PlayerStopped), record())))
        assertNull(ended.hosted)
        val (next, effects) = ended.after(SocialInput.Live(LiveSession(hosting(Later), record())), SocialInput.Answered(Code, Kestrel, Delivery.Delivered))
        assertEquals(listOf(Kestrel), effects.filterIsInstance<SocialEffect.Deliver>().map { it.to }, "the same friend is invited to the new game")
        assertEquals(mapOf(Kestrel to Delivery.Sending), next.hosted?.deliveries)
        assertEquals(Later, next.hosted?.code)
    }

    @Test
    fun aDuplicateInviteShowsOnceEvenAfterItWasClosed() {
        val invite = invitation("9f")
        val (shown, _) = SocialState().after(SocialInput.Came(invite), SocialInput.Came(invite))
        assertEquals(listOf(invite), shown.inbox)
        val (closed, _) = shown.after(SocialInput.Closed(invite.id), SocialInput.Came(invite))
        assertEquals(emptyList(), closed.inbox, "a dismissed invite delivered again stays closed")
    }

    @Test
    fun aNewerInviteFromTheSameFriendReplacesTheOlder() {
        val older = invitation("01")
        val newer = invitation("02")
        val other = invitation("03", from = Nori)
        val (state, _) = SocialState().after(SocialInput.Came(older), SocialInput.Came(other), SocialInput.Came(newer))
        assertEquals(listOf(newer, other), state.inbox)
    }

    @Test
    fun expiredInvitesDropAndAreForgottenWithTheirExpiry() {
        val soon = invitation("01", expires = Now + 10.seconds)
        val late = invitation("02", from = Nori, expires = Now + 50.seconds)
        val (state, _) = SocialState().after(SocialInput.Came(soon), SocialInput.Came(late))
        val (ticked, _) = state.after(SocialInput.Tick, at = Now + 11.seconds)
        assertEquals(listOf(late), ticked.inbox)
        assertEquals(setOf(InvitationKey(Nori, late.id)), ticked.seen.keys)
        val (stale, _) = SocialState().after(SocialInput.Came(soon), at = Now + 11.seconds)
        assertEquals(emptyList(), stale.inbox, "one that arrives expired never shows")
    }

    @Test
    fun aPublicFeedGameIsListedOnlyWhileItHostsWithACode() {
        val public = record(listing = Listing.Public)
        val (state, _) = SocialState().after(SocialInput.Live(LiveSession(hosting(), public)))
        val wanted = assertNotNull(listingWanted(state, LiveSession(hosting(), public)))
        assertEquals(JoinTicket(Code, Offer), wanted.ticket)
        assertEquals(Fra, wanted.region)
        assertEquals(state.hosted?.token, wanted.token)
        assertNull(listingWanted(state, LiveSession(hosting(), record(listing = Listing.InviteOnly))), "invite-only")
        assertNull(listingWanted(state, LiveSession(Session.Ended(EndReason.PlayerStopped), public)), "ended")
        val folder = GameRecord(Role.Host(Bebop.copy(origin = ModeOrigin.Folder("/modes/bebop")), Choices.None, Outreach(Listing.Public, emptySet()), Offer), null)
        assertNull(listingWanted(state, LiveSession(hosting(), folder)), "a folder's mode would make strangers download the host's build")
    }

    @Test
    fun theMailboxIsOpenWhileAGameListensAndBrieflyAfterAndNeverForAnotherAccount() {
        val (listening, _) = SocialState().after(SocialInput.Streams(1))
        assertEquals(Me, mailboxOwner(listening, Now, Me))
        assertNull(mailboxOwner(listening, Now, null), "no Steam login, no mailbox")
        val (closed, _) = listening.after(SocialInput.Streams(0))
        assertEquals(Me, mailboxOwner(closed, Now + 29.seconds, Me), "a page reload keeps it")
        assertNull(mailboxOwner(closed, Now + 31.seconds, Me))
        val (other, _) = listening.after(SocialInput.GameSelf(Nori))
        assertNull(mailboxOwner(other, Now, Me))
        assertNull(mailboxOwner(SocialState(), Now, Me), "never opened")
    }

    @Test
    fun serversAreReadOneAtATimeAndPingIsMeasuredAtMostEveryFiveMinutes() {
        val (loading, first) = SocialState().after(SocialInput.ServersWanted, SocialInput.ServersWanted)
        assertEquals(listOf<SocialEffect>(SocialEffect.ReadServers(measure = true)), first)
        val game = PublicGame(checkNotNull(ListingId.parse("3c41a9e07d5b2f18")), Offer, Fra, 2, GameStatus.Lobby)
        val (read, _) = loading.after(SocialInput.ServersAnswered(listOf(game), mapOf(4 to 31)))
        assertEquals(Servers(ServersRead.Ready, listOf(game), mapOf(4 to 31), Now), read.servers)
        assertEquals(listOf<SocialEffect>(SocialEffect.ReadServers(measure = false)), read.after(SocialInput.ServersWanted, at = Now + 4.minutes).second)
        assertEquals(listOf<SocialEffect>(SocialEffect.ReadServers(measure = true)), read.after(SocialInput.ServersWanted, at = Now + 5.minutes).second)
        val (down, _) = read.after(SocialInput.ServersWanted, SocialInput.ServersAnswered(null, null))
        assertEquals(ServersRead.Unreachable, down.servers.read)
        assertEquals(mapOf(4 to 31), down.servers.ping, "the last ping stays")
    }
}
