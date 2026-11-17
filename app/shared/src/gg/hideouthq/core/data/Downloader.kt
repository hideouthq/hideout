package gg.hideouthq.core.data

import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.isPlainAbsolutePath
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Fetches one exact GameBanana file into the download folder each call names, as `<folder>/<mod id>/<file id>/<name>`.
 * A transient failure is retried from the bytes already there, and a host that asks for time is waited out. What a
 * download got stays beside it when it fails or is stopped, so the next download of the same file goes on from there,
 * until [discardUnfinished] puts it away. The result is the file only when its size is the one GameBanana lists and, when
 * GameBanana publishes an MD5, its MD5 matches. Nothing already there is replaced or taken as the download, not even a
 * file with the same bytes. A download is recorded under the real path of its folder. Its [TwinLinks] find an archive
 * from its own path and source, wherever that folder was, and act only when the archive still sits in that layout with
 * no link or junction anywhere above it.
 */
interface Downloader : TwinLinks<DownloadEntry> {
    /** Hideout's own download folder, made when a download first needs it. */
    val defaultFolder: DownloadFolder

    /**
     * Whether [path] can take new downloads: a plain folder that exists and can be written, whose real place is outside
     * [games] and outside Hideout's own data, except [defaultFolder] itself. A folder is refused when its real place, or
     * that of a game or of Hideout's data, cannot be read, never judged by its name instead.
     */
    suspend fun checkFolder(path: String, games: Set<GameRoot>): FolderChoice

    /**
     * Downloads into [folder], which must already be a plain folder unless it is [defaultFolder]; a folder that is gone
     * fails the download rather than sending it elsewhere. A [DownloadResult.Done] archive is a file this call created,
     * under a name nothing had. [onPublished] gets that archive the moment it has its name, before the call returns, so
     * the caller still learns of it when it is cancelled before it sees the result.
     */
    suspend fun download(
        folder: DownloadFolder,
        submission: SubmissionRef,
        file: RemoteFile,
        onPublished: (ArchiveIdentity) -> Unit = {},
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult

    /**
     * Deletes what an unfinished download of file [fileId] of [submission] left in [folder], and the file and mod folders
     * it leaves empty; nothing when there is none.
     */
    suspend fun discardUnfinished(folder: DownloadFolder, submission: SubmissionRef, fileId: Long)
}

/** A folder downloads go into, as a plain absolute path. */
@JvmInline
value class DownloadFolder(val path: String) {
    init {
        require(isPlainAbsolutePath(path)) { "a download folder must be absolute, without . or .. or control characters" }
    }
}

sealed interface FolderChoice {
    data class Accepted(val folder: DownloadFolder) : FolderChoice

    data class Refused(val reason: FolderRefusal) : FolderChoice
}

enum class FolderRefusal { Missing, NotAFolder, NotWritable, InsideGame, InsideHideout, Unresolved }

/**
 * [stalled] while no bytes have come for a few seconds and the download is trying again by itself from what it has.
 * [busyUntil] while the file's host asked Hideout to wait: the download goes on by itself then.
 */
data class DownloadProgress(
    val doneBytes: Long,
    val totalBytes: Long,
    val remainingSeconds: Long? = null,
    val stalled: Boolean = false,
    val busyUntil: Instant? = null,
) {
    val percent: Int get() = if (totalBytes <= 0) 0 else ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
}

sealed interface DownloadResult {
    data class Done(val archive: ArchiveIdentity) : DownloadResult

    data class Failed(val problem: DownloadProblem) : DownloadResult
}

sealed interface DownloadProblem {
    /** The transfer kept stopping. [resumable] when what it got is kept and its server serves the rest from there. */
    data class Network(val reason: String, val resumable: Boolean = false) : DownloadProblem

    data class Http(val status: Int) : DownloadProblem

    data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : DownloadProblem

    data class Md5Mismatch(val expected: String, val actual: String) : DownloadProblem

    data class Disk(val reason: String) : DownloadProblem
}
