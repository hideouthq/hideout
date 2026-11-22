package gg.hideouthq.library

import gg.hideouthq.core.library.FileImage
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * What each name was last read to hold, kept while the name shows the same file at the same size, modification time and
 * creation time, so a check of a file that has not changed since need not read it again. It answers what a name holds
 * and whether a name may go while its twin keeps the bytes; a name deleted on its bytes alone may be its file's last,
 * so [NioLinks.remove] reads it.
 *
 * A write shows in the modification time by the time the program writing closes the file, which is all Windows
 * promises, so a write still under way, or one whose tool sets the old time back at the same size, is not seen. Linux
 * tells a file put at the name from the one read by its identity and creation time; Windows has no identity here and
 * can give a new file the creation time of the one it replaced, so there only the modification time shows it. A network
 * mount may answer from attributes it cached for some seconds.
 *
 * A reading is kept only once the file's last write is past the coarsest step its file system keeps times in, since
 * until then a second write could carry the same time, and only when nothing changed while it was read. What one name
 * is known to hold passes to another only while the two are one file. Nothing is kept past this run of the app.
 */
internal object KnownBytes {
    private val readings = object : LinkedHashMap<Path, Reading>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Reading>) = size > MaxReadings
    }

    /** What [file], whose attributes are [attributes], holds: known for it, known for [twin], a name proven one file with it, or read. */
    fun of(file: Path, attributes: BasicFileAttributes, twin: Path? = null): FileImage {
        val stamp = Stamp(attributes)
        val image = synchronized(readings) { readings[file] }?.takeIf { it.stamp == stamp }?.image
            ?: twin?.let(::current)?.takeIf { it.stamp == stamp }?.also { remember(file, it) }?.image
            ?: read(file)
        twin?.let { share(file, it) }
        return image
    }

    /** Lets [to], a name proven one file with [from], be known to hold what [from] is, while it shows the same. */
    fun share(from: Path, to: Path) {
        val reading = current(from) ?: return
        if (stampOf(to) == reading.stamp) remember(to, reading)
    }

    fun forget(file: Path) {
        synchronized(readings) { readings.remove(file) }
    }

    /** Reads the whole of [file] for its size and SHA-256, and keeps what it held when that is safe. */
    fun read(file: Path): FileImage {
        val started = Instant.now()
        val stamp = stampOf(file)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(1 shl 16)
            while (channel.read(buffer.clear()) >= 0) {
                digest.update(buffer.array(), 0, buffer.position())
                size += buffer.position()
            }
        }
        val image = FileImage(size, digest.digest().toHexString())
        if (stamp != null && stamp.size == size && stamp.settledBy(started) && stampOf(file) == stamp) remember(file, Reading(stamp, image))
        return image
    }

    private fun current(file: Path): Reading? {
        val reading = synchronized(readings) { readings[file] } ?: return null
        return reading.takeIf { stampOf(file) == it.stamp }
    }

    private fun remember(file: Path, reading: Reading) {
        synchronized(readings) { readings[file] = reading }
    }

    private fun stampOf(file: Path): Stamp? = attributesOrNull(file)?.let(::Stamp)

    private const val MaxReadings = 4_096
}

internal fun measure(file: Path): FileImage = KnownBytes.read(file)

/** A name's file as its attributes show it: its identity where the system gives one, its size, and its modification and creation times. */
private data class Stamp(val file: Any?, val size: Long, val modified: FileTime, val created: FileTime) {
    constructor(attributes: BasicFileAttributes) : this(attributes.fileKey(), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime())

    /**
     * Whether no write from [now] on can be given the modification time this shows. File systems keep times in steps:
     * FAT two seconds, exFAT ten milliseconds, ext4 with small inodes a second, and NTFS, ext4 and btrfs the clock tick,
     * at most 16 ms. A step of ten milliseconds or more leaves every time a whole number of them, so a time that is not
     * comes from a file system whose step is the tick.
     */
    fun settledBy(now: Instant): Boolean {
        val written = modified.toInstant()
        return written + (if (written.nano % 10_000_000 != 0) FineStep else CoarseStep) < now
    }

    private companion object {
        val FineStep: Duration = Duration.ofMillis(100)
        val CoarseStep: Duration = Duration.ofSeconds(3)
    }
}

private class Reading(val stamp: Stamp, val image: FileImage)
