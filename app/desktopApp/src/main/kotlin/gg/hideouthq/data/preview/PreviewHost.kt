package gg.hideouthq.data.preview

import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.AppId
import gg.hideouthq.data.UserPropertiesFile
import gg.hideouthq.data.userOverride
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest

internal class BuiltPreviewHost(val url: String, val key: PreviewKey)

internal object PreviewHost {
    const val Property = "hideout.preview.baseUrl"
    const val Environment = "HIDEOUT_PREVIEW_BASE_URL"
    val Resource = "/${AppId.replace('.', '/')}/preview.bin"

    val built: BuiltPreviewHost? by lazy {
        PreviewHost::class.java.getResourceAsStream(Resource)?.use { unmask(it.readBytes()) }?.let { (url, secret) -> BuiltPreviewHost(url, PreviewKey(secret, url)) }
    }

    fun fromProcess(): String? = resolve(System.getProperty(Property), System.getenv(Environment), AppDirs.config().toPath().resolve(UserPropertiesFile), built?.url)

    fun resolve(property: String?, environment: String?, userFile: Path, default: String?): String? =
        PreviewCache.baseUrl(userOverride(property, environment, userFile, Property) ?: default)

    fun unmask(stored: ByteArray): Pair<String, String> {
        val seed = stored.copyOfRange(0, SeedBytes)
        val plain = ByteArray(stored.size - SeedBytes) { i ->
            val block = MessageDigest.getInstance("SHA-256").apply { update(seed); update(ByteBuffer.allocate(4).putInt(i / 32).array()) }.digest()
            (stored[SeedBytes + i].toInt() xor block[i % 32].toInt()).toByte()
        }.decodeToString()
        return plain.substringBefore('\n') to plain.substringAfter('\n')
    }

    private const val SeedBytes = 32
}
