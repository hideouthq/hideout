package gg.hideouthq.core.model

data class ModDetail(
    val modId: String,
    val authors: List<Author>,
    val about: String,
    /** Free text from the author ("1.2", "Beta"); null when the mod page has none. */
    val version: String?,
    /** Images from the mod page, in its order. */
    val images: List<CoverImage>,
    val sourceUrl: String? = null,
    /** The author's latest updates, newest first; empty when there are none or they could not be read. */
    val updates: List<ModUpdate> = emptyList(),
    /** Every update on the mod page, when there are any. */
    val updatesUrl: String? = null,
    /** What the mod page says the mod needs to work, in its order; empty when it says nothing. */
    val requirements: List<Requirement> = emptyList(),
)

/**
 * Something a mod needs, by the [name] its author gave it, with the web page [url] they linked, if any, and [modId] when
 * that page is a GameBanana mod or sound.
 */
data class Requirement(val name: String, val url: String? = null, val modId: String? = null)

/** One update an author posted: its [version], when it gives one, and the GameBanana [files] it brought, when it lists them. */
data class ModUpdate(val title: String, val version: String?, val date: CalendarDate, val lines: List<ChangeLine>, val files: Set<Long> = emptySet())

/** A line of an update; [kind] is null for a line its author did not tag as added, fixed or improved. */
data class ChangeLine(val kind: ChangeKindTag?, val text: String)

enum class ChangeKindTag { Added, Fixed, Improved }

/** A credited person. [role] is the mod page's own wording ("3D Art", "Concept"), if it gives one. */
data class Author(val name: String, val role: String?)
