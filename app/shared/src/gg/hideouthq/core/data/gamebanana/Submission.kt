package gg.hideouthq.core.data.gamebanana

import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import kotlinx.serialization.Serializable

@Serializable
internal data class Submission(
    val key: SubmissionRef,
    val name: String,
    val profileUrl: String,
    val submitter: String?,
    val categoryId: Long?,
    val rootCategoryId: Long?,
    val tags: List<String>,
    val images: List<SubmissionImage>,
    val audioUrl: String?,
    val likes: Int,
    val addedAt: Long,
    val updatedAt: Long?,
    val obsolete: Boolean,
    /** How GameBanana shows it at first: "show", or "warn"/"hide" for mature content. */
    val visibility: String,
    val mature: Boolean = false,
    val facts: FileFacts? = null,
    val modifiedAt: Long? = null,
)

/** A screenshot. [file530] is a 530px-wide copy, which GameBanana makes for some images only. */
@Serializable
internal data class SubmissionImage(val baseUrl: String, val file: String, val file530: String? = null) {
    val cardUrl: String get() = "$baseUrl/${file530 ?: file}"

    val fullUrl: String get() = "$baseUrl/$file"
}

/** What a submission's downloads say about it. Archived (superseded) files are left out. */
@Serializable
data class FileFacts(
    val downloads: Int,
    val largestFileBytes: Long?,
    val newestFileAt: Long?,
    /** The file a download gets: set only when exactly one file is current and its identity is unambiguous. */
    val currentFile: SubmissionFile? = null,
    /** Every current file whose identity is unambiguous, even when there is more than one; empty when archive state is unknown. */
    val files: List<SubmissionFileOption> = emptyList(),
    /** Whether [files] is every current file: false when any file's archive state or identity could not be read. */
    val filesComplete: Boolean = false,
)

/** What a Multi answer says about one submission that was asked for. */
sealed interface FactsAnswer {
    data class Read(val facts: FileFacts) : FactsAnswer

    /** The answer left it out: gone, or only out of reach for now. Unknown, not "no files". */
    data object Missing : FactsAnswer

    /** Its row came back without readable files or download count, so nothing in the row is trusted. */
    data object Unreadable : FactsAnswer
}

/** The facts an answer gives, or null when they are unknown. */
val FactsAnswer.facts: FileFacts? get() = (this as? FactsAnswer.Read)?.facts

/** One GameBanana file, by id, with the MD5 GameBanana lists for it when it lists one. */
@Serializable
data class SubmissionFile(val id: Long, val md5: String? = null)

/** A current file worth offering someone picking among several, with the labels GameBanana gives it. */
@Serializable
data class SubmissionFileOption(
    val id: Long,
    val md5: String?,
    val filename: String?,
    val description: String?,
    val sizeBytes: Long? = null,
    val downloads: Long? = null,
    val addedAt: Long? = null,
)

@Serializable
internal data class SubmissionCategory(
    val id: Long,
    val name: String,
    val parentId: Long?,
    val kind: SubmissionKind,
    val itemCount: Int? = null,
)

internal data class SubmissionProfile(
    val key: SubmissionRef,
    val name: String,
    val profileUrl: String,
    val textHtml: String?,
    val description: String?,
    val version: String?,
    val submitter: String?,
    val credits: List<CreditGroup>,
    val images: List<SubmissionImage>,
    val hasUpdates: Boolean = false,
)

internal data class SubmissionUpdate(
    val name: String,
    val version: String?,
    val addedAt: Long,
    val changes: List<Pair<String, String?>>,
    val text: String?,
    val fileIds: Set<Long>,
)

internal data class CreditGroup(val name: String?, val authors: List<Credit>)

internal data class Credit(val name: String, val role: String?)
