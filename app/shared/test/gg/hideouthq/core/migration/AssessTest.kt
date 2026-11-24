package gg.hideouthq.core.migration

import gg.hideouthq.core.autoexec.AutoexecLines
import gg.hideouthq.core.data.BundleVpk
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ClaimedMod
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FilePrint
import gg.hideouthq.core.library.ForeignRoot
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Imprint
import gg.hideouthq.core.library.IncomingFile
import gg.hideouthq.core.library.IncomingMember
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LoadPosition
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModIdentity
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.core.library.resourceBytes
import gg.hideouthq.preview.sha256Hex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AssessTest {
    private val root = GameRoot("/games/Deadlock")

    private fun image(tag: String, size: Long = 100) = FileImage(size, sha256Hex(tag.encodeToByteArray()))

    private fun set(path: String, position: LoadPosition? = null, size: Long = 100, tag: String = path, chunks: Int = 0, imprint: Imprint? = null): FoundSet {
        val directory = CitadelPath(path)
        val folder = checkNotNull(directory.parent)
        val files = listOf(IncomingFile(directory, VpkPart.Directory, image(tag, size))) +
            (0 until chunks).map { IncomingFile(folder.resolve(directory.name.replace("_dir.vpk", "_00$it.vpk")), VpkPart.Chunk(it), image("$tag-$it")) }
        return FoundSet(folder, checkNotNull(ForeignRoot.of(folder)), files, position, imprint)
    }

    private fun survey(vararg sets: FoundSet, mounts: List<String> = listOf("addons")) =
        GameSurvey(root, mounts.map(::CitadelPath), sets.toList(), emptyMap(), emptyList(), null, null)

    private fun held(id: String, path: String, tag: String, size: Long = 100) = ManifestRecord(
        id, root, "Hideout's $id", Placement.On(Slot(path.substring(10, 12).toInt())),
        listOf(InstalledSet(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 9, 90, null)), null, null, SetOrigin.Imported(image("a")), null, path.substringAfterLast('/'),
            listOf(SetFile(VpkPart.Directory, FileImage(size, image(tag, size).sha256))))),
    )

    private fun Assessment.prospect(path: String): Prospect = prospects.single { prospect -> prospect.sets.any { set -> set.files.first().path.value == path } }

    private fun Prospect.paths(): List<String> = sets.map { it.files.first().path.value }

    @Test
    fun aDmmImportBringsEveryMountedSetOneRecordPerModAndItsParkedFilesOff() {
        val dmm = DmmReader.read(resourceBytes("dmm/state-v32.json"), mapOf(CitadelPath("addons/.dmm.json") to resourceBytes("dmm/addons-manifest.json")), resourceBytes("dmm/autoexec.cfg"))
        val hideouts = held("h06", "addons/pak06_dir.vpk", "toon", 190_289_674)
        val assessment = assess(
            dmm,
            survey(
                set("addons/pak01_dir.vpk", LoadPosition(0, 1), 55_188_361),
                set("addons/pak03_dir.vpk", LoadPosition(0, 3), 119_561_813),
                set("addons/pak04_dir.vpk", LoadPosition(0, 4), 120_763_362),
                set("addons/pak07_dir.vpk", LoadPosition(0, 7), 60_302),
                set("addons/pak10_dir.vpk", LoadPosition(0, 10), 589_428_847),
                set("addons/pak11_dir.vpk", LoadPosition(0, 11), 6_565),
                set("addons/637275_Percentage Rename.vpk", size = 181_511),
                set("addons/637275_pak06_dir.vpk", size = 181_095),
                set("addons/my_skin.vpk"),
                set("addons/profile_1726_ranked/pak01_dir.vpk", size = 119_561_813, tag = "addons/pak03_dir.vpk"),
                set("addons/profile_1726_ranked/pak02_dir.vpk", size = 190_289_674, tag = "toon"),
                set("addons/profile_1726_ranked/pak03_dir.vpk", size = 4_000),
            ),
            listOf(hideouts),
        )

        val on = assessment.prospects.filter { it.position != null }
        assertEquals(
            listOf(listOf("addons/pak01_dir.vpk"), listOf("addons/pak03_dir.vpk", "addons/pak04_dir.vpk"), listOf("addons/pak07_dir.vpk", "addons/pak11_dir.vpk"), listOf("addons/pak10_dir.vpk")),
            on.map { it.paths() },
            "every set that loads comes, one record per mod even with others between its sets, in engine order",
        )
        assertEquals("Yamato remodel", on[3].mod.title)
        assertEquals(listOf("pak39_dir.vpk", "pak41_dir.vpk"), on[1].sets.map { it.componentKey }, "each set keyed by the name it had in its archive")
        assertIs<ModIdentity.File>(on[3].mod.claim, "pak10 goes to the mod whose recorded size it has, not the stale claim")
        assertEquals("691863", (on[3].mod.claim as ModIdentity.File).identity.submissionId.toString())

        val parked = assessment.prospect("addons/637275_pak06_dir.vpk")
        assertEquals(null, parked.position)
        assertEquals(setOf("addons/637275_Percentage Rename.vpk", "addons/637275_pak06_dir.vpk"), parked.paths().toSet(), "a mod's parked files are one off record")
        assertEquals(setOf("Percentage Rename.vpk", "pak06_dir.vpk"), parked.sets.map { it.componentKey }.toSet())
        val staying = listOf("addons/my_skin.vpk", "addons/profile_1726_ranked/pak01_dir.vpk", "addons/profile_1726_ranked/pak02_dir.vpk")
        assertEquals(emptyList(), assessment.prospects.flatMap { it.paths() }.filter { it in staying }, "a copy of a set that comes, a loose file nothing claims and a copy of Hideout's own stay")
        assertEquals(listOf(CitadelPath("addons/.dmm.json")), assessment.manifests)
        assertEquals(11, assessment.autoexec.crosshair.size)
    }

    @Test
    fun aGrimoireImportBringsLockerAndGlobalModsAboveAddonsAndItsParkedOnesOff() {
        val grimoire = GrimoireReader.read(
            metadata = null,
            profiles = """[{"id":"p1","name":"Fun","mods":[{"fileName":"x","enabled":true,"sha256":"${image("addons/pak01_dir.vpk").sha256}"},{"fileName":"y","enabled":true,"sha256":"${image("grimoire/pak05_dir.vpk").sha256}"}]}]""".encodeToByteArray(),
            autoexec = null,
        )
        val assessment = assess(
            grimoire,
            survey(
                set("grimoire/pak01_dir.vpk", LoadPosition(0, 1)), set("grimoire/pak02_dir.vpk", LoadPosition(0, 2)),
                set("grimoire/pak03_dir.vpk", LoadPosition(0, 3)), set("grimoire/pak04_dir.vpk", LoadPosition(0, 4)),
                set("grimoire/pak05_dir.vpk", LoadPosition(0, 5), chunks = 2),
                set("addons/pak01_dir.vpk", LoadPosition(1, 1)),
                set("addons1/pak01_dir.vpk", LoadPosition(2, 1)),
                set("addons/.disabled/skin_a3f1_dir.vpk"),
                mounts = listOf("grimoire", "addons", "addons1"),
            ),
            emptyList(),
        )
        assertEquals(
            listOf("grimoire/pak01_dir.vpk", "grimoire/pak02_dir.vpk", "grimoire/pak03_dir.vpk", "grimoire/pak04_dir.vpk", "grimoire/pak05_dir.vpk", "addons/pak01_dir.vpk", "addons1/pak01_dir.vpk", "addons/.disabled/skin_a3f1_dir.vpk"),
            assessment.prospects.map { it.sets.single().files.first().path.value },
        )
        assertEquals(listOf("Grimoire Locker · hero cards", "Grimoire Locker · hero sounds", "Grimoire Locker · hero colors", "Grimoire Locker · trippy skins", null, null, null, null), assessment.prospects.map { it.mod.title })
        assertEquals(3, assessment.prospect("grimoire/pak05_dir.vpk").sets.single().files.size, "a set comes with its chunks")
        assertEquals(null, assessment.prospect("addons/.disabled/skin_a3f1_dir.vpk").position, "what Grimoire parks comes in off")
        assertEquals(listOf(listOf(IncomingMember.Adopted(5), IncomingMember.Adopted(4))), assessment.profiles.map { it.members })
    }

    @Test
    fun aBundleAdoptsWhatIsInAddonsCopiesTheRestAndLeavesWhatIsIncomplete() {
        val inGame = image("addons/pak05_dir.vpk")
        val heldImage = image("held", 50)
        val manifest = """{"format":"deadlock-mod-interchange","version":1,"mods":[
          {"key":"gamebanana:mod:1","name":"In game","enabled":true,"order":0,"origin":{"provider":"gamebanana","submissionId":"1","fileId":10},
           "files":[{"name":"a_dir.vpk","path":"files/0000-a/a_dir.vpk","sha256":"${inGame.sha256}","size":100}]},
          {"key":"local:b","name":"Copied","enabled":false,"order":1,"origin":{"provider":"local"},
           "files":[{"name":"b_dir.vpk","path":"files/0001-b/b_dir.vpk","sha256":"${"b".repeat(64)}","size":10},{"name":"b_000.vpk","path":"files/0001-b/b_000.vpk"}]},
          {"key":"local:c","name":"Broken","enabled":true,"order":2,"origin":{"provider":"local"},
           "files":[{"name":"c_dir.vpk","path":"files/0002-c/c_dir.vpk","sha256":"${"c".repeat(64)}","size":10}]},
          {"key":"local:d","name":"Hideout has it","enabled":true,"order":3,"origin":{"provider":"local"},
           "files":[{"name":"d_dir.vpk","path":"files/0003-d/d_dir.vpk"}]}],
          "profiles":[{"key":"profile:fun","name":"Fun","active":false,"mods":[{"modKey":"gamebanana:mod:1","enabled":true},{"modKey":"local:b","enabled":true},{"modKey":"local:d","enabled":true}]}]}"""
        val library = assertIs<BundleRead.Read>(BundleReader.read(manifest.encodeToByteArray())).library
        val chunked = VpkRead.Parsed(mapOf(0 to 4L))
        val bundle = mapOf(
            "files/0001-b/b_dir.vpk" to BundleVpk("files/0001-b/b_dir.vpk", "/bundle/files/0001-b/b_dir.vpk", 10, "b".repeat(64), chunked),
            "files/0001-b/b_000.vpk" to BundleVpk("files/0001-b/b_000.vpk", "/bundle/files/0001-b/b_000.vpk", 8, "e".repeat(64), VpkRead.Unreadable("a chunk")),
            "files/0002-c/c_dir.vpk" to BundleVpk("files/0002-c/c_dir.vpk", "/bundle/files/0002-c/c_dir.vpk", 10, "c".repeat(64), chunked),
            "files/0003-d/d_dir.vpk" to BundleVpk("files/0003-d/d_dir.vpk", "/bundle/files/0003-d/d_dir.vpk", 50, heldImage.sha256, VpkRead.Parsed(emptyMap())),
        )
        val assessment = assess(
            library,
            survey(set("addons/pak05_dir.vpk", LoadPosition(0, 5)), set("addons/profile_x/pak01_dir.vpk", tag = "addons/pak05_dir.vpk"), set("addons/pak09_dir.vpk", LoadPosition(0, 9))),
            listOf(held("d", "addons/pak02_dir.vpk", "held", 50)),
            bundle,
        )
        assertEquals(listOf(listOf("addons/pak05_dir.vpk")), assessment.prospects.map { it.paths() }, "a bundle mod whose bytes load in addons is adopted where it is, and nothing else in the game comes")
        assertEquals(
            listOf(CopyProspect(ClaimedMod(ModIdentity.None, "Copied"), "b_dir.vpk", enabled = false, listOf(LocalFile("/bundle/files/0001-b/b_dir.vpk", "b_dir.vpk", 10), LocalFile("/bundle/files/0001-b/b_000.vpk", "b_000.vpk", 8)), sets = 1)),
            assessment.copies,
        )
        assertEquals(listOf(listOf(IncomingMember.Adopted(0), IncomingMember.Copied(0), IncomingMember.Held("d"))), assessment.profiles.map { it.members }, "a copy Hideout holds already is found by its measured bytes, listed or not")
        assertEquals(AutoexecLines(emptyList(), emptyList()), assessment.autoexec)
        assertEquals(emptyList(), assessment.duplicates, "a bundle takes nothing over, so the game's other files stay")
    }

    @Test
    fun aTakenOverSourcesCopiesOfWhatComesOrWhatHideoutHoldsAreToGoAndLooseFilesStay() {
        val assessment = assess(
            DmmReader.read(null, emptyMap(), null),
            survey(
                set("addons/pak01_dir.vpk", tag = "classic", chunks = 1),
                set("addons/profile_x/pak01_dir.vpk", LoadPosition(0, 1), tag = "classic", chunks = 1),
                set("addons/profile_y/pak01_dir.vpk", tag = "classic", chunks = 1),
                set("addons/profile_y/pak02_dir.vpk", tag = "own"),
                set("addons/my_skin.vpk", tag = "loose"),
                set("addons/my_skin_copy.vpk", tag = "loose"),
                mounts = listOf("addons/profile_x"),
            ),
            listOf(held("h", "addons/pak09_dir.vpk", "own")),
        )
        assertEquals(listOf(listOf("addons/profile_x/pak01_dir.vpk")), assessment.prospects.map { it.paths() }, "the copy that loads comes")
        assertEquals(
            setOf("addons/pak01_dir.vpk", "addons/pak01_000.vpk", "addons/profile_y/pak01_dir.vpk", "addons/profile_y/pak01_000.vpk", "addons/profile_y/pak02_dir.vpk"),
            assessment.duplicates.map { it.path.value }.toSet(),
            "every file of each other copy goes, and a loose file stays",
        )
    }

    @Test
    fun aNumberedSetNothingClaimsComesOffWhenTakingOverWouldStartLoadingIt() {
        val assessment = assess(DmmReader.read(null, emptyMap(), null), survey(set("addons/pak07_dir.vpk"), set("addons/my_skin.vpk"), mounts = listOf("addons/profile_x")), emptyList())
        assertEquals(listOf(listOf("addons/pak07_dir.vpk") to null), assessment.prospects.map { it.paths() to it.position }, "Hideout mounts addons, so the file must be one it can turn on and off")
    }

    @Test
    fun aSetItsSourceSaysNothingOfIsWhoItsImprintSays() {
        val imprint = Imprint(ModIdentity.File(SubmissionIdentity(SubmissionKind.Mod, 512_345, 987_654, null)), FilePrint(18_874_368, 0x3B7A2C91))
        val grimoire = GrimoireReader.read(null, null, null)
        val assessment = assess(grimoire, survey(set("addons/pak03_dir.vpk", LoadPosition(0, 3), imprint = imprint)), emptyList())
        assertEquals(listOf(imprint.claim to true), assessment.prospects.map { it.mod.claim to it.mod.selfReported })
        val claimed = ModIdentity.File(SubmissionIdentity(SubmissionKind.Mod, 1, 2, null))
        val recorded = ForeignLibrary(listOf(ForeignMod(ModKey("a"), "Recorded", claimed, listOf(SetMatcher.At(CitadelPath("addons/pak03_dir.vpk"))), null)), emptyList(), null, AutoexecLines(emptyList(), emptyList()), Footprint(emptyList(), emptyList(), emptyList()))
        assertEquals(listOf(claimed to false), assess(recorded, survey(set("addons/pak03_dir.vpk", LoadPosition(0, 3), imprint = imprint)), emptyList()).prospects.map { it.mod.claim to it.mod.selfReported }, "what the source recorded comes first")
    }

    @Test
    fun filesTheSourceNeverListedComeApartNamedByTheManagerThatListsThem() {
        val skyline = image("grimoire/pak05_dir.vpk")
        val grimoire = GrimoireReader.read(
            """{"skyline":{"sha256":"${skyline.sha256}","modName":"Skyline","gameBananaId":700010,"gameBananaFileId":1800010,"sourceSection":"Mod","lastPriority":5}}""".encodeToByteArray(),
            profiles = null,
            autoexec = null,
        )
        val dmm = DmmReader.read(resourceBytes("dmm/state-v42.json"), mapOf(CitadelPath("addons/.dmm.json") to resourceBytes("dmm/addons-manifest-v42.json")), null)
        val game = survey(
            set("grimoire/pak05_dir.vpk", LoadPosition(0, 5)),
            set("grimoire/pak07_dir.vpk", LoadPosition(0, 7)),
            set("addons/pak01_dir.vpk", LoadPosition(1, 1)),
            set("addons/pak02_dir.vpk", LoadPosition(1, 2)),
            set("addons/pak03_dir.vpk", LoadPosition(1, 3)),
            set("addons/pak04_dir.vpk", LoadPosition(1, 4)),
            mounts = listOf("grimoire", "addons"),
        )

        val alone = assess(grimoire, game, emptyList())
        val both = assess(grimoire, game, emptyList(), others = listOf(dmm))
        assertEquals(alone.prospects.flatMap { it.paths() }, both.prospects.flatMap { it.paths() }, "what comes is the source's to say, whoever names it")
        assertEquals(
            listOf(
                Triple("Skyline", "pak05_dir.vpk", true),
                Triple(null, "pak07_dir.vpk", true),
                Triple("Classic Investments", "classicinvestments_dir.vpk", false),
                Triple("Blue Chonky Whale over Rem", "bluefish_rem.vpk", false),
                Triple(null, "pak04_dir.vpk", false),
            ),
            both.prospects.map { Triple(it.mod.title, it.fileName, it.fromSource) },
            "a set in a folder only Grimoire keeps is Grimoire's, those Deadlock Mod Manager lists keep its names, and a loose one is no one's",
        )
        assertEquals(listOf("pak02_dir.vpk", "pak03_dir.vpk").map { "addons/$it" }, both.prospect("addons/pak02_dir.vpk").paths(), "the other manager's mod comes as one, all its sets")
        assertEquals(listOf(null to false), alone.prospects.drop(2).map { it.mod.title to it.fromSource }.distinct(), "with no other manager to ask, Deadlock Mod Manager's files are named by nobody, and never as Grimoire's")
    }
}
