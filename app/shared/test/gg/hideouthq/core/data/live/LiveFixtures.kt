package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.gamebanana.FileFacts
import gg.hideouthq.core.data.gamebanana.Submission
import gg.hideouthq.core.data.gamebanana.SubmissionCategory
import gg.hideouthq.core.data.gamebanana.SubmissionImage
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.ModDetail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant

internal object LiveFixtures {
    const val Now = 1_791_244_800L

    val heroes = listOf(
        Hero("hero_ghost", "Lady Geist", "https://cdn.test/spectre_sm.webp", "https://cdn.test/spectre_bg.webp",
            signatureAbilities = mapOf(1 to "ability_tombstone", 2 to "ability_ghost_shroud", 3 to "ability_siphon_life", 4 to "ability_life_drain")),
        Hero("hero_frank", "Victor", "https://cdn.test/frank_sm.webp", "https://cdn.test/frank_bg.webp"),
        Hero("hero_doorman", "The Doorman", "https://cdn.test/doorman_sm.webp"),
    )

    val categories = listOf(
        SubmissionCategory(33295, "Skins", null, SubmissionKind.Mod),
        SubmissionCategory(33315, "Lady Geist", 33295, SubmissionKind.Mod),
        SubmissionCategory(40060, "Doorman", 33295, SubmissionKind.Mod),
        SubmissionCategory(31713, "HUD", null, SubmissionKind.Mod),
        SubmissionCategory(44001, "Gameplay", 31713, SubmissionKind.Mod),
        SubmissionCategory(6282, "VOs", null, SubmissionKind.Sound),
    )

    val submissions = listOf(
        submission(SubmissionKind.Mod, 1, "Bride of Victor | Lady Geist", category = 33315, root = 33295, downloads = 9_000, likes = 137, ageDays = 3),
        submission(SubmissionKind.Mod, 2, "Akiyama as the Doorman", category = 33295, root = 33295, downloads = 500, likes = 4, ageDays = 40),
        submission(SubmissionKind.Mod, 3, "Lewd Geist", category = 33315, root = 33295, downloads = 90_000, likes = 900, ageDays = 2, visibility = "hide"),
        submission(SubmissionKind.Mod, 4, "Classic Health Bars", category = 44001, root = 31713, downloads = 40_000, likes = 300, ageDays = 30),
        submission(SubmissionKind.Sound, 4, "Doctor&#39;s voice pack", category = 6282, root = 6282, downloads = 700, likes = 9, ageDays = 10, tags = listOf("Victor"), images = false),
    )

    fun snapshot(
        syncedAt: Long = Now,
        featured: List<SubmissionRef> = listOf(SubmissionRef(SubmissionKind.Mod, 3), SubmissionRef(SubmissionKind.Mod, 2)),
        checkedAt: Long = syncedAt,
    ) = CatalogSnapshot(
        CatalogSnapshot.SchemaVersion, syncedAt, categories, submissions, featured, checkedAt,
        modifiedThrough = submissions.groupBy { it.key.kind }.mapValues { (_, listed) -> listed.maxOf { it.modifiedAt!! } },
    )

    fun clockAt(epochSeconds: Long): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochSeconds(epochSeconds)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun TestScope.virtualClock(epochSeconds: Long = Now): Clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(epochSeconds * 1_000 + testScheduler.currentTime)
    }

    private fun submission(
        kind: SubmissionKind, id: Long, name: String, category: Long, root: Long, downloads: Int, likes: Int, ageDays: Long,
        visibility: String = "show", tags: List<String> = emptyList(), images: Boolean = true,
    ) = Submission(
        key = SubmissionRef(kind, id), name = name, profileUrl = "https://gamebanana.com/${kind.apiName.lowercase()}s/$id", submitter = "author$id",
        categoryId = category, rootCategoryId = root, tags = tags,
        images = if (!images) emptyList() else listOf(SubmissionImage("https://images.test", "$id.jpg", "530-90_$id.jpg")),
        audioUrl = if (kind == SubmissionKind.Sound) "https://files.test/$id.mp3" else null,
        likes = likes, addedAt = Now - ageDays * 86_400, updatedAt = null, obsolete = false, visibility = visibility,
        facts = FileFacts(downloads, largestFileBytes = 1_000_000L * id, newestFileAt = Now - 86_400, filesComplete = true),
        modifiedAt = Now - 86_400 + id,
    )
}

internal class StubSource(
    private val fetch: suspend (since: CatalogSnapshot?) -> CatalogSnapshot = { LiveFixtures.snapshot() },
    private val detail: suspend (String) -> ModDetail = { error("No mod pages here") },
) : CatalogSource {
    val sinces = mutableListOf<CatalogSnapshot?>()
    val fetches: Int get() = sinces.size
    var details = 0

    override suspend fun heroes() = LiveFixtures.heroes

    override fun fetch(since: CatalogSnapshot?, knownPicks: List<SubmissionRef>, onProgress: (Float?) -> Unit): Flow<CatalogSnapshot> = flow {
        sinces += since
        emit(fetch.invoke(since))
    }

    override suspend fun detail(modId: String): ModDetail {
        details++
        return detail.invoke(modId)
    }

    override suspend fun observe(installed: Set<SubmissionIdentity>): Map<SubmissionRef, FilesObservation> = error("No installed submissions here")
}
