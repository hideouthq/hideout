package gg.hideouthq.nativepackage

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.writeText

internal fun writePackageFormat(folder: Path, format: String): Path = (folder / "package-format").also { it.writeText(format) }

@OptIn(ExperimentalPathApi::class)
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun packageNative(
    @Input settings: NativePackageSettings,
    @Input imageOutput: Path,
    mainClass: String?,
    @Input tools: Path,
    @Output outputDir: Path,
) {
    outputDir.deleteRecursively()
    val image = imageOutput / ImageFolder
    val main = checkNotNull(mainClass) { "Set settings.jvm.mainClass" }
    val packages = (outputDir / "package").createDirectories()
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        msi(settings, image, outputDir / "msi", packages)
    } else {
        appImage(settings, image, main, tools, outputDir / "appimage", packages)
        deb(settings, image, main, outputDir / "deb", packages)
        rpm(settings, image, main, outputDir / "rpm", packages)
    }
    println("Packaged into $packages")
}
