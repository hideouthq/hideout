package gg.hideouthq.core.data.deadlockstats

/**
 * A background watch over Steam's replay cache, platform-specific (file-system watching) so the
 * real implementation lives alongside [gg.hideouthq.library.SteamGameLocator] in the desktop
 * app module; this backend only depends on the contract. A conforming implementation is expected
 * to, in order: run one startup scan over whatever cache directories it finds, then switch
 * to recursive observation for new files, and periodically repeat the scan so a missed or
 * coalesced file-system event (or an overflowed OS watch queue) is still eventually found. It
 * reports every candidate file's raw bytes — not a parsed URL — because deciding whether those
 * bytes actually contain a trustworthy metadata URL is this backend's job (see [ReplayCacheUrl]),
 * not the watcher's.
 */
interface ReplayCacheWatcherPort {
    /**
     * Starts watching and calls [onCandidate] with a cache file's bytes, and when it was last written in epoch seconds,
     * each time the startup scan, the live observation, or a reconciliation pass finds one. Must not block past kicking off
     * background work: this is called from the repository's own coroutine scope. Re-reporting the
     * same file (e.g. on every reconciliation pass) is expected; the repository, not the watcher, is
     * responsible for not re-processing a match it already has complete data for.
     */
    suspend fun start(onCandidate: suspend (header: ByteArray, writtenAt: Long) -> Unit)

    /** Stops observation and releases any OS resources (watch handles, threads). Idempotent. */
    fun close()
}
