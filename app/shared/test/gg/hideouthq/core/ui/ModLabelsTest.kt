package gg.hideouthq.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ModLabelsTest {
    @Test
    fun sizesUseTheUnitThatReadsBest() {
        assertEquals(SizeAmount("146", SizeUnit.KB), ModLabels.sizeAmount(146_145))
        assertEquals(SizeAmount("1", SizeUnit.KB), ModLabels.sizeAmount(12))
        assertEquals(SizeAmount("999", SizeUnit.KB), ModLabels.sizeAmount(999_499))
        assertEquals(SizeAmount("1", SizeUnit.MB), ModLabels.sizeAmount(999_500))
        assertEquals(SizeAmount("2.4", SizeUnit.MB), ModLabels.sizeAmount(2_400_000))
        assertEquals(SizeAmount("3", SizeUnit.MB), ModLabels.sizeAmount(3_000_000))
        assertEquals(SizeAmount("93", SizeUnit.MB), ModLabels.sizeAmount(93_000_000))
        assertEquals(SizeAmount("999", SizeUnit.MB), ModLabels.sizeAmount(999_499_999))
        assertEquals(SizeAmount("1", SizeUnit.GB), ModLabels.sizeAmount(999_600_000))
        assertEquals(SizeAmount("1.3", SizeUnit.GB), ModLabels.sizeAmount(1_250_000_000))
    }

    @Test
    fun countsRoundIntoTheNextUnitRatherThanReachAThousand() {
        assertEquals("950", ModLabels.count(950))
        assertEquals("4.1k", ModLabels.count(4_100))
        assertEquals("100k", ModLabels.count(99_960))
        assertEquals("999k", ModLabels.count(999_499))
        assertEquals("1M", ModLabels.count(999_600))
        assertEquals("1.3M", ModLabels.count(1_250_000))
    }
}
