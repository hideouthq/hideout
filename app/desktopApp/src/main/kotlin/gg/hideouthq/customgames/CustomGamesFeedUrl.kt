package gg.hideouthq.customgames

import gg.hideouthq.appupdate.signatureCheck
import gg.hideouthq.core.customgames.CustomGamesFeedSource
import gg.hideouthq.core.customgames.SignedCustomGamesFeed
import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.data.AppDirs
import gg.hideouthq.data.UserPropertiesFile
import gg.hideouthq.data.userOverride
import io.ktor.client.HttpClient
import java.nio.file.Path

internal object CustomGamesFeedUrl {
    const val Property = "hideout.customgames.feedUrl"
    const val Environment = "HIDEOUT_CUSTOMGAMES_FEED_URL"
    const val Default = "https://hideouthq.gg/customgames/customgames.json"

    fun resolve(property: String?, environment: String?, userFile: Path): String = userOverride(property, environment, userFile, Property) ?: Default
}

internal object CustomGamesFeedKey {
    const val Property = "hideout.customgames.feedKey"
    const val Environment = "HIDEOUT_CUSTOMGAMES_FEED_KEY"
    const val Default = "MCowBQYDK2VwAyEAhurl6iMAxAZhsvDzUl27iFpJphpy0RLVv0tHi3t8fyM="

    fun resolve(property: String?, environment: String?, userFile: Path): String = userOverride(property, environment, userFile, Property) ?: Default
}

internal object CustomGamesSite {
    const val Property = "hideout.customgames.siteOrigin"
    const val Environment = "HIDEOUT_CUSTOMGAMES_SITE_ORIGIN"
    const val Default = "https://hideouthq.gg"

    fun resolve(property: String?, environment: String?, userFile: Path): SiteOrigin? =
        SiteOrigin.parse(userOverride(property, environment, userFile, Property) ?: Default)

    fun fromProcess(): SiteOrigin? =
        resolve(System.getProperty(Property), System.getenv(Environment), AppDirs.config().toPath().resolve(UserPropertiesFile))

    /**
     * It must load with no redirect, so it ends in a slash: Cloudflare Pages redirects a path ending in `.html`, and the
     * renderer acknowledges each title by navigating to this address plus a fragment, which would then reload the page.
     */
    fun bridgePage(site: SiteOrigin?): String = "${site?.value ?: Default}/bridge/"
}

internal fun customGamesFeed(client: HttpClient, storage: CacheStorage): CustomGamesFeedSource {
    val userFile = AppDirs.config().toPath().resolve(UserPropertiesFile)
    val url = CustomGamesFeedUrl.resolve(System.getProperty(CustomGamesFeedUrl.Property), System.getenv(CustomGamesFeedUrl.Environment), userFile)
    val key = CustomGamesFeedKey.resolve(System.getProperty(CustomGamesFeedKey.Property), System.getenv(CustomGamesFeedKey.Environment), userFile)
    return SignedCustomGamesFeed(client, url, storage, signatureCheck(key))
}
