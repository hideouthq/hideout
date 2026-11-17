package gg.hideouthq.nativepackage

import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.outputStream
import kotlin.io.path.pathString
import kotlin.io.path.readBytes
import kotlin.io.path.writeText

internal class Pinned(val url: String, val sha256: String) {
    val fileName: String get() = url.substringAfterLast('/')
}

internal val AppImageTool = Pinned(
    "https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage",
    "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0",
)

internal val AppImageRuntime = Pinned(
    "https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64",
    "2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d",
)

@TaskAction
fun downloadAppImageTools(@Output outputDir: Path) {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
    listOf(AppImageTool, AppImageRuntime).forEach { download(it, outputDir) }
}

internal fun download(pinned: Pinned, directory: Path): Path {
    val target = directory.createDirectories() / pinned.fileName
    if (target.exists() && sha256(target) == pinned.sha256) return target
    URI(pinned.url).toURL().openStream().use { download -> target.outputStream().use { download.copyTo(it) } }
    val actual = sha256(target)
    if (actual != pinned.sha256) {
        target.deleteIfExists()
        error("${pinned.url} has SHA-256 $actual instead of ${pinned.sha256}")
    }
    target.toFile().setExecutable(true)
    return target
}

private fun sha256(file: Path): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))

/**
 * Packs [image] into `<packages>/<name>-<version>-x86_64.AppImage`, working in [work]. appimagetool is itself an
 * AppImage and runs extracted, so the build host needs no FUSE.
 */
@OptIn(ExperimentalPathApi::class)
internal fun appImage(settings: NativePackageSettings, image: Path, mainClass: String, tools: Path, work: Path, packages: Path) {
    val appDir = work.createDirectories() / "AppDir"
    copyTree(image, appDir)
    makeAppDir(appDir, settings, mainClass)
    val appImage = packages / "${settings.name}-${settings.version}-x86_64.AppImage"
    val temp = (work / "temp").createDirectories()
    runTool(
        listOf((tools / AppImageTool.fileName).pathString, "--runtime-file", (tools / AppImageRuntime.fileName).pathString, appDir.pathString, appImage.pathString),
        mapOf("APPIMAGE_EXTRACT_AND_RUN" to "1", "ARCH" to "x86_64", "VERSION" to settings.version, "TMPDIR" to temp.pathString),
    )
    work.deleteRecursively()
}

/**
 * Turns a copy of the native image into an AppDir. The runtime starts `AppRun`, a link to the executable. The desktop
 * entry and icon at the top are what desktops and AppImage managers read.
 */
internal fun makeAppDir(folder: Path, settings: NativePackageSettings, mainClass: String) {
    (folder / "AppRun").createSymbolicLinkPointingTo(Path.of(settings.name))
    (folder / "${settings.id}.desktop").writeText(desktopEntry(settings, mainClass, exec = settings.name, icon = settings.id))
    settings.linuxIcon.copyTo(folder / "${settings.id}.png")
    (folder / ".DirIcon").createSymbolicLinkPointingTo(Path.of("${settings.id}.png"))
    writePackageFormat(folder, "appimage")
}

internal fun copyTree(source: Path, target: Path) = runTool(listOf("cp", "-a", source.pathString, target.pathString))
