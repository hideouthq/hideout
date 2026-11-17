package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.ArchiveIndex
import gg.hideouthq.core.data.DownloadEntry
import gg.hideouthq.core.data.DownloadFolder
import gg.hideouthq.core.data.DownloadProblem
import gg.hideouthq.core.data.DownloadProgress
import gg.hideouthq.core.data.DownloadResult
import gg.hideouthq.core.data.Downloader
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.FolderChoice
import gg.hideouthq.core.data.FolderRefusal
import gg.hideouthq.core.data.GameBananaClient
import gg.hideouthq.core.data.GameProcesses
import gg.hideouthq.core.data.HeroSettingsFiles
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.ModInstallations
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.TwinLinks
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.FilePrint
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstallPorts
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import java.util.zip.CRC32

class FakeGameBanana(var listings: Map<SubmissionRef, FileListing> = emptyMap(), var searches: Map<String, List<SubmissionRef>> = emptyMap()) : GameBananaClient {
    var calls: Int = 0
        private set

    val searched: MutableList<String> = mutableListOf()

    override suspend fun files(submission: SubmissionRef): FileListing {
        calls++
        return listings[submission] ?: FileListing.Unavailable("no answer for ${submission.modId}")
    }

    override suspend fun search(title: String): List<SubmissionRef>? {
        searched += title
        return searches[title].orEmpty()
    }
}

class FakeDownloader(
    private val files: InMemoryGameFiles,
    folder: String = "/data/hideout/downloads",
) : Downloader, TwinLinks<DownloadEntry> by files.archiveLinks {
    private val contents = mutableMapOf<Long, List<Pair<String, ByteArray>>>()
    val results: MutableMap<Long, DownloadResult> = mutableMapOf()
    var gate: CompletableDeferred<Unit>? = null
    val gates: MutableMap<Long, CompletableDeferred<Unit>> = mutableMapOf()
    var held: CompletableDeferred<Unit>? = null
    var discarding: CompletableDeferred<Unit>? = null
    val requested: MutableList<Long> = mutableListOf()

    val unfinished: MutableMap<Triple<String, String, Long>, Long> = mutableMapOf()

    val stalls: MutableSet<Long> = mutableSetOf()

    val resumedFrom: MutableList<Pair<Long, Long>> = mutableListOf()

    var running: Int = 0
        private set
    var mostAtOnce: Int = 0
        private set
    val unavailable: MutableMap<String, FolderRefusal> = mutableMapOf()

    override val defaultFolder: DownloadFolder = DownloadFolder(folder)

    override suspend fun checkFolder(path: String, games: Set<GameRoot>): FolderChoice {
        unavailable[path]?.let { return FolderChoice.Refused(it) }
        val folder = runCatching { DownloadFolder(path) }.getOrNull() ?: return FolderChoice.Refused(FolderRefusal.Missing)
        val inside = games.any { game -> path == game.path || path.startsWith(game.path.trimEnd('/', '\\') + "/") || path.startsWith(game.path.trimEnd('/', '\\') + "\\") }
        return if (inside) FolderChoice.Refused(FolderRefusal.InsideGame) else FolderChoice.Accepted(folder)
    }

    fun serve(fileId: Long, entries: List<Pair<String, ByteArray>>) {
        contents[fileId] = entries
    }

    override suspend fun download(
        folder: DownloadFolder,
        submission: SubmissionRef,
        file: RemoteFile,
        onPublished: (ArchiveIdentity) -> Unit,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        requested += file.id
        mostAtOnce = maxOf(mostAtOnce, ++running)
        try {
            return fetch(folder, submission, file, onPublished, onProgress)
        } finally {
            running--
        }
    }

    override suspend fun discardUnfinished(folder: DownloadFolder, submission: SubmissionRef, fileId: Long) {
        discarding?.await()
        unfinished.remove(Triple(folder.path, submission.modId, fileId))
    }

    private suspend fun fetch(folder: DownloadFolder, submission: SubmissionRef, file: RemoteFile, onPublished: (ArchiveIdentity) -> Unit, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        val key = Triple(folder.path, submission.modId, file.id)
        val from = unfinished[key] ?: 0
        resumedFrom += file.id to from
        val halfway = maxOf(from, file.sizeBytes / 2)
        if (folder.path !in unavailable) unfinished[key] = halfway
        onProgress(DownloadProgress(halfway, file.sizeBytes))
        if (file.id in stalls) onProgress(DownloadProgress(halfway, file.sizeBytes, stalled = true))
        gate?.await()
        gates[file.id]?.await()
        val result = published(folder, submission, file, onPublished, onProgress)
        val kept = (result as? DownloadResult.Failed)?.problem.let { it is DownloadProblem.Network || it is DownloadProblem.Http && it.status >= 500 }
        if (!kept) unfinished.remove(key)
        return result
    }

    private suspend fun published(folder: DownloadFolder, submission: SubmissionRef, file: RemoteFile, onPublished: (ArchiveIdentity) -> Unit, onProgress: (DownloadProgress) -> Unit): DownloadResult {
        results[file.id]?.let { return it }
        if (folder.path in unavailable) return DownloadResult.Failed(DownloadProblem.Disk("${folder.path} is not available"))
        val entries = contents[file.id] ?: return DownloadResult.Failed(DownloadProblem.Http(404))
        val stem = file.fileName.substringBeforeLast('.')
        val extension = file.fileName.removePrefix(stem)
        val separator = if ('\\' in folder.path) "\\" else "/"
        val path = (1..Int.MAX_VALUE).asSequence()
            .map { n -> listOf(folder.path, submission.modId, file.id.toString(), if (n == 1) file.fileName else "$stem ($n)$extension").joinToString(separator) }
            .first { files.archiveFile(it) == null }
        val bytes = entries.flatMap { it.second.asIterable() }.toByteArray()
        val archive = ArchiveIdentity(path, file.sizeBytes.coerceAtLeast(1), sha256Hex(bytes))
        files.putArchive(path, entries, FileImage(archive.sizeBytes, archive.sha256))
        unfinished.remove(Triple(folder.path, submission.modId, file.id))
        onPublished(archive)
        onProgress(DownloadProgress(file.sizeBytes, file.sizeBytes))
        held?.await()
        return DownloadResult.Done(archive)
    }
}

class FakeProcesses(var scan: ProcessScan = ProcessScan.Seen()) : GameProcesses {
    var gate: CompletableDeferred<Unit>? = null
    var holdNext: CompletableDeferred<Unit>? = null
    var failure: String? = null
    var gamesEnded = 0

    override suspend fun scan(): ProcessScan {
        gate?.await()
        holdNext?.also { holdNext = null }?.await()
        failure?.let { throw IllegalStateException(it) }
        return scan
    }

    override suspend fun endGame() {
        gamesEnded++
    }
}

object FakeVpk {
    private const val EmbeddedIndex = 0x7FFF

    fun directory(embeddedBytes: Int = 16, chunkReads: Map<Int, Long> = emptyMap(), seed: Int = 0): ByteArray = with(Tree()) {
        string("vtex_c")
        string("materials")
        string("embedded$seed")
        u32(0); u16(0); u16(EmbeddedIndex); u32(0); u32(embeddedBytes.toLong()); u16(0xFFFF)
        chunkReads.forEach { (index, bytes) ->
            string("chunk$index")
            u32(0); u16(0); u16(index); u32(0); u32(bytes); u16(0xFFFF)
        }
        byte(0); byte(0); byte(0)
        vpk(ByteArray(embeddedBytes) { (it + seed).toByte() })
    }

    fun withRootFile(name: String, contents: ByteArray, preload: Boolean = false, seed: Int = 0): ByteArray = with(Tree()) {
        val resource = ByteArray(16) { (it + seed).toByte() }
        string("vtex_c"); string("materials"); string("embedded$seed")
        u32(0); u16(0); u16(EmbeddedIndex); u32(0); u32(resource.size.toLong()); u16(0xFFFF)
        byte(0); byte(0)
        string(name.substringAfterLast('.')); string(" "); string(name.substringBeforeLast('.'))
        if (preload) {
            u32(0); u16(contents.size); u16(EmbeddedIndex); u32(0); u32(0); u16(0xFFFF)
            contents.forEach { bytes += it }
        } else {
            u32(0); u16(0); u16(EmbeddedIndex); u32(resource.size.toLong()); u32(contents.size.toLong()); u16(0xFFFF)
        }
        byte(0); byte(0); byte(0)
        vpk(if (preload) resource else resource + contents)
    }

    fun of(files: Map<String, ByteArray>): ByteArray = with(Tree()) {
        val data = mutableListOf<ByteArray>()
        var offset = 0L
        files.entries.groupBy { it.key.substringAfterLast('.', " ") }.forEach { (extension, byExtension) ->
            string(extension)
            byExtension.groupBy { it.key.substringBeforeLast('/', " ") }.forEach { (folder, inFolder) ->
                string(folder)
                inFolder.forEach { (path, bytes) ->
                    string(path.substringAfterLast('/').let { if (extension == " ") it else it.substringBeforeLast('.') })
                    u32(CRC32().apply { update(bytes) }.value); u16(0); u16(EmbeddedIndex); u32(offset); u32(bytes.size.toLong()); u16(0xFFFF)
                    data += bytes
                    offset += bytes.size
                }
                byte(0)
            }
            byte(0)
        }
        byte(0)
        vpk(data.fold(ByteArray(0)) { all, bytes -> all + bytes })
    }

    private class Tree {
        val bytes = mutableListOf<Byte>()
        fun byte(value: Int) { bytes += value.toByte() }
        fun u16(value: Int) = repeat(2) { byte((value shr (8 * it)) and 0xFF) }
        fun u32(value: Long) = repeat(4) { byte(((value shr (8 * it)) and 0xFF).toInt()) }
        fun string(text: String) { text.encodeToByteArray().forEach { bytes += it }; byte(0) }

        fun vpk(data: ByteArray): ByteArray {
            val header = listOf(0x55AA1234L, 2L, bytes.size.toLong(), data.size.toLong(), 0L, 0L, 0L).flatMap { value -> List(4) { (value shr (8 * it)).toByte() } }
            return (header + bytes).toByteArray() + data
        }
    }
}

fun printOf(bytes: ByteArray): FilePrint = FilePrint(bytes.size.toLong(), CRC32().apply { update(bytes) }.value)

fun InMemoryGameFiles.ports(
    gameBanana: GameBananaClient = FakeGameBanana(),
    processes: GameProcesses = FakeProcesses(),
    archives: ArchiveIndex = ArchiveIndex.Unavailable,
    heroSettings: HeroSettingsFiles = HeroSettingsFiles.Unavailable,
): InstallPorts = InstallPorts(gameBanana, FakeDownloader(this), this, this, processes, gameFiles = gameFiles, archives = archives, backups = backups, heroSettings = heroSettings)

class FixedInstallations(private val status: Flow<InstallStatus>) : ModInstallations {
    val installs: MutableList<String> = mutableListOf()
    val cancels: MutableList<String> = mutableListOf()

    override fun status(modId: String): Flow<InstallStatus> = status

    override fun install(modId: String) {
        installs += modId
    }

    override fun cancel(modId: String): Boolean {
        cancels += modId
        return true
    }

    override fun dismiss(modId: String) = Unit
}
