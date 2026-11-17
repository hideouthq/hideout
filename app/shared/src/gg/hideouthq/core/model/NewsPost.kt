package gg.hideouthq.core.model

import kotlinx.serialization.Serializable

/**
 * A post from Deadlock's news on Steam. [postedAt] is in Unix seconds; [body] is its text in paragraphs, [excerpt] the
 * start of it; [imageUrl] is the first picture it shows, if any; [url] opens it on Steam.
 */
@Serializable
data class NewsPost(
    val id: String,
    val title: String,
    val kind: NewsKind,
    val postedAt: Long,
    val excerpt: String,
    val body: List<String>,
    val imageUrl: String?,
    val url: String,
)

/** Valve tags patch notes; everything else is an announcement. */
enum class NewsKind { PatchNotes, Announcement }
