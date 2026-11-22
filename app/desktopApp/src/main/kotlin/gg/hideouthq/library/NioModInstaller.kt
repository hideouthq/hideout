package gg.hideouthq.library

import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.ModInstaller
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.StageResult
import gg.hideouthq.core.data.UnpackResult
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ArchiveNote
import gg.hideouthq.core.library.ArchiveRejection
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.RejectionReason
import gg.hideouthq.core.library.Screening
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.StagedRef
import gg.hideouthq.core.library.StagedVpk
import gg.hideouthq.core.library.StagingId
import gg.hideouthq.core.library.TemporaryInfix
import gg.hideouthq.core.library.UnpackLimits
import gg.hideouthq.core.library.VpkFormat
import gg.hideouthq.core.library.VpkHead
import gg.hideouthq.core.library.admits
import gg.hideouthq.core.library.admitsTemporary
import gg.hideouthq.core.library.noteText
import gg.hideouthq.core.library.screenArchive
import gg.hideouthq.library.archive.ArchiveBudget
import gg.hideouthq.library.archive.ArchiveDrain
import gg.hideouthq.library.archive.ArchiveReaders
import gg.hideouthq.library.archive.ArchiveRefused
import gg.hideouthq.library.archive.Deadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class NioModInstaller(
    private val stagingRoot: Path,
    private val timeLimit: Duration = 30.minutes,
    private val clock: () -> Long = System::nanoTime,
    override val linker: Linker = Linker.System,
) : NioTwinLinks<GameEntry>(), ModInstaller {
    override suspend fun unpack(archive: ArchiveIdentity, limits: UnpackLimits, originalName: String): UnpackResult {
        var filled: StagingId? = null
        try {
            return extract(archive, originalName, limits) { filled = it }
        } catch (e: CancellationException) {
            // A caller cancelled as extraction ends never receives the result, so the folder it would have owned goes here.
            filled?.let { withContext(NonCancellable) { discard(it) } }
            throw e
        }
    }

    private suspend fun extract(archive: ArchiveIdentity, name: String, limits: UnpackLimits, made: (StagingId) -> Unit): UnpackResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val deadline = Deadline(clock() + timeLimit.inWholeNanoseconds, cancelled = { job?.isActive == false }, now = clock)
        val reader = try {
            ArchiveReaders.open(Path.of(archive.path), name, deadline)
        } catch (e: InterruptedIOException) {
            throw CancellationException("unpacking was cancelled")
        } catch (e: ArchiveRefused) {
            return@withContext UnpackResult.Rejected(e.rejection)
        } catch (e: IOException) {
            return@withContext UnpackResult.Failed("could not open $name: ${e.message}")
        }
        reader.use {
            val screened = when (val screening = screenArchive(reader.entries, limits)) {
                is Screening.Rejected -> return@withContext UnpackResult.Rejected(screening.rejection)
                is Screening.Accepted -> screening
            }
            val needed = screened.vpks.sumOf { it.declaredBytes ?: 0 }
            Files.createDirectories(stagingRoot)
            if (Files.getFileStore(stagingRoot).usableSpace < needed) return@withContext UnpackResult.Failed("there is not enough free space to unpack $name")
            val id = StagingId(UUID.randomUUID().toString())
            val folder = Files.createDirectory(stagingRoot.resolve(id.value))
            made(id)
            val budget = ArchiveBudget(limits.maxTotalBytes, deadline)
            val wanted = screened.vpks.associateBy { it.index }
            val noted = screened.notes.associateBy { it.index }
            val written = mutableMapOf<Int, StagingSink>()
            val read = mutableMapOf<Int, NoteSink>()
            try {
                reader.decode(
                    open = { index ->
                        wanted[index]?.let { vpk ->
                            StagingSink(folder.resolve("$index.vpk"), budget, vpk.path, vpk.declaredBytes ?: limits.maxVpkBytes).also { written[index] = it }
                        } ?: noted[index]?.let { NoteSink(budget, limits.maxNoteBytes).also { sink -> read[index] = sink } }
                    },
                    drain = { ArchiveDrain(budget) },
                )
                if (deadline.passed) throw ArchiveRefused(ArchiveRejection(RejectionReason.TimedOut, null))
                val staged = screened.vpks.map { vpk ->
                    val sink = written[vpk.index] ?: throw ArchiveRefused(ArchiveRejection(RejectionReason.Corrupt, vpk.path))
                    if (vpk.declaredBytes != null && sink.size != vpk.declaredBytes) throw ArchiveRefused(ArchiveRejection(RejectionReason.SizeMismatch, vpk.path))
                    val head = headOf(folder.resolve("${vpk.index}.vpk"), sink.size)
                    StagedVpk(StagedRef(id, vpk.index), vpk.path, sink.size, sink.sha256, VpkFormat.read(head, sink.size), VpkFormat.resources(head, sink.size))
                }
                val notes = screened.notes.mapNotNull { note -> read[note.index]?.let { noteText(it.bytes(), it.whole) }?.let { ArchiveNote(note.path, it) } }
                UnpackResult.Unpacked(id, staged, notes)
            } catch (e: Exception) {
                deleteTree(folder)
                val refused = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<ArchiveRefused>().firstOrNull()?.rejection
                when {
                    refused != null -> UnpackResult.Rejected(refused)
                    e is InterruptedIOException -> throw CancellationException("unpacking was cancelled")
                    else -> UnpackResult.Failed("could not unpack $name: ${e.message}")
                }
            }
        }
    }

    override suspend fun discard(staging: StagingId) = withContext(Dispatchers.IO) {
        if (StagingName.matches(staging.value)) deleteTree(stagingRoot.resolve(staging.value))
    }

    override suspend fun discardAllStaging() = withContext(Dispatchers.IO) {
        val attributes = attributesOrNull(stagingRoot) ?: return@withContext
        if (!attributes.isDirectory || attributes.isSymbolicLink) return@withContext
        Files.newDirectoryStream(stagingRoot).use { entries -> entries.toList() }.forEach(::deleteTree)
    }

    override suspend fun tidy(root: GameRoot) = withContext(Dispatchers.IO) {
        val citadel = Path.of(root.path, "game", "citadel")
        for (own in listOf(CitadelPath.HideoutOff, CitadelPath.HideoutBackups)) removeEmpty(citadel.resolve(own.value))
    }

    override suspend fun stage(root: GameRoot, folder: SetFolder, vpk: StagedVpk, temporary: String): StageResult = withContext(Dispatchers.IO) {
        if (!folder.admitsTemporary(temporary)) return@withContext StageResult.Failed("$temporary is not a temporary name Hideout makes")
        val source = staged(vpk.ref) ?: return@withContext StageResult.Failed("the extracted ${vpk.path} is gone")
        val placed = try {
            managedFolder(root, folder, create = true)
        } catch (e: IOException) {
            return@withContext StageResult.Failed(e.message ?: "${folder.segments.last()} is not usable")
        }
        val target = placed.resolve(temporary)
        try {
            copyNew(source, target)
        } catch (e: FileAlreadyExistsException) {
            return@withContext StageResult.Failed("$temporary already exists")
        } catch (e: IOException) {
            return@withContext StageResult.Failed("could not copy ${vpk.path} into ${folder.segments.last()}: ${e.message}")
        }
        try {
            StageResult.Written(KnownBytes.readWritten(target))
        } catch (e: IOException) {
            StageResult.Failed("could not read back $temporary: ${e.message}")
        }
    }

    override suspend fun publish(root: GameRoot, folder: SetFolder, temporary: String, name: String): PublishResult = withContext(Dispatchers.IO) {
        if (!folder.admitsTemporary(temporary) || !folder.admits(name) || !temporary.startsWith(name + TemporaryInfix)) {
            return@withContext PublishResult.Failed("$temporary cannot become $name")
        }
        try {
            val placed = managedFolder(root, folder, create = false)
            linker.link(placed.resolve(temporary), placed.resolve(name)).also { if (it == PublishResult.Published) syncFolderIfSupported(placed) }
        } catch (e: IOException) {
            PublishResult.Failed(e.message ?: "could not publish $name")
        }
    }

    override suspend fun sameFile(root: GameRoot, folder: SetFolder, temporary: String, name: String): Boolean = withContext(Dispatchers.IO) {
        require(folder.admitsTemporary(temporary) && folder.admits(name)) { "not names Hideout makes" }
        val placed = managedFolder(root, folder, create = false)
        linker.same(placed.resolve(temporary), placed.resolve(name)).also { if (it) KnownBytes.share(placed.resolve(temporary), placed.resolve(name)) }
    }

    override fun emptied(entry: GameEntry, file: Path) {
        if (entry.folder is SetFolder.Off) retryingBusy { Files.delete(file.parent) }
    }

    override fun path(entry: GameEntry, create: Boolean): Path = entryPath(entry, create)

    override fun nameOf(entry: GameEntry) = entry.name

    private fun staged(ref: StagedRef): Path? {
        if (!StagingName.matches(ref.staging.value) || ref.index < 0) return null
        val file = stagingRoot.resolve(ref.staging.value).resolve("${ref.index}.vpk")
        return file.takeIf { attributesOrNull(it)?.isRegularFile == true }
    }

    private class NoteSink(private val budget: ArchiveBudget, private val keep: Int) : OutputStream() {
        private val kept = ByteArrayOutputStream()
        var whole = true
            private set

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            budget.charge(len, null)
            val room = keep - kept.size()
            if (len > room) whole = false
            kept.write(b, off, minOf(len, room))
        }

        fun bytes(): ByteArray = kept.toByteArray()
    }

    private class StagingSink(file: Path, private val budget: ArchiveBudget, private val entry: String, private val limit: Long) : OutputStream() {
        private val channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        private val digest = sha256()
        private var hash: String? = null
        var size = 0L
            private set
        val sha256: String get() = hash ?: error("the entry is still being written")

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            budget.charge(len, entry)
            if (len > limit - size) throw ArchiveRefused(ArchiveRejection(RejectionReason.SizeMismatch, entry))
            digest.update(b, off, len)
            val buffer = ByteBuffer.wrap(b, off, len)
            while (buffer.hasRemaining()) channel.write(buffer)
            size += len
        }

        override fun close() {
            if (hash != null) return
            channel.close()
            hash = digest.digest().toHexString()
        }
    }
}

internal fun entryPath(entry: GameEntry, create: Boolean): Path {
    if (!entry.folder.admits(entry.name) && !entry.folder.admitsTemporary(entry.name)) throw IOException("${entry.name} is not a name Hideout makes")
    return managedFolder(entry.root, entry.folder, create).resolve(entry.name)
}

internal val StagingName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

internal fun headOf(file: Path, size: Long): ByteArray = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
    val input = Channels.newInputStream(channel)
    val prefix = input.readNBytes(VpkFormat.PrefixBytes)
    when (val head = VpkFormat.headLength(prefix, size)) {
        is VpkHead.NotVpk -> prefix
        is VpkHead.Length -> if (head.bytes <= prefix.size) prefix.copyOf(head.bytes) else prefix + input.readNBytes(head.bytes - prefix.size)
    }
}

private fun removeEmpty(folder: Path): Boolean {
    val attributes = attributesOrNull(folder) ?: return true
    if (!attributes.isDirectory || attributes.isSymbolicLink || attributes.isOther) return false
    val emptied = try {
        Files.newDirectoryStream(folder).use { entries -> entries.toList() }.map(::removeEmpty).all { it }
    } catch (_: IOException) {
        false
    }
    if (!emptied) return false
    return try {
        Files.deleteIfExists(folder)
        true
    } catch (_: IOException) {
        false
    }
}

internal fun attributesOrNull(path: Path): BasicFileAttributes? = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
} catch (e: NoSuchFileException) {
    null
}

internal fun readAtMost(file: Path, maxBytes: Int): ByteArray? =
    Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { Channels.newInputStream(it).readNBytes(maxBytes + 1) }.takeIf { it.size <= maxBytes }

internal fun deleteTree(path: Path) = retryingBusy { deleteEach(path) }

private fun deleteEach(path: Path) {
    val attributes = attributesOrNull(path) ?: return
    if (attributes.isDirectory && !attributes.isSymbolicLink) {
        Files.newDirectoryStream(path).use { entries -> entries.toList() }.forEach(::deleteEach)
    }
    Files.deleteIfExists(path)
}

internal fun writeDurably(file: Path, bytes: ByteArray, vararg options: OpenOption) {
    val channel = FileChannel.open(file, StandardOpenOption.WRITE, *options)
    try {
        channel.use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            it.force(true)
        }
    } catch (e: IOException) {
        runCatching { Files.deleteIfExists(file) }
        throw e
    }
}

internal fun copyNew(from: Path, to: Path) {
    val out = FileChannel.open(to, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    try {
        out.use {
            FileChannel.open(from, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                var position = 0L
                val size = input.size()
                while (position < size) position += input.transferTo(position, size - position, it)
            }
            it.force(true)
        }
    } catch (e: IOException) {
        runCatching { Files.deleteIfExists(to) }
        throw e
    }
}

internal fun replaceAtomically(file: Path, bytes: ByteArray) {
    val temporary = Files.createTempFile(file.parent, "${file.fileName}.", ".tmp")
    writeDurably(temporary, bytes)
    try {
        retryingBusy { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
    } catch (e: IOException) {
        Files.deleteIfExists(temporary)
        throw e
    }
}

internal fun syncFolderIfSupported(folder: Path) {
    if (folder.fileSystem.supportedFileAttributeViews().contains("posix")) FileChannel.open(folder, StandardOpenOption.READ).use { it.force(true) }
}

