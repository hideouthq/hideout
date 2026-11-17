package gg.hideouthq.library

import gg.hideouthq.core.data.PublishResult
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * exFAT, FAT and some network mounts make no hard links, and a name on another drive can't be one, so those get a copy.
 * Elsewhere than Windows, moving the copy to its name checks that no entry has it and then renames, so another program
 * that creates the very name in between loses its file; a hard link leaves no such moment.
 */
class Linker(private val createLink: (link: Path, existing: Path) -> Unit = HardLink) {
    private val linking = ConcurrentHashMap<FileStore, Boolean>()

    fun link(from: Path, to: Path): PublishResult {
        val source = attributesOrNull(from) ?: return PublishResult.Failed("${from.fileName} is not there")
        if (!source.isRegularFile) return PublishResult.Failed("${from.fileName} is not a plain file")
        try {
            createLink(to, from)
            return PublishResult.Published
        } catch (e: FileAlreadyExistsException) {
            return PublishResult.Taken
        } catch (e: IOException) {
            if (linksBetween(from, to)) throw e
        }
        return copy(from, to)
    }

    fun same(a: Path, b: Path): Boolean {
        val first = attributesOrNull(a)?.takeIf { it.isRegularFile } ?: return false
        val second = attributesOrNull(b)?.takeIf { it.isRegularFile } ?: return false
        if (Files.isSameFile(a, b)) return true
        return first.size() == second.size() && !linksBetween(a, b) && measure(a) == measure(b)
    }

    fun links(folder: Path): Boolean {
        val store = Files.getFileStore(folder)
        linking[store]?.let { return it }
        return probe(folder)?.also { linking[store] = it } ?: false
    }

    private fun linksBetween(a: Path, b: Path): Boolean {
        val folder = b.toAbsolutePath().parent
        return Files.getFileStore(a.toAbsolutePath().parent) == Files.getFileStore(folder) && links(folder)
    }

    private fun probe(folder: Path): Boolean? {
        val trial = folder.resolve(".hideout-probe-${UUID.randomUUID()}")
        val second = folder.resolve("${trial.fileName}-link")
        return try {
            Files.createFile(trial)
            try {
                createLink(second, trial)
                true
            } catch (e: IOException) {
                false
            }
        } catch (e: IOException) {
            null
        } finally {
            runCatching { retryingBusy { Files.deleteIfExists(second) } }
            runCatching { retryingBusy { Files.deleteIfExists(trial) } }
        }
    }

    private fun copy(from: Path, to: Path): PublishResult {
        val copy = to.resolveSibling(".hideout-copy-${UUID.randomUUID()}")
        try {
            copyNew(from, copy)
            if (measure(copy) != measure(from)) throw IOException("the copy of ${from.fileName} does not hold its bytes")
            retryingBusy { Files.move(copy, to) }
            syncFolderIfSupported(to.toAbsolutePath().parent)
            return PublishResult.Published
        } catch (e: FileAlreadyExistsException) {
            return PublishResult.Taken
        } finally {
            runCatching { retryingBusy { Files.deleteIfExists(copy) } }
        }
    }

    companion object {
        val HardLink: (link: Path, existing: Path) -> Unit = { link, existing -> Files.createLink(link, existing) }
        val System = Linker()
    }
}
