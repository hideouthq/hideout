package gg.hideouthq.core.data.preview

import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.assetPathProblem
import gg.hideouthq.preview.isBundleId

/**
 * A bundle's generated image as an image address. Only the desktop image loader resolves it, through the current
 * index, the bundle's verified manifest and the image's own hash; anything else fails rather than shows.
 */
sealed interface PreviewImageAddress {
    val bundleId: String
    val url: String

    /** A captured still, shown only while the bundle's pictures are ready. */
    data class Picture(override val bundleId: String, val role: PictureRole) : PreviewImageAddress {
        override val url: String get() = "$Scheme://$bundleId/${role.name.lowercase()}"
    }

    /** An ability icon, by the asset path its manifest lists it under. */
    data class Icon(override val bundleId: String, val asset: String) : PreviewImageAddress {
        override val url: String get() = "$Scheme://$bundleId/$IconPath/$asset"
    }

    companion object {
        const val Scheme = "hideout-preview"
        private const val IconPath = "icon"

        fun parse(url: String): PreviewImageAddress? {
            val rest = url.removePrefix("$Scheme://").takeIf { it != url } ?: return null
            val bundleId = rest.substringBefore('/').takeIf(::isBundleId) ?: return null
            val path = rest.substringAfter('/', "")
            val asset = path.removePrefix("$IconPath/").takeIf { it != path }
            if (asset != null) return asset.takeIf { assetPathProblem(it) == null }?.let { Icon(bundleId, it) }
            return PictureRole.entries.firstOrNull { it.name.lowercase() == path }?.let { Picture(bundleId, it) }
        }
    }
}
