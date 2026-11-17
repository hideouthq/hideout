package gg.hideouthq.nativebridge

import org.jetbrains.amper.plugins.Configurable
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import kotlin.io.path.*

@Configurable interface NativeBridgeSettings {
    val sourceDir: Path
    val prebuiltDir: Path
}

@TaskAction
fun bundleSteamBridge(
    @Input sourceDir: Path,
    @Input prebuiltDir: Path,
    @Output buildDir: Path,
    @Output outputDir: Path,
) {
    outputDir.toFile().deleteRecursively()
    if (!System.getProperty("os.name").lowercase().contains("linux")) return
    val artifactsDir = if (prebuiltDir.exists()) {
        prebuiltDir
    } else {
        val artifacts = buildDir.resolve("artifacts")
        println("Building both Steam bridge architectures in the pinned Steam Runtime SDK")
        val process = ProcessBuilder(
            "bash", sourceDir.resolve("tools/build-runtime.sh").toAbsolutePath().toString(),
            buildDir.resolve("sdk").toAbsolutePath().toString(), artifacts.toAbsolutePath().toString(),
        ).inheritIO().start()
        check(process.waitFor() == 0) { "Steam Runtime SDK build failed; see the build output above" }
        artifacts
    }
    val names = setOf("libhideout_steam_bridge.so", "bridge_probe", "launch.sh", "final-launch.sh", "client-container.sh", "THIRD_PARTY_NOTICES.txt", "BUILD.json")
    for (arch in listOf("i386", "x86_64")) {
        val source = artifactsDir.resolve("linux-$arch")
        check(source.resolve("SHA256SUMS").isRegularFile()) {
            "Incomplete Steam Runtime artifacts in $artifactsDir: missing linux-$arch/SHA256SUMS"
        }
        val lines = source.resolve("SHA256SUMS").readLines().filter { it.isNotBlank() }.map { it.split("  ") }
        require(lines.size == names.size && lines.all { it.size == 2 && it[0].matches(Regex("[0-9a-f]{64}")) } && lines.map { it[1] }.toSet() == names) { "Invalid Steam bridge manifest" }
        val target = outputDir.resolve("steam-bridge/linux-$arch").createDirectories()
        for ((expected, name) in lines) {
            val bytes = source.resolve(name).readBytes()
            check(MessageDigest.getInstance("SHA-256").digest(bytes).toHexString() == expected) { "Damaged bridge artifact $name" }
            target.resolve(name).writeBytes(bytes)
        }
        Files.copy(source.resolve("SHA256SUMS"), target.resolve("SHA256SUMS"), REPLACE_EXISTING)
    }
}
