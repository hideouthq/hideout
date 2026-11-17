package gg.hideouthq.deadlockstats

import gg.hideouthq.core.data.deadlockstats.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.Test
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class SteamBridgeTest {
    @Test fun protocolIsBoundedAndPreservesJobBits() {
        val original = SteamBridgeProtocol.Frame(SteamBridgeProtocol.Gc, -1, Long.MIN_VALUE, 4294967295L, 9113, byteArrayOf(1,2,3))
        val bytes = ByteArrayOutputStream()
        SteamBridgeProtocol.write(DataOutputStream(bytes), original)
        val restored = SteamBridgeProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(original.copy(body = restored.body), restored)
        assertContentEquals(original.body, restored.body)
        val invalid = ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()
        assertFailsWith<IllegalArgumentException> { SteamBridgeProtocol.read(DataInputStream(ByteArrayInputStream(invalid))) }
        val badMagic = bytes.toByteArray().also { it[4] = 0 }
        assertFailsWith<IllegalArgumentException> { SteamBridgeProtocol.read(DataInputStream(ByteArrayInputStream(badMagic))) }
    }

    @Test fun unsupportedBuildDoesNotCreateListenerOrModifyDesktop() = runBlocking {
        val root = Files.createTempDirectory("steam-unsupported-")
        val data = Files.createTempDirectory("steam-unsupported-data-")
        try {
            val installer = LinuxSteamTrackingInstaller({ steamTrackingTargets(listOf(gg.hideouthq.library.SteamInstall(root.toString(), gg.hideouthq.library.SteamClient.Desktop)), null) }, "Linux", data = data)
            assertFalse(installer.install().supported)
            assertEquals(0L, Files.list(root).use { it.count() })
            val server = SteamBridgeServer({ root.resolve("bridge") }, this, installer::inspect, installer::admit)
            server.start()
            assertEquals(SteamTrackingPhase.Unsupported, server.status.value.phase)
            assertFalse(Files.exists(root.resolve("bridge")))
            server.close()
        } finally { root.toFile().deleteRecursively(); data.toFile().deleteRecursively() }
    }

    private class Peer(directory: Path) : AutoCloseable {
        val socket = Socket("127.0.0.1", Files.readAllLines(directory.resolve("endpoint"))[1].toInt()).also { it.soTimeout = 5000 }
        private val input = DataInputStream(socket.getInputStream())
        private val output = DataOutputStream(socket.getOutputStream())
        init {
            val token = Files.readAllLines(directory.resolve("endpoint"))[2].hexToByteArray()
            val hello = ByteBuffer.allocate(40).put(token).putInt(123).putInt(32).array()
            send(SteamBridgeProtocol.Frame(SteamBridgeProtocol.Hello, body = hello))
            assertEquals(SteamBridgeProtocol.Ack, SteamBridgeProtocol.read(input).kind)
        }
        fun send(frame: SteamBridgeProtocol.Frame) = SteamBridgeProtocol.write(output, frame)
        fun bind(generation: Long, account: Long = 555) = send(SteamBridgeProtocol.Frame(SteamBridgeProtocol.Session, generation, account = account, message = 1))
        fun state(generation: Long, state: SteamGcReadiness) = send(SteamBridgeProtocol.Frame(SteamBridgeProtocol.GcState, generation, account = 555, message = state.ordinal))
        fun request(): SteamBridgeProtocol.Frame = SteamBridgeProtocol.read(input).also { assertEquals(SteamBridgeProtocol.Request, it.kind) }
        fun historyReply(request: SteamBridgeProtocol.Frame) {
            val payload = ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(9113 or Int.MIN_VALUE).putInt(9).put(0x59).putLong(request.job).put(8).put(1).array()
            send(SteamBridgeProtocol.Frame(SteamBridgeProtocol.Gc, request.session, account = request.account, message = 9113, body = payload))
        }
        override fun close() = socket.close()
    }

    @Test fun heartbeatCannotSwitchToAnotherProcessWithTheSameNamespacePid(): Unit = runBlocking {
        val root = Files.createTempDirectory("steam-pinned-process-")
        val identity = java.util.concurrent.atomic.AtomicReference(SteamProcessIdentity(100, "first"))
        val server = SteamBridgeServer({ root }, this, { SteamTrackingInstallation(true, true) }, { identity.get() })
        try {
            server.start()
            Peer(root).use { peer ->
                peer.bind(42)
                withTimeout(5000) { server.session.filterNotNull().first() }
                identity.set(SteamProcessIdentity(200, "second"))
                peer.send(SteamBridgeProtocol.Frame(SteamBridgeProtocol.Heartbeat))
                withTimeout(5000) { while (server.session.value != null) delay(5) }
                assertEquals(SteamTrackingPhase.Failed, server.status.value.phase)
            }
        } finally { server.close(); root.toFile().deleteRecursively() }
    }

    @Test fun inaccessibleProcessMetadataKeepsItsDiagnosticAndRecoversOnANewConnection(): Unit = runBlocking {
        val root = Files.createTempDirectory("steam-inaccessible-process-")
        val accessible = java.util.concurrent.atomic.AtomicBoolean(false)
        val server = SteamBridgeServer({ root }, this, { SteamTrackingInstallation(true, true) }, {
            if (!accessible.get()) throw SteamProcessMetadataUnavailable()
            SteamProcessIdentity(123, "fixture")
        })
        try {
            server.start()
            Socket("127.0.0.1", Files.readAllLines(root.resolve("endpoint"))[1].toInt()).use { socket ->
                socket.soTimeout = 5000
                val token = Files.readAllLines(root.resolve("endpoint"))[2].hexToByteArray()
                SteamBridgeProtocol.write(DataOutputStream(socket.getOutputStream()), SteamBridgeProtocol.Frame(
                    SteamBridgeProtocol.Hello, body = ByteBuffer.allocate(40).put(token).putInt(123).putInt(32).array()))
                assertEquals(-1, socket.getInputStream().read(), "unverified process must not receive an acknowledgement")
            }
            withTimeout(5000) { server.status.first { it.phase == SteamTrackingPhase.Failed } }
            delay(1100)
            assertEquals(SteamProcessMetadataUnavailable().message, server.status.value.message)
            assertNull(server.session.value)
            accessible.set(true)
            Peer(root).use { peer ->
                peer.bind(42)
                withTimeout(5000) { server.session.filterNotNull().first() }
                assertEquals(42L, server.session.value!!.generation)
            }
        } finally { server.close(); root.toFile().deleteRecursively() }
    }

    @Test fun readinessAndAcksAreSessionScopedAndMissedWelcomesCanRecover(): Unit = runBlocking {
        val root = Files.createTempDirectory("steam-readiness-")
        val server = SteamBridgeServer({ root }, this, { SteamTrackingInstallation(true, true) }, { SteamProcessIdentity(123, "fixture") })
        suspend fun await(condition: () -> Boolean) { withTimeout(5000) { while (!condition()) delay(5) } }
        try {
            server.start()
            Peer(root).use { peer ->
                peer.bind(42)
                await { server.session.value != null }
                val active = server.session.value!!
                assertEquals(SteamTrackingPhase.WaitingForGc, server.status.value.phase)
                val initial = async(start = CoroutineStart.UNDISPATCHED) { server.history(active) }
                val request = peer.request()
                peer.send(SteamBridgeProtocol.Frame(SteamBridgeProtocol.Ack, 42, request.job, 555, SteamBridgeProtocol.Queued))
                peer.send(SteamBridgeProtocol.Frame(SteamBridgeProtocol.Ack, 42, request.job, 555, SteamBridgeProtocol.Submitted))
                delay(20)
                assertFalse(initial.isCompleted, "local submission never completes a request")
                assertEquals(SteamGcReadiness.Unknown, server.gcState.value)
                peer.historyReply(request)
                assertTrue(initial.await().matches.isEmpty())
                assertEquals(SteamGcReadiness.Ready, server.gcState.value)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { server.history(active) } }
                peer.request()
                peer.state(42, SteamGcReadiness.Waiting)
                assertIs<SteamGcInterrupted>(pending.await().exceptionOrNull())
                assertEquals(active, server.session.value)
                assertEquals(SteamTrackingPhase.WaitingForGc, server.status.value.phase)
                assertFailsWith<SteamGcInterrupted> { server.history(active) }
                peer.state(41, SteamGcReadiness.Ready)
                delay(20)
                assertEquals(SteamGcReadiness.Waiting, server.gcState.value)
                peer.state(42, SteamGcReadiness.Ready)
                await { server.gcState.value == SteamGcReadiness.Ready }
                peer.bind(43)
                await { server.session.value?.generation == 43L }
                peer.historyReply(request)
                delay(20)
                assertEquals(SteamGcReadiness.Unknown, server.gcState.value)
                assertEquals(SteamTrackingPhase.WaitingForGc, server.status.value.phase)
            }
            await { server.session.value == null }
            Peer(root).use { peer ->
                peer.bind(44, 666)
                await { server.session.value?.generation == 44L }
                assertEquals(666L, server.session.value?.accountId)
            }
        } finally { server.close(); root.toFile().deleteRecursively() }
    }

    @Test fun authenticatedAdmissionFailureRequiresRestartUntilAVerifiedConnectionArrives(): Unit = runBlocking {
        val root = Files.createTempDirectory("steam-admission-status-")
        val allowed = java.util.concurrent.atomic.AtomicBoolean(false)
        val admissions = java.util.concurrent.atomic.AtomicInteger()
        val server = SteamBridgeServer({ root }, this, { SteamTrackingInstallation(true, true) },
            { admissions.incrementAndGet(); SteamProcessIdentity(123, "fixture").takeIf { allowed.get() } }, { SteamTrackingStatus(SteamTrackingPhase.WaitingForGame) })
        try {
            server.start()
            val endpoint = Files.readAllLines(root.resolve("endpoint"))
            fun rejected(token: ByteArray) = Socket("127.0.0.1", endpoint[1].toInt()).use { socket ->
                socket.soTimeout = 5000
                val hello = ByteBuffer.allocate(40).put(token).putInt(123).putInt(32).array()
                SteamBridgeProtocol.write(DataOutputStream(socket.getOutputStream()), SteamBridgeProtocol.Frame(SteamBridgeProtocol.Hello, body = hello))
                assertEquals(-1, socket.getInputStream().read())
            }
            rejected(ByteArray(32))
            assertEquals(0, admissions.get(), "authenticate before inspecting a supplied PID")
            assertNotEquals(SteamTrackingPhase.Failed, server.status.value.phase)
            rejected(endpoint[2].hexToByteArray())
            withTimeout(5000) { while (server.status.value.phase != SteamTrackingPhase.Failed) delay(5) }
            assertEquals(SteamTrackingPhase.Failed, server.status.value.phase)
            assertContains(server.status.value.message!!, "Restart Steam")
            delay(1100)
            assertEquals(SteamTrackingPhase.Failed, server.status.value.phase, "startup polling must not hide a restart-required failure")
            allowed.set(true)
            Peer(root).use {
                withTimeout(5000) { while (server.status.value.phase != SteamTrackingPhase.WaitingForGame) delay(5) }
            }
        } finally { server.close(); root.toFile().deleteRecursively() }
    }

    @Test fun admissionChecksActualMappingsAndRejectsReplacementAtTheSamePath(): Unit = runBlocking {
        val fixture = System.getProperty("hideout.steam.fixture")
        org.junit.Assume.assumeTrue(fixture != null)
        val source = Path.of(fixture!!).toAbsolutePath()
        // Use the project filesystem too: /tmp alone misses Btrfs subvolume device semantics.
        val root = Files.createTempDirectory(Files.createDirectories(Path.of("build/steam-admission-tests")), "path with spaces-").toAbsolutePath()
        val architecture = requireNotNull(SteamArchitecture.read(source))
        val binary = Files.createDirectories(root.resolve(architecture.clientDirectory)).resolve("steam")
        val module = binary.resolveSibling("steamclient.so")
        val library = root.resolve("bridge.so")
        Files.copy(source, binary); binary.toFile().setExecutable(true)
        Files.copy(source.parent.resolve(if (architecture == SteamArchitecture.I386) "libabi_fixture.so" else "libabi64_fixture.so"), module)
        Files.copy(source.parent.resolve("libhideout_steam_bridge.so"), library)
        val hash = SteamBridgeCompatibility.hash(library)
        val process = ProcessBuilder(binary.toString(), "--mapped-only", module.toString(), library.toString()).start()
        try {
            assertEquals("ready", process.inputStream.bufferedReader().readLine())
            val hello = SteamBridgeHello(process.pid(), architecture.bits)
            val target = SteamInstallationTarget("fixture", gg.hideouthq.library.SteamClient.Desktop, root, root, architecture, true, null)
            fun admit(hello: SteamBridgeHello, hash: String = SteamBridgeCompatibility.hash(library)) = SteamProcessResolver().admit(hello, target, library, hash)
            assertNotNull(admit(hello, hash))
            val start = Files.readString(Path.of("/proc/${process.pid()}/stat")).substringAfterLast(") ").split(' ')[19]
            val record = root.resolve("startup-${process.pid()}-$start.json")
            Files.writeString(record, """{"version":1,"pid":${process.pid()},"start":"$start","stage":"incompatible","reason":"map_layout_drift"}""")
            Files.setPosixFilePermissions(record, PosixFilePermissions.fromString("rw-------"))
            assertEquals(SteamTrackingPhase.Failed, readSteamStartupStatus(root, target)?.phase)
            val staleStart = (start.toLong() - 1).toString()
            Files.delete(record)
            val stale = root.resolve("startup-${process.pid()}-$staleStart.json")
            Files.writeString(stale, """{"version":1,"pid":${process.pid()},"start":"$staleStart","stage":"armed","reason":"compatible"}""")
            assertNull(readSteamStartupStatus(root, target))
            assertNull(admit(hello.copy(bits = if (architecture == SteamArchitecture.I386) 64 else 32), hash))
            assertNull(admit(hello, "00".repeat(32)))
            val replacement = root.resolve("replacement.so")
            Files.copy(module, replacement)
            Files.move(replacement, module, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertNull(admit(hello, hash), "a matching path cannot bless a replaced module mapping")
        } finally { process.destroy(); process.waitFor(); root.toFile().deleteRecursively() }
    }

    @Test fun desktopOverridePreservesArgumentsActionsAndRejectsInvalidPaths() {
        val original = "[Desktop Entry]\nExec=/usr/bin/steam -silent %U\nDBusActivatable=true\n[Desktop Action Store]\nExec=steam steam://store\n"
        val wrapped = SteamDesktopEntry.wrap(original, "/home/u/a b/launcher")
        assertTrue("Exec=\"/home/u/a b/launcher\" -- /usr/bin/steam -silent %U" in wrapped)
        assertTrue("Exec=\"/home/u/a b/launcher\" -- steam steam://store" in wrapped)
        assertTrue("DBusActivatable=false" in wrapped)
        assertFailsWith<IllegalArgumentException> { SteamDesktopEntry.wrap(original, "/tmp/bad\nExec=bad") }
    }

    @Test fun nativeFixtureAuthenticatesAndExchangesTypedGcRequests(): Unit = runBlocking {
        val fixture = System.getProperty("hideout.steam.fixture")
        org.junit.Assume.assumeTrue("Run native/steam-bridge/tools/test.sh for the native integration test", fixture != null)
        val executable = Path.of(fixture!!).toAbsolutePath().normalize()
        assertTrue(Files.isExecutable(executable), "Build native/steam-bridge before running this integration test")
        val root = Files.createTempDirectory("steam-bridge-test-")
        val directory = root.resolve("bridge")
        val server = SteamBridgeServer({ directory }, this, { SteamTrackingInstallation(true, true) }, { hello ->
            SteamProcessResolver().resolve(hello.pid, executable, requireNotNull(SteamArchitecture.read(executable)))
                .takeIf { hello.bits == System.getProperty("hideout.steam.bits", "64").toInt() }
        })
        var process: Process? = null
        try {
            server.start()
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("endpoint"))))
            val port = Files.readAllLines(directory.resolve("endpoint"))[1].toInt()
            Socket("127.0.0.1", port).use { bad ->
                bad.soTimeout = 5000
                SteamBridgeProtocol.write(DataOutputStream(bad.getOutputStream()), SteamBridgeProtocol.Frame(SteamBridgeProtocol.Hello, body = ByteArray(40)))
                assertEquals(-1, bad.getInputStream().read())
            }
            assertNull(server.session.value)
            process = ProcessBuilder(executable.toString(), directory.resolve("endpoint").toString(), "--drop-first-history").redirectErrorStream(true).start()
            val session = withTimeout(10_000) { server.session.filterNotNull().first() }
            assertEquals(SteamGcSession(555, 42), session)
            val timeout = assertFailsWith<IllegalStateException> { server.history(session) }
            assertFalse(timeout is CancellationException, "A GC response timeout must not cancel collection")
            assertEquals("Steam did not respond to request 9112", timeout.message)
            assertEquals(session, server.session.value)
            val history = server.history(session)
            assertEquals(123L, history.matches.single().matchId)
            assertEquals(5, history.matches.single().kills)
            assertEquals("http://replay1.valve.net/1422450/123_42.meta.bz2", server.metadata(session, 123).url)
            assertFailsWith<SteamGcInterrupted> { server.history(session.copy(generation = 43)) }
        } finally {
            server.close()
            process?.destroy()
            process?.waitFor()
            assertFalse(Files.exists(directory.resolve("endpoint")))
            root.toFile().deleteRecursively()
        }
    }
}
