package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.InstallOutcome
import gg.hideouthq.core.appupdate.ReleasePackage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Windows locks a running program's files, so a copy of [executable] runs the MSI once Hideout is gone. The MSI deletes
 * the installed folder, so neither the copy nor its working folder can be in it.
 */
internal class MsiInstaller(
    private val executable: Path,
    private val folder: Path,
    private val pid: Long,
    private val packageFile: (ReleasePackage) -> Path,
    private val start: StartProcess,
) {
    suspend fun replace(pkg: ReleasePackage, relaunch: Boolean): InstallOutcome = withContext(Dispatchers.IO) {
        try {
            val helper = folder.resolve(FinishHelperName)
            Files.copy(executable, helper, StandardCopyOption.REPLACE_EXISTING)
            val request = FinishRequest(pid, packageFile(pkg), folder.resolve("finish-update.log"), executable, relaunch)
            start(ProcessSpec(listOf(helper.toString()) + request.toArguments(), folder))
            InstallOutcome.Done
        } catch (e: IOException) {
            InstallOutcome.Failed("$e")
        }
    }
}
