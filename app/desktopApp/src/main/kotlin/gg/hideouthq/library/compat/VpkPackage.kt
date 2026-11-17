package gg.hideouthq.library.compat

import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

internal interface ByteSource : AutoCloseable {
    val size: Long

    fun read(position: Long, length: Int): ByteArray

    override fun close() = Unit
}

internal class ArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, length: Int): ByteArray {
        if (position < 0 || length < 0 || position + length > bytes.size) throw Kv3Failure("a read runs past the file")
        return bytes.copyOfRange(position.toInt(), position.toInt() + length)
    }
}

internal data class VpkEntry(
    val extension: String,
    val folder: String,
    val name: String,
    val crc: Int,
    val preload: ByteArray,
    val archive: Int,
    val offset: Long,
    val length: Long,
) {
    /** The path the game knows it by: forward slashes, lowercase, without the tree's blank placeholders. */
    val path: String
        get() = ((if (folder == " ") "" else "$folder/") + name + (if (extension == " ") "" else ".$extension")).replace('\\', '/').lowercase()

    val embedded: Boolean get() = archive == EmbeddedArchive

    override fun equals(other: Any?): Boolean = other is VpkEntry && extension == other.extension && folder == other.folder && name == other.name &&
        crc == other.crc && preload.contentEquals(other.preload) && archive == other.archive && offset == other.offset && length == other.length

    override fun hashCode(): Int = listOf(extension, folder, name, crc, archive, offset, length).hashCode() * 31 + preload.contentHashCode()

    companion object {
        const val EmbeddedArchive = 0x7FFF
    }
}

internal class VpkPackage(
    val version: Int,
    val groups: List<Pair<String, List<Pair<String, List<VpkEntry>>>>>,
    val dataStart: Long,
    val dataBytes: Long,
    val archiveMd5: ByteArray,
    val otherMd5: ByteArray,
    val signature: ByteArray,
) {
    val entries: List<VpkEntry> get() = groups.flatMap { (_, folders) -> folders.flatMap { it.second } }

    fun find(path: String): List<VpkEntry> = entries.filter { it.path == path.lowercase() }
}

internal object VpkPackages {
    private const val Signature = 0x55AA1234
    private const val Terminator = 0xFFFF
    private const val OtherMd5Bytes = 48
    private const val EmptySignatureBytes = 20
    const val MaxTreeBytes: Int = 64 * 1024 * 1024
    const val MaxEntryBytes: Long = 512L * 1024 * 1024

    fun read(dir: ByteSource): VpkPackage {
        if (dir.size < 12) throw Kv3Failure("shorter than a VPK header")
        val prefix = dir.read(0, minOf(28L, dir.size).toInt())
        if (Le.i32(prefix, 0) != Signature) throw Kv3Failure("not a VPK")
        val version = Le.i32(prefix, 4)
        val headerBytes = when (version) {
            1 -> 12
            2 -> 28.also { if (prefix.size < 28) throw Kv3Failure("shorter than a VPK header") }
            else -> throw Kv3Failure("VPK version $version is not supported")
        }
        val treeBytes = Le.u32(prefix, 8)
        if (treeBytes > MaxTreeBytes || headerBytes + treeBytes > dir.size) throw Kv3Failure("the VPK tree is too large or cut short")
        val tree = dir.read(headerBytes.toLong(), treeBytes.toInt())
        val dataStart = headerBytes + treeBytes
        val dataBytes: Long
        val archiveMd5: ByteArray
        val otherMd5: ByteArray
        val signature: ByteArray
        if (version == 1) {
            dataBytes = dir.size - dataStart
            archiveMd5 = ByteArray(0)
            otherMd5 = ByteArray(0)
            signature = ByteArray(0)
        } else {
            dataBytes = Le.u32(prefix, 12)
            val archiveBytes = Le.u32(prefix, 16)
            val otherBytes = Le.u32(prefix, 20)
            var signatureBytes = Le.u32(prefix, 24)
            val signatureStart = dataStart + dataBytes + archiveBytes + otherBytes
            if (signatureBytes == EmptySignatureBytes.toLong() && signatureStart + EmptySignatureBytes <= dir.size) {
                // This form declares its key and signature sizes, and those bytes follow its 20.
                val head = dir.read(signatureStart, EmptySignatureBytes)
                if (Le.i32(head, 0) == Signature) signatureBytes += Le.u32(head, 8) + Le.u32(head, 12)
            }
            if (signatureStart + signatureBytes != dir.size) throw Kv3Failure("the VPK sections do not end at the end of the file")
            if (otherBytes != 0L && otherBytes != OtherMd5Bytes.toLong()) throw Kv3Failure("an unknown checksum section")
            archiveMd5 = dir.read(dataStart + dataBytes, archiveBytes.toInt())
            otherMd5 = dir.read(dataStart + dataBytes + archiveBytes, otherBytes.toInt())
            signature = dir.read(signatureStart, signatureBytes.toInt())
        }
        return VpkPackage(version, parseTree(tree, dataBytes), dataStart, dataBytes, archiveMd5, otherMd5, signature)
    }

    private fun parseTree(tree: ByteArray, dataBytes: Long): List<Pair<String, List<Pair<String, List<VpkEntry>>>>> {
        var position = 0
        fun string(): String {
            val start = position
            while (position < tree.size && tree[position] != 0.toByte()) position++
            if (position >= tree.size) throw Kv3Failure("a name in the VPK tree is not terminated")
            position++
            return try {
                tree.decodeToString(start, position - 1, throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw Kv3Failure("a name in the VPK tree is not UTF-8")
            }
        }
        val groups = mutableListOf<Pair<String, List<Pair<String, List<VpkEntry>>>>>()
        while (true) {
            val extension = string().ifEmpty { null } ?: break
            val folders = mutableListOf<Pair<String, List<VpkEntry>>>()
            while (true) {
                val folder = string().ifEmpty { null } ?: break
                val entries = mutableListOf<VpkEntry>()
                while (true) {
                    val name = string().ifEmpty { null } ?: break
                    if (position + 18 > tree.size) throw Kv3Failure("a VPK entry is cut short")
                    val crc = Le.i32(tree, position)
                    val preload = Le.u16(tree, position + 4)
                    val archive = Le.u16(tree, position + 6)
                    val offset = Le.u32(tree, position + 8)
                    val length = Le.u32(tree, position + 12)
                    if (Le.u16(tree, position + 16) != Terminator) throw Kv3Failure("a VPK entry has no terminator")
                    position += 18
                    if (position + preload > tree.size) throw Kv3Failure("a VPK entry's preload runs past the tree")
                    val preloadBytes = tree.copyOfRange(position, position + preload)
                    position += preload
                    if (archive == VpkEntry.EmbeddedArchive && length > 0 && offset + length > dataBytes) throw Kv3Failure("a VPK entry reads past the embedded data")
                    entries += VpkEntry(extension, folder, name, crc, preloadBytes, archive, offset, length)
                }
                folders += folder to entries
            }
            groups += extension to folders
        }
        if (position != tree.size) throw Kv3Failure("the VPK tree ends before its declared size")
        return groups
    }

    fun payload(pack: VpkPackage, entry: VpkEntry, dir: ByteSource, chunk: (Int) -> ByteSource?, limit: Long = MaxEntryBytes): ByteArray {
        val total = entry.preload.size + entry.length
        if (total > limit) throw Kv3Failure("${entry.path} is larger than $limit bytes")
        val rest = if (entry.length == 0L) ByteArray(0) else if (entry.embedded) {
            dir.read(pack.dataStart + entry.offset, entry.length.toInt())
        } else {
            val source = chunk(entry.archive) ?: throw Kv3Failure("${entry.path} is in a chunk that is not there")
            if (entry.offset + entry.length > source.size) throw Kv3Failure("${entry.path} reads past its chunk")
            source.read(entry.offset, entry.length.toInt())
        }
        val bytes = entry.preload + rest
        val crc = CRC32().apply { update(bytes) }.value.toInt()
        if (crc != entry.crc) throw Kv3Failure("${entry.path} does not match its CRC32")
        return bytes
    }

    fun rewrite(pack: VpkPackage, dir: ByteSource, chunkSizes: Map<Int, Long>, replaced: Map<VpkEntry, ByteArray>, out: OutputStream): Map<Int, List<ByteArray>> {
        if (pack.version == 2 && pack.signature.isNotEmpty() && !emptySignature(pack.signature)) throw Kv3Failure("the VPK is signed")
        val all = pack.entries
        for (entry in replaced.keys) {
            if (all.count { it == entry } != 1) throw Kv3Failure("${entry.path} is not exactly one entry of the VPK")
            if (all.count { it.path == entry.path } != 1) throw Kv3Failure("${entry.path} is named more than once in the VPK")
        }
        var embeddedEnd = pack.dataBytes
        val chunkEnds = chunkSizes.toMutableMap()
        val appended = mutableMapOf<Int, MutableList<ByteArray>>()
        val moved = HashMap<VpkEntry, VpkEntry>()
        for ((entry, bytes) in replaced) {
            if (bytes.size > MaxEntryBytes) throw Kv3Failure("${entry.path} would be larger than $MaxEntryBytes bytes")
            val crc = CRC32().apply { update(bytes) }.value.toInt()
            val offset = if (entry.embedded) embeddedEnd.also { embeddedEnd += bytes.size } else {
                val end = chunkEnds[entry.archive] ?: throw Kv3Failure("${entry.path} is in a chunk that is not there")
                end.also { chunkEnds[entry.archive] = end + bytes.size }
            }
            if (offset + bytes.size > 0xFFFFFFFFL) throw Kv3Failure("the rewritten data is larger than a VPK can address")
            if (!entry.embedded) appended.getOrPut(entry.archive) { mutableListOf() } += bytes
            moved[entry] = entry.copy(crc = crc, preload = ByteArray(0), offset = offset, length = bytes.size.toLong())
        }
        val tree = Le.Writer()
        for ((extension, folders) in pack.groups) {
            tree.cString(extension)
            for ((folder, entries) in folders) {
                tree.cString(folder)
                for (entry in entries) {
                    val written = moved[entry] ?: entry
                    tree.cString(written.name)
                    tree.i32(written.crc)
                    tree.u16(written.preload.size)
                    tree.u16(written.archive)
                    tree.i32(written.offset.toInt())
                    tree.i32(written.length.toInt())
                    tree.u16(Terminator)
                    tree.bytes(written.preload)
                }
                tree.u8(0)
            }
            tree.u8(0)
        }
        tree.u8(0)
        val treeBytes = tree.toByteArray()
        val whole = MessageDigest.getInstance("MD5")
        fun emit(bytes: ByteArray) {
            whole.update(bytes)
            out.write(bytes)
        }
        val header = Le.Writer()
        header.i32(Signature)
        header.i32(pack.version)
        header.i32(treeBytes.size)
        if (pack.version == 2) {
            header.i32(embeddedEnd.toInt())
            header.i32(pack.archiveMd5.size)
            header.i32(pack.otherMd5.size)
            header.i32(pack.signature.size)
        }
        emit(header.toByteArray())
        emit(treeBytes)
        var copied = 0L
        while (copied < pack.dataBytes) {
            val step = minOf(CopyBytes.toLong(), pack.dataBytes - copied).toInt()
            emit(dir.read(pack.dataStart + copied, step))
            copied += step
        }
        for ((entry, bytes) in replaced) if (entry.embedded) emit(bytes)
        if (pack.version == 2) {
            emit(pack.archiveMd5)
            if (pack.otherMd5.isNotEmpty()) {
                val checksums = md5(treeBytes) + md5(pack.archiveMd5)
                emit(checksums)
                val total = whole.digest()
                out.write(total)
            }
            out.write(pack.signature)
        }
        return appended
    }

    private fun emptySignature(section: ByteArray): Boolean =
        section.size == EmptySignatureBytes && Le.i32(section, 0) == Signature && Le.i32(section, 8) == 0 && Le.i32(section, 12) == 0

    private fun md5(bytes: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(bytes)

    private const val CopyBytes = 1 shl 20
}
