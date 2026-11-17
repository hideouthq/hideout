package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.deadlock.DeadlockApi
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.gamebanana.GameBananaApi
import gg.hideouthq.core.data.gamebanana.jsonClient
import gg.hideouthq.core.data.live.LiveFixtures.virtualClock
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import io.ktor.client.request.HttpRequestData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class FeaturedPicksFreshnessTest {
    private val oldPick = SubmissionRef(SubmissionKind.Mod, 2)
    private val newPick = SubmissionRef(SubmissionKind.Mod, 1)

    @Test
    fun anHourlyCheckBringsInANewPickShowsItAndSavesIt() = runTest {
        val storage = MemoryStorage()
        SnapshotStore(storage).save(LiveFixtures.snapshot(featured = listOf(oldPick), checkedAt = LiveFixtures.Now - 30.minutes.inWholeSeconds))
        val repository = repository(storage, picks = { listOf(newPick) })

        val feed = repository.discoverFeed.first { it.featured.first() == newPick.modId }

        assertEquals(newPick.modId, feed.featured.first())
        assertEquals(listOf(newPick), assertNotNull(SnapshotStore(storage).load()).featured)
    }

    @Test
    fun aFailedPicksRequestInAnHourlyCheckKeepsTheOldOnes() = runTest {
        val storage = MemoryStorage()
        SnapshotStore(storage).save(LiveFixtures.snapshot(featured = listOf(oldPick), checkedAt = LiveFixtures.Now - 30.minutes.inWholeSeconds))
        val repository = repository(storage, picks = { null })

        repository.freshness.first { it.checkedAt?.let { at -> at >= LiveFixtures.Now } == true }

        assertEquals(oldPick.modId, repository.discoverFeed.first().featured.first())
        assertEquals(listOf(oldPick), assertNotNull(SnapshotStore(storage).load()).featured)
    }

    @Test
    fun aFailedPicksRequestInAFullCheckKeepsTheOldOnes() = runTest {
        val storage = MemoryStorage()
        val dayOld = LiveFixtures.Now - 25.hours.inWholeSeconds
        SnapshotStore(storage).save(LiveFixtures.snapshot(syncedAt = dayOld, featured = listOf(oldPick)))
        val repository = repository(storage, picks = { null })

        repository.freshness.first { it.checkedAt?.let { at -> at >= LiveFixtures.Now } == true }

        val saved = assertNotNull(SnapshotStore(storage).load())
        assertEquals(listOf(oldPick), saved.featured)
        assertTrue(saved.syncedAt >= LiveFixtures.Now, "the pass was a full one")
    }

    @Test
    fun aFailedPicksRequestWithNoPreviousPicksLeavesNone() = runTest {
        val client = jsonClient { answer(it, picks = { null }) }
        val source = GameBananaCatalogSource(GameBananaApi(client), DeadlockApi(client), LiveFixtures.clockAt(LiveFixtures.Now))

        assertEquals(emptyList(), source.fetch(since = null) {}.last().featured)
    }

    private fun TestScope.repository(storage: MemoryStorage, picks: () -> List<SubmissionRef>?): LiveCatalogRepository {
        val client = jsonClient { answer(it, picks) }
        val clock: Clock = virtualClock()
        return LiveCatalogRepository(GameBananaCatalogSource(GameBananaApi(client), DeadlockApi(client), clock), SnapshotStore(storage), backgroundScope, clock)
    }

    private fun answer(request: HttpRequestData, picks: () -> List<SubmissionRef>?): String? {
        val path = request.url.encodedPath
        return when {
            path.endsWith("/Featured") -> picks()?.let { refs -> records(refs.joinToString(",") { """{"_idRow":${it.id},"_sModelName":"Mod"}""" }, refs.size) }
            path.endsWith("/Categories") -> "[]"
            path.endsWith("/Mod/Index") -> records(listOf(1, 2).joinToString(",") { id ->
                """{"_idRow":$id,"_sModelName":"Mod","_sName":"Mod $id","_sProfileUrl":"https://gamebanana.com/mods/$id","_tsDateModified":${LiveFixtures.Now - 86_400 + id},"_bHasContentRatings":false,""" +
                    """"_aPreviewMedia":{"_aImages":[{"_sBaseUrl":"https://images.test","_sFile":"$id.jpg","_sFile530":"530-90_$id.jpg"}]},""" +
                    """"_aRootCategory":{"_sProfileUrl":"https://gamebanana.com/mods/cats/33295"},"_aSubCategory":{"_sProfileUrl":"https://gamebanana.com/mods/cats/33315"}}"""
            }, 2)
            path.endsWith("/Sound/Index") -> records("", 0)
            path.endsWith("/Multi") -> request.url.parameters["_csvRowIds"]!!.split(',').joinToString(",", "[", "]") { """{"_idRow":$it,"_aFiles":[]}""" }
            else -> null
        }
    }

    private fun records(rows: String, total: Int) = """{"_aMetadata":{"_nRecordCount":$total},"_aRecords":[$rows]}"""
}
