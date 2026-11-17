package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CatalogSnapshotTest {
    @Test
    fun checkTimesAndModificationTimesSurviveASaveAndAnUnknownFactStaysUnknown() = runTest {
        val storage = MemoryStorage()
        val fixture = LiveFixtures.snapshot(syncedAt = LiveFixtures.Now - 7_200, checkedAt = LiveFixtures.Now)
        val snapshot = fixture.copy(submissions = fixture.submissions.mapIndexed { index, submission -> if (index == 0) submission.copy(facts = null) else submission })
        SnapshotStore(storage).save(snapshot)
        val loaded = assertNotNull(SnapshotStore(storage).load())
        assertEquals(snapshot, loaded)
        assertEquals(null, loaded.submissions.first().facts)
        assertEquals(LiveFixtures.Now - 86_400 + 1, loaded.submissions.first().modifiedAt)
    }
}
