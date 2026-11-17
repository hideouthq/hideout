package gg.hideouthq.core.model

import gg.hideouthq.preview.IndexEntry

/**
 * A mod in the catalogue. Install state and profile membership live elsewhere (library and
 * profile repositories) so the catalogue can be shown by a front end that has no library.
 */
data class Mod(
    val id: String,
    val title: String,
    val category: ModCategory,
    /** The hero a skin replaces, if any. */
    val heroId: String? = null,
    /** A finer kind within the category ("Gameplay HUD", "Voice lines"). */
    val kind: String? = null,
    val author: String? = null,
    /** Null until GameBanana's facts for it have arrived. */
    val downloads: Int?,
    val likes: Int,
    val sizeBytes: Long?,
    val has3dPreview: Boolean = false,
    val worksOnCurrentPatch: Boolean = true,
    val mature: Boolean = false,
    /** When it was first published, in Unix seconds. */
    val publishedAt: Long,
    val updated: CalendarDate,
    /** Position in the catalogue's trending order (1 = most trending). */
    val trendingRank: Int,
    val cover: CoverImage = CoverImage(),
    /** A short audio preview (sounds only), if the mod page offers one. */
    val audioPreviewUrl: String? = null,
    /** The default display preview, matched to one current file. It does not choose the file to install. */
    val preview: IndexEntry? = null,
    /** Every current GameBanana file of this mod, each matched against the preview host by exact id and MD5. */
    val previewVariants: List<PreviewVariant> = emptyList(),
    /** False while a first listing could still turn up a mod ranked above it, so [trendingRank] is not its place yet. */
    val rankFinal: Boolean = true,
) {
    /** The UTC day it was first published. */
    val published: CalendarDate get() = CalendarDate.fromEpochSeconds(publishedAt)

    /** Whether the host has generated pictures of exactly this mod's file. */
    val hasReadyPicture: Boolean get() = preview != null

    /** Whether its pictures cannot be told yet: which file it has comes with GameBanana's facts. */
    val pictureUnknown: Boolean get() = downloads == null
}

/**
 * One of a mod's current GameBanana files, offered by its own filename or author description, never an invented
 * name. [entry] is the preview host's bundle for exactly this file's id and MD5; null for a file the host has no
 * bundle for, or only a stale one of another file or MD5.
 */
data class PreviewVariant(
    val fileId: Long,
    val fileMd5: String?,
    val filename: String?,
    val description: String?,
    val entry: IndexEntry? = null,
    val sizeBytes: Long? = null,
    val downloads: Long? = null,
    val addedAt: Long? = null,
)

/** The file Install takes unasked and the install dialog starts on: [isPartialFile] decides which, over the dialog's order. */
fun List<PreviewVariant>.offeredFirst(): PreviewVariant? {
    val ordered = sortedWith(compareByDescending<PreviewVariant> { it.downloads ?: 0 }.thenByDescending { it.addedAt ?: 0 }.thenBy { it.fileId })
    return ordered.firstOrNull { !isPartialFile(it.description, it.filename) } ?: ordered.firstOrNull()
}

/**
 * Whether a file's author calls it a part of the mod or a kit for people who make mods, by a word in its
 * [description] or [fileName] such as "only", "optional", "add-on", "patch", "template" or "source". Such a file is
 * never what Install takes unasked while the mod has another.
 */
fun isPartialFile(description: String?, fileName: String?): Boolean =
    PartialWords.containsMatchIn(listOfNotNull(description, fileName?.substringBeforeLast('.')).joinToString(" ").replace(NotWord, " "))

private val NotWord = Regex("[^\\p{L}\\p{N}]+")
private val PartialWords = Regex("\\b(only|optional|add ?ons?|patch|hotfix|templates?|source|creators?|modders?|extras?)\\b", RegexOption.IGNORE_CASE)

/**
 * A mod's cover. [url] is null when there is no artwork; [tone] is a hint for the flat placeholder
 * colour shown while it loads or when it is missing, so placeholders roughly follow the artwork.
 */
data class CoverImage(
    val url: String? = null,
    val tone: CoverTone? = null,
    /** The full-size original, for looking at the picture large; null when [url] is all there is. */
    val fullUrl: String? = null,
)

/** The six placeholder families, named after the dominant colour of the art they replace. */
enum class CoverTone { Green, Cream, Teal, Moss, Ember, Ink }
