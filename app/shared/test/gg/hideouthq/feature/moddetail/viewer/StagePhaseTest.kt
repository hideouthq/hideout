package gg.hideouthq.feature.moddetail.viewer

import gg.hideouthq.core.data.preview.PreviewFixtures
import gg.hideouthq.preview.SubmissionRef
import kotlin.test.Test
import kotlin.test.assertEquals

class StagePhaseTest {
    private val content = StageContent(heroName = "Silver", styleName = "Default", model = null)
    private val model = PreviewFixtures.manifest(SubmissionRef("Mod", 1), 101, PreviewFixtures.Md5).let { PreviewModel(PreviewFixtures.entry(it), it) }

    @Test
    fun aHeldStageIsHeldWhateverElseIsTrue() {
        assertEquals(StagePhase.Held, stagePhase(content.copy(held = true, pending = true), ViewportStatus.Loading(0.5f)))
    }

    @Test
    fun theManifestAndTheModelBothCountAsLoading() {
        assertEquals(StagePhase.Loading, stagePhase(content.copy(pending = true), ViewportStatus.NoModel))
        assertEquals(StagePhase.Loading, stagePhase(content, ViewportStatus.Loading(null)))
    }

    @Test
    fun aModelTheViewportHasNotYetReportedOnIsStillLoading() {
        assertEquals(StagePhase.Loading, stagePhase(content.copy(model = model), ViewportStatus.NoModel))
    }

    @Test
    fun aFailureThatCanBeRetriedIsFailedEvenWithAStillStandingIn() {
        assertEquals(StagePhase.Failed, stagePhase(content.copy(failed = true), ViewportStatus.NoModel))
        assertEquals(StagePhase.Failed, stagePhase(content, ViewportStatus.Unavailable(still = true, canRetry = true)))
    }

    @Test
    fun aDrawnModelOrAStillOnlyPreviewHasSettled() {
        assertEquals(StagePhase.Settled, stagePhase(content, ViewportStatus.Showing))
        assertEquals(StagePhase.Settled, stagePhase(content, ViewportStatus.Unavailable(still = true, canRetry = false)))
    }
}
