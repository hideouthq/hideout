package gg.hideouthq.feature.browse

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.live.CatalogAssembler
import gg.hideouthq.core.data.live.LiveFixtures
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class BrowseQueryTest {
    private val catalog = AppContainer.fake().catalog

    private suspend fun results(filters: BrowseFilters): List<String> {
        val heroes = catalog.heroes.first().associateBy { it.id }
        return BrowseQuery.results(catalog.mods.first(), heroes, filters, MatureMods.Blurred, catalog.today, emptyMap()).map { it.id }
    }

    @Test
    fun skinsInTrendingOrderMatchTheDesign() = runTest {
        assertEquals(
            listOf("faun-celeste", "lilith", "plague-doctor", "propeller-hat", "sadistic-warden", "grey-talon-upgrade", "garden-goddess", "werecat"),
            results(BrowseFilters(category = ModCategory.Skins)),
        )
    }

    @Test
    fun searchingWarFindsTheTwoWardens() = runTest {
        assertEquals(listOf("lilith", "sadistic-warden"), results(BrowseFilters(query = "war")))
    }

    @Test
    fun silverWith3dPreviewFindsTwoMods() = runTest {
        assertEquals(listOf("lilith", "werecat"), results(BrowseFilters(heroIds = setOf("silver"), only3d = true)))
    }

    @Test
    fun aCategoryIsFoundByTheLabelItIsShownBy() {
        val labels = mapOf(ModCategory.Skins to "Skins", ModCategory.QualityOfLife to "QoL & Fixes")
        assertEquals(listOf<BrowseSuggestion>(BrowseSuggestion.Category(ModCategory.QualityOfLife)), BrowseQuery.suggestions("fixes", emptyList(), BrowseFilters(), labels))
        assertEquals(emptyList(), BrowseQuery.suggestions("quality", emptyList(), BrowseFilters(), labels))
        val fix = Mod("fix", "Faster menus", ModCategory.QualityOfLife, downloads = 0, likes = 0, sizeBytes = null, publishedAt = 0, updated = CalendarDate(2026, 1, 1), trendingRank = 1)
        assertEquals(listOf(fix), BrowseQuery.results(listOf(fix), emptyMap(), BrowseFilters(query = "qol"), MatureMods.Blurred, CalendarDate(2026, 1, 2), labels))
    }

    @Test
    fun typingWarSuggestsWarden() = runTest {
        val suggestions = BrowseQuery.suggestions("war", catalog.heroes.first(), BrowseFilters(), emptyMap())
        assertEquals(listOf<BrowseSuggestion>(BrowseSuggestion.Hero("warden", "Warden")), suggestions)
    }

    @Test
    fun findAHeroNarrowsToMatches() = runTest {
        val chips = BrowseQuery.heroChips(catalog.heroes.first(), catalog.popularHeroIds.first(), BrowseFilters(heroQuery = "gr"))
        assertEquals(listOf("Graves", "Grey Talon"), chips.map { it.name })
    }

    @Test
    fun downloadsSortPutsHudlockFirst() = runTest {
        assertEquals("hudlock", results(BrowseFilters(sort = BrowseSort.Downloads)).first())
    }

    @Test
    fun newestOrdersTheSameDaysUploadsByTheSecondThenById() {
        val midnight = LiveFixtures.Now - 86_400
        val base = LiveFixtures.snapshot()
        val template = base.submissions.first()
        fun upload(id: Long, addedAt: Long, downloads: Int) =
            template.copy(key = SubmissionRef(SubmissionKind.Mod, id), addedAt = addedAt, facts = template.facts?.copy(downloads = downloads))
        val submissions = listOf(
            upload(4, midnight + 9 * 3_600, downloads = 90_000),
            upload(6, midnight + 21 * 3_600, downloads = 5),
            upload(5, midnight + 21 * 3_600, downloads = 10),
            upload(7, midnight - 3_600, downloads = 1),
        )
        val today = CalendarDate.fromEpochSeconds(LiveFixtures.Now)
        val mods = CatalogAssembler.assemble(base.copy(submissions = submissions), LiveFixtures.heroes, today).mods
        val newest = BrowseQuery.results(mods, emptyMap(), BrowseFilters(sort = BrowseSort.Newest), MatureMods.Blurred, today, emptyMap())

        assertEquals(listOf("mod-5", "mod-6", "mod-4", "mod-7"), newest.map { it.id })
        assertEquals(List(3) { CalendarDate.fromEpochSeconds(midnight) }, newest.take(3).map { it.published }, "the day shown stays the day")
    }
}
