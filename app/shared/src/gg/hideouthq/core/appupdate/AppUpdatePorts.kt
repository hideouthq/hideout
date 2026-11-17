package gg.hideouthq.core.appupdate

import kotlin.time.Instant

/**
 * How this copy of Hideout gets replaced, which only the platform knows. A copy with no method can only point at GitHub.
 * [format] says which of a release's packages it can install.
 */
sealed interface AppUpdateMethod {
    val format: PackageFormat

    /** Files are replaced as Hideout leaves, so quitting installs too. */
    class AtExit(
        override val format: PackageFormat,
        val replace: suspend (ReleasePackage, relaunch: Boolean) -> InstallOutcome,
    ) : AppUpdateMethod

    /** Needs a password the user may refuse, so it runs with Hideout open and never on quit. */
    class WhileOpen(
        override val format: PackageFormat,
        val install: suspend (ReleasePackage) -> InstallOutcome,
        val relaunch: suspend () -> InstallOutcome,
    ) : AppUpdateMethod
}

sealed interface InstallOutcome {
    data object Done : InstallOutcome

    /** The user refused, and nothing changed. */
    data object Cancelled : InstallOutcome

    /** [detail] is for the log; [why] is what the user is told. */
    data class Failed(val detail: String, val why: UpdateFailure = UpdateFailure.Install) : InstallOutcome
}

/** Where the release feed comes from. Neither call throws anything but cancellation. */
interface ReleaseFeedSource {
    /** The last feed fetched and proven genuine, kept so What's new opens offline; null when there is none or it no longer proves genuine. */
    suspend fun lastGood(): KnownFeed?

    suspend fun fetch(): FeedFetch
}

data class KnownFeed(val feed: ReleaseFeed, val fetchedAt: Instant)

sealed interface FeedFetch {
    data class Fresh(val known: KnownFeed) : FeedFetch

    /** No answer, or no feed there. [detail] is for the log. */
    data class Unreachable(val detail: String) : FeedFetch

    /** An answer Hideout will not believe or cannot read. [detail] is for the log. */
    data class Refused(val detail: String) : FeedFetch
}

/**
 * Why an update failed. [Install]: the installer did not finish. [Unwritable]: Hideout could not replace its own file.
 * [NotReplaced]: Hideout opened again on the version it was to replace, so trying the same way again would not help.
 */
enum class UpdateFailure { Connection, Server, Mismatch, Disk, Install, Unwritable, NotReplaced }

sealed interface PackageDownload {
    data object Verified : PackageDownload

    data class Failed(val why: UpdateFailure) : PackageDownload
}

/** The downloaded packages on disk. Paths stay on the platform's side. None of these throws anything but cancellation. */
interface UpdatePackages {
    /** Whether [pkg] is already on disk with its size and sha256 matching. */
    suspend fun probe(pkg: ReleasePackage): Boolean

    /** Gets [pkg], resuming what an earlier try left. Cancellation keeps the partial file for the next try. */
    suspend fun download(pkg: ReleasePackage, onProgress: (Long) -> Unit): PackageDownload

    /** Deletes every package and partial download except [pkg]'s; everything when null. */
    suspend fun keepOnly(pkg: ReleasePackage?)

    object None : UpdatePackages {
        override suspend fun probe(pkg: ReleasePackage): Boolean = false

        override suspend fun download(pkg: ReleasePackage, onProgress: (Long) -> Unit): PackageDownload = PackageDownload.Failed(UpdateFailure.Disk)

        override suspend fun keepOnly(pkg: ReleasePackage?) = Unit
    }
}
