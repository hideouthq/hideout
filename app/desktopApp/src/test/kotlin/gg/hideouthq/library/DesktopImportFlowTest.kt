package gg.hideouthq.library

import gg.hideouthq.core.crosshair.Crosshairs
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.gamebanana.GameBananaFiles
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.ImportSummary
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.WatchTiming
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.core.migration.ImportChoice
import gg.hideouthq.core.migration.ImportPhase
import gg.hideouthq.core.migration.ImportSource
import gg.hideouthq.core.migration.Migration
import gg.hideouthq.core.migration.MigrationRecordCodec
import gg.hideouthq.core.migration.RecordOutcome
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.library.archive.RangedArchiveIndex
import gg.hideouthq.preview.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DesktopImportFlowTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "import-flow-tests")), "home").toRealPath()
    private val game = home.resolve("Deadlock")
    private val root = GameRoot(game.toString())
    private val citadel = game.resolve("game/citadel").createDirectories()
    private val addons = citadel.resolve("addons").createDirectories()
    private val data = home.resolve("data").createDirectories()
    private val user = home.resolve("user").createDirectories()
    private val holds = home.resolve("holds").createDirectories()
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<FileLibraryStore>()

    @AfterTest
    fun stop() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        stores.forEach { it.close() }
        deleteTree(home)
    }

    private fun put(path: String, bytes: ByteArray): Path = citadel.resolve(path).also {
        it.parent.createDirectories()
        it.writeBytes(bytes)
    }

    private val ownBytes = FakeVpk.directory(seed = 90)
    private val own = ManifestRecord(
        "own", root, "Hideout's own", Placement.On(Slot(9)),
        listOf(InstalledSet(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 1, 10, null)), null, null, SetOrigin.Imported(Sha256.image(ownBytes)), null, "pak09_dir.vpk", listOf(SetFile(VpkPart.Directory, Sha256.image(ownBytes))))),
    )

    private val http = HttpClient(MockEngine { request ->
        if (request.url.encodedPath.endsWith("/700001")) {
            respond(
                """{"_idRow":700001,"_sName":"Neon Haze","_bIsTrashed":false,"_bIsWithheld":false,"_bIsPrivate":false,"_aFiles":[{"_idRow":1800001,"_sFile":"neon.zip","_nFilesize":10,"_nDownloadCount":1,"_tsDateAdded":1,"_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/1800001","_sMd5Checksum":"${"a".repeat(32)}"}]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        } else {
            respondError(HttpStatusCode.NotFound)
        }
    }) { expectSuccess = false }

    private fun start(managers: DesktopManagerData, client: HttpClient = http): Pair<ModLibrary, Migration> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> throw e }).also { scopes += it }
        val store = FileLibraryStore(data).also { stores += it }
        runBlocking { store.save(LibraryManifest(records = listOf(own))) }
        val ports = InstallPorts(
            GameBananaFiles(client), HttpDownloader(client, data.resolve("downloads"), hideoutAreas = listOf(data)), NioModInstaller(data.resolve("staging")), FileGameConfig(), FakeProcesses(),
            localImport = DesktopLocalImport(data.resolve("imports")), gameFiles = NioGameFiles(), archives = RangedArchiveIndex(client),
        )
        val library = ModLibrary(SteamGameLocator(steamInstalls = { emptyList() }), Preferences(FileCacheStorage(data.toFile())), store, NioGameInventory, ports, scope, watch = WatchTiming(debounce = 200.milliseconds, period = 2.seconds, processPoll = 200.milliseconds))
        runBlocking {
            library.useGameFolder(game.toString())
            library.start()
        }
        val crosshairs = Crosshairs(library, FileCacheStorage(data.toFile()), scope).apply { start() }
        val migration = Migration(library, NioGameInventory, managers, FileMigrationRecords(data.resolve("migrations")), crosshairs, scope).apply { start() }
        runBlocking { withTimeout(20_000) { library.state.first { state -> state.library.let { it is LibraryStatus.Ready && it.interrupted == null } } } }
        return library to migration
    }

    private fun Migration.importAll(source: ImportSource): ImportSummary {
        import(source, ImportChoice.Everything)
        val phase = runBlocking { withTimeout(30_000) { state.first { it.run?.running == false }.run!!.phase } }
        return assertIs<ImportPhase.Finished>(phase, "the import stopped: $phase").summary
    }

    private fun ModLibrary.awaitSettled(): List<ManifestRecord> = runBlocking {
        withTimeout(20_000) { state.first { it.choiceSync == ChoiceSync.Settled && it.library is LibraryStatus.Ready } }.library.let { (it as LibraryStatus.Ready).records }
    }

    private fun fileKey(path: Path): Any = checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey())

    private fun vpks(): Map<String, Any> = Files.walk(citadel).use { paths ->
        paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".vpk", ignoreCase = true) }.toList()
    }.associate { citadel.relativize(it).toString().replace('\\', '/') to fileKey(it) }

    private fun holdEvery(): Map<String, Path> = vpks().keys.withIndex().associate { (index, path) ->
        path to Files.createLink(holds.resolve("$index.vpk"), citadel.resolve(path))
    }

    private fun bytesUnder(folder: Path): Map<String, List<Byte>> = Files.walk(folder).use { paths ->
        paths.filter { Files.isRegularFile(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }.toList()
    }.associate { folder.relativize(it).toString() to it.readBytes().toList() }

    private fun recordFolder(): Path = Files.list(data.resolve("migrations")).use { it.toList() }.single()

    private val dmmInfo = (
        "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\t// Deadlock Mod Manager - Start\r\n" +
            "\t\t\tGame_UILanguage\tcitadel_*LANGUAGE*\r\n\t\t\tGame\tcitadel/addons\r\n\t\t\tMod\tcitadel\r\n\t\t\tWrite\tcitadel\r\n\t\t\tGame\tcitadel\r\n" +
            "\t\t\tMod\tcore\r\n\t\t\tWrite\tcore\r\n\t\t\tGame\tcore\r\n\t\t\t// Deadlock Mod Manager - End\r\n\t\t}\r\n\t}\r\n}\r\n"
        ).encodeToByteArray()
    private val dmmInfoTakenOver = (
        "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n" +
            "\t\t\tGame_UILanguage\tcitadel_*LANGUAGE*\r\n\t\t\tMod\tcitadel\r\n\t\t\tWrite\tcitadel\r\n" +
            "\t\t\t// Hideout start\r\n\t\t\tGame\tcitadel/addons\r\n\t\t\t// Hideout end\r\n\t\t\tGame\tcitadel\r\n" +
            "\t\t\tMod\tcore\r\n\t\t\tWrite\tcore\r\n\t\t\tGame\tcore\r\n\t\t}\r\n\t}\r\n}\r\n"
        ).encodeToByteArray()

    private fun dmmState(): ByteArray {
        fun mod(id: Long, name: String) = """{"remoteId":"$id","name":"$name","installOrder":${id - 700000},"selectedDownloads":[{"name":"$name.zip","url":"https://gamebanana.com/dl/${id + 1_100_000}"}]}"""
        val state = """{"profiles":{"default":{"name":"Default","folderName":null,"mods":[${mod(700_001, "Neon Haze")},${mod(700_002, "Iron Sights")},${mod(700_003, "Parked Skin")}]},
            |"x":{"name":"Ranked","folderName":"profile_x","mods":[${mod(700_001, "Neon Haze")}]}},"activeProfileId":"default","localMods":[]}""".trimMargin()
        return buildJsonObject { put("local-config", JsonPrimitive("""{"state":$state,"version":32}""")) }.toString().encodeToByteArray()
    }

    @Test
    fun aDmmImportAdoptsEveryFileInPlaceAndTakesTheGameOverFromIt() {
        citadel.resolve("gameinfo.gi").writeBytes(dmmInfo)
        put("cfg/autoexec.cfg", "bind F5 kill\r\n// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===\r\ncitadel_crosshair_pip_gap \"-7\"\r\n// === End Crosshair Settings ===\r\n".encodeToByteArray())
        put("addons/pak09_dir.vpk", ownBytes)
        put("addons/pak01_dir.vpk", FakeVpk.directory(seed = 1))
        put("addons/pak02_dir.vpk", FakeVpk.directory(chunkReads = mapOf(0 to 8L), seed = 2))
        put("addons/pak02_000.vpk", ByteArray(8) { 2 })
        put("addons/pak04_dir.vpk", FakeVpk.directory(seed = 4))
        put("addons/700003_Parked Skin.vpk", FakeVpk.directory(seed = 3))
        put("addons/profile_x/pak01_dir.vpk", FakeVpk.directory(seed = 1))
        put("addons/.dmm-clear/pak05_dir.vpk", FakeVpk.directory(seed = 5))
        put("addons-backups/addons-backup-2026-09-22/pak01_dir.vpk", FakeVpk.directory(seed = 6))
        put("addons-backups/addons-backup-2026-09-22/.dmm.json", "{\"version\":3,\"mods\":{}}".encodeToByteArray())
        val manifest = put(
            "addons/.dmm.json",
            """{"version":3,"mods":{"700001":{"enabled":true,"order":null,"shard":1,"currentVpks":["pak01_dir.vpk"],"disabledVpks":[],"originalVpkNames":["neon_dir.vpk"]},
              |"700002":{"enabled":true,"order":null,"shard":1,"currentVpks":["pak02_dir.vpk"],"disabledVpks":[],"originalVpkNames":["sights_dir.vpk"]},
              |"700003":{"enabled":false,"order":null,"shard":1,"currentVpks":[],"disabledVpks":["700003_Parked Skin.vpk"],"originalVpkNames":["Parked Skin.vpk"]}}}""".trimMargin().encodeToByteArray(),
        ).readBytes()
        put("addons/profile_x/.dmm.json", """{"version":3,"mods":{"700001":{"enabled":true,"order":null,"shard":1,"currentVpks":["pak01_dir.vpk"],"disabledVpks":[],"originalVpkNames":["neon_dir.vpk"]}}}""".encodeToByteArray())
        val replays = home.resolve("replays").createDirectories().also { it.resolve("match.dem").writeBytes(byteArrayOf(7)) }
        Files.createSymbolicLink(addons.resolve("replays"), replays)
        val stateFile = user.resolve("xdg-data/dev.stormix.deadlock-mod-manager/state.json").also { it.parent.createDirectories(); it.writeBytes(dmmState()) }
        val untouched = listOf("addons/.dmm-clear", "addons-backups").associateWith { bytesUnder(citadel.resolve(it)) }
        val before = vpks()
        val held = holdEvery()

        val (library, migration) = start(DesktopManagerData(user, mapOf("XDG_DATA_HOME" to user.resolve("xdg-data").toString())::get, windows = false))
        val summary = migration.importAll(ImportSource.Installed(Manager.DeadlockModManager))

        assertEquals(ImportSummary(on = 3, off = 1, profiles = listOf("Ranked"), crosshair = true, commands = 0), summary)
        val records = library.awaitSettled()
        val adopted = records.filter { it.installId != "own" }
        assertEquals(listOf("Neon Haze" to 1, "Iron Sights" to 2, "pak04_dir.vpk" to 4, "Parked Skin" to null), adopted.map { it.title to it.numbers.firstOrNull()?.value })
        assertEquals(
            listOf(1_800_001L, 1_800_002L).map { InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, it - 1_100_000, it, null)) },
            adopted.take(2).map { it.sets.single().source },
            "each mod comes as the file DMM recorded, without asking GameBanana",
        )
        for (claimed in listOf("addons/pak01_dir.vpk", "addons/pak02_dir.vpk", "addons/pak02_000.vpk", "addons/pak04_dir.vpk")) {
            assertTrue(Files.isSameFile(held.getValue(claimed), citadel.resolve(claimed)), "$claimed is claimed where it is")
        }
        val parked = adopted.last()
        assertTrue(Files.isSameFile(held.getValue("addons/700003_Parked Skin.vpk"), game.resolve(parked.files.single().relativePath(parked))), "the parked file has its Hideout name and is the same file")
        assertTrue(!citadel.resolve("addons/700003_Parked Skin.vpk").exists())
        assertEquals((before - "addons/profile_x/pak01_dir.vpk").values.toSet(), vpks().values.toSet(), "no VPK was copied: every file under the game is one that was there, less the other profile's copy")
        assertTrue(!citadel.resolve("addons/profile_x").exists(), "the profile's folder goes with its copy and manifest")
        assertTrue(Files.isSameFile(held.getValue("addons/pak09_dir.vpk"), addons.resolve("pak09_dir.vpk")))
        assertContentEquals(ownBytes, addons.resolve("pak09_dir.vpk").readBytes(), "Hideout's own mod is untouched")
        untouched.forEach { (path, bytes) -> assertEquals(bytes, bytesUnder(citadel.resolve(path)), "$path is untouched") }
        assertEquals(listOf<Byte>(7), replays.resolve("match.dem").readBytes().toList())
        assertTrue(Files.isSymbolicLink(addons.resolve("replays")))
        assertContentEquals(dmmState(), stateFile.readBytes(), "the manager's own data is only read")

        assertTrue(!citadel.resolve("addons/.dmm.json").exists() && !citadel.resolve("addons/profile_x/.dmm.json").exists(), "the manager's manifests go")
        assertContentEquals(manifest, recordFolder().resolve("files/addons/.dmm.json").readBytes(), "and their copies stay in the record")
        assertContentEquals(dmmInfo, recordFolder().resolve("files/gameinfo.gi").readBytes())
        assertContentEquals(dmmInfoTakenOver, citadel.resolve("gameinfo.gi").readBytes())
        assertEquals(
            "bind F5 kill\r\n// === Deadlock Mod Manager - Crosshair Settings (DO NOT EDIT) ===\r\ncitadel_crosshair_pip_gap \"-7\"\r\n// === End Crosshair Settings ===\r\n",
            citadel.resolve("cfg/autoexec.cfg").readBytes().decodeToString(),
            "the crosshair joins Yours and DMM's lines stay as they were",
        )
        val record = checkNotNull(MigrationRecordCodec.decode(recordFolder().resolve("record.json").readBytes()))
        assertIs<RecordOutcome.Finished>(record.outcome)
        assertEquals(setOf("addons/pak01_dir.vpk", "addons/pak02_dir.vpk", "addons/pak02_000.vpk", "addons/pak04_dir.vpk", "addons/700003_Parked Skin.vpk", "addons/profile_x/pak01_dir.vpk"), record.adopted.keys.map { it.value }.toSet())
    }

    @Test
    fun aModTheManagerKnowsOnlyBySubmissionIsPinnedAfterwardsToTheFileWhoseArchiveHoldsIt() {
        citadel.resolve("gameinfo.gi").writeBytes(dmmInfo)
        val noBag = FakeVpk.directory(seed = 41)
        put("addons/pak01_dir.vpk", noBag)
        put("addons/.dmm.json", """{"version":3,"mods":{"700005":{"enabled":true,"order":null,"shard":1,"currentVpks":["pak01_dir.vpk"],"disabledVpks":[],"originalVpkNames":["pak02_dir.vpk"]}}}""".encodeToByteArray())
        val state = """{"profiles":{"default":{"name":"Default","folderName":null,"mods":[{"remoteId":"700005","name":"Ace","installOrder":1}]}},"activeProfileId":"default","localMods":[]}"""
        user.resolve("xdg-data/dev.stormix.deadlock-mod-manager/state.json").also { it.parent.createDirectories() }
            .writeBytes(buildJsonObject { put("local-config", JsonPrimitive("""{"state":$state,"version":32}""")) }.toString().encodeToByteArray())
        val archives = mapOf(1_800_051L to zip("ace_nobag/pak02_dir.vpk" to noBag), 1_800_052L to zip("ace/pak02_dir.vpk" to FakeVpk.directory(seed = 42)))
        fun file(id: Long, name: String) =
            """{"_idRow":$id,"_sFile":"$name","_nFilesize":${archives.getValue(id).size},"_nDownloadCount":1,"_tsDateAdded":$id,"_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/$id","_sMd5Checksum":"${"%032x".format(id)}"}"""
        val listing = """{"_idRow":700005,"_sName":"Ace (One Piece) - Infernus","_bIsTrashed":false,"_bIsWithheld":false,"_bIsPrivate":false,"_aFiles":[${file(1_800_052, "ace.zip")},${file(1_800_051, "ace_nobag.zip")}]}"""
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/apiv11/Mod/700005") respond(listing, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            else download(request, archives) ?: respondError(HttpStatusCode.NotFound)
        }) { expectSuccess = false }

        val (library, migration) = start(DesktopManagerData(user, mapOf("XDG_DATA_HOME" to user.resolve("xdg-data").toString())::get, windows = false), client)
        migration.importAll(ImportSource.Installed(Manager.DeadlockModManager))

        val ace = runBlocking { withTimeout(20_000) { library.state.first { state -> state.unasked.isEmpty() } } }.let { (it.library as LibraryStatus.Ready).records.single { record -> record.installId != "own" } }
        assertEquals("Ace (One Piece) - Infernus", ace.title, "found on GameBanana once it came in")
        assertEquals(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 700_005, 1_800_051, "%032x".format(1_800_051))), ace.sets.single().source, "both archives hold a pak02_dir.vpk; the bytes in the game are the older one's")
    }

    @Test
    fun aGrimoireImportPutsItsOwnFolderFirstAndItsParkedModsOff() {
        citadel.resolve("gameinfo.gi").writeBytes(
            "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame\tcitadel/grimoire\n\t\t\tGame\tcitadel/addons\n\t\t\tGame\tcitadel/addons1\n\t\t\tMod\tcitadel\n\t\t\tWrite\tcitadel\n\t\t\tGame\tcitadel\n\t\t\tGame\tcore\n\t\t}\n\t}\n}\n".encodeToByteArray(),
        )
        put("cfg/autoexec.cfg", "// === CROSSHAIR SETTINGS (Mod Manager) ===\ncitadel_crosshair_pip_gap 3\n// === END CROSSHAIR ===\nfps_max 240\n".encodeToByteArray())
        put("addons/pak09_dir.vpk", ownBytes)
        (1..4).forEach { put("grimoire/pak0${it}_dir.vpk", FakeVpk.directory(seed = 10 + it)) }
        put("grimoire/pak05_dir.vpk", FakeVpk.directory(chunkReads = mapOf(0 to 6L), seed = 15))
        put("grimoire/pak05_000.vpk", ByteArray(6) { 5 })
        put("addons/pak01_dir.vpk", FakeVpk.directory(seed = 21))
        put("addons1/pak01_dir.vpk", FakeVpk.directory(seed = 22))
        put("addons/.disabled/night_market_dir.vpk", FakeVpk.directory(seed = 23))
        Files.createSymbolicLink(citadel.resolve("grimoire/replays"), home.resolve("replays").createDirectories())
        user.resolve(".config/grimoire").createDirectories().resolve("profiles.json")
            .writeBytes("""[{"id":"fun","name":"Fun","mods":[{"fileName":"x","enabled":true,"sha256":"${sha256Hex(FakeVpk.directory(seed = 21))}"}]}]""".encodeToByteArray())
        val before = vpks()
        val held = holdEvery()

        val (library, migration) = start(DesktopManagerData(user, emptyMap<String, String>()::get, windows = false))
        val summary = migration.importAll(ImportSource.Installed(Manager.Grimoire))

        assertEquals(ImportSummary(on = 7, off = 1, profiles = listOf("Fun"), crosshair = true, commands = 0), summary)
        val records = library.awaitSettled().sortedBy { it.numbers.firstOrNull()?.value ?: Int.MAX_VALUE }
        assertEquals(
            listOf("Grimoire Locker · hero cards", "Grimoire Locker · hero sounds", "Grimoire Locker · hero colors", "Grimoire Locker · trippy skins", "pak05_dir.vpk", "pak01_dir.vpk", "Hideout's own", "pak01_dir.vpk (pak01_dir.vpk)", "night_market_dir.vpk"),
            records.map { it.title },
            "Grimoire's own folder loads first, then addons, then addons1, and its parked mod is off",
        )
        assertEquals(listOf(true, true, true, true, true, true, true, true, false), records.map { it.enabled })
        for ((path, link) in held) {
            if (path == "addons/pak09_dir.vpk") continue
            val record = records.single { record -> record.files.any { Files.isSameFile(link, game.resolve(it.relativePath(record))) } }
            assertTrue(record.installId != "own", "$path is adopted, the same file under its Hideout name")
        }
        assertEquals(before.values.toSet(), vpks().values.toSet(), "no VPK was copied")
        assertTrue(Files.isSymbolicLink(citadel.resolve("grimoire/replays")), "a folder of Grimoire's that still holds a link stays, with it")
        assertTrue(!citadel.resolve("addons/.disabled").exists() && !citadel.resolve("addons1").exists(), "and those the import emptied go")
        assertEquals(
            "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tMod\tcitadel\n\t\t\tWrite\tcitadel\n\t\t\t// Hideout start\n\t\t\tGame\tcitadel/addons\n\t\t\t// Hideout end\n\t\t\tGame\tcitadel\n\t\t\tGame\tcore\n\t\t}\n\t}\n}\n",
            citadel.resolve("gameinfo.gi").readBytes().decodeToString(),
        )
        assertEquals(
            "// === CROSSHAIR SETTINGS (Mod Manager) ===\ncitadel_crosshair_pip_gap 3\n// === END CROSSHAIR ===\nfps_max 240\n",
            citadel.resolve("cfg/autoexec.cfg").readBytes().decodeToString(),
            "the crosshair joins Yours and Grimoire's lines stay as they were",
        )
    }

    @Test
    fun aBundleAdoptsWhatIsInTheGameAndCopiesTheRest() {
        citadel.resolve("gameinfo.gi").writeBytes("GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\t// Hideout start\n\t\t\tGame\tcitadel/addons\n\t\t\t// Hideout end\n\t\t\tGame\tcitadel\n\t\t\tGame\tcore\n\t\t}\n\t}\n}\n".encodeToByteArray())
        put("addons/pak09_dir.vpk", ownBytes)
        val inGame = FakeVpk.directory(seed = 31)
        put("addons/pak03_dir.vpk", inGame)
        val bundle = home.resolve("deadlock-mods-export-20261021").createDirectories()
        val copied = FakeVpk.directory(chunkReads = mapOf(0 to 4L), seed = 32)
        bundle.resolve("files/0001-b").createDirectories()
        bundle.resolve("files/0001-b/b_dir.vpk").writeBytes(copied)
        bundle.resolve("files/0001-b/b_000.vpk").writeBytes(ByteArray(4) { 9 })
        bundle.resolve("mod-interchange.json").writeBytes(
            """{"format":"deadlock-mod-interchange","version":1,"mods":[
              |{"key":"local:a","name":"In game","enabled":true,"order":0,"origin":{"provider":"local"},"files":[{"name":"a_dir.vpk","path":"files/0000-a/a_dir.vpk","sha256":"${sha256Hex(inGame)}","size":${inGame.size}}]},
              |{"key":"local:b","name":"Copied","enabled":true,"order":1,"origin":{"provider":"local"},"files":[{"name":"b_dir.vpk","path":"files/0001-b/b_dir.vpk","sha256":"${sha256Hex(copied)}","size":${copied.size}},{"name":"b_000.vpk","path":"files/0001-b/b_000.vpk"}]}],
              |"profiles":[{"key":"profile:fun","name":"Fun","active":false,"mods":[{"modKey":"local:a","enabled":true},{"modKey":"local:b","enabled":true}]}]}""".trimMargin().encodeToByteArray(),
        )
        val bundleBefore = bytesUnder(bundle)
        val held = holdEvery()

        val (library, migration) = start(DesktopManagerData(user, emptyMap<String, String>()::get, windows = false))
        val summary = migration.importAll(ImportSource.Bundle(bundle.toString()))

        assertEquals(ImportSummary(on = 2, off = 0, profiles = listOf("Fun"), crosshair = false, commands = 0), summary)
        val records = library.awaitSettled()
        val inPlace = records.single { it.title == "In game" }
        assertTrue(Files.isSameFile(held.getValue("addons/pak03_dir.vpk"), game.resolve(inPlace.files.single().relativePath(inPlace))), "a bundle mod in the game is adopted where it is")
        val copy = records.single { it.title == "Copied" }
        assertContentEquals(copied, game.resolve(copy.files.first().relativePath(copy)).readBytes())
        assertEquals(2, copy.files.size, "the copy keeps its chunk")
        assertEquals(bundleBefore, bytesUnder(bundle), "the bundle is only read")
        val profile = checkNotNull(library.state.value.profiles).profiles.single { it.name == "Fun" }
        assertEquals(setOf(inPlace.installId, copy.installId), profile.enabled)
    }
}
