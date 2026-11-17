package gg.hideouthq.library

import gg.hideouthq.core.data.FileObservation
import gg.hideouthq.core.data.FileRead
import gg.hideouthq.core.data.FileStamp
import gg.hideouthq.core.data.FolderEntry
import gg.hideouthq.core.data.FolderListing
import gg.hideouthq.core.data.GameInventory
import gg.hideouthq.core.data.VpkLook
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.EntryKind
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.Imprint
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.core.library.VpkHead
import gg.hideouthq.core.library.VpkRead
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.CheckedInputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NioGameInventory : GameInventory {
    override suspend fun list(root: GameRoot, folder: String): FolderListing = withContext(Dispatchers.IO) {
        try {
            when (val found = walk(gameFolder(root), SetFolder.GameSegments + folder.split('/').filter { it.isNotEmpty() }, last = Entry.Folder)) {
                is Walk.Found -> FolderListing.Listed(Files.newDirectoryStream(found.path).use { entries -> entries.map(::entryOf) })
                Walk.Missing -> FolderListing.Listed(emptyList())
                is Walk.NotPlain -> FolderListing.Unreadable("${found.path} is a link or not a folder")
            }
        } catch (e: IOException) {
            FolderListing.Unreadable(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun file(root: GameRoot, relativePath: String): FileObservation = withContext(Dispatchers.IO) {
        try {
            when (val file = walk(gameFolder(root), relativePath.split('/'), last = Entry.File)) {
                is Walk.Found -> retryingBusy { measure(file.path) }.let { FileObservation.Present(it.sizeBytes, it.sha256) }
                Walk.Missing, is Walk.NotPlain -> FileObservation.Absent
            }
        } catch (e: NoSuchFileException) {
            FileObservation.Absent
        } catch (e: IOException) {
            FileObservation.Unreadable(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun read(root: GameRoot, path: CitadelPath, maxBytes: Int): FileRead = withContext(Dispatchers.IO) {
        try {
            when (val file = walk(gameFolder(root), SetFolder.GameSegments + path.value.split('/'), last = Entry.File)) {
                is Walk.Found -> retryingBusy { readAtMost(file.path, maxBytes) }?.let { FileRead.Present(it) } ?: FileRead.Unreadable("${path.value} is larger than $maxBytes bytes")
                Walk.Missing -> FileRead.Absent
                is Walk.NotPlain -> {
                    val at = Path.of(root.path).relativize(file.path).invariantSeparatorsPathString
                    if (file.link) FileRead.Unreadable.link(at) else FileRead.Unreadable("$at is not a plain file")
                }
            }
        } catch (e: NoSuchFileException) {
            FileRead.Absent
        } catch (e: IOException) {
            FileRead.Unreadable(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override suspend fun vpk(root: GameRoot, path: CitadelPath): VpkLook = withContext(Dispatchers.IO) {
        try {
            when (val file = walk(gameFolder(root), SetFolder.GameSegments + path.value.split('/'), last = Entry.File)) {
                is Walk.Found -> retryingBusy { lookVpk(file.path) }
                Walk.Missing, is Walk.NotPlain -> VpkLook.Absent
            }
        } catch (e: NoSuchFileException) {
            VpkLook.Absent
        } catch (e: IOException) {
            VpkLook.Unreadable(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun gameFolder(root: GameRoot): Path {
        val path = try {
            Path.of(root.path)
        } catch (e: InvalidPathException) {
            null
        }
        if (path == null || !path.isAbsolute || path.normalize() != path) throw IOException("${root.path} is not an absolute folder path on this computer")
        val folder = try {
            Files.readAttributes(path, BasicFileAttributes::class.java).isDirectory
        } catch (e: NoSuchFileException) {
            false
        }
        if (!folder) throw IOException("the game folder ${root.path} is not there")
        return path
    }

    private fun walk(base: Path, segments: List<String>, last: Entry): Walk {
        var path = base
        segments.forEachIndexed { index, segment ->
            path = path.resolve(segment)
            val attributes = try {
                Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (e: NoSuchFileException) {
                return Walk.Missing
            }
            val expected = if (index == segments.lastIndex) last else Entry.Folder
            val plain = when (expected) {
                Entry.Folder -> attributes.isPlainFolder
                Entry.File -> attributes.isRegularFile
            }
            // A Windows junction reads as a folder that is also "other".
            if (attributes.isSymbolicLink || !plain) return Walk.NotPlain(path, link = attributes.isSymbolicLink || attributes.isOther)
        }
        return Walk.Found(path)
    }

    private fun entryOf(entry: Path): FolderEntry {
        val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val kind = when {
            // A Windows junction reads as a folder that is also "other".
            attributes.isSymbolicLink || attributes.isDirectory && attributes.isOther -> EntryKind.Link
            attributes.isDirectory -> EntryKind.Directory
            attributes.isRegularFile -> EntryKind.File
            else -> EntryKind.Other
        }
        return FolderEntry(entry.fileName.toString(), kind, if (kind == EntryKind.File) FileStamp(attributes.size(), attributes.lastModifiedTime().toMillis()) else null)
    }

    private enum class Entry { Folder, File }

    private sealed interface Walk {
        data class Found(val path: Path) : Walk
        data object Missing : Walk
        data class NotPlain(val path: Path, val link: Boolean) : Walk
    }
}

internal fun lookVpk(file: Path): VpkLook.Present = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
    val total = channel.size()
    val digest = MessageDigest.getInstance("SHA-256")
    val crc = CRC32()
    val input = DigestInputStream(CheckedInputStream(Channels.newInputStream(channel), crc), digest)
    val prefix = input.readNBytes(VpkFormat.PrefixBytes)
    val (head, directory) = when (val length = VpkFormat.headLength(prefix, total)) {
        is VpkHead.NotVpk -> prefix to VpkRead.Unreadable(length.reason)
        is VpkHead.Length -> {
            val head = prefix + input.readNBytes(maxOf(0, length.bytes - prefix.size))
            head to VpkFormat.read(head.copyOf(length.bytes), total)
        }
    }
    val modinfo = VpkFormat.modinfo(head, total)?.takeIf { it.length <= Imprint.MaxBytes }
    var size = head.size.toLong()
    val imprint = modinfo?.let { entry ->
        size += input.pass(entry.at - size)
        val stored = input.readNBytes(entry.length.toInt()).also { size += it.size }
        Imprint.read(entry.inline + stored)
    }
    size += input.transferTo(OutputStream.nullOutputStream())
    VpkLook.Present(FileImage(size, digest.digest().toHexString()), crc.value, directory, imprint)
}

private fun InputStream.pass(count: Long): Long {
    val buffer = ByteArray(1 shl 16)
    var left = count
    while (left > 0) {
        val read = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
        if (read < 0) break
        left -= read
    }
    return count - left
}
