package gg.hideouthq.library

import gg.hideouthq.core.data.LocalArchiveSnapshot
import gg.hideouthq.core.data.LocalFile
import gg.hideouthq.core.data.LocalImport
import gg.hideouthq.core.data.LocalImportResult
import gg.hideouthq.core.data.LocalRefusal
import gg.hideouthq.core.data.isSplitArchivePart
import gg.hideouthq.core.data.isSupportedImport
import gg.hideouthq.core.library.ArchiveIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.util.UUID

class DesktopLocalImport(private val directory: Path) : LocalImport {
    override suspend fun snapshot(file: LocalFile): LocalImportResult {
        if (file.isSplitArchivePart()) return LocalImportResult.Refused(LocalRefusal.SplitArchive)
        if (!file.isSupportedImport()) return LocalImportResult.Refused(LocalRefusal.UnsupportedType)
        val copy = directory.resolve("${UUID.randomUUID()}.${file.name.substringAfterLast('.').lowercase()}")
        return try {
            runInterruptible(Dispatchers.IO) {
                Files.createDirectories(directory)
                val digest = sha256()
                val size = DigestInputStream(Files.newInputStream(Path.of(file.path)), digest).use { Files.copy(it, copy) }
                if (size == 0L) {
                    Files.delete(copy)
                    return@runInterruptible LocalImportResult.Refused(LocalRefusal.Empty)
                }
                LocalImportResult.Ready(LocalArchiveSnapshot(ArchiveIdentity(copy.toString(), size, digest.digest().toHexString()), file.name))
            }
        } catch (e: Exception) {
            withContext(NonCancellable + Dispatchers.IO) { Files.deleteIfExists(copy) }
            // A copy cancelled while it reads fails with the channel's ClosedByInterruptException.
            currentCoroutineContext().ensureActive()
            if (e is CancellationException) throw e
            LocalImportResult.Refused(LocalRefusal.Unreadable)
        }
    }

    override suspend fun release(snapshot: LocalArchiveSnapshot) {
        withContext(Dispatchers.IO) { Files.deleteIfExists(directory.resolve(Path.of(snapshot.archive.path).fileName)) }
    }
}
