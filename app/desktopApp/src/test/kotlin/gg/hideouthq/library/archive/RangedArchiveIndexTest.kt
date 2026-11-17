package gg.hideouthq.library.archive

import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.printOf
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.library.download
import gg.hideouthq.library.zip
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.tukaani.xz.FinishableWrapperOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.CRC32
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class RangedArchiveIndexTest {
    private val pak21 = Random(21).nextBytes(1 shl 20)
    private val pak22 = Random(22).nextBytes(3_000)
    private val readme = "made for Infernus".encodeToByteArray()
    private val served = mutableListOf<LongRange>()
    private val fetched: Long get() = served.sumOf { it.last - it.first + 1 }

    private fun index(files: Map<Long, ByteArray>) = RangedArchiveIndex(hideoutHttpClient(MockEngine { request -> download(request, files, served) ?: respondError(HttpStatusCode.NotFound) }, "hideout-test"))

    private fun file(id: Long, name: String, bytes: ByteArray) = RemoteFile(id, name, bytes.size.toLong(), null, null, 1, 1, "https://gamebanana.com/dl/$id")

    private fun read(name: String, bytes: ByteArray, index: RangedArchiveIndex = index(mapOf(1L to bytes))) = runBlocking { index.vpks(file(1, name, bytes)) }

    @Test
    fun aZipIsKnownByItsFirstBytesAndListedFromItsLastSixtyFourKibibytes() {
        val archive = zip("ace/pak21_dir.vpk" to pak21, "ace/readme.txt" to readme, "ace/PAK22_DIR.VPK" to pak22)
        assertEquals(listOf(printOf(pak21), printOf(pak22)), read("ace_infernus_83fb6.rar", archive), "whatever its name says")
        assertEquals(listOf(0L until 65_536L, archive.size - 65_536L until archive.size), served, "its first window, then its tail")
    }

    @Test
    fun aCentralDirectoryLongerThanTheTailCostsAWindowMore() {
        val names = (1..900).map { "materials/models/heroes/infernus/ace/texture_$it.vtex_c" to readme }
        val archive = zip(*(names + ("ace/pak21_dir.vpk" to pak21)).toTypedArray())
        assertEquals(listOf(printOf(pak21)), read("ace.zip", archive))
    }

    @Test
    fun aRar5IsListedHeaderByHeaderAroundWhatItHolds() {
        val archive = Rar5.Signature + Rar5.main() + Rar5.file("pak21_dir.vpk", pak21) + Rar5.file("readme.txt", readme) + Rar5.file("pak22_dir.vpk", pak22) + Rar5.end()
        assertEquals(listOf(printOf(pak21), printOf(pak22)), read("ace_infernus_ognb_db0f9.rar", archive))
        assertEquals(listOf(0L until 65_536L, archive.size - 65_536L until archive.size), served, "the headers before pak21_dir.vpk's bytes, then those after")
    }

    @Test
    fun aRar4IsListedTheSameWay() {
        val archive = Rar4.Marker + Rar4.main() + Rar4.file("ace\\pak21_dir.vpk", pak21) + Rar4.file("ace\\readme.txt", readme) + Rar4.end()
        assertEquals(listOf(printOf(pak21)), read("ace_old.rar", archive))
    }

    @Test
    fun a7zIsListedFromItsStartAndItsHeaderPackedOrNot() {
        val plain = sevenZip("ace/pak21_dir.vpk" to pak21, "ace/readme.txt" to readme, "ace/pak22_dir.vpk" to pak22)
        for (archive in listOf(plain, packHeader(plain, lzma2 = false), packHeader(plain, lzma2 = true))) {
            served.clear()
            assertEquals(listOf(printOf(pak21), printOf(pak22)), read("ace.7z", archive))
            assertEquals(listOf(0L until 65_536L, archive.size - 65_536L until archive.size), served, "the start header, then the header at the end")
        }
        assertNull(read("ace.7z", packHeader(plain, lzma2 = false, padding = 16 shl 20)), "a header packed to unpack past 16 MiB is never unpacked")
    }

    @Test
    fun a7zHeaderSaidToLiePastTheEndOfAnyFileIsNeverLookedFor() {
        val plain = sevenZip("pak22_dir.vpk" to pak22)
        assertNull(read("ace.7z", plain.copyOf().also { le64(Long.MAX_VALUE).copyInto(it, 12) }))
        assertEquals(listOf(0L until plain.size.toLong()), served)
    }

    @Test
    fun a7zHeaderOfEndlessCodersWhosePropertiesSkipBackToThemIsNoEvidenceAtOnce() {
        val loop = byteArrayOf(0x17, 0x06) + number(0) + number(1) + byteArrayOf(0x09) + number(1) + byteArrayOf(0x00, 0x07, 0x0B) + number(1) + byteArrayOf(0x00) +
            number(Int.MAX_VALUE.toLong()) + byteArrayOf(0x20, 0xFF.toByte()) + le64(-10)
        val pointer = le64(0) + le64(loop.size.toLong()) + le32(crc(loop))
        val took = measureTime { assertNull(read("ace.7z", sevenZip("pak22_dir.vpk" to pak22).copyOf(8) + le32(crc(pointer)) + pointer + loop)) }
        assertTrue(took < 5.seconds, "took $took")
    }

    @Test
    fun anArchiveTooBigToListOrBrokenOrNotOneIsNoEvidence() {
        val zip = zip("pak21_dir.vpk" to pak21)
        assertNull(runBlocking { index(emptyMap()).vpks(file(9, "deleted.zip", zip)) }, "a deleted file redirects to a page")
        assertNull(read("ace.zip", byteArrayOf(0x1F, 0x8B.toByte(), 8, 0) + zip), "a gzip")
        assertNull(read("ace.rar", Rar5.Signature + Rar5.main() + Rar5.encryption() + Rar5.file("pak21_dir.vpk", pak21)), "encrypted RAR headers")
        assertNull(read("ace.zip", zip.copyOf().also { bytes -> for (at in bytes.size - 6 until bytes.size - 2) bytes[at] = 0xFF.toByte() }), "an end record pointing past the file")
        assertNull(read("ace.7z", sevenZip("pak21_dir.vpk" to pak21).copyOf(200)), "an archive shorter than its index says")
        served.clear()
        val huge = zip(*(1..45_000).map { "materials/models/heroes/infernus/ace/texture_$it.vtex_c" to readme }.toTypedArray())
        assertNull(read("ace.zip", huge), "a central directory past what one file may cost")
        assertTrue(fetched <= 4 shl 20, "$fetched bytes")
        val whole = RangedArchiveIndex(HttpClient(MockEngine { respond(zip) }))
        assertNull(runBlocking { whole.vpks(file(1, "ace.zip", zip)) }, "a download not served in part")
        val endless = RangedArchiveIndex(HttpClient(MockEngine { request ->
            val from = request.headers[HttpHeaders.Range]!!.removePrefix("bytes=").substringBefore('-')
            respond(ByteChannel(), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange to listOf("bytes $from-${zip.size - 1}/${zip.size}"), HttpHeaders.ContentLength to listOf("${zip.size}")))
        }))
        assertNull(runBlocking { withTimeout(10.seconds) { endless.vpks(file(1, "ace.zip", zip)) } }, "a part longer than asked for, refused unread")
    }

    @Test
    fun whatAFileSaidIsKeptButAFailedRequestIsAskedAgain() {
        val archive = zip("pak21_dir.vpk" to pak21)
        val index = index(mapOf(1L to archive))
        repeat(2) { assertEquals(listOf(printOf(pak21)), read("ace.zip", archive, index)) }
        repeat(2) { assertNull(runBlocking { index.vpks(file(2, "ace.zip", archive)) }) }
        assertEquals(2, served.size, "each file asked once: the one GameBanana has its first window and tail, the one it does not nothing")
        var offline = true
        var busy: HttpStatusCode? = null
        val flaky = RangedArchiveIndex(HttpClient(MockEngine { request -> if (offline) throw IOException("offline") else busy?.let { respondError(it) } ?: download(request, mapOf(1L to archive))!! }))
        assertNull(read("ace.zip", archive, flaky))
        offline = false
        for (status in listOf(HttpStatusCode.ServiceUnavailable, HttpStatusCode.TooManyRequests)) {
            busy = status
            assertNull(read("ace.zip", archive, flaky), "$status, after the client's own retries")
        }
        busy = null
        assertEquals(listOf(printOf(pak21)), read("ace.zip", archive, flaky))
    }

    /**
     * A 7z of [entries] stored, one folder each, with the header 7zFormat.txt lays out as 7-Zip writes it: each file's
     * CRC-32 among its substreams.
     */
    private fun sevenZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val count = number(entries.size.toLong())
        val sizes = entries.map { number(it.second.size.toLong()) }.reduce(ByteArray::plus)
        val names = entries.joinToString("") { it.first + '\u0000' }.toByteArray(Charsets.UTF_16LE)
        val header = byteArrayOf(0x01, 0x04, 0x06) + number(0) + count + byteArrayOf(0x09) + sizes + byteArrayOf(0x00) +
            byteArrayOf(0x07, 0x0B) + count + byteArrayOf(0x00) + entries.map { byteArrayOf(0x01, 0x01, 0x00) }.reduce(ByteArray::plus) + byteArrayOf(0x0C) + sizes + byteArrayOf(0x00) +
            byteArrayOf(0x08, 0x0A, 0x01) + entries.map { le32(crc(it.second)) }.reduce(ByteArray::plus) + byteArrayOf(0x00, 0x00) +
            byteArrayOf(0x05) + count + byteArrayOf(0x11) + number(names.size + 1L) + byteArrayOf(0x00) + names + byteArrayOf(0x00, 0x00)
        val pointer = le64(entries.sumOf { it.second.size }.toLong()) + le64(header.size.toLong()) + le32(crc(header))
        return byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4) + le32(crc(pointer)) + pointer + entries.map { it.second }.reduce(ByteArray::plus) + header
    }

    private fun packHeader(plain: ByteArray, lzma2: Boolean, padding: Int = 0): ByteArray {
        val start = java.nio.ByteBuffer.wrap(plain, 12, 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val headerAt = start.long.toInt()
        val header = plain.copyOfRange(32 + headerAt, 32 + headerAt + start.long.toInt()) + ByteArray(padding)
        val options = LZMA2Options().apply { dictSize = 1 shl 20 }
        val out = ByteArrayOutputStream()
        val coder = if (lzma2) {
            options.getOutputStream(FinishableWrapperOutputStream(out)).use { it.write(header) }
            byteArrayOf(0x21, 0x21, 1, 16)
        } else {
            val lzma = LZMAOutputStream(out, options, false)
            lzma.use { it.write(header) }
            byteArrayOf(0x23, 3, 1, 1, 5, lzma.props.toByte()) + le32(options.dictSize.toLong())
        }
        val packed = out.toByteArray()
        val encoded = byteArrayOf(0x17, 0x06) + number(headerAt.toLong()) + number(1) + byteArrayOf(0x09) + number(packed.size.toLong()) + byteArrayOf(0x00) +
            byteArrayOf(0x07, 0x0B) + number(1) + byteArrayOf(0x00) + number(1) + coder + byteArrayOf(0x0C) + number(header.size.toLong()) +
            byteArrayOf(0x0A, 0x01) + le32(crc(header)) + byteArrayOf(0x00, 0x00)
        val pointer = le64(headerAt.toLong() + packed.size) + le64(encoded.size.toLong()) + le32(crc(encoded))
        return plain.copyOf(6) + byteArrayOf(0, 4) + le32(crc(pointer)) + pointer + plain.copyOfRange(32, 32 + headerAt) + packed + encoded
    }

    /** RAR 5 blocks as RARLAB's technote lays them out: a CRC-32 of the header, its size, then its fields; files stored. */
    private object Rar5 {
        val Signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

        private fun block(fields: ByteArray, data: ByteArray = ByteArray(0)): ByteArray {
            val size = vint(fields.size.toLong())
            return le32(crc(size + fields)) + size + fields + data
        }

        fun main() = block(vint(1) + vint(0) + vint(0))

        fun file(name: String, data: ByteArray): ByteArray {
            val encoded = name.encodeToByteArray()
            return block(vint(2) + vint(0x02) + vint(data.size.toLong()) + vint(0x04) + vint(data.size.toLong()) + vint(0x20) + le32(crc(data)) + vint(0) + vint(0) + vint(encoded.size.toLong()) + encoded, data)
        }

        fun encryption() = block(vint(4) + vint(0) + vint(0) + vint(0) + byteArrayOf(15) + ByteArray(16))

        fun end() = block(vint(5) + vint(0) + vint(0))
    }

    /** RAR 4 blocks as its technote lays them out: CRC, type, flags and size, then the type's fields; files stored. */
    private object Rar4 {
        val Marker = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)

        private fun block(type: Int, flags: Int, fields: ByteArray, data: ByteArray = ByteArray(0)): ByteArray {
            val head = byteArrayOf(type.toByte()) + le16(flags) + le16(7 + fields.size) + fields
            return le16(crc(head).toInt() and 0xFFFF) + head + data
        }

        fun main() = block(0x73, 0, ByteArray(6))

        fun file(name: String, data: ByteArray): ByteArray {
            val encoded = name.encodeToByteArray()
            val fields = le32(data.size.toLong()) + le32(data.size.toLong()) + byteArrayOf(2) + le32(crc(data)) + le32(0x5A4C_0000) + byteArrayOf(20, 0x30) + le16(encoded.size) + le32(0x20) + encoded
            return block(0x74, 0x8000, fields, data)
        }

        fun end() = block(0x7B, 0x4000, ByteArray(0))
    }
}

private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

private fun le16(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte())

private fun le32(value: Long) = ByteArray(4) { (value ushr (8 * it)).toByte() }

private fun le64(value: Long) = ByteArray(8) { (value ushr (8 * it)).toByte() }

private fun vint(value: Long): ByteArray {
    val out = ByteArrayOutputStream()
    var rest = value
    do {
        val low = (rest and 0x7F).toInt()
        rest = rest ushr 7
        out.write(if (rest != 0L) low or 0x80 else low)
    } while (rest != 0L)
    return out.toByteArray()
}

/** 7-Zip's NUMBER: as many leading ones in the first byte as bytes follow, low first, and the top bits in the rest of it. */
private fun number(value: Long): ByteArray {
    val extra = (0..8).first { it == 8 || value < (1L shl (7 * it + 7)) }
    val high = if (extra == 8) 0 else (value ushr (8 * extra)).toInt()
    return byteArrayOf(((0xFF00 ushr extra) and 0xFF or high).toByte()) + ByteArray(extra) { (value ushr (8 * it)).toByte() }
}
