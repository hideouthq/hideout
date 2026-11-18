package gg.hideouthq.library

import gg.hideouthq.core.autoexec.AutoexecEdit
import gg.hideouthq.core.library.AutoexecWrite
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ChoiceAnswer
import gg.hideouthq.core.library.PakNumber
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetKey
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.KeyValues
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.data.GameConfig
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.ModInstaller
import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.library.BackupReason
import gg.hideouthq.core.library.BackupStage
import gg.hideouthq.core.library.BackupWork
import gg.hideouthq.core.library.ClearStage
import gg.hideouthq.core.library.Maintenance
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.ReplaceResult
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.SimulatedCrash
import gg.hideouthq.core.data.gamebanana.GameBananaFiles
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ChangeStage
import gg.hideouthq.core.library.FilesDamage
import gg.hideouthq.core.library.MoveStep
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameInfoEdit
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.LoadingOwner
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.ModLoading
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.SearchPathsCheck
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.Sha256
import gg.hideouthq.core.library.UninstallStep
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.WatchTiming
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.preview.sha256Hex
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopInstallFlowTest {
    private val home: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build", "flow-tests")), "home").toRealPath()
    private val game = home.resolve("Deadlock")
    private val root = GameRoot(game.toString())
    private val citadel = game.resolve("game/citadel").createDirectories()
    private val addons = citadel.resolve("addons").createDirectories()
    private val data = home.resolve("data").createDirectories()
    private val scopes = mutableListOf<CoroutineScope>()

    private val valve = "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\tGame_Language\t\tcitadel_*LANGUAGE*\r\n\t\t\tGame\t\t\t\tcitadel\r\n\t\t\tGame\t\t\t\tcore\r\n\t\t}\r\n\t}\r\n}\r\n".encodeToByteArray()
    private val foreign = mapOf("pak01_dir.vpk" to FakeVpk.directory(seed = 40), "631628_pak02_dir.vpk" to FakeVpk.directory(seed = 41), "readme.txt" to "mine".encodeToByteArray())

    private val dir = FakeVpk.directory(embeddedBytes = 16, chunkReads = mapOf(0 to 4_000L, 1 to 3_000L), seed = 3)
    private val chunk0 = ByteArray(4_000) { 5 }
    private val chunk1 = ByteArray(3_000) { 6 }
    private val archive = zip("Character/ivy_dir.vpk" to dir, "Character/ivy_000.vpk" to chunk0, "Character/ivy_001.vpk" to chunk1, "readme.txt" to ByteArray(10))

    private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).toHexString()

    private val horns = FakeVpk.directory(seed = 12)
    private val tail = FakeVpk.directory(seed = 13)

    private val served = mapOf(
        11L to Triple("chunked.zip", 3, archive),
        12L to Triple("horns.zip", 2, zip("Horns/horns_dir.vpk" to horns)),
        13L to Triple("tail.zip", 1, zip("tail.vpk" to tail)),
        14L to Triple("components.zip", 1, zip("Model/PAK_DIR.VPK" to dir, "Model/PAK_000.VPK" to chunk0, "Model/PAK_001.VPK" to chunk1, "Sound/PAK_DIR.VPK" to horns)),
    )

    private var listed: List<Long> = listOf(11)

    private val downloads = mutableListOf<Long>()

    private val listing: String get() = listed.joinToString(",", """{"_idRow":1,"_sName":"Test chunked set","_bIsTrashed":false,"_bIsWithheld":false,"_bIsPrivate":false,"_aFiles":[""", "]}") { id ->
        val (name, count, bytes) = served.getValue(id)
        """{"_idRow":$id,"_sFile":"$name","_nFilesize":${bytes.size},"_nDownloadCount":$count,"_tsDateAdded":1,"_sDownloadUrl":"https:\/\/gamebanana.com\/dl\/$id","_sMd5Checksum":"${md5(bytes)}"}"""
    }

    init {
        citadel.resolve("gameinfo.gi").writeBytes(valve)
        foreign.forEach { (name, bytes) -> addons.resolve(name).writeBytes(bytes) }
    }

    private val stores = mutableListOf<FileLibraryStore>()
    private val nextId = AtomicInteger()
    private var running: Pair<CoroutineScope, FileLibraryStore>? = null
    private val logged: MutableList<String> = CopyOnWriteArrayList()

    @AfterTest
    fun stop() = runBlocking {
        scopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        stores.forEach { it.close() }
        deleteTree(home)
    }

    private val http = HttpClient(MockEngine { request ->
        if (request.url.host == "gamebanana.com" && request.url.encodedPath.startsWith("/dl/")) {
            val id = request.url.encodedPath.removePrefix("/dl/").toLong()
            synchronized(downloads) { downloads += id }
            respond(served.getValue(id).third)
        } else {
            respond(listing, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }) { expectSuccess = false }

    private fun library(installer: ModInstaller = NioModInstaller(data.resolve("staging")), scope: CoroutineScope = scope(), config: GameConfig = FileGameConfig(), watch: WatchTiming = Watch): ModLibrary {
        exitRunning()
        val store = store()
        running = scope to store
        return open(store, scope, installer, config, watch).also { library ->
            await(library) { state -> state.library.let { it is LibraryStatus.Ready && it.interrupted == null } }
        }
    }

    private fun exitRunning() {
        running?.let { (scope, store) ->
            // Like a closed app, the old library must be done. A save still on its way would take the lock back from the next one.
            runBlocking { scope.coroutineContext.job.cancelAndJoin() }
            store.close()
        }
        running = null
    }

    private fun store() = FileLibraryStore(data).also { stores += it }

    private fun open(store: LibraryStore, scope: CoroutineScope, installer: ModInstaller = NioModInstaller(data.resolve("staging")), config: GameConfig = FileGameConfig(), watch: WatchTiming = Watch): ModLibrary {
        val ports = InstallPorts(GameBananaFiles(http), HttpDownloader(http, data.resolve("downloads"), hideoutAreas = listOf(data)), installer, config, FakeProcesses(), backups = FileBackupStore(data.resolve("originals")), log = { logged += it })
        val locator = SteamGameLocator(steamInstalls = { emptyList() })
        return ModLibrary(locator, Preferences(FileCacheStorage(data.toFile())), store, NioGameInventory, ports, scope, ids = { "id${nextId.incrementAndGet()}" }, watch = watch).also {
            runBlocking {
                it.useGameFolder(game.toString())
                it.start()
            }
        }
    }

    private fun scope(handler: CoroutineExceptionHandler? = null) =
        CoroutineScope(SupervisorJob() + Dispatchers.Default + (handler ?: CoroutineExceptionHandler { _, e -> throw e })).also { scopes += it }

    private fun await(library: ModLibrary, condition: (LibraryState) -> Boolean): LibraryState = runBlocking {
        withTimeout(20_000) { library.state.first(condition) }
    }

    private fun installedState(library: ModLibrary): LibraryState = await(library) { state ->
        val records = (state.library as? LibraryStatus.Ready)?.records.orEmpty()
        val on = state.profiles?.activeProfile?.enabled.orEmpty()
        state.operations.isEmpty() && records.isNotEmpty() && state.choiceSync == ChoiceSync.Settled && records.all { (it.installId in on) == it.enabled }
    }

    private fun addonsNames() = Files.list(addons).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() }

    private fun foreignUntouched() = foreign.forEach { (name, bytes) -> assertContentEquals(bytes, addons.resolve(name).readBytes(), name) }

    @Test
    fun aChunkedSetInstallsUnderOneNumberAndSurvivesARestart() {
        val record = installChunked(library())

        assertEquals(listOf(2), record.numbers.map { it.value })
        assertEquals(foreign.keys + setOf("pak02_dir.vpk", "pak02_000.vpk", "pak02_001.vpk"), addonsNames())
        assertContentEquals(chunk1, addons.resolve("pak02_001.vpk").readBytes())
        assertEquals(SearchPathsCheck.Read(LoadingOwner.Hideout, customGames = false), GameInfoPatch.check(citadel.resolve("gameinfo.gi").readBytes()))
        assertContentEquals(valve, withoutHideoutBlock(citadel.resolve("gameinfo.gi").readBytes()))
        assertEquals(emptyList(), Files.list(data.resolve("staging")).use { it.toList() }, "staging is emptied")
        foreignUntouched()

        val restarted = library(scope = scope())
        val reloaded = restarted.state.value.library as LibraryStatus.Ready
        assertEquals(listOf(record), reloaded.records)
        assertEquals(emptyMap(), reloaded.damaged)
    }

    @Test
    fun sameNamedArchiveComponentsKeepTheirChosenOrderAndChunksThroughRestart() {
        listed = listOf(14)
        val library = library()
        library.install(request())
        val choosing = await(library) { it.operations["mod-1"]?.stage is OperationStage.ChoosingSet }
        val question = assertIs<OperationStage.ChoosingSet>(choosing.operations.getValue("mod-1").stage)
        assertEquals(setOf("Model/PAK_DIR.VPK", "Sound/PAK_DIR.VPK"), question.preselected.toSet())
        assertEquals(foreign.keys, addonsNames(), "components stay in staging until confirmed")
        assertTrue(library.answer(question.id, ChoiceAnswer.Sets(listOf("Sound/PAK_DIR.VPK", "Model/PAK_DIR.VPK"), question.profiles)))
        val installed = installedState(library)
        val record = (installed.library as LibraryStatus.Ready).records.single()
        assertEquals(listOf("Sound/PAK_DIR.VPK", "Model/PAK_DIR.VPK"), record.sets.map { it.componentKey })
        assertEquals(listOf(14L, 14L), record.sets.map { it.fileId })
        assertEquals(listOf(2, 3), record.numbers.map { it.value })
        assertEquals(foreign.keys + setOf("pak02_dir.vpk", "pak03_dir.vpk", "pak03_000.vpk", "pak03_001.vpk"), addonsNames())
        assertContentEquals(horns, addons.resolve("pak02_dir.vpk").readBytes())
        assertContentEquals(dir, addons.resolve("pak03_dir.vpk").readBytes())
        assertContentEquals(chunk0, addons.resolve("pak03_000.vpk").readBytes())
        assertContentEquals(chunk1, addons.resolve("pak03_001.vpk").readBytes())
        assertEquals(listOf(14L), synchronized(downloads) { downloads.toList() })
        val restarted = library()
        assertEquals(listOf(record), (restarted.state.value.library as LibraryStatus.Ready).records)
        foreignUntouched()
    }

    @Test
    fun aGameUpdateThenReapplyPreservesNewSettingsAndDefaultKeybindingLookup() {
        val library = library()
        library.install(request())
        installedState(library)
        val updated = valve.decodeToString().replace("\tFileSystem", "\tPGIVersion \"new-game-build\"\r\n\tFileSystem").encodeToByteArray()
        val keys = "\"config\" { \"bindings\" { \"SPACE\" \"+jump\" } }".encodeToByteArray()
        citadel.resolve("cfg").createDirectories().resolve("user_keys_default.vcfg").writeBytes(keys)

        citadel.resolve("gameinfo.gi").writeBytes(updated)
        await(library) { it.loading == ModLoading.Off(notLoading = 1, vanilla = false) }
        assertNull(runBlocking { library.reapply() })
        val patched = citadel.resolve("gameinfo.gi").readBytes()
        assertContentEquals(updated, withoutHideoutBlock(patched))
        val paths = KeyValues.parse(patched.decodeToString(), escapes = false)
            .block("GameInfo")!!.block("FileSystem")!!.block("SearchPaths")!!
        val modPath = paths.string("Mod") ?: paths.string("Game")!!
        assertEquals("citadel", modPath)
        assertEquals("citadel", paths.string("Write"))
        assertContentEquals(keys, game.resolve("game/$modPath/cfg/user_keys_default.vcfg").readBytes())
        assertEquals(ModLoading.Loads(LoadingOwner.Hideout), library.state.value.loading)
        assertNull(runBlocking { library.reapply() })
        assertContentEquals(patched, citadel.resolve("gameinfo.gi").readBytes())
        foreignUntouched()
    }

    @Test
    fun aCrashWhilePublishingIsUndoneInTheModsOffFolderOnTheNextStartWithoutTouchingForeignFiles() {
        crashAtPublishing("_dir.vpk")
        assertEquals(foreign.keys, addonsNames(), "an install places nothing where the game loads it")
        val left = parkedNames()
        assertEquals(setOf(off("000"), off("001")), left.filterNot { ".hideout-" in it }.toSet(), "the crash left the chunks published")
        val copies = left.filter { ".hideout-" in it }
        assertEquals(listOf(off("000"), off("001"), off("dir")), copies.map { it.substringBefore(".hideout-") }.sorted(), "and every verified copy")
        listOf(off("000"), off("001")).forEach { chunk ->
            assertTrue(Files.isSameFile(parked.resolve(chunk), parked.resolve(copies.single { it.startsWith("$chunk.hideout-") })), "$chunk is still one file with its copy")
        }

        val restarted = library(scope = scope())
        assertEquals(emptySet(), parkedNames(), "chunks and copies are gone from the off folder")
        assertEquals(foreign.keys, addonsNames())
        assertEquals(emptyList(), (restarted.state.value.library as LibraryStatus.Ready).records)
        foreignUntouched()
        assertContentEquals(valve, citadel.resolve("gameinfo.gi").readBytes())
        assertEquals(GameStatus.Ready(root, gg.hideouthq.core.library.FoundVia.Chosen), restarted.state.value.game)
    }

    private fun crashAtPublishing(suffix: String) {
        val real = NioModInstaller(data.resolve("staging"))
        val crashing = object : ModInstaller by real {
            override suspend fun publish(root: GameRoot, folder: SetFolder, temporary: String, name: String): PublishResult {
                if (name.endsWith(suffix)) throw SimulatedCrash(FaultPoint.Publish(name))
                return real.publish(root, folder, temporary, name)
            }
        }
        crashWith(crashing) { it.install(request()) }
    }

    private fun crashWith(installer: ModInstaller, act: (ModLibrary) -> Unit) {
        val crashed = CompletableDeferred<Unit>()
        val doomed = scope(CoroutineExceptionHandler { _, e -> if (e is SimulatedCrash) crashed.complete(Unit) else crashed.completeExceptionally(e) })
        act(library(installer, doomed))
        runBlocking { withTimeout(20_000) { crashed.await() } }
        doomed.cancel()
    }

    private val parked = citadel.resolve("hideout_off/mod-1")

    private fun off(part: String) = VpkPart.parse(part)!!.fileName(SetKey.of(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 1, 11, md5(archive))), "Character/ivy_dir.vpk").offStem("id1"))

    private val offSet get() = listOf(off("dir"), off("000"), off("001"))

    private fun parkedNames() = if (Files.exists(parked)) Files.list(parked).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() } else emptySet()

    private var selectedProfiles: Set<ProfileId> = setOf(ProfileId("main"))

    private fun turnOffAfterInstalling() { selectedProfiles = emptySet() }

    private fun request() = InstallRequest(InstallInput.Remote(SubmissionRef(SubmissionKind.Mod, 1)), selectedProfiles)

    @Test
    fun restoringABackupBringsAnUninstalledModBack() {
        val library = library()
        val record = installChunked(library)
        library.backUp()
        val backup = await(library) { it.maintenance == Maintenance.Backups(BackupWork.BackUp, BackupStage.Done) }.backups.single()
        val store = citadel.resolve("hideout_backups")
        assertEquals(setOf("files", "seen", backup.id.value), Files.list(store).use { paths -> paths.map { it.fileName.toString() }.toList().toSet() })

        library.uninstall(library.uninstallConfirmation(record.installId)!!, deleteDownload = true)
        await(library) { (it.library as LibraryStatus.Ready).records.isEmpty() && it.changes.isEmpty() }
        assertEquals(foreign.keys, addonsNames())
        library.restoreBackup(backup)
        val restored = await(library) { it.maintenance == Maintenance.Backups(BackupWork.Revert(backup), BackupStage.Done) }
        assertEquals(listOf(record), (restored.library as LibraryStatus.Ready).records)
        assertEquals(foreign.keys + record.files.map { it.name }, addonsNames(), "every file is back, and no anchor or temporary is left")
        assertContentEquals(chunk1, addons.resolve("pak02_001.vpk").readBytes())
        foreignUntouched()

        val restarted = library(scope = scope()).state.value
        assertEquals(listOf(record), (restarted.library as LibraryStatus.Ready).records)
        assertEquals(emptyMap(), (restarted.library as LibraryStatus.Ready).damaged)
        assertEquals(listOf(BackupReason.BeforeRestore, BackupReason.Manual), restarted.backups.map { it.reason }, "the restore was backed up first")
    }

    @Test
    fun turnedOffAfterInstallingTheChunkedSetGoesOnlyIntoItsOffFolderAndSurvivesARestart() {
        turnOffAfterInstalling()
        val record = installChunked(library())

        assertEquals(Placement.Off, record.placement, "an off mod holds no number")
        assertEquals(offSet.toSet(), parkedNames())
        assertContentEquals(chunk1, parked.resolve(off("001")).readBytes())
        assertEquals(foreign.keys, addonsNames(), "nothing of the set ever appears in addons")
        foreignUntouched()
        assertContentEquals(valve, citadel.resolve("gameinfo.gi").readBytes(), "an off set does not make the game load mods")

        val restarted = library(scope = scope())
        val reloaded = restarted.state.value.library as LibraryStatus.Ready
        assertEquals(listOf(record), reloaded.records)
        assertEquals(emptyMap(), reloaded.damaged)
    }

    @Test
    fun anOffFolderGoesWhenTurningOnOrUninstallingEmptiesIt() {
        turnOffAfterInstalling()
        val library = library()
        val record = installChunked(library)
        assertEquals(offSet.toSet(), parkedNames())

        library.setEnabled(record.installId, true)
        await(library) { !it.applying && records(library).single().enabled }
        assertFalse(Files.exists(parked), "turning the mod on takes its emptied off folder away")
        assertTrue(Files.isDirectory(parked.parent), "hideout_off itself stays")
        library.setEnabled(record.installId, false)
        await(library) { !it.applying && !records(library).single().enabled }
        assertEquals(offSet.toSet(), parkedNames(), "turning it off again makes the folder anew")

        library.uninstall(library.uninstallConfirmation(record.installId)!!, deleteDownload = true)
        await(library) { (it.library as LibraryStatus.Ready).records.isEmpty() && it.changes.isEmpty() }
        assertFalse(Files.exists(parked), "uninstalling an off mod takes its folder too")
        assertEquals(foreign.keys, addonsNames())
        foreignUntouched()
    }

    @Test
    fun aSameByteFileAtAnUnpublishedChunkNameSurvivesRecoveryAsItWas() {
        crashAtPublishing("_000.vpk")
        val copy = parkedNames().single { it.startsWith("${off("000")}.hideout-") }
        Files.delete(parked.resolve(copy))
        val independent = parked.resolve(off("000")).also { it.writeBytes(chunk0) }
        val key = fileKey(independent)

        val restarted = library(scope = scope())
        assertEquals(setOf(off("000")), parkedNames(), "every copy is gone and the independent file stays")
        assertEquals(foreign.keys, addonsNames())
        assertContentEquals(chunk0, independent.readBytes())
        assertEquals(key, fileKey(independent))
        assertEquals(emptyList(), (restarted.state.value.library as LibraryStatus.Ready).records)
        foreignUntouched()
    }

    private fun racing(beforeDirectory: () -> Unit): ModInstaller {
        val real = NioModInstaller(data.resolve("staging"))
        return object : ModInstaller by real {
            override suspend fun publish(root: GameRoot, folder: SetFolder, temporary: String, name: String): PublishResult {
                if (name.endsWith("_dir.vpk")) beforeDirectory()
                return real.publish(root, folder, temporary, name)
            }
        }
    }

    private fun settled(library: ModLibrary): LibraryState = await(library) { state -> state.operations["mod-1"].let { it == null || it.stage is OperationStage.Failed } }

    @Test
    fun aChunkReplacedBySameBytesBeforeTheSetIsRecordedIsNeitherAdoptedNorDeleted() {
        val independent = parked.resolve(off("000"))
        var key: Any? = null
        val library = library(racing {
            Files.delete(independent)
            independent.writeBytes(chunk0)
            key = fileKey(independent)
        })
        library.install(request())
        val state = settled(library)

        assertIs<LibraryProblem.PlacementFailed>(assertIs<OperationStage.Failed>(state.operations["mod-1"]?.stage).problem)
        assertEquals(setOf(off("000")), parkedNames(), "the independent file stays, Hideout's names and copies go")
        assertEquals(foreign.keys, addonsNames())
        assertContentEquals(chunk0, independent.readBytes())
        assertEquals(key, fileKey(independent))
        assertEquals(emptyList(), (state.library as LibraryStatus.Ready).records)
        foreignUntouched()
    }

    @Test
    fun aChunkChangedInPlaceBeforeTheSetIsRecordedIsKeptAndNothingIsCommitted() {
        val chunk = parked.resolve(off("000"))
        val sentinel = ByteArray(chunk0.size) { 9 }
        val library = library(racing { chunk.writeBytes(sentinel) })
        library.install(request())
        val state = settled(library)

        val failed = assertIs<OperationStage.Failed>(state.operations["mod-1"]?.stage)
        assertIs<LibraryProblem.PlacementFailed>(failed.problem)
        assertEquals(setOf(off("000")), parkedNames(), "the changed file stays, every copy goes")
        assertEquals(foreign.keys, addonsNames())
        assertContentEquals(sentinel, chunk.readBytes())
        assertEquals(emptyList(), (state.library as LibraryStatus.Ready).records)
        foreignUntouched()
    }

    @Test
    fun aWatchWhoseFirstSubscriptionFailsStillNoticesARewrite() {
        val real = FileGameConfig()
        val subscriptions = AtomicInteger()
        val failingOnce = object : GameConfig by real {
            override fun changes(root: GameRoot, path: CitadelPath): Flow<Unit> =
                if (path == ConfigTarget.GameInfo.Path && subscriptions.getAndIncrement() == 0) flow { throw IOException("$citadel could not be watched") } else real.changes(root, path)
        }
        val library = library(config = failingOnce, watch = Watch.copy(period = 300.milliseconds))
        library.install(request())
        installedState(library)
        val deadline = System.nanoTime() + 10.seconds.inWholeNanoseconds
        while (subscriptions.get() < 2 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals(2, subscriptions.get(), "the failed watch should start again")

        Files.write(citadel.resolve("gameinfo.gi"), valve)
        await(library) { it.loading == ModLoading.Off(notLoading = 1, vanilla = false) }
    }

    private fun gameInfoNames() = Files.list(citadel).use { paths -> paths.map { it.fileName.toString() }.filter { it.startsWith("gameinfo.gi") }.toList().toSet() }

    private fun leaveInterruptedEdit(token: String, patched: ByteArray, displaced: ByteArray): PendingChange.ConfigEdit {
        exitRunning()
        val edit = PendingChange.ConfigEdit(root, ConfigTarget.GameInfo(HideoutLines(mods = true, customGames = false)), token, Sha256.image(valve), Sha256.image(patched))
        store().use { runBlocking { assertEquals(ManifestSave.Saved, it.save(LibraryManifest(pending = edit))) } }
        runBlocking { Preferences(FileCacheStorage(data.toFile())).setGameFolder(game.toString()) }
        Files.delete(citadel.resolve("gameinfo.gi"))
        citadel.resolve("gameinfo.gi.hideout-displaced-$token").writeBytes(displaced)
        return edit
    }

    @Test
    fun anEditInterruptedWhileGameInfoWasMovedAsideIsPutBackOnTheNextStart() {
        val patched = assertIs<GameInfoEdit.Patched>(GameInfoPatch.withBlock(valve, HideoutLines(mods = true, customGames = false))).bytes
        listOf("t1" to valve, "t2" to patched).forEach { (token, movedAside) ->
            leaveInterruptedEdit(token, patched, movedAside)
            citadel.resolve("gameinfo.gi.hideout-next-$token").writeBytes(patched)

            val library = library()
            assertContentEquals(movedAside, citadel.resolve("gameinfo.gi").readBytes(), token)
            assertEquals(setOf("gameinfo.gi"), gameInfoNames(), token)
            assertEquals(GameStatus.Ready(root, gg.hideouthq.core.library.FoundVia.Chosen), await(library) { it.game is GameStatus.Ready }.game, token)
        }
    }

    @Test
    fun anUnknownDisplacedFileIsNeverInstalledAndTheSavedEditStaysPending() {
        val patched = assertIs<GameInfoEdit.Patched>(GameInfoPatch.withBlock(valve, HideoutLines(mods = true, customGames = false))).bytes
        val foreignBytes = valve + "// not Hideout's edit\r\n".encodeToByteArray()
        val token = "t9"
        val edit = leaveInterruptedEdit(token, patched, foreignBytes)

        val testScope = scope()
        val testStore = store()
        running = testScope to testStore
        val library = open(testStore, testScope)
        val waiting = await(library) { state -> state.library.let { it is LibraryStatus.Ready && it.interrupted != null } }

        assertTrue(!Files.exists(citadel.resolve("gameinfo.gi")), "unknown displaced bytes are never published as the active config")
        assertContentEquals(foreignBytes, citadel.resolve("gameinfo.gi.hideout-displaced-$token").readBytes(), "the unverified file keeps its name and bytes")
        assertNotNull((waiting.library as LibraryStatus.Ready).interrupted)

        assertEquals(edit, savedManifest().pending, "the config edit stays pending, not settled by an unproven file")
    }

    private fun savedManifest(): LibraryManifest {
        exitRunning()
        return assertIs<ManifestLoad.Loaded>(runBlocking { store().use { it.load() } }).manifest
    }

    private fun pendingOnDisk(): PendingChange? {
        exitRunning()
        return (runBlocking { store().use { it.load() } } as? ManifestLoad.Loaded)?.manifest?.pending
    }

    @Test
    fun aCfgThatIsALinkRefusesAnAutoexecWriteBeforeAnythingIsJournaled() {
        val elsewhere = home.resolve("elsewhere").createDirectories()
        Files.createSymbolicLink(citadel.resolve("cfg"), elsewhere)
        val library = library()
        assertIs<AutoexecWrite.Refused>(runBlocking { library.writeAutoexec { AutoexecEdit.Add("fps_max", "240").applyTo(it) } })
        assertEquals(emptyList(), Files.list(elsewhere).use { it.toList() }, "nothing is written through the link")
        assertNull(pendingOnDisk(), "nothing waits to be settled")
    }

    @Test
    fun aFirstAutoexecWriteStoppedAfterItsJournalIsSettledOnTheNextStart() {
        val real = FileGameConfig()
        val doomed = scope()
        val crashing = object : GameConfig by real {
            override suspend fun replace(edit: PendingChange.ConfigEdit, next: ByteArray): ReplaceResult {
                doomed.cancel()
                throw SimulatedCrash(FaultPoint.ReplaceConfig)
            }
        }
        val library = library(scope = doomed, config = crashing)
        assertFailsWith<SimulatedCrash> { runBlocking { library.writeAutoexec { AutoexecEdit.Add("fps_max", "240").applyTo(it) } } }
        assertIs<PendingChange.ConfigEdit>(pendingOnDisk(), "the stop left the write's journal")

        library()
        assertNull(pendingOnDisk())
        assertFalse(Files.exists(citadel.resolve("cfg/autoexec.cfg")), "no file is where none was")
    }

    @Test
    fun aSecondAppOnTheSameDataIsBlockedAndCleansNothingUp() {
        val real = NioModInstaller(data.resolve("staging"))
        val cleared = CompletableDeferred<Unit>()
        val first = library(installer = object : ModInstaller by real {
            override suspend fun discardAllStaging() = real.discardAllStaging().also { cleared.complete(Unit) }
        })
        runBlocking { withTimeout(20_000) { cleared.await() } }
        val extracting = data.resolve("staging/0e8400e2-9b1d-41d4-a716-446655440000").createDirectories().resolve("0.vpk").also { it.writeBytes(chunk0) }

        val second = open(store(), scope())
        val blocked = await(second) { it.library !is LibraryStatus.Loading }
        assertIs<LibraryStatus.Blocked>(blocked.library)
        assertContentEquals(chunk0, extracting.readBytes(), "the first app's extraction is left alone")

        installChunked(first)
        assertEquals(foreign.keys + setOf("pak02_dir.vpk", "pak02_000.vpk", "pak02_001.vpk"), addonsNames())
    }

    private fun installChunked(library: ModLibrary): ManifestRecord {
        library.install(request())
        val installed = installedState(library)
        return (installed.library as LibraryStatus.Ready).records.single()
    }

    private fun changed(library: ModLibrary) = await(library) { !it.applying && it.changes.values.none { change -> change.stage == ChangeStage.Running } }

    private fun records(library: ModLibrary) = (library.state.value.library as LibraryStatus.Ready).records

    private fun fileKey(path: Path): Any = checkNotNull(Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()) {
        "The fixture filesystem must expose file identity: $path"
    }

    private fun changeFilesTo(library: ModLibrary, installId: String, vararg fileIds: Long): LibraryState {
        library.changeFiles(installId)
        val question = await(library) { it.operations["mod-1"]?.stage is OperationStage.ChoosingFile }.operations.getValue("mod-1").stage as OperationStage.ChoosingFile
        assertTrue(library.answer(question.id, ChoiceAnswer.Files(fileIds.toSet(), question.profiles)))
        return await(library) { state -> state.operations["mod-1"].let { it == null || it.stage is OperationStage.Failed } && !state.applying }
    }

    @Test
    fun severalFilesOfOneModShareOneEntryAndChangeOnDiskKeepingEveryKeptFileItself() {
        listed = listOf(11, 12, 13)
        val library = library()
        library.install(InstallRequest(InstallInput.Remote(SubmissionRef(SubmissionKind.Mod, 1), setOf(12, 11)), selectedProfiles))
        installedState(library)
        val record = records(library).single()
        assertEquals(listOf(11L, 12L), record.sets.map { it.fileId }, "most downloaded first, whatever order they were asked in")
        assertEquals(listOf(2, 3), record.numbers.map { it.value }, "the lowest two free numbers in a row, past the foreign pak01")
        assertEquals(foreign.keys + set + "pak03_dir.vpk", addonsNames())
        assertContentEquals(chunk1, addons.resolve("pak02_001.vpk").readBytes())
        assertContentEquals(horns, addons.resolve("pak03_dir.vpk").readBytes())
        assertEquals(listOf("chunked.zip", "horns.zip"), record.sets.map { it.originalName })
        val winner = identities(addons)
        record.sets.forEach { Files.delete(Path.of(it.download!!.path)) }
        synchronized(downloads) { downloads.clear() }

        val swapped = changeFilesTo(library, record.installId, 11, 13)
        assertNull(swapped.operations["mod-1"], "${swapped.operations["mod-1"]}")
        assertEquals(listOf(11L, 13L), records(library).single().sets.map { it.fileId })
        assertEquals(listOf(13L), synchronized(downloads) { downloads.toList() }, "only the new file downloads, though the kept one's download is gone")
        assertEquals(winner, identities(addons), "the kept chunked set is the very same files")
        assertContentEquals(tail, addons.resolve("pak03_dir.vpk").readBytes())
        assertEquals(foreign.keys + set + "pak03_dir.vpk", addonsNames())
        val tailIdentity = fileKey(addons.resolve("pak03_dir.vpk"))

        val real = NioModInstaller(data.resolve("staging"))
        val crashing = object : ModInstaller by real {
            override suspend fun link(source: GameEntry, target: GameEntry): LinkResult {
                if (target.name == "pak04_dir.vpk") throw SimulatedCrash(FaultPoint.Link(target.name))
                return real.link(source, target)
            }
        }
        crashWith(crashing) { first ->
            first.changeFiles(record.installId)
            val question = await(first) { it.operations["mod-1"]?.stage is OperationStage.ChoosingFile }.operations.getValue("mod-1").stage as OperationStage.ChoosingFile
            assertTrue(first.answer(question.id, ChoiceAnswer.Files(setOf(11, 12, 13), question.profiles)))
        }
        assertIs<PendingChange.Replace>(savedManifest().pending)

        val restarted = library(scope = scope())
        val changed = records(restarted).single()
        assertEquals(listOf(11L, 13L, 12L), changed.sets.map { it.fileId }, "recovery preserves retained priority and adds the new component last")
        assertEquals(listOf(2, 3, 4), changed.numbers.map { it.value })
        assertEquals(record.installId, changed.installId)
        assertEquals(winner, identities(addons))
        assertEquals(tailIdentity, fileKey(addons.resolve("pak03_dir.vpk")), "Tail remains the same file at the same priority")
        assertContentEquals(horns, addons.resolve("pak04_dir.vpk").readBytes())
        assertEquals(foreign.keys + set + "pak03_dir.vpk" + "pak04_dir.vpk", addonsNames())
        assertEquals(emptyList(), leftovers())
        assertEquals(setOf(record.installId), restarted.state.value.profiles?.activeProfile?.enabled)
        foreignUntouched()

        restarted.setEnabled(record.installId, false)
        await(restarted) { !it.applying && records(restarted).single().enabled.not() }
        assertEquals(foreign.keys, addonsNames())
        assertEquals(records(restarted).single().files.map { it.name }.toSet(), parkedNames(), "every set of the mod moved off together")
        restarted.setEnabled(record.installId, true)
        await(restarted) { !it.applying && records(restarted).single().enabled }

        val confirmation = assertNotNull(restarted.uninstallConfirmation(record.installId))
        assertEquals(changed.sets.map { it.download!! }, confirmation.downloads)
        restarted.uninstall(confirmation, deleteDownload = true)
        assertNull(changed(restarted).changes[record.installId])
        assertEquals(foreign.keys, addonsNames())
        assertEquals(emptySet(), parkedNames())
        assertEquals(emptyList(), leftovers())
        changed.sets.forEach { assertFalse(Files.exists(Path.of(it.download!!.path)), it.download!!.path) }
        foreignUntouched()
        val manifest = savedManifest()
        assertEquals(emptyList(), manifest.records)
        assertTrue(manifest.downloads.none { download -> changed.sets.any { it.download!! == download.archive } })
    }

    private val set = setOf("pak02_dir.vpk", "pak02_000.vpk", "pak02_001.vpk")

    private fun identities(folder: Path, names: Collection<String> = set) = names.associateWith { fileKey(folder.resolve(it)) }

    private fun leftovers(): List<Path> = Files.walk(citadel).use { paths -> paths.filter { ".hideout-" in it.fileName.toString() && "gameinfo" !in it.fileName.toString() }.toList() }

    @Test
    fun keepOffDeletesTheDownloadFromTheChosenFolderOnceTheSetIsInstalled() {
        val chosen = home.resolve("chosen downloads").createDirectories()
        runBlocking {
            Preferences(FileCacheStorage(data.toFile())).apply {
                setKeepDownloads(false)
                setDownloadFolder(DownloadFolder(chosen.toString()))
            }
        }
        val record = installChunked(library())
        assertEquals(chosen.resolve("mod-1/11/chunked.zip").toString(), record.sets.single().download!!.path)
        assertEquals(emptyList(), Files.list(chosen).use { it.toList() }, "neither the download, an anchor of it nor the folders it emptied are left")
        assertEquals(foreign.keys + set, addonsNames())
        assertFalse(Files.exists(data.resolve("downloads")), "Hideout's own folder was never used")
        foreignUntouched()
        val manifest = savedManifest()
        assertEquals(listOf(record), manifest.records)
        assertEquals(emptyList(), manifest.downloads)
    }

    @Test
    fun clearDeletesTheUnusedDownloadAndNothingElseInItsFolder() {
        val library = library()
        val record = installChunked(library)
        library.uninstall(assertNotNull(library.uninstallConfirmation(record.installId)), deleteDownload = false)
        changed(library)
        val archive = Path.of(record.sets.single().download!!.path)
        val beside = archive.resolveSibling("notes.txt").also { it.writeBytes("mine".encodeToByteArray()) }
        val stray = data.resolve("downloads/stray.zip").also { it.writeBytes(archive.readBytes()) }

        val space = assertNotNull(runBlocking { library.measureDownloads() })
        assertEquals(1, space.matching)
        assertEquals(listOf(record.sets.single().download!!), space.clear?.downloads?.map { it.archive })
        library.clearDownloads(space.clear!!)
        val cleared = await(library) { (it.maintenance as? Maintenance.Clear)?.stage is ClearStage.Cleared }
        assertEquals(ClearStage.Cleared(removed = 1, freedBytes = record.sets.single().download!!.sizeBytes, kept = 0), (cleared.maintenance as Maintenance.Clear).stage)
        assertFalse(Files.exists(archive))
        assertEquals(listOf("notes.txt"), Files.list(archive.parent).use { paths -> paths.map { it.fileName.toString() }.toList() }, "no anchor is left and the other file stays")
        assertContentEquals("mine".encodeToByteArray(), beside.readBytes())
        assertTrue(Files.exists(stray), "a file nothing registered is never cleared, even with the same bytes")
        foreignUntouched()
    }

    @Test
    fun offAndOnMoveTheSameFilesUnderTheSameNumberAndSurviveRestarts() {
        val library = library()
        val record = installChunked(library)
        val placed = identities(addons)

        library.setEnabled(record.installId, false)
        changed(library)
        assertEquals(foreign.keys, addonsNames())
        assertEquals(offSet.toSet(), parkedNames())
        assertEquals(placed.values.toList(), identities(parked, offSet).values.toList(), "turning off moves the very same files")
        assertContentEquals(chunk1, parked.resolve(off("001")).readBytes())
        assertEquals(emptyList(), leftovers())
        foreignUntouched()

        val restarted = library(scope = scope())
        val off = restarted.state.value.library as LibraryStatus.Ready
        assertEquals(listOf(record.placed(Placement.Off)), off.records)
        assertEquals(emptyMap(), off.damaged)

        restarted.setEnabled(record.installId, true)
        changed(restarted)
        assertEquals(foreign.keys + set, addonsNames())
        assertEquals(placed, identities(addons), "and turning on brings them back under the same number")
        assertEquals(emptySet(), parkedNames())
        assertEquals(emptyList(), leftovers())
        val on = library(scope = scope()).state.value.library as LibraryStatus.Ready
        assertEquals(listOf(record), on.records)
        assertEquals(emptyMap(), on.damaged)
        foreignUntouched()
    }

    @Test
    fun reinstallPutsBackAFileAnotherProgramEditedAndKeepTakesAnEditAsTheMods() {
        val library = library()
        val record = installChunked(library)
        val requested = synchronized(downloads) { downloads.toList() }
        val edit = FakeVpk.directory(seed = 50)
        addons.resolve("pak02_dir.vpk").writeBytes(edit)
        val editKey = fileKey(addons.resolve("pak02_dir.vpk"))
        val damaged = library(scope = scope())
        assertEquals(mapOf(record.installId to FilesDamage.Changed), (damaged.state.value.library as LibraryStatus.Ready).damaged)

        damaged.reinstall(record.installId)
        await(damaged) { it.operations.isEmpty() && (it.library as? LibraryStatus.Ready)?.damaged?.isEmpty() == true }
        assertEquals(listOf(record), records(damaged))
        assertContentEquals(dir, addons.resolve("pak02_dir.vpk").readBytes())
        assertEquals(foreign.keys + set, addonsNames())
        assertEquals(emptyList(), leftovers(), "no anchor or temporary stays")
        val aside = Files.list(citadel.resolve("hideout_backups/edits")).use { it.toList() }.single().resolve("pak02_dir.vpk")
        assertEquals(editKey, fileKey(aside), "the edited file moved to the edits folder")
        assertContentEquals(edit, aside.readBytes())
        assertEquals(requested, synchronized(downloads) { downloads.toList() }, "the kept download is unpacked again, nothing is downloaded")
        foreignUntouched()

        val chunk = addons.resolve("pak02_000.vpk")
        val identity = fileKey(chunk)
        val ours = ByteArray(4_000) { 9 }
        chunk.writeBytes(ours)
        val restarted = library(scope = scope())
        assertEquals(mapOf(record.installId to FilesDamage.Changed), (restarted.state.value.library as LibraryStatus.Ready).damaged)
        restarted.keepEdits(record.installId)
        await(restarted) { (it.library as? LibraryStatus.Ready)?.damaged?.isEmpty() == true }
        val kept = records(restarted).single().files.single { it.name == "pak02_000.vpk" }
        assertEquals(Sha256.image(ours), kept.image)
        assertEquals(identity, fileKey(chunk), "nothing moved")
        assertContentEquals(ours, chunk.readBytes())
        assertEquals(emptyList(), leftovers())
        assertEquals(emptyMap(), (library(scope = scope()).state.value.library as LibraryStatus.Ready).damaged, "the edit is the mod's own after a restart too")
    }

    @Test
    fun uninstallRemovesExactlyTheSetAndTheDownloadHideoutCreatedWhenAsked() {
        val library = library()
        val record = installChunked(library)
        val archive = Path.of(record.sets.single().download!!.path)
        assertTrue(Files.exists(archive))
        val confirmation = assertNotNull(library.uninstallConfirmation(record.installId))
        assertEquals(record.sets.single().download!!, confirmation.downloads.single())

        library.uninstall(confirmation, deleteDownload = true)
        assertNull(changed(library).changes[record.installId])
        assertEquals(foreign.keys, addonsNames())
        assertFalse(Files.exists(archive))
        assertEquals(emptyList(), Files.list(data.resolve("downloads")).use { it.toList() }, "no anchor of the download is left, nor the folders it emptied")
        assertEquals(emptyList(), leftovers())
        foreignUntouched()
        val manifest = savedManifest()
        assertEquals(LibraryManifest(gameConfigs = manifest.gameConfigs), manifest)
    }

    @Test
    fun anUninstallStoppedOnceItsFilesWentLeavesNoAnchorInTheGameOrBesideTheDownloadOnTheNextStart() {
        val record = installChunked(library())
        val archive = Path.of(record.sets.single().download!!.path)
        exitRunning()
        val real = store()
        val stopping = object : LibraryStore by real {
            override suspend fun save(manifest: LibraryManifest): ManifestSave = real.save(manifest).also {
                if ((manifest.pending as? PendingChange.Uninstall)?.step is UninstallStep.Settled) throw SimulatedCrash(FaultPoint.ReplaceConfig)
            }
        }
        val crashed = CompletableDeferred<Unit>()
        val doomed = scope(CoroutineExceptionHandler { _, e -> if (e is SimulatedCrash) crashed.complete(Unit) else crashed.completeExceptionally(e) })
        running = doomed to real
        val library = open(stopping, doomed).also { await(it) { state -> state.library.let { ready -> ready is LibraryStatus.Ready && ready.interrupted == null } } }
        library.uninstall(assertNotNull(library.uninstallConfirmation(record.installId)), deleteDownload = true)
        runBlocking {
            withTimeout(20_000) { crashed.await() }
            doomed.coroutineContext.job.cancelAndJoin()
        }
        assertEquals(set, leftovers().map { it.fileName.toString().substringBefore(".hideout-") }.toSet(), "every original went, so each anchor is its file's last name")
        assertEquals(listOf(archive.fileName.toString()), Files.list(archive.parent).use { paths -> paths.map { it.fileName.toString().substringBefore(".hideout-") }.toList() }, "and the download's")

        val restarted = library(scope = scope())
        assertEquals(emptyList(), records(restarted))
        assertEquals(foreign.keys, addonsNames())
        assertEquals(emptyList(), leftovers(), "no anchor is left in the game")
        assertEquals(emptyList(), Files.list(data.resolve("downloads")).use { it.toList() }, "nor beside the download, whose emptied folders go too")
        foreignUntouched()
        val manifest = savedManifest()
        assertEquals(LibraryManifest(gameConfigs = manifest.gameConfigs), manifest)
    }

    @Test
    fun uninstallKeepsAnEditedChunkAndTheDownloadWhenNotAsked() {
        val library = library()
        val record = installChunked(library)
        addons.resolve("pak02_000.vpk").writeBytes(ByteArray(4_000) { 9 })

        library.uninstall(assertNotNull(library.uninstallConfirmation(record.installId)), deleteDownload = false)
        val done = changed(library)
        assertNull(done.changes[record.installId])
        assertEquals(foreign.keys + "pak02_000.vpk", addonsNames())
        assertContentEquals(ByteArray(4_000) { 9 }, addons.resolve("pak02_000.vpk").readBytes())
        assertTrue(Files.exists(Path.of(record.sets.single().download!!.path)))
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun uninstallKeepsAChunkEditedOnceItIsAnchoredAndLeavesNoAnchor() {
        val record = installChunked(library())
        val chunk = addons.resolve("pak02_000.vpk")
        val edited = ByteArray(4_000) { 9 }
        val real = NioModInstaller(data.resolve("staging"))
        val editing = object : ModInstaller by real {
            override suspend fun removeTwin(entry: GameEntry, witness: GameEntry, expected: FileImage): Removal {
                if (entry.name == chunk.fileName.toString()) chunk.writeBytes(edited)
                return real.removeTwin(entry, witness, expected)
            }
        }
        val library = library(editing)

        library.uninstall(assertNotNull(library.uninstallConfirmation(record.installId)), deleteDownload = false)
        assertNull(changed(library).changes[record.installId])
        assertEquals(foreign.keys + "pak02_000.vpk", addonsNames())
        assertContentEquals(edited, chunk.readBytes())
        assertEquals(emptyList(), leftovers(), "the anchor, one file with the edited chunk, goes")
    }

    @Test
    fun anUninstallUndoneWhileAnchoringKeepsTheAnchorOfAChunkAnotherProgramReplacedAndReportsIt() {
        val record = installChunked(library())
        val chunk = addons.resolve("pak02_000.vpk")
        val placed = fileKey(chunk)
        val other = ByteArray(4_000) { 7 }
        val real = NioModInstaller(data.resolve("staging"))
        val racing = object : ModInstaller by real {
            override suspend fun link(existing: GameEntry, created: GameEntry): LinkResult {
                if (created.name.startsWith("pak02_001.vpk.hideout-")) return LinkResult.Failed("the drive refused the link")
                return real.link(existing, created).also {
                    if (created.name.startsWith("${chunk.fileName}.hideout-")) {
                        Files.delete(chunk)
                        chunk.writeBytes(other)
                    }
                }
            }
        }
        val library = library(racing)

        library.uninstall(assertNotNull(library.uninstallConfirmation(record.installId)), deleteDownload = false)
        changed(library)
        val noted = record.copy(sets = listOf(record.sets.single().copy(edits = mapOf(VpkPart.Chunk(0) to Sha256.image(other)))))
        assertEquals(listOf(noted), records(library), "the uninstall is undone, and what the other program wrote is noted as an edit")
        assertContentEquals(other, chunk.readBytes(), "what the other program wrote stays")
        val anchor = leftovers().single()
        assertTrue(anchor.fileName.toString().startsWith("${chunk.fileName}.hideout-"), "only the chunk's anchor is left: $anchor")
        assertContentEquals(chunk0, anchor.readBytes(), "the only copy of the mod's chunk stays under its anchor")
        assertEquals(placed, fileKey(anchor), "as the very file Hideout placed")
        assertTrue(logged.any { "game/citadel/addons/${anchor.fileName}" in it }, "and is reported left in place: $logged")
        foreignUntouched()
    }

    @Test
    fun aCrashAfterTheFirstSourceGoesIsFinishedOnRestartWithTheSameFiles() {
        val library = library()
        val record = installChunked(library)
        val placed = identities(addons)
        val real = NioModInstaller(data.resolve("staging"))
        val crashing = object : ModInstaller by real {
            override suspend fun removeTwin(entry: GameEntry, witness: GameEntry, expected: FileImage): Removal {
                val removed = real.removeTwin(entry, witness, expected)
                if (entry.name == "pak02_dir.vpk" && entry.folder == SetFolder.Addons) throw SimulatedCrash(FaultPoint.RemoveDone(entry.name))
                return removed
            }
        }
        crashWith(crashing) { it.setEnabled(record.installId, false) }
        assertEquals(foreign.keys + setOf("pak02_000.vpk", "pak02_001.vpk"), addonsNames().filterNot { ".hideout-" in it }.toSet(), "the directory left addons first")

        val restarted = library(scope = scope())
        assertEquals(listOf(record.placed(Placement.Off)), (restarted.state.value.library as LibraryStatus.Ready).records)
        assertEquals(foreign.keys, addonsNames())
        assertEquals(placed.values.toList(), identities(parked, offSet).values.toList())
        assertEquals(emptyList(), leftovers())
        foreignUntouched()
    }

    @Test
    fun aReorderInterruptedOnDiskIsFinishedOnRestartWithTheMovedSetsSameFilesUnderTheirNewNumber() {
        val chunked = installChunked(library())
        exitRunning()
        val loneBytes = FakeVpk.directory(seed = 50)
        addons.resolve("pak05_dir.vpk").writeBytes(loneBytes)
        val lone = chunked.copy(
            installId = "lone",
            title = "Lone",
            sets = listOf(
                InstalledSet(
                    source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 2, 22, null)),
                    originalName = null,
                    variant = chunked.sets.single().variant,
                    origin = SetOrigin.Downloaded(ArchiveIdentity(data.resolve("downloads/lone.zip").toString(), 1, "a".repeat(64))),
                    version = null,
                    componentKey = "pak05_dir.vpk",
                    files = listOf(SetFile(VpkPart.Directory, FileImage(loneBytes.size.toLong(), sha256Hex(loneBytes)))),
                ),
            ),
        ).placed(Placement.On(PakNumber(5)))
        runBlocking {
            store().use { store ->
                val manifest = assertIs<ManifestLoad.Loaded>(store.load()).manifest
                val records = manifest.records + lone
                assertEquals(ManifestSave.Saved, store.save(manifest.copy(records = records, profiles = ProfileBook.main(records), savedChoices = null)))
            }
        }
        val chunkedFiles = identities(addons)
        val loneFile = fileKey(addons.resolve("pak05_dir.vpk"))

        val real = NioModInstaller(data.resolve("staging"))
        val crashing = object : ModInstaller by real {
            override suspend fun link(existing: GameEntry, created: GameEntry): LinkResult {
                val made = real.link(existing, created)
                if (created.name == "pak06_000.vpk") throw SimulatedCrash(FaultPoint.LinkMade(created.name))
                return made
            }
        }
        crashWith(crashing) { it.reorder(assertNotNull(it.state.value.orderView), listOf("lone", chunked.installId)) }
        val journal = assertIs<PendingChange.Arrange>(savedManifest().pending)
        assertEquals(MoveStep.Linking, journal.step, "the journal was read back from library.json")
        assertFalse("pak06_dir.vpk" in addonsNames(), "a chunk of the moving set is back, but not its directory")

        val restarted = library(scope = scope())
        assertEquals(listOf(chunked.placed(Placement.On(PakNumber(6))), lone), (restarted.state.value.library as LibraryStatus.Ready).records, "the chunked set moves past lone, which loads first at the number it has")
        assertEquals(foreign.keys + setOf("pak05_dir.vpk", "pak06_dir.vpk", "pak06_000.vpk", "pak06_001.vpk"), addonsNames())
        assertEquals(loneFile, fileKey(addons.resolve("pak05_dir.vpk")), "lone's own directory never moved")
        assertEquals(chunkedFiles.mapKeys { it.key.replace("pak02", "pak06") }, chunkedFiles.keys.associate { it.replace("pak02", "pak06") to fileKey(addons.resolve(it.replace("pak02", "pak06"))) })
        assertContentEquals(chunk1, addons.resolve("pak06_001.vpk").readBytes())
        assertEquals(emptyList(), leftovers())
        foreignUntouched()
    }

    @Test
    fun turningOnPlansAroundAnotherFileWithTheSameBytesAtTheNumberItWouldTake() {
        turnOffAfterInstalling()
        val library = library()
        val record = installChunked(library)
        addons.resolve("pak02_000.vpk").writeBytes(chunk0)
        val occupant = fileKey(addons.resolve("pak02_000.vpk"))

        library.setEnabled(record.installId, true)
        val settled = changed(library)
        assertEquals(ChoiceSync.Settled, settled.choiceSync, "a foreign number is planned around, not a reason to pause")
        assertEquals(listOf(3), (settled.library as LibraryStatus.Ready).records.single().numbers.map { it.value })
        assertEquals(occupant, fileKey(addons.resolve("pak02_000.vpk")), "the same bytes at 02 prove nothing, so the file stays as it is")
        assertEquals(foreign.keys + "pak02_000.vpk" + setOf("pak03_dir.vpk", "pak03_000.vpk", "pak03_001.vpk"), addonsNames())
        assertEquals(emptySet(), parkedNames())
        assertEquals(emptyList(), leftovers())
    }

    @Test
    fun uninstallFinishesWhenTheDownloadsFoldersWereDeleted() {
        val library = library()
        val record = installChunked(library)
        val archive = Path.of(record.sets.single().download!!.path)
        val modFolder = archive.parent.parent
        deleteTree(modFolder)
        val confirmation = assertNotNull(library.uninstallConfirmation(record.installId))
        assertEquals(record.sets.single().download!!, confirmation.downloads.single())

        library.uninstall(confirmation, deleteDownload = true)
        val done = changed(library)
        assertNull(done.changes[record.installId])
        assertEquals(foreign.keys, addonsNames())
        assertEquals(emptyList(), leftovers())
        assertFalse(Files.exists(modFolder), "looking for the download creates no folder")
        foreignUntouched()
        val manifest = savedManifest()
        assertEquals(LibraryManifest(gameConfigs = manifest.gameConfigs), manifest, "the registration of a download that is gone goes too")
    }

    @Test
    fun uninstallPreservesAForeignArchiveWhenTheDownloadsRootBecomesASymlinkToIt() {
        val library = library()
        val record = installChunked(library)
        val archive = Path.of(record.sets.single().download!!.path)
        val originalBytes = archive.readBytes()
        val relative = data.resolve("downloads").relativize(archive)
        val confirmation = assertNotNull(library.uninstallConfirmation(record.installId))
        assertEquals(record.sets.single().download!!, confirmation.downloads.single())

        val foreignRoot = home.resolve("foreign-tree").createDirectories()
        val foreignArchive = foreignRoot.resolve(relative).also { it.parent.createDirectories() }
        foreignArchive.writeBytes(originalBytes)
        val foreignKey = fileKey(foreignArchive)
        deleteTree(data.resolve("downloads"))
        Files.createSymbolicLink(data.resolve("downloads"), foreignRoot)

        library.uninstall(confirmation, deleteDownload = true)
        val done = changed(library)
        assertNull(done.changes[record.installId])
        assertEquals(foreign.keys, addonsNames())
        assertEquals(emptyList(), leftovers())

        assertEquals(foreignKey, fileKey(foreignArchive), "the foreign archive keeps its identity")
        assertContentEquals(originalBytes, foreignArchive.readBytes())
        assertEquals(listOf(foreignArchive.fileName.toString()), Files.list(foreignArchive.parent).use { paths -> paths.map { it.fileName.toString() }.toList() }, "no witness is created beside the foreign archive")

        val manifest = savedManifest()
        assertTrue(manifest.downloads.any { it.archive == record.sets.single().download!! }, "the archive stays registered since its removal could not be confirmed")
    }
}

private fun withoutHideoutBlock(bytes: ByteArray): ByteArray = (GameInfoPatch.withBlock(bytes, HideoutLines(mods = false, customGames = false)) as? GameInfoEdit.Patched)?.bytes ?: bytes

private val Watch = WatchTiming(debounce = 200.milliseconds, period = 2.seconds)
