package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.gamebanana.Submission
import gg.hideouthq.core.data.gamebanana.SubmissionCategory
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.ModCategory
import kotlin.test.Test
import kotlin.test.assertEquals

class CategoryIndexTest {
    private val index = CategoryIndex(
        listOf(
            SubmissionCategory(33295, "Skins", null, SubmissionKind.Mod),
            SubmissionCategory(33306, "Abrams", 33295, SubmissionKind.Mod),
            SubmissionCategory(49629, "Rat King", 33295, SubmissionKind.Mod),
            SubmissionCategory(49852, "RatKing Flag", 49629, SubmissionKind.Mod),
            SubmissionCategory(33154, "Model Replacement", null, SubmissionKind.Mod),
            SubmissionCategory(44691, "NPCs", 33154, SubmissionKind.Mod),
            SubmissionCategory(31713, "HUD", null, SubmissionKind.Mod),
            SubmissionCategory(99999, "Brand new root", null, SubmissionKind.Mod),
            SubmissionCategory(5843, "Killsounds", null, SubmissionKind.Sound),
        ),
    )

    @Test
    fun skinsArePlacedUnderTheirHeroAtAnyDepth() {
        assertEquals(Placement(ModCategory.Skins, kind = null, heroCategory = "Abrams"), index.place(submission(SubmissionKind.Mod, 33306, 33295)))
        assertEquals(Placement(ModCategory.Skins, kind = "RatKing Flag", heroCategory = "Rat King"), index.place(submission(SubmissionKind.Mod, 49852, 33295)))
        assertEquals(Placement(ModCategory.Skins, kind = null, heroCategory = null), index.place(submission(SubmissionKind.Mod, 33295, 33295)))
    }

    @Test
    fun otherRootsGiveTheirCategoryAndLeafAsKind() {
        assertEquals(Placement(ModCategory.Models, "NPCs", null), index.place(submission(SubmissionKind.Mod, 44691, 33154)))
        assertEquals(Placement(ModCategory.Hud, null, null), index.place(submission(SubmissionKind.Mod, 31713, 31713)))
        assertEquals(ModCategory.Other, index.place(submission(SubmissionKind.Mod, 99999, 99999)).category)
        assertEquals(Placement(ModCategory.Sounds, "Killsounds", null), index.place(submission(SubmissionKind.Sound, 5843, 5843)))
    }

    @Test
    fun aCategoryMissingFromTheTreeFallsBackToTheListedRoot() {
        assertEquals(Placement(ModCategory.Hud, null, null), index.place(submission(SubmissionKind.Mod, 123, 31713)))
    }

    private fun submission(model: SubmissionKind, categoryId: Long, rootId: Long) = Submission(
        key = SubmissionRef(model, 1), name = "A mod", profileUrl = "", submitter = null, categoryId = categoryId, rootCategoryId = rootId,
        tags = emptyList(), images = emptyList(), audioUrl = null, likes = 0, addedAt = 0, updatedAt = null, obsolete = false, visibility = "show",
    )
}
