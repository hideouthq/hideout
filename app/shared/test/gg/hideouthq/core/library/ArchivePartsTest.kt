package gg.hideouthq.core.library

import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeGameLocator
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArchivePartsTest {
    private val root = GameRoot("/games/Deadlock")
    private val mod = SubmissionRef(SubmissionKind.Mod, 685_287)
    private val main = setOf(ProfileId("main"))

    private fun remote(id: Long, downloads: Long = 10, name: String = "parts_$id.zip") =
        RemoteFile(id, name, 1_000, null, null, downloads, addedAt = 1_777_795_519, downloadUrl = "https://gamebanana.com/dl/$id")

    private inner class World {
        val files = InMemoryGameFiles().apply { putBytes(root, InMemoryGameFiles.GameInfo, VanillaGameInfo) }
        val store = InMemoryLibraryStore()
        val banana = FakeGameBanana()
        val downloader = FakeDownloader(files)
        private var nextId = 0

        fun offer(vararg offered: Pair<RemoteFile, List<Pair<String, ByteArray>>>) {
            banana.listings = banana.listings + (mod to FileListing.Available("Vindicta parts", offered.map { it.first }))
            offered.forEach { (file, entries) -> downloader.serve(file.id, entries) }
        }

        fun TestScope.library(): ModLibrary = started(
            ModLibrary(
                FakeGameLocator(listOf(root.path), mapOf(root.path to null)), Preferences(MemoryStorage()), store, files,
                InstallPorts(banana, downloader, files, files, FakeProcesses()), backgroundScope, ids = { "id${++nextId}" }, watch = testWatchTiming,
            ),
        )

        fun record(): ManifestRecord = assertNotNull(store.manifest).records.single()

        fun addons() = files.addonsFiles(root)
    }

    private fun stage(library: ModLibrary): OperationStage = assertNotNull(library.state.value.operations[mod.modId]).stage

    private fun TestScope.install(library: ModLibrary, vararg fileIds: Long) {
        library.install(InstallRequest(InstallInput.Remote(mod, fileIds.toSet()), main))
        runCurrent()
    }

    private fun TestScope.answer(library: ModLibrary, vararg keys: String) {
        assertTrue(library.answer(assertIs<OperationStage.ChoosingSet>(stage(library)).id, ChoiceAnswer.Sets(keys.toList(), main)))
        runCurrent()
    }

    private fun colour(name: String) = FakeVpk.of(mapOf(
        "materials/models/heroes/vindicta/clothing_bra.vmat_c" to "$name bra".encodeToByteArray(),
        "materials/models/heroes/vindicta/clothing_panties.vmat_c" to "$name panties".encodeToByteArray(),
        "readme.txt" to ByteArray(0),
    ))

    private val colours = listOf("Pink" to 7, "Black" to 3, "Blue" to 5, "Red" to 9, "White" to 4, "Gold" to 8, "Green" to 6, "Purple" to 10)

    @Test
    fun coloursThatChangeTheSameMaterialsAreOnePickOneGroupWithTheAuthorsLowestNumberTicked() = runTest {
        val world = World()
        val addon = FakeVpk.of(mapOf("models/heroes/vindicta/necklace.vmdl_c" to "necklace".encodeToByteArray(), "readme.txt" to "different".encodeToByteArray()))
        world.offer(remote(1) to colours.map { (name, number) -> "$name/pak${number.toString().padStart(2, '0')}_dir.vpk" to colour(name) } + ("Necklace/pak01_dir.vpk" to addon))
        val library = with(world) { library() }
        install(library, 1)
        val asked = assertIs<OperationStage.ChoosingSet>(stage(library))
        val byNumber = colours.sortedBy { it.second }.map { (name, number) -> "$name/pak${number.toString().padStart(2, '0')}_dir.vpk" }
        assertEquals(listOf("Necklace/pak01_dir.vpk") + byNumber, asked.options.map { it.key }, "the author's numbers order the parts")
        assertEquals(listOf(byNumber), pickOneGroups(asked.options), "eight colours with their own bytes for the same two materials are one group")
        assertEquals(listOf("Necklace/pak01_dir.vpk", "Black/pak03_dir.vpk"), asked.preselected, "the add-on and the group's lowest number start ticked")
        answer(library, *asked.preselected.toTypedArray())
        assertEquals(listOf("Necklace/pak01_dir.vpk", "Black/pak03_dir.vpk"), world.record().sets.map { it.componentKey })
    }

    @Test
    fun unrelatedPartsAreAddOnsThatAllStartTickedWhateverReadmesAndPackerFilesTheyShare() = runTest {
        val world = World()
        fun part(own: String) = FakeVpk.of(mapOf(
            "particles/$own.vpcf_c" to own.encodeToByteArray(),
            "readme.txt" to "about $own".encodeToByteArray(),
            "materials/default/default_ao.vtex_c" to own.encodeToByteArray(),
            "panorama/image_compiler.vdata_c" to own.encodeToByteArray(),
        ))
        world.offer(remote(1) to listOf("Clutter/pak02_dir.vpk" to part("clutter"), "NoBlur/pak01_dir.vpk" to part("blur"), "Fog/pak02_dir.vpk" to part("fog")))
        val library = with(world) { library() }
        install(library, 1)
        val asked = assertIs<OperationStage.ChoosingSet>(stage(library))
        assertEquals(emptyList(), pickOneGroups(asked.options))
        assertEquals(listOf("NoBlur/pak01_dir.vpk", "Clutter/pak02_dir.vpk", "Fog/pak02_dir.vpk"), asked.preselected, "every add-on, lowest number first, then as the archive lists them")
    }

    @Test
    fun partsThatShipTheSameBytesForAFileDoNotChangeItDifferently() {
        val model = mapOf("models/ivy.vmdl_c" to 11L)
        val same = listOf(SetOption("a/pak01_dir.vpk", emptyList(), 1, model), SetOption("b/pak02_dir.vpk", emptyList(), 1, model))
        assertEquals(emptyList(), pickOneGroups(same))
        val different = listOf(SetOption("a/pak01_dir.vpk", emptyList(), 1, model), SetOption("b/pak02_dir.vpk", emptyList(), 1, mapOf("models/ivy.vmdl_c" to 12L)))
        assertEquals(listOf(listOf("a/pak01_dir.vpk", "b/pak02_dir.vpk")), pickOneGroups(different))
    }

    @Test
    fun aLowNumberedAddOnThatChangesSomeOfABasesFilesIsNoAlternativeToItSoTheBaseStartsTickedToo() {
        val paths = listOf("models/heroes/paige/paige.vmdl_c", "materials/paige/body.vmat_c", "materials/paige/hair.vtex_c", "materials/paige/eyes.vtex_c")
        fun variant(crc: Long) = paths.associateWith { crc } + ("readme.txt" to 0L)
        val options = listOf(
            SetOption("CowPrint/pak01_dir.vpk", emptyList(), 1, mapOf("materials/paige/body.vmat_c" to 1L, "readme.txt" to 1L)),
            SetOption("Pink/pak12_dir.vpk", emptyList(), 1, variant(12)),
            SetOption("Mint/pak14_dir.vpk", emptyList(), 1, variant(14)),
        )
        assertEquals(listOf(listOf("Pink/pak12_dir.vpk", "Mint/pak14_dir.vpk")), pickOneGroups(options), "the variants replace the same model; the add-on changes one of their files")
        assertEquals(listOf("CowPrint/pak01_dir.vpk", "Pink/pak12_dir.vpk"), preselect(options, emptyList()))
    }

    private val skin = remote(1, downloads = 38_114, name = "jacket_default.zip")
    private val localization = remote(2, downloads = 30_354, name = "localization_update.zip")

    private fun World.offerJacket(skinNumber: Int, localizationNumber: Int) = offer(
        skin to listOf("pak${skinNumber.toString().padStart(2, '0')}_dir.vpk" to FakeVpk.directory(seed = 1)),
        localization to listOf("pak${localizationNumber.toString().padStart(2, '0')}_dir.vpk" to FakeVpk.directory(seed = 2)),
    )

    private fun ManifestRecord.files(): List<Long?> = sets.map { it.fileId }

    @Test
    fun theAuthorsNumbersOrderAModsFilesAndDownloadsOnlyBreakATie() = runTest {
        val numbered = World().apply { offerJacket(skinNumber = 2, localizationNumber = 1) }
        install(with(numbered) { library() }, skin.id, localization.id)
        assertEquals(listOf(localization.id, skin.id), numbered.record().files(), "pak01 wins over pak02 whichever is downloaded more")

        val tied = World().apply { offerJacket(skinNumber = 1, localizationNumber = 1) }
        install(with(tied) { library() }, skin.id, localization.id)
        assertEquals(listOf(skin.id, localization.id), tied.record().files(), "with the same number, the more downloaded file wins")
    }

    @Test
    fun aFileAddedToAModGoesWhereItsNumberPutsItAmongTheFilesTheModHas() = runTest {
        val world = World().apply { offerJacket(skinNumber = 2, localizationNumber = 1) }
        val library = with(world) { library() }
        install(library, skin.id)
        assertTrue(changeFilesTo(library, world.record().installId, skin.id, localization.id))
        assertEquals(listOf(localization.id, skin.id), world.record().files(), "pak01 goes above the pak02 the mod has")
        assertEquals(setOf("pak98_dir.vpk", "pak99_dir.vpk"), world.addons().keys)
    }

    @Test
    fun aLonePartInstallsWithoutAskingWhateverReadmeOrFontsComeWithIt() = runTest {
        val world = World()
        world.offer(
            remote(1) to listOf("pak01_dir.vpk" to FakeVpk.directory(seed = 1), "README.txt" to "If Jacket is pak02, name this pak01.\r\n".encodeToByteArray()),
            remote(2) to listOf("pak02_dir.vpk" to FakeVpk.of(mapOf("panorama/fonts/reaver.ttf" to "font".encodeToByteArray()))),
        )
        val library = with(world) { library() }
        install(library, 1)
        assertEquals(listOf("pak01_dir.vpk"), world.record().sets.map { it.componentKey }, "a readme beside one part is no question")
        assertTrue(changeFilesTo(library, world.record().installId, 1, 2))
        assertEquals(listOf("pak01_dir.vpk", "pak02_dir.vpk"), world.record().sets.map { it.componentKey }, "nor is a part that replaces fonts")
    }

    @Test
    fun aReadmeShowsWhereTheArchiveAsksAboutItsParts() = runTest {
        val world = World()
        world.offer(remote(1) to listOf(
            "Main/pak01_dir.vpk" to FakeVpk.directory(seed = 1), "Extra/pak02_dir.vpk" to FakeVpk.directory(seed = 2), "README.txt" to "If Jacket is pak02, name this pak01.\r\n".encodeToByteArray(),
        ))
        val library = with(world) { library() }
        install(library, 1)
        assertEquals(listOf(ArchiveNote("README.txt", "If Jacket is pak02, name this pak01.")), assertIs<OperationStage.ChoosingSet>(stage(library)).notes)
    }

    @Test
    fun aPartThatCannotLoadIsListedInTheQuestionAndNothingInstallsIt() = runTest {
        val world = World()
        world.offer(remote(1) to listOf(
            "Main/pak10_dir.vpk" to FakeVpk.directory(chunkReads = mapOf(0 to 10L)),
            "Main/pak10_000 (1).vpk" to ByteArray(10),
            "addon/pak11_dir.vpk" to FakeVpk.directory(seed = 3),
        ))
        val library = with(world) { library() }
        install(library, 1)
        val asked = assertIs<OperationStage.ChoosingSet>(stage(library), "one complete part still asks, so the part left out is seen")
        assertEquals(listOf("addon/pak11_dir.vpk"), asked.options.map { it.key })
        assertEquals(listOf("Main/pak10_dir.vpk", "Main/pak10_000 (1).vpk"), asked.leftOut.map { it.key })
        assertEquals(SetProblem.MissingChunks(listOf("pak10_000.vpk")), asked.leftOut.first().problem)
        answer(library, "addon/pak11_dir.vpk")
        assertEquals(setOf("pak99_dir.vpk"), world.addons().keys)
    }

    @Test
    fun aDirectoryRenamedWithoutItsChunksInstallsWithThemAsOneSet() = runTest {
        val world = World()
        world.offer(remote(1) to listOf("mymod_dir.vpk" to FakeVpk.directory(chunkReads = mapOf(0 to 10L)), "pak01_000.vpk" to ByteArray(10) { 4 }))
        val library = with(world) { library() }
        install(library, 1)
        assertEquals(setOf("pak99_dir.vpk", "pak99_000.vpk"), world.addons().keys, "installed without a question")
        assertEquals(listOf("mymod_dir.vpk"), world.record().sets.map { it.componentKey })
    }
}
