package gg.hideouthq.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewIndexTest {
    private val current = sampleManifest()
    private val older = sampleManifest(fixtureInput.copy(file = fixtureInput.file.copy(id = 1400001, md5 = "0".repeat(32))))
    private val index = indexOf(entryFor(current), entryFor(older))

    @Test
    fun indexWithOneEntryPerExactFileIsValid() {
        assertEquals(emptyList(), index.problems())
    }

    @Test
    fun repeatedFilesAndBundlesAreRejected() {
        val repeatedFile = indexOf(entryFor(current), entryFor(current).copy(bundleId = older.bundleId))
        assertTrue(repeatedFile.problems().any { "repeats Mod 612345 file 1500001" in it }, repeatedFile.problems().toString())
        val repeatedBundle = indexOf(entryFor(current), entryFor(older).copy(bundleId = current.bundleId))
        assertTrue(repeatedBundle.problems().any { "repeats bundle" in it }, repeatedBundle.problems().toString())
    }

    @Test
    fun entryMustDescribeTheManifestItNames() {
        assertEquals(emptyList(), entryFor(current).problemsAgainst(current))
        val entry = entryFor(current)
        assertEquals(1, entry.copy(fileMd5 = null).problemsAgainst(current).size)
        assertEquals(1, entry.copy(fileId = 1400001).problemsAgainst(current).size)
        assertEquals(1, entry.copy(build = 21000002).problemsAgainst(current).size)
        assertEquals(1, entry.copy(bundleId = older.bundleId).problemsAgainst(current).size)
    }
}
