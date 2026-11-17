package gg.hideouthq.nativepackage

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

internal const val PreviewUrlVariable = "HIDEOUT_BUILD_PREVIEW_URL"
internal const val PreviewKeyVariable = "HIDEOUT_BUILD_PREVIEW_KEY"

@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun writePreviewHost(id: String, @Input localFile: Path, @Output outputDir: Path) = writePreviewHostFile(id, localFile, outputDir, System::getenv)

internal fun writePreviewHostFile(id: String, localFile: Path, outputDir: Path, environment: (String) -> String?) {
    val local = Properties().apply { if (localFile.isRegularFile()) localFile.inputStream().use(::load) }
    fun value(variable: String) = listOf(environment(variable), local.getProperty(variable)).firstNotNullOfOrNull { it?.trim()?.ifEmpty { null } }
    val file = outputDir / (id.replace('.', '/') + "/preview.bin")
    val url = value(PreviewUrlVariable)
    val key = value(PreviewKeyVariable)
    check((url == null) == (key == null)) {
        "Set both $PreviewUrlVariable and $PreviewKeyVariable, in the environment or in $localFile, or neither: a build with one has no working previews"
    }
    check(key == null || key.length >= ShortestKey) { "$PreviewKeyVariable is shorter than $ShortestKey characters, which the preview Worker refuses" }
    val stored = if (url != null && key != null) maskPreviewHost(url, key) else null
    when {
        stored == null -> file.deleteIfExists()
        !file.exists() || !file.readBytes().contentEquals(stored) -> file.apply { parent.createDirectories() }.writeBytes(stored)
    }
}

private const val ShortestKey = 32

internal fun maskPreviewHost(url: String, key: String): ByteArray {
    val plain = "$url\n$key".encodeToByteArray()
    val seed = MessageDigest.getInstance("SHA-256").digest(plain)
    return seed + ByteArray(plain.size) { i -> (plain[i].toInt() xor maskBlock(seed, i / 32)[i % 32].toInt()).toByte() }
}

private fun maskBlock(seed: ByteArray, index: Int): ByteArray =
    MessageDigest.getInstance("SHA-256").apply { update(seed); update(ByteBuffer.allocate(4).putInt(index).array()) }.digest()
