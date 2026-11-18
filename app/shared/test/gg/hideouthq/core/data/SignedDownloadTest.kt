package gg.hideouthq.core.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignedDownloadTest {
    private val url = "https://example.test/feed.json"
    private val signature = "c2lnbmVk"
    private val writers = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun stopWriters() = writers.cancel()

    private fun download(feed: () -> ByteReadChannel, sig: () -> ByteReadChannel = { ByteReadChannel(signature) }, length: Long? = null) = SignedDownload(
        HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val headers = length?.let { headersOf(HttpHeaders.ContentLength, "$it") } ?: headersOf()
                    if (request.url.toString() == url) respond(feed(), HttpStatusCode.OK, headers) else respond(sig())
                }
            }
        },
        "the feed",
    ) { _, signed -> signed == signature }

    private fun endless(written: AtomicLong): ByteReadChannel = writers.writer {
        val chunk = ByteArray(64 * 1024) { '{'.code.toByte() }
        while (true) {
            channel.writeFully(chunk)
            written.addAndGet(chunk.size.toLong())
        }
    }.channel

    @Test
    fun aFileUpToTheCapIsRead() = runTest {
        val answer = download({ ByteReadChannel(ByteArray(SignedDownload.MaxFileBytes.toInt())) }).fetch(url)
        assertEquals(SignedDownload.MaxFileBytes.toInt(), assertIs<SignedAnswer.Genuine>(answer).bytes.size)
    }

    @Test
    fun aFileOneByteOverTheCapIsUnreachable() = runTest {
        assertIs<SignedAnswer.Unreachable>(download({ ByteReadChannel(ByteArray(SignedDownload.MaxFileBytes.toInt() + 1)) }).fetch(url))
    }

    @Test
    fun anEndlessFileIsAbandonedNearTheCap() = runTest {
        val written = AtomicLong()
        assertIs<SignedAnswer.Unreachable>(download({ endless(written) }).fetch(url))
        assertTrue(written.get() < 8 * SignedDownload.MaxFileBytes, "sent ${written.get()} bytes")
    }

    @Test
    fun aFileThatSaysItIsTooLargeIsNotRead() = runTest {
        val written = AtomicLong()
        assertIs<SignedAnswer.Unreachable>(download({ endless(written) }, length = SignedDownload.MaxFileBytes + 1).fetch(url))
        assertTrue(written.get() < 8 * SignedDownload.MaxFileBytes, "sent ${written.get()} bytes")
    }

    @Test
    fun aSignatureOverItsCapIsUnreachableEvenWhenItWouldTrimToAGoodOne() = runTest {
        val padded = signature + " ".repeat(SignedDownload.MaxSignatureBytes.toInt())
        assertIs<SignedAnswer.Unreachable>(download({ ByteReadChannel("{}") }, sig = { ByteReadChannel(padded) }).fetch(url))
        assertIs<SignedAnswer.Genuine>(download({ ByteReadChannel("{}") }, sig = { ByteReadChannel("$signature\n") }).fetch(url))
    }
}
