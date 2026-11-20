package gg.hideouthq.core.library

import gg.hideouthq.core.data.GameLocator
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.SimulatedCrash
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class FixedGameLocator(private val roots: List<GameRoot>) : GameLocator {
    var valid = true
    override val separator: Char = '/'
    override suspend fun detect(): List<GameRoot> = if (valid) roots.take(1) else emptyList()
    override suspend fun check(path: String): FolderCheck =
        roots.firstOrNull { valid && it.path == path }?.let { FolderCheck.Valid(it) } ?: FolderCheck.Invalid(path, FolderProblem.Unavailable)
}

internal val VanillaGameInfo = "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame\t\tcitadel\n\t\t\tGame\t\tcore\n\t\t}\n\t}\n}\n".encodeToByteArray()

internal val HideoutGameInfo = (GameInfoPatch.withBlock(VanillaGameInfo, HideoutLines(mods = true, customGames = false), 0) as GameInfoEdit.Patched).bytes

internal fun withoutHideoutBlock(bytes: ByteArray): ByteArray = (GameInfoPatch.withBlock(bytes, HideoutLines(mods = false, customGames = false), 0) as? GameInfoEdit.Patched)?.bytes ?: bytes

internal val LoadsAddonsGameInfo = "GameInfo\n{\n\tFileSystem\n\t{\n\t\tSearchPaths\n\t\t{\n\t\t\tGame citadel/addons\n\t\t\tGame citadel\n\t\t}\n\t}\n}\n".encodeToByteArray()

internal val DmmGameInfo = (
    "\"GameInfo\"\r\n{\r\n\tFileSystem\r\n\t{\r\n\t\tSearchPaths\r\n\t\t{\r\n\t\t\t// Deadlock Mod Manager - Start\r\n" +
        "\t\t\tGame_UILanguage\tcitadel_*LANGUAGE*\r\n\t\t\tGame\tcitadel/addons\r\n\t\t\tMod\tcitadel\r\n\t\t\tWrite\tcitadel\r\n\t\t\tGame\tcitadel\r\n" +
        "\t\t\tMod\tcore\r\n\t\t\tWrite\tcore\r\n\t\t\tGame\tcore\r\n\t\t\t// Deadlock Mod Manager - End\r\n\t\t}\r\n\t}\r\n}\r\n"
    ).encodeToByteArray()

internal fun heldMod(installId: String, root: GameRoot, bytes: ByteArray, number: Int = 1) = ManifestRecord(
    installId, root, "Hideout's $installId", placedAt(number),
    listOf(InstalledSet(InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, number.toLong(), number * 10L, null)), null, null, SetOrigin.Imported(Sha256.image(bytes)), null, "pak01_dir.vpk",
        listOf(directory(Sha256.image(bytes))))),
)

internal val testWatchTiming = WatchTiming(debounce = 100.milliseconds, period = 10.seconds)

internal val pollingWatchTiming = testWatchTiming.copy(processPoll = 1.seconds)

internal fun InMemoryLibraryStore.arranges(): Int = saved.mapNotNull { (it.pending as? PendingChange.Arrange)?.token }.distinct().size

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.started(library: ModLibrary): ModLibrary = library.also {
    it.start()
    runCurrent()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.crashableScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> check(e is SimulatedCrash) { e } })

internal interface CrashWorld {
    val files: InMemoryGameFiles
    val store: InMemoryLibraryStore

    fun TestScope.open(scope: CoroutineScope): ModLibrary

    fun check(point: FaultPoint) {}
}

internal class CrashTrace(val points: List<FaultPoint>, val saves: Int)

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <W : CrashWorld> TestScope.traced(world: W, act: CoroutineScope.(ModLibrary) -> Unit): CrashTrace {
    val points = mutableListOf<FaultPoint>()
    world.files.around = { point ->
        world.check(point)
        points += point
    }
    val saved = world.store.saved.size
    val scope = session()
    scope.act(with(world) { open(scope) })
    runCurrent()
    world.files.around = world::check
    return CrashTrace(points.toList(), world.store.saved.size - saved)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun <W : CrashWorld> TestScope.crashAtEveryStep(
    trace: CrashTrace,
    world: () -> W,
    act: CoroutineScope.(ModLibrary) -> Unit,
    converged: (W, ModLibrary) -> Unit,
) {
    fun W.crashAt(index: Int) {
        var seen = 0
        files.around = { point ->
            check(point)
            if (seen++ == index) throw SimulatedCrash(point)
        }
    }
    val cuts = trace.points.indices.map { index -> "step $index ${trace.points[index]}" to { world: W -> world.crashAt(index) } } +
        (0 until trace.saves).flatMap { index ->
            listOf(
                "before save $index" to { world: W -> var seen = 0; world.store.refuse = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig); null } },
                "after save $index" to { world: W -> var seen = 0; world.store.afterStore = { if (seen++ == index) throw SimulatedCrash(FaultPoint.ReplaceConfig) } },
            )
        }
    for ((name, cut) in cuts) for (again in listOf(null, 0, 3, 8)) {
        val world = world()
        cut(world)
        val doomed = crashableScope()
        doomed.act(with(world) { open(doomed) })
        runCurrent()
        doomed.cancel()
        world.store.refuse = { null }
        world.store.afterStore = {}
        if (again != null) {
            world.crashAt(again)
            val recovering = crashableScope()
            with(world) { open(recovering) }
            recovering.cancel()
        }
        world.files.around = world::check
        val session = session()
        val restarted = with(world) { open(session) }
        try {
            assertNull(world.store.manifest?.pending)
            val ready = assertIs<LibraryStatus.Ready>(restarted.state.value.library)
            assertNull(ready.interrupted)
            assertEquals(emptyMap(), ready.damaged)
            converged(world, restarted)
        } catch (failure: AssertionError) {
            throw AssertionError("after a crash $name, then ${again?.let { "again at recovery step $it" } ?: "a clean restart"}: ${failure.message}", failure)
        }
        session.cancel()
    }
}

private fun TestScope.session(): CoroutineScope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
