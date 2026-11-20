package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ForeignRootsTest {
    private fun of(path: String) = ForeignRoot.of(CitadelPath(path))

    @Test
    fun everyFolderAnotherManagerKeepsHasItsRole() {
        val expected = mapOf(
            "addons" to RootRole.AdoptOn,
            "addons2" to RootRole.AdoptOn,
            "addons10" to RootRole.AdoptOn,
            "addons/profile_1726000000000_ab12cd_ranked" to RootRole.AdoptOn,
            "addons3/profile_1726000000000_ab12cd" to RootRole.AdoptOn,
            "grimoire" to RootRole.AdoptOn,
            "addons/.disabled" to RootRole.AdoptOff,
            "dmm_localization_default" to RootRole.UnmountOnly,
            "dmm_localization_0123456789abcdef" to RootRole.UnmountOnly,
            "addons/server_42" to RootRole.UnmountOnly,
        )
        assertEquals(expected, expected.keys.associateWith { of(it)?.role })
    }

    @Test
    fun namesMatchWhateverTheirCase() {
        assertEquals(ForeignRoot.Addons, of("Addons"))
        assertEquals(ForeignRoot.Priority, of("GRIMOIRE"))
        assertEquals(ForeignRoot.ProfileFolder, of("addons/Profile_1726_ab"))
    }

    @Test
    fun foldersNoOtherManagerKeepsAreNeverItsRoots() {
        listOf(
            "addons/replays", "addons-backups", "addons-backups/addons-backup-2026-07-31_03-55-22", "addons/.dmm-clear",
            "addons/.dmm-reorder", "addons/.dmm-update-650634", "hideout_off", "hideout_off/mod-650634", "hideout_backups", "hideout_backups/files", "deadworks_mods",
            "deadworks_addons/vpks", "grimoire/replays", "addons/.disabled/addons2", "addons/pak01_dir.vpk", "replays", "cfg",
        ).forEach { assertNull(of(it), it) }
    }

    @Test
    fun noFolderHideoutLoadsSetsFromIsAnotherManagersPastAddons() {
        assertEquals(listOf(ForeignRoot.Addons), SetFolder.Loading.mapNotNull { of(it.inCitadel) })
    }
}
