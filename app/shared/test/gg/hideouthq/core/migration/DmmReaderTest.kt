package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecLines
import gg.hideouthq.core.crosshair.CrosshairLines
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.resourceBytes
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class DmmReaderTest {
    private val addonsManifest = CitadelPath("addons/.dmm.json")
    private val realManifests = mapOf(addonsManifest to resourceBytes("dmm/addons-manifest.json"))
    private val live = DmmReader.read(resourceBytes("dmm/state-v32.json"), realManifests, resourceBytes("dmm/autoexec.cfg"))

    private fun ForeignLibrary.mod(key: String): ForeignMod = mods.single { it.key == ModKey(key) }

    private fun at(path: String, original: String? = null, vararg sizes: Long) = SetMatcher.At(CitadelPath(path), original, sizes.toSet())

    private fun parked(key: String, folder: String = "addons") = SetMatcher.Prefixed(CitadelPath(folder), "${key}_")

    private fun file(kind: SubmissionKind, id: Long, fileId: Long, md5: String?) = ModIdentity.File(SubmissionIdentity(kind, id, fileId, md5))

    private fun state(version: Int, state: String): ByteArray =
        buildJsonObject { put("local-config", JsonPrimitive("""{"state":$state,"version":$version}""")) }.toString().encodeToByteArray()

    private val dmmCrosshair = """{"gap":-6,"width":0,"height":5,"pipOpacity":1,"dotOpacity":0,"dotOutlineOpacity":0,"color":{"r":115,"g":255,"b":0},"pipBorder":false,"pipGapStatic":false,"hero":"Default"}"""

    private val dmmCrosshairLines = listOf(
        "citadel_crosshair_pip_gap_static \"false\"",
        "citadel_crosshair_pip_width \"0\"",
        "citadel_crosshair_pip_height \"5\"",
        "citadel_crosshair_pip_gap \"-6\"",
        "citadel_crosshair_pip_opacity \"1\"",
        "citadel_crosshair_pip_outline_border \"0\"",
        "citadel_crosshair_dot_opacity \"0\"",
        "citadel_crosshair_dot_outline_opacity \"0\"",
        "citadel_crosshair_color_r \"115\"",
        "citadel_crosshair_color_g \"255\"",
        "citadel_crosshair_color_b \"0\"",
    )

    @Test
    fun aPersistVersion42StatePassesOnItsModsProfilesAndAppliedCrosshair() {
        val ranked = "addons/profile_1793707836444_4d9mh1w48_ranked"
        val library = DmmReader.read(
            resourceBytes("dmm/state-v42.json"),
            mapOf(addonsManifest to resourceBytes("dmm/addons-manifest-v42.json"), CitadelPath("$ranked/.dmm.json") to resourceBytes("dmm/ranked-manifest-v42.json")),
            null,
        )
        assertEquals(setOf("724744", "735850"), library.mods.map { it.key.value }.toSet())
        assertEquals(file(SubmissionKind.Mod, 724744, 1875162, "3579a68c7c5c541274b1ed6328650f39"), library.mod("724744").identity)
        assertEquals(file(SubmissionKind.Mod, 735850, 1875151, "24c8409cbe9971aa71b4405a97c3f6b7"), library.mod("735850").identity)
        assertEquals("Blue Chonky Whale over Rem", library.mod("735850").name)
        assertEquals(ProfileKey("profile_1793707836444_4d9mh1w48"), library.activeProfile)
        assertEquals(listOf("Default Profile" to CitadelPath.Addons, "Ranked" to CitadelPath(ranked)), library.profiles.map { it.name to it.folder })
        assertEquals(listOf(3, 1), library.profiles.map { it.members.size }, "Classic and Blue Chonky's two files, then Ranked's Classic")
        assertEquals(AutoexecLines(dmmCrosshairLines, emptyList()), library.autoexec)
    }

    @Test
    fun theAppliedCrosshairCountsUnlessDmmHasCrosshairsOffOrAnOlderSectionSpeaksFirst() {
        fun crosshair(fields: String, autoexec: String? = null) =
            DmmReader.read(state(42, """{"profiles":{},"localMods":[]$fields}"""), emptyMap(), autoexec?.encodeToByteArray()).autoexec.crosshair
        assertEquals(dmmCrosshairLines, crosshair(""","crosshairsEnabled":true,"activeCrosshair":$dmmCrosshair"""))
        assertEquals(dmmCrosshairLines, crosshair(""","activeCrosshair":$dmmCrosshair"""), "a state without the switch has DMM's default, on")
        assertEquals(emptyList(), crosshair(""","crosshairsEnabled":false,"activeCrosshair":$dmmCrosshair"""), "DMM took a crosshair it has off back out of the game")
        assertEquals(emptyList(), crosshair(""","crosshairsEnabled":true,"activeCrosshair":null"""))
        assertEquals(emptyList(), crosshair(""","crosshairsEnabled":true,"activeCrosshair":{"gap":"wide"}"""))
        val section = "// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===\ncitadel_crosshair_pip_gap \"2\"\n// === End Crosshair Settings ===\n"
        assertEquals(listOf("citadel_crosshair_pip_gap \"2\""), crosshair(""","crosshairsEnabled":true,"activeCrosshair":$dmmCrosshair""", section), "an older DMM's section is what the game runs")
    }

    @Test
    fun theLiveStateAndManifestPassOnEveryClaimAsDmmWroteIt() {
        assertEquals(24, live.mods.size)
        assertEquals(
            ForeignMod(
                ModKey("691863"), "Yamato remodel", file(SubmissionKind.Mod, 691863, 1831456, "2db13168ffd17000c1550dc306a9e893"),
                listOf(at("addons/pak10_dir.vpk", "pak50_dir.vpk", 589428847), parked("691863")), position = 12,
            ),
            live.mod("691863"),
        )
        assertEquals(
            listOf(
                at("addons/pak06_dir.vpk", "pak07_dir.vpk", 76305073, 60302), at("addons/pak07_dir.vpk", "pak07_dir.vpk", 76305073, 60302),
                at("addons/pak08_dir.vpk", "pak08_dir.vpk", 38393143), at("addons/pak09_dir.vpk", "pak09_dir.vpk", 9765375),
                at("addons/pak10_dir.vpk", "pak10_dir.vpk", 7591382), at("addons/pak11_dir.vpk", "pak11_dir.vpk", 6565),
                at("addons/pak12_dir.vpk", "pak12_dir.vpk", 20549056), parked("631628"),
            ),
            live.mod("631628").matchers,
            "stale claims on Hideout's files and a missing pak12 are passed on, to be disproved by the game folder",
        )
        assertEquals(listOf("631628", "691863"), live.mods.filter { mod -> mod.matchers.any { (it as? SetMatcher.At)?.path == CitadelPath("addons/pak10_dir.vpk") } }.map { it.key.value })
        assertEquals(listOf(parked("637275")), live.mod("637275").matchers, "an entry whose lists lost its files still finds them by prefix")
        assertEquals(
            listOf(at("addons/721342_奶蛙v59_音效含logo_pak01_dir.vpk", "奶蛙v59_音效含logo_pak01_dir.vpk", 3013435), at("addons/721342_奶龙v59_模型本体_pak01_dir.vpk", "奶龙v59_模型本体_pak01_dir.vpk", 133303229), parked("721342")),
            live.mod("721342").matchers,
        )
        assertEquals("奶能 naineng（dynamo）", live.mod("721342").name)
        assertEquals(file(SubmissionKind.Mod, 630975, 1831991, "a4877181b759848796be4db8172b99a2"), live.mod("630975").identity)
        assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 626469)), live.mod("626469").identity)
        assertEquals(listOf(parked("670042")), live.mod("670042").matchers, "a mod only the state knows is found by prefix alone")
    }

    @Test
    fun theDefaultProfileIsLiveAndHasWhatItsManifestHasOn() {
        val profile = live.profiles.single()
        assertEquals(ProfileKey("default"), profile.key)
        assertEquals(ProfileKey("default"), live.activeProfile)
        assertEquals("Default Profile", profile.name)
        assertEquals(CitadelPath.Addons, profile.folder)
        val members = profile.members.map { (it as ProfileMember.Files).matcher as SetMatcher.At }
        assertEquals(13, members.size)
        assertEquals(
            setOf("626469", "631628", "656621", "673959", "691863", "696085"),
            live.mods.filter { mod -> mod.matchers.any { it in members } }.map { it.key.value }.toSet(),
        )
    }

    @Test
    fun itsCrosshairSectionAndFootprintComeAlong() {
        assertEquals(11, live.autoexec.crosshair.size)
        assertEquals("citadel_crosshair_color_r \"0\"", live.autoexec.crosshair.first())
        assertEquals(emptyList(), live.autoexec.commands)
        assertEquals(listOf(addonsManifest), live.footprint.manifests)
    }

    @Test
    fun theStaleNativeStateFillsInFileIdsFromWhicheverRecordKnowsThem() {
        val native = DmmReader.read(resourceBytes("dmm/state-v24.json"), realManifests, autoexec = null)
        assertEquals(28, native.mods.size)
        assertEquals(file(SubmissionKind.Mod, 626469, 1648840, "1b6e4c6be05becbe97c6c0c460ffba70"), native.mod("626469").identity, "by the installed archive's name, from localMods")
        assertEquals(file(SubmissionKind.Mod, 623518, 1747474, "f713f47714a3b7f485c94a602b93345d"), native.mod("623518").identity, "the active profile's record first")
        assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 631628)), native.mod("631628").identity, "seven archives name no one file")
        assertEquals(listOf(parked("623518")), native.mod("623518").matchers)
        assertEquals("Ace (One Piece) - Infernus", native.mod("626469").name)
        val manifestOnly = native.mods.drop(16)
        assertEquals(
            listOf("630975", "673959", "691863", "696085", "708938", "713044", "717740", "721285", "721342", "721650", "721908", "722139"),
            manifestOnly.map { it.key.value },
        )
        assertEquals(listOf<String?>(null), manifestOnly.map { it.name }.distinct())
        assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 722139)), native.mod("722139").identity)
        assertEquals(emptyList(), native.autoexec.commands)
        assertEquals(CrosshairLines.read(live.autoexec.crosshair).crosshair, CrosshairLines.read(native.autoexec.crosshair).crosshair, "with no section the state's active crosshair is the one that section held")
    }

    @Test
    fun olderPersistVersionsKeepTheirSelectedDownloadAndSoundIds() {
        val mods = """{"localMods":[
            {"remoteId":"123","name":"Old file","selectedDownload":{"url":"https://gamebanana.com/dl/555","md5Checksum":"ABCDEF0123456789ABCDEF0123456789"}},
            {"remoteId":"456","isAudio":true},
            {"remoteId":"snd-789","selectedDownloads":[{"url":"gamebanana-file://snd-789/790","md5Checksum":"not an md5"}]},
            {"remoteId":"321","selectedDownloads":[{"url":"https://gamebanana.com/dl/1"},{"url":"https://gamebanana.com/dl/2","name":"b.zip"}],"activeVariantArchive":"b.zip"},
            {"remoteId":"654","downloads":[{"url":"gamebanana-file://654/9","name":"c.zip"}],"installedFileTree":{"files":[{"name":"pak01_dir.vpk","size":3,"archive_name":"c.zip"}]}},
            {"remoteId":"wip-5"},
            {"remoteId":"local-0f8fad5b-d9cb-469f-a165-70867728950e"}
        ],"profiles":{},"activeProfileId":"default"}"""
        val old = DmmReader.read(state(10, mods), emptyMap(), autoexec = null)
        assertEquals(file(SubmissionKind.Mod, 123, 555, "abcdef0123456789abcdef0123456789"), old.mod("123").identity)
        assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Sound, 456)), old.mod("456").identity)
        assertEquals(file(SubmissionKind.Sound, 789, 790, md5 = null), old.mod("snd-789").identity)
        assertEquals(file(SubmissionKind.Mod, 321, 2, md5 = null), old.mod("321").identity)
        assertEquals(file(SubmissionKind.Mod, 654, 9, md5 = null), old.mod("654").identity)
        assertEquals(ModIdentity.None, old.mod("wip-5").identity)
        assertEquals(ModIdentity.None, old.mod("local-0f8fad5b-d9cb-469f-a165-70867728950e").identity)
        assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 456)), DmmReader.read(state(26, mods), emptyMap(), null).mod("456").identity)
    }

    @Test
    fun profilesShardsAndManifestVersionsPlaceEveryClaim() {
        val profiles = """{"localMods":[{"remoteId":"2"}],"activeProfileId":"profile_1_x","profiles":{
            "default":{"id":"default","name":"Default","isDefault":true,"folderName":null,"mods":[{"remoteId":"1"}]},
            "profile_1_x":{"id":"profile_1_x","name":"Ranked","folderName":"profile_1_x","mods":[{"remoteId":"2"}]},
            "profile_bad":{"id":"profile_bad","name":"Bad","folderName":"../escape","mods":[]}}}"""
        val manifests = mapOf(
            addonsManifest to """{"version":1,"mods":{
                "1":{"enabled":true,"order":0,"currentVpks":["pak01_dir.vpk"],"disabledVpks":[],"originalVpkNames":["cool.vpk"]},
                "3":{"enabled":true,"shard":3,"currentVpks":["pak01_dir.vpk"],"originalVpkNames":["a.vpk","b.vpk"]},
                "4":{"enabled":true,"shard":11,"currentVpks":["pak02_dir.vpk"]},
                "5":{"enabled":false,"order":4,"currentVpks":["pak09_dir.vpk"],"disabledVpks":["5_x.vpk","../y.vpk","sub/z.vpk"]}}}""",
            CitadelPath("addons/profile_1_x/.dmm.json") to """{"version":3,"mods":{"2":{"enabled":true,"shard":2,"currentVpks":["pak01_dir.vpk"],"originalVpkNames":["sub/x.vpk"]}}}""",
            CitadelPath("addons/profile_2_y/.dmm.json") to """{"version":4,"mods":{"6":{"enabled":true,"currentVpks":["pak01_dir.vpk"]}}}""",
            CitadelPath("addons/server_9/.dmm.json") to "{",
        ).mapValues { it.value.encodeToByteArray() }
        val library = DmmReader.read(state(32, profiles), manifests, autoexec = null)
        assertEquals(listOf("2", "1", "3", "4", "5"), library.mods.map { it.key.value })
        assertEquals(listOf(at("addons2/profile_1_x/pak01_dir.vpk"), parked("2", "addons/profile_1_x")), library.mod("2").matchers, "an original name is a name, never a path")
        assertEquals(listOf(at("addons/pak01_dir.vpk", "cool.vpk"), parked("1")), library.mod("1").matchers)
        assertEquals(listOf(at("addons3/pak01_dir.vpk"), parked("3")), library.mod("3").matchers, "two original names for one file align with neither")
        assertEquals(listOf(parked("4")), library.mod("4").matchers, "no shard 11 exists")
        assertEquals(listOf(at("addons/5_x.vpk"), parked("5")), library.mod("5").matchers, "a parked name is a name, never a path")
        assertEquals(4, library.mod("5").position)
        assertEquals(
            listOf(
                ForeignProfile(ProfileKey("default"), "Default", CitadelPath.Addons, listOf(at("addons/pak01_dir.vpk", "cool.vpk"), at("addons3/pak01_dir.vpk")).map(ProfileMember::Files)),
                ForeignProfile(ProfileKey("profile_1_x"), "Ranked", CitadelPath("addons/profile_1_x"), listOf(ProfileMember.Files(at("addons2/profile_1_x/pak01_dir.vpk")))),
            ),
            library.profiles,
        )
        assertEquals(ProfileKey("profile_1_x"), library.activeProfile)
        assertEquals(listOf(addonsManifest, CitadelPath("addons/profile_1_x/.dmm.json")), library.footprint.manifests)
    }

    @Test
    fun aProfilesMembersComeLowestFirstByItsOwnInstallOrderElseItsManifestsOrderAndThoseWithNeitherLast() {
        val profiles = """{"activeProfileId":"default","profiles":{
            "default":{"id":"default","name":"Default","folderName":null,"mods":[]},
            "profile_1_x":{"id":"profile_1_x","name":"Ranked","folderName":"profile_1_x","mods":[{"remoteId":"3","installOrder":0},{"remoteId":"1","installOrder":2},{"remoteId":"2"}]}}}"""
        val manifest = """{"version":3,"mods":{
            "1":{"enabled":true,"order":0,"currentVpks":["pak01_dir.vpk"]},
            "2":{"enabled":true,"order":1,"currentVpks":["pak02_dir.vpk"]},
            "3":{"enabled":true,"order":5,"currentVpks":["pak03_dir.vpk"]},
            "4":{"enabled":true,"currentVpks":["pak04_dir.vpk"]},
            "5":{"enabled":true,"order":3,"currentVpks":["pak05_dir.vpk"]},
            "6":{"enabled":false,"order":-1,"currentVpks":["pak06_dir.vpk"]}}}"""
        val library = DmmReader.read(state(32, profiles), mapOf(CitadelPath("addons/profile_1_x/.dmm.json") to manifest.encodeToByteArray()), autoexec = null)
        assertEquals(
            listOf("3", "2", "1", "5", "4").map { ProfileMember.Files(at("addons/profile_1_x/pak0${it}_dir.vpk")) },
            library.profiles.single { it.key == ProfileKey("profile_1_x") }.members,
        )
    }

    @Test
    fun withoutAReadableStateTheManifestsStillSpeak() {
        listOf(null, "{".encodeToByteArray(), """{"local-config":"not json"}""".encodeToByteArray(), """{"other":1}""".encodeToByteArray()).forEach { state ->
            val library = DmmReader.read(state, realManifests, autoexec = null)
            assertEquals(23, library.mods.size)
            assertEquals(listOf<String?>(null), library.mods.map { it.name }.distinct())
            assertEquals(ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 691863)), library.mod("691863").identity)
            assertEquals(emptyList(), library.profiles)
            assertEquals(null, library.activeProfile)
        }
    }
}
