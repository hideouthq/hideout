package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.InstallOutcome
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.appupdate.UpdateFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path

/**
 * Installs through one pkexec prompt. The downloaded file is the user's to change, so root copies it somewhere only root
 * can write, checks the copy against the feed's sha256, and installs that copy.
 */
internal class SystemPackageInstaller(
    private val tool: String,
    private val executable: Path,
    private val pid: Long,
    private val packageFile: (ReleasePackage) -> Path,
    private val start: StartProcess,
) {
    suspend fun install(pkg: ReleasePackage): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            val command = listOf("pkexec", "/bin/sh", "-c", InstallCopy, "sh", tool, packageFile(pkg).toString(), pkg.sha256)
            val process = start(ProcessSpec(command, executable.parent))
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

/** `apt-get` and `dnf` read a name as a file by how it ends, so the copy keeps the file's name. */
private val InstallCopy = """
    set -eu
    d=''
    trap 'rm -rf -- "${'$'}d"' EXIT
    d=${'$'}(mktemp -d)
    chmod 700 "${'$'}d"
    c="${'$'}d/${'$'}{2##*/}"
    cp -- "${'$'}2" "${'$'}c"
    [ "${'$'}(sha256sum -- "${'$'}c" | cut -d ' ' -f 1)" = "${'$'}3" ] || exit $PackageChangedCode
    "${'$'}1" install -y "${'$'}c"
""".trimIndent()

private const val PackageChangedCode = 65

/**
 * pkexec exits 126 when the prompt is dismissed and 127 when it can't get authorization. [PackageChangedCode] is the
 * copy failing its check, and anything else is the tool's.
 */
internal fun pkexecOutcome(code: Int): InstallOutcome = when (code) {
    0 -> InstallOutcome.Done
    126 -> InstallOutcome.Cancelled
    127 -> InstallOutcome.Failed("pkexec couldn't get authorization")
    PackageChangedCode -> InstallOutcome.Failed("The package changed before root could install it", UpdateFailure.Mismatch)
    else -> InstallOutcome.Failed("The package tool exited with $code")
}
