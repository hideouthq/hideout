package gg.hideouthq.nativepackage

import org.jetbrains.amper.plugins.Classpath
import org.jetbrains.amper.plugins.CompilationArtifact
import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.walk
import kotlin.io.path.readText
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

@OptIn(ExperimentalPathApi::class)
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun buildNativeImage(
    @Input settings: NativePackageSettings,
    @Input app: CompilationArtifact,
    @Input runtimeClasspath: Classpath,
    mainClass: String?,
    @Output outputDir: Path,
) {
    requireNotices(app.artifact, runtimeClasspath.resolvedFiles, settings.noticeIndex)
    val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    val graal = Path.of(checkNotNull(System.getenv("JAVA_HOME")) { "Set JAVA_HOME to GraalVM: native packages are compiled with its native-image" })
    outputDir.deleteRecursively()
    val image = (outputDir / ImageFolder).createDirectories()
    buildLinkHelper(settings.linkHelper, image, windows)
    requireLinkNotices(goModules(toolOutput(listOf("go", "version", "-m", (image / linkHelperName(windows)).pathString))), settings.noticeIndex)
    val classpath = (listOf(app.artifact) + runtimeClasspath.resolvedFiles).map { it.toAbsolutePath().normalize() }.distinct()
    val profile = settings.pgoProfile.takeIf { it.exists() }?.let { listOf("--pgo=${it.toAbsolutePath().normalize().pathString}") }.orEmpty()
    val systemOptions = otherSystemMetadataExcluded(app.artifact, windows) +
        if (windows) settings.windowsImageOptions + "-H:NativeLinkerOption=${windowsResources(settings, outputDir).pathString}" else emptyList()
    val arguments = outputDir / "native-image.args"
    arguments.writeLines(
        listOf("-cp", classpath.joinToString(File.pathSeparator) { it.pathString }, "-o", (image / settings.name).pathString) +
            settings.imageOptions + systemOptions + profile + checkNotNull(mainClass) { "Set settings.jvm.mainClass" },
    )
    runTool(listOf((graal / "bin" / if (windows) "native-image.cmd" else "native-image").pathString, "@${arguments.pathString}"))
    classpath.filter { it.name.startsWith(SkikoRuntime) }.forEach { unpackTopFiles(it, image) }
    (if (windows) WindowsJavaHomeFiles else LinuxJavaHomeFiles).forEach { file ->
        (graal / file).copyTo((image / file).also { it.parent.createDirectories() })
    }
    // rpmbuild reads library dependencies only from executable files, and shared libraries are installed executable anyway.
    if (!windows) image.walk().filter { it.name.endsWith(".so") }.forEach { it.toFile().setExecutable(true, false) }
    println("Built $image")
}

internal const val ImageFolder = "image"

internal fun buildLinkHelper(source: Path, image: Path, windows: Boolean, go: String = "go") {
    val linkerFlags = if (windows) "-s -w -H windowsgui" else "-s -w"
    val output = image.toAbsolutePath() / linkHelperName(windows)
    try {
        runTool(listOf(go, "-C", source.pathString, "build", "-trimpath", "-ldflags", linkerFlags, "-o", output.pathString, "."), mapOf("CGO_ENABLED" to "0"))
    } catch (e: IOException) {
        error("Put Go on PATH: hideout-link is built from ${source.pathString} with the version its go.mod names (${e.message})")
    }
}

private fun linkHelperName(windows: Boolean) = if (windows) "hideout-link.exe" else "hideout-link"

/**
 * Leaves out the app's reachability metadata in folders named after the other system, `linux` or `windows`, so each
 * image embeds only its own system's native libraries, although the JARs that carry them hold every system's.
 */
internal fun otherSystemMetadataExcluded(app: Path, windows: Boolean): List<String> =
    listOf("--exclude-config", ".*${Regex.escape(app.name)}", "META-INF/native-image/(.*/)?${if (windows) "linux" else "windows"}/.*")

internal fun windowsResources(settings: NativePackageSettings, outputDir: Path): Path {
    val manifest = outputDir / "app.manifest"
    manifest.writeText(WindowsManifest)
    val source = outputDir / "app.rc"
    source.writeText(windowsResourceScript(settings, manifest))
    val compiled = outputDir / "app.res"
    runTool(listOf("rc.exe", "/nologo", "/fo", compiled.pathString, source.pathString))
    return compiled
}

internal fun windowsResourceScript(settings: NativePackageSettings, manifest: Path): String {
    val numbers = (settings.version.split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0, 0)).take(4).joinToString(",")
    return """
    1 ICON "${rcPath(settings.windowsIcon)}"
    1 24 "${rcPath(manifest)}"
    1 VERSIONINFO
    FILEVERSION $numbers
    PRODUCTVERSION $numbers
    BEGIN
      BLOCK "StringFileInfo"
      BEGIN
        BLOCK "040904B0"
        BEGIN
          VALUE "CompanyName", "${settings.vendor}"
          VALUE "FileDescription", "${settings.name}"
          VALUE "FileVersion", "${settings.version}"
          VALUE "InternalName", "${settings.name}"
          VALUE "OriginalFilename", "${settings.name}.exe"
          VALUE "ProductName", "${settings.name}"
          VALUE "ProductVersion", "${settings.version}"
        END
      END
      BLOCK "VarFileInfo"
      BEGIN
        VALUE "Translation", 0x409, 1200
      END
    END
    """.trimIndent() + "\n"
}

private fun rcPath(file: Path) = file.toAbsolutePath().pathString.replace("\\", "\\\\")

/**
 * The manifest java.exe carries. Without it Windows treats the executable as unaware of display scaling and stretches
 * its window as a bitmap, which blurs it at any scale above 100%. AWT declares awareness itself only under the java
 * launcher. The manifest also reports Windows 10 and later as such, instead of as Windows 8.
 */
internal val WindowsManifest = """
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <dependency>
    <dependentAssembly>
      <assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" processorArchitecture="*" publicKeyToken="6595b64144ccf1df" language="*"/>
    </dependentAssembly>
  </dependency>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3">
    <security>
      <requestedPrivileges>
        <requestedExecutionLevel level="asInvoker" uiAccess="false"/>
      </requestedPrivileges>
    </security>
  </trustInfo>
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/PM</dpiAware>
      <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2</dpiAwareness>
    </windowsSettings>
  </application>
  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1">
    <application>
      <supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/>
    </application>
  </compatibility>
</assembly>
""".trimStart()

/**
 * Files the JDK reads from `<java.home>` at run time, which the app points at the image: JAWT, which Skiko opens, and on
 * Windows the font configuration AWT's font manager starts from.
 */
private val LinuxJavaHomeFiles = listOf("lib/libjawt.so")
private val WindowsJavaHomeFiles = listOf("bin/jawt.dll", "lib/fontconfig.bfc")

private const val SkikoRuntime = "skiko-awt-runtime-"

/**
 * Copies the files at the top of [jar] into [folder]. Skiko's runtime JAR holds its native library there, and on Windows
 * the ICU data the library reads beside it; the app points `skiko.library.path` at the executable's folder.
 */
private fun unpackTopFiles(jar: Path, folder: Path) = JarFile(jar.toFile()).use { source ->
    source.entries().asSequence().filter { !it.isDirectory && '/' !in it.name }.forEach { entry ->
        source.getInputStream(entry).use { Files.copy(it, folder / entry.name) }
    }
}

internal fun requireNotices(app: Path, runtimeClasspath: List<Path>, noticeIndex: Path) {
    val unmapped = unmappedJars(runtimeClasspath, app.toAbsolutePath().normalize().parent.parent, readNoticeIndex(noticeIndex.readText()))
    check(unmapped.isEmpty()) {
        "$noticeIndex names no notice for ${unmapped.size} runtime JARs, so the package would ship them unlicensed:\n" + unmapped.joinToString("\n")
    }
}

internal fun requireLinkNotices(modules: List<Coordinate>, noticeIndex: Path) {
    val named = readNoticeIndex(noticeIndex.readText())
    val unnamed = modules.filterNot { it in named }
    check(unnamed.isEmpty()) {
        "$noticeIndex names no notice for ${unnamed.size} Go modules in hideout-link, so the package would ship them unlicensed. Run tools/link/notices.py:\n" + unnamed.joinToString("\n")
    }
}

private fun toolOutput(command: List<String>): String {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "${command.joinToString(" ")} failed" }
    return output
}

internal fun runTool(command: List<String>, env: Map<String, String> = emptyMap()) {
    val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(env) }.start()
    process.inputStream.bufferedReader().useLines { lines -> lines.forEach(::println) }
    val exitCode = process.waitFor()
    check(exitCode == 0) { "${Path.of(command.first()).name} exited with code $exitCode" }
}
