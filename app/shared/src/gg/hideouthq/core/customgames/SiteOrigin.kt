package gg.hideouthq.core.customgames

import java.net.URI
import java.net.URISyntaxException

/** Where invite links and the game's bridge page point. No game traffic goes near it. */
@JvmInline
value class SiteOrigin private constructor(val value: String) {
    companion object {
        /** `https://host[:port]` and nothing else, since `/join` and the code are appended to it. */
        fun parse(text: String): SiteOrigin? {
            val uri = try {
                URI(text.trim().removeSuffix("/"))
            } catch (e: URISyntaxException) {
                return null
            }
            val bare = uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.userInfo == null &&
                uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null
            return if (bare) SiteOrigin(uri.toString()) else null
        }
    }
}
