package gg.hideouthq.feature.install.components

import gg.hideouthq.core.ui.SizeAmount
import gg.hideouthq.core.ui.SizeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class DialogSizeTest {
    @Test
    fun sizesKeepOneDecimalFromAMegabyteUpSoNearVersionsDiffer() {
        assertEquals(SizeAmount("31.2", SizeUnit.MB), dialogSizeAmount(31_200_000))
        assertEquals(SizeAmount("31.2", SizeUnit.MB), dialogSizeAmount(31_249_999))
        assertEquals(SizeAmount("62.0", SizeUnit.MB), dialogSizeAmount(62_000_000), "the reference keeps a trailing .0")
        assertEquals(SizeAmount("1.0", SizeUnit.MB), dialogSizeAmount(1_000_000))
        assertEquals(SizeAmount("900", SizeUnit.KB), dialogSizeAmount(900_000))
        assertEquals(SizeAmount("1", SizeUnit.KB), dialogSizeAmount(12))
        assertEquals(SizeAmount("999.9", SizeUnit.MB), dialogSizeAmount(999_900_000))
        assertEquals(SizeAmount("1.0", SizeUnit.GB), dialogSizeAmount(999_960_000), "rounding up to 1000 MB shows as gigabytes")
        assertEquals(SizeAmount("1.3", SizeUnit.GB), dialogSizeAmount(1_250_000_000))
    }
}
