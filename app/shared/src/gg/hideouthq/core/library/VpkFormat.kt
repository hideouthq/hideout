package gg.hideouthq.core.library

/**
 * What a VPK's own directory tree says about where its files' bytes live. Only the structure is checked: the
 * engine reads the resources, Hideout never extracts them, so repeated internal paths are not a defect here.
 */
sealed interface VpkRead {
    /** Each chunk index the tree names, with the furthest byte an entry reads from that chunk. */
    data class Parsed(val chunkExtents: Map<Int, Long>) : VpkRead

    data class Unreadable(val reason: String) : VpkRead
}

/** A file at the tree's root that the directory file holds itself: [inline] of its bytes in the tree, then [length] more from [at]. */
class EmbeddedFile(val inline: ByteArray, val at: Long, val length: Long)

sealed interface VpkHead {
    /** The header and tree together are [bytes] long. */
    data class Length(val bytes: Int) : VpkHead

    data class NotVpk(val reason: String) : VpkHead
}

/**
 * Valve's VPK directory format, versions 1 and 2, read from bytes the caller supplies: first [PrefixBytes] to learn
 * how long the header and tree are, then exactly that much.
 */
object VpkFormat {
    const val PrefixBytes: Int = 28

    /** The largest header and tree read. Real trees are a few hundred kilobytes at most. */
    const val MaxHeadBytes: Int = 64 * 1024 * 1024

    private const val Signature = 0x55AA1234L
    private const val V1HeaderBytes = 12
    private const val V2HeaderBytes = 28
    private const val EmbeddedIndex = 0x7FFF
    private const val Terminator = 0xFFFF
    private const val EntryBytes = 18

    fun headLength(prefix: ByteArray, fileSize: Long): VpkHead {
        if (prefix.size < V1HeaderBytes) return VpkHead.NotVpk("shorter than a VPK header")
        if (u32(prefix, 0) != Signature) return VpkHead.NotVpk("no VPK signature")
        val header = when (u32(prefix, 4)) {
            1L -> V1HeaderBytes
            2L -> V2HeaderBytes
            else -> return VpkHead.NotVpk("VPK version ${u32(prefix, 4)} is not one Deadlock reads")
        }
        val total = header + u32(prefix, 8)
        return when {
            total > fileSize -> VpkHead.NotVpk("its tree is longer than the file")
            total > MaxHeadBytes -> VpkHead.NotVpk("its tree is larger than $MaxHeadBytes bytes")
            else -> VpkHead.Length(total.toInt())
        }
    }

    /** Reads the directory from [head], the file's first header-and-tree bytes, in a file of [fileSize] bytes. */
    fun read(head: ByteArray, fileSize: Long): VpkRead = try {
        VpkRead.Parsed(parse(head, fileSize))
    } catch (e: MalformedVpk) {
        VpkRead.Unreadable(e.message ?: "malformed")
    }

    /** Informational only: failure to name resources never changes whether [read] accepts an installation. A repeated path keeps its first entry. */
    fun resources(head: ByteArray, fileSize: Long): ResourceIndex = try {
        val files = LinkedHashMap<String, Long>()
        parse(head, fileSize) { entry ->
            if (entry.path.any { it.isISOControl() }) fail("a resource name contains control characters")
            files.putIfAbsent(entry.path.replace('\\', '/').lowercase(), entry.crc)
        }
        ResourceIndex.Ready(files)
    } catch (e: MalformedVpk) {
        ResourceIndex.Unavailable
    } catch (e: CharacterCodingException) {
        ResourceIndex.Unavailable
    }

    /**
     * Where the directory file keeps [Imprint.EntryName], a file at the tree's root matched without case, when it keeps
     * all of it; null when the tree lists no such file, keeps it in a chunk, or cannot be read.
     */
    fun modinfo(head: ByteArray, fileSize: Long): EmbeddedFile? = try {
        var found: EmbeddedFile? = null
        parse(head, fileSize) { entry ->
            if (found == null && entry.folder == " " && entry.path.equals(Imprint.EntryName, ignoreCase = true) && (entry.bytes == 0L || entry.embeddedAt != null)) {
                found = EmbeddedFile(head.copyOfRange(entry.preloadAt, entry.preloadAt + entry.preload), entry.embeddedAt ?: 0, entry.bytes)
            }
        }
        found
    } catch (e: MalformedVpk) {
        null
    } catch (e: CharacterCodingException) {
        null
    }

    private class TreeEntry(val extension: String, val folder: String, val name: String, val crc: Long, val preloadAt: Int, val preload: Int, val bytes: Long, val embeddedAt: Long?) {
        val path: String get() = (if (folder == " ") "" else "$folder/") + name + (if (extension == " ") "" else ".$extension")
    }

    private fun parse(head: ByteArray, fileSize: Long, resource: ((TreeEntry) -> Unit)? = null): Map<Int, Long> {
        val length = when (val read = headLength(head, fileSize)) {
            is VpkHead.NotVpk -> fail(read.reason)
            is VpkHead.Length -> read
        }
        if (head.size < length.bytes) fail("the header and tree are cut short")
        val version = u32(head, 4).toInt()
        val headerBytes = if (version == 1) V1HeaderBytes else V2HeaderBytes
        val treeEnd = length.bytes
        val embedded = when (version) {
            1 -> fileSize - treeEnd
            else -> {
                val data = u32(head, 12)
                val checksums = u32(head, 16) + u32(head, 20) + u32(head, 24)
                if (treeEnd + data + checksums > fileSize) fail("its sections are longer than the file")
                data
            }
        }
        var position = headerBytes
        val chunkExtents = HashMap<Int, Long>()

        fun string(): String? {
            val start = position
            while (position < treeEnd && head[position] != 0.toByte()) position++
            if (position >= treeEnd) fail("a name in the tree is not terminated")
            position++
            if (position - 1 == start) return null
            return if (resource == null) "" else head.decodeToString(start, position - 1, throwOnInvalidSequence = true)
        }

        while (true) {
            val extension = string() ?: break
            while (true) {
                val folder = string() ?: break
                while (true) {
                    val name = string() ?: break
                    if (position + EntryBytes > treeEnd) fail("an entry is cut short")
                    val preload = u16(head, position + 4)
                    val archive = u16(head, position + 6)
                    val offset = u32(head, position + 8)
                    val bytes = u32(head, position + 12)
                    if (u16(head, position + 16) != Terminator) fail("an entry has no terminator")
                    if (position + EntryBytes + preload > treeEnd) fail("an entry's preload runs past the tree")
                    resource?.invoke(TreeEntry(extension, folder, name, u32(head, position), position + EntryBytes, preload, bytes, (treeEnd + offset).takeIf { archive == EmbeddedIndex }))
                    position += EntryBytes + preload
                    if (bytes == 0L) continue
                    val extent = offset + bytes
                    if (archive == EmbeddedIndex) {
                        if (extent > embedded) fail("an entry reads past the embedded data")
                    } else {
                        chunkExtents[archive] = maxOf(chunkExtents[archive] ?: 0L, extent)
                    }
                }
            }
        }
        if (position != treeEnd) fail("the tree ends before its declared size")
        return chunkExtents
    }

    private fun u16(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long = (0 until 4).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }

    private fun fail(reason: String): Nothing = throw MalformedVpk(reason)

    private class MalformedVpk(reason: String) : Exception(reason)
}
