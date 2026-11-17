package gg.hideouthq.core.library

import gg.hideouthq.preview.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SetKeyTest {
    private val remote = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 721_908, 1_829_854, "0332f45e5015186cb47219853f499a25"))
    private val local = InstalledSource.Local("k7", "mine.zip")

    private fun keyOf(text: String) = sha256Hex(text.encodeToByteArray()).take(12)

    @Test
    fun aKeyIsTwelveHexDigitsOfItsFileItsMd5AndItsComponent() {
        assertEquals(keyOf("remote/1829854/0332f45e5015186cb47219853f499a25\npak01_dir.vpk"), SetKey.of(remote, "pak01_dir.vpk").value)
        assertEquals(keyOf("local/k7\nskins/a_dir.vpk"), SetKey.of(local, "skins/a_dir.vpk").value)
        assertEquals(keyOf("remote/1829854/\npak01_dir.vpk"), SetKey.of(InstalledSource.Remote(remote.identity.copy(fileMd5 = null)), "pak01_dir.vpk").value, "a file with no MD5")
    }

    @Test
    fun aSetsOffNamesStayWithItsInstallAndAnotherInstallOfTheSameFileGetsOthers() {
        val key = SetKey.of(remote, "pak01_dir.vpk")
        assertEquals("set-" + keyOf("id1\n${key.value}"), key.offStem("id1"), "the names an off folder holds stay what every release reads them as")
        assertEquals(3, setOf(key.offStem("id1"), key.offStem("id2"), SetKey.of(local, "pak01_dir.vpk").offStem("id1")).size)
        assertTrue(SetFolder.Off(remote.modId).admits("${key.offStem("id1")}_dir.vpk"))
    }

    @Test
    fun aLocalModThatGainsOnlyItsSubmissionKeepsItsKeyAndOneThatPinsToAFileGetsTheFilesKey() {
        val set = InstalledSet(local, null, null, SetOrigin.Imported(Sha256.image(ByteArray(3))), null, "a_dir.vpk", listOf(directory(Sha256.image(ByteArray(4)))))
        val known = set.copy(source = local.copy(submission = SubmissionRef(SubmissionKind.Mod, 721_908)))
        val pinned = set.copy(source = remote)
        assertEquals(set.key, known.key, "the submission is not part of a local key")
        assertNotEquals(set.key, pinned.key)
        assertEquals(SetKey.of(remote, "a_dir.vpk"), pinned.key)
    }
}
