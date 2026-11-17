package gg.hideouthq.core.appupdate

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.model.CalendarDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Instant

internal val Running = AppVersion(1, 3, 2)
internal val Offered = AppVersion(1, 4, 0)
internal val Newer = AppVersion(1, 5, 0)
internal const val NotesUrl = "https://example.test/releases"

internal fun packageOf(version: AppVersion, format: PackageFormat = PackageFormat.AppImage) =
    ReleasePackage(version, format, "https://example.test/$version", 1000, "a".repeat(64))

internal fun feedOf(latest: AppVersion, format: PackageFormat? = PackageFormat.AppImage) = ReleaseFeed(
    latest,
    NotesUrl,
    listOf(ReleaseNotes(latest, CalendarDate(2026, 11, 2), ReleaseKind.Feature, emptyList(), emptyList(), emptyList(), emptyList())),
    listOfNotNull(format).associateWith { packageOf(latest, it) },
)

internal fun recordTo(to: AppVersion) = """{"to":"$to"}"""

internal class ScriptedFeed(var lastGood: KnownFeed? = null, var answer: FeedFetch = FeedFetch.Unreachable("offline")) : ReleaseFeedSource {
    var fetches = 0
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun lastGood(): KnownFeed? = lastGood

    override suspend fun fetch(): FeedFetch {
        fetches++
        gate?.await()
        return answer
    }

    fun offers(version: AppVersion, format: PackageFormat? = PackageFormat.AppImage) {
        answer = FeedFetch.Fresh(KnownFeed(feedOf(version, format), Instant.fromEpochSeconds(0)))
    }
}

internal class ScriptedPackages : UpdatePackages {
    val onDisk = mutableSetOf<ReleasePackage>()
    val kept = mutableListOf<ReleasePackage?>()
    val downloads = mutableListOf<ReleasePackage>()
    var result: PackageDownload? = PackageDownload.Verified
    var hold = CompletableDeferred<PackageDownload>()
    var progress: (Long) -> Unit = {}
    var downloadsRunning = 0
    var mostAtOnce = 0
    var windDown = Duration.ZERO
    var ignoresCancellation = false

    override suspend fun probe(pkg: ReleasePackage): Boolean {
        return pkg in onDisk
    }

    override suspend fun download(pkg: ReleasePackage, onProgress: (Long) -> Unit): PackageDownload {
        downloads += pkg
        progress = onProgress
        mostAtOnce = maxOf(mostAtOnce, ++downloadsRunning)
        try {
            val ended = result ?: if (ignoresCancellation) withContext(NonCancellable) { hold.await() } else hold.await()
            if (ended == PackageDownload.Verified) onDisk += pkg
            return ended
        } finally {
            withContext(NonCancellable) { delay(windDown) }
            downloadsRunning--
        }
    }

    override suspend fun keepOnly(pkg: ReleasePackage?) {
        kept += pkg
        onDisk.retainAll(setOfNotNull(pkg))
    }
}

internal class ScriptedMethod(private val records: CacheStorage, var outcome: InstallOutcome = InstallOutcome.Done) {
    val replaces = mutableListOf<Pair<ReleasePackage, Boolean>>()
    val installs = mutableListOf<ReleasePackage>()
    var relaunches = 0
    val recordAtCall = mutableListOf<String?>()
    var hold: CompletableDeferred<Unit>? = null

    val atExit = AppUpdateMethod.AtExit(PackageFormat.AppImage) { pkg, relaunch ->
        replaces += pkg to relaunch
        recordAtCall += records.read(RecordFile)
        hold?.await()
        outcome
    }

    val whileOpen = AppUpdateMethod.WhileOpen(
        PackageFormat.Deb,
        install = { pkg ->
            installs += pkg
            recordAtCall += records.read(RecordFile)
            hold?.await()
            outcome
        },
        relaunch = {
            relaunches++
            outcome
        },
    )
}

internal class RefusingStorage : CacheStorage by MemoryStorage() {
    override suspend fun write(name: String, text: String) = error("disk full")
}

internal class Rig(
    val test: TestScope,
    val feed: ScriptedFeed = ScriptedFeed(),
    val packages: ScriptedPackages = ScriptedPackages(),
    val records: CacheStorage = MemoryStorage(),
    val preferencesStorage: CacheStorage = MemoryStorage(),
) {
    val methods = ScriptedMethod(records)
    val preferences = Preferences(preferencesStorage)
    val modsBusy = MutableStateFlow(false)
    var busy: Flow<Boolean> = modsBusy
    val logged = mutableListOf<String>()

    fun updates(method: AppUpdateMethod? = methods.atExit, source: ReleaseFeedSource? = feed, running: AppVersion = Running) =
        AppUpdates(method, running, source, packages, records, preferences, busy, test.backgroundScope, logged::add)
}
