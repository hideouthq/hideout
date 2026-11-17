package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.InstallOutcome
import gg.hideouthq.core.appupdate.ReleasePackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

internal class SystemPackageInstaller(
    private val tool: String,
    private val executable: Path,
    private val pid: Long,
    private val packageFile: (ReleasePackage) -> Path,
    private val start: StartProcess,
) {
    suspend fun install(pkg: ReleasePackage): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            val process = start(ProcessSpec(listOf("pkexec", tool, "install", "-y", packageFile(pkg).toString()), executable.parent))
            pkexecOutcome(runInterruptible { process.waitFor() })
        } catch (e: IOException) {
            InstallOutcome.Failed("$e")
        }
    }

    suspend fun relaunch(): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            start(startAfterExit(pid, executable))
            InstallOutcome.Done
        } catch (e: IOException) {
            InstallOutcome.Failed("$e")
        }
    }
}

/** pkexec exits 126 when the prompt is dismissed and 127 when it can't get authorization; anything else is the tool's. */
internal fun pkexecOutcome(code: Int): InstallOutcome = when (code) {
    0 -> InstallOutcome.Done
    126 -> InstallOutcome.Cancelled
    127 -> InstallOutcome.Failed("pkexec couldn't get authorization")
    else -> InstallOutcome.Failed("The package tool exited with $code")
}
