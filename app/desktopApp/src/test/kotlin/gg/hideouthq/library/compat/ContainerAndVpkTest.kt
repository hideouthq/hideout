package gg.hideouthq.library.compat

import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.core.library.VpkRead
import gg.hideouthq.library.compat.SyntheticModels.VpkFile
import gg.hideouthq.library.compat.SyntheticModels.resource
import gg.hideouthq.library.compat.SyntheticModels.vpk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ContainerAndVpkTest {
    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** Where a version 2 directory's data starts: after its 28-byte header and the tree it declares. */
    private fun dataStart(vpk: ByteArray) = 28 + ByteBuffer.wrap(vpk).order(ByteOrder.LITTLE_ENDIAN).getInt(8)

    private val md5 = { bytes: ByteArray -> MessageDigest.getInstance("MD5").digest(bytes) }

    @Test
    fun anEmptyRewriteReproducesTheResourceExactly() {
        val bytes = resource(listOf("MVTX" to ByteArray(37) { 1 }, "MDAT" to ByteArray(20) { 2 }, "DATA" to ByteArray(9) { 3 }))
        assertContentEquals(bytes, ResourceContainer.rewrite(ResourceContainer.read(bytes), emptyMap()))
    }

    @Test
    fun aLongerBlockMovesLaterBlocksKeepingTheirAlignmentAndBytes() {
        val bytes = resource(listOf("MVTX" to ByteArray(37) { 1 }, "MDAT" to ByteArray(20) { 2 }, "DATA" to ByteArray(9) { 3 }))
        val file = ResourceContainer.read(bytes)
        val written = ResourceContainer.rewrite(file, mapOf(1 to ByteArray(70) { 5 }))
        val again = ResourceContainer.read(written)
        assertEquals(listOf("MVTX", "MDAT", "DATA"), again.blocks.map { it.type })
        assertContentEquals(file.payload(file.blocks[0]), again.payload(again.blocks[0]))
        assertContentEquals(ByteArray(70) { 5 }, again.payload(again.blocks[1]))
        assertContentEquals(file.payload(file.blocks[2]), again.payload(again.blocks[2]))
        assertTrue(again.blocks.all { it.offset % 16 == 0 }, "every block stays 16-aligned")
        assertEquals(written.size, ByteBuffer.wrap(written).order(ByteOrder.LITTLE_ENDIAN).getInt(0), "the file size is written again")
        assertContentEquals(bytes.copyOf(16), written.copyOf(16).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, bytes.size) }, "the header is kept")
    }

    @Test
    fun containersItCannotReproduceAreRefused() {
        val bytes = resource(listOf("MVTX" to ByteArray(37) { 1 }, "DATA" to ByteArray(9) { 3 }))
        assertFailsWith<Kv3Failure>("tail") { ResourceContainer.read(bytes + ByteArray(16)) }
        assertFailsWith<Kv3Failure>("data after the blocks") { ResourceContainer.read(bytes.copyOf().also { it[it.size - 1] = 9 }) }
        assertFailsWith<Kv3Failure>("a non-padding gap") { ResourceContainer.read(bytes.copyOf().also { it[48 + 37 + 2] = 'S'.code.toByte() }) }
        assertFailsWith<Kv3Failure>("another header version") { ResourceContainer.read(bytes.copyOf().also { it[4] = 11 }) }
        val overlapping = bytes.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16 + 12 + 4, 0) }
        assertFailsWith<Kv3Failure>("overlap") { ResourceContainer.read(overlapping) }
    }

    private val model = VpkFile("models/heroes/x/x.vmdl_c", ByteArray(300) { (it * 7).toByte() })
    private val texture = VpkFile("materials/x/x_color.vtex_c", ByteArray(900) { (it * 3).toByte() })
    private val sound = VpkFile("sounds/step.vsnd_c", ByteArray(50) { 9 })

    @Test
    fun aRepairedEntryIsAppendedAndEveryOtherKeepsItsFieldsAndBytes() {
        val (original) = vpk(listOf(model, texture, sound))
        val pack = VpkPackages.read(ArraySource(original))
        val entry = pack.find(model.path).single()
        val repaired = ByteArray(420) { 4 }
        val out = ByteArrayOutputStream()
        val appended = VpkPackages.rewrite(pack, ArraySource(original), emptyMap(), mapOf(entry to repaired), out)
        assertTrue(appended.isEmpty(), "an embedded entry extends the directory itself")
        val written = out.toByteArray()
        val again = VpkPackages.read(ArraySource(written))
        val source = ArraySource(written)
        assertContentEquals(repaired, VpkPackages.payload(again, again.find(entry.path).single(), source, { null }))
        for (other in listOf(texture, sound)) {
            assertEquals(pack.find(other.path).single(), again.find(other.path).single(), "${other.path} keeps its fields")
            assertContentEquals(other.bytes, VpkPackages.payload(again, again.find(other.path).single(), source, { null }))
        }
        assertContentEquals(original.copyOfRange(pack.dataStart.toInt(), (pack.dataStart + pack.dataBytes).toInt()), written.copyOfRange(again.dataStart.toInt(), (again.dataStart + pack.dataBytes).toInt()), "the original data stays in place")
        val sections = written.copyOfRange(written.size - 48, written.size)
        assertContentEquals(md5(written.copyOfRange(28, dataStart(written))), sections.copyOfRange(0, 16), "tree checksum")
        assertContentEquals(md5(ByteArray(0)), sections.copyOfRange(16, 32), "archive checksum")
        assertContentEquals(md5(written.copyOf(written.size - 16)), sections.copyOfRange(32, 48), "whole-file checksum")
        assertIs<VpkRead.Parsed>(VpkFormat.read(written.copyOf(dataStart(written)), written.size.toLong()), "the installer accepts it")
    }

    @Test
    fun anEntryInAChunkIsAppendedToThatChunkAlone() {
        val (original, chunks) = vpk(listOf(VpkFile(model.path, model.bytes, chunk = 1), VpkFile(texture.path, texture.bytes, chunk = 0), sound))
        val pack = VpkPackages.read(ArraySource(original))
        val entry = pack.find(model.path).single()
        val sizes = chunks.mapValues { it.value.size.toLong() }
        val out = ByteArrayOutputStream()
        val repaired = ByteArray(77) { 6 }
        val appended = VpkPackages.rewrite(pack, ArraySource(original), sizes, mapOf(entry to repaired), out)
        assertEquals(setOf(1), appended.keys)
        val again = VpkPackages.read(ArraySource(out.toByteArray()))
        val moved = again.find(entry.path).single()
        assertEquals(1, moved.archive)
        assertEquals(sizes.getValue(1), moved.offset, "it starts where chunk 1 ended")
        val chunk1 = chunks.getValue(1) + appended.getValue(1).single()
        assertContentEquals(repaired, VpkPackages.payload(again, moved, ArraySource(out.toByteArray()), { if (it == 1) ArraySource(chunk1) else ArraySource(chunks.getValue(it)) }))
        assertEquals(pack.find(texture.path).single(), again.find(texture.path).single(), "chunk 0 is not touched")
    }

    @Test
    fun aPreloadOnlyEntryCanMoveToEmbeddedDataWithoutChangingOtherPreloads() {
        val (original) = vpk(listOf(VpkFile(model.path, model.bytes, preload = model.bytes.size), VpkFile(sound.path, sound.bytes, preload = 11)))
        val pack = VpkPackages.read(ArraySource(original))
        val entry = pack.find(model.path).single()
        assertEquals(0L, entry.length)
        assertContentEquals(model.bytes, entry.preload)
        assertContentEquals(model.bytes, VpkPackages.payload(pack, entry, ArraySource(original), { error("preload-only needs no chunk") }))
        val damaged = entry.copy(preload = entry.preload.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertFailsWith<Kv3Failure> { VpkPackages.payload(pack, damaged, ArraySource(original), { null }) }
        val repaired = ByteArray(333) { 17 }
        val out = ByteArrayOutputStream()
        assertTrue(VpkPackages.rewrite(pack, ArraySource(original), emptyMap(), mapOf(entry to repaired), out).isEmpty())
        val written = out.toByteArray()
        val again = VpkPackages.read(ArraySource(written))
        val moved = again.find(entry.path).single()
        assertTrue(moved.preload.isEmpty())
        assertEquals(pack.dataBytes, moved.offset)
        assertContentEquals(repaired, VpkPackages.payload(again, moved, ArraySource(written), { null }))
        val other = pack.find(sound.path).single()
        assertEquals(other, again.find(other.path).single())
        assertContentEquals(sound.bytes, VpkPackages.payload(again, other, ArraySource(written), { null }))
        assertContentEquals(original.copyOfRange(pack.dataStart.toInt(), (pack.dataStart + pack.dataBytes).toInt()), written.copyOfRange(again.dataStart.toInt(), (again.dataStart + pack.dataBytes).toInt()))
    }

    @Test
    fun preloadAndSplitPayloadAreCrcCheckedTogetherAndReplacedInTheSameChunk() {
        val (original, chunks) = vpk(listOf(VpkFile(model.path, model.bytes, chunk = 1, preload = 37), VpkFile(texture.path, texture.bytes, chunk = 1, preload = 13)))
        val pack = VpkPackages.read(ArraySource(original))
        val entry = pack.find(model.path).single()
        val chunk = chunks.getValue(1)
        assertEquals(37, entry.preload.size)
        assertEquals(263L, entry.length)
        assertContentEquals(model.bytes, VpkPackages.payload(pack, entry, ArraySource(original), { ArraySource(chunk) }))
        assertFailsWith<Kv3Failure> { VpkPackages.payload(pack, entry, ArraySource(original), { null }) }
        val damaged = chunk.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertFailsWith<Kv3Failure> { VpkPackages.payload(pack, entry, ArraySource(original), { ArraySource(damaged) }) }
        val repaired = ByteArray(411) { 23 }
        val out = ByteArrayOutputStream()
        val appended = VpkPackages.rewrite(pack, ArraySource(original), mapOf(1 to chunk.size.toLong()), mapOf(entry to repaired), out)
        assertEquals(setOf(1), appended.keys)
        assertContentEquals(repaired, appended.getValue(1).single())
        val newChunk = chunk + appended.getValue(1).single()
        val again = VpkPackages.read(ArraySource(out.toByteArray()))
        val moved = again.find(entry.path).single()
        assertEquals(1, moved.archive)
        assertEquals(chunk.size.toLong(), moved.offset)
        assertTrue(moved.preload.isEmpty())
        assertContentEquals(repaired, VpkPackages.payload(again, moved, ArraySource(out.toByteArray()), { ArraySource(newChunk) }))
        val other = pack.find(texture.path).single()
        assertEquals(other, again.find(other.path).single())
        assertContentEquals(texture.bytes, VpkPackages.payload(again, other, ArraySource(out.toByteArray()), { ArraySource(newChunk) }))
        assertContentEquals(chunk, newChunk.copyOf(chunk.size))
    }

    @Test
    fun nonemptyArchiveHashRecordsStillDescribeTheOriginalChunkRanges() {
        val records = le(56)
            .putInt(1).putInt(7).putInt(103).put(md5(model.bytes.copyOfRange(7, 110)))
            .putInt(0).putInt(0).putInt(texture.bytes.size).put(md5(texture.bytes)).array()
        val (original, chunks) = vpk(listOf(VpkFile(model.path, model.bytes, chunk = 1), VpkFile(texture.path, texture.bytes, chunk = 0), sound), archiveHashes = records)
        val pack = VpkPackages.read(ArraySource(original))
        assertContentEquals(records, pack.archiveMd5)
        val repaired = ByteArray(127) { 19 }
        val out = ByteArrayOutputStream()
        val appended = VpkPackages.rewrite(pack, ArraySource(original), chunks.mapValues { it.value.size.toLong() }, mapOf(pack.find(model.path).single() to repaired), out)
        val written = out.toByteArray()
        val again = VpkPackages.read(ArraySource(written))
        assertContentEquals(records, again.archiveMd5)
        val newChunks = chunks.mapValues { (index, bytes) -> bytes + appended[index].orEmpty().fold(ByteArray(0)) { a, b -> a + b } }
        val hashes = ByteBuffer.wrap(again.archiveMd5).order(ByteOrder.LITTLE_ENDIAN)
        repeat(2) {
            val archive = hashes.int
            val offset = hashes.int
            val length = hashes.int
            val checksum = ByteArray(16).also { hashes.get(it) }
            assertContentEquals(md5(newChunks.getValue(archive).copyOfRange(offset, offset + length)), checksum)
        }
        assertContentEquals(md5(written.copyOfRange(28, dataStart(written))), again.otherMd5.copyOfRange(0, 16))
        assertContentEquals(md5(records), again.otherMd5.copyOfRange(16, 32))
        assertContentEquals(md5(written.copyOf(written.size - 16)), again.otherMd5.copyOfRange(32, 48))
        assertContentEquals(repaired, VpkPackages.payload(again, again.find(model.path).single(), ArraySource(written), { ArraySource(newChunks.getValue(it)) }))
        assertIs<VpkRead.Parsed>(VpkFormat.read(written.copyOf(dataStart(written)), written.size.toLong()))
    }

    @Test
    fun signedPackagesAreRefusedAndAnEmptySignatureIsKept() {
        val empty = le(20).putInt(0x55AA1234).putInt(0).putInt(0).putInt(0).putInt(0).array()
        val (unsigned) = vpk(listOf(model, sound), signature = empty)
        val pack = VpkPackages.read(ArraySource(unsigned))
        val out = ByteArrayOutputStream()
        VpkPackages.rewrite(pack, ArraySource(unsigned), emptyMap(), mapOf(pack.entries.first() to ByteArray(5)), out)
        assertContentEquals(empty, out.toByteArray().copyOfRange(out.size() - 20, out.size()))
        val key = ByteArray(16) { 1 }
        // Valve's form declares 20 bytes and the key and signature it names follow them.
        val (signed) = vpk(listOf(model, sound), signature = le(20).putInt(0x55AA1234).putInt(1).putInt(key.size).putInt(4).putInt(0).array() + key + ByteArray(4), declaredSignature = 20)
        val signedPack = VpkPackages.read(ArraySource(signed))
        assertFailsWith<Kv3Failure> { VpkPackages.rewrite(signedPack, ArraySource(signed), emptyMap(), mapOf(signedPack.entries.first() to ByteArray(5)), ByteArrayOutputStream()) }
    }

    @Test
    fun aDamagedEntryIsRefusedAndOnlyTheSelectedCopyIsRewritten() {
        val (original) = vpk(listOf(model, sound), md5 = false)
        val pack = VpkPackages.read(ArraySource(original))
        val damaged = original.copyOf().also { it[(pack.dataStart + 3).toInt()] = (it[(pack.dataStart + 3).toInt()] + 1).toByte() }
        assertFailsWith<Kv3Failure> { VpkPackages.payload(pack, pack.entries.first(), ArraySource(damaged), { null }) }
        val (twice) = vpk(listOf(model, VpkFile(model.path, ByteArray(3))), md5 = false)
        val twicePack = VpkPackages.read(ArraySource(twice))
        val out = ByteArrayOutputStream()
        val replacement = byteArrayOf(1, 2, 3, 4)
        VpkPackages.rewrite(twicePack, ArraySource(twice), emptyMap(), mapOf(twicePack.entries.first() to replacement), out)
        val source = ArraySource(out.toByteArray())
        val written = VpkPackages.read(source)
        val copies = written.find(model.path)
        assertEquals(2, copies.size)
        assertContentEquals(replacement, VpkPackages.payload(written, copies[0], source, { null }))
        assertContentEquals(ByteArray(3), VpkPackages.payload(written, copies[1], source, { null }))
    }

    @Test
    fun versionOnePackagesAreRewrittenToo() {
        val (v2) = vpk(listOf(model, sound), md5 = false)
        val treeSize = ByteBuffer.wrap(v2).order(ByteOrder.LITTLE_ENDIAN).getInt(8)
        val v1 = le(12).putInt(0x55AA1234).putInt(1).putInt(treeSize).array() + v2.copyOfRange(28, v2.size)
        val pack = VpkPackages.read(ArraySource(v1))
        val out = ByteArrayOutputStream()
        VpkPackages.rewrite(pack, ArraySource(v1), emptyMap(), mapOf(pack.entries.first() to ByteArray(11) { 2 }), out)
        val again = VpkPackages.read(ArraySource(out.toByteArray()))
        assertEquals(1, again.version)
        assertContentEquals(ByteArray(11) { 2 }, VpkPackages.payload(again, again.entries.first(), ArraySource(out.toByteArray()), { null }))
        assertContentEquals(sound.bytes, VpkPackages.payload(again, again.entries.last(), ArraySource(out.toByteArray()), { null }))
    }
}
