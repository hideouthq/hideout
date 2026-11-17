package gg.hideouthq.library

import gg.hideouthq.core.data.deadlockstats.ReplayCacheWatcherPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class SteamReplayCacheWatcher(
    private val steamInstalls: () -> List<SteamInstall>,
    private val reconcileInterval: Duration = 5.minutes,
) : ReplayCacheWatcherPort {
    @Volatile private var watchService: WatchService? = null
    @Volatile private var runningJob: Job? = null
    private val runMutex = Mutex()
    private val read = ConcurrentHashMap<Path, Stamp>()

    private data class Stamp(val size: Long, val modified: FileTime)

    override suspend fun start(onCandidate: suspend (header: ByteArray, writtenAt: Long) -> Unit) = withContext(Dispatchers.IO) { runMutex.withLock {
        runningJob = currentCoroutineContext()[Job]
        read.clear()
        val service = FileSystems.getDefault().newWatchService()
        watchService = service
        try {
            val keysByDir = ConcurrentHashMap<WatchKey, Path>()
            val queue = Channel<Path>(capacity = MaxQueuedFiles)
            coroutineScope {
                launch { consume(queue, onCandidate) }
                launch { poll(service, keysByDir, queue) }
                while (isActive) {
                    val found = HashSet<Path>()
                    for (dir in cacheDirectories()) watchTree(dir, service, keysByDir, queue, found)
                    read.keys.retainAll(found)
                    delay(reconcileInterval)
                }
            }
        } finally {
            runCatching { service.close() }
            watchService = null
            runningJob = null
        }
    } }

    override fun close() {
        runningJob?.cancel()
        runCatching { watchService?.close() }
    }

    /** Each root's cache with links resolved, so `~/.steam/steam` and `~/.local/share/Steam` are watched once. */
    private fun cacheDirectories(): List<Path> = steamInstalls().mapNotNull { install ->
        try {
            Path.of(install.root).takeIf { it.isAbsolute }?.resolve("appcache")?.resolve("httpcache")?.toRealPath()?.takeIf { Files.isDirectory(it) }
        } catch (e: InvalidPathException) {
            null
        } catch (e: IOException) {
            null
        }
    }.distinct()

    /** Registering a folder already watched returns its key, so each pass can walk it all again. */
    private suspend fun watchTree(dir: Path, service: WatchService, keysByDir: MutableMap<WatchKey, Path>, queue: Channel<Path>, found: MutableSet<Path>?) {
        try {
            keysByDir[dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY)] = dir
            Files.newDirectoryStream(dir).use { entries ->
                for (entry in entries) {
                    if (!currentCoroutineContext().isActive) return
                    when {
                        Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) -> watchTree(entry, service, keysByDir, queue, found)
                        Files.isRegularFile(entry) -> if (found == null) queue.trySend(entry) else {
                            found.add(entry)
                            if (read[entry] != stampOf(entry)) queue.send(entry)
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Steam can remove cache directories during a pass.
        } catch (_: DirectoryIteratorException) {
        }
    }

    /** Timed polls allow cancellation. */
    private suspend fun poll(service: WatchService, keysByDir: MutableMap<WatchKey, Path>, queue: Channel<Path>) {
        while (currentCoroutineContext().isActive) {
            val key = try {
                withContext(Dispatchers.IO) { service.poll(PollTimeoutMillis, TimeUnit.MILLISECONDS) }
            } catch (e: ClosedWatchServiceException) {
                return
            } catch (e: InterruptedException) {
                return
            }
            if (key == null) continue
            val dir = keysByDir[key]
            if (dir != null) {
                for (event in key.pollEvents()) {
                    val child = dir.resolve(event.context() as? Path ?: continue)
                    when {
                        Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) -> watchTree(child, service, keysByDir, queue, found = null)
                        Files.isRegularFile(child) -> queue.trySend(child)
                    }
                }
            }
            if (!key.reset()) keysByDir.remove(key)
        }
    }

    private suspend fun consume(queue: Channel<Path>, onCandidate: suspend (header: ByteArray, writtenAt: Long) -> Unit) {
        for (path in queue) {
            val stamp = stampOf(path)
            val header = readHeader(path)
            if (stamp != null && (read.size < MaxRemembered || read.containsKey(path))) read[path] = stamp
            if (header != null && stamp != null) onCandidate(header, stamp.modified.to(TimeUnit.SECONDS))
        }
    }

    private fun stampOf(path: Path): Stamp? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).let { Stamp(it.size(), it.lastModifiedTime()) }
    } catch (e: IOException) {
        null
    }

    private fun readHeader(path: Path): ByteArray? = try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(HeaderReadBytes) }.takeIf { it.isNotEmpty() }
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val MaxQueuedFiles = 2_048
        const val MaxRemembered = 100_000
        const val PollTimeoutMillis = 500L
        const val HeaderReadBytes = 256
    }
}
