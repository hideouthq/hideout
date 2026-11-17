package gg.hideouthq.appupdate

import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.UserPropertiesFile
import gg.hideouthq.data.preview.PreviewCache
import gg.hideouthq.data.userOverride
import java.nio.file.Path

internal object ReleaseFeedUrl {
    const val Property = "hideout.update.feedUrl"
    const val Environment = "HIDEOUT_UPDATE_FEED_URL"
    const val Default = "https://github.com/hideouthq/hideout/releases/latest/download/releases.json"

    fun fromProcess(): String? = resolve(System.getProperty(Property), System.getenv(Environment), AppDirs.config().toPath().resolve(UserPropertiesFile), isNativeImage())

    fun resolve(property: String?, environment: String?, userFile: Path, native: Boolean): String? =
        PreviewCache.baseUrl(userOverride(property, environment, userFile, Property) ?: if (native) Default else null)
}

internal fun isNativeImage() = System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime"
