package gg.hideouthq.core.migration

import gg.hideouthq.core.crosshair.with
import gg.hideouthq.core.crosshair.Crosshairs
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.ConvarNumber
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.CrosshairOrigin
import gg.hideouthq.core.data.BundleOpen
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.GrimoireFiles
import gg.hideouthq.core.data.ManagerData
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeManagerData
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.ForeignSeed
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.InMemoryMigrationRecords
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SampleForeign
import gg.hideouthq.core.data.fake.ports
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.DmmGameInfo
import gg.hideouthq.core.library.FixedGameLocator
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.ImportProblem
import gg.hideouthq.core.library.ImportSummary
import gg.hideouthq.core.library.ImportWait
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LoadingOwner
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ManagerActivity
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.RewrittenBy
import gg.hideouthq.core.library.SearchPathsCheck
import gg.hideouthq.core.library.SessionHold
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.SwitchSummary
import gg.hideouthq.core.library.citadelPath
import gg.hideouthq.core.library.crashableScope
import gg.hideouthq.core.library.gateOf
import gg.hideouthq.core.library.heldMod
import gg.hideouthq.core.library.number
import gg.hideouthq.core.library.onLoadOrder
import gg.hideouthq.core.library.resourceBytes
import gg.hideouthq.core.library.testWatchTiming
import gg.hideouthq.feature.imports.ImportCounts
import gg.hideouthq.feature.imports.counts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class MigrationTest {
    private val root = GameRoot("/games/Deadlock")
    private val citadel = "game/citadel"
    private val grimoireInfo = "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame\tcitadel/grimoire\n\t\t\tGame\tcitadel\n\t\t\tGame\tcore\n\t\t}\n\t}\n}\n".encodeToByteArray()
    private val dmm = ImportSource.Installed(Manager.DeadlockModManager)

    private fun listing(id: Long, fileId: Long) = FileListing.Available("GameBanana $id", listOf(RemoteFile(fileId, "$id.zip", 1, null, null, 1, 1, "https://gamebanana.com/dl/$fileId")))

    private inner class World(
        manifest: LibraryManifest = LibraryManifest(),
        seed: ForeignSeed = ForeignSeed.Dmm,
        unreadable: String? = null,
        private val managers: ManagerData = FakeManagerData(seed),
    ) {
        val files = InMemoryGameFiles().apply {
            putBytes(root, "$citadel/gameinfo.gi", if (seed == ForeignSeed.Grimoire) grimoireInfo else DmmGameInfo)
            FakeManagerData(seed).layOut(this, root)
        }
        val before = files.everyFile(root).keys.associateWith { files.bytes(root, it)?.toList() }
        val store = InMemoryLibraryStore(manifest, unreadable)
        val records = InMemoryMigrationRecords()
        val processes = FakeProcesses()
        val banana = FakeGameBanana(
            mapOf(
                SubmissionRef(SubmissionKind.Mod, 700_001) to listing(700_001, 1_800_001),
                SubmissionRef(SubmissionKind.Mod, 700_002) to FileListing.Unavailable("GameBanana did not answer"),
                SubmissionRef(SubmissionKind.Mod, 700_003) to listing(700_003, 1_800_003),
            ),
        )
        private var next = 0
        val logged = mutableListOf<String>()
        lateinit var library: ModLibrary
        lateinit var crosshairs: Crosshairs

        fun TestScope.start(scope: CoroutineScope = backgroundScope): Migration {
            library = ModLibrary(FixedGameLocator(listOf(root)), Preferences(MemoryStorage()), store, files, files.ports(banana, processes), scope, ids = { "id${++next}" }, watch = testWatchTiming)
            library.start()
            crosshairs = Crosshairs(library, MemoryStorage(), scope).apply { start() }
            val migration = Migration(library, files, managers, records, crosshairs, scope, ids = { "m${++next}" }, clock = FixedClock, log = logged::add)
            migration.start()
            runCurrent()
            return migration
        }

        fun record(): MigrationRecord = checkNotNull(MigrationRecordCodec.decode(records.records.values.single()))
    }

    private object FixedClock : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(1_760_000_000_000)
    }

    private fun TestScope.settle(seconds: Int = 3) {
        repeat(seconds) {
            advanceTimeBy(1.seconds)
            runCurrent()
        }
    }

    private fun World.assertTakenOver() {
        assertEquals(emptyList(), files.everyFile(root).keys.filter { it.endsWith(".dmm.json") }, "the manager's manifests go")
        assertNull(files.bytes(root, "$citadel/addons/700003_Ranked HUD.vpk"), "the parked file now has Hideout's name")
        val info = checkNotNull(files.gameInfo(root))
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(info))
        assertTrue("Deadlock Mod Manager" !in info.decodeToString())
        assertEquals(SampleForeign.dmmAutoexec.toList(), files.bytes(root, "$citadel/cfg/autoexec.cfg")?.toList(), "DMM's lines in autoexec.cfg stay as they are")
        val crosshair = Crosshair.GameDefault.with(gap = -7.0)
        val imported = crosshairs.state.value.yours.entries.single { it.origin is CrosshairOrigin.FromManager }
        assertEquals("Imported crosshair" to CrosshairOrigin.FromManager(Manager.DeadlockModManager, crosshair), imported.name to imported.origin, "DMM's crosshair joins Yours, every setting it left out the game's default")
        assertEquals(Crosshair.GameDefault, crosshairs.state.value.inUse?.crosshair, "the game's own crosshair stays in use")
        assertNull(files.bytes(root, "$citadel/addons/profile_ranked/pak01_dir.vpk"), "another profile's copy goes, its bytes held by the mod Hideout took over")
        val record = record()
        assertEquals(setOf(CitadelPath("gameinfo.gi"), CitadelPath("cfg/autoexec.cfg"), CitadelPath("addons/.dmm.json"), CitadelPath("addons/profile_ranked/.dmm.json")), record.copies.keys)
        for ((path, bytes) in records.copies.values.single()) assertEquals(before["$citadel/${path.value}"], bytes.toList(), "$path is copied byte for byte")
        assertEquals(setOf("addons/pak10_dir.vpk", "addons/pak11_dir.vpk", "addons/700003_Ranked HUD.vpk", "addons/profile_ranked/pak01_dir.vpk"), record.adopted.keys.map { it.value }.toSet())
        assertIs<RecordOutcome.Finished>(record.outcome)
    }

    private val summary = ImportSummary(on = 2, off = 1, profiles = listOf("Ranked"), crosshair = true, commands = 0)

    @Test
    fun dmm230sLiveSetupComesAsOneOwnedCopyOfEachModThatTurningOffUnloads() = runTest {
        val classic = FakeVpk.directory(seed = 140)
        val ranked = "addons/profile_1793707836444_4d9mh1w48_ranked"
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.None) {
            override suspend fun dmmState(): ByteArray = resourceBytes("dmm/state-v42.json")
        }
        val world = World(seed = ForeignSeed.None, managers = managers)
        world.files.putBytes(root, "$citadel/gameinfo.gi", DmmGameInfo.decodeToString().replace("citadel/addons", "citadel/$ranked").encodeToByteArray())
        world.files.putBytes(root, "$citadel/addons/pak01_dir.vpk", classic)
        world.files.putBytes(root, "$citadel/addons/pak02_dir.vpk", FakeVpk.directory(seed = 141))
        world.files.putBytes(root, "$citadel/addons/pak03_dir.vpk", FakeVpk.directory(seed = 142))
        world.files.putBytes(root, "$citadel/addons/.dmm.json", resourceBytes("dmm/addons-manifest-v42.json"))
        world.files.putBytes(root, "$citadel/$ranked/pak01_dir.vpk", classic)
        world.files.putBytes(root, "$citadel/$ranked/.dmm.json", resourceBytes("dmm/ranked-manifest-v42.json"))
        fun inAddons() = world.files.everyFile(root).keys.filter { it.startsWith("$citadel/addons/") }
        val migration = with(world) { start() }

        migration.look(dmm)
        settle()
        val preview = assertIs<Preview.Ready>(migration.state.value.previews[dmm])
        assertEquals(ImportCounts(mods = 2, profiles = 1, crosshairs = 1, commands = 0), preview.counts, "the import dialog offers the crosshair")

        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(ImportPhase.Finished(ImportSummary(on = 1, off = 1, profiles = listOf("Default Profile"), crosshair = true, commands = 0)), migration.state.value.run?.phase)
        assertEquals(listOf("$citadel/addons/pak99_dir.vpk"), inAddons(), "the one copy left is the record's, so no copy loads untracked and no profile folder stays")
        val installed = world.store.manifest!!.records.single { it.title == "Classic Investments" }
        assertEquals(99, installed.number)
        assertContentEquals(classic, world.files.bytes(root, "$citadel/addons/pak99_dir.vpk"))
        assertEquals(setOf(CitadelPath("addons/pak01_dir.vpk"), CitadelPath("$ranked/pak01_dir.vpk")), world.record().adopted.filterValues { it == Sha256.image(classic) }.keys, "the record says where each copy was, found in library.json by its bytes")

        world.library.setEnabled(installed.installId, false)
        settle()
        assertEquals(emptyList(), inAddons(), "turning the mod off leaves nothing in addons for the game to load")

        val crosshair = Crosshair.GameDefault.with(color = CrosshairColor(115, 255, 0), length = 5.0, thickness = 0.0, gap = -6.0, opacity = 1.0, lineBorder = 0.0, dotOpacity = 0.0)
            .let { it.copy(dot = it.dot.copy(border = it.dot.border.copy(opacity = ConvarNumber.Zero)), fixedGap = false) }
        assertEquals(CrosshairOrigin.FromManager(Manager.DeadlockModManager, crosshair), world.crosshairs.state.value.yours.entries.single { it.origin is CrosshairOrigin.FromManager }.origin, "the values DMM wrote to the game")
    }

    @Test
    fun aProfileWithEveryModOffComesAsAProfileWithNothingOn() = runTest {
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.None) {
            override suspend fun dmmState(): ByteArray = resourceBytes("dmm/state-v42-skins-only.json")
        }
        val world = World(seed = ForeignSeed.None, managers = managers)
        val skins = "addons/profile_1794412110746_538lub64d_skins-only"
        val lash = FakeVpk.directory(seed = 150)
        val warden = FakeVpk.directory(seed = 151)
        mapOf("pak01_dir.vpk" to lash, "pak07_dir.vpk" to warden, "632891_pak12_dir.vpk" to FakeVpk.directory(seed = 152))
            .plus((2..5).associate { "pak0${it}_dir.vpk" to FakeVpk.directory(seed = 152 + it) })
            .forEach { (name, bytes) -> world.files.putBytes(root, "$citadel/addons/$name", bytes) }
        world.files.putBytes(root, "$citadel/addons/.dmm.json", resourceBytes("dmm/addons-manifest-v42-skins-only.json"))
        world.files.putBytes(root, "$citadel/$skins/691043_pak03_dir.vpk", warden)
        world.files.putBytes(root, "$citadel/$skins/733735_pak13_dir.vpk", lash)
        world.files.putBytes(root, "$citadel/$skins/.dmm.json", resourceBytes("dmm/skins-only-manifest-v42.json"))
        val migration = with(world) { start() }

        migration.look(dmm)
        settle()
        assertEquals(listOf("Skins Only"), assertIs<Preview.Ready>(migration.state.value.previews[dmm]).profiles.map { it.name })
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(ImportPhase.Finished(ImportSummary(on = 4, off = 1, profiles = listOf("Skins Only"), crosshair = false, commands = 0)), migration.state.value.run?.phase)
        val book = world.store.manifest!!.profiles
        assertEquals(emptySet(), book.profiles.single { it.name == "Skins Only" }.enabled, "the profile comes, with its mods off")
        assertEquals(5, book.activeProfile.orders[root].orEmpty().size, "and the mods it has off are Hideout's, in Main")
        assertEquals(emptyList(), world.files.everyFile(root).keys.filter { it.startsWith("$citadel/$skins/") }, "its copies of Main's files go with its manifest")

        migration.dismiss()
        migration.look(dmm)
        settle()
        assertEquals(emptyList(), assertIs<Preview.Ready>(migration.state.value.previews[dmm]).profiles, "and it is not offered again")
    }

    @Test
    fun importingTheCrosshairAgainAddsNothingToYours() = runTest {
        val world = World()
        val migration = with(world) { start() }
        repeat(2) {
            migration.import(dmm, ImportChoice(ModsChoice.None, crosshair = true, commands = false))
            settle()
            migration.dismiss()
        }
        assertEquals(1, world.crosshairs.state.value.yours.entries.count { it.origin is CrosshairOrigin.FromManager })
    }

    @Test
    fun theSourcePageFindsTheManagerAndPreviewsWhatOnDiskWouldCome() = runTest {
        val world = World()
        val migration = with(world) { start() }
        assertEquals(listOf(FoundSource(Manager.DeadlockModManager, true, inGame = true), FoundSource(Manager.Grimoire, false)), migration.state.value.sources)
        migration.look(dmm)
        settle()
        val preview = assertIs<Preview.Ready>(migration.state.value.previews[dmm])
        assertEquals(
            listOf(
                PreviewMod("Neon Haze", "pak10_dir.vpk", true),
                PreviewMod("Iron Sights", "pak11_dir.vpk", true),
                PreviewMod("Ranked HUD", "700003_Ranked HUD.vpk", false),
            ),
            preview.mods,
        )
        assertEquals(listOf("citadel_crosshair_pip_gap \"-7\"", "citadel_crosshair_color_g \"255\""), preview.crosshair)
        assertEquals(emptyList(), preview.commands)
        assertEquals(world.before, world.files.everyFile(root).keys.associateWith { world.files.bytes(root, it)?.toList() }, "looking changes nothing")
        assertTrue(world.records.records.isEmpty())
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(world.store.manifest!!.profiles.profiles.drop(1).map { Triple(it.name, it.colour, it.enabled.size) }, preview.profiles.map { Triple(it.name, it.colour, it.enabled.size) }, "the profiles as the import makes them")
    }

    @Test
    fun theManagerWhoseFilesAreInTheGameComesFirstAndAStaleOneTakesNoneOfThem() = runTest {
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.Grimoire) {
            override suspend fun dmmState(): ByteArray = SampleForeign.dmmState
        }
        val world = World(seed = ForeignSeed.Grimoire, managers = managers)
        val migration = with(world) { start() }
        assertEquals(
            listOf(FoundSource(Manager.Grimoire, true, inGame = true), FoundSource(Manager.DeadlockModManager, true, inGame = false)),
            migration.state.value.sources,
            "Grimoire's folders are in the game, while Deadlock Mod Manager left only its data",
        )
        migration.look(dmm)
        settle()
        val stale = assertIs<Preview.Ready>(migration.state.value.previews[dmm])
        assertEquals(emptyList(), stale.mods, "none of Grimoire's files come as Deadlock Mod Manager's")
        assertEquals(3, stale.missing, "and the three mods its data lists have no files")
    }

    @Test
    fun grimoiresLeftoverFoldersWithoutItsRecordsOfferNothingAndClaimNoOtherManagersFiles() = runTest {
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.None) {
            override suspend fun dmmState(): ByteArray = resourceBytes("dmm/state-v42.json")
        }
        val world = World(seed = ForeignSeed.None, managers = managers)
        world.files.putBytes(root, "$citadel/addons/pak01_dir.vpk", FakeVpk.directory(seed = 170))
        world.files.putBytes(root, "$citadel/addons/pak02_dir.vpk", FakeVpk.directory(seed = 171))
        world.files.putBytes(root, "$citadel/addons/745080_ability_recast_timer.vpk", FakeVpk.directory(seed = 172))
        world.files.putBytes(root, "$citadel/addons/.dmm.json", resourceBytes("dmm/addons-manifest-dmm230-windows.json"))
        world.files.putBytes(root, "$citadel/grimoire/readme.txt", byteArrayOf())
        world.files.putBytes(root, "$citadel/addons/.disabled/readme.txt", byteArrayOf())
        val grimoire = ImportSource.Installed(Manager.Grimoire)
        val migration = with(world) { start() }

        assertEquals(listOf(Manager.DeadlockModManager), migration.state.value.sources.filter { it.found }.map { it.manager }, "only the manager whose records are here is found")
        migration.look(grimoire)
        settle()
        assertEquals(Preview.Failed(ImportProblem.SourceUnreadable("Grimoire keeps nothing on this computer")), migration.state.value.previews[grimoire], "Deadlock Mod Manager's files are not Grimoire's")
    }

    @Test
    fun theModsTheManagerListsWithoutFilesAreCountedAndLeftOut() = runTest {
        val world = World()
        world.files.remove(root, "$citadel/addons/700003_Ranked HUD.vpk")
        val migration = with(world) { start() }
        migration.look(dmm)
        settle()
        val preview = assertIs<Preview.Ready>(migration.state.value.previews[dmm])
        assertEquals(listOf("Neon Haze", "Iron Sights"), preview.mods.map { it.name })
        assertEquals(1, preview.missing)
        migration.import(dmm, ImportChoice.Everything)
        settle()
        migration.dismiss()
        migration.look(dmm)
        settle()
        assertEquals(1, assertIs<Preview.Ready>(migration.state.value.previews[dmm]).missing, "the mods that came are not missing afterwards")
    }

    @Test
    fun anImportedProfileComesInItsSourcesOrderAndSwitchingToItRenumbersTheFiles() = runTest {
        val neon = FakeVpk.directory(seed = 130)
        val iron = FakeVpk.directory(seed = 131)
        fun mod(remoteId: Long, installOrder: Int) = """{"remoteId":"$remoteId","installOrder":$installOrder}"""
        val state = """{"profiles":{"default":{"name":"Default","folderName":null,"mods":[${mod(700_001, 0)},${mod(700_002, 1)}]},
            "ranked":{"name":"Ranked","folderName":"profile_ranked","mods":[${mod(700_001, 1)},${mod(700_002, 0)}]}},"activeProfileId":"default","localMods":[]}"""
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.None) {
            override suspend fun dmmState(): ByteArray = buildJsonObject { put("local-config", JsonPrimitive("""{"state":$state,"version":32}""")) }.toString().encodeToByteArray()
        }
        fun manifest(vararg entries: Pair<Long, String>) =
            """{"version":3,"mods":{${entries.joinToString(",") { (key, vpk) -> """"$key":{"enabled":true,"shard":1,"currentVpks":["$vpk"]}""" }}}}""".encodeToByteArray()
        val world = World(seed = ForeignSeed.None, managers = managers)
        world.files.putBytes(root, "$citadel/addons/pak10_dir.vpk", neon)
        world.files.putBytes(root, "$citadel/addons/pak11_dir.vpk", iron)
        world.files.putBytes(root, "$citadel/addons/.dmm.json", manifest(700_001L to "pak10_dir.vpk", 700_002L to "pak11_dir.vpk"))
        world.files.putBytes(root, "$citadel/addons/profile_ranked/pak01_dir.vpk", neon)
        world.files.putBytes(root, "$citadel/addons/profile_ranked/pak02_dir.vpk", iron)
        world.files.putBytes(root, "$citadel/addons/profile_ranked/.dmm.json", manifest(700_001L to "pak01_dir.vpk", 700_002L to "pak02_dir.vpk"))
        val migration = with(world) { start() }
        migration.import(dmm, ImportChoice.Everything)
        settle()

        fun installed(file: String) = world.store.manifest!!.records.single { it.title == file }.installId
        val (first, second) = installed("pak10_dir.vpk") to installed("pak11_dir.vpk")
        assertEquals(listOf(first, second), onLoadOrder(world.store.manifest!!.records), "what loads keeps loading as it did")
        val ranked = world.library.state.value.profiles!!.profiles.single { it.name == "Ranked" }
        assertEquals(listOf(second, first), ranked.orders[root], "the profile comes in its own order")
        assertIs<MutationResult.Done<SwitchSummary>>(world.library.switchProfile(ranked.id))
        settle()
        assertEquals(listOf(second, first), onLoadOrder(world.store.manifest!!.records))
        assertEquals(listOf(11, 99), listOf(second, first).map { id -> world.store.manifest!!.records.single { it.installId == id }.number }, "the switch moves one file, to the highest number after the other")
        assertNull(world.store.manifest!!.savedChoices)
    }

    @Test
    fun anEmptyProfileComesAsAnEmptyProfile() = runTest {
        val state = """{"profiles":{"default":{"name":"Default","folderName":null,"mods":[{"remoteId":"700001","installOrder":0}]},
            "profile_1795528455057_nkc136lvk":{"name":"hud","folderName":"profile_1795528455057_nkc136lvk_hud","mods":[]}},"activeProfileId":"default","localMods":[]}"""
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.None) {
            override suspend fun dmmState(): ByteArray = buildJsonObject { put("local-config", JsonPrimitive("""{"state":$state,"version":42}""")) }.toString().encodeToByteArray()
        }
        val world = World(seed = ForeignSeed.None, managers = managers)
        world.files.putBytes(root, "$citadel/addons/pak10_dir.vpk", FakeVpk.directory(seed = 180))
        world.files.putBytes(root, "$citadel/addons/.dmm.json", """{"version":3,"mods":{"700001":{"enabled":true,"shard":1,"currentVpks":["pak10_dir.vpk"]}}}""".encodeToByteArray())
        val migration = with(world) { start() }

        migration.look(dmm)
        settle()
        assertEquals(listOf("hud"), assertIs<Preview.Ready>(migration.state.value.previews[dmm]).profiles.map { it.name }, "the preview names it")
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(emptySet(), world.store.manifest!!.profiles.profiles.single { it.name == "hud" }.enabled)
    }

    @Test
    fun thePreviewNamesTheProfilesTheImportMakesBesideTheUsersOwn() = runTest {
        val bytes = FakeVpk.directory(seed = 50)
        val held = heldMod("h1", root, bytes)
        val main = Profile(ProfileId("main"), "Main", ProfileColour.Sage, setOf("h1"), mapOf(root to listOf("h1")))
        val world = World(LibraryManifest(records = listOf(held), profiles = ProfileBook(main.id, listOf(main, main.copy(id = ProfileId("ranked"), name = "Ranked")))))
        world.files.putBytes(root, "$citadel/addons/pak01_dir.vpk", bytes)
        val migration = with(world) { start() }
        migration.look(dmm)
        settle()
        assertEquals(listOf("Ranked 2"), assertIs<Preview.Ready>(migration.state.value.previews[dmm]).profiles.map { it.name })
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(ImportPhase.Finished(summary.copy(profiles = listOf("Ranked 2"))), migration.state.value.run?.phase)
    }

    @Test
    fun aGrimoireProfileThatNamesHideoutsFilesByNameTurnsThoseModsOn() = runTest {
        val fov = FakeVpk.directory(seed = 160)
        val speedometer = FakeVpk.directory(seed = 161)
        val clutter = FakeVpk.directory(seed = 162)
        val clutterHash = Sha256.image(clutter).sha256
        val metadata = """{"pak01_dir.vpk":{"modName":"Unknown Pak01"},"pak02_dir.vpk":{"modName":"Unknown Pak02"},
            "pak03_dir.vpk":{"gameBananaId":722853,"gameBananaFileId":1902556,"sourceSection":"Mod","modName":"Clutter Be Gone","sha256":"$clutterHash"}}"""
        val profiles = """[{"id":"profile_1795530000000_casual","name":"casual","mods":[
            {"fileName":"pak01_dir.vpk","enabled":true,"priority":1},
            {"fileName":"pak02_dir.vpk","enabled":true,"priority":2},
            {"fileName":"pak03_dir.vpk","enabled":true,"priority":3,"gameBananaId":722853,"gameBananaFileId":1902556,"sha256":"$clutterHash"}],
            "autoexecCommands":[],"createdAt":"2026-11-24T14:00:00.000Z","updatedAt":"2026-11-24T14:00:00.000Z"}]"""
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.None) {
            override suspend fun grimoire(folder: String?) = GrimoireFiles(metadata.encodeToByteArray(), profiles.encodeToByteArray())
        }
        val world = World(LibraryManifest(records = listOf(heldMod("fov", root, fov, number = 1), heldMod("speedometer", root, speedometer, number = 2))), seed = ForeignSeed.None, managers = managers)
        world.files.putBytes(root, "$citadel/gameinfo.gi", grimoireInfo.decodeToString().replace("Game\tcitadel/grimoire\n", "Game\tcitadel/grimoire\n\t\t\tGame\tcitadel/addons\n").encodeToByteArray())
        world.files.putBytes(root, "$citadel/addons/pak01_dir.vpk", fov)
        world.files.putBytes(root, "$citadel/addons/pak02_dir.vpk", speedometer)
        world.files.putBytes(root, "$citadel/addons/pak03_dir.vpk", clutter)
        val grimoire = ImportSource.Installed(Manager.Grimoire)
        val migration = with(world) { start() }

        migration.import(grimoire, ImportChoice.Everything)
        settle()
        assertIs<ImportPhase.Finished>(migration.state.value.run?.phase)
        val manifest = world.store.manifest!!
        val clutterId = manifest.records.single { it.files.single().image == Sha256.image(clutter) }.installId
        assertEquals(setOf("fov", "speedometer", clutterId), manifest.profiles.profiles.single { it.name == "casual" }.enabled, "the files Grimoire names are Hideout's mods, which the profile turns on")
    }

    @Test
    fun anImportBacksUpReadsPlacesAndConfiguresInThatOrderWithoutAskingGameBanana() = runTest {
        val world = World()
        val migration = with(world) { start() }
        val phases = mutableListOf<ImportPhase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { migration.state.collect { state -> state.run?.phase?.let { if (phases.lastOrNull() != it) phases += it } } }
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(ImportPhase.Finished(summary), migration.state.value.run?.phase)
        val order = listOf(ImportPhase.BackingUp::class, ImportPhase.Reading::class, ImportPhase.Placing::class, ImportPhase.Configuring::class, ImportPhase.Finished::class)
        assertEquals(order, phases.map { it::class }.distinct(), "phases: $phases")
        assertEquals(0, world.banana.calls, "nothing waits on GameBanana")
        world.assertTakenOver()
        val records = world.store.manifest!!.records
        assertEquals(listOf("Neon Haze", "Iron Sights", "Ranked HUD"), records.map { it.title }, "each mod by the manager's name for it")
        assertEquals(
            InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 700_002, 1_800_002, "%032x".format(1_800_002))),
            records[1].sets.single().source,
            "a mod comes as the file the manager recorded",
        )
        assertEquals(listOf(10, 11), records.take(2).map { it.number }, "what loads is claimed where it is")
        migration.dismiss()
        assertNull(migration.state.value.run)
    }

    @Test
    fun aCrosshairOnlyImportAsksGameBananaNothingAndTakesNoFileOver() = runTest {
        val world = World()
        val migration = with(world) { start() }
        migration.import(dmm, ImportChoice(ModsChoice.None, crosshair = true, commands = false))
        settle()
        assertEquals(ImportPhase.Finished(ImportSummary(0, 0, emptyList(), crosshair = true, commands = 0)), migration.state.value.run?.phase)
        assertEquals(0, world.banana.calls, "no GameBanana listing is asked for")
        assertEquals(emptyMap(), world.record().adopted)
        val untouched = DmmGameInfo.decodeToString().replace("- Start\r\n", "- Start\r\n\t\t\tGame\tcitadel/addons/profile_ranked\r\n").encodeToByteArray()
        world.files.writeGameInfo(root, untouched)
        settle()
        assertContentEquals(untouched, world.files.gameInfo(root), "an import that brought no mods leaves the manager's lines its own")
    }

    @Test
    fun onceImportedFromGrimoiresOwnLinesAreTakenOutAgainAlsoAfterARestart() = runTest {
        val world = World(seed = ForeignSeed.Grimoire)
        val doomed = crashableScope()
        val migration = with(world) { start(doomed) }
        migration.import(ImportSource.Installed(Manager.Grimoire), ImportChoice.Everything)
        settle()
        assertIs<ImportPhase.Finished>(migration.state.value.run?.phase)
        val imported = checkNotNull(world.files.gameInfo(root))
        val fixed = imported.decodeToString().replace(Regex("(// Hideout start\n)\t+Game\t+citadel/addons\n"), "$1")
            .replaceFirst("\t\t{\n", "\t\t{\n\t\t\tGame\tcitadel/grimoire\n\t\t\tGame\tcitadel/addons\n").encodeToByteArray()

        world.files.writeGameInfo(root, fixed)
        settle()
        assertContentEquals(imported, world.files.gameInfo(root), "Grimoire's Fix now is put back")
        assertEquals(RewrittenBy.AnotherProgram(Manager.Grimoire), world.library.state.value.rewrite?.by)

        doomed.cancel()
        world.files.writeGameInfo(root, fixed)
        with(world) { start() }
        settle()
        assertContentEquals(imported, world.files.gameInfo(root), "the import's record says so at the next start")
    }

    @Test
    fun theImportWaitsForDeadlockAndTheManagerItImportsFromAndNothingElse() = runTest {
        val world = World()
        world.processes.scan = ProcessScan.Seen(managers = setOf(Manager.DeadlockModManager))
        val migration = with(world) { start() }
        settle()
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(ImportPhase.Waiting(ImportWait.ManagerOpen(Manager.DeadlockModManager)), migration.state.value.run?.phase)
        world.processes.scan = ProcessScan.Seen(game = true)
        settle()
        assertEquals(ImportPhase.Waiting(ImportWait.GameOpen), migration.state.value.run?.phase)
        world.processes.scan = ProcessScan.Unknown("no /proc")
        settle()
        assertEquals(ImportPhase.Waiting(ImportWait.GameOpen), migration.state.value.run?.phase)
        assertContentEquals(DmmGameInfo, world.files.gameInfo(root), "nothing changes while it waits")
        assertEquals(2, world.files.everyFile(root).keys.count { it.endsWith(".dmm.json") })
        world.processes.scan = ProcessScan.Seen(managers = setOf(Manager.Grimoire))
        settle()
        assertEquals(ImportPhase.Finished(summary), migration.state.value.run?.phase, "another manager open does not hold this import")
        world.assertTakenOver()
    }

    @Test
    fun aManagerWhoseActivityIsUnknownHoldsNothingUpAndABundleWaitsOnlyForTheGame() {
        val closed = LibraryState(deadlock = GameActivity.Closed, managers = ManagerActivity.Unknown)
        assertNull(gateOf(closed, Manager.Grimoire), "Hideout cannot tell, so it goes on")
        assertEquals(ImportWait.ManagerOpen(Manager.Grimoire), gateOf(closed.copy(managers = ManagerActivity.Seen(setOf(Manager.Grimoire))), Manager.Grimoire))
        assertNull(gateOf(closed, null))
        assertEquals(ImportWait.GameOpen, gateOf(closed.copy(deadlock = GameActivity.Unknown), null))
        assertEquals(ImportWait.CustomGame, gateOf(closed.copy(sessionHold = SessionHold(GameRoot("/games/Deadlock"))), null), "a closed client does not open the gate")
        assertNull(gateOf(closed.copy(managers = ManagerActivity.Seen(setOf(Manager.DeadlockModManager))), Manager.Grimoire))
    }

    @Test
    fun aLibraryThatCannotBeReadIsWhyAnImportCannotRunNotTheGame() = runTest {
        val migration = with(World(unreadable = "library.json is not JSON")) { start() }
        val busy = ImportProblem.LibraryBusy("library.json is not JSON")
        migration.look(dmm)
        settle()
        assertEquals(Preview.Failed(busy), migration.state.value.previews[dmm])
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(ImportPhase.Stopped(busy, null), migration.state.value.run?.phase)
    }

    @Test
    fun aFolderWhoseExportIsAnotherFormatSaysSoInWords() = runTest {
        val bundle = ImportSource.Bundle("/exports/mods")
        val managers = object : ManagerData by FakeManagerData(ForeignSeed.Dmm) {
            override suspend fun bundle(path: String) = BundleOpen.Found("""{"format":"mod-profile","version":1}""".encodeToByteArray(), path)
        }
        val migration = with(World(managers = managers)) { start() }
        migration.look(bundle)
        settle()
        assertEquals(Preview.Failed(ImportProblem.NotABundle("its mod-interchange.json is not a version 1 export")), migration.state.value.previews[bundle])
    }

    @Test
    fun withNoModsOfHideoutsAGrimoireImportTakesTheHighestNumbers() = runTest {
        val world = World(seed = ForeignSeed.Grimoire)
        val migration = with(world) { start() }
        migration.import(ImportSource.Installed(Manager.Grimoire), ImportChoice.Everything)
        settle()
        assertIs<ImportPhase.Finished>(migration.state.value.run?.phase)
        val records = world.store.manifest!!.records
        assertEquals(listOf(98, 99, null), records.map { it.numbers.firstOrNull()?.value }.sortedBy { it ?: Int.MAX_VALUE }, "the two that load take the highest numbers, and the disabled one comes in off")
        assertEquals(listOf(SampleForeign.lockerCards, SampleForeign.skyline).map(Sha256::image), records.filter { it.enabled }.sortedBy { it.numbers.first() }.map { it.files.single().image }, "in the order Grimoire's folder loads them")
        val off = records.single { !it.enabled }
        assertTrue(off.files.all { it.citadelPath(off).value.startsWith("hideout_off/${off.modId}/set-") }, "the disabled one in its own off folder, under a name that holds no number")
    }

    @Test
    fun anImportACrashStoppedAfterItsFilesCommittedFinishesTheConfigsAndManifestsAtTheNextStart() = runTest {
        val world = World()
        world.files.crashAt = FaultPoint.ReplaceConfig
        val doomed = crashableScope()
        val first = with(world) { start(doomed) }
        first.import(dmm, ImportChoice.Everything)
        settle()
        doomed.cancel()
        world.files.crashAt = null
        assertEquals(3, world.store.manifest!!.records.size, "the files were committed")
        assertTrue(world.files.bytes(root, "$citadel/addons/.dmm.json") != null, "the manifest is still there")
        assertNull(world.record().outcome)

        val restarted = with(world) { start() }
        settle()
        assertEquals(ImportPhase.Finished(summary), restarted.state.value.run?.phase)
        world.assertTakenOver()
        assertEquals(3, world.store.manifest!!.records.size, "nothing is adopted twice")
        assertEquals(listOf("Main", "Ranked"), world.store.manifest!!.profiles.profiles.map { it.name }, "the source's profile is made once")
    }

    @Test
    fun anImportThatStopsKeepsWhyInItsRecord() = runTest {
        val world = World()
        world.files.failing += FaultPoint.ReplaceConfig
        val migration = with(world) { start() }
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertIs<ImportPhase.Stopped>(migration.state.value.run?.phase)
        assertEquals(RecordOutcome.Stopped("failed at ReplaceConfig"), world.record().outcome)
        assertTrue(world.logged.single().startsWith("Importing from $dmm stopped: "), "the log says the import stopped: ${world.logged}")
    }

    @Test
    fun runningTheImportAgainChangesNothing() = runTest {
        val world = World()
        val migration = with(world) { start() }
        migration.import(dmm, ImportChoice.Everything)
        settle()
        val manifest = world.store.manifest!!
        val ironSights = manifest.records.single { it.title == "Iron Sights" }.installId
        assertEquals(setOf(ironSights), manifest.profiles.profiles.single { it.name == "Ranked" }.enabled, "the source's profile holds what its folder has on")
        val game = world.files.everyFile(root).keys.associateWith { world.files.bytes(root, it)?.toList() }
        migration.dismiss()
        migration.import(dmm, ImportChoice.Everything)
        settle()
        assertEquals(ImportPhase.Finished(ImportSummary(0, 0, emptyList(), false, 0)), migration.state.value.run?.phase, "a second import brings nothing new and makes no profile")
        assertEquals(manifest, world.store.manifest)
        assertEquals(game, world.files.everyFile(root).keys.associateWith { world.files.bytes(root, it)?.toList() })
    }
}
