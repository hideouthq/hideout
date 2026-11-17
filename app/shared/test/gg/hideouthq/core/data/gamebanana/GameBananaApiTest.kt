package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.network.RemoteException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GameBananaApiTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    @Test
    fun aListingIsFetchedPageByPageFromItsTotal() = runTest {
        val pagesAsked = MutableStateFlow<List<Int>>(emptyList())
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/Multi")) return@MockEngine respond("[]", headers = json)
            val page = request.url.parameters["_nPage"]!!.toInt()
            pagesAsked.update { it + page }
            assertEquals("20948", request.url.parameters["_aFilters[Generic_Game]"])
            assertEquals("Generic_Oldest", request.url.parameters["_sSort"])
            val ids = when (page) {
                1 -> 1..50
                2 -> 51..100
                else -> 100..119
            }
            respond(indexPage(total = 120, ids), headers = json)
        }
        val heard = MutableStateFlow(0)
        val crawl = GameBananaApi(HttpClient(engine)).submissions(SubmissionKind.Mod) { listed -> heard.update { it + listed.page.submissions.size } }

        assertEquals(listOf(1, 2, 3), pagesAsked.value.sorted())
        assertEquals((1L..119L).toList(), crawl.submissions.map { it.key.id }, "in listing order, each once")
        assertEquals(120, heard.value, "every page is heard as it lands")
    }

    @Test
    fun aListingWalksFromItsNewestPageAskingEachPagesFactsBeforeTheNext() = runTest {
        val asked = MutableStateFlow<List<String>>(emptyList())
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/Multi")) {
                val first = request.url.parameters["_csvRowIds"]!!.substringBefore(',').toInt()
                asked.update { it + "facts of ${first / 50 + 1}" }
                return@MockEngine respond("[]", headers = json)
            }
            val page = request.url.parameters["_nPage"]!!.toInt()
            asked.update { it + "page $page" }
            respond(indexPage(total = 140, (page - 1) * 50 until minOf(page * 50, 140)), headers = json)
        }

        GameBananaApi(HttpClient(engine), maxConcurrent = 1).submissions(SubmissionKind.Mod)

        val order = asked.value - "page 1" - "facts of 1"
        assertEquals(listOf("page 3", "facts of 3", "page 2", "facts of 2"), order)
    }

    @Test
    fun multiFactsAreDeduplicatedBatchedByModelAndMerged() = runTest {
        val batchSizes = MutableStateFlow<List<Int>>(emptyList())
        val paths = MutableStateFlow<List<String>>(emptyList())
        val engine = MockEngine { request ->
            val ids = request.url.parameters["_csvRowIds"]!!.split(',')
            batchSizes.update { it + ids.size }
            paths.update { it + request.url.encodedPath }
            val sound = request.url.encodedPath.contains("Sound")
            assertEquals("_idRow,_nDownloadCount,_aFiles", request.url.parameters["_csvProperties"])
            respond(ids.reversed().joinToString(",", "[", "]") { """{"_idRow":$it,"_nDownloadCount":${if (sound) "${it}0" else it},"_aFiles":[]}""" }, headers = json)
        }
        val mods = (1L..85L).map { SubmissionRef(SubmissionKind.Mod, it) }
        val sounds = listOf(SubmissionRef(SubmissionKind.Sound, 42))
        val facts = GameBananaApi(HttpClient(engine), baseUrl = "https://multi.test/apiv11").fileFacts(mods + mods + sounds)

        assertEquals(listOf(1, 35, 50), batchSizes.value.sorted())
        assertEquals(listOf("/apiv11/Mod/Multi", "/apiv11/Mod/Multi", "/apiv11/Sound/Multi"), paths.value.sorted())
        assertEquals(86, facts.size)
        assertEquals(FactsAnswer.Read(FileFacts(42, null, null, filesComplete = true)), facts[SubmissionRef(SubmissionKind.Mod, 42)])
        assertEquals(FactsAnswer.Read(FileFacts(420, null, null, filesComplete = true)), facts[SubmissionRef(SubmissionKind.Sound, 42)])
    }

    @Test
    fun noMoreThanTheAllowedRequestsRunAtOnce() = runTest {
        val inFlight = MutableStateFlow(0)
        val most = MutableStateFlow(0)
        val engine = MockEngine {
            val active = inFlight.updateAndGet { it + 1 }
            most.update { maxOf(it, active) }
            try {
                delay(10)
                respond("[]", headers = json)
            } finally {
                inFlight.update { it - 1 }
            }
        }
        GameBananaApi(HttpClient(engine), maxConcurrent = 2).fileFacts((1L..400L).map { SubmissionRef(SubmissionKind.Mod, it) })
        assertTrue(most.value in 1..2, "At most 2 requests should overlap, saw ${most.value}")
        assertEquals(0, inFlight.value)
    }

    @Test
    fun aLatestModifiedWalkReadsDownToItsBoundaryThroughRepeatedAndOutOfOrderRows() = runTest {
        val shifted = walk(since = 9_900) { page ->
            (if (page == 1) 120 else 123) to when (page) {
                1 -> 1..50
                2 -> 48..97
                else -> 98..120
            }.map { it to 10_000L - it }
        }
        assertEquals(listOf(1, 2, 3), shifted.pages)
        assertEquals((1L..100L).toList(), shifted.crawl.submissions.map { it.key.id }, "the tie at the boundary is kept and repeats are dropped")
        assertEquals(9_999L, shifted.crawl.newestModifiedAt)
        assertEquals(0, shifted.crawl.skipped)

        // GameBanana's own Mod/Index page 1, captured 2026-10-17, has a row newer than the older rows around it.
        val dip = walk(since = 10) { 5 to listOf(1 to 500L, 2 to 490L, 3 to 510L, 4 to 480L, 5 to 470L) }
        assertEquals((1L..5L).toList(), dip.crawl.submissions.map { it.key.id })
        assertEquals(510L, dip.crawl.newestModifiedAt)

        val oldLast = walk(since = 300) { page ->
            100 to (if (page == 1) (1..50).map { it to if (it == 50) 100L else 400L } else (51..100).map { it to if (it == 51) 500L else 100L })
        }
        assertEquals(listOf(1, 2), oldLast.pages)
        assertEquals((1L..49L).toList() + 51L, oldLast.crawl.submissions.map { it.key.id })
        assertEquals(500L, oldLast.crawl.newestModifiedAt)
    }

    @Test
    fun aRecordWithoutAModificationTimeIsUnreadableAndNeverEndsTheWalk() = runTest {
        for (missing in 0..3) {
            val pagesAsked = mutableListOf<Int>()
            val engine = MockEngine { request ->
                val page = request.url.parameters["_nPage"]!!.toInt()
                pagesAsked += page
                val records = ((page - 1) * 50 + 1..page * 50).map { id ->
                    id to when {
                        id <= missing -> if (id == 2) 0L else null
                        else -> 10_000L - id
                    }
                }
                respond(modifiedPage(total = 200, records), headers = json)
            }
            val api = GameBananaApi(HttpClient(engine))
            if (missing <= 2) {
                val crawl = api.modifiedSince(SubmissionKind.Mod, since = 9_920)
                assertEquals(listOf(1, 2, 3), pagesAsked)
                assertEquals(missing, crawl.skipped)
                assertEquals((missing + 1..80).map(Int::toLong), crawl.submissions.map { it.key.id })
            } else {
                assertFailsWith<RemoteException>("three of 200 is more than one in a hundred") { api.modifiedSince(SubmissionKind.Mod, since = 9_920) }
            }
        }
    }

    private class Walk(val crawl: Crawl, val pages: List<Int>)

    private suspend fun walk(since: Long, page: (Int) -> Pair<Int, List<Pair<Int, Long?>>>): Walk {
        val pages = mutableListOf<Int>()
        val client = jsonClient { request ->
            assertEquals("Generic_LatestModified", request.url.parameters["_sSort"])
            assertEquals("50", request.url.parameters["_nPerpage"])
            val number = request.url.parameters["_nPage"]!!.toInt()
            pages += number
            val (total, rows) = page(number)
            modifiedPage(total, rows)
        }
        return Walk(GameBananaApi(client).modifiedSince(SubmissionKind.Mod, since), pages)
    }

    private fun modifiedPage(total: Int, records: List<Pair<Int, Long?>>): String =
        records.joinToString(",", """{"_aMetadata":{"_nRecordCount":$total},"_aRecords":[""", "]}") { (id, modified) ->
            val time = modified?.let { ""","_tsDateModified":$it""" }.orEmpty()
            """{"_idRow":$id,"_sModelName":"Mod","_sName":"Mod $id","_sProfileUrl":"https://gamebanana.com/mods/$id","_bHasContentRatings":false$time}"""
        }

    private fun indexPage(total: Int, ids: IntRange): String = ids.joinToString(",", """{"_aMetadata":{"_nRecordCount":$total},"_aRecords":[""", "]}") {
        """{"_idRow":$it,"_sModelName":"Mod","_sName":"Mod $it","_sProfileUrl":"https://gamebanana.com/mods/$it","_bHasContentRatings":false}"""
    }
}
