package gg.hideouthq.core.library

import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.model.isPartialFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultVersionTest {
    private fun file(id: Long, name: String, downloads: Long, description: String? = null) =
        RemoteFile(id, name, 1_000, null, description, downloads, addedAt = 1_777_000_000, downloadUrl = "https://gamebanana.com/dl/$id")

    @Test
    fun aFileItsAuthorCallsAPartOrAMakersKitIsPartial() {
        assertTrue(isPartialFile("Optional: no fur", "lilith_no_fur.zip"))
        assertTrue(isPartialFile(null, "maplist_addon_only.zip"))
        assertTrue(isPartialFile("For map creators", "maplist_kit.zip"))
        assertTrue(isPartialFile(null, "Map-Template.7z"), "words are read whatever joins them")
        assertFalse(isPartialFile("Default", "lilith_hell_warden.zip"))
        assertFalse(isPartialFile("Commonly used", "patchwork_skin.zip"), "only whole words count")
        assertFalse(isPartialFile(null, null))
    }

    @Test
    fun installTakesTheMostDownloadedFileThatIsNotAPart() {
        val kit = file(1, "maplist_creators_template.zip", 900)
        val whole = file(2, "maplist.zip", 500)
        val patch = file(3, "maplist.zip", 100, description = "Hotfix")
        assertEquals(whole, defaultVersion(orderVersions(listOf(patch, whole, kit))), "the makers' kit is downloaded more, and is not the mod")
        assertEquals(kit, defaultVersion(orderVersions(listOf(patch, kit))), "with nothing but parts, the most downloaded")
    }
}
