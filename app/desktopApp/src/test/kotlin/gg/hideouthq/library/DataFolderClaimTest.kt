package gg.hideouthq.library

import gg.hideouthq.core.data.ManifestLoad
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.File
import java.net.StandardProtocolFamily
import java.net.URLClassLoader
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class DataFolderClaimTest {
    private val temp: Path = Files.createTempDirectory("claim").toRealPath()
    private val children = mutableListOf<Process>()
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun stopEverything() {
        cleanups.asReversed().forEach { it() }
        children.forEach { it.destroyForcibly().waitFor(10, TimeUnit.SECONDS) }
        temp.toFile().deleteRecursively()
    }

    private val noEnvironment: (String) -> String? = { null }
    private val quick = ClaimPatience(total = 1.5.seconds, attempt = 300.milliseconds, pause = 50.milliseconds)

    private val brief = ClaimPatience(total = 300.milliseconds, attempt = 100.milliseconds, pause = 20.milliseconds)

    private fun folder(name: String): Path = temp.resolve(name)

    private fun owner(folder: Path): DataFolderClaim.Owner = assertIs<DataFolderClaim.Owner>(claimDataFolder(folder, noEnvironment, quick)).also { claim ->
        cleanups += { claim.lock.close() }
    }

    private fun DataFolderClaim.Owner.serving(focus: () -> Boolean): DataFolderClaim.Owner = also {
        val serving = serveFocusRequests({ line -> System.err.println(line) }, focus = focus)
        cleanups += { serving.close() }
    }

    private fun launch(vararg arguments: String): Launch {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(listOf(java, "-cp", testClassPath(), ClaimingLaunch::class.java.name) + arguments)
            .redirectErrorStream(true)
            .start()
        children += process
        return Launch(process)
    }

    private class Launch(val process: Process) {
        private val output: BufferedReader = process.inputReader()

        fun nextLine(): String? = output.readLine()

        fun awaitLine(wanted: String): String {
            while (true) {
                val line = output.readLine() ?: throw AssertionError("the launch ended before saying '$wanted'")
                if (line.startsWith(wanted)) return line
            }
        }

        fun exitCode(): Int {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the launch never ended")
            return process.exitValue()
        }

        fun stop() {
            process.outputStream.close()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the launch never ended")
        }
    }

    private fun testClassPath(): String {
        val entries = LinkedHashSet(System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() })
        generateSequence(ClaimingLaunch::class.java.classLoader) { it.parent }.filterIsInstance<URLClassLoader>().forEach { loader ->
            loader.urLs.forEach { entries += Path.of(it.toURI()).toString() }
        }
        return entries.joinToString(File.pathSeparator)
    }

    @Test
    fun aSecondLaunchHandsOffToTheRunningOneWhichFocusesOnce() {
        val data = folder("data")
        val first = launch("serve", data.toString())
        first.awaitLine("owner")

        val second = launch("claim", data.toString())
        second.awaitLine("HandedOff")
        assertEquals(0, second.exitCode())

        first.awaitLine("focused")
        val third = launch("claim", data.toString())
        third.awaitLine("HandedOff")
        first.awaitLine("focused")
        first.stop()
    }

    @Test
    fun launchesRacingOnAFreshFolderEndWithOneOwner() {
        val data = folder("data")
        val racers = List(4) { launch("serve", data.toString()) }
        val outcomes = Executors.newFixedThreadPool(racers.size).let { pool ->
            try {
                racers.map { racer -> pool.submit<String> { racer.awaitOutcome() } }.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdown()
            }
        }
        assertEquals(listOf("HandedOff", "HandedOff", "HandedOff", "owner"), outcomes.sorted())
    }

    private fun Launch.awaitOutcome(): String {
        while (true) {
            val line = nextLine() ?: throw AssertionError("a racer ended without an outcome")
            if (line == "owner" || line == "HandedOff") return line
        }
    }

    @Test
    fun differentFoldersStayIndependent() {
        val first = launch("serve", folder("one").toString())
        first.awaitLine("owner")
        val other = launch("serve", folder("two").toString())
        other.awaitLine("owner")
        first.stop()
        other.stop()
    }

    @Test
    fun aKilledOwnerLeavesNothingThatBlocksTheNextLaunch() {
        val data = folder("data")
        val first = launch("serve", data.toString())
        first.awaitLine("owner")
        val left = focusSocketPath(data.toRealPath(), noEnvironment)!!
        assertTrue(Files.exists(left), "the test needs the socket's file to be there")

        first.process.destroyForcibly()
        assertTrue(first.process.waitFor(30, TimeUnit.SECONDS))
        assertTrue(Files.exists(left), "a killed process can't clean up, which is the point of this test")

        val next = launch("serve", data.toString())
        next.awaitLine("owner")
        val after = launch("claim", data.toString())
        after.awaitLine("HandedOff")
        next.awaitLine("focused")
        next.stop()
    }

    @Test
    fun anOwnerAskedToStopRemovesItsSocket() {
        val data = folder("data")
        val owner = launch("serve", data.toString())
        owner.awaitLine("owner")
        val socket = focusSocketPath(data.toRealPath(), noEnvironment)!!
        assertTrue(Files.exists(socket))

        owner.process.destroy()
        assertTrue(owner.process.waitFor(30, TimeUnit.SECONDS))
        assertTrue(Files.notExists(socket))
    }

    @Test
    fun aLaunchDuringAClosingOwnerTakesOverInsteadOfVanishing() {
        val data = folder("data")
        val closing = launch("close-after-refusing", data.toString(), "800")
        closing.awaitLine("owner")

        val relaunch = launch("serve", data.toString())
        relaunch.awaitLine("owner")
        closing.awaitLine("refused")
        assertEquals(0, closing.exitCode())
        relaunch.stop()
    }

    @Test
    fun anOwnerThatStopsAnsweringCostsABoundedWaitAndNoSecondOwner() {
        val data = folder("data")
        val released = CountDownLatch(1)
        cleanups += { released.countDown() }
        owner(data).serving {
            released.await()
            true
        }
        val started = TimeSource.Monotonic.markNow()
        val claim = claimDataFolder(data, noEnvironment, brief)
        val waited = started.elapsedNow()
        assertIs<DataFolderClaim.NoAnswer>(claim)
        assertTrue(waited >= brief.total && waited < 2.seconds, "waited $waited")
    }

    @Test
    fun aFrozenOwnerProcessCostsABoundedWait() {
        if (!Files.exists(Path.of("/proc/self"))) return
        val data = folder("data")
        val frozen = launch("serve", data.toString())
        frozen.awaitLine("owner")
        Runtime.getRuntime().exec(arrayOf("kill", "-STOP", frozen.process.pid().toString())).waitFor()
        cleanups += { Runtime.getRuntime().exec(arrayOf("kill", "-CONT", frozen.process.pid().toString())).waitFor() }

        val started = TimeSource.Monotonic.markNow()
        val claim = claimDataFolder(data, noEnvironment, brief)
        assertIs<DataFolderClaim.NoAnswer>(claim)
        assertTrue(started.elapsedNow() < 2.seconds, "waited ${started.elapsedNow()}")
    }

    @Test
    fun anOwnerWithoutASocketStillKeepsASecondWindowFromOpening() {
        val data = folder("data")
        owner(data)
        assertIs<DataFolderClaim.NoAnswer>(claimDataFolder(data, noEnvironment, brief))
    }

    @Test
    fun theStoreTakesTheClaimedLockAndAnotherIsStillRefused() = runBlocking<Unit> {
        val data = folder("data")
        val claim = owner(data)
        val store = FileLibraryStore(data, claim.lock)
        assertEquals(ManifestLoad.Absent, store.load())
        assertIs<ManifestLoad.Unreadable>(FileLibraryStore(data).load())
    }

    @Test
    fun aHandOffGrantsForegroundOnlyWhenAnotherHideoutIsRunning() {
        val data = folder("data")
        val grants = AtomicInteger()
        val claim = claimDataFolder(data, noEnvironment, quick) { grants.incrementAndGet() }
        assertIs<DataFolderClaim.Owner>(claim)
        cleanups += { claim.lock.close() }
        assertEquals(0, grants.get())

        claim.serving { true }
        assertEquals(DataFolderClaim.HandedOff, claimDataFolder(data, noEnvironment, quick) { grants.incrementAndGet() })
        assertEquals(1, grants.get())
    }

    @Test
    fun aSecondLaunchWithALinkHandsItToTheRunningOne() {
        val data = folder("data")
        val first = launch("serve", data.toString())
        first.awaitLine("owner")

        val second = launch("claim", data.toString(), "hideout://join/tcSomeInviteCode")
        second.awaitLine("HandedOff")
        assertEquals(0, second.exitCode())

        first.awaitLine("focused")
        assertEquals("link hideout://join/tcSomeInviteCode", first.awaitLine("link"))
        first.stop()
    }

    @Test
    fun aLinkGoesThroughOnlyWhenTheOwnerRaisedItsWindow() {
        val data = folder("data")
        val links = CopyOnWriteArrayList<String>()
        val claim = owner(data)
        val serving = claim.serveFocusRequests({}, links::add) { false }
        cleanups += { serving.close() }

        assertIs<DataFolderClaim.NoAnswer>(claimDataFolder(data, noEnvironment, brief, link = "hideout://join/tcCode"))
        assertEquals(emptyList(), links)
    }

    @Test
    fun theLongestInviteLinkArrivesAndOneThatCanNotTravelAsOneLineArrivesEmpty() {
        val data = folder("data")
        val links = CopyOnWriteArrayList<String>()
        val claim = owner(data)
        val serving = claim.serveFocusRequests({}, links::add) { true }
        cleanups += { serving.close() }

        val longest = "https://example.test/join#" + "A".repeat(2048)
        for (link in listOf(longest, "hideout://join/" + "A".repeat(4096), "hideout://join/a\nb")) {
            assertEquals(DataFolderClaim.HandedOff, claimDataFolder(data, noEnvironment, quick, link = link))
        }
        assertEquals(listOf(longest, "", ""), links, "the longest invite's link travels whole")
    }

    @Test
    fun noReasonALaunchGivesEverQuotesItsLink() {
        val data = folder("data")
        val released = CountDownLatch(1)
        cleanups += { released.countDown() }
        owner(data).serving {
            released.await()
            true
        }

        val claim = assertIs<DataFolderClaim.NoAnswer>(claimDataFolder(data, noEnvironment, brief, link = "hideout://join/tcSecretCode"))
        assertTrue("tcSecretCode" !in claim.reason, claim.reason)
    }

    @Test
    fun onlyTheFixedRequestFocusesAndOtherClientsDoNotStopTheService() {
        val data = folder("data")
        val focused = AtomicInteger()
        val claim = owner(data).serving {
            focused.incrementAndGet()
            true
        }
        val socket = assertNotNull(claim.focusSocket)

        for (junk in listOf("hello", "X".repeat(1 shl 16), "", "hideout link no line end", "hideout links\n", "hideout focus and more\n")) {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socket))
                val bytes = ByteBuffer.wrap(junk.encodeToByteArray())
                channel.configureBlocking(false)
                channel.write(bytes)
            }
        }
        assertEquals(0, focused.get())

        assertEquals(DataFolderClaim.HandedOff, claimDataFolder(data, noEnvironment, quick))
        assertEquals(1, focused.get())
    }

    @Test
    fun onlyThisUserCanReachTheSocket() {
        if (!temp.fileSystem.supportedFileAttributeViews().contains("posix")) return
        val claim = owner(folder("data")).serving { true }
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(assertNotNull(claim.focusSocket))))
    }

    @Test
    fun closingTheServiceLeavesNoFileAndAnotherServiceCanBindAgain() {
        val data = folder("data")
        val claim = owner(data)
        val socket = assertNotNull(claim.focusSocket)
        val focused = AtomicInteger()
        claim.serveFocusRequests({}) { true }.close()
        assertTrue(Files.notExists(socket))
        val again = claim.serveFocusRequests({}) {
            focused.incrementAndGet()
            true
        }
        cleanups += { again.close() }
        assertEquals(DataFolderClaim.HandedOff, claimDataFolder(data, noEnvironment, quick))
        assertEquals(1, focused.get())
    }

    @Test
    fun aDeepDataFolderKeepsItsSocketWithinTheAddressLimit() {
        val deep = Files.createDirectories(temp.resolve("d".repeat(120)))
        val runtime = Files.createDirectories(temp.resolve("run"))
        val environment = { name: String -> if (name == "XDG_RUNTIME_DIR") runtime.toString() else null }

        val socket = assertNotNull(focusSocketPath(deep.toRealPath(), environment))
        assertTrue(socket.startsWith(runtime.toRealPath()) || socket.startsWith(runtime))
        assertTrue(socket.toString().encodeToByteArray().size < 108)
        assertNotEquals(socket, focusSocketPath(Files.createDirectories(temp.resolve("e".repeat(120))).toRealPath(), environment), "another folder's")
        assertEquals(socket, focusSocketPath(deep.toRealPath(), environment), "both launches have to work out the same path")

        assertNull(focusSocketPath(deep.toRealPath(), noEnvironment))
        assertEquals(temp.toRealPath().resolve("hideout.sock"), focusSocketPath(temp.toRealPath(), environment))
    }

    @Test
    fun aDeepDataFolderStillHandsOffThroughItsRuntimeSocket() {
        val deep = temp.resolve("d".repeat(120))
        val runtime = Files.createDirectories(temp.resolve("run"))
        val environment = { name: String -> if (name == "XDG_RUNTIME_DIR") runtime.toString() else null }
        val focused = AtomicInteger()

        val claim = assertIs<DataFolderClaim.Owner>(claimDataFolder(deep, environment, quick))
        cleanups += { claim.lock.close() }
        claim.serving {
            focused.incrementAndGet()
            true
        }
        assertEquals(DataFolderClaim.HandedOff, claimDataFolder(deep, environment, quick))
        assertEquals(1, focused.get())
    }

    @Test
    fun aFailedBindLeavesTheOwnerRunningAndSaysWhy() {
        val data = folder("data")
        val claim = owner(data)
        Files.createDirectories(assertNotNull(claim.focusSocket).resolve("in-the-way"))
        val notes = mutableListOf<String>()
        claim.serveFocusRequests(notes::add) { true }.close()
        assertEquals(1, notes.size)
    }
}

object ClaimingLaunch {
    @JvmStatic
    fun main(args: Array<String>) {
        val (mode, folder) = args
        val patience = ClaimPatience(total = 5.seconds, attempt = 1.seconds, pause = 50.milliseconds)
        when (val claim = claimDataFolder(Path.of(folder), System::getenv, patience, link = args.getOrNull(2)?.takeIf { mode == "claim" })) {
            is DataFolderClaim.Owner -> when (mode) {
                "claim" -> say("owner")
                "serve" -> {
                    claim.serveFocusRequests(::say, onLink = { say("link $it") }) {
                        say("focused")
                        true
                    }
                    say("owner")
                    while (System.`in`.read() >= 0) Unit
                }
                "close-after-refusing" -> {
                    val refused = CountDownLatch(1)
                    claim.serveFocusRequests(::say) {
                        say("refused")
                        refused.countDown()
                        false
                    }
                    say("owner")
                    refused.await()
                    Thread.sleep(args[2].toLong())
                }
            }
            else -> say(claim::class.simpleName!!)
        }
    }

    private fun say(line: String) {
        println(line)
        System.out.flush()
    }
}
