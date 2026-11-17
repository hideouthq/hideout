package gg.hideouthq.library.compat

internal data class ResourceBlock(val index: Int, val type: String, val offset: Int, val size: Int) {
    val end: Int get() = offset + size
}

internal class ResourceFile(val bytes: ByteArray, val version: Int, val blocks: List<ResourceBlock>) {
    fun payload(block: ResourceBlock): ByteArray = bytes.copyOfRange(block.offset, block.end)

    fun blocks(type: String): List<ResourceBlock> = blocks.filter { it.type == type }
}

internal object ResourceContainer {
    private const val HeaderVersion = 12
    private const val HeaderBytes = 16
    private const val EntryBytes = 12
    private const val MaxBlocks = 512

    fun read(bytes: ByteArray): ResourceFile {
        if (bytes.size < HeaderBytes) throw Kv3Failure("shorter than a resource header")
        val fileSize = Le.u32(bytes, 0)
        if (Le.u16(bytes, 4) != HeaderVersion) throw Kv3Failure("resource header version ${Le.u16(bytes, 4)} is not supported")
        if (fileSize != bytes.size.toLong()) throw Kv3Failure("the resource declares $fileSize bytes but has ${bytes.size}")
        val version = Le.u16(bytes, 6)
        val tableField = 8L
        val table = tableField + Le.u32(bytes, 8)
        val count = Le.u32(bytes, 12)
        if (count > MaxBlocks) throw Kv3Failure("more than $MaxBlocks blocks")
        if (table < HeaderBytes || table + count * EntryBytes > bytes.size) throw Kv3Failure("the block table is outside the file")
        val blocks = (0 until count.toInt()).map { index ->
            val at = (table + index * EntryBytes).toInt()
            val type = bytes.decodeToString(at, at + 4)
            if (!type.all { it.isLetterOrDigit() && it.code < 128 }) throw Kv3Failure("a block type is not four letters")
            val offset = at + 4 + Le.u32(bytes, at + 4)
            val size = Le.u32(bytes, at + 8)
            if (offset + size > bytes.size) throw Kv3Failure("block $type runs past the file")
            ResourceBlock(index, type, offset.toInt(), size.toInt())
        }
        val tableEnd = (table + count * EntryBytes).toInt()
        val ordered = blocks.sortedBy { it.offset }
        var cursor = tableEnd
        for (block in ordered) {
            if (block.offset < cursor) throw Kv3Failure("block ${block.type} overlaps the table or another block")
            if (block !== ordered.first() && (cursor until block.offset).any { bytes[it] != 0.toByte() }) {
                throw Kv3Failure("the bytes before block ${block.type} are not padding")
            }
            cursor = block.end
        }
        if ((cursor until bytes.size).any { bytes[it] != 0.toByte() }) throw Kv3Failure("the resource has data after its blocks")
        return ResourceFile(bytes, version, blocks)
    }

    fun rewrite(file: ResourceFile, replacements: Map<Int, ByteArray>): ByteArray {
        require(replacements.keys.all { it in file.blocks.indices }) { "only the file's own blocks can be replaced" }
        val ordered = file.blocks.sortedBy { it.offset }
        val first = ordered.firstOrNull()?.offset ?: file.bytes.size
        val out = Le.Writer()
        out.bytes(file.bytes.copyOf(first))
        val placed = HashMap<Int, Pair<Int, Int>>()
        var previousEnd = first
        for (block in ordered) {
            val alignment = alignmentOf(block.offset)
            val extra = block.offset - previousEnd - (alignment - previousEnd % alignment) % alignment
            repeat(extra) { out.u8(0) }
            out.align(alignment)
            val payload = replacements[block.index] ?: file.payload(block)
            placed[block.index] = out.size to payload.size
            out.bytes(payload)
            previousEnd = block.end
        }
        val trailing = file.bytes.size - previousEnd
        repeat(trailing.coerceAtLeast(0)) { out.u8(0) }
        val result = out.toByteArray()
        val table = (8L + Le.u32(file.bytes, 8)).toInt()
        for (block in file.blocks) {
            val (offset, size) = placed.getValue(block.index)
            val at = table + block.index * EntryBytes
            Le.putI32(result, at + 4, offset - (at + 4))
            Le.putI32(result, at + 8, size)
        }
        Le.putI32(result, 0, result.size)
        return result
    }

    private fun alignmentOf(offset: Int): Int {
        var alignment = 16
        while (alignment > 1 && offset % alignment != 0) alignment /= 2
        return alignment
    }
}
