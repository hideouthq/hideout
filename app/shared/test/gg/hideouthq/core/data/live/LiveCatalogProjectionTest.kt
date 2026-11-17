package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.SubmissionFile
import gg.hideouthq.core.data.gamebanana.SubmissionFileOption
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.SubmissionRef
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveCatalogProjectionTest {
    private val snapshot = LiveFixtures.snapshot().let { snapshot ->
        snapshot.copy(submissions = snapshot.submissions.map { submission ->
            val fileId = submission.key.id * 100 + 1
            submission.copy(facts = submission.facts?.copy(
                currentFile = SubmissionFile(fileId, PreviewFixtures.Md5),
                files = listOf(SubmissionFileOption(fileId, PreviewFixtures.Md5, "skin.zip", "Current skin")),
            ))
        })
    }
    private val index = PreviewFixtures.index(*snapshot.submissions.map { submission ->
        PreviewFixtures.entry(PreviewFixtures.manifest(
            submission = SubmissionRef(submission.key.kind.apiName, submission.key.id),
            fileId = submission.key.id * 100 + 1,
        ))
    }.toTypedArray())

    @Test
    fun indexUpdatesReachTheCacheAndNextSubscriberWithoutCatalogueRefresh() = runTest {
        val previews = MutableStateFlow<PreviewIndex?>(null)
        val source = StubSource(fetch = { snapshot })
        val repository = LiveCatalogRepository(
            source, SnapshotStore(heroesSaved()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now), previews = previews,
        )
        runCurrent()
        assertNull(repository.cachedMod("mod-1")?.preview)

        previews.value = index.copy(generation = 7)
        runCurrent()
        assertEquals(101L, repository.cachedMod("mod-1")?.preview?.fileId)
        assertEquals(101L, repository.mods.first().first { it.id == "mod-1" }.preview?.fileId)

        val rebuilt = index.entries.first().copy(manifestSha256 = "8".repeat(64))
        previews.value = PreviewFixtures.index(rebuilt, generation = 1)
        runCurrent()
        val updated = repository.mods.first()
        assertEquals(rebuilt, repository.cachedMod("mod-1")?.preview)
        assertEquals(true, repository.cachedMod("mod-1")?.has3dPreview)
        assertNull(repository.cachedMod("mod-2")?.preview)
        assertEquals(updated, updated.map { repository.cachedMod(it.id) })

        previews.value = null
        runCurrent()
        assertNull(repository.cachedMod("mod-1")?.preview)
        assertEquals(listOf(101L), repository.cachedMod("mod-1")?.previewVariants?.map { it.fileId })
        assertTrue(repository.mods.first().all { it.preview == null && !it.has3dPreview })
        assertEquals(CatalogFreshness(checkedAt = LiveFixtures.Now), repository.freshness.value)
        assertEquals(1, source.fetches)
    }

    @Test
    fun aCatalogueRefreshMatchesItsNewFilesAgainstTheIndexItAlreadyHas() = runTest {
        val replacement = index.entries.first().copy(fileId = 102, fileMd5 = "0".repeat(32))
        var current = snapshot
        val source = StubSource(fetch = { current })
        val repository = LiveCatalogRepository(
            source, SnapshotStore(heroesSaved()), backgroundScope, LiveFixtures.clockAt(LiveFixtures.Now),
            previews = MutableStateFlow(index.copy(entries = index.entries + replacement)),
        )
        runCurrent()
        assertEquals(101L, repository.mods.first().first { it.id == "mod-1" }.preview?.fileId)

        current = snapshot.copy(submissions = snapshot.submissions.map { submission ->
            if (submission.key.modId != "mod-1") submission else submission.copy(facts = submission.facts?.copy(
                currentFile = SubmissionFile(102, replacement.fileMd5),
                files = listOf(SubmissionFileOption(102, replacement.fileMd5, "updated.zip", null)),
            ))
        })
        repository.refresh()
        runCurrent()
        val refreshed = repository.mods.first().first { it.id == "mod-1" }
        assertEquals(replacement, refreshed.preview)
        assertEquals(listOf("updated.zip"), refreshed.previewVariants.map { it.filename })
        assertEquals(refreshed, repository.cachedMod("mod-1"))
        assertEquals(2, source.fetches)
    }

    private suspend fun heroesSaved() = MemoryStorage().also { SnapshotStore(it).saveHeroes(LiveFixtures.heroes) }
}
