package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecLines
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BundleReaderTest {
    private val qolHash = "a".repeat(64)
    private val skinHash = "b".repeat(64)

    private fun read(json: String): ForeignLibrary = assertIs<BundleRead.Read>(BundleReader.read(json.encodeToByteArray())).library

    private fun document(mods: String, profiles: String = "[]", crosshairs: String = "[]") =
        """{"format":"deadlock-mod-interchange","version":1,"source":{"manager":"deadlock-mod-manager"},"mods":$mods,"profiles":$profiles,"crosshairs":$crosshairs,"warnings":[]}"""

    @Test
    fun aDmmExportReadsAsItsModsProfilesAndActiveCrosshair() {
        val library = read(
            document(
                mods = """[
                  {"key":"gamebanana:mod:650634","name":"QoL","enabled":true,"order":0,
                   "origin":{"provider":"gamebanana","submissionType":"mod","submissionId":"650634","fileId":1720039,"fileName":"qol_313.zip"},
                   "files":[{"name":"qol_313_dir.vpk","path":"files/0000-qol_lock/qol_313_dir.vpk","sha256":"${qolHash.uppercase()}","size":1667455,"selected":true}],
                   "extensions":{"deadlock-mod-manager":{"modId":"650634"}}},
                  {"key":"local:3f2b","name":"My skin","enabled":false,"order":1,"origin":{"provider":"local","localId":"3f2b"},
                   "files":[{"name":"skin_dir.vpk","path":"files/0001-my_skin/skin_dir.vpk","sha256":"$skinHash","size":10}]}
                ]""",
                profiles = """[{"key":"profile:default","name":"Default","active":true,"mods":[{"modKey":"gamebanana:mod:650634","enabled":true,"order":0},{"modKey":"local:3f2b","enabled":false,"order":1}],"crosshairKey":null,"autoexec":null}]""",
                crosshairs = """[{"key":"crosshair:dmm:0","name":"Active crosshair","active":true,"convars":{"citadel_crosshair_pip_gap":"-7","citadel_crosshair_pip_border":"false","citadel_crosshair_color_g":255}},
                                 {"key":"crosshair:dmm:1","name":"Older","active":false,"convars":{"citadel_crosshair_pip_gap":"4"}}]""",
            ),
        )
        val qolFile = BundledFile("files/0000-qol_lock/qol_313_dir.vpk", "qol_313_dir.vpk", FileImage(1667455, qolHash))
        val skinFile = BundledFile("files/0001-my_skin/skin_dir.vpk", "skin_dir.vpk", FileImage(10, skinHash))
        assertEquals(
            listOf(
                ForeignMod(
                    ModKey("gamebanana:mod:650634"), "QoL", ModIdentity.File(SubmissionIdentity(SubmissionKind.Mod, 650634, 1720039, fileMd5 = null)),
                    listOf(SetMatcher.Hash(qolHash), SetMatcher.Bundled(listOf(qolFile), enabled = true)), position = 0,
                ),
                ForeignMod(ModKey("local:3f2b"), "My skin", ModIdentity.None, listOf(SetMatcher.Hash(skinHash), SetMatcher.Bundled(listOf(skinFile), enabled = false)), position = 1),
            ),
            library.mods,
        )
        assertEquals(listOf(ForeignProfile(ProfileKey("profile:default"), "Default", folder = null, members = listOf(ProfileMember.Mod(ModKey("gamebanana:mod:650634"))))), library.profiles)
        assertEquals(ProfileKey("profile:default"), library.activeProfile)
        assertEquals(
            AutoexecLines(listOf("citadel_crosshair_pip_gap \"-7\"", "citadel_crosshair_pip_border \"false\"", "citadel_crosshair_color_g \"255\""), commands = emptyList()),
            library.autoexec,
        )
        assertEquals(Footprint.None, library.footprint)
    }

    @Test
    fun aProfilesModsComeLowestOrderFirstAndThoseWithoutOneLast() {
        val mods = (1..4).joinToString(",") { """{"key":"local:$it","name":"M$it","enabled":true,"origin":{"provider":"local"},"files":[]}""" }
        val profiles = """[{"key":"profile:a","name":"Ranked","active":false,"mods":[
            {"modKey":"local:1","enabled":true,"order":2},{"modKey":"local:2","enabled":true},{"modKey":"local:3","enabled":true,"order":0},{"modKey":"local:4","enabled":true,"order":1.5}]}]"""
        assertEquals(listOf("local:3", "local:4", "local:1", "local:2").map { ProfileMember.Mod(ModKey(it)) }, read(document("[$mods]", profiles)).profiles.single().members)
    }

    @Test
    fun aGrimoireExportsNumericIdsSoundsVariantsAndProfileCrosshairRead() {
        val library = read(
            document(
                mods = """[{"key":"gamebanana:sound:4321","name":"Bell","enabled":true,"order":2.0,
                   "origin":{"provider":"gamebanana","submissionType":"sound","submissionId":4321},
                   "files":[{"name":"pak02_dir.vpk","path":"./files/0000-bell/pak02_dir.vpk","sha256":"$qolHash","size":5,"selected":true},
                            {"name":"pak03_dir.vpk","path":"files/0000-bell/pak03_dir.vpk","selected":false},
                            {"name":"pak04_dir.vpk","path":"files\\0000-bell\\pak04_dir.vpk"}],
                   "extensions":{"grimoire":{"metaKeys":["pak02_dir.vpk"]}}}]""",
                profiles = """[{"key":"profile:a","name":"Ranked","active":false,"mods":[{"modKey":"gamebanana:sound:4321","enabled":true,"order":0}],"crosshairKey":null,"autoexec":null},
                               {"key":"profile:b","name":"Casual","active":true,"mods":[],"crosshairKey":"crosshair:profile:b","autoexec":["fps_max 240","","bind x \"y\"\nquit"]}]""",
                crosshairs = """[{"key":"crosshair:preset:p1","name":"Preset","active":true,"convars":{"citadel_crosshair_pip_gap":"1"}},
                                 {"key":"crosshair:profile:b","name":"Casual","active":false,"convars":{"citadel_crosshair_dot_size":4,"citadel_crosshair_disable_hero_specific_crosshairs":false,"fps_max":"0","citadel_crosshair_pip_gap":"1\" ; quit"}}]""",
            ),
        )
        val mod = library.mods.single()
        assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Sound, 4321)), mod.identity)
        assertEquals(2, mod.position)
        assertEquals(
            listOf(
                SetMatcher.Hash(qolHash),
                SetMatcher.Bundled(
                    listOf(BundledFile("files/0000-bell/pak02_dir.vpk", "pak02_dir.vpk", FileImage(5, qolHash)), BundledFile("files/0000-bell/pak04_dir.vpk", "pak04_dir.vpk", image = null)),
                    enabled = true,
                ),
            ),
            mod.matchers,
        )
        assertEquals(listOf("Ranked", "Casual"), library.profiles.map { it.name })
        assertEquals(ProfileKey("profile:b"), library.activeProfile)
        assertEquals(
            AutoexecLines(listOf("citadel_crosshair_dot_size \"4\"", "citadel_crosshair_disable_hero_specific_crosshairs \"false\""), listOf("fps_max 240")),
            library.autoexec,
        )
    }

    @Test
    fun anEntryThatIsBrokenCostsOnlyItself() {
        val file = """{"name":"x_dir.vpk","path":"files/x_dir.vpk"}"""
        val library = read(
            document(
                mods = """[
                  {"key":"a","name":"First","origin":{"provider":"local"},"files":[$file]},
                  {"key":"a","name":"Second","origin":{"provider":"local"},"files":[$file]},
                  {"key":"  ","name":"Blank key","origin":{"provider":"local"},"files":[$file]},
                  {"key":"b","name":"Unknown provider","origin":{"provider":"nexus"},"files":[$file]},
                  {"key":"b","name":"Known","origin":{"provider":"gamebanana","submissionId":"12"},"files":[$file]},
                  {"key":"c","origin":{"provider":"gamebanana","submissionId":"0"},"files":[$file]},
                  {"key":"d","origin":{"provider":"gamebanana","submissionId":"-5"},"files":[$file]},
                  {"key":"e","origin":{"provider":"gamebanana","submissionId":"12a"},"files":[$file]},
                  {"key":"f","origin":"gamebanana","files":[$file]},
                  {"key":"g","origin":{"provider":"local"},"files":[
                    {"name":"a.vpk","path":"/etc/a.vpk"},{"name":"b.vpk","path":"C:/b.vpk"},{"name":"c.vpk","path":"files/../../c.vpk"},
                    {"name":"d.vpk","path":"\\\\server\\d.vpk"},{"name":"","path":"files/e.vpk"},{"name":"f.vpk"},{"name":"g.vpk","path":"files//g.vpk","size":-1,"sha256":"xyz"}]},
                  "not an object"
                ]""",
                profiles = """[{"key":"p","name":"P","mods":[{"modKey":"a","enabled":true},{"modKey":"gone","enabled":true},{"modKey":"b","enabled":"true"}]},{"key":"p","name":"Again"},{"name":"No key"}]""",
            ),
        )
        assertEquals(listOf("a", "b", "g"), library.mods.map { it.key.value })
        assertEquals("First", library.mods[0].name)
        assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 12)), library.mods[1].identity)
        assertEquals(0, library.mods[1].position, "no order is order 0")
        assertEquals(listOf(SetMatcher.Bundled(listOf(BundledFile("files/g.vpk", "g.vpk", image = null)), enabled = false)), library.mods[2].matchers)
        assertEquals(listOf(ForeignProfile(ProfileKey("p"), "P", folder = null, members = listOf(ProfileMember.Mod(ModKey("a"))))), library.profiles)
        assertEquals(null, library.activeProfile)
        assertEquals(AutoexecLines(emptyList(), emptyList()), library.autoexec)
    }

    @Test
    fun onlyAnInterchangeVersionOneDocumentIsABundle() {
        val refused = listOf("not json", "[]", """{"format":"mod-profile","version":1}""", """{"version":1,"mods":[]}""", """{"format":"deadlock-mod-interchange"}""") +
            listOf("0", "2", "\"1\"", "null", "1.5").map { """{"format":"deadlock-mod-interchange","version":$it}""" }
        refused.forEach { assertEquals(BundleRead.Refused, BundleReader.read(it.encodeToByteArray()), it) }
        assertEquals(emptyList(), read("""{"format":"deadlock-mod-interchange","version":1}""").mods)
        assertEquals(emptyList(), read("\uFEFF" + """{"format":"deadlock-mod-interchange","version":1.0,"mods":"none"}""").mods)
    }
}
