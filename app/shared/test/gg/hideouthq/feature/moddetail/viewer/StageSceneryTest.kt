package gg.hideouthq.feature.moddetail.viewer

import gg.hideouthq.core.model.Backdrop
import gg.hideouthq.core.model.SceneLighting
import gg.hideouthq.core.model.SceneSettings
import kotlin.test.Test
import kotlin.test.assertEquals

class StageSceneryTest {
    @Test
    fun resetKeepsTheUsersOwnPictureAndAMissingOneShowsStudio() {
        val custom = SceneSettings(Backdrop.Custom, SceneLighting.Soft, turntable = false, bloom = true, customBackdrop = "/data/backdrops/a.png")
        assertEquals(SceneSettings(customBackdrop = "/data/backdrops/a.png"), custom.reset())
        assertEquals(Backdrop.Custom, custom.shownBackdrop)
        assertEquals(Backdrop.Studio, custom.copy(customBackdrop = null).shownBackdrop)
    }
}
