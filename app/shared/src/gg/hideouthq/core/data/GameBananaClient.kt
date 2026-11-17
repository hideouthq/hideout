package gg.hideouthq.core.data

import gg.hideouthq.core.library.SubmissionRef

/** GameBanana's files for one submission, asked fresh every time: what Hideout installs comes from this answer. */
fun interface GameBananaClient {
    suspend fun files(submission: SubmissionRef): FileListing

    /** Deadlock's mods whose names GameBanana finds [title] in, best match first; null when it does not answer. */
    suspend fun search(title: String): List<SubmissionRef>? = emptyList()
}

sealed interface FileListing {
    /**
     * [files] are the submission's current files, the only ones ever offered. [archived] are the superseded ones
     * GameBanana still serves, which only tell what an installed file once was. [complete] is false when GameBanana
     * listed a current file Hideout could not read, which [files] then leaves out: they are not all there.
     */
    data class Available(val title: String, val files: List<RemoteFile>, val complete: Boolean = true, val archived: List<RemoteFile> = emptyList()) : FileListing

    /** GameBanana says by its own flags that the submission is no longer offered. */
    data class Withdrawn(val why: Withdrawal) : FileListing

    /** Nothing reliable came back: no answer, an error, or an answer that does not parse. Not the same as withdrawn. */
    data class Unavailable(val reason: String) : FileListing
}

enum class Withdrawal { Trashed, Withheld, Private }

/** One downloadable file as GameBanana lists it now. [md5] is null when GameBanana publishes none. */
data class RemoteFile(
    val id: Long,
    val fileName: String,
    val sizeBytes: Long,
    val md5: String?,
    val description: String?,
    val downloads: Long,
    val addedAt: Long,
    val downloadUrl: String,
)
