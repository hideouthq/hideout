package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeadlockStatsSnapshotStoreTest {
    private val account = SteamAccountId.ofAccountId(555)!!

    @Test
    fun invalidSnapshotsAreIgnored() = runTest {
        val storage = MemoryStorage()
        val store = DeadlockStatsSnapshotStore(storage)
        for (text in listOf("not json", """{"accountId":777}""")) {
            storage.write("deadlock-stats-555.json", text)
            assertNull(store.load(account))
        }
    }

    @Test
    fun cancelledStorageReadsPropagateCancellation() = runTest {
        val storage = object : CacheStorage {
            override suspend fun read(name: String): String? = throw CancellationException("closed")
            override suspend fun write(name: String, text: String) = Unit
        }

        assertFailsWith<CancellationException> { DeadlockStatsSnapshotStore(storage).load(account) }
    }
}
