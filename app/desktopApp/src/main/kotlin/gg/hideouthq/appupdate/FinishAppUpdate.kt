package gg.hideouthq.appupdate

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal const val FinishAppUpdateFlag = "--finish-app-update"

internal const val FinishHelperName = "finish-update.exe"

/**
 * One base64 word on the command line, because a native image on Windows reads its arguments in the system's code page
 * and decodes them as UTF-8, which turns a user folder like `C:\Users\Jürgen` into a path that isn't one.
 */
internal data class FinishRequest(val pid: Long, val msi: Path, val log: Path, val executable: Path, val relaunch: Boolean) {
    fun toArguments(): List<String> {
        val fields = listOf(pid.toString(), msi.toString(), log.toString(), executable.toString(), relaunch.toString())
        return listOf(FinishAppUpdateFlag, Base64.getUrlEncoder().encodeToString(fields.joinToString("\n").encodeToByteArray()))
    }

    companion object {
        fun parse(arguments: List<String>): FinishRequest? {
            if (arguments.size != 2 || arguments[0] != FinishAppUpdateFlag) return null
            return try {
                val fields = Base64.getUrlDecoder().decode(arguments[1]).decodeToString().split("\n")
                if (fields.size != 5) return null
                FinishRequest(
                    pid = fields[0].toLongOrNull() ?: return null,
                    msi = Path.of(fields[1]),
                    log = Path.of(fields[2]),
                    executable = Path.of(fields[3]),
                    relaunch = fields[4].toBooleanStrictOrNull() ?: return null,
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/** If the app outlives the wait, the MSI doesn't run, because with its files still in use it would finish only after a reboot. */
internal class FinishAppUpdate(
    private val start: StartProcess = startForReal,
    private val waitForExit: (pid: Long) -> Boolean = ::waitForProcessExit,
    private val pause: (millis: Long) -> Unit = Thread::sleep,
) {
    fun run(request: FinishRequest): Int {
        val note = { line: String -> note(request.log, line) }
        if (!waitForExit(request.pid)) {
            note("Hideout ${request.pid} was still running after the wait; the MSI did not run")
            return StillRunning
        }
        val installed = runMsi(request, note)
        if (request.relaunch) {
            try {
                start(ProcessSpec(listOf(request.executable.toString()), request.executable.parent))
                note("Started ${request.executable}")
            } catch (e: IOException) {
                note("Couldn't start ${request.executable}: $e")
            }
        }
        return if (installed) 0 else InstallFailed
    }

    private fun runMsi(request: FinishRequest, note: (String) -> Unit): Boolean {
        val command = listOf(
            "msiexec", "/i", request.msi.toString(), "/qn", "/norestart", "MSIRESTARTMANAGERCONTROL=Disable", "/l*v", request.log.toString(),
        )
        repeat(BusyRetries + 1) { attempt ->
            if (attempt > 0) pause(BusyPauseMillis)
            val code = try {
                start(ProcessSpec(command, request.log.parent)).waitFor()
            } catch (e: IOException) {
                note("Couldn't start msiexec: $e")
                return false
            }
            note("msiexec exited with $code")
            when (code) {
                // 3010 and 1641 are a success that wants a reboot, which `/norestart` leaves to the user.
                0, 3010, 1641 -> return true
                AnotherInstallRunning -> Unit
                else -> return false
            }
        }
        return false
    }

    private fun note(log: Path, line: String) {
        try {
            Files.writeString(log.resolveSibling("${log.fileName}.result"), "$line\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: IOException) {
        }
    }

    private companion object {
        const val AnotherInstallRunning = 1618
        const val BusyRetries = 5
        const val BusyPauseMillis = 20_000L
        const val StillRunning = 3
        const val InstallFailed = 1
    }
}

private fun waitForProcessExit(pid: Long): Boolean {
    val process = ProcessHandle.of(pid).orElse(null) ?: return true
    return try {
        process.onExit().get(120, TimeUnit.SECONDS)
        true
    } catch (e: TimeoutException) {
        false
    }
}

internal fun finishAppUpdate(arguments: List<String>): Int {
    val request = FinishRequest.parse(arguments) ?: return BadArguments
    return FinishAppUpdate().run(request)
}

private const val BadArguments = 2
