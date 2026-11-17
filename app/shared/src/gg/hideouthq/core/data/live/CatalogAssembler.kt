package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.gamebanana.Submission
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.SubmissionRef

internal class Catalog(
    val mods: List<Mod>,
    val popularHeroIds: List<String>,
    val discoverFeed: DiscoverFeed,
    val files: Map<String, ExactFile> = emptyMap(),
    val fileOptions: Map<String, List<FileOption>> = emptyMap(),
    private val picks: List<String> = emptyList(),
    private val submissions: Map<String, SubmissionRef> = emptyMap(),
) {
    val modsById: Map<String, Mod> = mods.associateBy { it.id }

    fun previewed(entries: Map<SubmissionRef, List<IndexEntry>>): Catalog {
        fun entryFor(file: ExactFile): IndexEntry? = entries[file.submission]
            ?.firstOrNull { it.fileId == file.id }
            ?.takeIf { file.md5 == null || it.fileMd5 == null || it.fileMd5 == file.md5 }

        val previewedMods = mods.map { mod ->
            val variants = fileOptions[mod.id].orEmpty().map { option ->
                PreviewVariant(option.file.id, option.file.md5, option.filename, option.description, entryFor(option.file), option.sizeBytes, option.downloads, option.addedAt)
            }
            val entry = files[mod.id]?.let(::entryFor)
                ?: variants.firstNotNullOfOrNull { it.entry }
            if (entry == mod.preview && variants == mod.previewVariants) mod else {
                mod.copy(preview = entry, has3dPreview = entry != null, previewVariants = variants)
            }
        }
        val listed = submissions.filterValues { it in entries }.keys
        return Catalog(previewedMods, popularHeroIds, CatalogAssembler.discoverFeed(previewedMods, picks, discoverFeed.heroesToBrowse, listed), files, fileOptions, picks, submissions)
    }
}

internal data class ExactFile(val submission: SubmissionRef, val id: Long, val md5: String?)

internal data class FileOption(
    val file: ExactFile,
    val filename: String?,
    val description: String?,
    val sizeBytes: Long? = null,
    val downloads: Long? = null,
    val addedAt: Long? = null,
)

internal object CatalogAssembler {
    private const val FeaturedCount = 4

    private const val FirstCards = 4
    private const val PopularHeroes = 12
    private const val TrendingSkins = 24
    private const val TrendingHud = 4
    private const val TrendingSounds = 4
    private const val TrendingGameplay = 24
    private val MatureVisibility = setOf("warn", "hide")

    fun assemble(snapshot: CatalogSnapshot, heroes: List<Hero>, today: CalendarDate): Catalog {
        val categories = CategoryIndex(snapshot.categories)
        val matcher = HeroMatcher(heroes)
        val unheardScores = snapshot.unheard.mapValues { (_, limit) ->
            Trending.score(limit.downloads, limit.likes, limit.addedAt?.let { CalendarDate.fromEpochSeconds(it).daysUntil(today) } ?: 0)
        }
        val mods = snapshot.submissions
            .map { submission ->
                val mod = submission.toMod(categories, matcher)
                val score = Trending.score(mod.downloads ?: 0, mod.likes, mod.published.daysUntil(today))
                val unheard = unheardScores[submission.key.kind]
                mod.copy(rankFinal = unheard == null || mod.downloads != null && score > unheard) to score
            }
            .sortedWith(compareByDescending<Pair<Mod, Double>> { it.second }.thenBy { it.first.id })
            .mapIndexed { index, (mod, _) -> mod.copy(trendingRank = index + 1) }
        val popular = popularHeroes(mods, heroes, categories.heroSkinCounts().entries.mapNotNull { (name, count) -> matcher.named(name)?.let { it to count } }.toMap())
        val files = snapshot.submissions.mapNotNull { submission ->
            submission.facts?.currentFile?.let { file -> submission.key.modId to ExactFile(submission.previewRef, file.id, file.md5) }
        }.toMap()
        val fileOptions = snapshot.submissions.mapNotNull { submission ->
            submission.facts?.files?.takeIf { it.isNotEmpty() }?.let { options ->
                submission.key.modId to options.map { FileOption(ExactFile(submission.previewRef, it.id, it.md5), it.filename, it.description, it.sizeBytes, it.downloads, it.addedAt) }
            }
        }.toMap()
        val picks = snapshot.featured.map { it.modId }
        val submissions = snapshot.submissions.associate { it.key.modId to it.previewRef }
        return Catalog(mods, popular.take(PopularHeroes), discoverFeed(mods, picks, popular, emptySet()), files, fileOptions, picks, submissions)
    }

    private val Submission.previewRef: SubmissionRef get() = SubmissionRef(key.kind.apiName, key.id)

    private fun Submission.toMod(categories: CategoryIndex, matcher: HeroMatcher): Mod {
        val placement = categories.place(this)
        val title = HtmlText.decodeEntities(name)
        val heroId = when (placement.category) {
            ModCategory.Skins -> if (placement.heroCategory != null) matcher.named(placement.heroCategory) else matcher.mentionedIn(title)
            ModCategory.Sounds -> tags.firstNotNullOfOrNull(matcher::named) ?: matcher.mentionedIn(title)
            else -> null
        }
        val published = CalendarDate.fromEpochSeconds(addedAt)
        return Mod(
            id = key.modId,
            title = title,
            category = placement.category,
            heroId = heroId,
            kind = placement.kind ?: placement.heroCategory.takeIf { heroId == null },
            author = submitter,
            downloads = facts?.downloads,
            likes = likes,
            sizeBytes = facts?.largestFileBytes,
            worksOnCurrentPatch = !obsolete,
            mature = mature || visibility in MatureVisibility,
            publishedAt = addedAt,
            updated = (facts?.newestFileAt ?: updatedAt)?.let(CalendarDate::fromEpochSeconds)?.coerceAtLeast(published) ?: published,
            trendingRank = 0,
            cover = CoverImage(
                url = images.firstOrNull()?.cardUrl,
                fullUrl = images.firstOrNull()?.fullUrl,
            ),
            audioPreviewUrl = audioUrl,
        )
    }

    private fun popularHeroes(mods: List<Mod>, heroes: List<Hero>, filed: Map<String, Int>): List<String> {
        val skins = filed.ifEmpty { mods.filter { it.category == ModCategory.Skins }.mapNotNull { it.heroId }.groupingBy { it }.eachCount() }
        return heroes.sortedWith(compareByDescending<Hero> { skins[it.id] ?: 0 }.thenBy { it.name }).map { it.id }
    }

    fun discoverFeed(mods: List<Mod>, picks: List<String>, popularHeroIds: List<String>, listed: Set<String>): DiscoverFeed {
        val byId = mods.associateBy { it.id }
        val showable = mods.filter { !it.mature }
        val isSkinWithArt = { mod: Mod -> !mod.mature && mod.category == ModCategory.Skins && mod.cover.url != null }
        val skinsWithArt = mods.filter(isSkinWithArt)
        val pickedSkins = picks.mapNotNull(byId::get).filter(isSkinWithArt)
        val candidates = (pickedSkins + skinsWithArt.filter { it.rankFinal }).distinctBy { it.id }
        val featurable = if (candidates.any { it.hasReadyPicture }) candidates.filter { it.hasReadyPicture || it.pictureUnknown && it in pickedSkins && it.id in listed } else candidates
        val featured = featurable.take(FeaturedCount).map { it.id }
        fun row(of: List<Mod>, count: Int): List<String> {
            val final = of.filter { it.rankFinal }
            return if (final.size < of.size && final.size < minOf(FirstCards, count)) emptyList() else final.take(count).map { it.id }
        }
        fun trending(category: ModCategory, count: Int) = row(showable.filter { it.category == category && it.id !in featured }, count)
        return DiscoverFeed(
            featured = featured,
            heroesToBrowse = popularHeroIds,
            trendingSkins = row(skinsWithArt.filter { it.id !in featured }, TrendingSkins),
            trendingHud = trending(ModCategory.Hud, TrendingHud),
            trendingSounds = trending(ModCategory.Sounds, TrendingSounds),
            trendingGameplay = trending(ModCategory.Gameplay, TrendingGameplay),
        )
    }
}
