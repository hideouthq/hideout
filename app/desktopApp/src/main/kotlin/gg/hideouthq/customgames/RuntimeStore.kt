package gg.hideouthq.customgames

import gg.hideouthq.OnWindows
import gg.hideouthq.core.customgames.Artifact
import gg.hideouthq.core.customgames.FetchProblem
import gg.hideouthq.core.customgames.InstalledMode
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.customgames.ModeId
import gg.hideouthq.core.customgames.Sha256
import gg.hideouthq.core.customgames.SharedPack
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.customgames.encodeInstalled
import gg.hideouthq.core.customgames.packages
import gg.hideouthq.core.customgames.parseInstalled
import gg.hideouthq.core.library.ArchiveRejection
import gg.hideouthq.core.library.EntryScreening
import gg.hideouthq.core.library.RejectionReason
import gg.hideouthq.core.library.UnpackLimits
import gg.hideouthq.core.library.screenEntries
import gg.hideouthq.library.Blob
import gg.hideouthq.library.BlobFailure
import gg.hideouthq.library.archive.ArchiveBudget
import gg.hideouthq.library.archive.ArchiveDrain
import gg.hideouthq.library.archive.ArchiveReaders
import gg.hideouthq.library.archive.ArchiveRefused
import gg.hideouthq.library.archive.Deadline
import gg.hideouthq.library.deleteTree
import gg.hideouthq.library.holdsBlob
import gg.hideouthq.library.readSmallText
import gg.hideouthq.library.retryingBusy
import gg.hideouthq.library.transferVerified
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal sealed interface RuntimePiece {
    val artifact: Artifact

    data class Program(override val artifact: Artifact, val name: String) : RuntimePiece

    data class Package(override val artifact: Artifact, val runnable: Boolean) : RuntimePiece
}

internal class RuntimeStore(
    private val folder: Path,
    client: () -> HttpClient,
    private val log: (String) -> Unit,
    private val limits: UnpackLimits = UnpackLimits(),
) {
    private val client by lazy(client)
    private val mutex = Mutex()

    fun folderOf(digest: Sha256): Path = folder.resolve(digest.hex)

    fun blobOf(digest: Sha256): Path = folder.resolve("${digest.hex}$BlobSuffix")

    suspend fun placeShared(pack: SharedPack): FetchProblem? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (Files.isDirectory(folderOf(pack.sha256))) null else placed { place(pack.sha256) { blob, staging -> unpack(blob, staging, runnable = false) } }
        }
    }

    suspend fun provide(wanted: List<RuntimePiece>, keep: Set<Sha256>, optional: List<RuntimePiece> = emptyList(), record: Mode? = null, progress: (Transfer) -> Unit): FetchProblem? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pieces = (wanted + optional).distinctBy { it.artifact.sha256 }
            val needed = keep + pieces.map { it.artifact.sha256 }
            removeExcept(needed + records().values.flatMap { it.mode.packages })
            val missing = pieces.filterNot { Files.isDirectory(folderOf(it.artifact.sha256)) }
            val total = missing.sumOf { it.artifact.bytes }
            var before = 0L
            for (piece in missing) {
                val start = before
                val problem = placed { fetchAndPlace(piece) { done -> progress(Transfer(start + done, total)) } }
                if (problem != null) {
                    if (piece in wanted) return@withContext problem
                    log("Custom games: going on without ${piece.artifact.sha256.hex}: $problem")
                }
                before += piece.artifact.bytes
            }
            record?.let { mode -> recorded(mode, needed) }
            null
        }
    }

    suspend fun installed(): Map<ModeId, InstalledMode> = mutex.withLock { withContext(Dispatchers.IO) { records() } }

    suspend fun remove(id: ModeId, keep: Set<Sha256>): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = records()
            val gone = all[id] ?: return@withContext
            val rest = all - id
            writeRecords(rest.values)
            (gone.mode.packages - keep - rest.values.flatMap { it.mode.packages }.toSet()).forEach { removing(folderOf(it)) }
        }
    }

    suspend fun played(id: ModeId, at: Instant): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val all = records()
            val record = all[id] ?: return@withContext
            writeRecords((all + (id to record.copy(lastPlayed = at))).values)
        }
    }

    private fun recorded(mode: Mode, keep: Set<Sha256>) {
        val all = records()
        val packages = mode.packages
        if (packages.isEmpty() || !packages.all { Files.isDirectory(folderOf(it)) }) return
        val bytes = packages.sumOf { sizeOf(folderOf(it)) }
        val rest = all - mode.id
        writeRecords(rest.values + InstalledMode(mode, bytes, all[mode.id]?.lastPlayed))
        val replaced = all[mode.id]?.mode?.packages.orEmpty() - packages - keep - rest.values.flatMap { it.mode.packages }.toSet()
        replaced.forEach { removing(folderOf(it)) }
    }

    private fun records(): Map<ModeId, InstalledMode> = readSmallText(recordFile)?.let(::parseInstalled).orEmpty()

    private fun writeRecords(modes: Collection<InstalledMode>) {
        Files.createDirectories(folder)
        val next = folder.resolve("$RecordFile$PartSuffix")
        Files.writeString(next, encodeInstalled(modes))
        retryingBusy { Files.move(next, recordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    }

    private val recordFile: Path get() = folder.resolve(RecordFile)

    private fun sizeOf(tree: Path): Long = Files.walk(tree).use { files -> files.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum() }

    private suspend fun placed(work: suspend () -> FetchProblem?): FetchProblem? = try {
        work()
    } catch (e: ArchiveRefused) {
        log("Custom games: a package was refused: ${e.message}")
        FetchProblem.Unpack
    } catch (e: IOException) {
        log("Custom games: the runtime folder failed: ${e::class.simpleName}")
        FetchProblem.Disk
    }

    private suspend fun fetchAndPlace(piece: RuntimePiece, onProgress: (Long) -> Unit): FetchProblem? {
        val artifact = piece.artifact
        val digest = artifact.sha256.hex
        val blob = blobOf(artifact.sha256)
        if (!(Files.exists(blob) && holdsBlob(blob, artifact.bytes, digest))) {
            val failure = transferVerified(client, Blob(artifact.url, artifact.bytes, digest), folder.resolve("$digest$PartSuffix"), blob, onProgress)
            if (failure != null) {
                log("Custom games: downloading $digest failed: $failure")
                return failure.problem
            }
        }
        return place(artifact.sha256) { from, staging ->
            when (piece) {
                is RuntimePiece.Program -> {
                    retryingBusy { Files.move(from, staging.resolve(piece.name)) }
                    if (!OnWindows) staging.resolve(piece.name).toFile().setExecutable(true)
                }
                is RuntimePiece.Package -> unpack(from, staging, piece.runnable)
            }
        }
    }

    private suspend fun place(digest: Sha256, fill: suspend (blob: Path, staging: Path) -> Unit): FetchProblem? {
        val blob = blobOf(digest)
        val staging = folder.resolve("${digest.hex}$StagingSuffix")
        deleteTree(staging)
        try {
            Files.createDirectory(staging)
            fill(blob, staging)
            retryingBusy { Files.move(staging, folderOf(digest), StandardCopyOption.ATOMIC_MOVE) }
        } finally {
            deleteTree(staging)
        }
        retryingBusy { Files.deleteIfExists(blob) }
        return null
    }

    private suspend fun unpack(zip: Path, into: Path, runnable: Boolean) {
        val job = currentCoroutineContext().job
        val deadline = Deadline(System.nanoTime() + UnpackLimit.inWholeNanoseconds, cancelled = { !job.isActive })
        try {
            ArchiveReaders.open(zip, zip.fileName.toString(), deadline).use { reader ->
                val accepted = when (val screening = screenEntries(reader.entries, limits)) {
                    is EntryScreening.Rejected -> throw ArchiveRefused(screening.rejection)
                    is EntryScreening.Accepted -> screening
                }
                val targets = accepted.files.associate { it.index to safeTarget(into, it.path) }
                val paths = accepted.files.associate { it.index to it.path }
                val budget = ArchiveBudget(limits.maxTotalBytes, deadline)
                reader.decode(
                    open = { index ->
                        targets[index]?.let { target ->
                            Files.createDirectories(target.parent)
                            BudgetedOutput(BufferedOutputStream(Files.newOutputStream(target)), budget, paths[index])
                        }
                    },
                    drain = { ArchiveDrain(budget) },
                )
                if (runnable && !OnWindows) targets.values.forEach { it.toFile().setExecutable(true) }
            }
        } catch (e: InterruptedIOException) {
            throw CancellationException("unpacking was cancelled")
        }
    }

    private fun removeExcept(keep: Set<Sha256>) {
        val names = keep.map { it.hex }.toSet()
        val entries = folder.toFile().listFiles().orEmpty().map { it.toPath() }
        for (entry in entries) {
            val found = StoreEntry.matchEntire(entry.fileName.toString()) ?: continue
            if (found.groupValues[1] in names && found.groupValues[2] != RemovingSuffix) continue
            removing(entry)
        }
    }

    /** Windows refuses to rename a folder with a file in use, so a folder still in use is left whole, for the next time. */
    private fun removing(entry: Path) {
        if (!Files.exists(entry)) return
        try {
            if (StoreEntry.matchEntire(entry.fileName.toString())?.groupValues?.get(2).isNullOrEmpty()) {
                val removing = entry.resolveSibling(entry.fileName.toString() + RemovingSuffix)
                deleteTree(removing)
                Files.move(entry, removing, StandardCopyOption.ATOMIC_MOVE)
                deleteTree(removing)
            } else {
                deleteTree(entry)
            }
        } catch (e: IOException) {
            log("Custom games: could not remove an old runtime yet: ${e::class.simpleName}")
        }
    }

    private class BudgetedOutput(private val out: OutputStream, private val budget: ArchiveBudget, private val entry: String?) : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            budget.charge(len, entry)
            out.write(b, off, len)
        }

        override fun close() = out.close()
    }

    private companion object {
        const val PartSuffix = ".part"
        const val BlobSuffix = ".blob"
        const val StagingSuffix = ".unpacking"
        const val RemovingSuffix = ".removing"
        const val RecordFile = "installed.json"
        val StoreEntry = Regex("""([0-9a-f]{64})(|\.part|\.blob|\.unpacking|\.removing)""")
        val UnpackLimit = 30.minutes
    }
}

private fun safeTarget(root: Path, path: String): Path {
    val segments = path.split('/')
    if (segments.any { it == ".." || ':' in it }) throw ArchiveRefused(ArchiveRejection(RejectionReason.UnsupportedEntry, path))
    return root.resolve(path).normalize().also {
        if (!it.startsWith(root)) throw ArchiveRefused(ArchiveRejection(RejectionReason.UnsupportedEntry, path))
    }
}

private val BlobFailure.problem: FetchProblem
    get() = when (this) {
        BlobFailure.Server -> FetchProblem.Server
        BlobFailure.Connection -> FetchProblem.Connection
        BlobFailure.Disk -> FetchProblem.Disk
        BlobFailure.Mismatch -> FetchProblem.Mismatch
    }
