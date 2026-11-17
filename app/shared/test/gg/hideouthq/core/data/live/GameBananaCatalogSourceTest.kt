package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.deadlock.DeadlockApi
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.GameBananaApi
import gg.hideouthq.core.data.gamebanana.Submission
import gg.hideouthq.core.data.gamebanana.SubmissionCategory
import gg.hideouthq.core.data.gamebanana.facts
import gg.hideouthq.core.data.gamebanana.jsonClient
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.network.RemoteException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameBananaCatalogSourceTest {
    @Test
    fun aFullCheckShowsNewestListingFirstThenEverySubmissionOnceWithItsFacts() = runTest {
        val asked = MutableStateFlow(emptyList<String>())
        val client = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath.substringAfter("/apiv11/")
            val sort = request.url.parameters["_sSort"]
            asked.update { it + "$path $sort" }
            val page = request.url.parameters["_nPage"]?.toInt() ?: 1
            fun rows(total: Int) = ((page - 1) * 50 + 1..minOf(page * 50, total)).map { it to 0L }
            val body = when (path) {
                "Mod/Categories", "Sound/Categories" -> "[]"
                "Util/List/Featured" -> listing(total = 0, emptyList(), category = 0)
                "Mod/Index" -> listing(total = 120, rows(120), category = 33315, rated = 100)
                "Sound/Index" -> listing(total = 60, rows(60), model = "Sound", category = 6282)
                else -> {
                    val downloads = if (path == "Sound/Multi") 1 else 1_000
                    request.url.parameters["_csvRowIds"]!!.split(',').joinToString(",", "[", "]") { """{"_idRow":$it,"_aFiles":[],"_nDownloadCount":${it.toInt() * downloads}}""" }
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val heard = Channel<CatalogSnapshot>(Channel.UNLIMITED)
        val crawl = launch { source(client).fetch(since = null) {}.collect(heard::send) }

        val first = heard.receive()
        assertTrue(first.submissions.isNotEmpty() && first.submissions.none { it.key.kind == SubmissionKind.Sound }, "mods show before any sound")
        crawl.join()
        heard.close()
        val whole = heard.toList().last()
        assertTrue(whole.unheard.isEmpty(), "a whole catalogue has nothing left to hear of")

        assertEquals((1L..120L).map(::mod) + (1L..60L).map { SubmissionRef(SubmissionKind.Sound, it) }, whole.submissions.map { it.key }, "each once, none lost")
        assertTrue(whole.submissions.all { it.facts?.downloads == it.key.id.toInt() * (if (it.key.kind == SubmissionKind.Mod) 1_000 else 1) })
        val order = asked.value
        assertTrue(order.indexOf("Sound/Index Generic_Oldest") > order.lastIndexOf("Mod/Index Generic_Oldest"), "sounds after every mod page: $order")
    }

    @Test
    fun aDegradedListingIsNeverTheWholeCatalogue() = runTest {
        for (badRecords in listOf(1, 2)) {
            var hydrationRequests = 0
            val client = jsonClient { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/Categories") -> "[]"
                    path.endsWith("/Featured") || path.endsWith("/Sound/Index") -> """{"_aMetadata":{"_nRecordCount":0},"_aRecords":[]}"""
                    path.endsWith("/Mod/Index") && request.url.parameters["_sSort"] != "Generic_Oldest" -> """{"_aMetadata":{"_nRecordCount":0},"_aRecords":[]}"""
                    path.endsWith("/Mod/Index") -> {
                        val page = request.url.parameters["_nPage"]!!.toInt()
                        val range = if (page == 1) 1..50 else 51..100
                        range.joinToString(",", """{"_aMetadata":{"_nRecordCount":100},"_aRecords":[""", "]}") { id ->
                            if (id <= badRecords) """{"_sName":"Unreadable"}"""
                            else """{"_idRow":$id,"_sModelName":"Mod","_sName":"Mod $id","_sProfileUrl":"https://gamebanana.com/mods/$id","_tsDateModified":${5_000 + id},"_bHasContentRatings":false}"""
                        }
                    }
                    path.endsWith("/Multi") -> {
                        hydrationRequests++
                        request.url.parameters["_csvRowIds"]!!.split(',').joinToString(",", "[", "]") { id ->
                            """{"_idRow":$id,"_aFiles":[],"_nDownloadCount":42}"""
                        }
                    }
                    else -> error("Unexpected request $path")
                }
            }
            val source = source(client)
            if (badRecords == 1) {
                val snapshot = source.fetch(since = null) {}.last()
                assertEquals(99, snapshot.submissions.size, "exactly one in a hundred unreadable is admitted")
                assertEquals(42, snapshot.submissions.first().facts?.downloads)
                assertEquals(2, hydrationRequests, "one batch of facts a page")
                assertEquals(LiveFixtures.Now, snapshot.syncedAt)
                assertEquals(LiveFixtures.Now, snapshot.checkedAt)
                assertEquals(mapOf(SubmissionKind.Mod to 5_100L), snapshot.modifiedThrough, "a kind with nothing listed has no time to read back from")
            } else {
                assertFailsWith<RemoteException> { source.fetch(since = null) {}.last() }
            }
        }
    }

    @Test
    fun aPageAsksForItsUpdatesOnlyWhenItHasThemAndShowsWithoutThemWhenTheyFail() = runTest {
        for ((hasUpdates, updatesFail) in listOf(true to false, false to false, true to true)) {
            var updateRequests = 0
            val client = jsonClient { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/Mod/723542/ProfilePage") -> """{"_sName":"Victor Bride","_sProfileUrl":"https://gamebanana.com/mods/723542","_bHasUpdates":$hasUpdates}"""
                    path.endsWith("/Mod/723542/Updates") -> {
                        updateRequests++
                        assertEquals("1", request.url.parameters["_nPage"])
                        """{"_aRecords":[{"_sName":"Wolf form fixes","_sVersion":"1.2","_tsDateAdded":1791100800,"_aChangeLog":[{"text":"Fixed it","cat":"Bugfix"}]}]}"""
                            .takeUnless { updatesFail }
                    }
                    else -> error("Unexpected request $path")
                }
            }
            val detail = source(client).detail("mod-723542")
            assertEquals(if (hasUpdates) 1 else 0, updateRequests)
            assertEquals(if (hasUpdates && !updatesFail) listOf("Wolf form fixes") else emptyList(), detail.updates.map { it.title })
        }
    }

    @Test
    fun anIncrementalCheckReadsBackAnHourHydratesWhatChangedOrIsUnknownAndRemovesNothing() = runTest {
        val watermark = 9_000_000L
        val previous = incrementalBase(watermark)
        val hydrated = mutableListOf<Set<Long>>()
        val client = jsonClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/Mod/Index") -> when (val page = request.url.parameters["_nPage"]) {
                    "1" -> listing(total = 400, listOf(10 to watermark + 100, 1 to watermark + 50, 3 to watermark - 3_600, 4 to watermark - 3_601), category = 33315)
                    "2" -> listing(total = 400, listOf(4 to watermark - 3_601), category = 33315)
                    else -> error("The entirely older second page should end the walk, requested $page")
                }
                path.endsWith("/Sound/Index") -> listing(total = 0, emptyList(), model = "Sound", category = 6282)
                path.endsWith("/Mod/Multi") -> {
                    val ids = request.url.parameters["_csvRowIds"]!!.split(',').map { it.toLong() }.toSet()
                    hydrated += ids
                    ids.filter { it != 2L }.joinToString(",", "[", "]") { """{"_idRow":$it,"_aFiles":[],"_nDownloadCount":${it * 1000}}""" }
                }
                path.endsWith("/Featured") -> listing(total = 1, listOf(3 to 0L), category = 33315)
                else -> error("Unexpected request $path")
            }
        }
        val progress = mutableListOf<Float?>()
        val updated = source(client).fetch(since = previous) { progress += it }.last()

        assertEquals(listOf(setOf(1L, 2L, 10L)), hydrated, "the new, the changed, and the one whose facts were unknown")
        val byId = updated.submissions.associateBy { it.key }
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 10L), byId.keys.filter { it.kind == SubmissionKind.Mod }.map { it.id }.toSet(), "5 is no longer listed, but only a full check removes")
        assertEquals(10_000, byId.getValue(mod(10)).facts?.downloads)
        assertEquals(1_000, byId.getValue(mod(1)).facts?.downloads)
        assertEquals("Mod 1", byId.getValue(mod(1)).name)
        assertNull(byId.getValue(mod(2)).facts, "left out of the answer is unknown, not empty")
        assertEquals(previous.submissions.first { it.key == mod(3) }.facts, byId.getValue(mod(3)).facts)
        assertEquals(previous.submissions.first { it.key == mod(4) }, byId.getValue(mod(4)))
        assertEquals(previous.syncedAt, updated.syncedAt)
        assertEquals(LiveFixtures.Now, updated.checkedAt)
        assertEquals(previous.featured, updated.featured)
        assertEquals(previous.categories, updated.categories)
        assertEquals(watermark + 100, updated.modifiedThrough[SubmissionKind.Mod])
        assertEquals(1f, progress.last())
    }

    @Test
    fun anIncrementalCheckAsksForTheCategoryTreeOnlyWhenAChangedSubmissionIsFiledSomewhereItDoesNotKnow() = runTest {
        for (category in listOf(33315L, 99999L)) {
            val watermark = 9_000_000L
            var treeRequests = 0
            val client = jsonClient { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/Mod/Categories") || path.endsWith("/Sound/Categories") -> {
                        treeRequests++
                        if (path.contains("/Mod/")) """[{"_idRow":33295,"_sName":"Skins","_nCategoryCount":0},{"_idRow":99999,"_sName":"New","_nCategoryCount":0}]""" else "[]"
                    }
                    path.endsWith("/Mod/Index") -> listing(total = 1, listOf(11 to watermark + 1), category = category)
                    path.endsWith("/Sound/Index") -> listing(total = 0, emptyList(), model = "Sound", category = 6282)
                    path.endsWith("/Mod/Multi") -> """[{"_idRow":11,"_aFiles":[]}]"""
                    path.endsWith("/Featured") -> listing(total = 0, emptyList(), category = 33315)
                    else -> error("Unexpected request $path")
                }
            }
            val previous = incrementalBase(watermark).let { base -> base.copy(submissions = base.submissions.filter { it.facts != null }) }
            val updated = source(client).fetch(previous) {}.last()
            if (category == 33315L) {
                assertEquals(0, treeRequests)
                assertEquals(previous.categories, updated.categories)
            } else {
                assertEquals(2, treeRequests)
                assertTrue(updated.categories.any { it.id == 99999L })
            }
        }
    }

    @Test
    fun aFailedHydrationBatchFailsTheWholeCheck() = runTest {
        val client = jsonClient { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/Mod/Index") -> listing(total = 1, listOf(12 to 9_000_001L), category = 33315)
                path.endsWith("/Sound/Index") -> listing(total = 0, emptyList(), model = "Sound", category = 6282)
                else -> null
            }
        }
        assertFailsWith<RemoteException> { source(client).fetch(incrementalBase(9_000_000L)) {}.last() }
    }

    @Test
    fun installedSubmissionsAreAskedByKindInBatchesAndAFailedBatchFailsOnlyItsOwn() = runTest {
        val asked = MutableStateFlow(emptyList<String>())
        val client = jsonClient { request ->
            val path = request.url.encodedPath
            val ids = request.url.parameters["_csvRowIds"]!!.split(',').map { it.toLong() }
            asked.update { it + "${path.substringAfter("/apiv11/")} ${ids.size}" }
            val offset = if (path.endsWith("/Sound/Multi")) 500 else 100
            ids.joinToString(",", "[", "]") { """{"_idRow":$it,"_aFiles":[{"_idRow":${it + offset}}]}""" }
                .takeUnless { path.endsWith("/Mod/Multi") && 51L in ids }
        }
        val mods = (1L..51L).map { SubmissionIdentity(SubmissionKind.Mod, it, it + 100, null) }
        val sound = SubmissionIdentity(SubmissionKind.Sound, 5, 505, null)
        val second = SubmissionIdentity(SubmissionKind.Mod, 5, 999, null)
        val seen = source(client).observe((mods + sound + second).toSet())

        assertEquals(listOf("Mod/Multi 1", "Mod/Multi 50", "Sound/Multi 1"), asked.value.sorted(), "a submission with two installed files is asked about once")
        assertEquals(FilesObservation(answer = null, setOf(mods.last()), failure = DataError.Unavailable), seen.getValue(mods.last().ref), "only the failed batch's own")
        assertEquals(listOf(105L), seen.getValue(SubmissionRef(SubmissionKind.Mod, 5)).answer?.facts?.files?.map { it.id })
        assertEquals(setOf(mods[4], second), seen.getValue(SubmissionRef(SubmissionKind.Mod, 5)).targets)
        assertEquals(listOf(505L), seen.getValue(sound.ref).answer?.facts?.files?.map { it.id }, "a sound and a mod with one id are told apart")
        assertEquals(52, seen.size)
    }

    private fun source(client: HttpClient) = GameBananaCatalogSource(GameBananaApi(client), DeadlockApi(client), LiveFixtures.clockAt(LiveFixtures.Now))

    private fun incrementalBase(watermark: Long): CatalogSnapshot {
        val submissions = (1L..5L).map { id ->
            Submission(
                key = mod(id), name = "Old $id", profileUrl = "https://gamebanana.com/mods/$id", submitter = null, categoryId = 33315, rootCategoryId = 33295,
                tags = emptyList(), images = emptyList(), audioUrl = null, likes = 0, addedAt = 1_000, updatedAt = null, obsolete = false, visibility = "show",
                facts = if (id == 2L) null else FileFacts(downloads = id.toInt(), largestFileBytes = null, newestFileAt = null, filesComplete = true),
                modifiedAt = when (id) {
                    3L -> watermark - 3_600
                    4L -> watermark - 3_601
                    else -> watermark - id
                },
            )
        }
        return CatalogSnapshot(
            schemaVersion = CatalogSnapshot.SchemaVersion,
            syncedAt = LiveFixtures.Now - 7_200,
            categories = listOf(SubmissionCategory(33295, "Skins", null, SubmissionKind.Mod), SubmissionCategory(33315, "Lady Geist", 33295, SubmissionKind.Mod)),
            submissions = submissions,
            featured = listOf(mod(3)),
            checkedAt = LiveFixtures.Now - 3_600,
            modifiedThrough = mapOf(SubmissionKind.Mod to watermark),
        )
    }

    private fun mod(id: Long) = SubmissionRef(SubmissionKind.Mod, id)

    private fun listing(total: Int, records: List<Pair<Int, Long>>, model: String = "Mod", category: Long, rated: Int? = null): String =
        records.joinToString(",", """{"_aMetadata":{"_nRecordCount":$total},"_aRecords":[""", "]}") { (id, modified) ->
            val root = if (model == "Mod") 33295 else category
            """{"_idRow":$id,"_sModelName":"$model","_sName":"$model $id","_sProfileUrl":"https://gamebanana.com/${model.lowercase()}s/$id","_tsDateModified":$modified,"_bHasContentRatings":${id == rated},""" +
                """"_aRootCategory":{"_sProfileUrl":"https://gamebanana.com/${model.lowercase()}s/cats/$root"},"_aSubCategory":{"_sProfileUrl":"https://gamebanana.com/${model.lowercase()}s/cats/$category"}}"""
        }
}
