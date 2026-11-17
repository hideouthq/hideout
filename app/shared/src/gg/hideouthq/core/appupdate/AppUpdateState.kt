package gg.hideouthq.core.appupdate

import gg.hideouthq.core.data.DownloadProgress
import kotlin.time.Instant

/**
 * Where updating Hideout itself stands. "Update" alone means a mod update everywhere else in the app.
 *
 * [openedFor] is the last version whose panel the user opened, kept across starts, so its chip stops asking for attention.
 */
data class AppUpdateState(
    val running: AppVersion,
    val phase: AppUpdatePhase = AppUpdatePhase.Idle,
    val feed: ReleaseFeed? = null,
    val lastChecked: Instant? = null,
    val checking: Boolean = false,
    val lastCheckFailed: Boolean = false,
    val openedFor: AppVersion? = null,
    val justUpdatedTo: AppVersion? = null,
)

sealed interface AppUpdatePhase {
    val version: AppVersion?
    val pkg: ReleasePackage?

    data object Idle : AppUpdatePhase {
        override val version: AppVersion? get() = null
        override val pkg: ReleasePackage? get() = null
    }

    /** A newer version exists, and this copy can only point at where to get it. */
    data class GitHubOnly(override val version: AppVersion, val releasesUrl: String) : AppUpdatePhase {
        override val pkg: ReleasePackage? get() = null
    }

    data class Available(override val pkg: ReleasePackage) : AppUpdatePhase {
        override val version: AppVersion get() = pkg.version
    }

    data class Downloading(override val pkg: ReleasePackage, val doneBytes: Long) : AppUpdatePhase {
        override val version: AppVersion get() = pkg.version
        val percent: Int get() = DownloadProgress(doneBytes, pkg.sizeBytes).percent
    }

    data class Ready(override val pkg: ReleasePackage, val restart: RestartStage) : AppUpdatePhase {
        override val version: AppVersion get() = pkg.version
    }

    /** [pkg] is null when the install of [version] failed and this copy now has no package for it, so Try again leads to GitHub. */
    data class Failed(override val version: AppVersion, override val pkg: ReleasePackage?, val why: UpdateFailure, val releasesUrl: String) : AppUpdatePhase
}

/**
 * How far a Restart has got: not asked for, held until mod changes and any custom game finish, at the password prompt, or
 * on the way out.
 */
enum class RestartStage { Idle, Waiting, Installing, Exiting }
