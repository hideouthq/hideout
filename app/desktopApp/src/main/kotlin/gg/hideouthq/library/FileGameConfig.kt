package gg.hideouthq.library

import gg.hideouthq.OnWindows
import gg.hideouthq.core.data.ConfigRead
import gg.hideouthq.core.data.ConfigRecovery
import gg.hideouthq.core.data.GameConfig
import gg.hideouthq.core.data.MaxConfigBytes
import gg.hideouthq.core.data.PublishResult
import gg.hideouthq.core.data.ReplaceResult
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.Sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.StandardWatchEventKinds
import java.nio.file.attribute.DosFileAttributes
import java.util.concurrent.TimeUnit

/**
 * On Windows a file the player marked read-only, as players do so Steam's updates leave `gameinfo.gi` alone, is
 * marked again once replaced.
 */
class FileGameConfig(
    private val windows: Boolean = OnWindows,
    private val afterDisplace: (Path) -> Unit = {},
    createLink: (link: Path, existing: Path) -> Unit = Linker.HardLink,
) : GameConfig {
    private val linker = Linker(createLink)

    override suspend fun read(root: GameRoot): ConfigRead = withContext(Dispatchers.IO) {
        try {
            val file = config(root, ConfigTarget.GameInfo.Segments)
            val attributes = attributesOrNull(file) ?: return@withContext ConfigRead.Missing
            if (!attributes.isRegularFile) return@withContext ConfigRead.Unreadable("gameinfo.gi is not a plain file")
            val bytes = readAtMost(file, MaxConfigBytes) ?: return@withContext ConfigRead.Unreadable("gameinfo.gi is larger than $MaxConfigBytes bytes")
            ConfigRead.Present(bytes, Sha256.image(bytes))
        } catch (e: IOException) {
            ConfigRead.Unreadable(e.message ?: "gameinfo.gi could not be read")
        }
    }

    override suspend fun prepare(root: GameRoot, target: ConfigTarget): String? = withContext(Dispatchers.IO) {
        try {
            config(root, target.segments, create = target == ConfigTarget.Autoexec)
            null
        } catch (e: IOException) {
            e.message ?: "the folder of ${target.path.value} could not be used"
        }
    }

    override suspend fun replace(edit: PendingChange.ConfigEdit, next: ByteArray): ReplaceResult = withContext(Dispatchers.IO) {
        require(Token.matches(edit.token)) { "not a token Hideout makes" }
        val expected = edit.from
        val current = try {
            config(edit.gameRoot, edit.target.segments)
        } catch (e: IOException) {
            return@withContext ReplaceResult.Failed(e.message ?: "${edit.target.path.value} could not be replaced")
        }
        val name = current.fileName.toString()
        val staged = current.resolveSibling("$name$NextInfix${edit.token}")
        val displaced = current.resolveSibling("$name$DisplacedInfix${edit.token}")
        var created = false
        var moved = false
        fun discardStaged() {
            if (created && imageOrNull(staged) == Sha256.image(next)) delete(staged)
        }
        try {
            if (imageOrNull(current) != expected || expected == null && attributesOrNull(current) != null) return@withContext ReplaceResult.Changed
            val readOnly = windows && expected != null && Files.readAttributes(current, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isReadOnly
            writeDurably(staged, next, StandardOpenOption.CREATE_NEW)
            created = true
            if (expected != null) {
                retryingBusy { Files.move(current, displaced) }
                moved = true
                afterDisplace(current)
                if (imageOrNull(displaced) != expected) {
                    discardStaged()
                    return@withContext when (val restored = moveWithoutReplacing(displaced, current)) {
                        PublishResult.Published -> ReplaceResult.Changed
                        PublishResult.Taken -> ReplaceResult.Conflict("$name changed while it was being replaced; the version seen then is kept as ${displaced.fileName}")
                        is PublishResult.Failed -> ReplaceResult.Failed(restored.reason)
                    }
                }
            }
            when (val published = moveWithoutReplacing(staged, current)) {
                PublishResult.Published -> {
                    try {
                        if (readOnly) Files.setAttribute(current, "dos:readonly", true, LinkOption.NOFOLLOW_LINKS)
                        if (moved && imageOrNull(displaced) == expected) delete(displaced)
                    } catch (e: IOException) {
                    }
                    ReplaceResult.Replaced
                }
                PublishResult.Taken -> {
                    discardStaged()
                    val kept = if (moved) ", and the earlier file is kept as ${displaced.fileName}" else ""
                    ReplaceResult.Conflict("another program wrote $name while Hideout was editing it; theirs stays$kept")
                }
                is PublishResult.Failed -> {
                    restore(displaced, current)
                    discardStaged()
                    ReplaceResult.Failed(published.reason)
                }
            }
        } catch (e: IOException) {
            if (moved) runCatching { restore(displaced, current) }
            runCatching { discardStaged() }
            ReplaceResult.Failed(e.message ?: "$name could not be replaced")
        }
    }

    override suspend fun recover(edit: PendingChange.ConfigEdit): ConfigRecovery = withContext(Dispatchers.IO) {
        require(Token.matches(edit.token)) { "not a token Hideout makes" }
        val from = edit.from
        val to = edit.to
        val current = try {
            config(edit.gameRoot, edit.target.segments)
        } catch (e: NoSuchFileException) {
            return@withContext if (from == null) ConfigRecovery.Undone() else ConfigRecovery.Failed("${edit.target.path.value} is missing and Hideout has no copy of it")
        } catch (e: IOException) {
            return@withContext ConfigRecovery.Failed(e.message ?: "${edit.target.path.value} could not be checked")
        }
        val name = current.fileName.toString()
        val staged = current.resolveSibling("$name$NextInfix${edit.token}")
        val displaced = current.resolveSibling("$name$DisplacedInfix${edit.token}")
        try {
            val kept = mutableListOf<String>()
            if (attributesOrNull(staged) != null) {
                if (imageOrNull(staged) == to) delete(staged) else kept += staged.fileName.toString()
            }
            val now = imageOrNull(current)
            val aside = imageOrNull(displaced)
            fun keepDisplaced() = kept + listOfNotNull(displaced.fileName.toString().takeIf { attributesOrNull(displaced) != null })
            when {
                now == null && aside != null && (aside == from || aside == to) -> when (val restored = moveWithoutReplacing(displaced, current)) {
                    PublishResult.Published -> if (aside == from) ConfigRecovery.Undone(kept) else ConfigRecovery.Applied(kept)
                    PublishResult.Taken -> ConfigRecovery.Conflict("$name appeared while the file moved aside was being put back; that file is kept as ${displaced.fileName}", keepDisplaced())
                    is PublishResult.Failed -> ConfigRecovery.Failed(restored.reason)
                }
                now == null && aside != null -> ConfigRecovery.Failed(
                    "$name is missing and the file moved aside as ${displaced.fileName} matches neither the edit's original nor its result, " +
                        "so it is kept as ${displaced.fileName} rather than published",
                )
                now == null && from == null -> ConfigRecovery.Undone(kept)
                now == null -> ConfigRecovery.Failed("$name is missing and Hideout has no copy of it")
                now == to || now == from -> {
                    if (from != null && aside == from) delete(displaced)
                    if (now == to) ConfigRecovery.Applied(keepDisplaced()) else ConfigRecovery.Undone(keepDisplaced())
                }
                else -> ConfigRecovery.Conflict("$name was changed by another program; it stays as it is", keepDisplaced())
            }
        } catch (e: IOException) {
            ConfigRecovery.Failed(e.message ?: "$name could not be checked")
        }
    }

    override fun changes(root: GameRoot, path: CitadelPath): Flow<Unit> = flow {
        emitAll(fileChanges(config(root, SetFolder.GameSegments + path.value.split('/'))))
    }.flowOn(Dispatchers.IO)

    private fun restore(displaced: Path, current: Path) {
        if (attributesOrNull(current) == null && attributesOrNull(displaced) != null) moveWithoutReplacing(displaced, current)
    }

    private fun moveWithoutReplacing(from: Path, to: Path): PublishResult {
        val staged = attributesOrNull(from) ?: return PublishResult.Failed("${from.fileName} is not there")
        if (!staged.isRegularFile) return PublishResult.Failed("${from.fileName} is not a plain file")
        val renames = windows || !linker.links(to.toAbsolutePath().parent)
        try {
            if (renames) retryingBusy { Files.move(from, to) } else when (val linked = linker.link(from, to)) {
                PublishResult.Published -> Unit
                PublishResult.Taken -> return PublishResult.Taken
                is PublishResult.Failed -> return linked
            }
        } catch (e: FileAlreadyExistsException) {
            return PublishResult.Taken
        }
        if (!renames) {
            try {
                Files.delete(from)
            } catch (e: IOException) {
            }
        }
        return PublishResult.Published
    }

    /**
     * Windows refuses to delete a read-only file, and players mark `gameinfo.gi` read-only to keep Steam from reverting
     * it, so there the mark comes off first.
     */
    private fun delete(file: Path) {
        if (windows) Files.setAttribute(file, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS)
        retryingBusy { Files.deleteIfExists(file) }
    }

    private fun imageOrNull(file: Path): FileImage? = attributesOrNull(file)?.takeIf { it.isRegularFile }?.let { measure(file) }

    private fun config(root: GameRoot, segments: List<String>, create: Boolean = false): Path = gameFolder(root, segments.dropLast(1), create).resolve(segments.last())

    private companion object {
        const val NextInfix = ".hideout-next-"
        const val DisplacedInfix = ".hideout-displaced-"
        val Token = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}

internal fun fileChanges(file: Path): Flow<Unit> = callbackFlow {
    val folder = file.parent
    val watcher = folder.fileSystem.newWatchService()
    try {
        folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE)
    } catch (e: IOException) {
        watcher.close()
        throw e
    }
    launch {
        try {
            while (isActive) {
                val key = watcher.poll(WatchPollMillis, TimeUnit.MILLISECONDS) ?: continue
                val touched = key.pollEvents().any { event ->
                    event.kind() == StandardWatchEventKinds.OVERFLOW || (event.context() as? Path)?.fileName == file.fileName
                }
                key.reset()
                if (touched) trySend(Unit)
            }
        } catch (e: ClosedWatchServiceException) {
            return@launch
        }
    }
    awaitClose { watcher.close() }
}.flowOn(Dispatchers.IO)

private const val WatchPollMillis = 500L
