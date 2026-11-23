package gg.hideouthq.data.preview

import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.PreviewJson
import gg.hideouthq.preview.decodeBundleManifest
import gg.hideouthq.preview.decodePreviewIndex
import gg.hideouthq.preview.sha256Hex
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.math.abs

internal const val TestBuild = "0.3.0"

internal fun previewKey(secret: String, baseUrl: String, build: String = TestBuild) =
    PreviewKey(Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.encodeToByteArray(), "HmacSHA256")) }, baseUrl, build)

internal fun testPreviewKey(baseUrl: String) = previewKey("test-preview-key-0123456789abcdef", baseUrl)

internal fun signedForTests(method: String, target: String, header: (String) -> String?): Boolean {
    val time = header(PreviewKey.TimeHeader)?.toLongOrNull() ?: return false
    val host = header(HttpHeaders.Host)?.lowercase() ?: return false
    return abs(time - System.currentTimeMillis() / 1000) <= PreviewKey.WindowSeconds && header(PreviewKey.BuildHeader) == TestBuild &&
        header(PreviewKey.SignatureHeader) == testPreviewKey("http://$host").signature(method, host, target, time)
}

internal class SeenRequest(val path: String, val headers: Map<String, String> = emptyMap())

internal class Exchange(private val socket: Socket, val request: SeenRequest) {
    val output: OutputStream get() = socket.getOutputStream()

    fun head(status: Int, length: Int?, etag: String? = null, encoding: String? = null) {
        val reason = mapOf(200 to "OK", 304 to "Not Modified").getOrDefault(status, "Not Found")
        val lines = listOfNotNull("HTTP/1.1 $status $reason", length?.let { "Content-Length: $it" }, "Connection: close", etag?.let { "ETag: $it" }, encoding?.let { "Content-Encoding: $it" })
        output.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    fun close() = socket.close()
}

internal class ScriptedHost(private val answer: (exchange: Exchange, attempt: Int) -> Unit) : AutoCloseable {
    val requests = CopyOnWriteArrayList<SeenRequest>()
    private val attempts = AtomicInteger()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
    val baseUrl = "http://127.0.0.1:${server.localPort}"

    init {
        pool.execute {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            val head = readHead(socket.getInputStream()) ?: return
            val lines = head.split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val (method, target) = lines.first().split(' ')
            val request = SeenRequest(target, headers)
            requests += request
            val exchange = Exchange(socket, request)
            if (!signedForTests(method, target) { headers[it.lowercase()] }) return exchange.missing()
            runCatching { answer(exchange, attempts.incrementAndGet()) }
        }
    }

    private fun readHead(input: InputStream): String? {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return null
            head.append(byte.toChar())
        }
        return head.removeSuffix("\r\n\r\n").toString()
    }

    fun cache(directory: Path) = PreviewCache(baseUrl, directory, "Hideout tests", testPreviewKey(baseUrl))

    override fun close() {
        server.close()
        pool.shutdownNow()
    }
}

internal fun Exchange.send(body: ByteArray, etag: String? = null, stopAfter: Int = body.size, contentLength: Int? = body.size, encoding: String? = null) {
    head(200, contentLength, etag, encoding)
    output.write(body, 0, stopAfter)
    output.flush()
    close()
}

internal fun Exchange.missing() {
    head(404, 0)
    close()
}

internal fun serveStore(root: Path): EmbeddedServer<*, *> =
    embeddedServer(Netty, port = 0, host = "127.0.0.1") {
        intercept(ApplicationCallPipeline.Plugins) {
            if (!signedForTests(call.request.httpMethod.value, call.request.uri) { call.request.header(it) }) {
                call.respond(HttpStatusCode.NotFound)
                finish()
            }
        }
        routing { staticFiles("/v1", root.toFile()) }
    }.start(wait = false)

internal val EmbeddedServer<*, *>.baseUrl: String get() = "http://127.0.0.1:${runBlocking { engine.resolvedConnectors().single().port }}"

@OptIn(ExperimentalPathApi::class)
internal class ServedStore : AutoCloseable {
    val root: Path = Files.createTempDirectory("served-store").resolve("store")
    init {
        Path.of(checkNotNull(System.getProperty("preview.fixture")) { "Run with ./kotlin test, which sets preview.fixture from module.yaml" })
            .copyToRecursively(root, followLinks = false, overwrite = false)
    }
    private val server = serveStore(root)
    val baseUrl = server.baseUrl

    fun cache(directory: Path) = PreviewCache(baseUrl, directory, "Hideout tests", testPreviewKey(baseUrl))

    fun index(): PreviewIndex = decodePreviewIndex(Files.readString(root.resolve("index.json")))

    fun entry(): IndexEntry = index().entries.single()

    fun manifest(): BundleManifest = decodeBundleManifest(Files.readString(bundle().resolve("manifest.json")))

    fun bundle(): Path = root.resolve("bundles").resolve(entry().bundleId)

    fun add(path: String, bytes: ByteArray, type: String = "image/png"): PreviewAsset {
        val asset = PreviewAsset(path, bytes.size.toLong(), sha256Hex(bytes), type)
        republish(mapOf(path to bytes)) { it.copy(assets = it.assets.filter { existing -> existing.path != path } + asset) }
        return asset
    }

    fun republish(files: Map<String, ByteArray>, change: (BundleManifest) -> BundleManifest) {
        files.forEach { (path, bytes) ->
            Files.createDirectories(bundle().resolve(path).parent)
            Files.write(bundle().resolve(path), bytes)
        }
        val text = PreviewJson.encodeToString(BundleManifest.serializer(), change(manifest())).encodeToByteArray()
        val entry = entry().copy(manifestSha256 = sha256Hex(text))
        Files.write(bundle().resolve("manifest.json"), text)
        Files.writeString(root.resolve("index.json"), PreviewJson.encodeToString(PreviewIndex.serializer(), index().copy(generation = index().generation + 1, entries = listOf(entry))))
    }

    override fun close() = server.stop(0, 0)
}
