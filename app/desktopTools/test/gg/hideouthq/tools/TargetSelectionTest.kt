package gg.hideouthq.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TargetSelectionTest {
    private val registry = listOf("app", "app_browse", "app_library")

    @Test
    fun aBlankSelectionSelectsAllAndANamedOneIsTrimmedOnce() {
        for (raw in listOf(null, "", "   ")) assertEquals(registry.toSet(), selectTargets(raw, registry))
        assertEquals(setOf("app", "app_library"), selectTargets(" app , app_library,app ", registry))
    }

    @Test
    fun unknownOrBlankEntriesRejectTheWholeSelection() {
        for (raw in listOf("App", "bogus", "app,bogus", ", ,")) {
            assertFailsWith<IllegalArgumentException>("'$raw' should reject the whole selection") {
                selectTargets(raw, registry)
            }
        }
    }
}
