package gg.hideouthq.feature.moddetail

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.feature.moddetail.viewer.SoundStageState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DetailStageTest {
    private fun header(): Pair<ModHeader, PreviewModel> {
        val manifest = PreviewFixtures.manifest()
        val entry = PreviewFixtures.entry(manifest)
        val header = ModHeader(
            modId = "mod-1", title = "Fixture skin", category = ModCategory.Skins, heroName = "Infernus", kind = null,
            has3dPreview = true, worksOnCurrentPatch = true, published = CalendarDate(2026, 4, 6),
            updated = CalendarDate(2026, 4, 6), downloads = 1, likes = 1, cover = CoverImage(), preview = entry,
        )
        return header to PreviewModel(entry, manifest)
    }

    private fun kind(header: ModHeader, preview: Loadable<PreviewModel>?) =
        stageKind(ModDetailUiState(loading = false, header = header, preview = preview), header)

    @Test
    fun theAuthorsPicturesShowForAModWithNoPreview() {
        val (header, _) = header()
        assertEquals(StageKind.Pictures, kind(header.copy(has3dPreview = false, preview = null), null))
    }

    @Test
    fun aPreviewKeepsTheModelStageWhateverItsLoadAndASoundKeepsItsOwn() {
        val (header, model) = header()
        assertEquals(StageKind.Model, kind(header, Loadable.Loading))
        assertEquals(StageKind.Model, kind(header, Loadable.Ready(model)))
        assertEquals(StageKind.Model, kind(header, Loadable.Failed(DataError.Unavailable)))
        val sound = header.copy(category = ModCategory.Sounds, has3dPreview = false, preview = null)
        val state = ModDetailUiState(loading = false, header = sound, sound = SoundStageState(available = true, playing = false, loading = false, lengthMillis = null, looping = false))
        assertEquals(StageKind.Sound, stageKind(state, sound))
    }

    @Test
    fun aSoundHasAnImagesTabOnlyWhenItsModPageHasImages() {
        val (header, _) = header()
        val sound = header.copy(category = ModCategory.Sounds, has3dPreview = false, preview = null)
        val playable = SoundStageState(available = true, playing = false, loading = false, lengthMillis = null, looping = false)
        val pictured = ModDetail(sound.modId, authors = emptyList(), about = "", version = null, images = listOf(CoverImage(url = "https://images.invalid/1.jpg")))
        val state = ModDetailUiState(loading = false, header = sound, sound = playable, detail = pictured)
        assertTrue(stageTabbed(state, StageKind.Sound))
        assertFalse(stageTabbed(state.copy(detail = pictured.copy(images = emptyList())), StageKind.Sound), "no tab for its cover alone")
        assertFalse(stageTabbed(state.copy(detail = null), StageKind.Sound), "nor before the mod page is read")
        assertTrue(stageTabbed(state.copy(detail = null), StageKind.Model), "a 3D viewer always has its Images tab")
        assertFalse(stageTabbed(state, StageKind.Pictures), "the images are the whole stage")
    }

    @Test
    fun anExplicitReadyFileShowsItsModelWithoutChoosingAnAmbiguousDefault() {
        val (single, model) = header()
        val header = single.copy(has3dPreview = false, preview = null, previewVariants = listOf(
            gg.hideouthq.core.model.PreviewVariant(model.entry.fileId, model.entry.fileMd5, "skin.zip", null, model.entry),
        ))
        val state = ModDetailUiState(header = header, preview = Loadable.Ready(model), selectedFile = FileSelection(model.entry.fileId))
        assertEquals(StageKind.Model, stageKind(state, header))
        assertEquals(StageKind.Pictures, stageKind(state.copy(selectedFile = null), header))
        assertEquals(StageKind.Pictures, stageKind(state.copy(selectedFile = FileSelection(999)), header))
        assertEquals(StageKind.Pictures, stageKind(state.copy(selectedFile = FileSelection(999)), single), "nor does a default model stand in for a missing file")
    }

    @Test
    fun theAboutSectionCreditsOnlyTheVerifiedBundleOnTheStage() {
        val (header, model) = header()
        val shown = ModDetailUiState(loading = false, header = header, preview = Loadable.Ready(model))
        assertEquals(model.manifest, stageCredit(shown, StageKind.Model))
        assertNull(stageCredit(shown, StageKind.Pictures), "the mod page's images show no generated preview to credit")
        val otherFile = PreviewFixtures.manifest(fileId = 102)
        val stale = PreviewModel(PreviewFixtures.entry(otherFile), otherFile)
        assertNull(stageCredit(shown.copy(preview = Loadable.Ready(stale)), StageKind.Model), "another file's entry is stale")
        assertNull(stageCredit(shown.copy(preview = Loadable.Loading), StageKind.Model))
    }
}
