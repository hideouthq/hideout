package gg.hideouthq.core.data.preview

import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.preview.InvalidPreview
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewRepositoryTest {
    private val manifest = PreviewFixtures.manifest()
    private val entry = PreviewFixtures.entry(manifest)
    private val good = PreviewFixtures.text(PreviewFixtures.index(entry))

    @Test
    fun withoutAHostThereAreNoPreviews() = runTest {
        val repository = PreviewRepository(null, MemoryStorage(), backgroundScope)
        runCurrent()
        assertNull(repository.index.value)
        assertFailsWith<IllegalStateException> { repository.manifest(entry) }
    }

    @Test
    fun aGoodIndexIsPublishedAndSaved() = runTest {
        val storage = MemoryStorage()
        val repository = PreviewRepository(FixturePreviewSource(good), storage, backgroundScope)
        runCurrent()
        assertEquals(listOf(entry), repository.index.value?.entries)
        assertEquals(good, storage.read("preview-index.json"))
    }

    @Test
    fun aSavedIndexServesWhileTheHostIsDownAndAFailedRefreshKeepsIt() = runTest {
        val source = FixturePreviewSource(good).apply { failIndex = true }
        val repository = PreviewRepository(source, MemoryStorage(mapOf("preview-index.json" to good)), backgroundScope)
        runCurrent()
        assertEquals(listOf(entry), repository.index.value?.entries)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, source.indexRequests)
        assertEquals(listOf(entry), repository.index.value?.entries)
    }

    @Test
    fun theHostIsAskedForTheIndexOnlyIfItDiffersFromTheOneHeld() = runTest {
        val storage = MemoryStorage(mapOf("preview-index.json" to good))
        val source = FixturePreviewSource(good)
        val repository = PreviewRepository(source, storage, backgroundScope)
        runCurrent()
        assertEquals(listOf<String?>(sha256Hex(good.encodeToByteArray())), source.held, "the first ask names the saved index")
        assertEquals(listOf(entry), repository.index.value?.entries, "an unchanged index leaves the saved one serving")

        val next = PreviewFixtures.text(PreviewFixtures.index(entry, generation = 2))
        source.index = next
        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(2L, repository.index.value?.generation)
        assertEquals(next, storage.read("preview-index.json"))
        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(sha256Hex(next.encodeToByteArray()), source.held.last(), "a refreshed index is the one held next")
        assertEquals(2L, repository.index.value?.generation)
    }

    @Test
    fun aHostThatCouldNotBeReachedIsAskedAgainAfterAMinute() = runTest {
        val source = FixturePreviewSource(good).apply { failIndex = true }
        val repository = PreviewRepository(source, MemoryStorage(), backgroundScope)
        runCurrent()
        assertNull(repository.index.value)
        source.failIndex = false
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf(entry), repository.index.value?.entries)
    }

    @Test
    fun unknownSchemasAndUnreadableIndexesAreRejectedAndTheLastGoodOneKept() = runTest {
        val source = FixturePreviewSource(good)
        val storage = MemoryStorage()
        val repository = PreviewRepository(source, storage, backgroundScope)
        runCurrent()
        for (bad in listOf(good.replace("\"schema\":1", "\"schema\":2"), good.replace(entry.bundleId, "../${entry.bundleId}"))) {
            source.index = bad
            advanceTimeBy(1.hours)
            runCurrent()
            assertEquals(listOf(entry), repository.index.value?.entries, bad)
            assertEquals(good, storage.read("preview-index.json"), bad)
        }
    }

    @Test
    fun aCorruptOrForeignSavedIndexIsIgnored() = runTest {
        for (saved in listOf("{\"schema\":1,\"generation\":", good.replace("\"schema\":1", "\"schema\":7"))) {
            val source = FixturePreviewSource(good).apply { failIndex = true }
            val repository = PreviewRepository(source, MemoryStorage(mapOf("preview-index.json" to saved)), backgroundScope)
            runCurrent()
            assertNull(repository.index.value, saved)
        }
    }

    @Test
    fun aRebuiltHostReplacesTheSavedInventoryEvenWhenItsGenerationRestarted() = runTest {
        val saved = PreviewFixtures.text(PreviewFixtures.index(entry, generation = 5734))
        val rebuilt = PreviewFixtures.text(PreviewFixtures.index(generation = 1695))
        val storage = MemoryStorage(mapOf("preview-index.json" to saved))
        val source = FixturePreviewSource(rebuilt).apply { failIndex = true }
        val repository = PreviewRepository(source, storage, backgroundScope)
        runCurrent()
        assertEquals(listOf(entry), repository.index.value?.entries)

        source.failIndex = false
        advanceTimeBy(1.hours)
        runCurrent()
        assertEquals(1695L, repository.index.value?.generation)
        assertEquals(emptyList(), repository.index.value?.entries)
        assertEquals(rebuilt, storage.read("preview-index.json"))

        source.failIndex = true
        val restarted = PreviewRepository(source, storage, backgroundScope)
        runCurrent()
        assertEquals(1695L, restarted.index.value?.generation)
        assertEquals(emptyList(), restarted.index.value?.entries)
    }

    @Test
    fun manifestsMustMatchTheirEntry() = runTest {
        val text = PreviewFixtures.text(manifest)
        val repository = PreviewRepository(FixturePreviewSource(good, mapOf(manifest.bundleId to text)), MemoryStorage(), backgroundScope)
        assertEquals(manifest, repository.manifest(entry))
        assertFailsWith<InvalidPreview> { repository.manifest(entry.copy(fileId = 202)) }
        assertFailsWith<InvalidPreview> { repository.manifest(entry.copy(fileMd5 = null)) }
        assertFailsWith<InvalidPreview> { repository.manifest(entry.copy(build = 1)) }
    }

    @Test
    fun imageAddressesNameOnlyABundleAndARoleOrAListedAssetPath() {
        val picture = PreviewImageAddress.Picture(entry.bundleId, PictureRole.Featured)
        assertEquals("hideout-preview://${entry.bundleId}/featured", picture.url)
        assertEquals(picture, PreviewImageAddress.parse(picture.url))
        val icon = PreviewImageAddress.Icon(entry.bundleId, "icons/vanilla/ability_1.png")
        assertEquals("hideout-preview://${entry.bundleId}/icon/icons/vanilla/ability_1.png", icon.url)
        assertEquals(icon, PreviewImageAddress.parse(icon.url))
        for (url in listOf(
            "https://example.test/${entry.bundleId}/featured",
            "hideout-preview://${entry.bundleId}/thumbnail",
            "hideout-preview://../featured",
            "hideout-preview://${entry.bundleId}/icon/../manifest.json",
            "hideout-preview://not-a-bundle/icon/icons/ability_1.png",
        )) assertNull(PreviewImageAddress.parse(url), url)
    }
}
