package gg.hideouthq.social

import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.LocalPlayer
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.social.Delivery
import gg.hideouthq.core.customgames.social.GameStatus
import gg.hideouthq.core.customgames.social.Invitation
import gg.hideouthq.core.customgames.social.ListingId
import gg.hideouthq.core.customgames.social.ListingToken
import gg.hideouthq.core.customgames.social.ListingWanted
import gg.hideouthq.core.customgames.social.Resolved
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs against a local `wrangler dev` of hideout-site's worker: HIDEOUT_SITE_ROUND_TRIP is its address, such as
 * 127.0.0.1:8787, and HIDEOUT_SITE_ROUND_TRIP_MASTER the BUILD_MASTER it started with. Wrangler hands the Worker each
 * request at its route's host, so requests are signed for `hideouthq.gg` and only the connection goes local.
 */
class SiteRoundTripTest {
    private val site = System.getenv("HIDEOUT_SITE_ROUND_TRIP")
    private val master = System.getenv("HIDEOUT_SITE_ROUND_TRIP_MASTER")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun end() = scope.cancel()

    private fun routes(device: DeviceKey, build: String = "0.3.0"): SiteRoutes {
        val key = HexFormat.of().formatHex(hmac(master).doFinal("invite\n$build".encodeToByteArray()))
        val (address, port) = site.split(':')
        val local = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().let { it.newBuilder().url(it.url.newBuilder().host(address).port(port.toInt()).build()).build() })
        }.build()
        return SiteRoutes(local, SiteSigner(hmac(key), build, device), "http://hideouthq.gg")
    }

    private fun hmac(key: String) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.encodeToByteArray(), "HmacSHA256")) }

    private fun steam(): SteamId = checkNotNull(SteamId.parse("7656119" + (1_000_000_000L + SecureRandom().nextLong(8_999_999_999L)).toString()))

    @Test
    fun anInviteAndAListingGoThroughTheWorkerSignedAsTheAppSignsThem() = runBlocking {
        assumeTrue("set HIDEOUT_SITE_ROUND_TRIP and HIDEOUT_SITE_ROUND_TRIP_MASTER", site != null && master != null)
        val host = steam()
        val friend = steam()
        val hostRoutes = routes(DeviceKey.generate(SecureRandom()).first)
        val hostMailbox = SiteMailbox(hostRoutes, scope, ::println)
        val friendMailbox = SiteMailbox(routes(DeviceKey.generate(SecureRandom()).first), scope, ::println)
        val arrived = async { withTimeout(30.seconds) { friendMailbox.invitations.first() } }
        hostMailbox.hold(host)
        friendMailbox.hold(friend)

        val code = checkNotNull(InviteCode.parse("P95-MAN-26D"))
        val offer = InviteOffer.of(FakeCustomGamesFeed.BebopWars, Choices.None, LocalPlayer(host.value, null))
        val ticket = JoinTicket(code, offer)
        var delivery = Delivery.NotReached
        withTimeout(30.seconds) {
            while (delivery != Delivery.Delivered) {
                delay(250.milliseconds)
                delivery = hostMailbox.deliver(friend, host, ticket, 2)
            }
        }
        val invitation: Invitation = arrived.await()
        assertEquals(host, invitation.from)
        assertEquals(ticket, invitation.ticket)

        val stranger = SiteMailbox(routes(DeviceKey.generate(SecureRandom()).first), scope, ::println)
        assertEquals(Delivery.NotReached, stranger.deliver(friend, host, ticket, 2), "another device can't send as the host")
        assertEquals(Delivery.NotReached, SiteMailbox(routes(DeviceKey.generate(SecureRandom()).first, build = "0.3"), scope, ::println).deliver(friend, host, ticket, 2), "nor a build that isn't one")

        val directory = SiteDirectory(hostRoutes, scope)
        val token = ListingToken(HexFormat.of().formatHex(ByteArray(16).also(SecureRandom()::nextBytes)))
        val id = checkNotNull(ListingId.parse(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.value.encodeToByteArray())).take(16)))
        directory.keep(ListingWanted(token, ticket, RelayRegion(4, "fra"), 2, GameStatus.Lobby))
        withTimeout(10.seconds) { while (directory.list().orEmpty().none { it.id == id }) delay(100.milliseconds) }
        assertEquals(Resolved.Open(ticket), directory.resolve(id))
        directory.keep(null)
        withTimeout(10.seconds) { while (directory.resolve(id) != Resolved.Gone) delay(100.milliseconds) }
        hostMailbox.hold(null)
        friendMailbox.hold(null)
    }
}
