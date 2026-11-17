package gg.hideouthq.library.archive

import gg.hideouthq.core.library.ArchiveEntry
import gg.hideouthq.core.library.ArchiveRejection
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.RejectionReason
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.rarfile.HostSystem
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.archivers.zip.ZipMethod
import org.tukaani.xz.MemoryLimitException
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer

internal class ArchiveRefused(val rejection: ArchiveRejection) : IOException("${rejection.reason} ${rejection.entry.orEmpty()}")

/** [passed] stays true once it fired, so a reader that swallows the exception cannot hide it. */
internal class Deadline(private val endsAt: Long, private val cancelled: () -> Boolean = { false }, private val now: () -> Long = System::nanoTime) {
    @Volatile
    var passed: Boolean = false
        private set

    fun check(entry: String? = null) {
        if (cancelled()) throw InterruptedIOException("cancelled")
        if (now() - endsAt >= 0) {
            passed = true
            throw ArchiveRefused(ArchiveRejection(RejectionReason.TimedOut, entry))
        }
    }
}

internal class ArchiveBudget(private val maxBytes: Long, private val deadline: Deadline) {
    private var used = 0L

    fun charge(bytes: Int, entry: String?) {
        deadline.check(entry)
        if (bytes > maxBytes - used) throw ArchiveRefused(ArchiveRejection(RejectionReason.TooLarge, entry))
        used += bytes
    }
}

internal class ArchiveDrain(private val budget: ArchiveBudget) : OutputStream() {
    override fun write(b: Int) = budget.charge(1, null)

    override fun write(b: ByteArray, off: Int, len: Int) = budget.charge(len, null)
}

internal interface ArchiveReader : Closeable {
    val entries: List<ArchiveEntry>

    fun decode(open: (Int) -> OutputStream?, drain: () -> OutputStream)
}

internal object ArchiveReaders {
    const val MaxDictionaryBytes: Long = 128L * 1024 * 1024

    /** A file [name]d as a VPK is one, since a chunk has no header to tell it by. */
    fun open(path: Path, name: String, deadline: Deadline): ArchiveReader {
        val magic = Files.newInputStream(path).use { it.readNBytes(8) }
        val reader = try {
            when {
                name.endsWith(".vpk", ignoreCase = true) || magic.startsWith(0x34, 0x12, 0xAA, 0x55) -> BareVpkReader(path, name)
                magic.startsWith(0x50, 0x4B, 0x07, 0x08) || SplitName.matches(name.lowercase()) -> refuse(RejectionReason.SplitVolume)
                magic.isZip() -> ZipReader(timed(path, deadline))
                magic.isSevenZ() -> SevenZReader(timed(path, deadline))
                magic.isRar() -> RarReader(path, deadline)
                laterZipVolume(path) -> refuse(RejectionReason.SplitVolume)
                else -> refuse(RejectionReason.UnsupportedFormat)
            }
        } catch (e: IOException) {
            if (deadline.passed) refuse(RejectionReason.TimedOut)
            throw e
        }
        if (deadline.passed) {
            reader.close()
            refuse(RejectionReason.TimedOut)
        }
        return reader
    }
}

private val SplitName = Regex(".+\\.((7z|zip|rar)\\.\\d{3}|z\\d{2}|r\\d{2})")

private fun laterZipVolume(path: Path): Boolean {
    val tail = Files.newByteChannel(path).use { channel ->
        val size = channel.size()
        val read = minOf(size, EndRecordReach).toInt()
        ByteBuffer.allocate(read).also { channel.position(size - read).read(it) }.array()
    }
    val at = (tail.size - EndRecordBytes downTo 0).firstOrNull { tail.copyOfRange(it, it + 4).startsWith(0x50, 0x4B, 0x05, 0x06) } ?: return false
    val disk = (tail[at + 4].toInt() and 0xFF) or ((tail[at + 5].toInt() and 0xFF) shl 8)
    return disk != 0
}

private const val EndRecordBytes = 22
private const val EndRecordReach = EndRecordBytes + 0xFFFFL

private fun ByteArray.startsWith(vararg bytes: Int) = size >= bytes.size && bytes.indices.all { this[it] == bytes[it].toByte() }

internal fun ByteArray.isZip() = startsWith(0x50, 0x4B, 0x03, 0x04) || startsWith(0x50, 0x4B, 0x05, 0x06)

internal fun ByteArray.isSevenZ() = startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)

internal fun ByteArray.isRar() = startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

private fun refuse(reason: RejectionReason, entry: String? = null): Nothing = throw ArchiveRefused(ArchiveRejection(reason, entry))

/** Throws the refusal or cancellation that caused [e], if one did, so a reader's own error mapping cannot hide it. */
private fun rethrowRefusal(e: Throwable) {
    generateSequence(e) { it.cause }.firstOrNull { it is ArchiveRefused || it is InterruptedIOException }?.let { throw it }
}

private fun timed(path: Path, deadline: Deadline): SeekableByteChannel = TimedChannel(Files.newByteChannel(path), deadline)

private class TimedChannel(private val channel: SeekableByteChannel, private val deadline: Deadline) : SeekableByteChannel by channel {
    override fun read(dst: ByteBuffer): Int {
        deadline.check()
        return channel.read(dst)
    }

    override fun position(newPosition: Long): SeekableByteChannel = apply { channel.position(newPosition) }

    override fun truncate(size: Long): SeekableByteChannel = apply { channel.truncate(size) }
}

private fun nfc(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFC)

private const val Buffer = 1 shl 16
private const val UnixTypeMask = 0xF000
private const val UnixLink = 0xA000
private const val WindowsReparsePoint = 0x400
private const val WindowsUnixExtension = 0x8000

private class ZipReader(channel: SeekableByteChannel) : ArchiveReader {
    private val zip = try {
        ZipFile.builder().setSeekableByteChannel(channel).setMaxNumberOfDisks(1).get()
    } catch (e: IOException) {
        channel.close()
        rethrowRefusal(e)
        refuse(RejectionReason.Corrupt)
    }
    private val ordered: List<ZipArchiveEntry> = zip.entriesInPhysicalOrder.toList()

    override val entries: List<ArchiveEntry> = ordered.mapIndexed { index, entry ->
        val kind = when {
            entry.isUnixSymlink -> EntryKind.Link
            entry.isDirectory -> EntryKind.Directory
            else -> EntryKind.File
        }
        ArchiveEntry(index, nfc(entry.name), kind, entry.size.takeIf { it >= 0 }, entry.generalPurposeBit.usesEncryption())
    }

    override fun decode(open: (Int) -> OutputStream?, drain: () -> OutputStream) {
        ordered.forEachIndexed { index, entry ->
            if (entry.isDirectory) return@forEachIndexed
            val sink = open(index) ?: return@forEachIndexed
            sink.use {
                try {
                    input(entry).use { input -> input.copyTo(it, Buffer) }
                } catch (e: MemoryLimitException) {
                    refuse(RejectionReason.DictionaryTooLarge, entry.name)
                }
            }
        }
    }

    private fun input(entry: ZipArchiveEntry): InputStream {
        if (!zip.canReadEntryData(entry)) refuse(RejectionReason.UnsupportedMethod, entry.name)
        return when (ZipMethod.getMethodByCode(entry.method)) {
            ZipMethod.XZ -> XZInputStream(BufferedInputStream(zip.getRawInputStream(entry)), (ArchiveReaders.MaxDictionaryBytes / 1024).toInt())
            in BoundedZipMethods -> zip.getInputStream(entry)
            else -> refuse(RejectionReason.UnsupportedMethod, entry.name)
        }
    }

    override fun close() = zip.close()

    private companion object {
        val BoundedZipMethods = setOf(ZipMethod.STORED, ZipMethod.UNSHRINKING, ZipMethod.IMPLODING, ZipMethod.DEFLATED, ZipMethod.ENHANCED_DEFLATED, ZipMethod.BZIP2)
    }
}

private class SevenZReader(channel: SeekableByteChannel) : ArchiveReader {
    private val archive = try {
        SevenZFile.builder().setSeekableByteChannel(channel).setMaxMemoryLimitKiB((ArchiveReaders.MaxDictionaryBytes / 1024).toInt()).get()
    } catch (e: IOException) {
        channel.close()
        rethrowRefusal(e)
        refuse(
            when (e.javaClass.simpleName) {
                "PasswordRequiredException" -> RejectionReason.Encrypted
                "MemoryLimitException" -> RejectionReason.DictionaryTooLarge
                else -> RejectionReason.Corrupt
            },
        )
    }
    private val all = archive.entries.toList()

    override val entries: List<ArchiveEntry> = all.mapIndexed { index, entry ->
        val attributes = if (entry.hasWindowsAttributes) entry.windowsAttributes else 0
        val unixLink = attributes and WindowsUnixExtension != 0 && (attributes ushr 16) and UnixTypeMask == UnixLink
        val kind = when {
            entry.isAntiItem -> EntryKind.Other
            unixLink || attributes and WindowsReparsePoint != 0 -> EntryKind.Link
            entry.isDirectory -> EntryKind.Directory
            else -> EntryKind.File
        }
        val encrypted = entry.contentMethods?.any { it.method == SevenZMethod.AES256SHA256 } == true
        ArchiveEntry(index, nfc(entry.name ?: ""), kind, if (entry.hasStream()) entry.size else 0, encrypted)
    }

    override fun decode(open: (Int) -> OutputStream?, drain: () -> OutputStream) {
        try {
            decodeAll(open, drain)
        } catch (e: IOException) {
            rethrowRefusal(e)
            // Entries list no cipher when the archive keeps it for a whole folder of them; reading one asks for the password.
            if (e.javaClass.simpleName == "PasswordRequiredException") refuse(RejectionReason.Encrypted)
            throw e
        }
    }

    private fun decodeAll(open: (Int) -> OutputStream?, drain: () -> OutputStream) {
        val buffer = ByteArray(Buffer)
        var index = 0
        while (true) {
            val entry = archive.nextEntry ?: break
            if (entry.hasStream() && !entry.isDirectory) {
                (open(index) ?: drain()).use { sink ->
                    while (true) {
                        val read = archive.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                    }
                }
            }
            index++
        }
    }

    override fun close() = archive.close()
}

private class SingleVolume(private val file: Path, private val deadline: Deadline) : VolumeManager {
    override fun nextVolume(archive: Archive, lastVolume: Volume?): Volume? {
        if (lastVolume != null) return null
        val channel = Files.newByteChannel(file)
        return object : Volume {
            override fun getArchive(): Archive = archive
            override fun getLength(): Long = channel.size()
            override fun getChannel(): SeekableReadOnlyByteChannel = TimedRarChannel(channel, deadline)
        }
    }
}

internal abstract class RarChannel : SeekableReadOnlyByteChannel {
    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun readFully(buffer: ByteArray, count: Int): Int {
        var done = 0
        while (done < count) {
            val read = read(buffer, done, count - done)
            if (read < 0) throw EOFException()
            done += read
        }
        return count
    }
}

private class TimedRarChannel(private val channel: SeekableByteChannel, private val deadline: Deadline) : RarChannel() {
    override fun getPosition(): Long = channel.position()
    override fun setPosition(position: Long) { channel.position(position) }
    override fun close() = channel.close()

    override fun read(buffer: ByteArray, off: Int, count: Int): Int {
        deadline.check()
        return channel.read(ByteBuffer.wrap(buffer, off, count))
    }
}

private class RarReader(path: Path, deadline: Deadline) : ArchiveReader {
    private val archive = try {
        Archive(SingleVolume(path, deadline), ArchiveOptions.builder().maxDictionarySize(ArchiveReaders.MaxDictionaryBytes).build())
    } catch (e: Exception) {
        rethrowRefusal(e)
        refuse(rarReason(e))
    }
    private val headers: List<FileHeader>

    init {
        try {
            if (archive.isPasswordProtected || archive.isEncrypted) refuse(RejectionReason.Encrypted)
            if (archive.hasBrokenHeaders()) refuse(RejectionReason.Corrupt)
            if (archive.mainHeader?.isMultiVolume == true) refuse(RejectionReason.SplitVolume)
            headers = archive.fileHeaders
            headers.firstOrNull { it.isSplitBefore || it.isSplitAfter }?.let { refuse(RejectionReason.SplitVolume, it.fileName) }
        } catch (e: ArchiveRefused) {
            archive.close()
            throw e
        } catch (e: Exception) {
            archive.close()
            rethrowRefusal(e)
            refuse(rarReason(e))
        }
    }

    override val entries: List<ArchiveEntry> = headers.mapIndexed { index, header ->
        val attributes = header.fileAttr
        val link = header.redirection != null ||
            (header.hostOS == HostSystem.unix && attributes and UnixTypeMask == UnixLink) ||
            (header.hostOS != HostSystem.unix && attributes and WindowsReparsePoint != 0)
        val kind = when {
            link -> EntryKind.Link
            header.isDirectory -> EntryKind.Directory
            else -> EntryKind.File
        }
        ArchiveEntry(index, nfc(header.fileName), kind, if (header.isUnpSizeUnknown) null else header.fullUnpackSize, header.isEncrypted)
    }

    override fun decode(open: (Int) -> OutputStream?, drain: () -> OutputStream) {
        headers.forEachIndexed { index, header ->
            if (header.isDirectory) return@forEachIndexed
            (open(index) ?: drain()).use { sink ->
                try {
                    archive.extractFile(header, sink)
                } catch (e: Exception) {
                    rethrowRefusal(e)
                    refuse(rarReason(e), header.fileName)
                }
            }
        }
    }

    override fun close() = archive.close()

    private fun rarReason(e: Throwable): RejectionReason {
        val name = generateSequence(e) { it.cause }.joinToString(" ") { it.javaClass.simpleName }
        return when {
            "Password" in name || "Encrypted" in name || "Decipherer" in name -> RejectionReason.Encrypted
            "DictionarySize" in name -> RejectionReason.DictionaryTooLarge
            "Volume" in name -> RejectionReason.SplitVolume
            "UnsupportedRarMethod" in name || "UnsupportedRarVersion" in name -> RejectionReason.UnsupportedMethod
            "UnsafeLink" in name -> RejectionReason.Link
            else -> RejectionReason.Corrupt
        }
    }
}

private class BareVpkReader(private val path: Path, name: String) : ArchiveReader {
    override val entries: List<ArchiveEntry> = listOf(
        ArchiveEntry(0, nfc(name.substringAfterLast('/').substringAfterLast('\\')), EntryKind.File, Files.size(path), encrypted = false),
    )

    override fun decode(open: (Int) -> OutputStream?, drain: () -> OutputStream) {
        open(0)?.use { sink -> Files.newInputStream(path).use { it.copyTo(sink, Buffer) } }
    }

    override fun close() = Unit
}
