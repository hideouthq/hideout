package gg.hideouthq.library

import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.ConfigTarget
import gg.hideouthq.core.library.GameRoot
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopHandoffTest {
    @Test fun linuxUsesSeparateArgumentsForUnicodeFoldersAndTheUnchangedSteamUri() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val handoff = DesktopHandoff("Linux", native = { error("Linux must use xdg-open") }, start = {
            commands += it
            FinishedProcess(0)
        })
        val folder = File("Downloads 日本語/skin & sounds; literal")
        handoff.open(folder)
        SteamLauncher({ emptyList() }, handoff).launch(PickedGame)
        assertEquals(listOf(
            listOf("xdg-open", folder.absolutePath),
            listOf("xdg-open", SteamLauncher.RunGame.toString()),
        ), commands)
    }

    @Test fun missingOpenerAndFailedUriAssociationReachTheCaller() = runBlocking<Unit> {
        val missing = DesktopHandoff("Linux", start = { throw IOException("xdg-open is missing") })
        assertFailsWith<IOException> { SteamLauncher({ emptyList() }, missing).launch(PickedGame) }
        val refused = DesktopHandoff("Linux", start = { FinishedProcess(4) })
        val error = assertFailsWith<IOException> { SteamLauncher({ emptyList() }, refused).launch(PickedGame) }
        assertTrue(error.message.orEmpty().contains("exit 4"))
        assertTrue(error.message.orEmpty().contains(SteamLauncher.RunGame.toString()))
    }

    @Test fun windowsOpensFoldersNativelyAndHandsLinksUnchangedToTheirOwnHandlerWithoutAShell() {
        val opened = mutableListOf<File>()
        val links = mutableListOf<String>()
        val handoff = DesktopHandoff("Windows 11", native = { opened += it; true }, shellExecute = { links += it; 42 }, start = { error("No subprocess expected") })
        val folder = File("C:\\Games 日本語\\Deadlock & mods")
        handoff.open(folder)
        handoff.browse(SteamLauncher.RunGame)
        handoff.browse(URI("https://gamebanana.com/mods/722688?a=1&b=2#files"))
        assertEquals(listOf(folder), opened, "only the folder goes to Desktop, which would give a link to the browser")
        assertEquals(listOf("steam://rungameid/1422450", "https://gamebanana.com/mods/722688?a=1&b=2#files"), links)
    }

    @Test fun aLinkShellExecuteRefusesReachesTheCallerWithoutAFallback() = runBlocking<Unit> {
        for (code in listOf(2, 31, 32)) {
            val handoff = DesktopHandoff("Windows 11", native = { error("Links never use AWT on Windows") }, shellExecute = { code }, start = { error("Never use cmd/start or explorer for a Windows URI") })
            assertFailsWith<IOException>("ShellExecute returned $code") { SteamLauncher({ emptyList() }, handoff).launch(PickedGame) }
        }
        DesktopHandoff("Windows 11", shellExecute = { 33 }, start = { error("No fallback expected") }).browse(SteamLauncher.RunGame)
    }

    @Test fun unsupportedOrFailedWindowsFolderActionFallsBackToExplorerOnly() {
        val folder = File("C:\\Games 日本語\\Deadlock & mods")
        for (native in listOf<(File) -> Boolean>({ false }, { throw IOException("association failed") })) {
            val commands = mutableListOf<List<String>>()
            DesktopHandoff("Windows 11", native = native, start = { commands += it; FinishedProcess(0) }).open(folder)
            assertEquals(listOf(listOf("explorer.exe", folder.absolutePath)), commands)
        }
        DesktopHandoff("Windows 11", native = { false }, start = { FinishedProcess(1) }).open(folder)
        val noExplorer = DesktopHandoff("Windows 11", native = { false }, start = { throw IOException("explorer.exe is missing") })
        assertFailsWith<IOException> { noExplorer.open(folder) }
    }

    @Test fun immediateProcessObservationIsBoundedAndDoesNotTerminateTheDesktopApp() {
        val process = FinishedProcess(0, finished = false)
        val handoff = DesktopHandoff("Linux", start = { process })
        handoff.browse(SteamLauncher.RunGame)
        assertEquals(DesktopHandoff.ImmediateWaitMillis, process.waitMillis)
        assertFalse(process.destroyed)
    }

    @Test fun aSteamLinkTheDesktopIsStillTakingIsHandedOffAfterABoundedWait() {
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val handoff = DesktopHandoff("Windows 11", shellExecute = {
            try {
                while (release.count > 0) {
                    try { release.await() } catch (_: InterruptedException) { }
                }
                42
            } finally { finished.countDown() }
        }, start = { error("URI handoffs have no shell fallback") }, nativeWaitMillis = 200)
        try {
            val elapsed = measureTimeMillis { handoff.browse(SteamLauncher.RunGame) }
            assertTrue(elapsed < 1_000, "Native handoff held the caller for $elapsed ms")
            assertEquals(1L, finished.count, "Caller returned while the native action was still blocked")
            var nextOpened = false
            DesktopHandoff("Windows 11", shellExecute = { nextOpened = true; 42 }, start = { error("No fallback expected") })
                .browse(SteamLauncher.RunGame)
            assertTrue(nextOpened, "A stuck native call must not monopolize later handoffs")
            assertEquals(1L, finished.count, "The second handoff must finish before releasing the first")
        } finally {
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
        }
    }

    @Test fun folderOpenerPropagatesImmediateDesktopFailureWithoutChangingFolders() = runBlocking {
        val project = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("project.yaml")) }
        val root = Files.createTempDirectory(Files.createDirectories(project.resolve("build/profiles/handoff-fixtures")), "case-")
        val addons = Files.createDirectories(root.resolve("game/citadel/addons"))
        val handoff = DesktopHandoff("Linux", start = { FinishedProcess(3) })
        assertFalse(DesktopFolderOpener(handoff).open(GameRoot(root.toString()), CitadelPath.Addons))
        assertTrue(Files.isDirectory(addons))
        Files.list(addons).use { assertEquals(0L, it.count()) }
    }

    @Test fun configFilesHandTheFileToItsEditorAndItsFolderToTheFileManagerAndSayWhenTheDesktopCannot() = runBlocking {
        val commands = mutableListOf<List<String>>()
        val files = DesktopConfigFiles(DesktopHandoff("Linux", start = { commands += it; FinishedProcess(0) }))
        val root = GameRoot("/games/Deadlock")
        assertTrue(files.edit(root, ConfigTarget.Autoexec.path))
        assertTrue(files.show(root, ConfigTarget.Autoexec.path))
        assertEquals(listOf(listOf("xdg-open", "/games/Deadlock/game/citadel/cfg/autoexec.cfg"), listOf("xdg-open", "/games/Deadlock/game/citadel/cfg")), commands)
        val failing = DesktopConfigFiles(DesktopHandoff("Linux", start = { FinishedProcess(4) }))
        assertFalse(failing.edit(root, ConfigTarget.Autoexec.path))
        assertFalse(failing.show(root, ConfigTarget.Autoexec.path))
    }

    internal class FinishedProcess(private val exit: Int, private val finished: Boolean = true) : Process() {
        var waitMillis: Long? = null
        var destroyed = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = error("Unbounded wait must never be used")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitMillis = unit.toMillis(timeout)
            return finished
        }
        override fun exitValue(): Int = if (finished) exit else throw IllegalThreadStateException()
        override fun destroy() { destroyed = true }
    }

    private companion object {
        val PickedGame = GameRoot("/games/Deadlock")
    }
}
