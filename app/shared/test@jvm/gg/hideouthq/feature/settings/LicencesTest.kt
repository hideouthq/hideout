package gg.hideouthq.feature.settings

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LicencesTest {
    @Test
    fun everyComponentHasNoticesTheAppCanReadAndEveryBundledNoticeIsShown() = runTest {
        val licences = loadLicences()
        assertTrue(licences.size > 20, "${licences.size} components")
        assertEquals(licences.map { it.component }.distinct(), licences.map { it.component }, "each component once")
        val referenced = licences.flatMap { it.files }.toSet()
        referenced.forEach { file -> assertTrue(loadNotice(file).isNotBlank(), "$file has text") }
        val bundled = File("composeResources/files/licenses").listFiles().orEmpty().map { it.name }.filter { it != "index.tsv" }.toSet()
        assertEquals(bundled, referenced, "every notice file is listed and every listed file is bundled")
    }
}
