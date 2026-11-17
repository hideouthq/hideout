package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.gamebanana.SubmissionCategory
import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.facts
import gg.hideouthq.core.data.gamebanana.parseFileFacts
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.ModCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CatalogAssemblerTest {
    private val today = CalendarDate(2026, 10, 6)
    private val catalog = CatalogAssembler.assemble(LiveFixtures.snapshot(), LiveFixtures.heroes, today)

    @Test
    fun submissionsBecomeModsWithTheirHero() {
        val bride = catalog.modsById.getValue("mod-1")
        assertEquals(ModCategory.Skins, bride.category)
        assertEquals("hero_ghost", bride.heroId)
        assertEquals(9_000, bride.downloads)
        assertEquals(1_000_000L, bride.sizeBytes)
        assertEquals("https://images.test/530-90_1.jpg", bride.cover.url)
        assertEquals(CalendarDate(2026, 10, 3), bride.published)
        assertEquals(CalendarDate(2026, 10, 5), bride.updated)

        assertEquals("hero_doorman", catalog.modsById.getValue("mod-2").heroId)
        assertTrue(catalog.modsById.getValue("mod-3").mature)

        val hud = catalog.modsById.getValue("mod-4")
        assertEquals(ModCategory.Hud, hud.category)
        assertEquals("Gameplay", hud.kind)

        val voices = catalog.modsById.getValue("sound-4")
        assertEquals(ModCategory.Sounds, voices.category)
        assertEquals("hero_frank", voices.heroId)
        assertEquals("Doctor's voice pack", voices.title)
        assertEquals("VOs", voices.kind)
        assertEquals("https://files.test/4.mp3", voices.audioPreviewUrl)
    }

    @Test
    fun modsAreRankedByTrending() {
        assertEquals((1..catalog.mods.size).toList(), catalog.mods.map { it.trendingRank })
        assertEquals("mod-3", catalog.mods.first().id)
    }

    @Test
    fun skinsFeatureTheirSecondPictureAndOpenTheViewerOnlyWithAnExactPreview() {
        val bride = catalog.modsById.getValue("mod-1")
        assertFalse(bride.has3dPreview)
        assertEquals("https://images.test/1.jpg", bride.cover.fullUrl)
        assertFalse(catalog.modsById.getValue("mod-4").has3dPreview)
    }

    @Test
    fun discoverFeaturesOnlySkinsAndLeavesOutMatureMods() {
        val feed = CatalogAssembler.assemble(
            LiveFixtures.snapshot(featured = listOf(SubmissionRef(SubmissionKind.Mod, 4), SubmissionRef(SubmissionKind.Mod, 3), SubmissionRef(SubmissionKind.Mod, 2))),
            LiveFixtures.heroes, today,
        ).discoverFeed
        assertEquals(listOf("mod-2", "mod-1"), feed.featured)
        assertFalse("mod-3" in feed.trendingSkins)
        assertEquals(listOf("mod-4"), feed.trendingHud)
        assertEquals(listOf("sound-4"), feed.trendingSounds)
    }

    @Test
    fun aSubmissionShowsBeforeItsFactsWithoutACountAndOnlyTheListingMarksItMature() {
        val snapshot = LiveFixtures.snapshot().let { it.copy(submissions = it.submissions.map { row -> row.copy(facts = null, mature = row.key.modId == "mod-1") }) }
        val unknown = CatalogAssembler.assemble(snapshot, LiveFixtures.heroes, today)
        assertTrue(unknown.mods.all { it.downloads == null })
        assertEquals(setOf("mod-1", "mod-3"), unknown.mods.filter { it.mature }.map { it.id }.toSet(), "rated in the listing, or hidden by its visibility")
        assertEquals(listOf("mod-2"), unknown.discoverFeed.featured)
        assertEquals(listOf("sound-4"), unknown.discoverFeed.trendingSounds)
        assertEquals(listOf("mod-2", "mod-1"), catalog.discoverFeed.featured)
    }

    @Test
    fun eachFileKeepsOnlyItsOwnSizeFromTheMultiAnswer() {
        val submission = LiveFixtures.snapshot().submissions.first()
        val facts = parseFileFacts(
            """[{"_idRow":${submission.key.id},"_aFiles":[
                {"_idRow":101,"_sFile":"small.zip","_nFilesize":1200},
                {"_idRow":102,"_sFile":"large.zip","_nFilesize":"9800"},
                {"_idRow":103,"_sFile":"unknown.zip"},
                {"_idRow":104,"_sFile":"invalid.zip","_nFilesize":-1},
                {"_idRow":105,"_sFile":"bad.zip","_nFilesize":"unknown"}
            ]}]""",
            submission.key.kind,
            setOf(submission.key.id),
        ).getValue(submission.key).facts
        val mod = CatalogAssembler.assemble(LiveFixtures.snapshot().copy(submissions = listOf(submission.copy(facts = facts))), LiveFixtures.heroes, today)
            .previewed(emptyMap()).mods.single()
        assertEquals(9800L, mod.sizeBytes)
        assertEquals(listOf(1200L, 9800L, null, null, null), mod.previewVariants.map { it.sizeBytes })
    }

    @Test
    fun heroesArePopularByTheirSkins() {
        assertEquals(listOf("hero_ghost", "hero_doorman", "hero_frank"), catalog.popularHeroIds)
    }

    @Test
    fun browseByHeroOffersTheWholeRosterBeforeAndAfterPreviewsArrive() {
        val roster = LiveFixtures.heroes + (1..20).map { Hero("hero_extra_$it", "Extra ${it.toString().padStart(2, '0')}") }
        val assembled = CatalogAssembler.assemble(LiveFixtures.snapshot(), roster, today)
        val order = listOf("hero_ghost", "hero_doorman") + (1..20).map { "hero_extra_$it" } + "hero_frank"

        assertEquals(order, assembled.discoverFeed.heroesToBrowse)
        assertEquals(order, assembled.previewed(emptyMap()).discoverFeed.heroesToBrowse, "previews change no hero's place")
    }

    @Test
    fun gameplayModsTrendOnDiscoverLeavingOutMatureOnes() {
        val base = LiveFixtures.snapshot()
        val hud = base.submissions.first { it.key == SubmissionRef(SubmissionKind.Mod, 4) }
        val outlines = hud.copy(
            key = SubmissionRef(SubmissionKind.Mod, 5), name = "Clear outlines", categoryId = 33331, rootCategoryId = 33331,
            facts = FileFacts(5_000, largestFileBytes = null, newestFileAt = null),
        )
        val mature = outlines.copy(key = SubmissionRef(SubmissionKind.Mod, 6), name = "Mature", visibility = "hide")
        val assembled = CatalogAssembler.assemble(
            base.copy(categories = base.categories + SubmissionCategory(33331, "Gameplay Modifications", null, SubmissionKind.Mod), submissions = base.submissions + outlines + mature),
            LiveFixtures.heroes, today,
        )
        val mod = assembled.modsById.getValue("mod-5")
        assertEquals(ModCategory.Gameplay, mod.category)
        assertEquals(listOf("mod-5"), assembled.discoverFeed.trendingGameplay)
    }
}
