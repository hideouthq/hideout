package gg.hideouthq.library.compat

import gg.hideouthq.core.library.FileImage
import gg.hideouthq.preview.sha256Hex
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant

internal class GamePackages private constructor(private val directory: GameDirectory, private val log: ReadLog?) : AutoCloseable {
    private val chunks = HashMap<Int, ByteSource>()

    val identity: String get() = directory.identity

    init {
        log?.whole(directory.file, null, directory.image)
    }

    fun resource(path: String): ByteArray? {
        val entry = directory.entry(path.lowercase()) ?: return null
        return VpkPackages.payload(directory.pack, entry, directory.dir, ::chunk, ModelRepairs.MaxResourceBytes)
    }

    @Synchronized
    private fun chunk(index: Int): ByteSource? = chunks[index] ?: run {
        if (index !in 0..999) return null
        val file = directory.file.resolveSibling("pak01_${index.toString().padStart(3, '0')}.vpk")
        val opened = try {
            log?.open(file, installId = null) ?: FileSource.open(file)
        } catch (e: NoSuchFileException) {
            return null
        }
        opened.also { chunks[index] = it }
    }

    @Synchronized
    override fun close() {
        chunks.values.forEach { it.close() }
    }

    companion object {
        /** Parses the tree now, so a directory that cannot be parsed fails the opening rather than each look into it. */
        fun open(root: Path, directories: GameDirectories, log: ReadLog?): GamePackages = GamePackages(directories.of(root).also { it.pack }, log)
    }
}

/** The game's `pak01_dir.vpk` as read once: its bytes name the game, and its tree is parsed only when a check needs it. */
internal class GameDirectory(val file: Path, bytes: ByteArray, val sha256: String) {
    val dir: ByteSource = ArraySource(bytes)
    val image = FileImage(bytes.size.toLong(), sha256)
    val identity = "pak01:$sha256"
    private val tree by lazy { VpkPackages.read(dir).let { it to PathIndex(it.entries) } }
    val pack: VpkPackage get() = tree.first

    /** The one entry named [path], or null when none or several are. */
    fun entry(path: String): VpkEntry? = tree.second.single(path)
}

/** Entries ordered by their path's hash, each key the hash above the entry's index; a map of every path would hold tens of megabytes of names. */
private class PathIndex(private val entries: List<VpkEntry>) {
    private val keys = LongArray(entries.size) { (entries[it].path.hashCode().toLong() shl 32) or it.toLong() }.apply { sort() }

    fun single(path: String): VpkEntry? {
        val hash = path.hashCode()
        var at = keys.binarySearch(hash.toLong() shl 32).let { if (it < 0) -it - 1 else it }
        var found: VpkEntry? = null
        while (at < keys.size && (keys[at] shr 32).toInt() == hash) {
            val entry = entries[keys[at].toInt()]
            if (entry.path == path) {
                if (found != null) return null
                found = entry
            }
            at++
        }
        return found
    }
}

/**
 * Keeps the last directory read, since every check and every look at which game this is would otherwise read and parse
 * all of it. It is read again unless its size, times and file are as they were, and only where the system keeps a change
 * time nothing can set back; elsewhere the bytes are read and hashed each time and only the parsed tree is kept.
 */
internal class GameDirectories(private val now: () -> Instant = Instant::now) {
    @Volatile
    private var held: Held? = null

    private class Held(val stamp: Stamp?, val directory: GameDirectory)

    private data class Stamp(val size: Long, val modified: FileTime, val changed: FileTime, val device: Any?, val inode: Any?)

    fun of(root: Path): GameDirectory {
        val file = root.resolve("game").resolve("citadel").resolve("pak01_dir.vpk")
        val last = held?.takeIf { it.directory.file == file }
        val before = stamp(file)
        if (before != null && last?.stamp == before) return last.directory
        val started = now()
        val bytes = try {
            readWhole(file, MaxDirectoryBytes)
        } catch (e: NoSuchFileException) {
            throw IOException("the game's pak01_dir.vpk is not there")
        }
        val sha256 = sha256Hex(bytes)
        val directory = last?.directory?.takeIf { it.sha256 == sha256 } ?: GameDirectory(file, bytes, sha256)
        val settled = before?.takeIf { it == stamp(file) && Duration.between(maxOf(it.modified, it.changed).toInstant(), started) >= Settled }
        held = Held(settled, directory)
        return directory
    }

    private fun stamp(file: Path): Stamp? {
        if ("unix" !in file.fileSystem.supportedFileAttributeViews()) return null
        val attributes = try {
            Files.readAttributes(file, "unix:size,lastModifiedTime,ctime,dev,ino", LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            return null
        }
        return Stamp(attributes["size"] as Long, attributes["lastModifiedTime"] as FileTime, attributes["ctime"] as FileTime, attributes["dev"], attributes["ino"])
    }

    private companion object {
        const val MaxDirectoryBytes = 512L * 1024 * 1024

        /** A file changed this recently could change again within the same tick of its clock, FAT's two seconds among them, and look the same. */
        val Settled: Duration = Duration.ofSeconds(2)

        fun readWhole(file: Path, limit: Long): ByteArray = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size > limit) throw IOException("$file is larger than it can be")
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw IOException("$file ended early")
            if (channel.read(ByteBuffer.allocate(1)) >= 0) throw IOException("$file grew while it was read")
            buffer.array()
        }
    }
}
