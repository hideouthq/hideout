package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.gamebanana.SubmissionProfile
import gg.hideouthq.core.data.gamebanana.SubmissionUpdate
import gg.hideouthq.core.model.Author
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.ChangeKindTag
import gg.hideouthq.core.model.ChangeLine
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.model.ModUpdate

internal object ModDetailMapper {
    fun toDetail(profile: SubmissionProfile, updates: List<SubmissionUpdate> = emptyList()): ModDetail = ModDetail(
        modId = profile.key.modId,
        authors = authors(profile),
        about = profile.textHtml?.let(HtmlText::toPlainText)?.takeIf { it.isNotBlank() } ?: profile.description.orEmpty().trim(),
        version = profile.version?.trim()?.takeIf { it.isNotEmpty() },
        images = profile.images.map { CoverImage(url = it.cardUrl, fullUrl = it.fullUrl) },
        sourceUrl = profile.profileUrl,
        updates = updates.map(::toUpdate),
        updatesUrl = "https://gamebanana.com/${profile.key.kind.apiName.lowercase()}s/updates/${profile.key.id}".takeIf { updates.isNotEmpty() },
    )

    private fun toUpdate(update: SubmissionUpdate): ModUpdate = ModUpdate(
        title = update.name,
        version = update.version,
        date = CalendarDate.fromEpochSeconds(update.addedAt),
        lines = update.changes.map { (text, category) -> ChangeLine(tag(category), text) }.ifEmpty {
            update.text?.let(HtmlText::toPlainText).orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { ChangeLine(null, it) }
        },
        files = update.fileIds,
    )

    private fun tag(category: String?): ChangeKindTag? = when (category?.lowercase()) {
        "addition", "feature" -> ChangeKindTag.Added
        "bugfix" -> ChangeKindTag.Fixed
        "improvement", "optimization", "adjustment", "tweak", "overhaul", "refactor", "amendment" -> ChangeKindTag.Improved
        else -> null
    }

    private fun authors(profile: SubmissionProfile): List<Author> {
        val credited = profile.credits.flatMap { group -> group.authors.map { Author(it.name, it.role ?: group.name) } }
            .distinctBy { it.name.lowercase() }
        return credited.ifEmpty { listOfNotNull(profile.submitter?.let { Author(it, null) }) }
    }
}
