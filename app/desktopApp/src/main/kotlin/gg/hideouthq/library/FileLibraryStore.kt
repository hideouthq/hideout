package gg.hideouthq.library

import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.ManifestSave
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.ManifestCodec
import gg.hideouthq.core.library.ManifestDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

class FileLibraryStore internal constructor(private val directory: Path, private var held: FolderLock?) : LibraryStore, AutoCloseable {
    constructor(directory: Path) : this(directory, null)

    private val primary get() = directory.resolve(FileName)

    override suspend fun load(): ManifestLoad = withContext(Dispatchers.IO) {
        acquireLock()?.let { return@withContext ManifestLoad.Unreadable(it) }
        readManifest(primary)
    }

    override suspend fun save(manifest: LibraryManifest): ManifestSave = withContext(Dispatchers.IO) {
        acquireLock()?.let { return@withContext ManifestSave.Refused(it) }
        val bytes = manifestBytes(manifest) ?: return@withContext ManifestSave.Refused(ManifestTooLarge)
        (readManifest(primary) as? ManifestLoad.Unreadable)?.let { return@withContext ManifestSave.Refused(it.reason) }
        try {
            replaceAtomically(primary, bytes)
            ManifestSave.Saved
        } catch (e: IOException) {
            ManifestSave.Refused("could not write $primary: ${e.message ?: e::class.simpleName}")
        }
    }

    @Synchronized
    override fun close() {
        held?.close()
        held = null
    }

    @Synchronized
    private fun acquireLock(): String? {
        if (held != null) return null
        held = try {
            FolderLock.take(directory)
        } catch (e: IOException) {
            return "could not lock $directory: ${e.message ?: e::class.simpleName}"
        }
        return if (held == null) "another Hideout is already using $directory" else null
    }

    private companion object {
        const val FileName = "library.json"
    }
}

internal fun readManifest(file: Path): ManifestLoad {
    val text = try {
        val attributes = attributesOrNull(file)
        when {
            attributes == null -> return ManifestLoad.Absent
            attributes.isSymbolicLink -> return ManifestLoad.Unreadable("$file is a link")
            !attributes.isRegularFile -> return ManifestLoad.Unreadable("$file is not a regular file")
        }
        val bytes = readAtMost(file, ManifestCodec.MaxBytes) ?: return ManifestLoad.Unreadable("$file is larger than ${ManifestCodec.MaxBytes} bytes")
        bytes.decodeToString(throwOnInvalidSequence = true)
    } catch (e: CharacterCodingException) {
        return ManifestLoad.Unreadable("$file is not UTF-8 text")
    } catch (e: IOException) {
        return ManifestLoad.Unreadable("could not read $file: ${e.message ?: e::class.simpleName}")
    }
    return when (val decoded = ManifestCodec.decode(text)) {
        is ManifestDecode.Ok -> ManifestLoad.Loaded(decoded.manifest)
        is ManifestDecode.Unsupported -> ManifestLoad.Unreadable("schema version ${decoded.schemaVersion} is not supported")
        is ManifestDecode.Malformed -> ManifestLoad.Unreadable(decoded.reason)
    }
}

internal fun manifestBytes(manifest: LibraryManifest): ByteArray? = ManifestCodec.encode(manifest).encodeToByteArray().takeIf { it.size <= ManifestCodec.MaxBytes }

internal const val ManifestTooLarge = "the install list would be larger than ${ManifestCodec.MaxBytes} bytes"

/** Closing any channel to a locked file can release the lock, so a second holder in this process is refused first. */
internal class FolderLock private constructor(private val folder: Path, private val channel: FileChannel) : AutoCloseable {
    override fun close() {
        channel.close()
        heldHere.remove(folder)
    }

    companion object {
        private const val FileName = "hideout.lock"
        private val heldHere: MutableSet<Path> = ConcurrentHashMap.newKeySet()

        fun take(folder: Path): FolderLock? {
            Files.createDirectories(folder)
            val real = folder.toRealPath()
            if (!heldHere.add(real)) return null
            var taken: FolderLock? = null
            try {
                val channel = FileChannel.open(real.resolve(FileName), StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                try {
                    if (channel.tryLock() != null) taken = FolderLock(real, channel)
                } finally {
                    if (taken == null) channel.close()
                }
            } finally {
                if (taken == null) heldHere.remove(real)
            }
            return taken
        }
    }
}
