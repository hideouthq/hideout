package gg.hideouthq.library

import gg.hideouthq.core.library.FileImage
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

/**
 * What each name was last read to hold, kept while the name shows the same file at the same size, modification time and
 * creation time, so a check of a file that has not changed since need not read it again. It answers what a name holds
 * and whether a name may go while its twin keeps the bytes; a name deleted on its bytes alone may be its file's last,
 * so [NioLinks.remove] reads it.
 *
 * A write shows in the modification time by the time the program writing closes the file, which is all Windows
 * promises, so a write still under way, or one whose tool sets the old time back at the same size, is not seen. A file
 * put at the name in place of the one read shows by its identity, the inode on Linux and the volume and file id on
 * Windows, even when it keeps the old one's size and times, as a copy over it or Windows' tunneling of a deleted name's
 * creation time can. Where the identity is unknown, only the times show it. A network mount may answer from attributes
 * it cached for some seconds.
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
        val stamp = Stamp(file, attributes)
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

    /**
     * Reads [file], which Hideout has just written, once its modified time is set back further than the coarsest step a
     * file system keeps times in. The reading is then kept although the file is new, since a write from then on gives
     * it a later time, and the read sees every write before. Where the time can't be set, it is kept only as [read] keeps one.
     */
    fun readWritten(file: Path): FileImage {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now() - CoarseStep - CoarseStep))
        } catch (e: IOException) {
        }
        return read(file)
    }

    /** Reads the whole of [file] for its size and SHA-256, and keeps what it held when that is safe. */
    fun read(file: Path): FileImage {
        val started = Instant.now()
        val stamp = stampOf(file)
        val digest = sha256()
        val size = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { it.digestRest(digest) }
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

    private fun stampOf(file: Path): Stamp? = attributesOrNull(file)?.let { Stamp(file, it) }

    private const val MaxReadings = 4_096
}

internal fun measure(file: Path): FileImage = KnownBytes.read(file)

/** A name's file as [attributes] show it: its [fileIdentity] where known, its size, and its modification and creation times. */
private data class Stamp(val file: Any?, val size: Long, val modified: FileTime, val created: FileTime) {
    constructor(name: Path, attributes: BasicFileAttributes) : this(fileIdentity(name, attributes), attributes.size(), attributes.lastModifiedTime(), attributes.creationTime())

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
}

private val FineStep: Duration = Duration.ofMillis(100)
private val CoarseStep: Duration = Duration.ofSeconds(3)

private class Reading(val stamp: Stamp, val image: FileImage)
