package gg.hideouthq.core.data.collections

import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsRepositoryTest {
    private class Host(var text: String?) : CollectionsSource {
        var requests = 0

        override suspend fun collectionsText(): String {
            requests++
            return text ?: throw IOException("unreachable")
        }
    }

    @Test
    fun theSavedCopyServesAtOnceWhileTheHostIsDown() = runTest {
        val repository = LiveCollectionsRepository(Host(null), MemoryStorage(mapOf(FileName to document("halloween"))), backgroundScope)
        runCurrent()
        assertEquals(listOf("halloween"), repository.ids())
    }

    @Test
    fun aGoodDocumentIsShownAndSavedThenFetchedAgainHourly() = runTest {
        val host = Host(document("halloween"))
        val storage = MemoryStorage()
        val repository = LiveCollectionsRepository(host, storage, backgroundScope)
        runCurrent()
        assertEquals(listOf("halloween"), repository.ids())
        assertEquals(document("halloween"), storage.read(FileName))

        host.text = document("halloween", "winter")
        advanceTimeBy(59.minutes)
        runCurrent()
        assertEquals(listOf("halloween"), repository.ids(), "not before the hour")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf("halloween", "winter"), repository.ids())
        assertEquals(document("halloween", "winter"), storage.read(FileName))
    }

    @Test
    fun aFailedOrMalformedFetchKeepsTheLastGoodDocument() = runTest {
        val host = Host(document("halloween"))
        val storage = MemoryStorage()
        val repository = LiveCollectionsRepository(host, storage, backgroundScope)
        runCurrent()
        for (bad in listOf(null, "{\"schema\": 1, \"collections\": [", document("halloween").replace("\"schema\": 1", "\"schema\": 2"))) {
            host.text = bad
            advanceTimeBy(1.hours)
            runCurrent()
            assertEquals(listOf("halloween"), repository.ids(), bad)
            assertEquals(document("halloween"), storage.read(FileName), bad)
        }
    }

    @Test
    fun anUnreachableHostIsAskedAgainAfterAMinute() = runTest {
        val host = Host(null)
        val repository = LiveCollectionsRepository(host, MemoryStorage(), backgroundScope)
        runCurrent()
        assertEquals(emptyList(), repository.ids())
        host.text = document("neon")
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(2, host.requests)
        assertEquals(listOf("neon"), repository.ids())
    }

    private fun CollectionsRepository.ids() = collections.value.map { it.id }

    private companion object {
        const val FileName = "collections.json"

        fun document(vararg ids: String) =
            """{"schema": 1, "collections": [${ids.joinToString { """{"id": "$it", "title": "$it", "look": $Look, "cover": [], "members": [{"model": "Mod", "id": 1}]}""" }}]}"""

        const val Look = """{"top": "#000000", "bottom": "#000000", "main": "#FFFFFF", "glows": [], "arch": false, "rim": ["#FFFFFF"]}"""
    }
}
