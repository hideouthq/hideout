package gg.hideouthq.feature.appupdate

import androidx.compose.ui.text.SpanStyle
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.boldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppUpdateLabelsTest {
    @Test
    fun sizesReadTheWayTheReleaseNotesQuoteThem() {
        assertEquals("18.4", updateMegabytes(19_293_798))
        assertEquals("11.4", updateMegabytes(11_962_154))
        assertEquals("0.0", updateMegabytes(0))
    }

    @Test
    fun theToastBoldsOnlyTheVersion() {
        val text = boldValue("Hideout updated to 1.4.0", "1.4.0", SpanStyle(fontWeight = HideoutWeights.Title))
        assertEquals("Hideout updated to 1.4.0", text.text)
        val bold = text.spanStyles.single()
        assertEquals(HideoutWeights.Title, bold.item.fontWeight)
        assertEquals("1.4.0", text.text.substring(bold.start, bold.end))
    }

    @Test
    fun aWordThatIsNotThereLeavesTheTextPlain() {
        assertTrue(boldValue("Hideout updated", "1.4.0", SpanStyle(fontWeight = HideoutWeights.Title)).spanStyles.isEmpty())
    }
}
