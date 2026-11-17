package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CitadelPathTest {
    @Test
    fun namesAnotherManagerWroteAreKeptAsTheyAre() {
        listOf(
            "addons/637275_Percentage Rename.vpk",
            "addons/721342_奶龙v59_模型本体_pak01_dir.vpk",
            "addons/profile_1726_ab_ranked/.dmm.json",
            "Grimoire/PAK05_DIR.vpk",
            "addons/.disabled",
        ).forEach { assertEquals(it, CitadelPath.parse(it)?.value) }
    }

    @Test
    fun anythingThatCouldClimbOutOrConfuseAPathIsRefused() {
        listOf(
            "", "/addons", "addons/", "addons//pak01_dir.vpk", ".", "addons/./pak01_dir.vpk", "..", "addons/../gameinfo.gi",
            "C:", "C:/Windows", "d:addons", "addons\\pak01_dir.vpk", "addons/pak01\u0000_dir.vpk", "addons/a\nb.vpk",
        ).forEach { value ->
            assertNull(CitadelPath.parse(value), value)
            assertFailsWith<IllegalArgumentException>(value) { CitadelPath(value) }
        }
    }

    @Test
    fun resolvingCannotClimbOutAndTheTopFolderHasNoParent() {
        assertNull(CitadelPath.Addons.parent)
        assertFailsWith<IllegalArgumentException> { CitadelPath.Addons.resolve("../cfg/autoexec.cfg") }
    }

    @Test
    fun withinIsTheFolderItselfOrBelowItWholeSegmentsOnly() {
        val addons = CitadelPath.Addons
        assertTrue(addons.within(addons))
        assertTrue(CitadelPath("addons/pak01_dir.vpk").within(addons))
        assertTrue(CitadelPath("addons/profile_1/pak01_dir.vpk").within(addons))
        assertFalse(CitadelPath("addons2/pak01_dir.vpk").within(addons))
        assertFalse(CitadelPath("addons-backups/x/pak01_dir.vpk").within(addons))
        assertFalse(addons.within(CitadelPath("addons/pak01_dir.vpk")))
        assertFalse(CitadelPath("Addons/pak01_dir.vpk").within(addons), "case is compared exactly")
    }

    @Test
    fun noImportEntersAnotherManagersSnapshotsOrStagingOrHideoutsBackups() {
        listOf("addons-backups/x/pak01_dir.vpk", "addons/.dmm-clear/pak01_dir.vpk", "hideout_backups/files/pak01_dir.vpk", "Hideout_Backups/x").forEach { assertTrue(CitadelPath(it).offLimits, it) }
        listOf("addons/pak01_dir.vpk", "hideout_off/mod-1/pak01_dir.vpk", "grimoire/pak05_dir.vpk").forEach { assertFalse(CitadelPath(it).offLimits, it) }
    }

    @Test
    fun eachFolderAdmitsOnlyItsOwnKindOfName() {
        val off = SetFolder.Off("mod-1")
        val stem = SetKey.of(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 1, 10, null)), "pak01_dir.vpk").offStem("id1")
        assertTrue(SetFolder.Addons.admits("pak07_dir.vpk") && SetFolder.Addons.admits("pak07_002.vpk"))
        assertTrue(off.admits("${stem}_dir.vpk") && off.admits("${stem}_002.vpk"))
        assertFalse(off.admits("pak07_dir.vpk"), "a pak name is refused in an off folder")
        assertFalse(SetFolder.Addons.admits("${stem}_dir.vpk"), "an off name is refused in addons")
        listOf("pak00_dir.vpk", "pak100_dir.vpk", "631628_pak07_dir.vpk", "pak07_dir.vpk.bak", "PAK07_DIR.VPK").forEach { assertFalse(SetFolder.Addons.admits(it), it) }
        assertFalse(off.admits("set-3FA94C0D12BE_dir.vpk"), "a key is lowercase hex")

        for ((folder, name) in listOf(SetFolder.Addons to "pak07_dir.vpk", off to "${stem}_000.vpk")) {
            assertTrue(folder.admitsTemporary("$name.hideout-id1"), "an install's temporary in ${folder.path}")
            assertTrue(folder.admitsTemporary("$name.hideout-t2"), "an anchor in ${folder.path}")
            assertTrue(folder.admitsTemporary("$name.hideout-t2-new"), "a change's temporary in ${folder.path}")
            assertTrue(folder.admitsTemporary("$name.hideout-${"t".repeat(MaxTokenLength)}-new"), "a change's temporary with the longest token in ${folder.path}")
            assertFalse(folder.admitsTemporary("$name.hideout-${"t".repeat(MaxTokenLength + 1)}"), "no longer token than a change's in ${folder.path}")
            assertFalse(folder.admits("$name.hideout-t2"), "a temporary is never a final name")
            assertFalse(folder.admitsTemporary(name), "a final name is not a temporary")
            assertFalse(folder.admitsTemporary("$name.hideout-"), "a temporary names its token")
        }
        assertFalse(off.admitsTemporary("pak07_dir.vpk.hideout-t2"))
        assertFalse(SetFolder.Addons.admitsTemporary("${stem}_dir.vpk.hideout-t2"))
    }

    @Test
    fun aPathIsHideoutsOnlyUnderTheNameItsFolderAdmits() {
        val stem = SetKey.of(InstalledSource.Local("x", "x.zip"), "x_dir.vpk").offStem("id1")
        assertEquals(SetFolder.Addons, CitadelPath("addons/pak07_dir.vpk").hideoutFolder)
        assertEquals(SetFolder.Off("mod-1"), CitadelPath("hideout_off/mod-1/${stem}_dir.vpk").hideoutFolder)
        assertNull(CitadelPath("hideout_off/mod-1/pak07_dir.vpk").hideoutFolder)
        assertNull(CitadelPath("addons/${stem}_dir.vpk").hideoutFolder)
        assertNull(CitadelPath("addons/profile_1/pak07_dir.vpk").hideoutFolder)
    }
}
