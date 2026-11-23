package gg.hideouthq.ingame

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import gg.hideouthq.core.customgames.InGamePort
import gg.hideouthq.core.customgames.MenusPack
import gg.hideouthq.feature.ingame.CommandResult
import gg.hideouthq.feature.ingame.Frame
import gg.hideouthq.feature.ingame.InGame
import gg.hideouthq.feature.ingame.InGameProtocol
import gg.hideouthq.feature.ingame.InGameWire
import gg.hideouthq.feature.ingame.Topic
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Every route refuses a Host that isn't loopback, which keeps DNS rebinding out. The stream admits the pack's Origin
 * and the game's User-Agent, as Modlock's relay does. A Panorama Image panel sends no Origin, so `/c` takes the pack's
 * token instead, and a repeated sequence number gets its first answer again, so a retried image can't host twice.
 */
class InGameEndpoint(
    private val inGame: InGame,
    private val pack: suspend () -> MenusPack?,
    private val log: (String) -> Unit,
    private val keepalive: Duration = Keepalive,
) : AutoCloseable {
    private var server: HttpServer? = null
    private val seen = Seen()

    val port: Int? get() = server?.address?.port

    fun start(port: Int = InGamePort): Boolean {
        val bound = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        } catch (e: IOException) {
            log("In-game: could not listen on port $port: ${e::class.simpleName}")
            return false
        }
        // An open stream holds its thread, and a command must not wait behind it.
        bound.executor = Executors.newVirtualThreadPerTaskExecutor()
        bound.createContext("/") { exchange ->
            try {
                handle(exchange)
            } catch (e: IOException) {
            } finally {
                exchange.close()
            }
        }
        bound.start()
        server = bound
        return true
    }

    override fun close() {
        server?.stop(0)
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        when {
            !loopback(exchange.requestHeaders.getFirst("Host")) -> exchange.sendResponseHeaders(403, -1)
            exchange.requestURI.path == "/panorama/stream" -> stream(exchange)
            exchange.requestURI.path == "/c" && exchange.requestMethod == "GET" -> command(exchange)
            else -> exchange.sendResponseHeaders(404, -1)
        }
    }

    private fun stream(exchange: HttpExchange) {
        val site = runBlocking { pack() }?.let { originOf(it.stamp.page) }
        val agent = exchange.requestHeaders.getFirst("User-Agent").orEmpty()
        if (site == null || exchange.requestHeaders.getFirst("Origin") != site || RelayAgent !in agent) return exchange.sendResponseHeaders(403, -1)
        val headers = exchange.responseHeaders
        headers["Access-Control-Allow-Origin"] = site
        headers["Access-Control-Allow-Private-Network"] = "true"
        headers["Vary"] = "Origin"
        when (exchange.requestMethod) {
            "OPTIONS" -> {
                headers["Access-Control-Allow-Methods"] = "GET"
                exchange.sendResponseHeaders(204, -1)
            }
            "GET" -> {
                headers["Cache-Control"] = "no-store"
                headers["Content-Type"] = "text/plain; charset=utf-8"
                exchange.sendResponseHeaders(200, 0)
                inGame.opened()
                try {
                    runBlocking { follow(exchange.responseBody) }
                } finally {
                    inGame.closed()
                }
            }
            else -> exchange.sendResponseHeaders(405, -1)
        }
    }

    private suspend fun follow(body: OutputStream) {
        var sent = emptyMap<Topic, String>()
        var sequence = 0L
        fun write(frame: String) {
            body.write(InGameWire.line(++sequence, frame))
            body.flush()
        }
        while (true) {
            val next = withTimeoutOrNull(keepalive) { inGame.topics.first { it.isNotEmpty() && it != sent } }
            if (next == null) {
                write(InGameWire.encode(Frame.Alive))
                continue
            }
            Topic.entries.forEach { topic -> next[topic]?.takeIf { it != sent[topic] }?.let(::write) }
            sent = next
            delay(FrameGap)
        }
    }

    private fun command(exchange: HttpExchange) {
        val query = queryOf(exchange.requestURI.rawQuery)
        val token = runBlocking { pack() }?.token?.hex
        val given = query["t"]
        if (token == null || given == null || !MessageDigest.isEqual(token.encodeToByteArray(), given.encodeToByteArray())) return exchange.sendResponseHeaders(404, -1)
        val boot = query["b"]?.takeIf(BootShape::matches)
        val sequence = query["n"]?.toLongOrNull()?.takeIf { it >= 0 }
        val result = when {
            query["p"] != InGameProtocol.toString() -> CommandResult.Outdated
            boot == null || sequence == null -> CommandResult.Invalid
            else -> seen.answer(boot, sequence) { query["c"]?.let(InGameWire::parseCommand)?.let { runBlocking { inGame.run(it) } } ?: CommandResult.Invalid }
        }
        val png = pixelRow(result.width)
        exchange.responseHeaders["Content-Type"] = "image/png"
        exchange.responseHeaders["Cache-Control"] = "no-store"
        exchange.sendResponseHeaders(200, png.size.toLong())
        exchange.responseBody.write(png)
    }

    private companion object {
        /** Modlock's `session.RelayAgent`: the game's HTML panels say it, and an ordinary browser doesn't. */
        const val RelayAgent = "Source2 HTML"
        val Keepalive = 10.seconds
        val FrameGap = 50.milliseconds
        val BootShape = Regex("[A-Za-z0-9]{1,32}")
    }
}

/** Mirrors Modlock's `session.Loopback`. */
internal fun loopback(host: String?): Boolean {
    if (host == null || host.substringAfterLast(':', missingDelimiterValue = "").toIntOrNull() == null) return false
    val name = host.substringBeforeLast(':')
    return name == "127.0.0.1" || name.equals("localhost", ignoreCase = true)
}

private fun originOf(page: String): String? = try {
    URI(page).let { uri -> uri.rawAuthority?.let { "${uri.scheme}://$it" } }
} catch (e: URISyntaxException) {
    null
}

private fun queryOf(raw: String?): Map<String, String> = try {
    raw.orEmpty().split('&').filter { '=' in it }.map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        .distinctBy { it.first }.toMap()
} catch (e: IllegalArgumentException) {
    emptyMap()
}

/** Holds each boot's newest press while it runs too, so a retry waits for that answer and other boots don't wait at all. */
internal class Seen(private val boots: Int = 16) {
    private val last = LinkedHashMap<String, Pair<Long, CompletableFuture<CommandResult>>>()

    fun answer(boot: String, sequence: Long, run: () -> CommandResult): CommandResult {
        val pending = CompletableFuture<CommandResult>()
        val answer = synchronized(this) {
            last[boot]?.let { (newest, answer) -> if (sequence == newest) return@synchronized answer else if (sequence < newest) return CommandResult.Invalid }
            last.remove(boot)
            last[boot] = sequence to pending
            while (last.size > boots) last.remove(last.keys.first())
            pending
        }
        if (answer === pending) {
            try {
                pending.complete(run())
            } catch (e: Throwable) {
                synchronized(this) { if (last[boot]?.second === pending) last.remove(boot) }
                pending.completeExceptionally(e)
            }
        }
        return try {
            answer.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}

internal fun pixelRow(width: Int): ByteArray {
    val pixels = ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(ByteArray(1 + width * 4)) } }.toByteArray()
    val header = ByteArrayOutputStream().also { out -> DataOutputStream(out).run { writeInt(width); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) } }.toByteArray()
    val png = ByteArrayOutputStream()
    png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0d, 0x0a, 0x1a, 0x0a))
    DataOutputStream(png).run {
        for ((type, data) in listOf("IHDR" to header, "IDAT" to pixels, "IEND" to ByteArray(0))) {
            val name = type.encodeToByteArray()
            writeInt(data.size)
            write(name)
            write(data)
            writeInt(CRC32().apply { update(name); update(data) }.value.toInt())
        }
    }
    return png.toByteArray()
}
