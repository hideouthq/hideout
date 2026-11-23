package gg.hideouthq.nativepackage

import org.jetbrains.amper.plugins.ExecutionAvoidance
import org.jetbrains.amper.plugins.Input
import org.jetbrains.amper.plugins.Output
import org.jetbrains.amper.plugins.TaskAction
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.SecureRandom
import java.util.HexFormat
import java.util.Properties
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

internal const val PreviewUrlVariable = "HIDEOUT_BUILD_PREVIEW_URL"
internal const val MasterVariable = "HIDEOUT_BUILD_MASTER"
internal const val PostHogKeyVariable = "HIDEOUT_BUILD_POSTHOG_KEY"

/**
 * The mask key goes into the generated `BuildMask` as compiled code, not a file beside the masked values. A build keeps
 * its key while the values stay the same, so a build that changes nothing compiles nothing.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun writeBuildSecrets(id: String, version: String, @Input localFile: Path, @Output sourceDir: Path, @Output resourceDir: Path, @Output stateDir: Path) =
    writeBuildSecretsFiles(id, version, localFile, BuildSecretsOutput(sourceDir, resourceDir, stateDir), System::getenv)

internal class BuildSecretsOutput(val sourceDir: Path, val resourceDir: Path, val stateDir: Path) {
    fun secrets(id: String): Path = resourceDir / (id.replace('.', '/') + "/secrets")

    fun source(id: String): Path = sourceDir / (id.substringBeforeLast('.').replace('.', '/') + "/BuildMask.kt")
}

internal fun writeBuildSecretsFiles(
    id: String,
    version: String,
    localFile: Path,
    output: BuildSecretsOutput,
    environment: (String) -> String?,
    newKey: () -> ByteArray = { ByteArray(MaskBytes).also(SecureRandom()::nextBytes) },
) {
    val local = Properties().apply { if (localFile.isRegularFile()) localFile.inputStream().use(::load) }
    val secrets = buildSecrets(version) { variable ->
        listOf(environment(variable), local.getProperty(variable)).firstNotNullOfOrNull { it?.trim()?.ifEmpty { null } }
    }.mapValues { it.value.encodeToByteArray() }
    val directory = output.secrets(id)
    val keyFile = output.stateDir / "mask"
    val kept = keyFile.takeIf { it.isRegularFile() }?.readBytes()
    val key = kept?.takeIf { secrets.all { (name, value) -> (directory / name).holds(mask(kept, name, value)) } } ?: newKey()
    if (directory.exists()) directory.listDirectoryEntries().filter { it.name !in secrets }.forEach { it.deleteIfExists() }
    for ((name, value) in secrets) (directory / name).update(mask(key, name, value))
    keyFile.update(key)
    output.source(id).update(maskSource(id.substringBeforeLast('.'), key).encodeToByteArray())
}

/** Signing keys are derived from one master per release and purpose, so a key taken from one release is only that release's. */
internal fun buildSecrets(version: String, value: (String) -> String?): Map<String, String> {
    val url = value(PreviewUrlVariable)
    val master = value(MasterVariable)
    val postHog = value(PostHogKeyVariable)
    check(url == null || master != null) {
        "Set $MasterVariable with $PreviewUrlVariable, in the environment or in the preview file: a build without it has no working previews"
    }
    check(master == null || master.length >= ShortestKey) { "$MasterVariable is shorter than $ShortestKey characters, which the Workers refuse" }
    check(master == null || version.matches(Version)) { "The Workers take only versions like 1.4.0, not $version" }
    check(postHog == null || postHog.matches(Regex("phc_[A-Za-z0-9]+"))) { "$PostHogKeyVariable must be a PostHog project capture key" }
    return buildMap {
        if (master != null) {
            if (url != null) {
                put("preview-url", url)
                put("preview-key", releaseKey(master, "preview", version))
            }
            put("invite-key", releaseKey(master, "invite", version))
        }
        if (postHog != null) put("posthog-key", postHog)
    }
}

/** The Workers derive the same key for this release. */
internal fun releaseKey(master: String, purpose: String, version: String): String =
    HexFormat.of().formatHex(hmac(master.encodeToByteArray()).doFinal("$purpose\n$version".encodeToByteArray()))

/** Its own inverse. The app's `BuildSecrets.unmask` must match it. */
internal fun mask(key: ByteArray, name: String, value: ByteArray): ByteArray {
    val mac = hmac(key)
    val blocks = List((value.size + 31) / 32) { block -> mac.doFinal("$name\n".encodeToByteArray() + ByteBuffer.allocate(4).putInt(block).array()) }
    return ByteArray(value.size) { i -> (value[i].toInt() xor blocks[i / 32][i % 32].toInt()).toByte() }
}

internal fun maskSource(packageName: String, key: ByteArray): String =
    "package $packageName\n\ninternal object BuildMask {\n    fun bytes(): ByteArray = byteArrayOf(${key.joinToString()})\n}\n"

private fun hmac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }

private fun Path.holds(bytes: ByteArray) = isRegularFile() && readBytes().contentEquals(bytes)

private fun Path.update(bytes: ByteArray) {
    if (!holds(bytes)) apply { parent.createDirectories() }.writeBytes(bytes)
}

private const val ShortestKey = 32
private const val MaskBytes = 32
private val Version = Regex("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")
