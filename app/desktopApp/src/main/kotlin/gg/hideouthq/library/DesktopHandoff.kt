package gg.hideouthq.library

import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinUser
import java.awt.Desktop
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DesktopHandoff internal constructor(
    private val osName: String = System.getProperty("os.name"),
    private val native: (File) -> Boolean = ::nativeOpen,
    private val shellExecute: (link: String) -> Int = ::shellOpen,
    private val start: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    },
    private val nativeWaitMillis: Long = NativeWaitMillis,
) {
    constructor() : this(System.getProperty("os.name"))

    private sealed interface Target {
        data class Link(val uri: URI) : Target
        data class Local(val file: File) : Target
    }

    fun browse(uri: URI) = open(Target.Link(uri))

    fun open(file: File) = open(Target.Local(file))

    private fun open(target: Target) {
        val argument = when (target) {
            is Target.Link -> target.uri.toString()
            is Target.Local -> target.file.absolutePath
        }
        if (osName.startsWith("Linux", ignoreCase = true)) return subprocess(listOf("xdg-open", argument))
        val accepted = try {
            nativeHandoff(target, argument)
        } catch (failure: IOException) {
            if (target !is Target.Local) throw failure
            false
        }
        when {
            accepted -> Unit
            // Explorer exits with 1 even when it opened the folder; only failing to start it says anything.
            target is Target.Local -> start(listOf("explorer.exe", argument)).outputStream.close()
            else -> throw IOException("No desktop association can open $argument.")
        }
    }

    internal fun subprocess(command: List<String>) {
        val process = start(command)
        process.outputStream.close()
        // Some openers stay alive with their window.
        if (process.waitFor(ImmediateWaitMillis, TimeUnit.MILLISECONDS) && process.exitValue() != 0) {
            throw IOException("${command.first()} could not open ${command.last()} (exit ${process.exitValue()}).")
        }
    }

    private fun nativeHandoff(target: Target, argument: String): Boolean {
        // Native calls need not honor interruption.
        val pending = FutureTask {
            when (target) {
                is Target.Link -> shellExecute(argument) > LastShellExecuteError
                is Target.Local -> native(target.file)
            }
        }
        Thread(pending, "hideout-desktop-handoff").apply { isDaemon = true }.start()
        return try {
            pending.get(nativeWaitMillis, TimeUnit.MILLISECONDS)
        } catch (failure: TimeoutException) {
            // Windows can hold a steam:// link until Steam is up; the game watcher, not this call, tells whether it started.
            if (target is Target.Link) true else throw IOException("The desktop did not respond to the open request.", failure)
        } catch (failure: ExecutionException) {
            throw IOException("The desktop could not open the requested item.", failure.cause)
        } finally {
            pending.cancel(true)
        }
    }

    internal companion object {
        const val ImmediateWaitMillis = 750L
        const val NativeWaitMillis = 2_000L

        /** ShellExecute returns a value above this when it handed the link on; 32 and below are its error codes. */
        const val LastShellExecuteError = 32

        /**
         * `Desktop.browse` gives every link to the default browser, so a `steam://` link opened a blank browser tab.
         * ShellExecute may start the handler through COM, so this thread joins an apartment first, as AWT's own shell
         * thread does.
         */
        private fun shellOpen(link: String): Int {
            Ole32.INSTANCE.CoInitializeEx(null, Ole32.COINIT_APARTMENTTHREADED or Ole32.COINIT_DISABLE_OLE1DDE)
            try {
                return Shell32.INSTANCE.ShellExecute(null, "open", link, null, null, WinUser.SW_SHOWNORMAL).toInt()
            } finally {
                Ole32.INSTANCE.CoUninitialize()
            }
        }

        private fun nativeOpen(file: File): Boolean {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) return false
            Desktop.getDesktop().open(file)
            return true
        }
    }
}
