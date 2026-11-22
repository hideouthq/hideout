package gg.hideouthq.library

import gg.hideouthq.core.autoexec.Autoexec
import gg.hideouthq.core.crosshair.Crosshairs
import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.data.ModInstaller
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.library.CheckBasis
import gg.hideouthq.core.library.ChoiceAnswer
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.CompatibilityPort
import gg.hideouthq.core.library.ChangeStage
import gg.hideouthq.core.library.FileEvidence
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.Provider
import gg.hideouthq.core.library.RepairChoice
import gg.hideouthq.core.library.ResourceIndexCache
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.SetCheck
import gg.hideouthq.core.library.StagingId
import gg.hideouthq.core.library.choice
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.core.migration.Migration
import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.core.network.gameBananaUntil
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.data.FileCacheStorage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Times library operations on real mods against a scratch copy of the real game, through the same classes production
 * wires. Does nothing unless HIDEOUT_BENCH_MODS names a folder with `on/` mod VPKs and `off/` folders of mod VPKs.
 */
class LibraryBench {
    @Test
    fun bench() {
        val mods = System.getenv("HIDEOUT_BENCH_MODS") ?: return
        val dir = System.getenv("HIDEOUT_BENCH_DIR")?.let(Path::of) ?: Path.of("build", "library-bench", "run").toAbsolutePath()
        val realGame = System.getenv("HIDEOUT_BENCH_GAME")?.let(Path::of) ?: Path.of(System.getProperty("user.home"), ".steam/steam/steamapps/common/Deadlock")
        val runs = System.getenv("HIDEOUT_BENCH_RUNS")?.toInt() ?: 5
        var failures = 0
        HashedFiles.during { hashed = it; failures = Bench(Path.of(mods), dir, realGame, runs).run() }
        assertEquals(0, failures, "failed ops")
    }
}

/** Every SHA-256 the bench's library made, so each op can say how often it hashed its mod's bytes. */
private lateinit var hashed: HashedFiles

private class Meter {
    class Call(val start: Long, val end: Long)

    val calls = ConcurrentLinkedQueue<Call>()
    val inFlight = AtomicInteger()

    suspend fun <T> time(block: suspend () -> T): T {
        inFlight.incrementAndGet()
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            calls += Call(start, System.nanoTime())
            inFlight.decrementAndGet()
        }
    }

    fun lastEnd(): Long = calls.maxOfOrNull { it.end } ?: 0L

    fun window(from: Long, to: Long): JsonObject {
        val inside = calls.filter { it.start in from..to }
        return buildJsonObject {
            put("n", inside.size)
            put("ms", inside.sumOf { it.end - it.start } / 1e6)
        }
    }
}

private class MeteredProcesses(private val real: GameProcesses, private val meter: Meter) : GameProcesses by real {
    override suspend fun scan() = meter.time { real.scan() }
}

/**
 * Each call the twin links answer, keyed by the library function that asked, with the bytes of the file it asked
 * about and the bytes the call read. The links do their work on the calling thread once it is on [Dispatchers.IO], so
 * the thread's read count in `/proc/thread-self/io` before and after the call is what that call read.
 */
private class HashCalls {
    class Call(val start: Long, val end: Long, val caller: String, val bytes: Long, val read: Long)

    val calls = ConcurrentLinkedQueue<Call>()

    suspend fun <T> time(kind: String, bytes: (T) -> Long, block: suspend () -> T): T {
        val caller = Throwable().stackTrace.firstOrNull { it.className.startsWith("gg.hideouthq.core.library") }
            ?.let { "${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName}" } ?: "?"
        return withContext(Dispatchers.IO) {
            val start = System.nanoTime()
            val before = threadRead()
            val result = block()
            calls += Call(start, System.nanoTime(), "$kind<$caller", bytes(result), threadRead() - before)
            result
        }
    }

    fun window(from: Long, to: Long): JsonObject = buildJsonObject {
        calls.filter { it.start in from..to }.groupBy { it.caller }.toSortedMap().forEach { (caller, inside) ->
            put(caller, buildJsonObject {
                put("n", inside.size)
                put("ms", inside.sumOf { it.end - it.start } / 1e6)
                put("mb", inside.sumOf { it.bytes } / 1e6)
                put("readMb", inside.sumOf { it.read } / 1e6)
            })
        }
    }

    private fun threadRead(): Long = Files.readAllLines(Path.of("/proc/thread-self/io")).first { it.startsWith("rchar:") }.substringAfter(':').trim().toLong()
}

private class MeteredInstaller(private val real: ModInstaller, private val hashes: HashCalls) : ModInstaller by real {
    override suspend fun inspect(entry: GameEntry, witness: GameEntry): EntryState =
        hashes.time("inspect", { (it as? EntryState.Present)?.image?.sizeBytes ?: 0 }) { real.inspect(entry, witness) }
    override suspend fun removeTwin(entry: GameEntry, witness: GameEntry, expected: FileImage): Removal =
        hashes.time("removeTwin", { expected.sizeBytes }) { real.removeTwin(entry, witness, expected) }
    override suspend fun removeExact(entry: GameEntry, expected: FileImage): Removal =
        hashes.time("removeExact", { expected.sizeBytes }) { real.removeExact(entry, expected) }
    override suspend fun unlinkTwin(entry: GameEntry, witness: GameEntry): Removal =
        hashes.time("unlinkTwin", { 0L }) { real.unlinkTwin(entry, witness) }
}

private class MeteredStore(private val real: LibraryStore, private val meter: Meter) : LibraryStore by real {
    override suspend fun save(manifest: LibraryManifest): ManifestSave = meter.time { real.save(manifest) }
}

private class MeteredCompatibility(private val real: CompatibilityPort, private val meters: Map<String, Meter>) : CompatibilityPort by real {
    override suspend fun check(record: ManifestRecord, originals: List<RetainedOriginal>, above: List<Provider>) = meters.getValue("check").time { real.check(record, originals, above) }
    override suspend fun game(root: GameRoot) = meters.getValue("game").time { real.game(root) }
    override suspend fun changed(evidence: Set<FileEvidence>) = meters.getValue("changed").time { real.changed(evidence) }
    override suspend fun derive(record: ManifestRecord, set: InstalledSet, original: RetainedOriginal, expected: SetCheck, basis: CheckBasis, above: List<Provider>) =
        meters.getValue("derive").time { real.derive(record, set, original, expected, basis, above) }
    override suspend fun retain(record: ManifestRecord, set: InstalledSet, id: String) = meters.getValue("retain").time { real.retain(record, set, id) }
    override suspend fun stageOriginal(original: RetainedOriginal, set: InstalledSet) = meters.getValue("stageOriginal").time { real.stageOriginal(original, set) }
    override suspend fun discard(staging: StagingId) = meters.getValue("discard").time { real.discard(staging) }
}

private val CompatCalls = listOf("check", "game", "changed", "derive", "retain", "stageOriginal", "discard")

/** One scratch game and data folder with a running library over it, wired as `AppContainer.live` and `liveContainer` wire it. */
private class Rig(val base: Path, realGame: Path) : AutoCloseable {
    val game: Path = base.resolve("Deadlock")
    val citadel: Path = game.resolve("game/citadel")
    val data: Path = base.resolve("data")
    private val cache = base.resolve("cache")
    val scans = Meter()
    val saves = Meter()
    val hashes = HashCalls()
    val compat = CompatCalls.associateWith { Meter() }
    val requests = AtomicInteger()
    val answers = AtomicInteger()
    val timeline = ConcurrentLinkedQueue<Pair<Long, String>>()
    val log = ConcurrentLinkedQueue<Pair<Long, String>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store: FileLibraryStore
    val library: ModLibrary

    init {
        scratchGame(realGame.resolve("game/citadel"), citadel)
        data.createDirectories()
        cache.createDirectories()
        val logFile = base.resolve("hideout.log")
        val write: (String) -> Unit = { line ->
            log += System.nanoTime() to line
            synchronized(logFile) { Files.writeString(logFile, "${Instant.now()} $line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND) }
        }
        val engine = MockEngine {
            requests.incrementAndGet()
            respondError(HttpStatusCode.NotFound)
        }
        val busy = BusyHosts()
        val api = hideoutHttpClient(engine, "HideoutBench", write, busy)
        val built = DesktopInstall.ports(data, cache, api, DesktopInstall.downloadClient(engine, "HideoutBench", write), busy, write)
        val ports = InstallPorts(
            built.gameBanana, built.downloader, MeteredInstaller(built.installer, hashes), built.gameConfig, MeteredProcesses(built.processes, scans), built.limits,
            built.localImport, built.gameFiles, built.archives, built.backups, built.heroSettings, built.log,
        )
        val storage = FileCacheStorage(cache.toFile())
        val dataStorage = FileCacheStorage(data.toFile())
        store = FileLibraryStore(data)
        val gameBananaBusy = busy.hosts.map { it.gameBananaUntil() }.stateIn(scope, SharingStarted.Eagerly, null)
        library = ModLibrary(
            SteamGameLocator(steamInstalls = { emptyList() }), Preferences(dataStorage), MeteredStore(store, saves), NioGameInventory, ports, scope,
            compatibility = MeteredCompatibility(DesktopInstall.compatibility(data), compat), resourceIndexes = ResourceIndexCache(storage, NioResourceIndexSource()),
            gameBananaBusy = gameBananaBusy,
        )
        watchers.launch {
            var last = ""
            library.state.collect { state ->
                val now = phase(state)
                if (now != last) timeline += System.nanoTime() to now
                last = now
            }
        }
        val asked = HashSet<Long>()
        watchers.launch {
            library.state.collect { state ->
                for (operation in state.operations.values) {
                    val stage = operation.stage
                    val answer = when (stage) {
                        is OperationStage.ChoosingSet -> ChoiceAnswer.Sets(stage.preselected, stage.profiles)
                        is OperationStage.ChoosingSwap -> ChoiceAnswer.KeepBoth
                        is OperationStage.ChoosingFile -> ChoiceAnswer.Files(stage.preselected.ifEmpty { setOf(stage.files.first().id) }, stage.profiles)
                        else -> continue
                    }
                    val id = checkNotNull(stage.choice)
                    if (asked.add(id.value)) {
                        answers.incrementAndGet()
                        library.answer(id, answer)
                    }
                }
            }
        }
        runBlocking { library.useGameFolder(game.toString()) }
        library.start()
        val crosshairs = Crosshairs(library, dataStorage, scope, log = write).apply { start() }
        Autoexec(library, NioGameInventory, scope).start()
        Migration(library, NioGameInventory, DesktopManagerData(base.resolve("user").createDirectories(), { null }, windows = false), FileMigrationRecords(data.resolve("migrations")), crosshairs, scope, log = write).start()
        runBlocking {
            withTimeout(120_000) {
                library.state.first { state -> state.game is GameStatus.Ready && state.library.let { it is LibraryStatus.Ready && it.interrupted == null } && state.profiles != null }
            }
        }
    }

    val state: LibraryState get() = library.state.value

    val records: List<ManifestRecord> get() = (state.library as? LibraryStatus.Ready)?.records.orEmpty()

    fun compatBusy(): Boolean = compat.values.any { it.inFlight.get() > 0 }

    fun compatLastEnd(): Long = compat.values.maxOf { it.lastEnd() }

    override fun close() = runBlocking {
        watchers.coroutineContext.job.cancelAndJoin()
        scope.coroutineContext.job.cancelAndJoin()
        store.close()
    }
}

/**
 * The game's own files a library reads, in a scratch tree on the same filesystem. pak01's chunks are hard links, since
 * the compatibility code opens them with NOFOLLOW_LINKS and copying them would take tens of gigabytes; nothing Hideout does writes
 * them, and [Bench] checks they are unchanged.
 */
private fun scratchGame(real: Path, citadel: Path) {
    citadel.resolve("addons").createDirectories()
    for (name in listOf("gameinfo.gi", "pak01_dir.vpk", "steam.inf")) Files.copy(real.resolve(name), citadel.resolve(name))
    Files.walk(real.resolve("cfg")).use { paths ->
        paths.forEach { path ->
            val target = citadel.resolve(real.relativize(path).toString())
            if (path.isDirectory()) target.createDirectories() else Files.copy(path, target)
        }
    }
    chunks(real).forEach { Files.createLink(citadel.resolve(it.name), it) }
}

private fun chunks(citadel: Path): List<Path> = Files.list(citadel).use { paths -> paths.filter { Chunk.matches(it.name) }.sorted().toList() }

private val Chunk = Regex("pak01_\\d{3}\\.vpk")

private fun phase(state: LibraryState): String {
    val sync = when (val choice = state.choiceSync) {
        ChoiceSync.Settled -> "Settled"
        ChoiceSync.Applying -> "Applying"
        is ChoiceSync.Waiting -> "Waiting(${choice.activity})"
        is ChoiceSync.Failed -> "Failed(${choice.problem})"
    }
    val operations = state.operations.values.joinToString(",") { it.stage::class.simpleName.orEmpty() }
    val changes = state.changes.values.joinToString(",") { it.stage::class.simpleName.orEmpty() }
    val repairs = state.compatibility.installs.values.mapNotNull { it.pending?.toString() }.joinToString(",")
    val checking = state.compatibility.installs.values.count { it.checking }
    val compat = if (state.compatibility.checking) "checking($checking)" else "idle"
    return "sync=$sync ops=[$operations] changes=[$changes] leaving=${state.leaving.size} saving=${state.saving} launching=${state.launching} compat=$compat repairs=[$repairs] game=${state.deadlock}"
}

private class OpFailed(message: String) : Exception(message)

private class Op(val rig: Rig) {
    val marks = LinkedHashMap<String, Long>()

    suspend fun until(label: String, timeoutMs: Long = 600_000, done: (LibraryState) -> Boolean): LibraryState {
        val reached = withTimeout(timeoutMs) { rig.library.state.first { done(it) || broken(it) != null } }
        marks[label] = System.nanoTime()
        broken(reached)?.takeIf { !done(reached) }?.let { throw OpFailed("$label: $it") }
        return reached
    }
}

/** What went wrong in [state], if anything did: a failed install, refused change, failed sync or an interrupted library. */
private fun broken(state: LibraryState): String? {
    val library = state.library
    return when {
        library is LibraryStatus.Blocked -> "library blocked: ${library.reason}"
        library is LibraryStatus.Ready && library.interrupted != null -> "interrupted: ${library.interrupted}"
        state.choiceSync is ChoiceSync.Failed -> "sync failed: ${(state.choiceSync as ChoiceSync.Failed).problem}"
        state.choiceProblem != null -> "choice refused: ${state.choiceProblem}"
        else -> state.operations.values.firstNotNullOfOrNull { (it.stage as? OperationStage.Failed)?.let { failed -> "install failed: ${failed.problem} ${failed.file.orEmpty()}" } }
            ?: state.changes.values.firstNotNullOfOrNull { (it.stage as? ChangeStage.Refused)?.let { refused -> "change refused: ${refused.problem}" } }
    }
}

/** The files are where the saved choices put them and nothing is moving toward them. */
private fun settled(state: LibraryState): Boolean {
    val records = (state.library as? LibraryStatus.Ready)?.records ?: return false
    val on = state.profiles?.activeProfile?.enabled ?: return false
    return state.operations.isEmpty() && state.changes.isEmpty() && state.choiceSync == ChoiceSync.Settled && !state.saving && records.all { (it.installId in on) == it.enabled }
}

private class Bench(private val mods: Path, private val dir: Path, private val realGame: Path, private val runs: Int) {
    private val results = mutableListOf<JsonObject>()
    private var failures = 0
    private val sources = HashMap<String, Path>()

    fun run(): Int {
        val load = Files.readString(Path.of("/proc/loadavg")).trim()
        val processes = Files.list(Path.of("/proc")).use { paths -> paths.filter { it.name.all(Char::isDigit) }.count() }
        println("bench: loadavg $load, processes $processes, cores ${Runtime.getRuntime().availableProcessors()}, runs $runs, dir $dir")
        val realCitadel = realGame.resolve("game/citadel")
        val guarded = listOf("pak01_dir.vpk", "gameinfo.gi").associateWith { sha256(realCitadel.resolve(it)) }
        val chunkStats = chunks(realCitadel).associate { it.name to (it.fileSize() to Files.getLastModifiedTime(it)) }

        if (Files.exists(dir)) deleteTree(dir)
        dir.createDirectories()
        val inputs = dir.resolve("inputs").createDirectories()
        val on = Files.list(mods.resolve("on")).use { paths -> paths.filter { it.name.endsWith(".vpk") }.sorted().toList() }
        val off = Files.list(mods.resolve("off")).use { paths -> paths.filter { it.isDirectory() }.sorted().toList() }
            .flatMap { folder -> Files.list(folder).use { paths -> paths.filter { it.name.endsWith(".vpk") }.sorted().toList() } }
        val small = variant(on.minBy { it.fileSize() }, inputs.resolve("bench-small_dir.vpk"))
        val large = variant(on.maxBy { it.fileSize() }, inputs.resolve("bench-large_dir.vpk"))
        val started = System.nanoTime()

        Rig(dir, realGame).use { rig ->
            op(rig, "idle-10s-empty", 0) { delay(10_000) }
            on.forEachIndexed { index, file -> op(rig, "setup-install", 0, mapOf("index" to index, "file" to file.name, "bytes" to file.fileSize())) { sources[install(file)] = file } }
            val offIds = off.map { file -> lateinit var id: String; op(rig, "setup-install-off", 0, mapOf("file" to file.name, "bytes" to file.fileSize())) { id = install(file); sources[id] = file }; id }
            op(rig, "setup-turn-off", 0, mapOf("mods" to offIds.size)) {
                rig.library.setEnabledTogether(offIds.toSet(), false)
                until("applied") { state -> settled(state) && rig.records.filter { it.installId in offIds }.none { it.enabled } }
            }
            op(rig, "idle-10s-full", 0) { delay(10_000) }
            for (run in 1..runs) mainRun(rig, run, small, large)
        }
        Rig(dir.resolve("empty"), realGame).use { rig ->
            for (run in 1..runs) {
                for (file in listOf(small, large)) {
                    val kind = if (file == small) "small" else "large"
                    lateinit var id: String
                    op(rig, "empty-install-$kind", run, mapOf("bytes" to file.fileSize())) { id = install(file) }
                    op(rig, "empty-toggle-off-$kind", run) { toggle(id, false) }
                    op(rig, "empty-toggle-on-$kind", run) { toggle(id, true) }
                    op(rig, "empty-uninstall-$kind", run) { uninstall(id) }
                }
            }
        }

        val after = listOf("pak01_dir.vpk", "gameinfo.gi").associateWith { sha256(realCitadel.resolve(it)) }
        val chunksAfter = chunks(realCitadel).associate { it.name to (it.fileSize() to Files.getLastModifiedTime(it)) }
        if (after != guarded) fail("the real game's ${after.filter { (name, sha) -> guarded[name] != sha }.keys} changed")
        if (chunksAfter != chunkStats) fail("the real game's pak01 chunks changed")
        println("bench: real game unchanged: ${after == guarded && chunksAfter == chunkStats} (${guarded.entries.joinToString { "${it.key} ${it.value.take(12)}" }}, ${chunkStats.size} chunks)")

        val out = Path.of("build", "library-bench").toAbsolutePath().createDirectories()
            .resolve("results-${DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now())}.jsonl")
        out.writeText(results.joinToString("\n", postfix = "\n"))
        summary()
        println("bench: ${results.size} op runs, $failures failed, ${(System.nanoTime() - started) / 1_000_000_000} s, results $out")
        return failures
    }

    private fun mainRun(rig: Rig, run: Int, small: Path, large: Path) {
        for (file in listOf(small, large)) {
            val kind = if (file == small) "small" else "large"
            lateinit var id: String
            op(rig, "install-one-$kind", run, mapOf("bytes" to file.fileSize())) { id = install(file) }
            op(rig, "uninstall-one-$kind", run) { uninstall(id) }
        }
        val enabled = rig.records.filter { it.enabled }
        val big = enabled.maxBy { it.files.sumOf { file -> file.sizeBytes } }
        val tiny = enabled.minBy { it.files.sumOf { file -> file.sizeBytes } }
        for ((kind, record) in listOf("large" to big, "small" to tiny)) {
            val bytes = record.files.sumOf { it.sizeBytes }
            op(rig, "toggle-off-$kind", run, mapOf("bytes" to bytes, "title" to record.title)) { toggle(record.installId, false) }
            op(rig, "toggle-on-$kind", run, mapOf("bytes" to bytes, "title" to record.title)) { toggle(record.installId, true) }
        }
        val order = checkNotNull(rig.state.orderView).sets
        val onIds = rig.records.filter { it.enabled }.map { it.installId }.toSet()
        val last = order.last { it in onIds }
        op(rig, "reorder-last-to-first", run, mapOf("mods" to order.size)) { reorder(listOf(last) + (order - last)) }
        op(rig, "reorder-back", run, mapOf("mods" to order.size)) { reorder(order) }

        val middle = order.filter { it in onIds }.let { it[it.size / 2] }
        val leaving = rig.records.single { it.installId == middle }
        op(rig, "uninstall", run, mapOf("title" to leaving.title, "bytes" to leaving.files.sumOf { it.sizeBytes })) { uninstall(middle) }
        val source = sources.getValue(middle)
        op(rig, "restore-install", run, mapOf("file" to source.name)) { sources[install(source)] = source }

        op(rig, "play-prep", run) {
            val problem = rig.library.launch(modded = true) { }
            marks["applied"] = System.nanoTime()
            if (problem != null) throw OpFailed("launch refused: $problem")
        }
        op(rig, "library-open", run) {
            rig.library.refreshLeftovers()
            leftoverLook(rig.library)?.join()
            marks["applied"] = System.nanoTime()
        }

        val candidate = rig.state.compatibility.installs.values.firstOrNull { it.repairable.isNotEmpty() || it.applied.isNotEmpty() }
        if (candidate == null) {
            println("bench: run $run: no installed mod has a repairable or repaired compatibility result")
            results += buildJsonObject {
                put("op", "repair")
                put("run", run)
                put("skipped", "no mod is repairable: ${rig.state.compatibility.installs.size} checked, unavailable=${rig.state.compatibility.unavailable}")
            }
        } else {
            val id = candidate.installId
            val repairedFirst = candidate.applied.isEmpty()
            for (choice in if (repairedFirst) listOf(RepairChoice.Repaired, RepairChoice.Original) else listOf(RepairChoice.Original, RepairChoice.Repaired)) {
                op(rig, if (choice == RepairChoice.Repaired) "repair-use-repaired" else "repair-use-original", run, mapOf("title" to rig.records.single { it.installId == id }.title)) {
                    if (choice == RepairChoice.Repaired) rig.library.useRepairedFiles(setOf(id)) else rig.library.useOriginalFiles(id)
                    marks["saved"] = System.nanoTime()
                    until("applied") { state ->
                        val compat = state.compatibility.installs[id]
                        settled(state) && compat != null && compat.choice == choice && compat.pending == null &&
                            (if (choice == RepairChoice.Repaired) compat.applied.isNotEmpty() || compat.problem != null else compat.applied.isEmpty())
                    }
                    rig.state.compatibility.installs[id]?.problem?.let { throw OpFailed("repair did not finish: $it") }
                }
            }
        }
    }

    private suspend fun Op.install(file: Path): String {
        val before = rig.records.map { it.installId }.toSet()
        val profiles = setOfNotNull(rig.state.profiles?.active)
        rig.library.install(InstallRequest(InstallInput.Local(LocalFile(file.toString(), file.name, file.fileSize())), profiles))
        until("recorded") { state -> (state.library as? LibraryStatus.Ready)?.records.orEmpty().any { it.installId !in before } }
        val state = until("applied") { state -> settled(state) && (state.library as LibraryStatus.Ready).records.any { it.installId !in before && it.enabled } }
        return (state.library as LibraryStatus.Ready).records.single { it.installId !in before }.installId
    }

    private suspend fun Op.toggle(id: String, enabled: Boolean) {
        rig.library.setEnabled(id, enabled)
        until("applied") { state -> settled(state) && (state.library as LibraryStatus.Ready).records.single { it.installId == id }.enabled == enabled }
    }

    private suspend fun Op.reorder(order: List<String>) {
        rig.library.reorder(checkNotNull(rig.state.orderView), order)
        until("applied") { state ->
            val records = (state.library as? LibraryStatus.Ready)?.records.orEmpty().filter { it.enabled }
            val on = records.map { it.installId }.toSet()
            settled(state) && records.sortedBy { it.numbers.first() }.map { it.installId } == order.filter { it in on }
        }
    }

    private suspend fun Op.uninstall(id: String) {
        rig.library.uninstall(setOf(id))
        until("saved") { state -> state.profiles?.profiles.orEmpty().none { id in it.enabled } }
        until("filesOff") { state -> (state.library as? LibraryStatus.Ready)?.records.orEmpty().none { it.installId == id && it.enabled } }
        until("changeStarted") { state -> id in state.changes || (state.library as? LibraryStatus.Ready)?.records.orEmpty().none { it.installId == id } }
        until("applied") { state -> settled(state) && id !in state.leaving && (state.library as LibraryStatus.Ready).records.none { it.installId == id } }
    }

    private fun op(rig: Rig, name: String, run: Int, extra: Map<String, Any> = emptyMap(), act: suspend Op.() -> Unit) {
        val records = rig.records.size
        val start = System.nanoTime()
        val op = Op(rig)
        val problems = mutableListOf<String>()
        val requests = rig.requests.get()
        val answers = rig.answers.get()
        runBlocking {
            try {
                op.act()
            } catch (e: OpFailed) {
                problems += e.message.orEmpty()
            } catch (e: TimeoutCancellationException) {
                problems += "timed out: ${phase(rig.state)}"
            }
        }
        val applied = op.marks["applied"] ?: System.nanoTime()
        val idle = runBlocking { quiet(rig, applied) }
        val end = maxOf(idle.first, applied)
        problems += idle.second?.let(::listOf).orEmpty()
        if (problems.isEmpty() && !name.startsWith("idle")) problems += verify(rig)
        if (problems.isNotEmpty()) {
            failures++
            println("bench: FAILED $name run $run: ${problems.joinToString("; ")}")
        }
        fun ms(at: Long) = (at - start) / 1e6
        results += buildJsonObject {
            put("op", name)
            put("run", run)
            put("recordsBefore", records)
            put("recordsAfter", rig.records.size)
            extra.forEach { (key, value) -> put(key, if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString())) }
            put("ok", problems.isEmpty())
            put("failures", JsonArray(problems.map(::JsonPrimitive)))
            put("appliedMs", ms(applied))
            put("compatIdleMs", ms(end))
            put("marksMs", JsonObject(op.marks.mapValues { JsonPrimitive(ms(it.value)) }))
            put("scansToApplied", rig.scans.window(start, applied))
            put("scansToIdle", rig.scans.window(start, end))
            put("savesToApplied", rig.saves.window(start, applied))
            put("savesToIdle", rig.saves.window(start, end))
            put("compatToIdle", JsonObject(rig.compat.mapValues { it.value.window(start, end) }))
            put("hashesToApplied", rig.hashes.window(start, applied))
            put("sha256", buildJsonObject {
                (extra["bytes"] as? Long)?.let { bytes ->
                    put("ofModToApplied", hashed.of(bytes, start, applied))
                    put("ofModToIdle", hashed.of(bytes, start, end))
                    put("ofModBy", JsonObject(hashed.by(bytes, start, end).mapValues { JsonPrimitive(it.value) }))
                }
                put("mbOver1MbToApplied", hashed.bytesOver(1_000_000, start, applied) / 1e6)
                put("mbOver1MbToIdle", hashed.bytesOver(1_000_000, start, end) / 1e6)
            })
            put("httpRequests", rig.requests.get() - requests)
            put("answers", rig.answers.get() - answers)
            put("timeline", JsonArray(rig.timeline.filter { it.first in start..end }.map { JsonArray(listOf(JsonPrimitive(ms(it.first)), JsonPrimitive(it.second))) }))
            put("log", JsonArray(rig.log.filter { it.first in start..end }.map { JsonArray(listOf(JsonPrimitive(ms(it.first)), JsonPrimitive(it.second))) }))
        }
        val last = results.last()
        println("bench: $name run $run: sha256 ${last["sha256"]}, applied ${"%.0f".format(ms(applied))} ms, compat idle ${"%.0f".format(ms(end))} ms, scans ${last["scansToApplied"]}, saves ${last["savesToApplied"]}, checks ${(last["compatToIdle"] as JsonObject)["check"]}")
    }

    /**
     * Waits until no compatibility check or repair runs, nothing moves toward the saved choices, and that has held for
     * [QuietMs]; returns when the last of it ended. Failed installs are not activity: [verify] reports them.
     */
    private suspend fun quiet(rig: Rig, from: Long): Pair<Long, String?> {
        var lastBusy = from
        while (true) {
            val state = rig.state
            val now = System.nanoTime()
            val busy = state.compatibility.checking || rig.compatBusy() || state.compatibility.installs.values.any { it.pending != null } ||
                state.operations.values.any { it.stage !is OperationStage.Failed } || state.changes.values.any { it.stage == ChangeStage.Running } ||
                state.choiceSync == ChoiceSync.Applying || state.choiceSync is ChoiceSync.Waiting || state.saving || state.leaving.isNotEmpty()
            if (busy) lastBusy = now
            lastBusy = maxOf(lastBusy, rig.compatLastEnd())
            if (now - lastBusy >= QuietMs * 1_000_000) return lastBusy to null
            if (now - from > 600_000_000_000) return now to "never went quiet: ${phase(state)}"
            delay(50)
        }
    }

    private fun verify(rig: Rig): List<String> {
        val state = rig.state
        val problems = mutableListOf<String>()
        broken(state)?.let { problems += it }
        val ready = state.library as? LibraryStatus.Ready ?: return problems + "library ${state.library}"
        if (!settled(state)) problems += "not settled: ${phase(state)}"
        if (ready.damaged.isNotEmpty()) problems += "damaged: ${ready.damaged}"
        if (ready.unchecked.isNotEmpty()) problems += "unchecked: ${ready.unchecked}"
        val expected = HashSet<String>()
        for (record in ready.records) {
            for (file in record.files) {
                val relative = file.relativePath(record)
                expected += relative
                val path = rig.game.resolve(relative)
                when {
                    !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> problems += "${record.title}: $relative is missing"
                    Files.size(path) != file.sizeBytes -> problems += "${record.title}: $relative has ${Files.size(path)} bytes, not ${file.sizeBytes}"
                }
                if (("/hideout_off/" in "/$relative") == record.enabled) problems += "${record.title}: $relative is in the wrong folder for enabled=${record.enabled}"
            }
        }
        val found = Files.list(rig.citadel).use { paths -> paths.filter { it.isDirectory() && (it.name.startsWith("addons") || it.name == "hideout_off") }.toList() }
            .flatMap { folder -> Files.walk(folder).use { paths -> paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.name.endsWith(".vpk") }.toList() } }
            .map { rig.game.relativize(it).toString() }.toSet()
        (found - expected).takeIf { it.isNotEmpty() }?.let { problems += "files no record owns: $it" }
        return problems
    }

    private fun fail(message: String) {
        failures++
        println("bench: FAILED $message")
    }

    private fun summary() {
        val measured = results.filter { it["ok"] == JsonPrimitive(true) }.groupBy { (it["op"] as JsonPrimitive).content }
        fun JsonObject.num(vararg path: String): Double = path.dropLast(1).fold(this) { json, key -> json[key] as JsonObject }[path.last()].let { (it as JsonPrimitive).content.toDouble() }
        fun List<Double>.median() = sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
        println("%-24s %3s %9s %9s %9s %10s %6s %6s %6s %8s".format("op", "n", "median", "min", "max", "idle-med", "scans", "saves", "checks", "check-ms"))
        for ((name, rows) in measured) {
            val applied = rows.map { it.num("appliedMs") }
            println(
                "%-24s %3d %9.0f %9.0f %9.0f %10.0f %6.0f %6.0f %6.0f %8.0f".format(
                    name, rows.size, applied.median(), applied.min(), applied.max(), rows.map { it.num("compatIdleMs") }.median(),
                    rows.map { it.num("scansToApplied", "n") }.median(), rows.map { it.num("savesToApplied", "n") }.median(),
                    rows.map { it.num("compatToIdle", "check", "n") }.median(), rows.map { it.num("compatToIdle", "check", "ms") }.median(),
                ),
            )
        }
    }
}

private const val QuietMs = 3_000L

/** The running look [ModLibrary.refreshLeftovers] started, which it does not hand back. */
private fun leftoverLook(library: ModLibrary): Job? = ModLibrary::class.java.getDeclaredField("leftoverLook").apply { isAccessible = true }.get(library) as Job?

/** A copy of [source] with bytes after its declared sections, which VPK readers ignore, so it is a distinct local file. */
private fun variant(source: Path, target: Path): Path {
    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
    Files.write(target, "hideout-bench-${target.name}".encodeToByteArray(), StandardOpenOption.APPEND)
    return target
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newByteChannel(path).use { it.digestRest(digest) }
    return digest.digest().toHexString()
}
