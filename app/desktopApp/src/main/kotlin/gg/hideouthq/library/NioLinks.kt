package gg.hideouthq.library

import gg.hideouthq.core.data.EntryState
import gg.hideouthq.core.data.LinkResult
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.Removal
import gg.hideouthq.core.data.TwinLinks
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.SetFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

abstract class NioTwinLinks<E> : TwinLinks<E> {
    protected open val linker: Linker = Linker.System

    protected abstract fun path(entry: E, create: Boolean = false): Path

    protected abstract fun nameOf(entry: E): String

    override suspend fun link(existing: E, created: E): LinkResult = withContext(Dispatchers.IO) {
        try {
            val from = try {
                path(existing)
            } catch (e: NoSuchFileException) {
                return@withContext LinkResult.NotThere
            }
            NioLinks.link(linker, from, path(created, create = true))
        } catch (e: IOException) {
            LinkResult.Failed(e.message ?: "could not link ${nameOf(created)}")
        }
    }

    override suspend fun inspect(entry: E, witness: E): EntryState = withContext(Dispatchers.IO) {
        try {
            NioLinks.inspect(linker, path(entry), witnessPath(witness))
        } catch (e: NoSuchFileException) {
            EntryState.Absent
        } catch (e: IOException) {
            EntryState.Unreadable(e.message ?: "could not read ${nameOf(entry)}")
        }
    }

    override suspend fun removeTwin(entry: E, witness: E, expected: FileImage): Removal =
        removing(entry) { NioLinks.removeTwin(linker, it, witnessPath(witness), expected) }

    override suspend fun removeExact(entry: E, expected: FileImage): Removal = removing(entry) { NioLinks.remove(it, expected) { null } }

    protected open fun emptied(entry: E, file: Path) {}

    private suspend fun removing(entry: E, remove: (Path) -> Removal): Removal = withContext(Dispatchers.IO) {
        try {
            val file = path(entry)
            val removed = remove(file)
            if (removed == Removal.Removed) {
                try {
                    emptied(entry, file)
                } catch (e: IOException) {
                }
            }
            removed
        } catch (e: NoSuchFileException) {
            Removal.Absent
        } catch (e: IOException) {
            Removal.Failed(e.message ?: "could not remove ${nameOf(entry)}")
        }
    }

    private fun witnessPath(witness: E): Path? = try {
        path(witness)
    } catch (e: NoSuchFileException) {
        null
    }
}

/** Java cannot check and unlink in one step, so a file swapped in between the check and the delete is not caught. */
internal object NioLinks {
    fun link(linker: Linker, existing: Path, created: Path): LinkResult {
        if (attributesOrNull(existing)?.isRegularFile != true) return LinkResult.NotThere
        return when (val linked = linker.link(existing, created)) {
            PublishResult.Published -> {
                syncFolderIfSupported(created.parent)
                LinkResult.Linked
            }
            PublishResult.Taken -> LinkResult.Taken
            is PublishResult.Failed -> LinkResult.Failed(linked.reason)
        }
    }

    fun inspect(linker: Linker, entry: Path, witness: Path?): EntryState {
        val attributes = attributesOrNull(entry) ?: return EntryState.Absent
        if (!attributes.isRegularFile) return EntryState.NotPlain
        val image = measure(entry)
        return EntryState.Present(image, twin = witness != null && linker.same(entry, witness))
    }

    fun removeTwin(linker: Linker, entry: Path, witness: Path?, expected: FileImage): Removal = remove(entry, expected) {
        if (witness != null && linker.same(entry, witness)) null else "${entry.fileName} is not one file with ${witness?.fileName ?: "its anchor"}"
    }

    fun remove(entry: Path, expected: FileImage, notOurs: () -> String?): Removal = retryingBusy {
        val attributes = attributesOrNull(entry)
        when {
            attributes == null -> Removal.Absent
            !attributes.isRegularFile -> Removal.Kept("${entry.fileName} is not a plain file")
            measure(entry) != expected -> Removal.Kept("${entry.fileName} changed since Hideout wrote it")
            else -> notOurs()?.let { Removal.Kept(it) } ?: run {
                Files.delete(entry)
                syncFolderIfSupported(entry.parent)
                Removal.Removed
            }
        }
    }
}

internal val BasicFileAttributes.isPlainFolder: Boolean get() = isDirectory && !isSymbolicLink && !isOther

internal fun plainFolders(folder: Path): Path {
    var path = folder.root ?: throw IOException("$folder is not an absolute folder path")
    for (name in folder) {
        path = path.resolve(name)
        val attributes = attributesOrNull(path) ?: throw NoSuchFileException("$path is not there")
        if (!attributes.isPlainFolder) throw IOException("$path is a link, a junction or not a folder")
    }
    return folder
}

internal fun ownFolder(parent: Path, name: String): Path {
    val folder = parent.resolve(name)
    try {
        Files.createDirectory(folder)
    } catch (e: FileAlreadyExistsException) {
    }
    val attributes = attributesOrNull(folder) ?: throw NoSuchFileException("$folder is not there")
    if (!attributes.isPlainFolder) throw IOException("$folder is a link, a junction or not a folder")
    return folder
}

internal fun managedFolder(root: GameRoot, folder: SetFolder, create: Boolean): Path = gameFolder(root, folder.segments, create)

internal fun gameFolder(root: GameRoot, segments: List<String>, create: Boolean = false): Path {
    val base = Path.of(root.path)
    if (!base.isAbsolute || base.normalize() != base) throw IOException("${root.path} is not an absolute folder path on this computer")
    var path = base
    segments.forEachIndexed { index, segment ->
        path = path.resolve(segment)
        val attributes = attributesOrNull(path)
        when {
            attributes == null && create && index >= SetFolder.GameSegments.size -> Files.createDirectory(path)
            attributes == null -> throw NoSuchFileException("$path is not there")
            !attributes.isPlainFolder -> throw IOException("$path is a link, a junction or not a folder")
        }
    }
    return path
}
