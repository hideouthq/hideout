package gg.hideouthq.library.compat

import com.github.luben.zstd.Zstd

internal class Kv3Failure(override val message: String) : Exception(message)

internal object Kv3Type {
    const val Null = 1
    const val Boolean = 2
    const val Int64 = 3
    const val UInt64 = 4
    const val Double = 5
    const val String = 6
    const val Blob = 7
    const val Array = 8
    const val Object = 9
    const val ArrayTyped = 10
    const val Int32 = 11
    const val UInt32 = 12
    const val True = 13
    const val False = 14
    const val Int64Zero = 15
    const val Int64One = 16
    const val DoubleZero = 17
    const val DoubleOne = 18
    const val Float = 19
    const val Int16 = 20
    const val UInt16 = 21
    const val Int8 = 22
    const val UInt8 = 23
    const val ArrayByteLength = 24
    const val ArrayAuxiliary = 25
    const val UInt32Zero = 26
    const val UInt32One = 27

    val SinceVersion4: Set<Int> = setOf(Float, Int16, UInt16, Int8, UInt8, ArrayByteLength, ArrayAuxiliary, UInt32Zero, UInt32One)

    val Real: Set<Int> = setOf(Double, DoubleZero, DoubleOne, Float)
}

internal sealed interface Kv3 {
    val type: Int
    val flag: Int

    data class Null(override val flag: Int = 0) : Kv3 {
        override val type: Int get() = Kv3Type.Null
    }

    data class Bool(override val type: Int, val value: Boolean, override val flag: Int = 0) : Kv3

    /** Raw bits keep negative zero and every NaN exactly. */
    data class Number(override val type: Int, val bits: Long, override val flag: Int = 0) : Kv3 {
        val isReal: Boolean get() = type in Kv3Type.Real

        val real: kotlin.Double
            get() = when (type) {
                Kv3Type.Double -> java.lang.Double.longBitsToDouble(bits)
                Kv3Type.DoubleZero -> 0.0
                Kv3Type.DoubleOne -> 1.0
                Kv3Type.Float -> java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
                else -> error("not a real")
            }
    }

    data class Str(val value: String, override val flag: Int = 0) : Kv3 {
        override val type: Int get() = Kv3Type.String
    }

    class Blob(val bytes: ByteArray, override val flag: Int = 0) : Kv3 {
        override val type: Int get() = Kv3Type.Blob
        override fun equals(other: Any?): Boolean = other is Blob && other.flag == flag && other.bytes.contentEquals(bytes)
        override fun hashCode(): Int = bytes.contentHashCode() * 31 + flag
        override fun toString(): String = "Blob(${bytes.size} bytes)"
    }

    data class List(val items: kotlin.collections.List<Kv3>, override val flag: Int = 0) : Kv3 {
        override val type: Int get() = Kv3Type.Array
    }

    data class Typed(override val type: Int, val elementType: Int, val elementFlag: Int, val items: kotlin.collections.List<Kv3>, override val flag: Int = 0) : Kv3

    data class Obj(val members: kotlin.collections.List<Pair<String, Kv3>>, override val flag: Int = 0) : Kv3 {
        override val type: Int get() = Kv3Type.Object

        operator fun get(name: String): Kv3? {
            val found = members.filter { it.first == name }
            if (found.size > 1) throw Kv3Failure("the member $name is repeated")
            return found.singleOrNull()?.second
        }
    }
}

internal data class Kv3Hints(val objects16: Int, val arrays16: Int, val nodes: Int, val arrays: Int, val arrayElements: Int)

internal class Kv3Document(
    val version: Int,
    val format: ByteArray,
    val compression: Int,
    val strings: kotlin.collections.List<String>,
    val root: Kv3,
    val hints: Kv3Hints,
) {
    fun withRoot(next: Kv3): Kv3Document = Kv3Document(version, format, compression, strings, next, hints)
}

internal object Kv3Binary {
    private const val Magic = 0x4B563300
    private const val Trailer = 0xFFEEDD00.toInt()
    private const val Lz4FrameSize = 16384
    private const val Uncompressed = 0
    private const val Lz4Method = 1
    private const val ZstdMethod = 2

    private const val MaxBufferBytes = 128 * 1024 * 1024
    private const val MaxNodes = 4_000_000
    private const val MaxDepth = 128

    fun isKv3(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val magic = Le.i32(bytes, 0)
        return (magic and 0xFFFFFF00.toInt()) == Magic && (magic and 0xFF) in 1..5
    }

    fun decode(block: ByteArray): Kv3Document {
        val r = Le.Reader(block)
        val magic = r.i32()
        if ((magic and 0xFFFFFF00.toInt()) != Magic) throw Kv3Failure("not a binary KV3 block")
        val version = magic and 0xFF
        if (version !in 2..5) throw Kv3Failure("KV3 version $version is not supported")
        val format = r.bytes(16)
        val compression = r.i32()
        if (compression !in Uncompressed..ZstdMethod) throw Kv3Failure("unknown compression method $compression")
        val dictionary = r.u16()
        val frameSize = r.u16()
        if (dictionary != 0) throw Kv3Failure("compression dictionaries are not supported")
        if (compression == Lz4Method && frameSize != Lz4FrameSize) throw Kv3Failure("LZ4 frame size $frameSize is not supported")
        if (compression != Lz4Method && frameSize != 0) throw Kv3Failure("a frame size without LZ4")
        val countBytes1 = r.count()
        val countBytes4 = r.count()
        val countBytes8 = r.count()
        val countTypes = r.count()
        val objects16 = r.u16()
        val arrays16 = r.u16()
        val sizeUncompressed = r.count()
        val sizeCompressed = r.count()
        val countBlobs = r.count()
        val sizeBlobs = r.count()
        val countBytes2 = if (version >= 4) r.count() else 0
        val sizeFrameSizes = if (version >= 4) r.count() else 0
        var sizeBuffer1 = sizeUncompressed
        var compressedBuffer1 = sizeCompressed
        var sizeBuffer2 = 0
        var compressedBuffer2 = 0
        var lanes2 = IntArray(4)
        var nodes = 0
        var objects2 = 0
        var arrays2 = 0
        var arrayElements = 0
        if (version >= 5) {
            sizeBuffer1 = r.count()
            compressedBuffer1 = r.count()
            sizeBuffer2 = r.count()
            compressedBuffer2 = r.count()
            lanes2 = IntArray(4) { r.count() }
            nodes = r.count()
            objects2 = r.count()
            arrays2 = r.count()
            arrayElements = r.count()
            if (sizeUncompressed.toLong() != sizeBuffer1.toLong() + sizeBuffer2) throw Kv3Failure("the buffer sizes do not add up")
        }
        if (sizeBuffer1 > MaxBufferBytes || sizeBuffer2 > MaxBufferBytes || sizeBlobs > MaxBufferBytes) throw Kv3Failure("the block is larger than $MaxBufferBytes bytes")
        var blobsTogether: ByteArray? = null
        val buffer1 = when (compression) {
            Uncompressed -> {
                if (version >= 5 && compressedBuffer1 != 0) throw Kv3Failure("an uncompressed buffer declares a compressed size")
                if (version < 5 && sizeCompressed != sizeUncompressed) throw Kv3Failure("an uncompressed buffer declares another size")
                r.bytes(sizeBuffer1)
            }
            Lz4Method -> lz4(r.bytes(compressedBuffer1), sizeBuffer1)
            else -> if (version < 5) {
                // Before version 5 the buffer and the blobs are two zstd frames under one compressed size.
                val both = zstd(r.bytes(sizeCompressed), sizeBuffer1 + sizeBlobs)
                blobsTogether = both.copyOfRange(sizeBuffer1, both.size)
                both.copyOf(sizeBuffer1)
            } else {
                zstd(r.bytes(compressedBuffer1), sizeBuffer1)
            }
        }

        val context = Context(version)
        val strings: kotlin.collections.List<String>
        val tail: Le.Reader
        if (version >= 5) {
            val buffer2 = when (compression) {
                Uncompressed -> {
                    if (compressedBuffer2 != 0) throw Kv3Failure("an uncompressed buffer declares a compressed size")
                    r.bytes(sizeBuffer2)
                }
                Lz4Method -> lz4(r.bytes(compressedBuffer2), sizeBuffer2)
                else -> zstd(r.bytes(compressedBuffer2), sizeBuffer2)
            }
            val b1 = Le.Reader(buffer1)
            val bytes1 = b1.slice(countBytes1.toLong())
            val bytes2 = b1.aligned(2, countBytes2 * 2L)
            val bytes4 = b1.aligned(4, countBytes4 * 4L)
            val bytes8 = b1.aligned(8, countBytes8 * 8L)
            if (b1.position != buffer1.size) throw Kv3Failure("the first buffer has bytes after its lanes")
            if (countBytes4 < 1) throw Kv3Failure("the string count is missing")
            val count = bytes4.count()
            strings = (0 until count).map { bytes1.cString() }
            context.aux = Lanes(bytes1, bytes2, bytes4, bytes8)
            val b2 = Le.Reader(buffer2)
            context.objectLengths = b2.slice(objects2 * 4L)
            val m1 = b2.slice(lanes2[0].toLong())
            val m2 = b2.aligned(2, lanes2[1] * 2L)
            val m4 = b2.aligned(4, lanes2[2] * 4L)
            val m8 = b2.aligned(8, lanes2[3] * 8L)
            context.main = Lanes(m1, m2, m4, m8)
            context.types = b2.slice(countTypes.toLong())
            tail = b2
        } else {
            val b = Le.Reader(buffer1)
            val m1 = b.slice(countBytes1.toLong())
            val m2 = b.aligned(2, countBytes2 * 2L)
            val m4 = b.aligned(4, countBytes4 * 4L)
            val m8 = b.aligned(8, countBytes8 * 8L, padWhenEmpty = true)
            if (countBytes4 < 1) throw Kv3Failure("the string count is missing")
            val count = m4.count()
            val stringsStart = b.position
            strings = (0 until count).map { b.cString() }
            val typesLength = countTypes - (b.position - stringsStart)
            if (typesLength < 0) throw Kv3Failure("the type list is shorter than its strings")
            context.types = b.slice(typesLength.toLong())
            context.main = Lanes(m1, m2, m4, m8)
            context.aux = context.main
            tail = b
        }
        context.strings = strings
        if (countBlobs == 0) {
            if (tail.i32() != Trailer) throw Kv3Failure("the buffer has no trailer")
            if (version >= 5 && tail.remaining != sizeFrameSizes) throw Kv3Failure("the buffer has bytes after its trailer")
            if (version < 5 && tail.remaining != 0) throw Kv3Failure("the buffer has bytes after its trailer")
            if (sizeBlobs != 0) throw Kv3Failure("blob bytes without blobs")
            context.blobs = Le.Reader(ByteArray(0))
        } else {
            if (countBlobs > MaxNodes) throw Kv3Failure("the blob count exceeds the node budget")
            if (countBlobs.toLong() * 4 + 4 > tail.remaining.toLong()) throw Kv3Failure("the blob lengths and trailer do not fit in the buffer")
            val lengths = IntArray(countBlobs) { tail.count() }
            if (lengths.sumOf { it.toLong() } != sizeBlobs.toLong()) throw Kv3Failure("the blob lengths do not add up")
            if (tail.i32() != Trailer) throw Kv3Failure("the blob lengths have no trailer")
            context.blobLengths = Le.Reader(Le.ints(lengths))
            val blobs = when (compression) {
                Uncompressed -> {
                    if (tail.remaining != 0) throw Kv3Failure("uncompressed blobs declare frame sizes")
                    r.bytes(sizeBlobs)
                }
                Lz4Method -> {
                    val output = ByteArray(sizeBlobs)
                    var at = 0
                    for (length in lengths) {
                        val end = at + length
                        while (at < end) {
                            val frame = tail.u16()
                            val input = r.bytes(frame)
                            val written = Lz4.decode(input, 0, input.size, output, at, end)
                            if (written < 1) throw Kv3Failure("an empty LZ4 blob frame")
                            at += written
                        }
                    }
                    if (tail.remaining != 0) throw Kv3Failure("unused LZ4 blob frame sizes")
                    output
                }
                else -> if (version >= 5) {
                    if (sizeFrameSizes != 0) throw Kv3Failure("zstd blobs declare frame sizes")
                    zstd(r.bytes(sizeCompressed - compressedBuffer1 - compressedBuffer2), sizeBlobs)
                } else {
                    blobsTogether ?: throw Kv3Failure("the zstd blob frame is missing")
                }
            }
            if (r.i32() != Trailer) throw Kv3Failure("the blobs have no trailer")
            context.blobs = Le.Reader(blobs)
        }
        if (r.remaining != 0) throw Kv3Failure("the block has bytes after its KV3 data")
        val (rootType, rootFlag) = context.type()
        val root = context.value(rootType, rootFlag, context.main, depth = 0)
        if (context.types.remaining != 0) throw Kv3Failure("unused node types")
        if (context.objectLengths.remaining != 0) throw Kv3Failure("unused object lengths")
        if (context.blobLengths.remaining != 0 || context.blobs.remaining != 0) throw Kv3Failure("unused blobs")
        for (lane in listOf(context.main, context.aux)) {
            if (lane.bytes1.remaining != 0 || lane.bytes2.remaining != 0 || lane.bytes4.remaining != 0 || lane.bytes8.remaining != 0) throw Kv3Failure("unused values")
        }
        return Kv3Document(version, format, compression, strings, root, Kv3Hints(objects16, arrays16, nodes, arrays2, arrayElements))
    }

    private class Lanes(val bytes1: Le.Reader, val bytes2: Le.Reader, val bytes4: Le.Reader, val bytes8: Le.Reader)

    private class Context(val version: Int) {
        lateinit var main: Lanes
        lateinit var aux: Lanes
        lateinit var types: Le.Reader
        var objectLengths: Le.Reader = Le.Reader(ByteArray(0))
        var blobLengths: Le.Reader = Le.Reader(ByteArray(0))
        lateinit var blobs: Le.Reader
        lateinit var strings: kotlin.collections.List<String>
        var nodes = 0

        fun type(): Pair<Int, Int> {
            val raw = types.u8()
            if (version < 3) {
                if (raw and 0x80 != 0) throw Kv3Failure("version 2 flags are not supported")
                return raw to 0
            }
            if (raw and 0x40 != 0) throw Kv3Failure("the extended type bit is not supported")
            val flag = if (raw and 0x80 != 0) types.u8().also { if (it == 0 || it > MaxFlag) throw Kv3Failure("KV3 flag $it is not supported") } else 0
            val type = raw and 0x3F
            if (type < Kv3Type.Null || type > Kv3Type.UInt32One) throw Kv3Failure("KV3 node type $type is not supported")
            if (version < 4 && type in Kv3Type.SinceVersion4) throw Kv3Failure("node type $type before version 4")
            return type to flag
        }

        fun string(id: Int): String = when {
            id == -1 -> ""
            id in strings.indices -> strings[id]
            else -> throw Kv3Failure("string $id is not in the table")
        }

        fun value(type: Int, flag: Int, lane: Lanes, depth: Int): Kv3 {
            if (++nodes > MaxNodes) throw Kv3Failure("more than $MaxNodes nodes")
            if (depth > MaxDepth) throw Kv3Failure("nested deeper than $MaxDepth")
            val main = this.main
            return when (type) {
                Kv3Type.Null -> Kv3.Null(flag)
                Kv3Type.True -> Kv3.Bool(type, true, flag)
                Kv3Type.False -> Kv3.Bool(type, false, flag)
                Kv3Type.Boolean -> Kv3.Bool(type, when (lane.bytes1.u8()) { 0 -> false; 1 -> true; else -> throw Kv3Failure("a boolean that is neither 0 nor 1") }, flag)
                Kv3Type.Int64Zero, Kv3Type.UInt32Zero -> Kv3.Number(type, 0, flag)
                Kv3Type.Int64One, Kv3Type.UInt32One -> Kv3.Number(type, 1, flag)
                Kv3Type.DoubleZero -> Kv3.Number(type, 0, flag)
                Kv3Type.DoubleOne -> Kv3.Number(type, java.lang.Double.doubleToRawLongBits(1.0), flag)
                Kv3Type.Int8 -> Kv3.Number(type, lane.bytes1.u8().toByte().toLong(), flag)
                Kv3Type.UInt8 -> Kv3.Number(type, lane.bytes1.u8().toLong(), flag)
                Kv3Type.Int16 -> Kv3.Number(type, lane.bytes2.u16().toShort().toLong(), flag)
                Kv3Type.UInt16 -> Kv3.Number(type, lane.bytes2.u16().toLong(), flag)
                Kv3Type.Int32 -> Kv3.Number(type, lane.bytes4.i32().toLong(), flag)
                Kv3Type.UInt32 -> Kv3.Number(type, lane.bytes4.i32().toLong() and 0xFFFFFFFFL, flag)
                Kv3Type.Float -> Kv3.Number(type, lane.bytes4.i32().toLong() and 0xFFFFFFFFL, flag)
                Kv3Type.Int64, Kv3Type.UInt64, Kv3Type.Double -> Kv3.Number(type, lane.bytes8.i64(), flag)
                Kv3Type.String -> Kv3.Str(string(main.bytes4.i32()), flag)
                Kv3Type.Blob -> {
                    val length = blobLengths.count()
                    Kv3.Blob(blobs.bytes(length), flag)
                }
                Kv3Type.Array -> {
                    val count = main.bytes4.count()
                    val items = ArrayList<Kv3>(minOf(count, 4096))
                    repeat(count) {
                        val (elementType, elementFlag) = type()
                        items += value(elementType, elementFlag, main, depth + 1)
                    }
                    Kv3.List(items, flag)
                }
                Kv3Type.ArrayTyped, Kv3Type.ArrayByteLength, Kv3Type.ArrayAuxiliary -> {
                    val count = if (type == Kv3Type.ArrayTyped) main.bytes4.count() else main.bytes1.u8()
                    if (count == 0) throw Kv3Failure("a typed array is empty")
                    val (elementType, elementFlag) = type()
                    val elements = if (type == Kv3Type.ArrayAuxiliary) aux else main
                    val items = ArrayList<Kv3>(minOf(count, 4096))
                    repeat(count) { items += value(elementType, elementFlag, elements, depth + 1) }
                    Kv3.Typed(type, elementType, elementFlag, items, flag)
                }
                Kv3Type.Object -> {
                    val count = if (version >= 5) objectLengths.count() else main.bytes4.count()
                    val members = ArrayList<Pair<String, Kv3>>(minOf(count, 4096))
                    repeat(count) {
                        val (memberType, memberFlag) = type()
                        val name = string(main.bytes4.i32())
                        members += name to value(memberType, memberFlag, main, depth + 1)
                    }
                    Kv3.Obj(members, flag)
                }
                else -> throw Kv3Failure("KV3 node type $type is not supported")
            }
        }
    }

    fun encode(document: Kv3Document): ByteArray {
        val version = document.version
        if (version !in 2..5) throw Kv3Failure("KV3 version $version cannot be written")
        if (document.format.size != 16) throw Kv3Failure("the format GUID is not 16 bytes")
        val compression = document.compression
        val writer = Writer(document)
        writer.node(document.root)
        val blobs = writer.blobs.toByteArray()
        val blobLengths = writer.blobLengths
        val frames = if (compression == Lz4Method) lz4Frames(blobs, blobLengths) else emptyList()
        val frameSizes = Le.Writer().also { w -> frames.forEach { w.u16(it.size) } }.toByteArray()
        val storedBlobs = when (compression) {
            Uncompressed -> blobs
            Lz4Method -> Le.Writer().also { w -> frames.forEach { w.bytes(it) } }.toByteArray()
            else -> if (blobLengths.isEmpty()) ByteArray(0) else Zstd.compress(blobs, ZstdLevel)
        }
        val hints = document.hints
        val out = Le.Writer()
        out.i32(Magic or version)
        out.bytes(document.format)
        out.i32(compression)
        out.u16(0)
        out.u16(if (compression == Lz4Method) Lz4FrameSize else 0)
        if (version >= 5) {
            val buffer1 = Le.Writer()
            writer.table.forEach { buffer1.cString(it) }
            buffer1.bytes(writer.aux1.toByteArray())
            val count1 = buffer1.size
            buffer1.lane(writer.aux2, 2)
            buffer1.align(4)
            buffer1.i32(writer.table.size)
            buffer1.bytes(writer.aux4.toByteArray())
            buffer1.lane(writer.aux8, 8)
            val buffer2 = Le.Writer()
            buffer2.bytes(writer.objectLengths.toByteArray())
            buffer2.bytes(writer.main1.toByteArray())
            buffer2.lane(writer.main2, 2)
            buffer2.lane(writer.main4, 4)
            buffer2.lane(writer.main8, 8)
            buffer2.bytes(writer.types.toByteArray())
            blobLengths.forEach { buffer2.i32(it) }
            buffer2.i32(Trailer)
            val b1 = buffer1.toByteArray()
            val b2 = buffer2.toByteArray()
            val c1 = compress(compression, b1)
            val c2 = compress(compression, b2 + frameSizes)
            val compressed = compression != Uncompressed
            out.i32(count1)
            out.i32(1 + writer.aux4.size / 4)
            out.i32(writer.aux8.size / 8)
            out.i32(writer.types.size)
            out.u16(hints.objects16)
            out.u16(hints.arrays16)
            out.i32(b1.size + b2.size + frameSizes.size)
            out.i32(c1.size + c2.size + if (compression == ZstdMethod) storedBlobs.size else 0)
            out.i32(blobLengths.size)
            out.i32(blobs.size)
            out.i32(writer.aux2.size / 2)
            out.i32(frameSizes.size)
            out.i32(b1.size)
            out.i32(if (compressed) c1.size else 0)
            out.i32(b2.size + frameSizes.size)
            out.i32(if (compressed) c2.size else 0)
            out.i32(writer.main1.size)
            out.i32(writer.main2.size / 2)
            out.i32(writer.main4.size / 4)
            out.i32(writer.main8.size / 8)
            out.i32(hints.nodes)
            out.i32(writer.objectCount)
            out.i32(hints.arrays)
            out.i32(hints.arrayElements)
            out.bytes(c1)
            out.bytes(c2)
            if (blobLengths.isNotEmpty()) {
                out.bytes(storedBlobs)
                out.i32(Trailer)
            }
            return out.toByteArray()
        }
        if (writer.main2.size > 0 && version < 4) throw Kv3Failure("two-byte values before version 4")
        val buffer = Le.Writer()
        buffer.bytes(writer.main1.toByteArray())
        buffer.lane(writer.main2, 2)
        buffer.align(4)
        buffer.i32(writer.table.size)
        buffer.bytes(writer.main4.toByteArray())
        buffer.align(8)
        buffer.bytes(writer.main8.toByteArray())
        val stringsStart = buffer.size
        writer.table.forEach { buffer.cString(it) }
        buffer.bytes(writer.types.toByteArray())
        val countTypes = buffer.size - stringsStart
        blobLengths.forEach { buffer.i32(it) }
        buffer.i32(Trailer)
        val b = buffer.toByteArray()
        // Before version 5 zstd keeps the blobs as a second frame under the buffer's compressed size.
        val c = compress(compression, b + frameSizes)
        out.i32(writer.main1.size)
        out.i32(1 + writer.main4.size / 4)
        out.i32(writer.main8.size / 8)
        out.i32(countTypes)
        out.u16(hints.objects16)
        out.u16(hints.arrays16)
        out.i32(b.size + frameSizes.size)
        out.i32(c.size + if (compression == ZstdMethod) storedBlobs.size else 0)
        out.i32(blobLengths.size)
        out.i32(blobs.size)
        if (version >= 4) {
            out.i32(writer.main2.size / 2)
            out.i32(frameSizes.size)
        }
        out.bytes(c)
        if (blobLengths.isNotEmpty()) {
            out.bytes(storedBlobs)
            out.i32(Trailer)
        }
        return out.toByteArray()
    }

    private fun compress(method: Int, bytes: ByteArray): ByteArray = when (method) {
        Uncompressed -> bytes
        Lz4Method -> lz4Literals(bytes, 0, bytes.size)
        else -> Zstd.compress(bytes, ZstdLevel)
    }

    private fun lz4Frames(blobs: ByteArray, lengths: List<Int>): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var at = 0
        for (length in lengths) {
            var done = 0
            while (done < length) {
                val step = minOf(Lz4FrameSize, length - done)
                frames += lz4Literals(blobs, at + done, at + done + step)
                done += step
            }
            at += length
        }
        return frames
    }

    private fun lz4Literals(input: ByteArray, from: Int, to: Int): ByteArray {
        val count = to - from
        val out = Le.Writer()
        out.u8(minOf(count, 15) shl 4)
        if (count >= 15) {
            var rest = count - 15
            while (rest >= 255) {
                out.u8(255)
                rest -= 255
            }
            out.u8(rest)
        }
        out.bytes(input.copyOfRange(from, to))
        return out.toByteArray()
    }

    private class Writer(document: Kv3Document) {
        val version = document.version
        val table = ArrayList(document.strings)
        private val ids = HashMap<String, Int>().also { map -> table.forEachIndexed { index, s -> map.putIfAbsent(s, index) } }
        val types = Le.Writer()
        val main1 = Le.Writer()
        val main2 = Le.Writer()
        val main4 = Le.Writer()
        val main8 = Le.Writer()
        val aux1: Le.Writer = if (version >= 5) Le.Writer() else main1
        val aux2: Le.Writer = if (version >= 5) Le.Writer() else main2
        val aux4: Le.Writer = if (version >= 5) Le.Writer() else main4
        val aux8: Le.Writer = if (version >= 5) Le.Writer() else main8
        val objectLengths: Le.Writer = if (version >= 5) Le.Writer() else main4
        val blobs = Le.Writer()
        val blobLengths = ArrayList<Int>()
        var objectCount = 0

        fun id(value: String): Int = if (value.isEmpty()) -1 else ids.getOrPut(value) { table.add(value); table.size - 1 }

        fun type(type: Int, flag: Int) {
            if (version < 4 && type in Kv3Type.SinceVersion4) throw Kv3Failure("node type $type before version 4")
            if (flag == 0) types.u8(type) else {
                if (version < 3) throw Kv3Failure("version 2 flags are not supported")
                types.u8(type or 0x80)
                types.u8(flag)
            }
        }

        fun node(value: Kv3) {
            type(value.type, value.flag)
            payload(value, auxiliary = false)
        }

        fun payload(value: Kv3, auxiliary: Boolean) {
            val b1 = if (auxiliary) aux1 else main1
            val b2 = if (auxiliary) aux2 else main2
            val b4 = if (auxiliary) aux4 else main4
            val b8 = if (auxiliary) aux8 else main8
            when (value) {
                is Kv3.Null -> Unit
                is Kv3.Bool -> when (value.type) {
                    Kv3Type.True -> require(value.value) { "a true node holds false" }
                    Kv3Type.False -> require(!value.value) { "a false node holds true" }
                    Kv3Type.Boolean -> b1.u8(if (value.value) 1 else 0)
                    else -> throw Kv3Failure("a boolean with type ${value.type}")
                }
                is Kv3.Number -> when (value.type) {
                    Kv3Type.Int64Zero, Kv3Type.UInt32Zero, Kv3Type.DoubleZero -> require(value.bits == 0L) { "a zero node holds another value" }
                    Kv3Type.Int64One, Kv3Type.UInt32One -> require(value.bits == 1L) { "a one node holds another value" }
                    Kv3Type.DoubleOne -> require(value.bits == java.lang.Double.doubleToRawLongBits(1.0)) { "a one node holds another value" }
                    Kv3Type.Int8 -> b1.u8(value.bits.toInt().also { require(it in Byte.MIN_VALUE..Byte.MAX_VALUE) })
                    Kv3Type.UInt8 -> b1.u8(value.bits.toInt().also { require(it in 0..255) })
                    Kv3Type.Int16 -> b2.u16(value.bits.toInt().also { require(it in Short.MIN_VALUE..Short.MAX_VALUE) })
                    Kv3Type.UInt16 -> b2.u16(value.bits.toInt().also { require(it in 0..65535) })
                    Kv3Type.Int32 -> b4.i32(value.bits.toInt().also { require(value.bits in Int.MIN_VALUE..Int.MAX_VALUE) })
                    Kv3Type.UInt32, Kv3Type.Float -> b4.i32(value.bits.toInt().also { require(value.bits in 0..0xFFFFFFFFL) })
                    Kv3Type.Int64, Kv3Type.UInt64, Kv3Type.Double -> b8.i64(value.bits)
                    else -> throw Kv3Failure("a number with type ${value.type}")
                }
                is Kv3.Str -> main4.i32(id(value.value))
                is Kv3.Blob -> {
                    blobLengths += value.bytes.size
                    blobs.bytes(value.bytes)
                }
                is Kv3.List -> {
                    main4.i32(value.items.size)
                    value.items.forEach(::node)
                }
                is Kv3.Typed -> {
                    require(value.items.isNotEmpty()) { "a typed array is empty" }
                    when (value.type) {
                        Kv3Type.ArrayTyped -> main4.i32(value.items.size)
                        Kv3Type.ArrayByteLength, Kv3Type.ArrayAuxiliary -> main1.u8(value.items.size.also { require(it <= 255) { "a byte-length array is longer than 255" } })
                        else -> throw Kv3Failure("a typed array with type ${value.type}")
                    }
                    type(value.elementType, value.elementFlag)
                    for (item in value.items) {
                        require(item.type == value.elementType && item.flag == value.elementFlag) { "a typed array element changed its type" }
                        payload(item, auxiliary = value.type == Kv3Type.ArrayAuxiliary)
                    }
                }
                is Kv3.Obj -> {
                    objectCount++
                    objectLengths.i32(value.members.size)
                    for ((name, member) in value.members) {
                        type(member.type, member.flag)
                        main4.i32(id(name))
                        payload(member, auxiliary = false)
                    }
                }
            }
        }
    }

    private fun lz4(input: ByteArray, size: Int): ByteArray {
        val output = ByteArray(size)
        if (Lz4.decode(input, 0, input.size, output, 0, size) != size) throw Kv3Failure("LZ4 output is shorter than declared")
        return output
    }

    private fun zstd(input: ByteArray, size: Int): ByteArray {
        val output = ByteArray(size)
        val written = try {
            Zstd.decompressByteArray(output, 0, size, input, 0, input.size)
        } catch (e: RuntimeException) {
            throw Kv3Failure("zstd data does not decompress: ${e.message}")
        }
        if (written.toInt() != size) throw Kv3Failure("zstd output is not the declared size")
        return output
    }

    private const val MaxFlag = 15

    private const val ZstdLevel = 3
}

internal object Le {
    fun i32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    fun u32(bytes: ByteArray, at: Int): Long = i32(bytes, at).toLong() and 0xFFFFFFFFL

    fun putI32(bytes: ByteArray, at: Int, value: Int) {
        for (i in 0 until 4) bytes[at + i] = (value ushr (8 * i)).toByte()
    }

    fun ints(values: IntArray): ByteArray = Writer().also { w -> values.forEach { w.i32(it) } }.toByteArray()

    class Reader(private val source: ByteArray, private val start: Int = 0, private val end: Int = source.size) {
        var position: Int = 0
            private set

        val remaining: Int get() = end - start - position

        private fun need(count: Long) {
            if (count < 0 || count > remaining) throw Kv3Failure("a value runs past its data")
        }

        fun u8(): Int {
            need(1)
            return source[start + position++].toInt() and 0xFF
        }

        fun u16(): Int {
            need(2)
            return u16(source, start + position).also { position += 2 }
        }

        fun i32(): Int {
            need(4)
            return i32(source, start + position).also { position += 4 }
        }

        fun count(): Int = i32().also { if (it < 0) throw Kv3Failure("a negative count") }

        fun i64(): Long {
            need(8)
            val low = i32(source, start + position).toLong() and 0xFFFFFFFFL
            val high = i32(source, start + position + 4).toLong()
            position += 8
            return low or (high shl 32)
        }

        fun bytes(count: Int): ByteArray {
            need(count.toLong())
            return source.copyOfRange(start + position, start + position + count).also { position += count }
        }

        fun slice(count: Long): Reader {
            need(count)
            return Reader(source, start + position, start + position + count.toInt()).also { position += count.toInt() }
        }

        fun aligned(alignment: Int, count: Long, padWhenEmpty: Boolean = false): Reader {
            if (count == 0L && !padWhenEmpty) return Reader(source, start + position, start + position)
            val padding = (alignment - position % alignment) % alignment
            need(padding.toLong())
            position += padding
            return slice(count)
        }

        fun cString(): String {
            var at = start + position
            while (at < end && source[at] != 0.toByte()) at++
            if (at >= end) throw Kv3Failure("a string is not terminated")
            val text = try {
                source.decodeToString(start + position, at, throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw Kv3Failure("a string is not UTF-8")
            }
            position = at + 1 - start
            return text
        }
    }

    class Writer {
        private var buffer = ByteArray(256)
        var size: Int = 0
            private set

        private fun room(count: Int) {
            if (size + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + count))
        }

        fun u8(value: Int) {
            room(1)
            buffer[size++] = value.toByte()
        }

        fun u16(value: Int) {
            room(2)
            buffer[size++] = value.toByte()
            buffer[size++] = (value ushr 8).toByte()
        }

        fun i32(value: Int) {
            room(4)
            putI32(buffer, size, value)
            size += 4
        }

        fun i64(value: Long) {
            i32(value.toInt())
            i32((value ushr 32).toInt())
        }

        fun bytes(value: ByteArray) {
            room(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }

        fun cString(value: String) {
            val encoded = value.encodeToByteArray()
            if (encoded.any { it == 0.toByte() }) throw Kv3Failure("a string contains a NUL")
            bytes(encoded)
            u8(0)
        }

        fun align(alignment: Int) {
            while (size % alignment != 0) u8(0)
        }

        fun lane(lane: Writer, alignment: Int) {
            if (lane.size == 0) return
            align(alignment)
            bytes(lane.toByteArray())
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}
