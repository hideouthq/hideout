package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.InstallOutcome
import gg.hideouthq.core.appupdate.PackageFormat
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.customgames.ProcessIdentity
import gg.hideouthq.customgames.identityOf
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstallersTest {
    private val root = Files.createTempDirectory("hideout-installers")
    private val pkg = ReleasePackage(AppVersion(1, 2, 0), PackageFormat.AppImage, "https://example.test/p", 3, "a".repeat(64))
    private val started = mutableListOf<ProcessSpec>()
    private val starter: StartProcess = { spec ->
        started += spec
        Finished(0)
    }

    @AfterTest
    fun cleanUp() = root.toFile().deleteRecursively().let { }

    private fun appImageSwap(relaunch: Boolean, start: StartProcess = starter): Pair<Path, InstallOutcome> {
        val target = Files.createDirectories(root.resolve("Apps")).resolve("Hideout-1.1.0-x86_64.AppImage")
        Files.writeString(target, "old")
        Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ))
        val cached = root.resolve("cached.AppImage")
        Files.writeString(cached, "new")
        return target to runBlocking { AppImageInstaller(target, 4242, { cached }, start).replace(pkg, relaunch) }
    }

    @Test
    fun anAppImageIsReplacedUnderTheSameNameKeepingItsModeAndExecutable() {
        val (target, outcome) = appImageSwap(relaunch = false)

        assertEquals(InstallOutcome.Done, outcome)
        assertEquals("new", Files.readString(target))
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ),
            Files.getPosixFilePermissions(target),
        )
        assertEquals(listOf(target.fileName.toString()), Files.list(target.parent).use { it.toList() }.map { it.fileName.toString() }, "nothing else stays in the user's folder")
    }

    @Test
    fun quittingReplacesTheAppImageWithoutStartingIt() {
        appImageSwap(relaunch = false)
        assertEquals(emptyList(), started)
    }

    @Test
    fun restartingStartsTheNewAppImageFromItsFolderAfterThisProcessWithoutTheOldRuntimesVariables() {
        val (target, _) = appImageSwap(relaunch = true)

        assertEquals(listOf(startAfterExit(4242, target, setOf("APPDIR", "APPIMAGE", "ARGV0", "OWD"))), started)
        assertEquals(target.parent, started.single().folder)
    }

    @Test
    fun aRelaunchThatThrowsStillCountsAsInstalledBecauseTheNewFileIsInPlace() {
        for (failure in listOf(IOException("no such program"), IllegalStateException("refused"))) {
            val (target, outcome) = appImageSwap(relaunch = true, start = { throw failure })

            assertEquals(InstallOutcome.Done, outcome, "$failure")
            assertEquals("new", Files.readString(target), "$failure")
        }
    }

    @Test
    fun aCachedPackageThatHasGoneFailsAndLeavesTheAppImageAndFolderClean() {
        val target = Files.createDirectories(root.resolve("Apps")).resolve("Hideout.AppImage")
        Files.writeString(target, "old")

        val outcome = runBlocking { AppImageInstaller(target, 4242, { root.resolve("gone") }, starter).replace(pkg, relaunch = true) }

        assertIs<InstallOutcome.Failed>(outcome)
        assertEquals("old", Files.readString(target))
        assertEquals(1, Files.list(target.parent).use { it.count() })
        assertEquals(emptyList(), started)
    }

    @Test
    fun aRunningScriptCarriesOnFromItsOldFileAndItsPathStartsTheNewOneOnceItHasGone() {
        val target = Files.createDirectories(root.resolve("Apps")).resolve("app.sh")
        val out = root.resolve("out.txt")
        val waitFile = root.resolve("go")
        fun script(tag: String) = "#!/bin/sh\necho $tag-started >> '$out'\nif [ \"$tag\" = old ]; then while [ ! -e '$waitFile' ]; do sleep 0.05; done; echo old-still-here >> '$out'; fi\n"
        Files.writeString(target, script("old"))
        Files.setPosixFilePermissions(target, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
        val cached = root.resolve("cached").also { Files.writeString(it, script("new")) }

        val running = ProcessBuilder(target.toString()).start()
        waitUntil { Files.exists(out) }
        val outcome = runBlocking { AppImageInstaller(target, running.pid(), { cached }, startForReal).replace(pkg, relaunch = true) }
        assertEquals(InstallOutcome.Done, outcome)
        Thread.sleep(500)
        assertTrue(running.isAlive, "the old copy runs on after its path was replaced")
        assertEquals(listOf("old-started"), Files.readAllLines(out), "the new copy waits for the old one, which holds the library's lock")
        Files.writeString(waitFile, "")
        assertTrue(running.waitFor(5, TimeUnit.SECONDS))
        waitUntil { Files.readAllLines(out).contains("new-started") }

        assertEquals(listOf("old-started", "old-still-here", "new-started"), Files.readAllLines(out))
    }

    @Test
    fun aParentThatNeverReapsTheOldCopyDoesNotHoldUpTheNewOne() {
        val out = root.resolve("zombie.txt")
        val script = root.resolve("new.sh")
        Files.writeString(script, "#!/bin/sh\necho started >> '$out'\n")
        script.toFile().setExecutable(true)
        val parent = ProcessBuilder("sh", "-c", "sh -c 'exit 0' & echo \$!; exec sleep 20").start()
        val zombie = parent.inputStream.bufferedReader().readLine().toLong()
        waitUntil { Files.readString(Path.of("/proc/$zombie/status")).contains("State:\tZ") }

        startForReal(startAfterExit(zombie, script))
        waitUntil { Files.exists(out) }
        parent.destroyForcibly()

        assertEquals("started", Files.readString(out).trim())
    }

    @Test
    fun aRealStartDropsTheVariablesItIsToldTo() {
        val out = root.resolve("env.txt")
        val script = root.resolve("env.sh")
        Files.writeString(script, "#!/bin/sh\necho \"[\$PATH_ONLY_IN_TEST][\${HOME:+home}]\" > '$out'\n")
        script.toFile().setExecutable(true)

        val process = startForReal(ProcessSpec(listOf(script.toString()), root, setOf("HOME")))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))

        assertEquals("[][]", Files.readString(out).trim())
    }

    @Test
    fun aDebInstallsThroughPkexecAptGetAndAnRpmThroughPkexecDnf() {
        val executable = Path.of("/opt/hideout/Hideout")
        val file = Path.of("/home/me/.cache/hideout/app-update/abc.deb")

        runBlocking {
            SystemPackageInstaller("apt-get", executable, 1, { file }, starter).install(pkg)
            SystemPackageInstaller("dnf", executable, 1, { file }, starter).install(pkg)
        }

        assertEquals(
            listOf(listOf("pkexec", "apt-get", "install", "-y", file.toString()), listOf("pkexec", "dnf", "install", "-y", file.toString())),
            started.map { it.command },
        )
    }

    @Test
    fun relaunchingASystemPackageStartsTheInstalledExecutableAfterThisProcess() {
        val outcome = runBlocking { SystemPackageInstaller("apt-get", Path.of("/opt/hideout/Hideout"), 4242, { root }, starter).relaunch() }

        assertEquals(InstallOutcome.Done, outcome)
        assertEquals(listOf(startAfterExit(4242, Path.of("/opt/hideout/Hideout"))), started)
    }

    @Test
    fun pkexecsExitCodesSayWhoStoppedIt() {
        assertEquals(InstallOutcome.Done, pkexecOutcome(0))
        assertEquals(InstallOutcome.Cancelled, pkexecOutcome(126))
        assertIs<InstallOutcome.Failed>(pkexecOutcome(127))
        assertIs<InstallOutcome.Failed>(pkexecOutcome(1))
    }

    @Test
    fun aDismissedPromptReachesTheCallerAsCancelled() {
        val outcome = runBlocking { SystemPackageInstaller("apt-get", Path.of("/opt/hideout/Hideout"), 1, { root }, { Finished(126) }).install(pkg) }
        assertEquals(InstallOutcome.Cancelled, outcome)
    }

    @Test
    fun pkexecThatWontStartFails() {
        val outcome = runBlocking { SystemPackageInstaller("apt-get", Path.of("/opt/hideout/Hideout"), 1, { root }, { throw IOException("no pkexec") }).install(pkg) }
        assertIs<InstallOutcome.Failed>(outcome)
    }

    @Test
    fun theMsiHelperIsACopyOfTheExecutableStartedOutsideTheInstalledFolderWithAllItNeeds() {
        val installed = Files.createDirectories(root.resolve("Hideout"))
        val executable = installed.resolve("Hideout.exe").also { Files.writeString(it, "the program") }
        val folder = Files.createDirectories(root.resolve("Cache/app-update"))
        val msi = folder.resolve("abc.msi")

        val outcome = runBlocking { MsiInstaller(executable, folder, 4242, { msi }, starter).replace(pkg, relaunch = true) }

        assertEquals(InstallOutcome.Done, outcome)
        val helper = folder.resolve("finish-update.exe")
        assertEquals("the program", Files.readString(helper))
        val spec = started.single()
        assertEquals(folder, spec.folder)
        assertEquals(helper.toString(), spec.command.first())
        assertEquals(FinishRequest(4242, msi, folder.resolve("finish-update.log"), executable, relaunch = true), FinishRequest.parse(spec.command.drop(1)))
        assertTrue(FinishingUpdate(folder).handOff(null, { it == identityOf(ProcessHandle.current()) }, System.currentTimeMillis()), "the started helper is announced")
    }

    @Test
    fun anExecutableThatCannotBeCopiedFails() {
        val outcome = runBlocking { MsiInstaller(root.resolve("gone.exe"), root, 1, { root }, starter).replace(pkg, relaunch = false) }
        assertIs<InstallOutcome.Failed>(outcome)
        assertEquals(emptyList(), started)
    }

    @Test
    fun aFinishRequestWithANonAsciiFolderCrossesTheCommandLineAsAscii() {
        val request = FinishRequest(7, Path.of("/c/a b/x.msi"), Path.of("/c/Jürgen 用户/x.log"), Path.of("/c/Hideout.exe"), false)
        val arguments = request.toArguments()

        assertTrue(arguments.all { argument -> argument.all { it.code < 128 } })
        assertEquals(request, FinishRequest.parse(arguments))
    }

    @Test
    fun aMangledFinishRequestDoesNothing() {
        fun encoded(text: String) = java.util.Base64.getUrlEncoder().encodeToString(text.toByteArray())

        assertEquals(null, FinishRequest.parse(listOf("--finish-app-update")))
        assertEquals(null, FinishRequest.parse(listOf("--finish-app-update", "not base64 !!")))
        assertEquals(null, FinishRequest.parse(listOf("--finish-app-update", encoded("pid\na\nb\nc\ntrue"))))
        assertEquals(null, FinishRequest.parse(listOf("--finish-app-update", encoded("1\na\nb\nc\nmaybe"))))
        assertEquals(null, FinishRequest.parse(listOf("--finish-app-update", encoded("1\na\nb"))))
        assertEquals(null, FinishRequest.parse(listOf("--finish-app-update", encoded("1\na\u0000\nb\nc\ntrue"))))
        assertEquals(2, finishAppUpdate(listOf("--finish-app-update")))
    }

    @Test
    fun msiexecsExitCodesCountAsTheContractSays() {
        for (success in listOf(0, 3010, 1641)) assertEquals(0, finish(listOf(success)).exit, "$success")
        for (failure in listOf(1, 1603)) assertEquals(1, finish(listOf(failure)).exit, "$failure")
    }

    private fun finish(codes: List<Int>, relaunch: Boolean = true, appGone: Boolean = true, reopen: String? = null): FinishRun {
        val log = Files.createDirectories(root.resolve("update")).resolve("finish.log")
        val executable = root.resolve("Hideout/Hideout.exe")
        val request = FinishRequest(99, root.resolve("x y.msi"), log, executable, relaunch)
        val finishing = FinishingUpdate(log.parent)
        finishing.announce(Helper)
        reopen?.let { assertTrue(finishing.handOff(it, { true }, Helper.startedAtMillis)) }
        val commands = mutableListOf<ProcessSpec>()
        val pauses = mutableListOf<Long>()
        val waited = mutableListOf<Long>()
        val remaining = codes.toMutableList()
        val finish = FinishAppUpdate(
            start = { spec ->
                commands += spec
                Finished(if (spec.command.first() == "msiexec") remaining.removeFirst() else 0)
            },
            waitForExit = { waited += it; appGone },
            pause = { pauses += it },
        )
        val exit = finish.run(request)
        assertFalse(finishing.handOff(null, { true }, Helper.startedAtMillis), "the helper's announcement is gone once it ends")
        return FinishRun(exit, request, commands, pauses, waited, log.resolveSibling("finish.log.result"))
    }

    private class FinishRun(val exit: Int, val request: FinishRequest, val commands: List<ProcessSpec>, val pauses: List<Long>, val waited: List<Long>, val result: Path) {
        val msiexecs get() = commands.filter { it.command.first() == "msiexec" }
        val relaunches get() = commands.filter { it.command.first() != "msiexec" }
    }

    @Test
    fun theHelperWaitsForTheAppRunsTheMsiSilentlyAndStartsTheInstalledApp() {
        val run = finish(listOf(0))

        assertEquals(0, run.exit)
        assertEquals(listOf(99L), run.waited)
        assertEquals(
            listOf("msiexec", "/i", run.request.msi.toString(), "/qn", "/norestart", "MSIRESTARTMANAGERCONTROL=Disable", "/l*v", run.request.log.toString()),
            run.msiexecs.single().command,
        )
        assertEquals(run.request.log.parent, run.msiexecs.single().folder, "msiexec's working folder is outside what it replaces")
        assertEquals(ProcessSpec(listOf(run.request.executable.toString()), run.request.executable.parent), run.relaunches.single())
        assertContains(Files.readString(run.result), "msiexec exited with 0")
    }

    @Test
    fun quittingInstallsAndStartsNothing() {
        val run = finish(listOf(0), relaunch = false)
        assertEquals(1, run.msiexecs.size)
        assertEquals(emptyList(), run.relaunches)
    }

    @Test
    fun anotherInstallRunningIsWaitedOutTwentySecondsApart() {
        val run = finish(listOf(1618, 1618, 0))

        assertEquals(0, run.exit)
        assertEquals(3, run.msiexecs.size)
        assertEquals(listOf(20_000L, 20_000L), run.pauses)
        assertEquals(1, run.relaunches.size)
    }

    @Test
    fun anotherInstallThatNeverEndsGivesUpAfterFiveRetriesAndStillOpensTheApp() {
        val run = finish(List(6) { 1618 })

        assertEquals(1, run.exit)
        assertEquals(6, run.msiexecs.size)
        assertEquals(1, run.relaunches.size, "the old version opens, and its start reports the failed install")
    }

    @Test
    fun aFailedInstallIsNotRetriedAndStillOpensTheApp() {
        val run = finish(listOf(1603))

        assertEquals(1, run.exit)
        assertEquals(1, run.msiexecs.size)
        assertEquals(1, run.relaunches.size)
        assertContains(Files.readString(run.result), "msiexec exited with 1603")
    }

    @Test
    fun aHideoutOpenedDuringTheInstallIsReopenedAfterItWithItsLink() {
        val run = finish(listOf(0), relaunch = false, reopen = "hideout://join/ABCD")
        assertEquals(1, run.msiexecs.size)
        assertEquals(ProcessSpec(listOf(run.request.executable.toString(), "hideout://join/ABCD"), run.request.executable.parent), run.relaunches.single())
        assertEquals(listOf(run.request.executable.toString()), finish(listOf(0), relaunch = false, reopen = "").relaunches.single().command)
        assertEquals(1, finish(listOf(0), relaunch = true, reopen = "").relaunches.size, "a restart and a reopen open one copy")
    }

    @Test
    fun anAppThatOutlivesTheWaitGetsNoInstallAndNoSecondCopy() {
        val run = finish(emptyList(), appGone = false)

        assertEquals(3, run.exit)
        assertEquals(emptyList(), run.commands)
        assertContains(Files.readString(run.result), "still running")
    }

    @Test
    fun aProcessAlreadyGoneIsNotWaitedFor() {
        val gone = ProcessBuilder("true").start().also { it.waitFor() }.pid()
        val log = Files.createDirectories(root.resolve("real")).resolve("finish.log")
        var ran = false
        val exit = FinishAppUpdate(start = { ran = true; Finished(0) }).run(FinishRequest(gone, root.resolve("x.msi"), log, root.resolve("Hideout.exe"), relaunch = false))

        assertEquals(0, exit)
        assertTrue(ran)
        assertFalse(Files.exists(root.resolve("Hideout.exe")))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    private companion object {
        val Helper = ProcessIdentity(77, 1_800_000_000_000)
    }

    private class Finished(private val code: Int) : Process() {
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream() = ByteArrayInputStream(ByteArray(0))
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() = Unit
        override fun toHandle(): ProcessHandle = ProcessHandle.current()
    }
}
