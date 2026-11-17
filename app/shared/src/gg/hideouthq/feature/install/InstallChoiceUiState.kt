package gg.hideouthq.feature.install

import androidx.compose.runtime.Immutable
import gg.hideouthq.core.library.ArchiveNote
import gg.hideouthq.core.library.ChoiceId
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.RejectedSet
import gg.hideouthq.core.model.CoverTone

/**
 * Installing [title], for [heroName] when it is a skin, while [others], which show some of its models with other bytes,
 * are on: swap them out, or keep both. [skins] when the catalogue says every one of [others] is a skin.
 */
@Immutable
data class SwapQuestion(val id: ChoiceId, val title: String, val heroName: String?, val others: List<String>, val skins: Boolean)

/**
 * One question with its draft [selected] rows and [selectedProfiles]: downloads or complete archive components.
 * [installed] is what an installed mod has when the question changes its files. [closing] means the question is
 * settled and this is what it last showed, kept until the dialog has gone; [preview] is the exact file to open then.
 */
@Immutable
data class InstallDialogState(
    val id: ChoiceId,
    val modId: String,
    val header: DialogHeader,
    val question: Question,
    val rows: List<VersionRow>,
    val selected: kotlin.collections.Set<VersionPick>,
    val selectedProfiles: kotlin.collections.Set<ProfileId>,
    val profiles: List<Profile> = emptyList(),
    val canInstall: Boolean = false,
    /** The user moved a row, so [rows] are in the order they chose. */
    val arranged: Boolean = false,
    /** The downloaded file a set question unpacked. */
    val download: String? = null,
    val closing: Boolean = false,
    val preview: PreviewTarget? = null,
    val replacing: Boolean = false,
    val installed: kotlin.collections.Set<VersionPick> = emptySet(),
    /** The installed files the mod has parts left out of: confirming one as it is asks about its parts again. */
    val partsLeftOut: kotlin.collections.Set<VersionPick> = emptySet(),
    /** The archive's VPKs that make no complete set, which nothing installs, and its readmes. */
    val leftOut: List<RejectedSet> = emptyList(),
    val notes: List<ArchiveNote> = emptyList(),
    /** How many files the ticked parts of a pick-one group share, by [VersionRow.group], for each group with two or more ticked. */
    val shared: Map<Int, Int> = emptyMap(),
    /** The files Hideout still holds its own download of: picking one downloads nothing. */
    val kept: kotlin.collections.Set<VersionPick> = emptySet(),
    /** The ticked parts that change a file another ticked part changes, so which is higher decides what shows. */
    val movable: kotlin.collections.Set<VersionPick> = emptySet(),
) {
    /** The rows picked, in the order the files would win. */
    val selectedRows: List<VersionRow> get() = rows.filter { it.pick in selected }

    /** The order of [rows] the user chose, or null while they keep the one shown first. */
    val order: List<VersionPick>? get() = if (arranged) rows.map { it.pick } else null

    /** What confirming downloads: the files picked that the mod does not have and Hideout holds no download of. */
    val toDownload: List<VersionRow> get() = selectedRows.filter { it.pick !in installed && it.pick !in kept }

    /** Every part is an alternative to the others, so the archive holds versions of one thing. */
    val versions: Boolean get() = question == Question.Sets && rows.size > 1 && rows.all { it.group != null } && rows.distinctBy { it.group }.size == 1

    /**
     * Something is picked, and when this changes an installed mod's files, the files picked are not the ones it has, or
     * one of them has parts left out to ask about again.
     */
    val canConfirm: Boolean get() = canInstall && !closing && selected.isNotEmpty() && (question != Question.Files || !replacing || selected != installed || selected.any { it in partsLeftOut })
}

/** The mod's title, GameBanana's, and its picture once the catalogue knows the mod. */
@Immutable
data class DialogHeader(val title: String, val coverUrl: String? = null, val coverTone: CoverTone? = null)

/** Download selection, or complete components within an unpacked archive. */
enum class Question { Files, Sets }

/**
 * One choice. [name] is the GameBanana description or the cleaned file name for a file, the folder for a set;
 * [path] is a set's archive path, only when another set has its [name]. GameBanana no longer [listed] a file only the
 * installed mod still has; a set has a [group] when it changes the same files as other parts, so only one of them
 * shows. [preview] is set only for a file the preview host has an interactive bundle of, by exact id and MD5.
 */
@Immutable
data class VersionRow(
    val pick: VersionPick,
    val name: String,
    val sizeBytes: Long,
    val path: String? = null,
    val mostDownloaded: Boolean = false,
    val preview: PreviewTarget? = null,
    val installed: Boolean = false,
    val listed: Boolean = true,
    val group: Int? = null,
    /** A set that replaces fonts, which load only through `fonts.conf`. */
    val needsFonts: Boolean = false,
    /** For an add-on, a set that changes some of the files larger ticked sets outside its group change. */
    val covers: Covers? = null,
)

/** An add-on changes [files] of the files [names] change; it shows there when it is [above] them. */
@Immutable
data class Covers(val names: String, val files: Int, val above: Boolean)

sealed interface VersionPick {
    data class File(val fileId: Long) : VersionPick

    data class Set(val key: String) : VersionPick
}

/** The exact GameBanana file whose 3D preview to open on its mod page. */
@Immutable
data class PreviewTarget(val modId: String, val fileId: Long, val fileMd5: String?)
