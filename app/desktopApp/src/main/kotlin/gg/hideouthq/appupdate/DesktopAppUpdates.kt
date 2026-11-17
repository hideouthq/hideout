package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.AppUpdateMethod
import gg.hideouthq.core.appupdate.PackageFormat
import gg.hideouthq.core.appupdate.ReleaseFeedSource
import gg.hideouthq.core.appupdate.SignedReleaseFeed
import gg.hideouthq.core.appupdate.UpdatePackages
import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.FileCacheStorage
import io.ktor.client.HttpClient
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

internal class DesktopAppUpdates(val method: AppUpdateMethod?, val feed: ReleaseFeedSource?, val packages: UpdatePackages)

/**
 * The executable's path is read here, at start, because once a system package has replaced the file the system reports
 * the old one as deleted.
 */
internal fun desktopAppUpdates(client: HttpClient, downloadClient: () -> HttpClient): DesktopAppUpdates {
    val feedUrl = ReleaseFeedUrl.fromProcess() ?: return DesktopAppUpdates(null, null, UpdatePackages.None)
    val feed = SignedReleaseFeed(client, feedUrl, FileCacheStorage(AppDirs.cache()), Clock.System, signatureCheck(ReleaseKey))
    val executable = ProcessHandle.current().info().command().map { Path.of(it) }.orElse(null)
    val installed = executable?.takeIf { isNativeImage() }?.let { detectInstall(readMarker(it), System::getenv, it.parent, ::realPathOrNull, Files::isWritable, ::onPath) }
        ?: return DesktopAppUpdates(null, feed, UpdatePackages.None)
    val folder = AppDirs.cache().toPath().resolve("app-update")
    val pid = ProcessHandle.current().pid()
    val cache = PackageCache(folder, downloadClient())
    val method = when (installed) {
        is InstalledAs.AppImage -> AppImageInstaller(installed.file, pid, cache::file, startForReal).let { AppUpdateMethod.AtExit(PackageFormat.AppImage, it::replace) }
        is InstalledAs.SystemPackage -> SystemPackageInstaller(installed.tool, executable, pid, cache::file, startForReal).let { AppUpdateMethod.WhileOpen(installed.format, it::install, it::relaunch) }
        InstalledAs.Msi -> MsiInstaller(executable, folder, pid, cache::file, startForReal).let { AppUpdateMethod.AtExit(PackageFormat.Msi, it::replace) }
    }
    return DesktopAppUpdates(method, feed, cache)
}

internal fun runningAppImage(): Path? {
    val executable = ProcessHandle.current().info().command().map { Path.of(it) }.orElse(null)?.takeIf { isNativeImage() } ?: return null
    return if (readMarker(executable)?.trim() == "appimage") appImageFile(System::getenv, executable.parent, ::realPathOrNull) else null
}

/** The package this native copy came in, as its build marked it beside the executable: `appimage`, `deb`, `rpm` or `msi`. */
internal fun packageFormat(): String? =
    ProcessHandle.current().info().command().map { Path.of(it) }.orElse(null)?.takeIf { isNativeImage() }?.let(::readMarker)?.trim()

private fun readMarker(executable: Path): String? = try {
    executable.resolveSibling("package-format").toFile().takeIf { it.isFile }?.readText()
} catch (e: IOException) {
    null
}

private fun realPathOrNull(path: Path): Path? = try {
    path.toRealPath()
} catch (e: IOException) {
    null
}

private fun onPath(program: String): Boolean =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { it.isNotEmpty() && Files.isExecutable(Path.of(it, program)) }
