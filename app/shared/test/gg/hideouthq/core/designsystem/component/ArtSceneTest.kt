package gg.hideouthq.core.designsystem.component

import gg.hideouthq.resources.Res
import gg.hideouthq.resources.onboarding_skyline
import gg.hideouthq.resources.onboarding_skyline_wide
import kotlin.test.Test
import kotlin.test.assertEquals

class ArtSceneTest {
    @Test
    fun aPanelTakesTheWidePictureOnceItIsOnePointOneFiveTimesAsWideAsItIsTall() {
        assertEquals(Res.drawable.onboarding_skyline, ArtScene.Skyline.picture(1.14f))
        assertEquals(Res.drawable.onboarding_skyline_wide, ArtScene.Skyline.picture(1.15f))
        assertEquals(Res.drawable.onboarding_skyline_wide, ArtScene.Skyline.picture(1.6f))
    }
}
