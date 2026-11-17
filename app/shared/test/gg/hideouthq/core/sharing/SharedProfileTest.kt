package gg.hideouthq.core.sharing

import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.directory
import gg.hideouthq.core.library.oneSetRecord
import gg.hideouthq.core.model.CalendarDate
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.model.PreviewVariant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedProfileTest {
    private val root = GameRoot("/games/deadlock")

    @Test
    fun itWritesMinimalLeb128VarintsAfterTheVersionAndName() {
        val sample = SharedProfile("Ranked", listOf(SharedMod(700_001, setOf(7))))
        assertEquals("010652616e6b6564e1dc2a0107", sample.bytes().toHex())
        assertEquals(sample, SharedProfile.read("010652616e6b6564e1dc2a0107".hexBytes()))
        assertEquals(SharedProfile("Ranked", listOf(SharedMod(699_969, setOf(7)))), SharedProfile.read("010652616e6b6564c1dc2a0107".hexBytes()))
    }

    @Test
    fun aProfileComesBackAsItWentInThroughBytesAndBase64() {
        val profile = SharedProfile("Halloween 🎃 night", listOf(SharedMod(731_748), SharedMod(1, setOf(1_500_000, 3)), SharedMod((1L shl 53) - 1, setOf(9))))
        assertEquals(profile, SharedProfile.read(profile.bytes()))
        assertEquals(profile, SharedProfile.read(profile.encoded))
        assertEquals(profile, SharedProfile.read(profile.encoded + "=="), "padding is allowed")
        assertEquals(listOf(1_500_000L, 3L), SharedProfile.read(profile.encoded)!!.mods[1].files.toList(), "pins keep their order")
    }

    @Test
    fun anythingButExactlyWhatItWritesIsUnreadable() {
        val good = SharedProfile("Ranked", listOf(SharedMod(700_001, setOf(7)))).bytes()
        val bad = listOf(
            "empty" to byteArrayOf(),
            "other version" to byteArrayOf(2) + good.drop(1),
            "no mods" to "0106".hexBytes() + "Ranked".encodeToByteArray(),
            "cut mid-mod" to good.copyOf(good.size - 1),
            "trailing byte" to good + 0x05,
            "padded varint" to "010652616e6b6564e1dcaa000107".hexBytes(),
            "zero submission" to "010652616e6b65640000".hexBytes(),
            "zero file" to "010652616e6b6564010100".hexBytes(),
            "seventeen pins" to "010652616e6b65640111".hexBytes() + (1..17).map { it.toByte() },
            "repeated pin" to "010652616e6b6564010207 07".replace(" ", "").hexBytes(),
            "repeated mod" to "010652616e6b656401000100".hexBytes(),
            "empty name" to "01000100".hexBytes(),
            "untrimmed name" to "01022061".hexBytes() + "0100".hexBytes(),
            "control in name" to "0101070100".hexBytes(),
            "invalid UTF-8" to "0102c328".hexBytes() + "0100".hexBytes(),
            "name over 100 bytes" to byteArrayOf(1, 101) + ByteArray(101) { 'a'.code.toByte() } + "0100".hexBytes(),
            "number over 2^53-1" to "0106".hexBytes() + "Ranked".encodeToByteArray() + "8080808080808010".hexBytes() + 0,
        )
        bad.forEach { (why, bytes) -> assertNull(SharedProfile.read(bytes), why) }
        val mods = { count: Int -> byteArrayOf(1, 1, 'a'.code.toByte()) + (128 until 128 + count).flatMap { listOf((it and 0x7F or 0x80).toByte(), (it shr 7).toByte(), 0) } }
        assertEquals(500, SharedProfile.read(mods(500))?.mods?.size)
        assertNull(SharedProfile.read(mods(501)), "501 mods")
        listOf("", "not base64!", "AQ", Base64.getUrlEncoder().encodeToString(good).replace('-', '+') + "+/").forEach { assertNull(SharedProfile.read(it), it) }
    }

    @Test
    fun aProfileSharesTheGameBananaModsItTurnsOnWinnerFirstAndCountsTheRestLeftOut() {
        val records = listOf(
            remote("a", 100, 1000L),
            remote("b", 200, 2000L),
            local("c"),
            remote("d", 300, 3000L),
            remote("e", 400, 4000L, kind = SubmissionKind.Sound),
            remote("f", 100, 1001L),
        )
        val profile = profile(on = setOf("d", "a", "c", "e", "f"), order = listOf("d", "b", "c", "a", "e", "f"))
        val share = profile.share(records) { null }
        assertEquals(listOf(300L, 100L), share.profile!!.mods.map { it.submission }, "winner first; b is off, and f is a second copy of a")
        assertEquals(2, share.leftOut, "the mod from a file and the sound")
        assertEquals("Ranked", share.profile.name)
    }

    @Test
    fun aModsFilesArePinnedOnlyWhenInstallWouldNotTakeThemUnasked() {
        val catalogue = mapOf(
            "mod-100" to mod(100, variant(1000, downloads = 50), variant(1001, downloads = 10)),
            "mod-200" to mod(200, variant(2000, downloads = 50), variant(2001, downloads = 10)),
            "mod-300" to mod(300, variant(3001, downloads = 5)),
            "mod-500" to mod(500, variant(5000, downloads = 50), variant(5001, downloads = 10, description = "Optional add-on")),
        )
        val records = listOf(
            remote("default", 100, 1000L),
            remote("variant", 200, 2001L),
            remote("delisted", 300, 3000L),
            remote("uncatalogued", 400, 4000L),
            remote("both", 500, 5000L, 5001L),
        )
        val ids = records.map { it.installId }
        val shared = profile(ids.toSet(), ids).share(records, catalogue::get).profile!!
        assertEquals(
            listOf(100L to emptySet(), 200L to setOf(2001L), 300L to emptySet(), 400L to setOf(4000L), 500L to setOf(5000L, 5001L)),
            shared.mods.map { it.submission to it.files },
        )
    }

    @Test
    fun aProfileWithNothingToShareSharesNothingAndALongNameIsCutToWholeCharacters() {
        assertEquals(ProfileShare(null, 1), profile(setOf("c"), listOf("c")).share(listOf(local("c"))) { null })
        val long = "é".repeat(60)
        val shared = profile(setOf("a"), listOf("a"), name = long).share(listOf(remote("a", 100, 1000L))) { null }.profile!!
        assertEquals("é".repeat(50), shared.name)
    }

    @Test
    fun evenFiftyPinnedModsFitInAChatMessageAsALongLink() {
        val site = checkNotNull(SiteOrigin.parse("https://hideouthq.gg"))
        val fifty = SharedProfile("Ranked", (1..50).map { SharedMod(700_000L + it * 37, setOf(1_500_000L + it)) })
        assertTrue(fifty.longLink(site).length < 2000, "Discord's limit")
        assertEquals(31, checkNotNull(ShareCode.parse("K7Q2M9XD")).webLink(site).length)
    }

    private fun profile(on: Set<String>, order: List<String>, name: String = "Ranked") =
        Profile(ProfileId("ranked"), name, ProfileColour.Blue, on, mapOf(root to order))

    private fun remote(id: String, submission: Long, vararg files: Long, kind: SubmissionKind = SubmissionKind.Mod): ManifestRecord {
        val first = oneSetRecord(id, root, InstalledSource.Remote(SubmissionIdentity(kind, submission, files.first(), null)), "Mod $id", null, archive, 5, listOf(directory(image)))
        return first.copy(sets = files.mapIndexed { index, file -> first.sets.single().copy(source = InstalledSource.Remote(SubmissionIdentity(kind, submission, file, null)), componentKey = "pak0${index + 1}_dir.vpk") })
    }

    private fun local(id: String) = oneSetRecord(id, root, InstalledSource.Local(id, "$id.zip"), "Mod $id", null, archive, 5, listOf(directory(image)))

    private fun mod(id: Long, vararg variants: PreviewVariant) = Mod(
        id = "mod-$id", title = "Mod $id", category = ModCategory.Skins, downloads = 1, likes = 0, sizeBytes = null,
        publishedAt = 0, updated = CalendarDate(2026, 10, 1), trendingRank = 1, previewVariants = variants.toList(),
    )

    private fun variant(fileId: Long, downloads: Long, description: String? = null) = PreviewVariant(fileId, null, "file$fileId.zip", description, downloads = downloads)

    private companion object {
        val archive = ArchiveIdentity("/downloads/a.zip", 10, "a".repeat(64))
        val image = FileImage(10, "b".repeat(64))
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
