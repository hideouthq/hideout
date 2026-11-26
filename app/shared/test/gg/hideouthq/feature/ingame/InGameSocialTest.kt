package gg.hideouthq.feature.ingame

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.CustomGamesFetch
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.LinkEvent
import gg.hideouthq.core.customgames.Listing
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.Outreach
import gg.hideouthq.core.customgames.Player
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.Role
import gg.hideouthq.core.customgames.ServerEvent
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.social.Delivery
import gg.hideouthq.core.customgames.social.Directory
import gg.hideouthq.core.customgames.social.GameStatus
import gg.hideouthq.core.customgames.social.Invitation
import gg.hideouthq.core.customgames.social.InvitationId
import gg.hideouthq.core.customgames.social.ListingId
import gg.hideouthq.core.customgames.social.ListingWanted
import gg.hideouthq.core.customgames.social.Mailbox
import gg.hideouthq.core.customgames.social.PublicGame
import gg.hideouthq.core.customgames.social.Resolved
import gg.hideouthq.core.customgames.social.Social
import gg.hideouthq.core.customgames.social.SocialNetwork
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.FakeSessionFiles
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val Me = checkNotNull(SteamId.parse("76561198000000001"))
private val Kestrel = checkNotNull(SteamId.parse("76561198000000007"))
private val Nori = checkNotNull(SteamId.parse("76561198000000008"))
private val Bebop = FakeCustomGamesFeed.BebopWars
private val Solo = FakeCustomGamesFeed.SoloMode
private val Code = checkNotNull(InviteCode.parse("P95-MAN-26D"))
private val FriendCode = checkNotNull(InviteCode.parse("Q22-RST-33E"))
private val FriendOffer = InviteOffer.of(Bebop, Choices.None, LocalPlayer("76561198000000007", null))
private val Fra = RelayRegion(4, "fra")
private val Listed = checkNotNull(ListingId.parse("3c41a9e07d5b2f18"))

private class FakeMailbox : Mailbox {
    val held = CopyOnWriteArrayList<SteamId?>()
    val delivered = CopyOnWriteArrayList<Pair<SteamId, JoinTicket>>()
    val arriving = MutableSharedFlow<Invitation>(extraBufferCapacity = 16)
    override val refused = MutableStateFlow<SteamId?>(null)

    @Volatile
    var answer: suspend () -> Delivery = { Delivery.Delivered }

    override fun hold(owner: SteamId?) {
        if (held.lastOrNull() != owner || held.isEmpty()) held += owner
    }

    override val invitations: Flow<Invitation> = arriving

    override suspend fun deliver(id: InvitationId, to: SteamId, from: SteamId, ticket: JoinTicket, players: Int): Delivery {
        delivered += to to ticket
        return answer()
    }
}

private class FakeDirectory : Directory {
    val kept = CopyOnWriteArrayList<ListingWanted?>()
    val listed = listOf(PublicGame(Listed, FriendOffer, Fra, 1, GameStatus.Lobby))

    @Volatile
    var answer = CompletableDeferred<Resolved>(Resolved.Refused.Closed)

    override suspend fun list(): List<PublicGame> = listed

    override suspend fun resolve(id: ListingId): Resolved = answer.await()

    override fun keep(wanted: ListingWanted?) {
        if (kept.isEmpty() || kept.last() != wanted) kept += wanted
    }
}

class InGameSocialTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val platform = FakeCustomGamesPlatform(chatty = false)
    private val feed = FakeCustomGamesFeed(answer = CustomGamesFetch.Fresh(FakeCustomGamesFeed.SampleModes.copy(modes = listOf(Bebop, Solo))))
    private val games = CustomGames(platform, FakeSessionFiles(), feed, scope, hostFolders = flowOf(false)).apply { start() }
    private val mailbox = FakeMailbox()
    private val directory = FakeDirectory()
    private val social = Social(games, SocialNetwork(mailbox, directory) { mapOf(4 to 31) }, scope, me = { Me }, sendLimit = 1.seconds).apply { start() }
    private val inGame = InGame(games, social, SiteOrigin.parse("https://hideouthq.gg"), scope)

    @AfterTest
    fun end() = scope.cancel()

    private fun <T> Flow<T>.awaited(what: String, wanted: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(60.seconds) { first(wanted) }
        } catch (e: Exception) {
            throw AssertionError("never saw $what", e)
        }
    }

    private fun press(command: PanelCommand) = runBlocking { inGame.run(command) }

    private fun until(what: String, holds: () -> Boolean) = runBlocking {
        try {
            withTimeout(10.seconds) { while (!holds()) delay(10.milliseconds) }
        } catch (e: Exception) {
            throw AssertionError("never saw $what", e)
        }
    }

    private fun topic(topic: Topic, what: String, wanted: (JsonObject) -> Boolean): JsonObject =
        inGame.topics.map { topics -> topics[topic]?.let { Json.parseToJsonElement(it).jsonObject } }.awaited(what) { it != null && wanted(it) }!!

    private fun hostAndReachHosting(outreach: Outreach, mode: ModeId = Bebop.id) {
        inGame.opened()
        games.modes.awaited("the mode list") { it.feed != null }
        assertEquals(CommandResult.Accepted, press(PanelCommand.Host(mode, emptyMap(), outreach, restart = null)))
        until("the helper started") { platform.links.isNotEmpty() }
        platform.links.last().say(LinkEvent.InviteReady(Code, Fra))
        platform.links.last().say(LinkEvent.Server(ServerEvent.Ready("street")))
        games.live.awaited("the game hosting") { it.session is Session.Hosting }
    }

    private fun invitation(id: String, from: SteamId = Kestrel) =
        Invitation(checkNotNull(InvitationId.parse(id.padEnd(32, '0'))), from, JoinTicket(FriendCode, FriendOffer), 2, Clock.System.now() + 60.seconds)

    private fun inbox(what: String, wanted: (List<String>) -> Boolean) =
        topic(Topic.Inbox, what) { frame -> wanted(frame["invites"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }) }

    @Test
    fun eachInviteeGetsOneInviteOnceTheGameHostsAndTheCardSaysWhatCameOfIt() {
        mailbox.answer = { Delivery.NotReached }
        hostAndReachHosting(Outreach(Listing.InviteOnly, setOf(Kestrel)))
        val card = topic(Topic.Game, "the delivery on the card") { it["host"]?.jsonObject?.get("deliveries")?.jsonObject?.isNotEmpty() == true && it["host"]!!.jsonObject["deliveries"]!!.jsonObject[Kestrel.value]?.jsonPrimitive?.content != "sending" }
        assertEquals("not-reached", card["host"]!!.jsonObject["deliveries"]!!.jsonObject[Kestrel.value]!!.jsonPrimitive.content, "the card offers Copy link")
        assertEquals(listOf(Kestrel to JoinTicket(Code, checkNotNull(games.live.value.game?.role?.let { (it as Role.Host).offer }))), mailbox.delivered.toList())

        platform.links.last().say(LinkEvent.Server(ServerEvent.Joined(1, Player("Kit", "76561198000000003"))))
        games.live.awaited("a friend signed on") { (it.session as? Session.Hosting)?.players?.isNotEmpty() == true }
        assertEquals(CommandResult.Accepted, press(PanelCommand.Invite(setOf(Kestrel, Nori))))
        games.live.awaited("Nori invited") { Nori in (it.game?.role as Role.Host).outreach.invitees }
        until("Nori's invite") { mailbox.delivered.size >= 2 }
        runBlocking { delay(300.milliseconds) }
        assertEquals(listOf(Kestrel, Nori), mailbox.delivered.map { it.first }, "Kestrel's invite never goes twice")
    }

    @Test
    fun aSoloGameInvitesNobodyIsNeverListedAndRefusesBothPressesWhateverItsHostPressAsked() {
        hostAndReachHosting(Outreach(Listing.Public, setOf(Kestrel)), Solo.id)
        assertEquals(Outreach.Private, assertIs<Role.Host>(games.live.value.game?.role).outreach)
        val card = topic(Topic.Game, "the solo game's card") { it["host"] is JsonObject && it["startsHost"]?.jsonPrimitive?.content == "false" }["host"]!!.jsonObject
        assertEquals(JsonNull, card["link"], "no link to copy")
        assertEquals("invite-only", card["listing"]!!.jsonPrimitive.content)
        assertEquals(emptyList(), card["invitees"]!!.jsonArray.toList())
        assertEquals(CommandResult.Invalid, press(PanelCommand.Invite(setOf(Nori))))
        assertEquals(CommandResult.Invalid, press(PanelCommand.Relist(Listing.Public)))
        runBlocking { delay(300.milliseconds) }
        assertEquals(emptyList(), mailbox.delivered.toList())
        assertEquals(emptyList(), directory.kept.filterNotNull())
    }

    private fun deliveryTo(friend: SteamId): String =
        topic(Topic.Game, "an answer for ${friend.value}") { it.deliveries()?.get(friend.value)?.let { delivery -> delivery != "sending" } == true }.deliveries()!!.getValue(friend.value)

    private fun JsonObject.deliveries(): Map<String, String>? =
        (this["host"] as? JsonObject)?.get("deliveries")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun aSendThatBreaksOrNeverAnswersEndsNotReached() {
        mailbox.answer = { error("the send broke") }
        hostAndReachHosting(Outreach(Listing.InviteOnly, setOf(Kestrel)))
        assertEquals("not-reached", deliveryTo(Kestrel))
        mailbox.answer = { awaitCancellation() }
        assertEquals(CommandResult.Accepted, press(PanelCommand.Invite(setOf(Nori))))
        assertEquals("not-reached", deliveryTo(Nori))
    }

    @Test
    fun aMailboxTheSiteRefusesSaysInvitesAreBlockedWithOnlyThePlayersOwnId() {
        inGame.opened()
        topic(Topic.Hello, "social on") { it["social"]?.jsonPrimitive?.content == "on" }
        mailbox.refused.value = Me
        val blocked = topic(Topic.Hello, "invites blocked") { it["social"]?.jsonPrimitive?.content == "blocked" }
        assertEquals(Me.value, blocked["supportId"]!!.jsonPrimitive.content)
        mailbox.refused.value = Nori
        assertEquals(JsonNull, topic(Topic.Hello, "another account's refusal is not this player's") { it["social"]?.jsonPrimitive?.content == "on" }["supportId"])
    }

    @Test
    fun aPublicGameIsListedWhileItHostsAndTakenDownAfter() {
        hostAndReachHosting(Outreach(Listing.Public, emptySet()))
        until("the listing") { directory.kept.lastOrNull() != null }
        val listing = checkNotNull(directory.kept.last())
        assertEquals(Code, listing.ticket.code)
        assertEquals(Fra, listing.region)

        assertEquals(CommandResult.Accepted, press(PanelCommand.Relist(Listing.InviteOnly)))
        until("the listing taken down") { directory.kept.last() == null }
        assertEquals(CommandResult.Accepted, press(PanelCommand.Relist(Listing.Public)))
        until("the listing back") { directory.kept.last() != null }

        assertEquals(CommandResult.Accepted, press(PanelCommand.Act(Action.Stop)))
        games.live.awaited("the game stopped") { it.session !is Session.Hosting }
        until("the listing taken down after the game") { directory.kept.last() == null }
    }

    @Test
    fun aDuplicateInviteShowsOnceAndJoiningItFindsTheGameWithItsOfferAccepted() {
        inGame.opened()
        until("the mailbox open") { mailbox.held.lastOrNull() == Me }
        val invite = invitation("9f")
        mailbox.arriving.tryEmit(invite)
        mailbox.arriving.tryEmit(invite)
        inbox("the invite") { it.isNotEmpty() }
        runBlocking { delay(200.milliseconds) }
        assertEquals(listOf(invite.id.value), inbox("one toast") { true }["invites"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })

        assertEquals(CommandResult.Accepted, press(PanelCommand.JoinInvite(invite.id)))
        val finding = games.live.awaited("finding the friend's game") { it.session is Session.Finding }.session as Session.Finding
        assertEquals(FriendCode, finding.code)
        assertEquals(FriendOffer, finding.acceptedBefore)
        assertEquals(Kestrel.value, join("the joining card") { true }["hostId"]?.jsonPrimitive?.content, "the friend who sent the invite")
        inbox("the toast gone everywhere") { it.isEmpty() }
        assertEquals(CommandResult.Gone, press(PanelCommand.JoinInvite(invite.id)), "taken already")
    }

    @Test
    fun joiningWhileASessionRunsIsBusyAndLeavesTheInviteShowing() {
        hostAndReachHosting(Outreach.Private)
        val invite = invitation("ab", from = Nori)
        mailbox.arriving.tryEmit(invite)
        inbox("the invite") { it.isNotEmpty() }
        assertEquals(CommandResult.Busy, press(PanelCommand.JoinInvite(invite.id)))
        assertEquals(CommandResult.Busy, press(PanelCommand.JoinServer(Listed)))
        assertEquals(1, inbox("still showing") { true }["invites"]!!.jsonArray.size)
        assertIs<Session.Hosting>(games.live.value.session)
    }

    private fun serversRead() {
        assertEquals(CommandResult.Accepted, press(PanelCommand.Servers))
        social.state.awaited("the servers read") { it.servers.games.isNotEmpty() }
    }

    private fun join(what: String, wanted: (JsonObject) -> Boolean): JsonObject =
        topic(Topic.Game, what) { (it["join"] as? JsonObject)?.let(wanted) == true }["join"]!!.jsonObject

    private val JsonObject.phase get() = this["phase"]!!.jsonPrimitive.content
    private val JsonObject.actions get() = this["actions"]!!.jsonArray.map { it.jsonPrimitive.content }
    private val JsonObject.title get() = this["words"]!!.jsonObject["title"]!!.jsonPrimitive.content

    @Test
    fun aServerJoinIsAcceptedAtOnceAndTheJoiningLayerLooksForTheGameUntilTheSiteAnswers() {
        serversRead()
        directory.answer = CompletableDeferred()
        assertEquals(CommandResult.Accepted, press(PanelCommand.JoinServer(Listed)))
        val looking = join("looking for the game") { it.phase == "finding" }
        assertEquals("Looking for the game…", looking["busy"]!!.jsonPrimitive.content)
        assertEquals(listOf("stop"), looking.actions)
        assertEquals(Session.Idle, games.live.value.session, "nothing starts before the site answers")

        directory.answer.complete(Resolved.Open(JoinTicket(FriendCode, FriendOffer)))
        assertEquals(FriendCode, (games.live.awaited("finding the listed game") { it.session is Session.Finding }.session as Session.Finding).code)
        assertEquals(JsonNull, join("the session's joining card") { true }["hostId"], "a listing names no host's id")
    }

    @Test
    fun aServerThatClosedFilledOrCouldNotBeAskedFailsInTheJoiningLayerWithItsOwnWords() {
        serversRead()
        directory.answer = CompletableDeferred(Resolved.Refused.Closed)
        assertEquals(CommandResult.Accepted, press(PanelCommand.JoinServer(Listed)))
        val closed = join("the closed game") { it.phase == "failed" }
        assertEquals("That game has closed", closed.title)
        assertEquals(listOf("dismiss"), closed.actions)
        assertEquals(CommandResult.Accepted, press(PanelCommand.Act(Action.Dismiss)))
        topic(Topic.Game, "the layer closed") { it["join"] !is JsonObject }

        directory.answer = CompletableDeferred(Resolved.Refused.Full)
        assertEquals(CommandResult.Accepted, press(PanelCommand.JoinServer(Listed)))
        val full = join("the full game") { it.phase == "failed" }
        assertEquals("That game is full", full.title)
        assertEquals(listOf("again", "dismiss"), full.actions)

        directory.answer = CompletableDeferred(Resolved.Refused.Unreachable)
        assertEquals(CommandResult.Accepted, press(PanelCommand.Act(Action.Again)))
        assertEquals("Couldn’t look up that game", join("the site unreachable") { it.phase == "failed" && it.title != full.title }.title)

        directory.answer = CompletableDeferred(Resolved.Open(JoinTicket(FriendCode, FriendOffer)))
        assertEquals(CommandResult.Accepted, press(PanelCommand.Act(Action.Again)))
        games.live.awaited("finding the listed game") { it.session is Session.Finding }
    }

    @Test
    fun cancellingWhileItLooksDropsTheSitesLateAnswer() {
        serversRead()
        directory.answer = CompletableDeferred()
        assertEquals(CommandResult.Accepted, press(PanelCommand.JoinServer(Listed)))
        join("looking for the game") { it.phase == "finding" }
        assertEquals(CommandResult.Accepted, press(PanelCommand.Act(Action.Stop)))
        topic(Topic.Game, "the layer closed") { it["join"] !is JsonObject }
        directory.answer.complete(Resolved.Open(JoinTicket(FriendCode, FriendOffer)))
        runBlocking { delay(300.milliseconds) }
        assertEquals(Session.Idle, games.live.value.session)
    }

    @Test
    fun aServerNoLongerListedIsGoneAtThePress() {
        assertEquals(CommandResult.Gone, press(PanelCommand.JoinServer(Listed)))
        assertEquals(Session.Idle, games.live.value.session)
    }

    @Test
    fun theMailboxFollowsTheGamesStreamsAndTheGamesAccount() {
        inGame.opened()
        until("the mailbox open") { mailbox.held.lastOrNull() == Me }
        assertEquals("on", topic(Topic.Hello, "social on") { it["social"]?.jsonPrimitive?.content == "on" }["social"]!!.jsonPrimitive.content)
        assertEquals(CommandResult.Accepted, press(PanelCommand.Hello(Nori)))
        until("the mailbox closed") { mailbox.held.lastOrNull() == null }
        topic(Topic.Hello, "the other account") { it["social"]?.jsonPrimitive?.content == "other-account" }
        assertEquals(CommandResult.Accepted, press(PanelCommand.Hello(Me)))
        until("the mailbox open") { mailbox.held.lastOrNull() == Me }
    }

    @Test
    fun theServersTabReadsTheDirectoryOnDemand() {
        topic(Topic.Servers, "servers idle") { it["state"]?.jsonPrimitive?.content == "idle" }
        assertEquals(CommandResult.Accepted, press(PanelCommand.Servers))
        topic(Topic.Servers, "servers read") { it["state"]?.jsonPrimitive?.content == "ready" }
    }
}
