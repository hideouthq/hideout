package gg.hideouthq.feature.moddetail.viewer

import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.preview.PictureRole
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewModelTest {
    private fun model(
        featuredHides: List<String> = listOf(".flame_hair"),
        detailHides: List<String> = listOf(".flame_hair"),
    ): PreviewModel {
        val manifest = PreviewFixtures.manifest(featuredHides = featuredHides, detailHides = detailHides)
        return PreviewModel(PreviewFixtures.entry(manifest), manifest)
    }

    @Test
    fun theDetailStillStandsInForTheModelAndItsHiddenMeshesApplyToTheModel() {
        val model = model(featuredHides = listOf(".flame_hair"), detailHides = listOf(".flask_in_hand_l"))
        assertEquals(PictureRole.Detail, model.fallbackPicture?.role)
        assertEquals(listOf(".flask_in_hand_l"), model.hiddenMeshSuffixes)
    }

    @Test
    fun theFeaturedStillIsUsedWithoutADetailOne() {
        val base = model()
        val featuredOnly = base.manifest.copy(pictures = base.manifest.pictures.filter { it.role == PictureRole.Featured })
        assertEquals(PictureRole.Featured, PreviewModel(base.entry, featuredOnly).fallbackPicture?.role)
    }
}
