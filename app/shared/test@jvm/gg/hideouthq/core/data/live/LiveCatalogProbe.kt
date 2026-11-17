package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.deadlock.DeadlockApi
import gg.hideouthq.core.data.gamebanana.GameBananaApi
import gg.hideouthq.core.data.gamebanana.parseIndexPage
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.network.getText
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.feature.browse.BrowseFilters
import gg.hideouthq.feature.browse.BrowseQuery
import gg.hideouthq.feature.browse.BrowseSort
import gg.hideouthq.feature.browse.MatureMods
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.TimeSource

class LiveCatalogProbe {
    @Test
    fun liveCatalogCoversWhatTheScreensNeed() = runBlocking {
        val client = hideoutHttpClient(OkHttp.create(), "Hideout/probe (+https://hideouthq.gg)")
        val source = GameBananaCatalogSource(GameBananaApi(client), DeadlockApi(client), Clock.System)
        val started = TimeSource.Monotonic.markNow()
        val heroes = source.heroes()
        val snapshot = source.fetch(since = null) {}.last()
        println("Reconciled in ${started.elapsedNow()}; read back from ${snapshot.modifiedThrough}")
        val checked = TimeSource.Monotonic.markNow()
        val updated = source.fetch(since = snapshot) {}.last()
        println("Checked incrementally in ${checked.elapsedNow()}: ${updated.submissions.size - snapshot.submissions.size} new")
        val catalog = CatalogAssembler.assemble(snapshot, heroes, CalendarDate.today())
        val mods = catalog.mods
        val skins = mods.filter { it.category == ModCategory.Skins }
        val matcher = HeroMatcher(heroes)
        val heroCategories = snapshot.categories.filter { it.parentId == SkinsRoot }
        val unmatched = heroCategories.filter { matcher.named(it.name) == null }.map { it.name }

        println("Records: ${mods.size}; facts unknown: ${snapshot.submissions.count { it.facts == null }}; files incomplete: ${snapshot.submissions.count { it.facts?.filesComplete == false }}")
        ModCategory.entries.forEach { category -> println("  $category: ${mods.count { it.category == category }}") }
        println("Heroes: ${heroes.size}; without an icon: ${heroes.filter { it.iconUrl == null }.map { it.name }}")
        println("GameBanana hero categories: ${heroCategories.size}; not matched to a hero: $unmatched")
        println("Skins with a hero: ${share(skins) { it.heroId != null }}; sounds with a hero: ${share(mods.filter { it.category == ModCategory.Sounds }) { it.heroId != null }}")
        println("With a cover: ${share(mods.filter { it.category != ModCategory.Sounds }) { it.cover.url != null }} (sounds have none)")
        println("With downloads: ${share(mods) { (it.downloads ?: 0) > 0 }}; with a size: ${share(mods) { it.sizeBytes != null }}; mature: ${share(mods) { it.mature }}")
        println("Popular heroes: ${catalog.popularHeroIds.map { id -> heroes.first { it.id == id }.name }}")
        val title = { id: String -> catalog.modsById[id]?.title }
        println("Featured: ${catalog.discoverFeed.featured.map(title)}")
        println("Trending skins: ${catalog.discoverFeed.trendingSkins.map(title)}; HUD: ${catalog.discoverFeed.trendingHud.map(title)}; sounds: ${catalog.discoverFeed.trendingSounds.map(title)}")
        mods.take(3).forEach(::println)
        println("Sound heroes, a sample: ${mods.filter { it.category == ModCategory.Sounds && it.heroId != null }.take(8).map { it.title to it.heroId }}")

        val detail = source.detail(catalog.discoverFeed.featured.first())
        println("Mod page: ${detail.authors}, version ${detail.version}, ${detail.images.size} images, about: ${detail.about.take(160)}")

        val icon = client.get(heroes.first().iconUrl!!)
        val cover = client.get(skins.first { it.cover.url != null }.cover.url!!)
        println("Hero icon: ${icon.status} ${icon.contentType()}; cover: ${cover.status} ${cover.contentType()}")

        assertTrue(heroes.size >= MinHeroes, "Expected the full roster, got ${heroes.size} heroes")
        // Heroes still in pre-release have no icon yet and keep their initials.
        assertTrue(heroes.count { it.iconUrl != null } >= MinHeroes, "Released heroes should have icons")
        assertEquals(emptyList(), unmatched, "Every GameBanana hero category should match a hero")
        assertTrue(skins.count { it.heroId != null } >= skins.size * MinSkinsWithHero, "Too few skins have a hero")
        assertTrue(mods.count { (it.downloads ?: 0) > 0 } >= mods.size * MinWithDownloads, "Download counts are missing")
        assertEquals(4, catalog.discoverFeed.featured.size)
        assertTrue(icon.status.isSuccess() && cover.status.isSuccess(), "Images should load")
        assertEquals(SubmissionKind.entries.toSet(), snapshot.modifiedThrough.keys, "Both kinds should give modification times")
        assertTrue(updated.submissions.map { it.key }.containsAll(snapshot.submissions.map { it.key }), "An incremental check removes nothing")
        assertEquals(snapshot.syncedAt, updated.syncedAt)
    }

    @Test
    fun latestModifiedAndNewestOrdersMatchGameBanana() = runBlocking {
        val client = hideoutHttpClient(OkHttp.create(), "Hideout/probe (+https://hideouthq.gg)")
        val api = GameBananaApi(client)
        val now = Clock.System.now().epochSeconds
        for (model in SubmissionKind.entries) {
            // GameBanana's own listing can present a row or two out of time order; the walk tolerates that
            // instead of failing, so it is not asserted here.
            val walk = api.modifiedSince(model, since = now - DayInSeconds)
            val times = walk.submissions.map { it.modifiedAt!! }
            println("$model changed in the last day: ${walk.submissions.size} of ${walk.listed} listed, ${walk.skipped} unreadable; newest ${times.maxOrNull()}")
            assertTrue(times.all { it >= now - DayInSeconds })
        }

        val newestListed = parseIndexPage(client.getText("${GameBananaApi.DefaultBaseUrl}/Mod/Index") {
            parameter("_aFilters[Generic_Game]", GameBananaApi.GameId)
            parameter("_sSort", "Generic_Newest")
            parameter("_nPerpage", NewestShown)
            parameter("_nPage", 1)
        }).submissions
        val listing = api.submissions(SubmissionKind.Mod).submissions
        val today = CalendarDate.today()
        val mods = CatalogAssembler.assemble(CatalogSnapshot(CatalogSnapshot.SchemaVersion, now, emptyList(), listing, emptyList()), emptyList(), today).mods
        val ours = BrowseQuery.results(mods, emptyMap(), BrowseFilters(sort = BrowseSort.Newest, currentPatch = false), MatureMods.Shown, today, emptyMap()).map { it.id }
        val theirs = newestListed.map { it.key.modId }.filter { it in ours }
        println("GameBanana's newest: $theirs; Hideout's: ${ours.take(NewestShown)}")
        assertEquals(newestListed.map { it.addedAt }.sortedDescending(), newestListed.map { it.addedAt })
        assertEquals(theirs, ours.filter { it in theirs }, "Newest should keep GameBanana's upload order")
    }

    private fun share(mods: List<Mod>, predicate: (Mod) -> Boolean): String =
        if (mods.isEmpty()) "n/a" else "${mods.count(predicate) * Percent / mods.size}% of ${mods.size}"

    private companion object {
        const val SkinsRoot = 33295L
        const val MinHeroes = 40
        const val MinSkinsWithHero = 0.95
        const val MinWithDownloads = 0.9
        const val Percent = 100
        const val DayInSeconds = 86_400L
        const val NewestShown = 20
    }
}
