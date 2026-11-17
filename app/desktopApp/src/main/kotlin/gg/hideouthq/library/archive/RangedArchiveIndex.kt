package gg.hideouthq.library.archive

import gg.hideouthq.core.data.ArchiveIndex
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.library.FilePrint
import gg.hideouthq.library.contentRangeStart
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.io.SeekableReadOnlyByteChannel
import com.github.junrar.volume.Volume
import com.github.junrar.volume.VolumeManager
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.util.Collections
import kotlin.time.Duration.Companion.minutes

/** What a file said is kept for the session, since a GameBanana file id's bytes never change. */
class RangedArchiveIndex(private val client: HttpClient) : ArchiveIndex {
    private val known = Collections.synchronizedMap(HashMap<Long, List<FilePrint>?>())

    override suspend fun vpks(file: RemoteFile): List<FilePrint>? {
        if (known.containsKey(file.id)) return known[file.id]
        return withContext(Dispatchers.IO) {
            val job = coroutineContext.job
            val remote = RemoteArchive(client, file, job, Deadline(System.nanoTime() + TimeLimit.inWholeNanoseconds, cancelled = { !job.isActive }))
            val vpks = try {
                list(remote)
            } catch (e: Exception) {
                null
            } catch (e: OutOfMemoryError) {
                null
            }
            when (remote.stopped) {
                null -> vpks
                is Refused -> null
                else -> return@withContext null
            }.also { known[file.id] = it }
        }
    }

    private fun list(remote: RemoteArchive): List<FilePrint>? {
        val head = remote.bytes(0, StartHeader)
        return when {
            head.isZip() -> ZipFile.builder().setSeekableByteChannel(remote).setIgnoreLocalFileHeader(true).get().use { zip ->
                zip.entries.toList().filter { isVpk(it.name) }.map { FilePrint(it.size, it.crc) }
            }
            head.isSevenZ() -> {
                checkPackedHeader(remote, ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN))
                SevenZFile.builder().setSeekableByteChannel(remote).setMaxMemoryLimitKiB(MaxMemoryKiB).get().use { archive ->
                    archive.entries.filter { isVpk(it.name) }.map { FilePrint(it.size, it.crcValue) }
                }
            }
            head.isRar() -> Archive(VolumeManager { archive, last -> if (last == null) remote.volume(archive) else null }, ArchiveOptions.builder().build()).use { rar ->
                if (rar.hasBrokenHeaders()) null else rar.fileHeaders.filter { isVpk(it.fileName) }.map { FilePrint(it.fullUnpackSize, it.fileCRC.toLong() and 0xFFFFFFFFL) }
            }
            else -> null
        }
    }
}

/**
 * Refuses a 7z archive whose header is not within it, or is packed to unpack to more than [MaxHeader] before SevenZFile
 * decodes it whole into memory, whatever size it says. [start] says where the header is, and a packed one says how it
 * was packed in 7zFormat.txt's StreamsInfo, read here as far as 7-Zip writes it for a header: one packed stream with its
 * size, one folder of up to [MaxCoders] simple coders, then each coder's unpacked size.
 */
private fun checkPackedHeader(remote: RemoteArchive, start: ByteBuffer) {
    val offset = start.getLong(12)
    val size = start.getLong(20)
    check(offset in 0..remote.size() - StartHeader && size in 1..remote.size() - StartHeader - offset)
    val at = StartHeader + offset
    if (remote.bytes(at, 1).single() != EncodedHeader) return
    val header = ByteBuffer.wrap(remote.bytes(at + 1, minOf(size, Budget).toInt() - 1))
    check(header.get() == PackInfo)
    header.number()
    check(header.number() == 1L && header.get() == Size)
    header.number()
    check(header.get() == End && header.get() == UnpackInfo && header.get() == Folder && header.number() == 1L && header.get() == Inline)
    val coders = header.number().also { check(it in 1..MaxCoders) }.toInt()
    repeat(coders) {
        val flags = header.get().toInt()
        check(flags and ComplexCoder == 0)
        header.skip((flags and CoderIdSize).toLong())
        if (flags and CoderAttributes != 0) header.skip(header.number())
    }
    repeat(2 * (coders - 1)) { header.number() }
    check(header.get() == CodersUnpackSize)
    repeat(coders) { check(header.number() in 0..MaxHeader) }
}

/** 7-Zip's NUMBER: as many leading ones in the first byte as bytes follow, low first, and the top bits in the rest of it. */
private fun ByteBuffer.number(): Long {
    val first = get().toInt() and 0xFF
    var value = 0L
    for (i in 0 until 8) {
        if (first and (0x80 ushr i) == 0) return value or ((first and (0x7F ushr i)).toLong() shl (8 * i))
        value = value or ((get().toLong() and 0xFF) shl (8 * i))
    }
    return value
}

private fun ByteBuffer.skip(count: Long) {
    check(count in 0..remaining())
    position(position() + count.toInt())
}

/** [stopped] keeps the first failure, so a library that swallows it cannot hide it. */
private class RemoteArchive(private val client: HttpClient, private val file: RemoteFile, private val job: Job, private val deadline: Deadline) :
    RarChannel(), SeekableByteChannel {
    private val windows = mutableListOf<Pair<Long, ByteArray>>()
    private var at = 0L
    private var fetched = 0L
    var stopped: Exception? = null
        private set

    override fun read(dst: ByteBuffer): Int {
        stopped?.let { throw it }
        try {
            deadline.check()
            if (at >= file.sizeBytes) return -1
            val (from, bytes) = windows.firstOrNull { (from, bytes) -> at >= from && at < from + bytes.size } ?: fetch()
            val count = minOf(dst.remaining().toLong(), from + bytes.size - at).toInt()
            dst.put(bytes, (at - from).toInt(), count)
            at += count
            return count
        } catch (e: Exception) {
            stopped = e
            throw e
        }
    }

    private fun fetch(): Pair<Long, ByteArray> {
        val next = windows.map { it.first }.filter { it > at }.minOrNull() ?: file.sizeBytes
        val from = maxOf(0, minOf(at, next - Window))
        val count = minOf(Window.toLong(), file.sizeBytes - from).toInt()
        fetched += count
        if (fetched > Budget) throw Refused()
        return (from to runBlocking(job) { get(from, count) }).also { windows += it }
    }

    /** A reply that is not the part asked for, such as a deleted file's page, is refused unread. */
    private suspend fun get(from: Long, count: Int): ByteArray = client.prepareGet(file.downloadUrl) { header(HttpHeaders.Range, "bytes=$from-${from + count - 1}") }.execute { response ->
        if (response.status.value >= 500 || response.status == HttpStatusCode.TooManyRequests) throw IOException("GameBanana answered ${response.status}")
        val part = response.status == HttpStatusCode.PartialContent && contentRangeStart(response.headers[HttpHeaders.ContentRange]) == from && response.contentLength() == count.toLong()
        if (!part || response.contentType()?.match(ContentType.Text.Html) == true) throw Refused()
        response.readRawBytes()
    }

    fun bytes(from: Long, count: Int): ByteArray {
        val was = at
        at = from
        return ByteArray(count).also { readFully(it, count) }.also { at = was }
    }

    fun volume(archive: Archive): Volume = object : Volume {
        override fun getChannel(): SeekableReadOnlyByteChannel = this@RemoteArchive
        override fun getLength(): Long = file.sizeBytes
        override fun getArchive(): Archive = archive
    }

    override fun read(buffer: ByteArray, off: Int, count: Int): Int = read(ByteBuffer.wrap(buffer, off, count))

    override fun getPosition(): Long = at

    override fun setPosition(position: Long) {
        at = position
    }

    override fun position(): Long = at

    override fun position(newPosition: Long): SeekableByteChannel = apply { at = newPosition }

    override fun size(): Long = file.sizeBytes

    override fun write(src: ByteBuffer): Int = throw NonWritableChannelException()

    override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()

    override fun isOpen(): Boolean = true

    override fun close() = Unit
}

private class Refused : IOException()

private fun isVpk(name: String): Boolean = name.endsWith(".vpk", ignoreCase = true)

/** A ZIP's end record must lie in its last 64 KiB, so one window takes its whole tail. */
private const val Window = 64 * 1024

private const val Budget = 4L * 1024 * 1024

/** 7-Zip packs headers with a 1 MiB dictionary. */
private const val MaxMemoryKiB = 16 * 1024

private const val MaxHeader = 16L * 1024 * 1024

private val TimeLimit = 1.minutes

/** 7-Zip packs a header with LZMA, or with AES then LZMA. */
private const val MaxCoders = 4L

private const val StartHeader = 32

private const val End: Byte = 0x00
private const val PackInfo: Byte = 0x06
private const val UnpackInfo: Byte = 0x07
private const val Size: Byte = 0x09
private const val Folder: Byte = 0x0B
private const val CodersUnpackSize: Byte = 0x0C
private const val EncodedHeader: Byte = 0x17
private const val Inline: Byte = 0x00
private const val CoderIdSize = 0x0F
private const val ComplexCoder = 0x10
private const val CoderAttributes = 0x20
