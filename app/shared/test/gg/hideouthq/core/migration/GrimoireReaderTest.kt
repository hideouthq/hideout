package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecLines
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.resourceBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class GrimoireReaderTest {
    private fun hash(c: Char) = c.toString().repeat(64)

    private val locker = listOf(
        ForeignMod(ModKey("locker:cards"), "Grimoire Locker · hero cards", ModIdentity.None, listOf(SetMatcher.At(CitadelPath("grimoire/pak01_dir.vpk"))), position = null, listed = false),
        ForeignMod(ModKey("locker:sounds"), "Grimoire Locker · hero sounds", ModIdentity.None, listOf(SetMatcher.At(CitadelPath("grimoire/pak02_dir.vpk"))), position = null, listed = false),
        ForeignMod(ModKey("locker:colors"), "Grimoire Locker · hero colors", ModIdentity.None, listOf(SetMatcher.At(CitadelPath("grimoire/pak03_dir.vpk"))), position = null, listed = false),
        ForeignMod(ModKey("locker:trippyskins"), "Grimoire Locker · trippy skins", ModIdentity.None, listOf(SetMatcher.At(CitadelPath("grimoire/pak04_dir.vpk"))), position = null, listed = false),
    )

    @Test
    fun rowsGrimoireOnlyBackfilledSayNothingAndItsFoldersAreItsFootprint() {
        val library = GrimoireReader.read(resourceBytes("grimoire/mod-metadata.json"), profiles = null, autoexec = resourceBytes("dmm/autoexec.cfg"))
        assertEquals(locker, library.mods, "32 rows with hashes and guessed heroes but no identity claim nothing")
        assertEquals(emptyList(), library.profiles)
        assertEquals(null, library.activeProfile)
        assertEquals(AutoexecLines(emptyList(), emptyList()), library.autoexec, "DMM's section is not Grimoire's")
    }

    @Test
    fun identifiedRowsJoinByHashAndOneSubmissionIsOneMod() {
        val metadata = """{
          "pak03_dir.vpk":{"gameBananaId":650634,"gameBananaFileId":1720039,"sourceSection":"Mod","modName":"QoL","sha256":"${hash('a').uppercase()}","lastPriority":3},
          "pak04_dir.vpk":{"gameBananaId":650634,"gameBananaFileId":1720039,"vpkIndex":1,"sha256":"${hash('b')}"},
          "bell_dir.vpk":{"gameBananaId":4321,"sourceSection":"Sound","sha256":"${hash('c')}"},
          "two_files_dir.vpk":{"gameBananaId":99,"gameBananaFileId":1,"sha256":"${hash('1')}"},
          "two_files_2_dir.vpk":{"gameBananaId":99,"gameBananaFileId":2,"sha256":"${hash('2')}"},
          "wip_dir.vpk":{"gameBananaId":77,"sourceSection":"Wip","modName":"Work in progress","sha256":"${hash('d')}"},
          "mine_dir.vpk":{"modName":"Mine","localGroupId":"g1","sha256":"${hash('e')}"},
          "mine_2_dir.vpk":{"modName":"Mine","localGroupId":"g1","sha256":"${hash('f')}","lastPriority":7},
          "pak09_dir.vpk":{"modName":"No hash","sha256":"not a hash"},
          "pak10_dir.vpk":{"lockerHero":"Ivy","globalType":null,"sha256":"${hash('0')}"},
          "locker:cards":{"lockerCosmetics":{},"sha256":"${hash('9')}"}
        }"""
        val library = GrimoireReader.read(metadata.encodeToByteArray(), profiles = null, autoexec = null)
        assertEquals(
            locker + listOf(
                ForeignMod(
                    ModKey("gamebanana:mod:650634"), "QoL", ModIdentity.File(SubmissionIdentity(SubmissionKind.Mod, 650634, 1720039, fileMd5 = null)),
                    listOf(SetMatcher.Hash(hash('a')), SetMatcher.Hash(hash('b'))), position = 3,
                ),
                ForeignMod(ModKey("gamebanana:sound:4321"), null, ModIdentity.Submission(SubmissionRef(SubmissionKind.Sound, 4321)), listOf(SetMatcher.Hash(hash('c'))), position = null),
                ForeignMod(ModKey("gamebanana:mod:99"), null, ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 99)), listOf(SetMatcher.Hash(hash('1')), SetMatcher.Hash(hash('2'))), position = null),
                ForeignMod(ModKey("wip:77"), "Work in progress", ModIdentity.None, listOf(SetMatcher.Hash(hash('d'))), position = null),
                ForeignMod(ModKey("local:g1"), "Mine", ModIdentity.None, listOf(SetMatcher.Hash(hash('e')), SetMatcher.Hash(hash('f'))), position = 7),
            ),
            library.mods,
        )
    }

    @Test
    fun profilesKeepTheirMembersByHashThenByIdsAndNoneIsLive() {
        val profiles = """[
          {"id":"profile_1726000000000_ab12cd","name":"Ranked","mods":[
            {"fileName":"pak03_dir.vpk","enabled":true,"priority":1,"gameBananaId":650634,"gameBananaFileId":1720039,"sha256":"${hash('a')}"},
            {"fileName":"pak05_dir.vpk","enabled":true,"priority":2,"gameBananaId":4321,"gameBananaFileId":99},
            {"fileName":"pak06_dir.vpk","priority":3,"gameBananaId":12},
            {"fileName":"pak07_dir.vpk","enabled":false,"priority":4,"sha256":"${hash('b')}"},
            {"fileName":"pak08_dir.vpk","enabled":true,"priority":5}],
           "crosshair":{"pipGap":4},"autoexecCommands":["fps_max 240"],"createdAt":"2026-10-01T00:00:00.000Z","updatedAt":"2026-10-01T00:00:00.000Z"},
          {"id":"profile_1726000000000_ab12cd","name":"Again","mods":[]},
          {"name":"No id","mods":[]}
        ]"""
        val library = GrimoireReader.read(metadata = null, profiles.encodeToByteArray(), autoexec = null)
        assertEquals(
            listOf(
                ForeignProfile(
                    ProfileKey("profile_1726000000000_ab12cd"), "Ranked", folder = null,
                    members = listOf(ProfileMember.Files(SetMatcher.Hash(hash('a'))), ProfileMember.Submission(4321), ProfileMember.Submission(12)),
                ),
            ),
            library.profiles,
        )
        assertEquals(null, library.activeProfile)
        assertEquals(locker, library.mods)
    }

    @Test
    fun aProfilesMembersComeLowestPriorityFirstAndThoseWithoutOneLast() {
        val profiles = """[{"id":"p1","name":"Fun","mods":[
            {"priority":3,"sha256":"${hash('a')}"},
            {"enabled":true,"sha256":"${hash('b')}"},
            {"enabled":true,"priority":1,"gameBananaId":12},
            {"enabled":true,"priority":2,"sha256":"${hash('c')}"}]}]"""
        assertEquals(
            listOf(ProfileMember.Submission(12), ProfileMember.Files(SetMatcher.Hash(hash('c'))), ProfileMember.Files(SetMatcher.Hash(hash('a'))), ProfileMember.Files(SetMatcher.Hash(hash('b')))),
            GrimoireReader.read(metadata = null, profiles.encodeToByteArray(), autoexec = null).profiles.single().members,
        )
    }

    @Test
    fun aSaveFromBefore1315WhosePrioritiesRepeatIsAlreadyInLoadOrder() {
        val profiles = """[{"id":"p1","name":"Old","mods":[
            {"enabled":true,"priority":5,"sha256":"${hash('a')}"},
            {"enabled":false,"priority":1,"sha256":"${hash('b')}"},
            {"enabled":true,"priority":1,"gameBananaId":12},
            {"enabled":true,"priority":2,"sha256":"${hash('c')}"}]}]"""
        assertEquals(
            listOf(ProfileMember.Files(SetMatcher.Hash(hash('a'))), ProfileMember.Submission(12), ProfileMember.Files(SetMatcher.Hash(hash('c')))),
            GrimoireReader.read(metadata = null, profiles.encodeToByteArray(), autoexec = null).profiles.single().members,
            "addons1 numbers from 1 again, so the list is the order",
        )
    }

    @Test
    fun itsAutoexecSectionsAreCarriedAsWrittenAndUnreadableFilesCountAsAbsent() {
        val autoexec = "// === AUTOEXEC COMMANDS (Mod Manager) ===\nfps_max 240\n// === END COMMANDS ===\n\n// === CROSSHAIR SETTINGS (Mod Manager) ===\n// Preset: Dot\ncitadel_crosshair_pip_gap 5\n// === END CROSSHAIR ===\n"
        val library = GrimoireReader.read("{".encodeToByteArray(), "{}".encodeToByteArray(), autoexec.encodeToByteArray())
        assertEquals(AutoexecLines(listOf("// Preset: Dot", "citadel_crosshair_pip_gap 5"), listOf("fps_max 240")), library.autoexec)
        assertEquals(locker, library.mods)
        assertEquals(emptyList(), library.profiles)
    }
}
