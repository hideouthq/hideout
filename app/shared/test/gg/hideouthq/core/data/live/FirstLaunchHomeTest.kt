package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.deadlock.DeadlockApi
import gg.hideouthq.core.data.fake.NoCacheStorage
import gg.hideouthq.core.data.gamebanana.GameBananaApi
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.ui.catalog.featuredRenderUrl
import gg.hideouthq.feature.discover.DiscoverUiState
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.SubmissionRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FirstLaunchHomeTest {
    @Test
    fun nothingAFirstLaunchHasShownEverMoves() = runTest {
        val feeds = feedsFrom(firstLaunch(FakeGameBanana()))
        val final = feeds.last()

        assertTrue(final.trendingSkins.size >= 4 && final.trendingHud.size == 4 && final.trendingSounds.size == 4, "the fixture fills every row: $final")
        feeds.forEach { feed ->
            assertEquals(final.featured, feed.featured, "the featured mods")
            assertEquals(final.heroesToBrowse, feed.heroesToBrowse, "Browse by hero")
            assertStaysPut("skins", feed.trendingSkins, final.trendingSkins)
            assertStaysPut("HUD", feed.trendingHud, final.trendingHud)
            assertStaysPut("gameplay", feed.trendingGameplay, final.trendingGameplay)
            assertStaysPut("sounds", feed.trendingSounds, final.trendingSounds)
        }
    }

    @Test
    fun theFeaturedCutOutsDoNotWaitForTheListing() = runTest {
        val walking = CompletableDeferred<Unit>()
        val gameBanana = FakeGameBanana(holdListing = walking)
        val repository = firstLaunch(gameBanana)

        val feed = repository.discoverFeed.first()

        assertEquals(gameBanana.picks.take(4).map { "mod-$it" }, feed.featured)
        feed.featured.forEach { id -> assertNotNull(repository.cachedMod(id)?.featuredRenderUrl, "$id's cut-out, with the listing still to come") }
        assertEquals(gameBanana.heroOrder, feed.heroesToBrowse.take(2), "Browse by hero, from the category tree's counts")
        walking.complete(Unit)
    }

    @Test
    fun theTrendingSkinsDoNotWaitForTheOldestHalfOfTheListing() = runTest {
        val walking = CompletableDeferred<Unit>()
        val repository = firstLaunch(FakeGameBanana(holdListing = walking, heldPages = 2..6))

        val feed = repository.discoverFeed.first { it.trendingSkins.size >= 4 }

        assertEquals(wholeFeed().trendingSkins.take(feed.trendingSkins.size), feed.trendingSkins)
        walking.complete(Unit)
    }

    @Test
    fun aListingCutShortLeavesEveryRowItCanFill() = runTest {
        val gameBanana = FakeGameBanana(failAfterRequests = 40)
        val repository = firstLaunch(gameBanana)

        repository.freshness.first { it.error != null }

        val feed = repository.discoverFeed.first { it.trendingSkins.isNotEmpty() }
        assertEquals(gameBanana.picks.take(4).map { "mod-$it" }, feed.featured)
        assertFalse(DiscoverUiState(freshness = repository.freshness.value).filling, "no section still waits to be filled")
    }

    @Test
    fun rateLimitedOnEverythingLeavesAnErrorNotASilentPage() = runTest {
        val repository = firstLaunch(FakeGameBanana(failAfterRequests = 0))

        val freshness = repository.freshness.first { it.error != null }

        assertFalse(DiscoverUiState(freshness = freshness).filling)
    }

    private suspend fun TestScope.wholeFeed(): DiscoverFeed = feedsFrom(firstLaunch(FakeGameBanana())).last()

    private fun assertStaysPut(row: String, shown: List<String>, final: List<String>) =
        assertEquals(final.take(shown.size), shown, "the $row row may only ever grow at its end")

    private suspend fun TestScope.feedsFrom(repository: LiveCatalogRepository): List<DiscoverFeed> {
        val feeds = CopyOnWriteArrayList<DiscoverFeed>()
        repository.discoverFeed.onEach { feeds += it }.launchIn(backgroundScope)
        repository.freshness.first { it.checkedAt != null }
        testScheduler.runCurrent()
        return feeds
    }

    private fun TestScope.firstLaunch(gameBanana: FakeGameBanana): LiveCatalogRepository {
        val client = gameBanana.client()
        val source = GameBananaCatalogSource(GameBananaApi(client), DeadlockApi(client), LiveFixtures.clockAt(LiveFixtures.Now))
        return LiveCatalogRepository(
            source, SnapshotStore(NoCacheStorage), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now),
            previews = MutableStateFlow<PreviewIndex?>(gameBanana.previews), previewsAvailable = true,
        )
    }
}

private class FakeGameBanana(
    private val holdListing: CompletableDeferred<Unit>? = null,
    private val heldPages: IntRange = 1..Int.MAX_VALUE,
    private val failAfterRequests: Int? = null,
) {
    private val requests = AtomicInteger()
    val picks = listOf(599L, 597L, 41L, 593L, 591L)
    val heroOrder = listOf("hero_doorman", "hero_ghost")

    val previews: PreviewIndex = PreviewFixtures.index(*picks.map { id ->
        PreviewFixtures.entry(PreviewFixtures.manifest(SubmissionRef("Mod", id), fileIdOf(id), md5 = null))
    }.toTypedArray())

    fun client() = HttpClient(MockEngine { request ->
        if (request.url.encodedPath.endsWith("/assets/heroes")) return@MockEngine respond(Roster, headers = Json)
        val asked = requests.getAndIncrement()
        if (failAfterRequests != null && asked >= failAfterRequests) return@MockEngine respond("", HttpStatusCode.ServiceUnavailable)
        val path = request.url.encodedPath.substringAfter("/apiv11/")
        val parameters = request.url.parameters
        if (parameters["_sSort"] == "Generic_Oldest" && parameters["_nPage"]!!.toInt() in heldPages) holdListing?.await()
        val body = when (path) {
            "Mod/Categories" -> modCategories(parameters["_idCategoryRow"]?.toLong())
            "Sound/Categories" -> if (parameters["_idCategoryRow"] == null) """[{"_idRow":$SoundsRoot,"_sName":"VOs","_nItemCount":$SoundCount,"_nCategoryCount":0}]""" else "[]"
            "Util/List/Featured" -> page(picks.size, picks.map { record("Mod", it) })
            "Mod/Index" -> listing("Mod", 1L..ModCount, parameters["_sSort"]!!, parameters["_nPage"]!!.toInt())
            "Sound/Index" -> listing("Sound", 1L..SoundCount, parameters["_sSort"]!!, parameters["_nPage"]!!.toInt())
            "Mod/Multi", "Sound/Multi" -> parameters["_csvRowIds"]!!.split(',').map(String::toLong).joinToString(",", "[", "]") { id ->
                """{"_idRow":$id,"_nDownloadCount":${downloads(id)},"_aFiles":[{"_idRow":${fileIdOf(id)},"_sFile":"f.zip","_nFilesize":1}]}"""
            }
            else -> "[]"
        }
        respond(body, headers = Json)
    })

    private fun modCategories(parent: Long?): String = when (parent) {
        null -> """[{"_idRow":$SkinsRoot,"_sName":"Skins","_nItemCount":${skins().size},"_nCategoryCount":2},""" +
            """{"_idRow":$HudRoot,"_sName":"HUD","_nItemCount":${hud().size},"_nCategoryCount":0},{"_idRow":$GameplayRoot,"_sName":"Gameplay","_nItemCount":${gameplay().size},"_nCategoryCount":0}]"""
        SkinsRoot -> """[{"_idRow":$GeistCategory,"_sName":"Lady Geist","_nItemCount":${skins().count { !isDoormans(it) }},"_nCategoryCount":0},""" +
            """{"_idRow":$DoormanCategory,"_sName":"Doorman","_nItemCount":${skins().count(::isDoormans)},"_nCategoryCount":0}]"""
        else -> "[]"
    }

    private fun listing(model: String, ids: LongRange, sort: String, number: Int): String {
        val ordered = when (sort) {
            "Generic_Oldest" -> ids.sortedBy(::addedAt)
            "Generic_MostDownloaded" -> ids.sortedByDescending(::downloads)
            "Generic_MostLiked" -> ids.sortedByDescending(::likes)
            else -> error("Unexpected sort $sort")
        }
        return page(ids.count(), ordered.drop((number - 1) * 50).take(50).map { record(model, it) })
    }

    private fun page(total: Int, records: List<String>) = records.joinToString(",", """{"_aMetadata":{"_nRecordCount":$total},"_aRecords":[""", "]}")

    private fun record(model: String, id: Long): String {
        val plural = "${model.lowercase()}s"
        val (root, category) = if (model == "Sound") SoundsRoot to SoundsRoot else when {
            id in gameplay() -> GameplayRoot to GameplayRoot
            id in hud() -> HudRoot to HudRoot
            isDoormans(id) -> SkinsRoot to DoormanCategory
            else -> SkinsRoot to GeistCategory
        }
        return """{"_idRow":$id,"_sModelName":"$model","_sName":"$model $id","_sProfileUrl":"https://gamebanana.com/$plural/$id","_tsDateAdded":${addedAt(id)},"_nLikeCount":${likes(id)},""" +
            """"_bHasContentRatings":false,"_aPreviewMedia":{"_aImages":[{"_sBaseUrl":"https://cdn.test","_sFile":"$id.jpg"}]},""" +
            """"_aRootCategory":{"_sProfileUrl":"https://gamebanana.com/$plural/cats/$root"},"_aSubCategory":{"_sProfileUrl":"https://gamebanana.com/$plural/cats/$category"}}"""
    }

    private fun gameplay() = (1L..ModCount).filter { it % 25 == 0L }.toSet()
    private fun hud() = (1L..ModCount).filter { it % 10 == 0L && it !in gameplay() }.toSet()
    private fun skins() = (1L..ModCount).filter { it !in gameplay() && it !in hud() }
    private fun isDoormans(id: Long) = id <= 400 || id % 4 == 1L
    private fun addedAt(id: Long) = LiveFixtures.Now - (ModCount - id + 1) * 6 * 3_600
    private fun likes(id: Long) = ((id * 37) % 97).toInt()
    private fun downloads(id: Long) = if (id % 37 == 0L) 400_000 else ((id * 2_654_435_761L) % 20_000).toInt()

    private fun fileIdOf(id: Long) = 10_000 + id

    private companion object {
        val Json = headersOf(HttpHeaders.ContentType, "application/json")
        val Roster = """[{"class_name":"hero_ghost","name":"Lady Geist","player_selectable":true},{"class_name":"hero_doorman","name":"The Doorman","player_selectable":true}]"""
        const val ModCount = 600L
        const val SoundCount = 120L
        const val SkinsRoot = 33295L
        const val HudRoot = 31713L
        const val GameplayRoot = 33331L
        const val SoundsRoot = 6282L
        const val GeistCategory = 33315L
        const val DoormanCategory = 40060L
    }
}
