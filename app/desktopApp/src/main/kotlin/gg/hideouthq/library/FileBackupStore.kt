package gg.hideouthq.library

import gg.hideouthq.core.data.BackupStore
import gg.hideouthq.core.data.GameEntry
import gg.hideouthq.core.data.HeldFile
import gg.hideouthq.core.data.ManifestLoad
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.data.PlaceResult
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.library.BackupCodec
import gg.hideouthq.core.library.BackupId
import gg.hideouthq.core.library.BackupInfo
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.VpkPart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlin.time.Instant
import kotlin.time.toKotlinInstant

class FileBackupStore(private val originals: Path, createLink: (link: Path, existing: Path) -> Unit = Linker.HardLink) : BackupStore {
    private val linker = Linker(createLink)

    override suspend fun list(root: GameRoot): List<BackupInfo> = withContext(Dispatchers.IO) {
        val store = try {
            gameFolder(root, StoreSegments)
        } catch (e: NoSuchFileException) {
            return@withContext emptyList()
        }
        Files.newDirectoryStream(store).use { it.toList() }.mapNotNull { folder ->
            val id = BackupId.parse(folder.fileName.toString())?.takeIf { it.value !in OwnFolders && attributesOrNull(folder)?.isPlainFolder == true } ?: return@mapNotNull null
            val info = folder.resolve(InfoName).takeIf { attributesOrNull(it)?.isRegularFile == true } ?: return@mapNotNull null
            readAtMost(info, MaxInfoBytes)?.let { BackupCodec.decode(id, it.decodeToString()) }
        }
    }

    override suspend fun read(root: GameRoot, id: BackupId): LibraryManifest = withContext(Dispatchers.IO) {
        when (val loaded = readManifest(gameFolder(root, StoreSegments + id.value).resolve(LibraryName))) {
            is ManifestLoad.Loaded -> loaded.manifest
            ManifestLoad.Absent -> throw IOException("its install list is missing")
            is ManifestLoad.Unreadable -> throw IOException(loaded.reason)
        }
    }

    override suspend fun keep(entry: GameEntry, image: FileImage, modifiedAt: Instant?): Unit = withContext(Dispatchers.IO) {
        val file = try {
            entryPath(entry, create = false)
        } catch (e: IOException) {
            return@withContext
        }
        if (attributesOrNull(file)?.isRegularFile != true) return@withContext
        val files = gameFolder(entry.root, FilesSegments, create = true)
        val stored = files.resolve(image.sha256)
        if (!twins(stored, file)) {
            // Windows can keep a stale modified time for another name of a file, so the time is read through the game's name.
            val holding = { incoming: Path -> if (modifiedAt == null) measure(incoming) == image else twins(incoming, file) && unmodified(file, image, modifiedAt) }
            add(files, image, holding) { incoming ->
                when (val linked = linker.link(file, incoming)) {
                    PublishResult.Published -> Unit
                    PublishResult.Taken -> throw FileAlreadyExistsException(incoming.toString())
                    is PublishResult.Failed -> throw IOException(linked.reason)
                }
            }
        }
        if (modifiedAt != null && twins(stored, file)) writeDurably(gameFolder(entry.root, SeenSegments, create = true).resolve(image.sha256), modifiedAt.toString().encodeToByteArray(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    }

    override suspend fun modified(entry: GameEntry): Instant? = withContext(Dispatchers.IO) {
        try {
            attributesOrNull(entryPath(entry, create = false))?.takeIf { it.isRegularFile }?.modified
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun holds(entry: GameEntry, image: FileImage): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = entryPath(entry, create = false)
            val attributes = attributesOrNull(file)?.takeIf { it.isRegularFile && it.size() == image.sizeBytes } ?: return@withContext false
            val seen = readAtMost(gameFolder(entry.root, SeenSegments).resolve(image.sha256), MaxSeenBytes)?.decodeToString()
            twins(gameFolder(entry.root, FilesSegments).resolve(image.sha256), file) && seen == attributes.modified.toString()
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun forget(entry: GameEntry, image: FileImage): Unit = withContext(Dispatchers.IO) {
        val files = try {
            gameFolder(entry.root, FilesSegments)
        } catch (e: NoSuchFileException) {
            return@withContext
        }
        val stored = files.resolve(image.sha256)
        if (!twins(stored, entryPath(entry, create = false))) return@withContext
        retryingBusy { Files.delete(stored) }
        syncFolderIfSupported(files)
    }

    override suspend fun has(root: GameRoot, image: FileImage): Boolean = withContext(Dispatchers.IO) {
        try {
            attributesOrNull(gameFolder(root, FilesSegments).resolve(image.sha256))?.takeIf { it.isRegularFile }?.size() == image.sizeBytes
        } catch (e: IOException) {
            false
        }
    }

    override suspend fun setAside(entry: GameEntry, folder: String): Unit = withContext(Dispatchers.IO) {
        if (folder.isBlank() || folder == "." || folder == ".." || folder.any { it == '/' || it == '\\' || it.isISOControl() }) throw IOException("\"$folder\" is not one plain folder name")
        val file = entryPath(entry, create = false)
        val aside = gameFolder(entry.root, EditsSegments + folder, create = true).resolve(entry.name)
        when (val linked = linker.link(file, aside)) {
            PublishResult.Published -> syncFolderIfSupported(aside.parent)
            PublishResult.Taken -> if (!twins(aside, file)) throw IOException("$aside already holds another file")
            is PublishResult.Failed -> throw IOException(linked.reason)
        }
    }

    private fun unmodified(file: Path, image: FileImage, modifiedAt: Instant): Boolean =
        attributesOrNull(file)?.let { it.size() == image.sizeBytes && it.modified == modifiedAt } == true

    private fun twins(stored: Path, file: Path): Boolean = linker.same(stored, file)

    override suspend fun keepOriginal(root: GameRoot, original: RetainedOriginal): Unit = withContext(Dispatchers.IO) {
        val files = gameFolder(root, FilesSegments, create = true)
        val folder = originals.resolve(original.id)
        for (file in original.files) {
            if (attributesOrNull(files.resolve(file.image.sha256))?.isRegularFile == true) continue
            val copy = retainedFile(folder, file.part)
            if (attributesOrNull(copy)?.isRegularFile == true) add(files, file.image) { incoming -> copyNew(copy, incoming) }
        }
    }

    private fun add(files: Path, image: FileImage, holding: (Path) -> Boolean = { measure(it) == image }, write: (Path) -> Unit) {
        val incoming = incoming(files, UUID.randomUUID().toString())
        try {
            write(incoming)
            if (!holding(incoming)) return
            retryingBusy { Files.move(incoming, files.resolve(image.sha256), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            syncFolderIfSupported(files)
        } finally {
            retryingBusy { Files.deleteIfExists(incoming) }
        }
    }

    override suspend fun publish(root: GameRoot, info: BackupInfo, library: LibraryManifest, held: Map<HeldFile, ByteArray>) = withContext(Dispatchers.IO) {
        val bytes = manifestBytes(library) ?: throw IOException(ManifestTooLarge)
        publishFolder(gameFolder(root, StoreSegments, create = true), info.id.value) { incoming ->
            writeDurably(incoming.resolve(LibraryName), bytes, StandardOpenOption.CREATE_NEW)
            // Copies, never links: an editor or the game saving in place would change a linked copy too.
            for ((file, content) in held) writeDurably(incoming.resolve(file.fileName), content, StandardOpenOption.CREATE_NEW)
            writeDurably(incoming.resolve(InfoName), BackupCodec.encode(info).encodeToByteArray(), StandardOpenOption.CREATE_NEW)
        }
    }

    override suspend fun held(root: GameRoot, id: BackupId): Map<HeldFile, ByteArray> = withContext(Dispatchers.IO) {
        val folder = gameFolder(root, StoreSegments + id.value)
        Files.newDirectoryStream(folder).use { it.toList() }.mapNotNull { file ->
            val held = HeldFile.named(file.fileName.toString()) ?: return@mapNotNull null
            held to (readAtMost(file, MaxConfigBytes) ?: throw IOException("its ${held.fileName} is larger than $MaxConfigBytes bytes"))
        }.toMap()
    }

    override suspend fun delete(root: GameRoot, id: BackupId) = withContext(Dispatchers.IO) {
        val store = try {
            gameFolder(root, StoreSegments)
        } catch (e: NoSuchFileException) {
            return@withContext
        }
        val backup = store.resolve(id.value)
        if (attributesOrNull(backup)?.isPlainFolder != true) return@withContext
        val deleting = store.resolve("$Deleting${id.value}")
        retryingBusy { Files.move(backup, deleting, StandardCopyOption.ATOMIC_MOVE) }
        syncFolderIfSupported(store)
        deleteTree(deleting)
    }

    override suspend fun prune(root: GameRoot, named: Set<String>) = withContext(Dispatchers.IO) {
        val store = try {
            gameFolder(root, StoreSegments)
        } catch (e: NoSuchFileException) {
            return@withContext
        }
        Files.newDirectoryStream(store).use { it.toList() }.filter { it.fileName.toString().let { name -> name.startsWith(IncomingPrefix) || name.startsWith(Deleting) } }.forEach(::deleteTree)
        for (folder in listOf(FilesName, SeenName).map(store::resolve)) {
            if (attributesOrNull(folder)?.isPlainFolder != true) continue
            Files.newDirectoryStream(folder).use { it.toList() }.filter { it.fileName.toString() !in named }.forEach(::deleteTree)
        }
    }

    override suspend fun place(image: FileImage, entry: GameEntry): PlaceResult = withContext(Dispatchers.IO) {
        val stored = try {
            gameFolder(entry.root, FilesSegments).resolve(image.sha256).takeIf { attributesOrNull(it)?.isRegularFile == true }
        } catch (e: NoSuchFileException) {
            null
        } ?: return@withContext PlaceResult.Unavailable
        try {
            val target = entryPath(entry, create = true)
            when (val linked = linker.link(stored, target)) {
                PublishResult.Published -> syncFolderIfSupported(target.parent)
                PublishResult.Taken -> return@withContext PlaceResult.Failed("${entry.name} already exists")
                is PublishResult.Failed -> return@withContext PlaceResult.Failed(linked.reason)
            }
            if (measure(target) == image) PlaceResult.Placed else PlaceResult.Unavailable.also { retryingBusy { Files.delete(target) } }
        } catch (e: IOException) {
            PlaceResult.Failed(e.message ?: "could not place ${entry.name}")
        }
    }

    override suspend fun placeOriginal(root: GameRoot, original: RetainedOriginal): PlaceResult = withContext(Dispatchers.IO) {
        try {
            val files = try {
                gameFolder(root, FilesSegments)
            } catch (e: NoSuchFileException) {
                return@withContext PlaceResult.Unavailable
            }
            Files.createDirectories(originals)
            deleteTree(originals.resolve(original.id))
            publishFolder(originals, original.id) { incoming ->
                for (file in original.files) {
                    val stored = files.resolve(file.image.sha256)
                    if (attributesOrNull(stored)?.isRegularFile != true) return@withContext PlaceResult.Unavailable
                    val copy = retainedFile(incoming, file.part)
                    copyNew(stored, copy)
                    if (measure(copy) != file.image) return@withContext PlaceResult.Unavailable
                }
            }
            PlaceResult.Placed
        } catch (e: IOException) {
            PlaceResult.Failed(e.message ?: "could not put back original ${original.id}")
        }
    }

    private companion object {
        val StoreSegments = SetFolder.GameSegments + CitadelPath.HideoutBackups.value
        const val FilesName = "files"
        val FilesSegments = StoreSegments + FilesName
        const val SeenName = "seen"
        val SeenSegments = StoreSegments + SeenName
        val EditsSegments = SetFolder.GameSegments + CitadelPath.HideoutEdits.value.split('/')
        val OwnFolders = setOf(FilesName, SeenName, EditsSegments.last())
        const val MaxSeenBytes = 64
        const val InfoName = "backup.json"
        const val LibraryName = "library.json"
        const val Deleting = ".deleting-"
        const val MaxInfoBytes = 64 * 1024
    }
}

private val BasicFileAttributes.modified: Instant get() = lastModifiedTime().toInstant().toKotlinInstant()

internal fun incoming(folder: Path, name: String): Path = folder.resolve("$IncomingPrefix$name")

internal inline fun publishFolder(parent: Path, name: String, write: (incoming: Path) -> Unit) {
    val incoming = incoming(parent, name)
    Files.createDirectory(incoming)
    try {
        write(incoming)
        syncFolderIfSupported(incoming)
        retryingBusy { Files.move(incoming, parent.resolve(name), StandardCopyOption.ATOMIC_MOVE) }
        syncFolderIfSupported(parent)
    } finally {
        deleteTree(incoming)
    }
}

private const val IncomingPrefix = ".incoming-"

internal fun retainedFile(folder: Path, part: VpkPart): Path = folder.resolve("${part.label}.vpk")
