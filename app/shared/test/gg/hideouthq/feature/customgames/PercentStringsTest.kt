package gg.hideouthq.feature.customgames

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PercentStringsTest {
    /** Compose's resources replace `%1$d` and leave everything else alone, so `%%` reaches the screen as two signs. */
    @Test
    fun noStringDoublesItsPercentSign() {
        val strings = Files.list(Path.of("composeResources/values")).use { files -> files.toList() }
        assertTrue(strings.isNotEmpty())
        assertEquals(emptyList(), strings.filter { "%%" in it.readText() }.map { it.name })
    }
}
