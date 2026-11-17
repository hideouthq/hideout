package gg.hideouthq.feature.discover

import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.preview.SubmissionRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DiscoverOrderTest {
    private fun skin(id: String, hero: String, rank: Int, cover: Boolean = true, mature: Boolean = false) = Mod(
        id = id, title = id, category = ModCategory.Skins, heroId = hero, downloads = 0, likes = 0, sizeBytes = null, mature = mature,
        publishedAt = 0, updated = CalendarDate(2026, 1, 1), trendingRank = rank, cover = CoverImage(url = if (cover) "https://example.com/$id.jpg" else null),
    )

    private val mods = listOf(
        skin("haze-1", "hero_haze", 1), skin("haze-2", "hero_haze", 2), skin("inferno-1", "hero_inferno", 3),
        skin("wraith-1", "hero_wraith", 4), skin("lash-1", "hero_lash", 5), skin("ivy-1", "hero_tengu", 6),
        skin("ivy-bare", "hero_tengu", 7, cover = false), skin("ivy-mature", "hero_tengu", 8, mature = true), skin("ivy-2", "hero_tengu", 30),
    )

    private val feed = DiscoverFeed(
        featured = listOf("haze-1", "haze-2", "inferno-1", "wraith-1"),
        heroesToBrowse = listOf("hero_haze", "hero_inferno", "hero_wraith", "hero_lash", "hero_tengu"),
        trendingSkins = listOf("lash-1", "ivy-1"),
        trendingHud = listOf("hud"),
        trendingSounds = emptyList(),
    )

    @Test
    fun noPicksLeaveTheFeedAsItIs() {
        assertSame(feed, personalize(feed, mods, emptyList()))
    }

    @Test
    fun whileSkinsAreStillBeingRankedOnlyTheHeroStripFollowsThePicks() {
        val ranking = mods.map { if (it.id == "ivy-2") it.copy(rankFinal = false) else it }
        val led = personalize(feed, ranking, listOf("hero_tengu"))
        assertEquals(feed.featured, led.featured)
        assertEquals(feed.trendingSkins, led.trendingSkins)
        assertEquals("hero_tengu", led.heroesToBrowse.first())
    }

    @Test
    fun featuredOpensWithEachPickedHerosBestSkinThatCanBeFeatured() {
        val led = personalize(feed, mods, listOf("hero_tengu", "hero_lash"))
        assertEquals(listOf("ivy-1", "lash-1", "haze-1", "haze-2"), led.featured)
    }

    @Test
    fun aPickedHerosBestSkinWithAPictureLeadsWhileAnySkinHasOne() {
        fun pictured(mod: Mod) = mod.copy(preview = PreviewFixtures.entry(PreviewFixtures.manifest(SubmissionRef("Mod", mod.trendingRank.toLong()), 1, PreviewFixtures.Md5)))
        val withPictures = mods.map { if (it.id in setOf("ivy-2", "haze-1")) pictured(it) else it }
        assertEquals("ivy-2", personalize(feed, withPictures, listOf("hero_tengu")).featured.first(), "ivy-1 trends higher but has no picture")
        assertEquals("haze-1", personalize(feed, withPictures, listOf("hero_lash")).featured.first(), "Lash has no skin with a picture, so nothing leads for him")
    }

    @Test
    fun trendingSkinsOpenWithThePickedHeroesSkinsAndKeepTheirLength() {
        val led = personalize(feed, mods, listOf("hero_tengu"))
        assertEquals(listOf("ivy-1", "haze-1", "haze-2", "inferno-1"), led.featured)
        assertEquals(listOf("ivy-2", "wraith-1"), led.trendingSkins, "Ivy's other skin with art leads; the bare and mature ones never show")
        assertEquals(feed.trendingHud, led.trendingHud)
    }

    @Test
    fun theHeroStripOpensWithThePickedHeroesInPickOrder() {
        val led = personalize(feed, mods, listOf("hero_lash", "hero_unknown", "hero_tengu"))
        assertEquals(listOf("hero_lash", "hero_tengu", "hero_haze", "hero_inferno", "hero_wraith"), led.heroesToBrowse)
    }
}
