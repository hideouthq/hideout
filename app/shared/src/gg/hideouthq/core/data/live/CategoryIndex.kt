package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.gamebanana.Submission
import gg.hideouthq.core.data.gamebanana.SubmissionCategory
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.model.ModCategory

internal data class Placement(
    val category: ModCategory,
    val kind: String?,
    val heroCategory: String?,
)

/**
 * GameBanana's Deadlock category tree. Its roots map onto [ModCategory]; every sound root is
 * [ModCategory.Sounds], and a root the app does not know is [ModCategory.Other]. Skins sit under a
 * category per hero, sometimes a level deeper ("Rat King" > "RatKing Flag").
 */
internal class CategoryIndex(categories: List<SubmissionCategory>) {
    private val byKey = categories.associateBy { it.kind to it.id }

    fun place(submission: Submission): Placement {
        val kind = submission.key.kind
        val path = pathTo(kind, submission.categoryId)
        val rootId = path.firstOrNull()?.id ?: submission.rootCategoryId
        if (kind == SubmissionKind.Sound) return Placement(ModCategory.Sounds, path.lastOrNull()?.name, heroCategory = null)
        val category = ModRoots[rootId] ?: ModCategory.Other
        return if (category == ModCategory.Skins) {
            val hero = path.getOrNull(1)
            Placement(category, kind = path.drop(2).lastOrNull()?.name, heroCategory = hero?.name)
        } else {
            Placement(category, kind = path.drop(1).lastOrNull()?.name, heroCategory = null)
        }
    }

    fun heroSkinCounts(): Map<String, Int> =
        byKey.values.filter { it.kind == SubmissionKind.Mod && it.parentId == SkinsRoot }
            .mapNotNull { category -> category.itemCount?.let { category.name to it } }.toMap()

    private fun pathTo(kind: SubmissionKind, categoryId: Long?): List<SubmissionCategory> {
        val path = ArrayDeque<SubmissionCategory>()
        var next = categoryId
        while (next != null && path.size < MaxDepth) {
            val category = byKey[kind to next] ?: break
            path.addFirst(category)
            next = category.parentId
        }
        return path.takeIf { it.firstOrNull()?.parentId == null }.orEmpty()
    }

    private companion object {
        const val MaxDepth = 8
        const val SkinsRoot = 33295L

        val ModRoots = mapOf(
            SkinsRoot to ModCategory.Skins,
            31713L to ModCategory.Hud,
            33154L to ModCategory.Models,
            46154L to ModCategory.QualityOfLife,
            33331L to ModCategory.Gameplay,
            47611L to ModCategory.Animations,
            37225L to ModCategory.Maps,
            31710L to ModCategory.Other,
        )
    }
}
