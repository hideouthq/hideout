package gg.hideouthq.core.data.deadlockstats

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReplayMetadataDownloaderTest {
    private val ref = ReplayMetadataRef(matchId = 1, clusterId = 1, metadataSalt = 2, url = "http://replay1.valve.net/1422450/1_2.meta.bz2")

    @Test
    fun followsAValidatedRedirectToAnotherReplayHost() = runTest {
        val body = byteArrayOf(9, 9)
        var calls = 0
        val downloader = ReplayMetadataDownloader(
            mockClient {
                calls++
                if (calls == 1) {
                    respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://replay2.valve.net/1422450/1_2.meta.bz2"))
                } else {
                    respond(body)
                }
            },
        )

        assertContentEquals(body, downloader.download(ref))
        assertEquals(2, calls)
    }

    @Test
    fun refusesARedirectToAHostThatIsNotTheReplayCdn() = runTest {
        val downloader = ReplayMetadataDownloader(
            mockClient { respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://attacker.example/steal")) },
        )

        val failure = assertFailsWith<ReplayDownloadException> { downloader.download(ref) }
        assertTrue(failure.message!!.contains("not an allowed replay-CDN URL"))
    }

    @Test
    fun givesUpAfterTooManyRedirects() = runTest {
        val downloader = ReplayMetadataDownloader(
            mockClient { respond(ByteArray(0), HttpStatusCode.Found, headersOf(HttpHeaders.Location, "http://replay1.valve.net/1422450/1_2.meta.bz2")) },
        )

        val failure = assertFailsWith<ReplayDownloadException> { downloader.download(ref) }
        assertTrue(failure.message!!.contains("redirects"))
    }

    @Test
    fun aServerErrorFailsWithoutRetrying() = runTest {
        var calls = 0
        val downloader = ReplayMetadataDownloader(
            mockClient {
                calls++
                respondError(HttpStatusCode.InternalServerError)
            },
        )

        val failure = assertFailsWith<ReplayDownloadException> { downloader.download(ref) }
        assertEquals(1, calls)
        assertEquals(500, failure.httpStatus)
    }

    @Test
    fun aResponseOverTheSizeLimitIsRejected() = runTest {
        val body = ByteArray(1_000)
        val downloader = ReplayMetadataDownloader(mockClient { respond(body) }, maxBytes = 10)

        val failure = assertFailsWith<ReplayDownloadException> { downloader.download(ref) }
        assertTrue(failure.message!!.contains("limit"))
    }

    @Test
    fun aTransportFailureIsWrapped() = runTest {
        val downloader = ReplayMetadataDownloader(mockClient { throw IOException("no route to host") })

        assertFailsWith<ReplayDownloadException> { downloader.download(ref) }
    }

    private fun mockClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
        HttpClient(MockEngine(handler)) { expectSuccess = false; followRedirects = false }
}
