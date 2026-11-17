package gg.hideouthq.customgames

import gg.hideouthq.core.customgames.GameSetup
import gg.hideouthq.core.customgames.LinkEvent
import gg.hideouthq.core.customgames.LinkProcess
import gg.hideouthq.core.customgames.LinkStop
import gg.hideouthq.core.customgames.ProcessIdentity
import gg.hideouthq.core.customgames.SharedPack
import gg.hideouthq.core.customgames.parseLinkEvent
import gg.hideouthq.core.customgames.redactInvites
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.time.Duration

/** Two threads read its output from the moment it starts, so it never blocks on a full pipe, whether or not anyone collects [events]. */
internal class HelperProcess(
    private val process: Process,
    private val log: (String) -> Unit,
    private val windows: Boolean,
    private val joinFlags: (GameSetup, SharedPack?) -> List<String>,
) : LinkProcess {
    private val lines = Channel<LinkEvent>(Channel.UNLIMITED)
    private val stdin = process.outputStream
    private var stdinClosed = false

    override val identity: ProcessIdentity? = identityOf(process.toHandle())

    override val events: Flow<LinkEvent> = lines.receiveAsFlow()

    init {
        thread(isDaemon = true, name = "custom-game-helper-stdout") { readEvents() }
        thread(isDaemon = true, name = "custom-game-helper-stderr") { logErrors() }
    }

    fun give(line: String) {
        synchronized(stdin) {
            if (stdinClosed) return
            stdin.write((line + "\n").toByteArray(StandardCharsets.UTF_8))
            stdin.flush()
        }
    }

    override suspend fun enter(window: Duration) = withContext(Dispatchers.IO) { give("enter ${window.inWholeSeconds}") }

    override suspend fun join(setup: GameSetup, shared: SharedPack?) = withContext(Dispatchers.IO) {
        give("join " + JsonArray(joinFlags(setup, shared).map(::JsonPrimitive)))
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        synchronized(stdin) {
            stdinClosed = true
            try {
                stdin.close()
            } catch (e: IOException) {
            }
        }
    }

    override suspend fun kill() = withContext(Dispatchers.IO) { endNow() }

    fun endNow() = endHelper(process.toHandle(), windows)

    /**
     * `stopped` is only passed on once the process has exited, because on Windows the rest of the server's tree dies as
     * the helper exits, and what comes next is writing to files that tree holds.
     */
    private fun readEvents() {
        var stopped: LinkEvent.Stopped? = null
        readLines(process.inputStream.bufferedReader(StandardCharsets.UTF_8)) { line ->
            when (val event = parseLinkEvent(line)) {
                null -> Unit
                is LinkEvent.Stopped -> {
                    stopped = event
                    event.message?.let { log("Custom games helper stopped: ${event.reason}: ${safeToLog(it)}") }
                }
                else -> lines.trySend(event)
            }
            stopped == null
        }
        process.waitFor()
        lines.trySend(stopped ?: LinkEvent.Stopped(LinkStop.Unknown))
        lines.close()
    }

    private fun logErrors() {
        var logged = 0
        readLines(process.errorStream.bufferedReader(StandardCharsets.UTF_8)) { line ->
            if (logged < MaxLoggedLines) {
                logged++
                log("Custom games helper: ${safeToLog(line)}")
            }
            true
        }
    }

    private inline fun readLines(reader: BufferedReader, onLine: (String) -> Boolean) {
        try {
            while (true) {
                val line = reader.readLine() ?: return
                if (!onLine(line)) return
            }
        } catch (e: IOException) {
        }
    }

    private companion object {
        const val MaxLoggedLines = 2_000
    }
}

private const val MaxLoggedLineLength = 2_000

internal fun safeToLog(line: String): String = redactInvites(line).take(MaxLoggedLineLength)

internal fun identityOf(handle: ProcessHandle): ProcessIdentity? =
    handle.info().startInstant().map { ProcessIdentity(handle.pid(), it.toEpochMilli()) }.orElse(null)

/**
 * On Linux the helper's death interrupts the server through `Pdeathsig`, the one signal Modlock cleans up its Proton tree
 * on, so killing the children too would cut that short. On Windows the helper's job ends the tree, and the children go here as well.
 */
internal fun endHelper(handle: ProcessHandle, windows: Boolean) {
    val children = if (windows) handle.descendants().toList() else emptyList()
    handle.destroyForcibly()
    children.forEach { it.destroyForcibly() }
}
