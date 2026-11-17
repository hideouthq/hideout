package gg.hideouthq.core.ui.catalog

import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.CheckProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogLoadingTest {
    @Test
    fun theColdLoadPlaceholderCarriesTheRunningCheckAndItsProgress() = runTest {
        val freshness = MutableStateFlow(CatalogFreshness(running = CheckProgress(0.4f)))
        val seen = mutableListOf<CatalogFreshness>()
        val result = emptyFlow<String>().whileCatalogLoads(freshness) { status -> seen += status; "placeholder" }
        assertEquals("placeholder", result.first())
        assertEquals(listOf(CatalogFreshness(running = CheckProgress(0.4f))), seen)
    }

    @Test
    fun contentTakesPriorityOverThePlaceholderOnceItArrives() = runTest {
        val freshness = MutableStateFlow(CatalogFreshness(running = CheckProgress(0.9f)))
        val result = flowOf("content").whileCatalogLoads(freshness) { "placeholder" }
        assertEquals(listOf("placeholder", "content"), result.take(2).toList(), "the placeholder shows first, then the real content replaces it")
    }
}
