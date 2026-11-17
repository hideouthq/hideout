package gg.hideouthq.core.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImprintTest {
    private val mod = resourceBytes("grimoire/modinfo.json").decodeToString()
    private val vindicta = Imprint(ModIdentity.File(SubmissionIdentity(SubmissionKind.Mod, 512_345, 987_654, null)), FilePrint(18_874_368, 0x3B7A2C91))

    private fun read(text: String) = Imprint.read(text.encodeToByteArray())

    @Test
    fun theSpecsModRecordNamesItsFileAndItsBytesBeforeImprinting() {
        assertEquals(vindicta, read(mod))
        assertEquals(Imprint(ModIdentity.Submission(SubmissionRef(SubmissionKind.Mod, 512_345)), vindicta.original), read(mod.replace("\"gamebananaFileId\": 987654,", "")))
        assertEquals(Imprint(ModIdentity.File(SubmissionIdentity(SubmissionKind.Sound, 512_345, 987_654, null)), vindicta.original), read(mod.replace("\"section\": \"Mod\"", "\"section\": \"Sound\"")))
        assertEquals(Imprint(ModIdentity.None, vindicta.original), read(mod.replace("\"section\": \"Mod\"", "\"section\": \"Wip\"")), "a work in progress is no mod Hideout installs")
        assertEquals(Imprint(vindicta.claim, null), read(mod.replace("\"crc32\": \"3b7a2c91\"", "\"crc32\": \"3b7a2c9\"")), "a CRC-32 of the wrong shape says nothing of the bytes")
    }

    @Test
    fun aRecordThatIsNotAWholeV1ModRecordIsNone() {
        assertNull(read(resourceBytes("grimoire/modinfo-merge.json").decodeToString()), "a merge is made here, not on GameBanana")
        assertNull(read(mod.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")))
        assertNull(read(mod.replace("\"format\": \"vpk-modinfo\"", "\"format\": \"other\"")))
        assertNull(read(mod.dropLast(10)))
    }
}
