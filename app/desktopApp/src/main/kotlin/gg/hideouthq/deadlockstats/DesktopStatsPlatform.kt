package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.deadlockstats.DeadlockStatsRepository
import gg.hideouthq.core.data.deadlockstats.LocalSteamIdentity
import gg.hideouthq.core.data.deadlockstats.StatsRequestGate
import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstaller
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.data.desktopEngine
import gg.hideouthq.feature.stats.data.StatsPlatform
import gg.hideouthq.library.SteamLoginIdentities
import gg.hideouthq.library.SteamReplayCacheWatcher
import gg.hideouthq.library.steamInstalls
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DesktopStatsPlatform(
    userAgent: String,
    private val directory: File = AppDirs.data().resolve("deadlock-stats"),
    httpEngine: () -> HttpClientEngine = { desktopEngine() },
) : StatsPlatform, AutoCloseable {
    private val installationPreferences = Preferences(FileCacheStorage(directory.parentFile))
    private val installer = LinuxSteamTrackingInstaller(targets = {
        discoverSteamTrackingTargets(steamInstalls(), installationPreferences.gameFolder()?.let(::GameRoot))
    }, data = directory.toPath())
    override val steamTrackingInstaller: SteamTrackingInstaller get() = installer
    private val engine = lazy(httpEngine)
    private val client = lazy { hideoutHttpClient(engine.value, userAgent, busy = null) }
    private val httpClient get() = client.value
    private val requestGate = StatsRequestGate()

    override fun openTracked(scope: CoroutineScope): DeadlockStatsRepository = DeadlockStatsRepository(
        httpClient = httpClient,
        storage = FileCacheStorage(directory),
        scope = scope,
        watcher = SteamReplayCacheWatcher(steamInstalls = ::steamInstalls),
        steamTrackingFactory = {
            SteamBridgeServer(installer::endpointDirectory, scope, installer::inspect, installer::admit, installer::startupStatus)
        },
        requestGate = requestGate,
    )

    override fun openVisitor(scope: CoroutineScope): DeadlockStatsRepository = DeadlockStatsRepository(
        httpClient = httpClient, storage = MemoryOnly(), scope = scope, requestGate = requestGate,
    )

    override fun localIdentities(): List<LocalSteamIdentity> = SteamLoginIdentities.at(steamInstalls())

    override fun close() {
        if (client.isInitialized()) client.value.close()
        if (engine.isInitialized()) engine.value.close()
    }
}

private class MemoryOnly : CacheStorage {
    private val texts = ConcurrentHashMap<String, String>()
    override suspend fun read(name: String): String? = texts[name]
    override suspend fun write(name: String, text: String) {
        texts[name] = text
    }
}
