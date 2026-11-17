package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlockstats.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class SteamBridgeHello(val pid: Long, val bits: Int)

internal class SteamBridgeServer(
    private val endpointDirectory: () -> Path,
    parent: CoroutineScope,
    private val installation: suspend () -> SteamTrackingInstallation,
    private val admit: (SteamBridgeHello) -> SteamProcessIdentity?,
    private val startupStatus: () -> SteamTrackingStatus? = { null },
) : SteamTrackingPort {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO)
    private val mutableStatus = MutableStateFlow(SteamTrackingStatus(SteamTrackingPhase.WaitingForSteam))
    override val status = mutableStatus.asStateFlow()
    private val mutableSession = MutableStateFlow<SteamGcSession?>(null)
    override val session = mutableSession.asStateFlow()
    private val readiness = MutableStateFlow(SteamGcReadiness.Unknown)
    override val gcState = readiness.asStateFlow()
    private val mutableEvents = MutableSharedFlow<SteamTrackingEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = mutableEvents.asSharedFlow()
    private data class Pending(val session: SteamGcSession, val response: Int, val result: CompletableDeferred<ByteArray>)
    private val pending = ConcurrentHashMap<Long, Pending>()
    private val jobs = AtomicLong(SecureRandom().nextLong())
    private val requests = Mutex()
    private val writes = Any()
    private val token = ByteArray(32).also(SecureRandom()::nextBytes)
    @Volatile private var peer: Socket? = null
    @Volatile private var listener: ServerSocket? = null
    private var lockChannel: FileChannel? = null
    private var fileLock: FileLock? = null
    @Volatile private var closed = false
    @Volatile private var admissionFailure: String? = null
    private var started = false
    private lateinit var directory: Path

    override suspend fun start() {
        check(!closed && !started)
        started = true
        val installed = installation()
        if (!installed.supported || !installed.installed) {
            mutableStatus.value = SteamTrackingStatus(SteamTrackingPhase.Unsupported, installed.reason ?: "Steam tracking is not installed")
            return
        }
        try {
            directory = endpointDirectory()
            val mode = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            Files.createDirectories(directory, mode)
            require(!Files.isSymbolicLink(directory)) { "Unsafe bridge directory" }
            val owner = Files.getOwner(Path.of(System.getProperty("user.home")))
            require(Files.getOwner(directory, NOFOLLOW_LINKS) == owner)
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val channel = FileChannel.open(directory.resolve("listener.lock"), CREATE, WRITE, NOFOLLOW_LINKS)
            lockChannel = channel
            fileLock = channel.tryLock() ?: error("Another Hideout collector is running")
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            listener = server
            val temporary = Files.createTempFile(directory, "endpoint-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                Files.writeString(temporary, "HSG2\n${server.localPort}\n${token.toHexString()}\n")
                Files.move(temporary, directory.resolve("endpoint"), ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            scope.launch { accept(server) }
            scope.launch {
                while (isActive && !closed) {
                    if (peer == null && admissionFailure == null) mutableStatus.value = startupStatus() ?: SteamTrackingStatus(SteamTrackingPhase.WaitingForSteam)
                    delay(1000)
                }
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    private suspend fun accept(server: ServerSocket) {
        while (scope.isActive && !closed) {
            try {
                val socket = server.accept()
                peer = socket
                socket.use { connected ->
                    connected.tcpNoDelay = true
                    connected.soTimeout = 5000
                    val input = DataInputStream(connected.getInputStream())
                    val hello = SteamBridgeProtocol.read(input, 40)
                    require(hello.kind == SteamBridgeProtocol.Hello && hello.body.size == 40 && hello.session == 0L && hello.job == 0L && hello.account == 0L && hello.message == 0)
                    require(MessageDigest.isEqual(token, hello.body.copyOfRange(0, 32))) { "Bridge authentication failed" }
                    val body = ByteBuffer.wrap(hello.body, 32, 8)
                    val identity = SteamBridgeHello(body.int.toLong() and 0xffffffffL, body.int)
                    val admitted = admit(identity)
                    if (admitted == null) {
                        admissionFailure = "Steam bridge mappings do not match the installed artifacts. Restart Steam through its tracking launcher."
                        error("Steam bridge mappings do not match the installed artifacts")
                    }
                    admissionFailure = null
                    connected.soTimeout = 15_000
                    synchronized(writes) { SteamBridgeProtocol.write(DataOutputStream(connected.getOutputStream()), SteamBridgeProtocol.Frame(SteamBridgeProtocol.Ack)) }
                    mutableStatus.value = SteamTrackingStatus(SteamTrackingPhase.WaitingForGame)
                    while (scope.isActive && !closed) {
                        val frame = SteamBridgeProtocol.read(input)
                        if (frame.kind == SteamBridgeProtocol.Heartbeat && admit(identity) != admitted) {
                            admissionFailure = "Steam mappings changed. Restart Steam through its tracking launcher."
                            error("Steam mappings changed; restart Steam through its tracking launcher")
                        }
                        receive(frame)
                    }
                }
            } catch (e: Exception) {
                if (e is SteamProcessMetadataUnavailable) admissionFailure = e.message
                if (!closed) mutableStatus.value = if (admissionFailure != null) SteamTrackingStatus(SteamTrackingPhase.Failed, admissionFailure)
                    else SteamTrackingStatus(SteamTrackingPhase.WaitingForSteam, "Waiting for a verified Steam connection")
            } finally {
                peer?.close()
                peer = null
                changeSession(null)
            }
        }
    }

    private fun receive(frame: SteamBridgeProtocol.Frame) {
        if (frame.kind == SteamBridgeProtocol.Session) {
            require(frame.body.isEmpty() && frame.message in 0..1)
            require(frame.job == 0L && if (frame.message == 1) frame.account != 0L else frame.account == 0L && frame.session == 0L)
            val next = if (frame.message == 1) SteamGcSession(frame.account, frame.session.also { require(it != 0L) }) else null
            changeSession(next)
            mutableStatus.value = SteamTrackingStatus(if (next == null) SteamTrackingPhase.WaitingForGame else SteamTrackingPhase.WaitingForGc)
            return
        }
        if (frame.kind == SteamBridgeProtocol.Heartbeat) {
            require(frame.body.isEmpty())
            return
        }
        val active = mutableSession.value ?: return
        if (frame.session != active.generation || frame.account != active.accountId) return
        when (frame.kind) {
            SteamBridgeProtocol.GcState -> {
                require(frame.body.isEmpty() && frame.job == 0L && frame.message in 0..2)
                val next = SteamGcReadiness.entries[frame.message]
                readiness.value = next
                if (next == SteamGcReadiness.Waiting) cancelPending("Waiting for the game's GC connection")
                mutableStatus.value = SteamTrackingStatus(if (next == SteamGcReadiness.Ready) SteamTrackingPhase.Connected else SteamTrackingPhase.WaitingForGc)
            }
            SteamBridgeProtocol.Gc -> {
                val message = SteamGcCodec.decode(frame.body)
                require(frame.message == message.type)
                if (message.type == 9166) mutableEvents.tryEmit(SteamTrackingEvent.AccountUpdated(active))
                else message.job?.let { id ->
                    val request = pending[id]
                    if (request != null && request.session == active && request.response == message.type && readiness.value != SteamGcReadiness.Waiting) {
                        readiness.value = SteamGcReadiness.Ready
                        mutableStatus.value = SteamTrackingStatus(SteamTrackingPhase.Connected)
                        request.result.complete(message.body)
                    }
                }
            }
            SteamBridgeProtocol.Gap -> {
                require(frame.body.size == 8)
                mutableEvents.tryEmit(SteamTrackingEvent.Gap(active, ByteBuffer.wrap(frame.body).long.also { require(it >= 0) }))
            }
            SteamBridgeProtocol.Ack -> {
                require(frame.body.isEmpty())
                require(frame.message in SteamBridgeProtocol.Queued..SteamBridgeProtocol.Submitted)
                val reason = when (frame.message) {
                    SteamBridgeProtocol.QueueRejected -> "Steam bridge queue rejected the request"
                    SteamBridgeProtocol.Expired -> "Steam bridge request expired before submission"
                    SteamBridgeProtocol.SendRejected -> "Steam rejected local GC submission"
                    else -> null
                }
                if (reason != null) pending[frame.job]?.result?.completeExceptionally(SteamGcInterrupted(reason))
            }
            else -> error("Unexpected bridge frame")
        }
    }

    private fun changeSession(next: SteamGcSession?) {
        if (mutableSession.value != next) {
            mutableSession.value = next
            readiness.value = SteamGcReadiness.Unknown
            cancelPending("Steam session changed")
        }
    }

    private fun cancelPending(reason: String) {
        pending.values.forEach { it.result.completeExceptionally(SteamGcInterrupted(reason)) }
        pending.clear()
    }

    private fun requireSession(session: SteamGcSession) {
        if (closed || mutableSession.value != session || readiness.value == SteamGcReadiness.Waiting)
            throw SteamGcInterrupted("Steam GC session is no longer available")
    }

    private suspend fun request(session: SteamGcSession, message: Int, argument: Long = 0): ByteArray = requests.withLock {
        requireSession(session)
        val id = jobs.incrementAndGet()
        val promise = CompletableDeferred<ByteArray>()
        pending[id] = Pending(session, message + 1, promise)
        try {
            withContext(Dispatchers.IO) {
                synchronized(writes) {
                    requireSession(session)
                    val socket = checkNotNull(peer)
                    SteamBridgeProtocol.write(DataOutputStream(socket.getOutputStream()), SteamBridgeProtocol.Frame(
                        SteamBridgeProtocol.Request, session.generation, id, session.accountId, message, SteamBridgeProtocol.argument(argument)))
                }
            }
            withTimeoutOrNull(30_000) { promise.await() } ?: error("Steam did not respond to request $message")
        } catch (e: IOException) {
            throw SteamGcInterrupted("Steam bridge transport disconnected")
        } finally {
            pending.remove(id)
        }
    }

    override suspend fun history(session: SteamGcSession, cursor: Long) = SteamGcCodec.history(request(session, 9112, cursor))
    override suspend fun metadata(session: SteamGcSession, matchId: Long) = SteamGcCodec.metadata(request(session, 9167, matchId), matchId)

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        runCatching { peer?.close() }
        runCatching { listener?.close() }
        scope.cancel()
        changeSession(null)
        if (fileLock != null) runCatching { Files.deleteIfExists(directory.resolve("endpoint")) }
        runCatching { fileLock?.release() }
        runCatching { lockChannel?.close() }
    }
}
