package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.InstallOutcome
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.library.syncFolderIfSupported
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

internal class AppImageInstaller(private val target: Path, private val pid: Long, private val packageFile: (ReleasePackage) -> Path, private val start: StartProcess) {
    suspend fun replace(pkg: ReleasePackage, relaunch: Boolean): InstallOutcome = withContext(Dispatchers.IO) {
        val stage = target.resolveSibling(".${target.fileName}.update")
        try {
            Files.copy(packageFile(pkg), stage, StandardCopyOption.REPLACE_EXISTING)
            Files.setPosixFilePermissions(stage, Files.getPosixFilePermissions(target) + PosixFilePermission.OWNER_EXECUTE)
            FileChannel.open(stage, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(stage) }
            return@withContext InstallOutcome.Failed("$e", UpdateFailure.Unwritable)
        }
        runCatching { syncFolderIfSupported(target.parent) }
        runCatching { if (relaunch) start(startAfterExit(pid, target, InheritedFromTheOldOne)) }
        InstallOutcome.Done
    }

    private companion object {
        /** What the AppImage runtime sets for its app. The new runtime sets its own, and an AppDir left here would point at the old mount. */
        val InheritedFromTheOldOne = setOf("APPDIR", "APPIMAGE", "ARGV0", "OWD")
    }
}
