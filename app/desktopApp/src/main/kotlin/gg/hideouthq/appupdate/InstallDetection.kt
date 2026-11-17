package gg.hideouthq.appupdate

import gg.hideouthq.core.appupdate.PackageFormat
import java.nio.file.Path

internal sealed interface InstalledAs {
    data class AppImage(val file: Path) : InstalledAs

    data class SystemPackage(val format: PackageFormat, val tool: String) : InstalledAs

    data object Msi : InstalledAs
}

internal fun detectInstall(
    marker: String?,
    environment: (String) -> String?,
    executableFolder: Path,
    realPath: (Path) -> Path?,
    writable: (Path) -> Boolean,
    onPath: (String) -> Boolean,
): InstalledAs? =
    when (marker?.trim()) {
        "msi" -> InstalledAs.Msi
        "appimage" -> appImageFile(environment, executableFolder, realPath)
            ?.takeIf { it.parent != null && writable(it.parent) }
            ?.let(InstalledAs::AppImage)
        "deb" -> systemPackage(PackageFormat.Deb, "apt-get", onPath)
        "rpm" -> systemPackage(PackageFormat.Rpm, "dnf", onPath)
        else -> null
    }

/** Null without a `$APPIMAGE` that is this copy's own: Nix's extracted copy has none, and another AppImage's terminal can pass its own on. */
internal fun appImageFile(environment: (String) -> String?, executableFolder: Path, realPath: (Path) -> Path?): Path? =
    environment("APPIMAGE")?.let { Path.of(it) }
        ?.takeIf { it.isAbsolute }
        ?.takeIf { environment("APPDIR")?.let { dir -> realPath(Path.of(dir)) }?.let { dir -> dir == realPath(executableFolder) } == true }

private fun systemPackage(format: PackageFormat, tool: String, onPath: (String) -> Boolean): InstalledAs? =
    InstalledAs.SystemPackage(format, tool).takeIf { onPath("pkexec") && onPath(tool) }
