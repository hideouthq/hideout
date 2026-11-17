package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.SubmissionFile
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption
import gg.hideouthq.core.data.preview.FixturePreviewSource
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.data.preview.PreviewSource
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef as InstalledRef
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.core.ui.catalog.featuredRenderUrl
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.SubmissionRef
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewMatchingTest {
    private val today = CalendarDate(2026, 10, 6)
    private val md5 = PreviewFixtures.Md5
    private val otherMd5 = "0123456789abcdef0123456789abcdef"

    private val currentFiles = mapOf(
        InstalledRef(SubmissionKind.Mod, 1) to SubmissionFile(101, md5),
        InstalledRef(SubmissionKind.Mod, 2) to SubmissionFile(202, md5),
        InstalledRef(SubmissionKind.Mod, 3) to SubmissionFile(301, md5),
        InstalledRef(SubmissionKind.Sound, 4) to SubmissionFile(501, md5),
    )
    private val mod4Current = SubmissionFileOption(401, md5, "classic_bars_v2.zip", null)

    private val mod4Other = SubmissionFileOption(402, md5, "classic_bars_legacy.zip", null)

    private val snapshot = LiveFixtures.snapshot().let { snapshot ->
        snapshot.copy(
            submissions = snapshot.submissions.map {
                val options = if (it.key == InstalledRef(SubmissionKind.Mod, 4)) listOf(mod4Other, mod4Current) else emptyList()
                it.copy(facts = it.facts?.copy(currentFile = currentFiles[it.key], files = options))
            },
        )
    }
    private val catalog = CatalogAssembler.assemble(snapshot, LiveFixtures.heroes, today)

    private fun entry(model: String, id: Long, fileId: Long, md5: String?): IndexEntry =
        PreviewFixtures.entry(PreviewFixtures.manifest(SubmissionRef(model, id), fileId, md5))

    private val exact = entry("Mod", 1, 101, md5)
    private val olderFile = entry("Mod", 2, 201, md5)
    private val otherBytes = entry("Mod", 3, 301, otherMd5)
    private val ambiguous = entry("Mod", 4, 401, md5)
    private val noIndexMd5 = entry("Sound", 4, 501, null)
    private val index = PreviewFixtures.index(exact, olderFile, otherBytes, ambiguous, noIndexMd5)

    private fun previewed(modId: String, index: PreviewIndex = this.index) =
        catalog.previewed(index.entries.groupBy { it.submission }).modsById.getValue(modId)

    @Test
    fun onlyTheExactCurrentFileGetsAPreview() {
        assertEquals(exact, previewed("mod-1").preview)
        assertTrue(previewed("mod-1").has3dPreview)
        assertNull(previewed("mod-2").preview, "a bundle for an older file of the submission is stale")
        assertNull(previewed("mod-3").preview, "a bundle for the same file id with another MD5 is stale")
        assertEquals(ambiguous, previewed("mod-4").preview, "a ready current variant is a display default")
        assertTrue(previewed("mod-4").has3dPreview)
        assertEquals(noIndexMd5, previewed("sound-4").preview, "MD5s are compared only when both sides have one")
    }

    @Test
    fun md5sMustAgreeOnlyWhenBothTheCatalogueAndIndexHaveOne() {
        val base = catalog.modsById.getValue("mod-1")
        for ((catalogMd5, indexMd5, matches) in listOf(
            Triple(md5, md5, true),
            Triple(md5, otherMd5, false),
            Triple(null, md5, true),
            Triple(md5, null, true),
            Triple(null, null, true),
        )) {
            val file = ExactFile(SubmissionRef("Mod", 1), 101, catalogMd5)
            val single = Catalog(
                listOf(base), emptyList(), catalog.discoverFeed,
                files = mapOf(base.id to file),
                fileOptions = mapOf(base.id to listOf(FileOption(file, "skin.zip", null))),
            )
            val candidate = entry("Mod", 1, 101, indexMd5)
            val mod = single.previewed(mapOf(candidate.submission to listOf(candidate))).mods.single()
            val expected = candidate.takeIf { matches }
            assertEquals(expected, mod.preview, "catalogue MD5 $catalogMd5 and index MD5 $indexMd5")
            assertEquals(expected, mod.previewVariants.single().entry)
            assertEquals(matches, mod.has3dPreview)
        }
    }

    @Test
    fun equalFileAndSubmissionIdsUnderAnotherModelNeverMatch() {
        val sound = entry("Sound", 1, 101, md5)
        assertNull(previewed("mod-1", PreviewFixtures.index(sound)).preview)
        assertEquals(exact, previewed("mod-1", PreviewFixtures.index(sound, exact)).preview)
    }

    @Test
    fun aReadyCurrentVariantGetsADefaultPreviewWithoutChangingTheDownloadIdentity() {
        val mod4 = previewed("mod-4")
        assertEquals(ambiguous, mod4.preview)
        assertTrue(mod4.has3dPreview)
        assertNull(catalog.files["mod-4"], "a display default does not choose which download to install")
        assertEquals(
            listOf(
                PreviewVariant(402, md5, "classic_bars_legacy.zip", null, null),
                PreviewVariant(401, md5, "classic_bars_v2.zip", null, ambiguous),
            ),
            mod4.previewVariants,
        )
    }

    @Test
    fun theIndexReachesModsWhileSyncAndBrowsingIgnoreIt() = runTest {
        val previews = MutableStateFlow<PreviewIndex?>(null)
        val repository = LiveCatalogRepository(StubSource(fetch = { snapshot }), SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now), previews = previews)
        runCurrent()
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
        assertNull(repository.mods.first().first { it.id == "mod-1" }.preview)

        previews.value = index
        runCurrent()
        assertEquals(exact, repository.mods.first().first { it.id == "mod-1" }.preview)
        assertEquals(exact, repository.cachedMod("mod-1")?.preview)
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
    }

    @Test
    fun aPickWithNoPictureIsNotFeaturedWhileOthersHaveOneAndIsOnceItHasOne() = runTest {
        val previews = MutableStateFlow<PreviewIndex?>(null)
        val repository = LiveCatalogRepository(StubSource(fetch = { snapshot }), SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now), previews = previews)
        runCurrent()
        assertEquals(listOf("mod-2", "mod-1"), repository.discoverFeed.first().featured, "with no pictures known, every candidate stands")

        val pick = entry("Mod", 2, 202, md5)
        previews.value = PreviewFixtures.index(exact)
        runCurrent()
        val without = repository.discoverFeed.first()
        assertEquals(listOf("mod-1"), without.featured, "a pick without a picture gives way to a skin with one")
        assertEquals(listOf("mod-2"), without.trendingSkins, "trending leaves out exactly what is featured")

        previews.value = PreviewFixtures.index(exact, pick)
        runCurrent()
        val with = repository.discoverFeed.first()
        assertEquals(listOf("mod-2", "mod-1"), with.featured, "the pick leads again once it has its picture")
        assertEquals(emptyList(), with.trendingSkins)
    }

    @Test
    fun aPickHoldsItsPlaceWhileItsFactsAreOutstandingAndGivesWayOnlyOnceKnownToHaveNoPicture() {
        val pick = entry("Mod", 2, 202, md5)
        fun featuredWith(factsOutstanding: Set<String>, vararg entries: IndexEntry): List<String> {
            val streaming = snapshot.copy(submissions = snapshot.submissions.map { if (it.key.modId in factsOutstanding) it.copy(facts = null) else it })
            return CatalogAssembler.assemble(streaming, LiveFixtures.heroes, today).previewed(entries.groupBy { it.submission }).discoverFeed.featured
        }
        assertEquals(listOf("mod-2", "mod-1"), featuredWith(setOf("mod-2"), exact, pick), "the pick's facts are not in, so whether the index's bundle is of its file is unknown")
        assertEquals(listOf("mod-1"), featuredWith(emptySet(), exact, olderFile), "the pick's facts are in and the index has no picture of its file")
        assertEquals(listOf("mod-2", "mod-1"), featuredWith(emptySet(), exact, pick), "the pick's picture is ready")
        assertEquals(listOf("mod-2"), featuredWith(setOf("mod-1"), pick), "only a pick holds a place on unknown facts")
    }

    @Test
    fun aPickTheIndexHasNoBundleOfIsNotFeaturedSoNoTurnShowsAnEmptyStage() {
        val streaming = snapshot.copy(submissions = snapshot.submissions.map { if (it.key.modId == "mod-2") it.copy(facts = null) else it })
        val catalog = CatalogAssembler.assemble(streaming, LiveFixtures.heroes, today)
        assertEquals(listOf("mod-2", "mod-1"), catalog.discoverFeed.featured, "before the index has loaded, the pick holds its place")
        val featured = catalog.previewed(listOf(exact).groupBy { it.submission })
        assertEquals(listOf("mod-1"), featured.discoverFeed.featured, "the loaded index has no bundle of the pick's submission at all")
        assertTrue(featured.discoverFeed.featured.all { featured.modsById.getValue(it).featuredRenderUrl != null })
    }

    @Test
    fun aDownOrHangingPreviewHostLeavesTheCatalogueAlone() = runTest {
        val hanging = object : PreviewSource {
            override suspend fun indexText(held: String?): String? = CompletableDeferred<String>().await()
            override suspend fun manifestText(entry: IndexEntry): String = CompletableDeferred<String>().await()
        }
        val down = FixturePreviewSource(PreviewFixtures.text(index)).apply { failIndex = true }
        for (source in listOf(hanging, down)) {
            val previews = PreviewRepository(source, MemoryStorage(), backgroundScope)
            val repository = LiveCatalogRepository(StubSource(fetch = { snapshot }), SnapshotStore(MemoryStorage()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now), previews = previews.index)
            runCurrent()
            assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
            assertEquals(5, repository.mods.first().size)
            assertTrue(repository.mods.first().none { it.preview != null || it.has3dPreview })
            assertNull(previews.index.value)
        }
    }
}
