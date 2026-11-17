package gg.hideouthq.library

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal sealed interface DataFolderClaim {
    class Owner(val lock: FolderLock, val focusSocket: Path?) : DataFolderClaim

    data object HandedOff : DataFolderClaim

    data class NoAnswer(val reason: String) : DataFolderClaim

    data class Unlockable(val reason: String) : DataFolderClaim
}

internal class ClaimPatience(val total: Duration = 5.seconds, val attempt: Duration = 1500.milliseconds, val pause: Duration = 100.milliseconds)

internal fun claimDataFolder(
    folder: Path,
    environment: (String) -> String?,
    patience: ClaimPatience = ClaimPatience(),
    link: String? = null,
    onRunningInstance: () -> Unit = {},
): DataFolderClaim {
    val giveUpAt = TimeSource.Monotonic.markNow() + patience.total
    val request = linkRequest(link)
    var why: String
    var told = false
    do {
        val real: Path
        val lock = try {
            real = Files.createDirectories(folder).toRealPath()
            FolderLock.take(real)
        } catch (e: IOException) {
            return DataFolderClaim.Unlockable("could not lock $folder: ${e.message ?: e::class.simpleName}")
        }
        val socket = focusSocketPath(real, environment)
        if (lock != null) return DataFolderClaim.Owner(lock, socket)
        if (!told) onRunningInstance()
        told = true
        why = if (socket == null) "no socket path fits under $folder" else askToFocus(socket, patience.attempt, request) ?: return DataFolderClaim.HandedOff
        Thread.sleep(patience.pause.inWholeMilliseconds)
    } while (giveUpAt.hasNotPassedNow())
    return DataFolderClaim.NoAnswer("another Hideout holds $folder and did not answer within ${patience.total}: $why")
}

/** A Unix socket address has room for 107 bytes, which a deep folder overflows. */
internal fun focusSocketPath(realFolder: Path, environment: (String) -> String?): Path? {
    realFolder.resolve("hideout.sock").takeIf(::fitsAnAddress)?.let { return it }
    val runtime = environment("XDG_RUNTIME_DIR")?.let(Path::of)?.takeIf { it.isAbsolute } ?: return null
    val name = MessageDigest.getInstance("SHA-256").digest(realFolder.toString().encodeToByteArray()).take(8).joinToString("") { "%02x".format(it) }
    return runtime.resolve("hideout-$name.sock").takeIf(::fitsAnAddress)
}

private fun fitsAnAddress(path: Path) = path.toString().encodeToByteArray().size <= MaxAddressBytes

internal fun DataFolderClaim.Owner.serveFocusRequests(note: (String) -> Unit, onLink: (String) -> Unit = {}, focus: () -> Boolean): AutoCloseable {
    val socket = focusSocket ?: return AutoCloseable { }.also { note("A second Hideout can't reach this one: no socket path fits") }
    val listener = try {
        Files.deleteIfExists(socket)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { channel ->
            channel.bind(UnixDomainSocketAddress.of(socket))
            // Windows keeps the data folder to its user with the ACL it inherits from %LOCALAPPDATA%.
            if ("posix" in socket.fileSystem.supportedFileAttributeViews()) {
                Files.setPosixFilePermissions(socket, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            }
        }
    } catch (e: Exception) {
        return unreachable(note, e)
    } catch (e: LinkageError) {
        return unreachable(note, e)
    }
    thread(isDaemon = true, name = "hideout-focus-requests") {
        while (true) {
            val client = try {
                listener.accept()
            } catch (e: ClosedChannelException) {
                break
            } catch (e: IOException) {
                note("A second Hideout can't reach this one any more: $e")
                break
            }
            try {
                client.use { answer(it, onLink, focus) }
            } catch (e: IOException) {
            }
        }
    }
    val stop = AutoCloseable {
        listener.close()
        runCatching { Files.deleteIfExists(socket) }
    }
    Runtime.getRuntime().addShutdownHook(thread(start = false) { stop.close() })
    return stop
}

private fun unreachable(note: (String) -> Unit, failure: Throwable) = AutoCloseable { }.also { note("A second Hideout can't reach this one: $failure") }

private fun answer(client: SocketChannel, onLink: (String) -> Unit, focus: () -> Boolean) {
    client.configureBlocking(false)
    val request = ByteBuffer.allocate(LinkRequest.size + MaxLinkBytes + 1)
    if (!client.drainLine(TimeSource.Monotonic.markNow() + RequestWait, request)) return
    val line = request.array().copyOf(request.position())
    val link = when {
        line.contentEquals(FocusRequest) -> null
        line.copyOf(minOf(line.size, LinkRequest.size)).contentEquals(LinkRequest) -> line.decodeToString(LinkRequest.size, line.size - 1)
        else -> return
    }
    if (!focus()) return
    link?.let(onLink)
    client.pour(TimeSource.Monotonic.markNow() + RequestWait, ByteBuffer.wrap(Focused))
}

private fun linkRequest(link: String?): ByteArray {
    if (link == null) return FocusRequest
    val bytes = link.encodeToByteArray()
    val fits = bytes.size <= MaxLinkBytes && '\n' !in link
    return LinkRequest + (if (fits) bytes else ByteArray(0)) + '\n'.code.toByte()
}

private fun askToFocus(socket: Path, within: Duration, request: ByteArray): String? {
    val giveUpAt = TimeSource.Monotonic.markNow() + within
    return try {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.configureBlocking(false)
            val answer = ByteBuffer.allocate(Focused.size)
            when {
                !channel.connect(UnixDomainSocketAddress.of(socket)) && !polling(giveUpAt) { channel.finishConnect() } -> "connecting timed out"
                !channel.pour(giveUpAt, ByteBuffer.wrap(request)) -> "asking timed out"
                !channel.drain(giveUpAt, answer) || !answer.array().contentEquals(Focused) -> "it did not raise its window"
                else -> null
            }
        }
    } catch (e: Exception) {
        "$e"
    } catch (e: LinkageError) {
        "$e"
    }
}

private fun SocketChannel.drain(giveUpAt: TimeMark, into: ByteBuffer): Boolean = polling(giveUpAt) { read(into) < 0 || !into.hasRemaining() } && !into.hasRemaining()

private fun SocketChannel.drainLine(giveUpAt: TimeMark, into: ByteBuffer): Boolean {
    fun lineEnded() = into.position() > 0 && into.get(into.position() - 1) == '\n'.code.toByte()
    return polling(giveUpAt) { read(into) < 0 || !into.hasRemaining() || lineEnded() } && lineEnded()
}

private fun SocketChannel.pour(giveUpAt: TimeMark, from: ByteBuffer): Boolean = polling(giveUpAt) {
    write(from)
    !from.hasRemaining()
}

private inline fun polling(giveUpAt: TimeMark, done: () -> Boolean): Boolean {
    while (!done()) {
        if (giveUpAt.hasPassedNow()) return false
        Thread.sleep(PollMillis)
    }
    return true
}

private const val MaxAddressBytes = 107
private const val PollMillis = 10L
private val RequestWait = 500.milliseconds
private const val MaxLinkBytes = 4096
private val FocusRequest = "hideout focus\n".encodeToByteArray()
private val LinkRequest = "hideout link ".encodeToByteArray()
private val Focused = "focused\n".encodeToByteArray()
