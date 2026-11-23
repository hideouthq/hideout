package gg.hideouthq.social

import gg.hideouthq.core.customgames.InviteCode
import gg.hideouthq.core.customgames.InviteOffer
import gg.hideouthq.core.customgames.JoinTicket
import gg.hideouthq.core.customgames.RelayRegion
import gg.hideouthq.core.customgames.SteamId
import gg.hideouthq.core.customgames.social.Delivery
import gg.hideouthq.core.customgames.social.Directory
import gg.hideouthq.core.customgames.social.GameStatus
import gg.hideouthq.core.customgames.social.Invitation
import gg.hideouthq.core.customgames.social.InvitationId
import gg.hideouthq.core.customgames.social.ListingId
import gg.hideouthq.core.customgames.social.ListingToken
import gg.hideouthq.core.customgames.social.ListingWanted
import gg.hideouthq.core.customgames.social.Mailbox
import gg.hideouthq.core.customgames.social.PublicGame
import gg.hideouthq.core.customgames.social.Resolved
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.customgames.social.RelayLatency
import gg.hideouthq.core.customgames.social.SocialNetwork
import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.BuildSecrets
import gg.hideouthq.data.desktopOkHttp
import gg.hideouthq.data.signedMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SiteSigner(private val mac: Mac, private val build: String, private val device: DeviceKey, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val offset = AtomicLong()

    fun headers(method: String, url: HttpUrl, body: ByteArray): Headers {
        val time = now() + offset.get()
        val message = signedMessage(method, hostOf(url), url.encodedPath, time, build, body)
        return Headers.Builder()
            .add(TimeHeader, time.toString())
            .add(BuildHeader, build)
            .add(SignatureHeader, HexFormat.of().formatHex(synchronized(mac) { mac.doFinal(message) }))
            .add(DeviceHeader, device.header)
            .add(DeviceSignatureHeader, device.sign(message))
            .build()
    }

    /** The site refuses a time outside its window, so a clock that far off takes the site's time from its `Date`. */
    fun heard(response: Response): Boolean {
        val served = response.header("Date")?.let(::epochSeconds) ?: return false
        val time = now() + offset.get()
        if (abs(served - time) <= WindowSeconds) return false
        offset.set(served - now())
        return true
    }

    companion object {
        const val TimeHeader = "X-Hideout-Time"
        const val BuildHeader = "X-Hideout-Build"
        const val SignatureHeader = "X-Hideout-Sig"
        const val DeviceHeader = "X-Hideout-Device"
        const val DeviceSignatureHeader = "X-Hideout-Device-Sig"
        private const val WindowSeconds = 240L

        fun hostOf(url: HttpUrl): String = url.host.lowercase() + if (url.port == HttpUrl.defaultPort(url.scheme)) "" else ":${url.port}"

        private fun epochSeconds(date: String): Long? = runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrNull()
    }
}

internal class SiteRoutes(private val client: OkHttpClient, private val signer: SiteSigner, site: String) {
    private val base = site.trimEnd('/').toHttpUrl()

    fun signed(method: String, path: String, body: ByteArray = ByteArray(0), extra: Headers = Headers.Builder().build()): Request {
        val url = base.newBuilder().encodedPath(path).build()
        val content = if (method == "GET" || method == "DELETE" && body.isEmpty()) null else body.toRequestBody(JsonType)
        return Request.Builder().url(url).headers(extra.newBuilder().addAll(signer.headers(method, url, body)).build()).method(method, content).build()
    }

    /** A [limit] covers the whole exchange, a retry at the site's time included. */
    suspend fun call(method: String, path: String, body: ByteArray = ByteArray(0), limit: Duration? = null): Pair<Int, String>? = runInterruptible(Dispatchers.IO) {
        val deadline = limit?.let { System.nanoTime() + it.inWholeNanoseconds }
        fun send() = client.newCall(signed(method, path, body)).apply { deadline?.let { timeout().deadlineNanoTime(it) } }.execute()
        try {
            var skewed = false
            val first = send().use { response ->
                skewed = signer.heard(response)
                response.code to response.body.string()
            }
            if (first.first == NotFound && skewed) send().use { it.code to it.body.string() } else first
        } catch (e: IOException) {
            null
        }
    }

    fun webSocket(path: String, listener: WebSocketListener): WebSocket = client.newWebSocket(signed("GET", path), listener)

    fun heard(response: Response) = signer.heard(response)

    private companion object {
        const val NotFound = 404
        val JsonType = "application/json".toMediaType()
    }
}

/**
 * The site answers `ping` with `pong` without waking its object, and drops a socket silent for 75 s, as [Silent] does
 * here. A [Replaced] close means another app took the mailbox, so this waits the longest, and the two never take turns.
 */
internal class SiteMailbox(private val routes: SiteRoutes, private val scope: CoroutineScope, private val log: (String) -> Unit, private val clock: Clock = Clock.System) : Mailbox {
    private val incoming = MutableSharedFlow<Invitation>(extraBufferCapacity = 64)
    override val invitations: Flow<Invitation> = incoming.asSharedFlow()

    private var held: SteamId? = null
    private var connection: Job? = null

    @Synchronized
    override fun hold(owner: SteamId?) {
        if (owner == held) return
        held = owner
        connection?.cancel()
        connection = owner?.let { scope.launch { follow(it) } }
    }

    private suspend fun follow(owner: SteamId) {
        var backoff = FirstRetry
        while (true) {
            val ended = session(owner)
            backoff = when (ended) {
                Ended.AfterOpening -> FirstRetry
                Ended.Replaced -> LastRetry
                Ended.Refused -> (backoff * 2).coerceAtMost(LastRetry)
            }
            delay(backoff)
        }
    }

    private enum class Ended { AfterOpening, Replaced, Refused }

    private suspend fun session(owner: SteamId): Ended {
        val ended = CompletableDeferred<Ended>()
        val heard = AtomicReference(clock.now())
        val opened = AtomicBoolean()
        val socket = routes.webSocket("/api/mailbox/${owner.value}", object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                heard.set(clock.now())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                heard.set(clock.now())
                parseInvite(text, clock.now())?.let(incoming::tryEmit)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                ended.complete(if (code == Replaced) Ended.Replaced else Ended.AfterOpening)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(if (code == Replaced) Ended.Replaced else Ended.AfterOpening)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response != null) {
                    routes.heard(response)
                    log("Invites: the mailbox answered ${response.code}")
                }
                ended.complete(if (opened.get()) Ended.AfterOpening else Ended.Refused)
            }
        })
        try {
            while (true) {
                withTimeoutOrNull(PingEvery) { ended.await() }?.let { return it }
                if (clock.now() - heard.get() > Silent) return Ended.AfterOpening
                if (opened.get()) socket.send("ping")
            }
        } finally {
            socket.cancel()
        }
    }

    override suspend fun deliver(to: SteamId, from: SteamId, ticket: JoinTicket, players: Int): Delivery {
        val body = buildJsonObject {
            put("from", from.value)
            put("code", ticket.code.value)
            put("offer", ticket.offer.encoded)
            put("players", players)
        }.toString().encodeToByteArray()
        val (status, text) = routes.call("POST", "/api/mailbox/${to.value}/invites", body) ?: return Delivery.NotReached
        val delivered = status == Ok && (objectOf(text)?.get("delivered") as? JsonPrimitive)?.content == "true"
        return if (delivered) Delivery.Delivered else Delivery.NotReached
    }

    private companion object {
        const val Replaced = 4000
        const val Ok = 200
        val PingEvery = 30.seconds
        val Silent = 75.seconds
        val FirstRetry = 1.seconds
        val LastRetry = 60.seconds
    }
}

/** The site expires a listing after 90 s, so a kept one is put again every [refresh] and lapses once this app stops. */
internal class SiteDirectory(private val routes: SiteRoutes, scope: CoroutineScope, private val refresh: Duration = 30.seconds) : Directory {
    private val wanted = MutableStateFlow<ListingWanted?>(null)
    private val listed = AtomicReference<ListingToken?>(null)

    init {
        scope.launch {
            wanted.collectLatest { next ->
                listed.get()?.takeIf { it != next?.token }?.let { old ->
                    routes.call("DELETE", "/api/games/${old.value}")
                    listed.compareAndSet(old, null)
                }
                while (next != null) {
                    val answered = routes.call("PUT", "/api/games/${next.token.value}", listingBody(next))?.first
                    if (answered == NoContent) listed.set(next.token)
                    delay(refresh)
                }
            }
        }
    }

    override fun keep(wanted: ListingWanted?) {
        this.wanted.value = wanted
    }

    suspend fun close() {
        wanted.value = null
        listed.getAndSet(null)?.let { routes.call("DELETE", "/api/games/${it.value}") }
    }

    override suspend fun list(): List<PublicGame>? {
        val (status, text) = routes.call("GET", "/api/games") ?: return null
        if (status != Ok) return null
        val rows = objectOf(text)?.get("games") as? JsonArray ?: return null
        return rows.mapNotNull { (it as? JsonObject)?.let(::publicGame) }
    }

    override suspend fun resolve(id: ListingId): Resolved {
        val (status, text) = routes.call("GET", "/api/games/${id.value}", limit = ResolveLimit) ?: return Resolved.Gone
        val row = objectOf(text)?.takeIf { status == Ok } ?: return Resolved.Gone
        val code = row.text("code")?.let(InviteCode::parse) ?: return Resolved.Gone
        val offer = row.text("offer")?.let(InviteOffer::decode) ?: return Resolved.Gone
        return Resolved.Open(JoinTicket(code, offer))
    }

    private fun listingBody(wanted: ListingWanted): ByteArray = buildJsonObject {
        put("code", wanted.ticket.code.value)
        put("offer", wanted.ticket.offer.encoded)
        putJsonObject("region") {
            put("id", wanted.region.id)
            put("code", wanted.region.code)
        }
        put("players", wanted.players)
        put("status", wanted.status.name.lowercase())
        putJsonObject("host") { put("kind", "player") }
    }.toString().encodeToByteArray()

    private companion object {
        const val Ok = 200
        const val NoContent = 204

        /** A join from the game waits on this, and the game's panel gives up on a press after 8 s. */
        val ResolveLimit = 5.seconds
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun objectOf(text: String): JsonObject? = try {
    json.parseToJsonElement(text) as? JsonObject
} catch (e: IllegalArgumentException) {
    null
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

/** The site sends an invite's remaining lifetime, not its clock's deadline, so this computer's clock can't make one look expired. */
internal fun parseInvite(text: String, now: Instant): Invitation? {
    val message = objectOf(text)?.takeIf { it.text("type") == "invite" } ?: return null
    val invite = message["invite"] as? JsonObject ?: return null
    val id = invite.text("id")?.let(InvitationId::parse) ?: return null
    val from = invite.text("from")?.let(SteamId::parse) ?: return null
    val code = invite.text("code")?.let(InviteCode::parse) ?: return null
    val offer = invite.text("offer")?.let(InviteOffer::decode) ?: return null
    val players = invite.int("players")?.takeIf { it in 1..offer.capacity.coerceAtLeast(1) } ?: return null
    val ttl = (invite["ttl"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }?.milliseconds ?: return null
    return Invitation(id, from, JoinTicket(code, offer), players, now + ttl)
}

private fun publicGame(row: JsonObject): PublicGame? {
    val id = row.text("id")?.let(ListingId::parse) ?: return null
    val offer = row.text("offer")?.let(InviteOffer::decode) ?: return null
    val region = row["region"] as? JsonObject ?: return null
    val relay = RelayRegion(region.int("id") ?: return null, region.text("code") ?: return null)
    val players = row.int("players")?.takeIf { it >= 1 } ?: return null
    val status = GameStatus.entries.firstOrNull { it.name.lowercase() == row.text("status") } ?: return null
    if ((row["host"] as? JsonObject)?.text("kind") != "player") return null
    return PublicGame(id, offer, relay, players, status)
}

internal class SiteSocial private constructor(val network: SocialNetwork, private val directory: SiteDirectory) {
    fun close() {
        runBlocking { withTimeoutOrNull(CloseLimit) { directory.close() } }
    }

    companion object {
        private val CloseLimit = 3.seconds

        fun forThisBuild(site: SiteOrigin?, build: String, helper: Path?, log: (String) -> Unit): SiteSocial? {
            val mac = BuildSecrets.mac("invite-key") ?: return null
            if (site == null) return null
            val device = try {
                DeviceKey.load(AppDirs.config().toPath().resolve("device.key"))
            } catch (e: IOException) {
                log("Invites: could not keep this computer's key: ${e::class.simpleName}")
                return null
            }
            val routes = SiteRoutes(desktopOkHttp(), SiteSigner(mac, build, device), site.value)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val directory = SiteDirectory(routes, scope)
            val latency = helper?.let(::HelperRelayLatency) ?: RelayLatency { null }
            return SiteSocial(SocialNetwork(SiteMailbox(routes, scope, log), directory, latency), directory)
        }
    }
}
