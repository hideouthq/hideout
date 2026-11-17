package gg.hideouthq.library

import gg.hideouthq.core.data.gamebanana.GameBananaFiles
import gg.hideouthq.core.library.CompatibilityPort
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.network.BusyHosts
import gg.hideouthq.core.network.logUnanswered
import gg.hideouthq.library.archive.RangedArchiveIndex
import gg.hideouthq.library.compat.NioCompatibility
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import java.nio.file.Path

object DesktopInstall {
    fun ports(data: Path, cache: Path, api: HttpClient, downloads: HttpClient, busy: BusyHosts, log: (String) -> Unit): InstallPorts = InstallPorts(
        gameBanana = GameBananaFiles(api),
        downloader = HttpDownloader(downloads, data.resolve("downloads"), hideoutAreas = listOf(data, cache), busy = busy),
        installer = NioModInstaller(data.resolve("staging")),
        gameConfig = FileGameConfig(),
        processes = ProcessTable(),
        localImport = DesktopLocalImport(data.resolve("staging").resolve("imports")),
        gameFiles = NioGameFiles(),
        archives = RangedArchiveIndex(api),
        backups = FileBackupStore(data.resolve(Originals)),
        heroSettings = FileHeroSettings(),
        log = log,
    )

    fun compatibility(data: Path): CompatibilityPort = NioCompatibility(data.resolve(Originals), data.resolve("staging"))

    fun downloadClient(engine: HttpClientEngine, userAgent: String, log: (String) -> Unit = {}): HttpClient = HttpClient(engine) {
        expectSuccess = false
        install(UserAgent) { agent = userAgent }
        logUnanswered(log)
        install(HttpTimeout) {
            connectTimeoutMillis = ConnectTimeoutMillis
            socketTimeoutMillis = SocketTimeoutMillis
        }
    }

    private const val Originals = "originals"

    private const val ConnectTimeoutMillis = 15_000L
    private const val SocketTimeoutMillis = 15_000L
}
