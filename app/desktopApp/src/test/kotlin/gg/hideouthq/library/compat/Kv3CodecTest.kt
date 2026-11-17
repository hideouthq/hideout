package gg.hideouthq.library.compat

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class Kv3CodecTest {
    private class Sink {
        val out = ByteArrayOutputStream()
        val size: Int get() = out.size()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun i32(v: Int) = repeat(4) { u8(v ushr (8 * it)) }
        fun i64(v: Long) = repeat(8) { u8((v ushr (8 * it)).toInt()) }
        fun f32(v: Float) = i32(java.lang.Float.floatToRawIntBits(v))
        fun f64(v: Double) = i64(java.lang.Double.doubleToRawLongBits(v))
        fun bytes(b: ByteArray) = out.write(b)
        fun cstr(s: String) { bytes(s.encodeToByteArray()); u8(0) }
        fun pad(to: Int) { while (size % to != 0) u8(0) }
        fun toBytes(): ByteArray = out.toByteArray()
    }

    private val guid = ByteArray(16) { it.toByte() }

    /**
     * Version 5, uncompressed: root { "count": INT32 7, "ratio": DOUBLE 1.5, "name": STRING "hero", "on": TRUE,
     * "none": DOUBLE_ZERO, "one": UINT32_ONE, "v": ARRAY_TYPE_AUXILIARY_BUFFER of 3 FLOAT, "tags": ARRAY_TYPE_BYTE_LENGTH of 2 STRING (one flagged resource) }.
     */
    private fun handBuiltV5(): ByteArray {
        val strings = listOf("count", "ratio", "name", "hero", "on", "none", "one", "v", "tags", "a", "b")
        // Buffer 1: strings, then aux 1-byte values (none), aux 4-byte lane: string count then the three floats.
        val b1 = Sink()
        strings.forEach { b1.cstr(it) }
        val stringBytes = b1.size
        b1.pad(4)
        b1.i32(strings.size)
        b1.f32(1f); b1.f32(-0f); b1.f32(2.5f)
        val main1 = Sink()
        val main4 = Sink()
        val main8 = Sink()
        val types = Sink()
        fun name(s: String) = main4.i32(strings.indexOf(s))
        types.u8(9) // root object
        name("count"); types.u8(11); main4.i32(7)
        name("ratio"); types.u8(5); main8.f64(1.5)
        name("name"); types.u8(6); main4.i32(strings.indexOf("hero"))
        name("on"); types.u8(13)
        name("none"); types.u8(17)
        name("one"); types.u8(27)
        name("v"); types.u8(25); main1.u8(3); types.u8(19)
        name("tags"); types.u8(24); main1.u8(2); types.u8(6 or 0x80); types.u8(1); main4.i32(strings.indexOf("a")); main4.i32(strings.indexOf("b"))
        val b2 = Sink()
        b2.i32(8) // one object of eight members
        b2.bytes(main1.toBytes())
        b2.pad(4)
        b2.bytes(main4.toBytes())
        b2.pad(8)
        b2.bytes(main8.toBytes())
        b2.bytes(types.toBytes())
        b2.i32(0xFFEEDD00.toInt())
        val buffer1 = b1.toBytes()
        val buffer2 = b2.toBytes()
        val header = Sink()
        header.i32(0x4B563305)
        header.bytes(guid)
        header.i32(0); header.u16(0); header.u16(0)
        header.i32(stringBytes) // countBytes1 in buffer 1
        header.i32(1 + 3) // countBytes4 in buffer 1: the string count and three floats
        header.i32(0) // countBytes8 in buffer 1
        header.i32(types.size)
        header.u16(1); header.u16(2) // object and array hints
        header.i32(buffer1.size + buffer2.size)
        header.i32(buffer1.size + buffer2.size)
        header.i32(0); header.i32(0) // no blobs
        header.i32(0); header.i32(0) // countBytes2 in buffer 1, frame sizes
        header.i32(buffer1.size); header.i32(0); header.i32(buffer2.size); header.i32(0)
        header.i32(main1.size); header.i32(0); header.i32(main4.size / 4); header.i32(main8.size / 8)
        header.i32(14); header.i32(1); header.i32(2); header.i32(5) // node, object, array and element hints
        header.bytes(buffer1)
        header.bytes(buffer2)
        return header.toBytes()
    }

    @Test
    fun aHandBuiltVersion5BlockDecodesWithEveryTypeKept() {
        val document = Kv3Binary.decode(handBuiltV5())
        assertEquals(5, document.version)
        val root = assertIs<Kv3.Obj>(document.root)
        assertEquals(Kv3.Number(Kv3Type.Int32, 7), root["count"])
        assertEquals(1.5, (root["ratio"] as Kv3.Number).real)
        assertEquals(Kv3.Str("hero"), root["name"])
        assertEquals(Kv3.Bool(Kv3Type.True, true), root["on"])
        assertEquals(Kv3Type.DoubleZero, root["none"]!!.type)
        assertEquals(Kv3.Number(Kv3Type.UInt32One, 1), root["one"])
        val v = assertIs<Kv3.Typed>(root["v"])
        assertEquals(Kv3Type.ArrayAuxiliary, v.type)
        assertEquals(Kv3Type.Float, v.elementType)
        assertEquals(listOf(1.0, -0.0, 2.5), v.items.map { (it as Kv3.Number).real })
        assertEquals(0x80000000L, (v.items[1] as Kv3.Number).bits, "negative zero keeps its sign bit")
        val tags = assertIs<Kv3.Typed>(root["tags"])
        assertEquals(1, tags.elementFlag)
        assertEquals(listOf("a", "b"), tags.items.map { (it as Kv3.Str).value })
    }

    @Test
    fun anUncompressedBlockIsWrittenBackByteForByte() {
        val bytes = handBuiltV5()
        assertContentEquals(bytes, Kv3Binary.encode(Kv3Binary.decode(bytes)))
    }

    @Test
    fun aChangedFloatStaysAFloatAndEverythingElseIsUntouched() {
        val document = Kv3Binary.decode(handBuiltV5())
        val root = document.root as Kv3.Obj
        val changed = root.replacing("v") { v -> (v as Kv3.Typed).copy(items = v.items.mapIndexed { i, item -> if (i == 0) retyped(item as Kv3.Number, 0.25) else item }) }
        val again = Kv3Binary.decode(Kv3Binary.encode(document.withRoot(changed)))
        val v = (again.root as Kv3.Obj)["v"] as Kv3.Typed
        assertEquals(Kv3.Number(Kv3Type.Float, java.lang.Float.floatToRawIntBits(0.25f).toLong()), v.items[0])
        assertEquals(root.members.filter { it.first != "v" }, (again.root as Kv3.Obj).members.filter { it.first != "v" })
    }

    @Test
    fun realsTakeValvesZeroAndOneFormsOutsideTypedArrays() {
        val double = Kv3.Number(Kv3Type.Double, java.lang.Double.doubleToRawLongBits(3.0))
        assertEquals(Kv3Type.DoubleZero, retyped(double, 0.0).type)
        assertEquals(Kv3Type.DoubleOne, retyped(double, 1.0).type)
        assertEquals(Kv3Type.Double, retyped(double, -0.0).type, "negative zero is not Valve's zero form")
        assertEquals(Kv3Type.Double, retyped(Kv3.Number(Kv3Type.DoubleZero, 0), 94.0).type)
    }

    @Test
    fun lz4BlocksDecodeLiteralsMatchesAndOverlappingCopies() {
        // "abcd" as literals, then a match 4 back of length 8 that overlaps its own output, then "!" as the final literal.
        val block = byteArrayOf(0x44, 'a'.code.toByte(), 'b'.code.toByte(), 'c'.code.toByte(), 'd'.code.toByte(), 4, 0, 0x10, '!'.code.toByte())
        val out = ByteArray(13)
        assertEquals(13, Lz4.decode(block, 0, block.size, out, 0, out.size))
        assertEquals("abcdabcdabcd!", out.decodeToString())
    }

    @Test
    fun lz4RefusesOffsetsBeforeItsOutputAndOutputBeyondItsLimit() {
        assertFailsWith<Kv3Failure> { Lz4.decode(byteArrayOf(0x10, 'a'.code.toByte(), 9, 0, 0x00), 0, 5, ByteArray(16), 0, 16) }
        assertFailsWith<Kv3Failure> { Lz4.decode(byteArrayOf(0x40, 1, 2, 3, 4), 0, 5, ByteArray(2), 0, 2) }
    }

    @Test
    fun malformedOrUnknownBlocksAreRefusedNeverGuessed() {
        val good = handBuiltV5()
        // The twelve node types end just before the trailer; the fourth is the "name" member's STRING.
        val types = good.size - 4 - 12 + 3
        assertFailsWith<Kv3Failure>("cut short") { Kv3Binary.decode(good.copyOf(good.size - 5)) }
        assertFailsWith<Kv3Failure>("trailing bytes") { Kv3Binary.decode(good + byteArrayOf(0)) }
        assertFailsWith<Kv3Failure>("no trailer") { Kv3Binary.decode(good.copyOf().also { it[it.size - 1] = 0 }) }
        assertFailsWith<Kv3Failure>("unknown node type") { Kv3Binary.decode(good.copyOf().also { it[types] = 40 }) }
        assertFailsWith<Kv3Failure>("extended type bit") { Kv3Binary.decode(good.copyOf().also { it[types] = (9 or 0x40).toByte() }) }
        assertFailsWith<Kv3Failure>("legacy") { Kv3Binary.decode(byteArrayOf(0x56, 0x4B, 0x56, 0x03) + ByteArray(40)) }
        assertFailsWith<Kv3Failure>("version 1") { Kv3Binary.decode(good.copyOf().also { it[0] = 1 }) }
        assertFailsWith<Kv3Failure>("unknown compression") { Kv3Binary.decode(good.copyOf().also { it[20] = 7 }) }
    }

    @Test
    fun aStringIdOutsideTheTableIsMalformed() {
        val good = handBuiltV5()
        // Buffer 2 ends the block, its size in the header at 80; its first name id follows the object lengths and the 1-byte lane, padded to 4.
        val at = good.size - ByteBuffer.wrap(good).order(ByteOrder.LITTLE_ENDIAN).getInt(80) + 4 + 4
        assertFailsWith<Kv3Failure> { Kv3Binary.decode(good.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(at, 999) }) }
    }

    @Test
    fun aHostileBlobCountIsRejectedBeforeAllocation() {
        val hostile = handBuiltV5().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(56, Int.MAX_VALUE) }
        assertFailsWith<Kv3Failure> { Kv3Binary.decode(hostile) }
    }

    @Test
    fun blobsSurviveEveryCompressionAndVersion() {
        val v5 = Kv3Binary.decode(handBuiltV5())
        val blob = Kv3.Blob(ByteArray(40_000) { (it % 251).toByte() })
        val root = Kv3.Obj((v5.root as Kv3.Obj).members + ("data" to blob))
        for (compression in 0..2) for (version in 4..5) {
            val bytes = Kv3Binary.encode(Kv3Document(version, guid, compression, v5.strings + "data", root, v5.hints))
            assertEquals(compression, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(20), "v$version c$compression keeps its compression method")
            val again = Kv3Binary.decode(bytes)
            assertEquals(version, again.version)
            assertEquals(root, again.root, "v$version c$compression")
        }
    }
}
