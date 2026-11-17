package gg.hideouthq.core.crosshair.community

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.library.resourceBytes
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.TestTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityCrosshairsTest {
    private val body = resourceBytes("dmm/crosshairs.json").decodeToString()
    private val file = "community-crosshairs.json"
    private val requests = mutableListOf<HttpRequestData>()

    private fun TestScope.client(answer: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = HttpClient(MockEngine) {
        engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests += request
                answer(request)
            }
        }
    }

    private fun kept(body: String, etag: String?) = mapOf(file to JsonObject(listOfNotNull(etag?.let { "etag" to JsonPrimitive(it) }, "body" to JsonPrimitive(body)).toMap()).toString())

    private suspend fun MemoryStorage.kept(): Pair<String, String?>? = read(file)?.let { Json.parseToJsonElement(it).jsonObject }?.let { it.getValue("body").jsonPrimitive.content to it["etag"]?.jsonPrimitive?.content }

    private fun MockRequestHandleScope.library() = respond(body, headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.ETag to listOf("W/\"398\"")))

    @Test
    fun theLibraryIsFetchedWithAGetAndKeptAsItCameWithItsTag() = runTest {
        val storage = MemoryStorage()
        val community = DmmCrosshairs(client { library() }, storage, backgroundScope)
        assertEquals(Loadable.Loading, community.crosshairs.value)
        community.refresh()
        runCurrent()
        assertEquals(398, assertIs<Loadable.Ready<List<CommunityCrosshair>>>(community.crosshairs.value).value.size)
        val request = requests.single()
        assertEquals(HttpMethod.Get to "https://api.deadlockmods.app/api/v2/crosshairs", request.method to request.url.toString())
        assertNull(request.headers[HttpHeaders.IfNoneMatch])
        assertEquals(body to "W/\"398\"", storage.kept())
    }

    @Test
    fun theKeptCopyShowsAtOnceAndAnUnchangedLibraryIsNotSentAgain() = runTest {
        val storage = MemoryStorage(kept(body, "W/\"398\""))
        val answered = CompletableDeferred<Unit>()
        val community = DmmCrosshairs(client { answered.await(); respond("", HttpStatusCode.NotModified) }, storage, backgroundScope)
        community.refresh()
        runCurrent()
        val shown = assertIs<Loadable.Ready<List<CommunityCrosshair>>>(community.crosshairs.value, "the copy shows while the fetch waits")
        assertEquals(398, shown.value.size)
        answered.complete(Unit)
        runCurrent()
        assertEquals("W/\"398\"", requests.single().headers[HttpHeaders.IfNoneMatch])
        assertEquals(shown, community.crosshairs.value)
        assertEquals(body to "W/\"398\"", storage.kept())
    }

    @Test
    fun aKeptCopyThatDoesNotReadIsFetchedAgainWithoutItsTag() = runTest {
        val storage = MemoryStorage(kept("<html>", "W/\"398\""))
        val community = DmmCrosshairs(client { request -> if (request.headers[HttpHeaders.IfNoneMatch] != null) respond("", HttpStatusCode.NotModified) else library() }, storage, backgroundScope)
        community.refresh()
        runCurrent()
        assertNull(requests.single().headers[HttpHeaders.IfNoneMatch])
        assertEquals(398, assertIs<Loadable.Ready<List<CommunityCrosshair>>>(community.crosshairs.value).value.size)
        assertEquals(body to "W/\"398\"", storage.kept())
    }

    @Test
    fun theLibraryIsAskedAgainOnlyAfterSixHoursUnlessTheLastAskFailed() = runTest {
        val time = TestTimeSource()
        var down = true
        val community = DmmCrosshairs(client { if (down) respond("", HttpStatusCode.ServiceUnavailable) else library() }, MemoryStorage(), backgroundScope, time)
        community.refresh()
        runCurrent()
        down = false
        community.refresh()
        runCurrent()
        assertEquals(2, requests.size, "a failed ask is retried at once")
        community.refresh()
        time += 5.hours
        community.refresh()
        runCurrent()
        assertEquals(2, requests.size, "a library checked within six hours is not asked again")
        time += 1.hours
        community.refresh()
        runCurrent()
        assertEquals(3, requests.size)
    }

    @Test
    fun aFailedFetchKeepsWhatIsShownAndWithNothingShownSaysWhy() = runTest {
        val offline = DmmCrosshairs(client { throw IOException("no network") }, MemoryStorage(), backgroundScope)
        offline.refresh()
        runCurrent()
        assertEquals(Loadable.Failed(DataError.Offline), offline.crosshairs.value)

        val down = DmmCrosshairs(client { respond("", HttpStatusCode.ServiceUnavailable) }, MemoryStorage(), backgroundScope)
        down.refresh()
        runCurrent()
        assertEquals(Loadable.Failed(DataError.Unavailable), down.crosshairs.value)

        val kept = DmmCrosshairs(client { respond("<html>", HttpStatusCode.OK) }, MemoryStorage(kept(body, etag = null)), backgroundScope)
        kept.refresh()
        runCurrent()
        assertEquals(398, assertIs<Loadable.Ready<List<CommunityCrosshair>>>(kept.crosshairs.value).value.size, "an answer that is not the library keeps the copy")
        assertEquals(setOf(HttpMethod.Get), requests.map { it.method }.toSet(), "only GET is ever sent")
    }
}
