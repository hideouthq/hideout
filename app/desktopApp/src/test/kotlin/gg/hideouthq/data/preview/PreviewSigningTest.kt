package gg.hideouthq.data.preview

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreviewSigningTest {
    private val signing = Json.parseToJsonElement(
        File(checkNotNull(System.getProperty("preview.signing")) { "Run with ./kotlin test, which sets preview.signing from module.yaml" }).readText(),
    ).jsonObject
    private val vectors = signing.getValue("vectors").jsonArray.map { it.jsonObject }
    private val built = "https://previews.example.test"
    private val key = PreviewKey("a-key-0123456789abcdef0123456789abcdef", built)

    @Test
    fun theClientSignsTheSharedVectors() = runBlocking {
        assertEquals(signing.getValue("window").jsonPrimitive.int.toLong(), PreviewKey.WindowSeconds)
        for (v in vectors) {
            val text = { name: String -> v.getValue(name).jsonPrimitive.content }
            val seen = mutableListOf<HttpRequestData>()
            val client = HttpClient(MockEngine { request -> seen += request; respond("") })
            val vectorKey = PreviewKey(text("key"), text("url"))
            assertEquals(text("host"), vectorKey.host)
            client.signPreviewRequests(vectorKey) { v.getValue("time").jsonPrimitive.long }
            client.request(text("url")) { method = HttpMethod(text("method")) }
            assertEquals(text("time"), seen.single().headers[PreviewKey.TimeHeader])
            assertEquals(text("signature"), seen.single().headers[PreviewKey.SignatureHeader], text("url"))
        }
    }

    @Test
    fun aRequestToAnyOtherHostGoesUnsigned() = runBlocking {
        val seen = mutableListOf<HttpRequestData>()
        val client = HttpClient(MockEngine { request -> seen += request; respond("") })
        client.signPreviewRequests(key) { 1_800_000_000 }
        for (url in listOf("http://127.0.0.1:8080/v1/index.json", "https://previews.example.test.proxy.example/v1/index.json", "https://previews.example.test:8443/v1/index.json")) {
            client.get(url)
        }
        client.get("https://PREVIEWS.example.test:443/v1/index.json")
        assertEquals(listOf(null, null, null), seen.take(3).map { it.headers[PreviewKey.SignatureHeader] })
        assertEquals(key.signature("GET", "previews.example.test", "/v1/index.json", 1_800_000_000), seen.last().headers[PreviewKey.SignatureHeader])
    }

    private fun host(hostTime: Long, seen: MutableList<Long>, accepted: PreviewKey = key) = MockEngine { request ->
        val time = request.headers[PreviewKey.TimeHeader]!!.toLong()
        seen += time
        val date = headersOf(HttpHeaders.Date, DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(hostTime).atOffset(ZoneOffset.UTC)))
        val signed = request.headers[PreviewKey.SignatureHeader] == accepted.signature(request.method.value, request.url.host, request.url.encodedPath, time)
        if (signed && abs(time - hostTime) <= PreviewKey.WindowSeconds) respond("served", HttpStatusCode.OK, date) else respond("{}", HttpStatusCode.NotFound, date)
    }

    @Test
    fun aClockAnHourOffTakesTheHostsTimeOnceItsRequestIsServed() = runBlocking {
        val seen = mutableListOf<Long>()
        val client = HttpClient(host(1_800_003_600, seen))
        client.signPreviewRequests(key) { 1_800_000_000 }
        assertEquals("served", client.get("$built/v1/index.json").bodyAsText())
        assertEquals("served", client.get("$built/v1/collections.json").bodyAsText())
        assertEquals(listOf(1_800_000_000L, 1_800_003_600L, 1_800_003_600L), seen)
    }

    @Test
    fun aDateThatDoesNotGetTheRequestServedIsNotKept() = runBlocking {
        val seen = mutableListOf<Long>()
        val client = HttpClient(host(1_800_003_600, seen, accepted = PreviewKey("another-key-0123456789abcdef0123456789", built)))
        client.signPreviewRequests(key) { 1_800_000_000 }
        repeat(2) { assertEquals(HttpStatusCode.NotFound, client.get("$built/v1/index.json").status) }
        assertEquals(listOf(1_800_000_000L, 1_800_003_600L, 1_800_000_000L, 1_800_003_600L), seen)
    }

    @Test
    fun aRefusalFromAHostWhoseClockAgreesIsNotAskedAgain() = runBlocking {
        val seen = mutableListOf<Long>()
        val client = HttpClient(host(1_800_000_100, seen, accepted = PreviewKey("another-key-0123456789abcdef0123456789", built)))
        client.signPreviewRequests(key) { 1_800_000_000 }
        assertEquals(HttpStatusCode.NotFound, client.get("$built/v1/missing.json").status)
        assertEquals(listOf(1_800_000_000L), seen)
    }

    @Test
    fun theTestStoreRefusesAnUnsignedCache() = runBlocking {
        ServedStore().use { store ->
            val unsigned = PreviewCache(store.baseUrl, Files.createTempDirectory("unsigned"), "Hideout tests", key = null)
            assertFailsWith<PreviewFileException> { unsigned.indexText(null) }
            assertEquals(Files.readString(store.root.resolve("index.json")), store.cache(Files.createTempDirectory("signed")).indexText(null))
        }
    }

    @Test
    fun anOverriddenHostGetsNoSignatureFromTheBuiltKey() = runBlocking {
        ScriptedHost { exchange, _ -> exchange.send("{}".encodeToByteArray()) }.use { other ->
            val cache = PreviewCache(other.baseUrl, Files.createTempDirectory("override"), "Hideout tests", key)
            assertFailsWith<PreviewFileException> { cache.collectionsText() }
            assertTrue(other.requests.isNotEmpty())
            assertTrue(other.requests.all { PreviewKey.SignatureHeader.lowercase() !in it.headers && PreviewKey.TimeHeader.lowercase() !in it.headers })
        }
    }

    @Test
    fun aRedirectIsNotFollowedSoTheSignatureStaysWithTheHost() = runBlocking {
        ScriptedHost { exchange, _ -> exchange.send("{}".encodeToByteArray()) }.use { elsewhere ->
            ScriptedHost { exchange, _ ->
                exchange.output.write("HTTP/1.1 302 Found\r\nLocation: ${elsewhere.baseUrl}/v1/collections.json\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                exchange.close()
            }.use { host ->
                assertFailsWith<Exception> { host.cache(Files.createTempDirectory("redirect")).collectionsText() }
                assertEquals(1, host.requests.size)
                assertTrue(elsewhere.requests.isEmpty())
            }
        }
    }
}
