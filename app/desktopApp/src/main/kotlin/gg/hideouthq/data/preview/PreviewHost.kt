package gg.hideouthq.data.preview

import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.BuildSecrets
import gg.hideouthq.data.DesktopBuild
import gg.hideouthq.data.UserPropertiesFile
import gg.hideouthq.data.userOverride
import java.nio.file.Path

internal class BuiltPreviewHost(val url: String, val key: PreviewKey)

internal object PreviewHost {
    const val Property = "hideout.preview.baseUrl"
    const val Environment = "HIDEOUT_PREVIEW_BASE_URL"

    val built: BuiltPreviewHost? by lazy {
        val url = BuildSecrets.text("preview-url") ?: return@lazy null
        val mac = BuildSecrets.mac("preview-key") ?: return@lazy null
        BuiltPreviewHost(url, PreviewKey(mac, url, DesktopBuild.read().about.version))
    }

    fun fromProcess(): String? = resolve(System.getProperty(Property), System.getenv(Environment), AppDirs.config().toPath().resolve(UserPropertiesFile), built?.url)

    fun resolve(property: String?, environment: String?, userFile: Path, default: String?): String? =
        PreviewCache.baseUrl(userOverride(property, environment, userFile, Property) ?: default)
}
