package gg.hideouthq.feature.collections

import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CollectionLook
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.MonthDay
import gg.hideouthq.core.model.Rgb
import gg.hideouthq.core.model.Season
import gg.hideouthq.core.model.SkinCollection
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.ReadinessState
import gg.hideouthq.preview.SubmissionRef
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionsJoinTest {
    private val heroes = listOf("silver", "mina", "haze", "paige").map { Hero(it, it.replaceFirstChar(Char::uppercase), iconUrl = "https://example.test/$it.png") }

    private val mods = listOf(
        skin("mod-1", "silver", previewed = true),
        skin("mod-2", "silver", mature = true, previewed = true),
        skin("mod-3", "mina"),
        skin("mod-4", "mina"),
        skin("mod-5", "haze", previewed = true),
        skin("mod-6", "paige"),
        skin("mod-7", "silver"),
        skin("mod-8", null),
        skin("mod-9", "ghost"),
    )

    private val look = CollectionLook(Rgb(0x000000), Rgb(0x000000), Rgb(0xFFFFFF), emptyList(), arch = false, rim = listOf(Rgb(0xFFFFFF)))

    private val halloween = SkinCollection(
        "halloween", "Halloween", Season(MonthDay(10, 1), MonthDay(11, 7)), look,
        cover = listOf("mod-2", "mod-3", "mod-1", "mod-5"),
        members = listOf("mod-99", "mod-1", "mod-2", "mod-3", "mod-4", "mod-5", "mod-6", "mod-7", "mod-8"),
    )
    private val winter = SkinCollection("winter", "Winter", Season(MonthDay(12, 1), MonthDay(1, 7)), look, emptyList(), listOf("mod-1"))
    private val gothic = SkinCollection("gothic", "Gothic", null, look, emptyList(), listOf("mod-4"))

    private fun shown(published: List<SkinCollection>, day: CalendarDate = October) = summaries(published, mods, heroes, day)

    @Test
    fun missingAndMatureMembersAreLeftOutAndACollectionWithNoneLeftGetsNoCard() {
        val card = shown(listOf(SkinCollection("gone", "Gone", null, look, emptyList(), listOf("mod-99", "mod-2")), halloween)).single()
        assertEquals("halloween", card.id, "gone's mod-99 is not in the catalogue and its mod-2 is mature")
        assertEquals(7, card.skins, "mod-99 is not in the catalogue and mod-2 is mature")
        assertEquals(4, card.heroes, "the prop counts for no hero")
    }

    @Test
    fun aHeroTheCatalogueDoesNotKnowCountsForNone() {
        val card = shown(listOf(SkinCollection("odd", "Odd", null, look, emptyList(), listOf("mod-1", "mod-9")))).single()
        assertEquals(2, card.skins)
        assertEquals(1, card.heroes, "mod-9's hero is not in the catalogue")
    }

    @Test
    fun theThreeHeroesWithTheMostMembersLeadByCountThenName() {
        val top = shown(listOf(halloween)).single().topHeroes
        assertEquals(listOf("Mina" to 2, "Silver" to 2, "Haze" to 1), top.map { it.name to it.count })
        assertEquals(listOf("mina", "silver", "haze"), top.map { it.id })
        assertEquals("https://example.test/mina.png", top.first().iconUrl)
    }

    @Test
    fun coverModsShowTheirReadyRendersAndOthersAreSkipped() {
        assertEquals(listOf("hideout-preview://${bundle("mod-1")}/featured", "hideout-preview://${bundle("mod-5")}/featured"), shown(listOf(halloween)).single().figures)
    }

    @Test
    fun collectionsInSeasonComeFirstThenThoseWithoutOneThenTheRest() {
        val october = shown(listOf(winter, gothic, halloween))
        assertEquals(listOf("halloween", "gothic", "winter"), october.map { it.id })
        assertEquals(listOf(CalendarDate(2026, 11, 7), null, null), october.map { it.seasonEnd })
        assertEquals(listOf("winter", "gothic", "halloween"), shown(listOf(winter, gothic, halloween), CalendarDate(2026, 12, 24)).map { it.id })
    }

    private companion object {
        val October = CalendarDate(2026, 10, 6)

        fun bundle(id: String) = id.removePrefix("mod-").padStart(32, '0')

        fun skin(id: String, hero: String?, mature: Boolean = false, previewed: Boolean = false) = Mod(
            id = id, title = id, category = ModCategory.Skins, heroId = hero, downloads = 10, likes = 0, sizeBytes = null,
            mature = mature, publishedAt = 0, updated = October, trendingRank = 1,
            preview = if (!previewed) null else IndexEntry(
                SubmissionRef("Mod", id.removePrefix("mod-").toLong()), 11, bundleId = bundle(id), manifestSha256 = "9".repeat(64), build = 1,
                picture = ReadinessState.Ready, interactive = ReadinessState.Ready,
            ),
        )
    }
}
