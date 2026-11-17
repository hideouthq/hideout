package gg.hideouthq.tools.preview

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

internal class SlowProxy(private val target: String, private val bytesPerSecond: Int) : AutoCloseable {
    private val server = ServerSocket(0, Backlog, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool { Thread(it, "slow-proxy").apply { isDaemon = true } }
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<String>()

    init {
        pool.execute {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute { relay(client) }
            }
        }
    }

    private fun relay(client: Socket) {
        runCatching {
            URI(target).let { Socket(it.host, it.port) }.use { upstream ->
                client.use {
                    pool.execute { runCatching { recordHeads(client.getInputStream(), upstream.getOutputStream()) }; runCatching { upstream.close() } }
                    throttle(upstream.getInputStream(), client.getOutputStream())
                }
            }
        }
    }

    private fun recordHeads(input: InputStream, output: OutputStream) {
        val pending = StringBuilder()
        val buffer = ByteArray(4096)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            output.flush()
            pending.append(String(buffer, 0, read, Charsets.ISO_8859_1))
            while (true) {
                val end = pending.indexOf("\r\n\r\n")
                if (end < 0) break
                requests += pending.substring(0, end)
                pending.delete(0, end + 4)
            }
        }
    }

    private fun throttle(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(Chunk)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
            output.flush()
            Thread.sleep(read * 1000L / bytesPerSecond)
        }
    }

    override fun close() {
        server.close()
        pool.shutdownNow()
    }

    private companion object {
        const val Backlog = 50
        const val Chunk = 16 * 1024
    }
}
