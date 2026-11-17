package gg.hideouthq.core.data.fake

import gg.hideouthq.core.appupdate.AppUpdateMethod
import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.FeedFetch
import gg.hideouthq.core.appupdate.Highlight
import gg.hideouthq.core.appupdate.InstallOutcome
import gg.hideouthq.core.appupdate.KnownFeed
import gg.hideouthq.core.appupdate.PackageDownload
import gg.hideouthq.core.appupdate.PackageFormat
import gg.hideouthq.core.appupdate.RecordFile
import gg.hideouthq.core.appupdate.ReleaseEntry
import gg.hideouthq.core.appupdate.ReleaseFeed
import gg.hideouthq.core.appupdate.ReleaseFeedSource
import gg.hideouthq.core.appupdate.ReleaseKind
import gg.hideouthq.core.appupdate.ReleaseNotes
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.core.appupdate.UpdatePackages
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.model.CalendarDate
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlin.time.Clock

enum class FakeAppUpdate {
    None,
    UpToDate,
    Available,
    Downloading,
    Ready,
    FailedConnection,
    FailedServer,
    FailedMismatch,
    FailedDisk,
    FailedInstall,
    GitHubOnly,
    JustUpdated,
    Password,
}

class FakeAppUpdatePorts(private val script: FakeAppUpdate, private val running: AppVersion) {
    private val offered = AppVersion(running.major, running.minor + 1, 0)
    private val latest = if (script == FakeAppUpdate.UpToDate || script == FakeAppUpdate.JustUpdated) running else offered

    val downloadsAutomatically: Boolean get() = script != FakeAppUpdate.Available

    val method: AppUpdateMethod? = when (script) {
        FakeAppUpdate.GitHubOnly -> null
        FakeAppUpdate.Password -> AppUpdateMethod.WhileOpen(
            PackageFormat.Deb,
            install = {
                delay(PasswordMillis)
                InstallOutcome.Done
            },
            relaunch = { InstallOutcome.Done },
        )
        else -> AppUpdateMethod.AtExit(PackageFormat.AppImage) { _, _ -> InstallOutcome.Done }
    }

    val feed: ReleaseFeedSource? = if (script == FakeAppUpdate.None) null else object : ReleaseFeedSource {
        override suspend fun lastGood(): KnownFeed? = null

        override suspend fun fetch(): FeedFetch = FeedFetch.Fresh(KnownFeed(sampleFeed(), Clock.System.now()))
    }

    val packages: UpdatePackages = object : UpdatePackages {
        private var onDisk = script == FakeAppUpdate.Ready || script == FakeAppUpdate.Password || script == FakeAppUpdate.FailedInstall

        override suspend fun probe(pkg: ReleasePackage): Boolean = onDisk

        override suspend fun download(pkg: ReleasePackage, onProgress: (Long) -> Unit): PackageDownload = when (script) {
            FakeAppUpdate.Downloading -> {
                onProgress((pkg.sizeBytes * 62 + 99) / 100)
                awaitCancellation()
            }
            FakeAppUpdate.FailedConnection -> PackageDownload.Failed(UpdateFailure.Connection)
            FakeAppUpdate.FailedServer -> PackageDownload.Failed(UpdateFailure.Server)
            FakeAppUpdate.FailedMismatch -> PackageDownload.Failed(UpdateFailure.Mismatch)
            FakeAppUpdate.FailedDisk -> PackageDownload.Failed(UpdateFailure.Disk)
            else -> {
                for (step in 1..DownloadSteps) {
                    delay(DownloadStepMillis)
                    onProgress(pkg.sizeBytes * step / DownloadSteps)
                }
                onDisk = true
                PackageDownload.Verified
            }
        }

        override suspend fun keepOnly(pkg: ReleasePackage?) = Unit
    }

    val records: CacheStorage = MemoryStorage(
        when (script) {
            FakeAppUpdate.JustUpdated -> mapOf(RecordFile to """{"to":"$running"}""")
            FakeAppUpdate.FailedInstall -> mapOf(RecordFile to """{"to":"$offered"}""")
            else -> emptyMap()
        },
    )

    private fun sampleFeed() = ReleaseFeed(
        latest = latest,
        notesUrl = "https://github.com/hideouthq/hideout/releases",
        versions = listOf(featureNotes(latest)) + olderVersions().zip(olderNotes) { version, notes -> notes(version) },
        packages = listOf(PackageFormat.AppImage, PackageFormat.Deb).associateWith { format ->
            ReleasePackage(latest, format, "https://example.invalid/hideout-$latest.${format.feedKey}", SampleSizeBytes, "a".repeat(64))
        },
    )

    private fun olderVersions(): List<AppVersion> {
        val first = if (latest == running) AppVersion(running.major, (running.minor - 1).coerceAtLeast(0), 2) else running
        return generateSequence(first) { version ->
            when {
                version.patch > 0 -> version.copy(patch = version.patch - 1)
                version.minor > 0 -> version.copy(minor = version.minor - 1, patch = 4)
                else -> null
            }
        }.take(olderNotes.size).toList()
    }

    private fun featureNotes(version: AppVersion) = ReleaseNotes(
        version, CalendarDate(2026, 11, 2), ReleaseKind.Feature,
        highlights = listOf(
            Highlight("Skin collections", "Hand-picked sets on Discover, like Halloween and Winter.", "$ArtFolder/1.4.0-collections@2x.webp"),
            Highlight("Crosshairs", "Browse crosshairs from the community, or edit your own.", "$ArtFolder/1.4.0-crosshairs@2x.webp"),
        ),
        new = listOf(
            ReleaseEntry("Autoexec", "Edit autoexec.cfg as a list or as text, with a library of commands."),
            ReleaseEntry("Stats", "A match timeline with your kills and deaths."),
        ),
        improved = listOf(
            ReleaseEntry("Stats", "With live tracking on, new matches show up about a minute after they end."),
            ReleaseEntry("Setup", "A new first-run setup that brings your mods over from Deadlock Mod Manager and Grimoire."),
            ReleaseEntry("General", "A new logo."),
        ),
        fixed = listOf(
            ReleaseEntry("Library", "Mods with spaces in the file name load again."),
            ReleaseEntry("Profiles", "Switching profiles no longer makes the sidebar flicker."),
            ReleaseEntry("Game", "Hideout no longer crashes when the game is on a network drive."),
        ),
    )

    private val olderNotes: List<(AppVersion) -> ReleaseNotes> = listOf(
        { version ->
            ReleaseNotes(
                version, CalendarDate(2026, 10, 18), ReleaseKind.Fixes, emptyList(), emptyList(), emptyList(),
                fixed = listOf(
                    ReleaseEntry("Library", "Load order no longer resets after a game update."),
                    ReleaseEntry("Discover", "Images on slow connections no longer stay blurred."),
                    ReleaseEntry("General", "The window no longer opens off-screen after unplugging a monitor."),
                ),
            )
        },
        { version ->
            ReleaseNotes(
                version, CalendarDate(2026, 10, 9), ReleaseKind.Fixes, emptyList(), emptyList(),
                improved = listOf(ReleaseEntry("Discover", "Search results come up faster.")),
                fixed = listOf(ReleaseEntry("Install", "Installs no longer stop at 99%."), ReleaseEntry("Stats", "Rank badges load again.")),
            )
        },
        { version ->
            ReleaseNotes(
                version, CalendarDate(2026, 10, 1), ReleaseKind.Feature,
                highlights = listOf(Highlight("Match stats", "Your rank, your heroes and every match you play.", "$ArtFolder/1.3.0-match-stats@2x.webp")),
                new = listOf(
                    ReleaseEntry("Stats", "Match stats: your rank, your heroes and every match."),
                    ReleaseEntry("Profiles", "Profiles: sets of mods you switch between in one click."),
                ),
                improved = listOf(ReleaseEntry("Library", "A cards view for the library.")),
                fixed = listOf(ReleaseEntry("Install", "Some .7z files didn’t unpack.")),
            )
        },
        { version ->
            ReleaseNotes(
                version, CalendarDate(2026, 9, 20), ReleaseKind.Fixes, emptyList(), emptyList(), emptyList(),
                fixed = listOf(ReleaseEntry("Install", "Downloads resume after a dropped connection."), ReleaseEntry("General", "Hideout starts faster on older drives.")),
            )
        },
    )

    private companion object {
        const val ArtFolder = "https://example.invalid/release-art"
        const val SampleSizeBytes = 19_293_798L
        const val DownloadSteps = 40
        const val DownloadStepMillis = 100L
        const val PasswordMillis = 3_000L
    }
}
