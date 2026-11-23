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
internal const val PreviewMasterVariable = "HIDEOUT_BUILD_PREVIEW_MASTER"
internal const val InviteMasterVariable = "HIDEOUT_BUILD_INVITE_MASTER"
internal const val PostHogKeyVariable = "HIDEOUT_BUILD_POSTHOG_KEY"

/**
 * The values a build ships, each masked into the resource `<id>/secrets/<name>` under one random key. The key goes into
 * `fun bytes()` of the generated `BuildMask` object in the id's package, so it is compiled code and not a file beside the
 * data. A build keeps its key while the values stay the same, so a build that changes nothing compiles nothing.
 */
@TaskAction(executionAvoidance = ExecutionAvoidance.Disabled)
fun writeBuildSecrets(id: String, version: String, @Input localFile: Path, @Output sourceDir: Path, @Output resourceDir: Path, @Output stateDir: Path) =
    writeBuildSecretsFiles(id, version, localFile, BuildSecretsOutput(sourceDir, resourceDir, stateDir), System::getenv)

/** Where the generated `BuildMask` source, the masked resources and the key a later build reuses go. */
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

/**
 * What a build masks, by the name the app reads it under. Preview, invite and directory requests are signed with keys
 * derived from their masters for this version, so a key taken from one release is only that release's.
 */
internal fun buildSecrets(version: String, value: (String) -> String?): Map<String, String> {
    val url = value(PreviewUrlVariable)
    val master = value(PreviewMasterVariable)
    val invite = value(InviteMasterVariable)
    val postHog = value(PostHogKeyVariable)
    check((url == null) == (master == null)) {
        "Set both $PreviewUrlVariable and $PreviewMasterVariable, in the environment or in the preview file, or neither: a build with one has no working previews"
    }
    check(master == null || master.length >= ShortestKey) { "$PreviewMasterVariable is shorter than $ShortestKey characters, which the preview Worker refuses" }
    check(invite == null || invite.length >= ShortestKey) { "$InviteMasterVariable is shorter than $ShortestKey characters, which the site Worker refuses" }
    check((master == null && invite == null) || version.matches(Version)) { "The Workers take only versions like 1.4.0, not $version" }
    check(postHog == null || postHog.matches(Regex("phc_[A-Za-z0-9]+"))) { "$PostHogKeyVariable must be a PostHog project capture key" }
    return buildMap {
        if (url != null && master != null) {
            put("preview-url", url)
            put("preview-key", releaseKey(master, "preview", version))
        }
        if (invite != null) put("invite-key", releaseKey(invite, "invite", version))
        if (postHog != null) put("posthog-key", postHog)
    }
}

/** The hex HMAC-SHA256, under [master], of [purpose], a newline and [version]: the key the service derives alike for this release. */
internal fun releaseKey(master: String, purpose: String, version: String): String =
    HexFormat.of().formatHex(hmac(master.encodeToByteArray()).doFinal("$purpose\n$version".encodeToByteArray()))

/** [value] XORed with HMAC-SHA256 blocks, under [key], of [name], a newline and the block's number. It unmasks itself. */
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
