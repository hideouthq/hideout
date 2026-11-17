package gg.hideouthq.core.data.fake

import gg.hideouthq.core.customgames.SiteOrigin
import gg.hideouthq.core.sharing.ProfileShares
import gg.hideouthq.core.sharing.ShareCode
import gg.hideouthq.core.sharing.SharedLookup
import gg.hideouthq.core.sharing.SharedProfile
import gg.hideouthq.preview.sha256Hex

/** The site's profile store in memory: the same profile gets the same code, and while not [reachable] every call fails as offline. */
class FakeProfileShares(override val site: SiteOrigin? = SiteOrigin.parse("https://hideouthq.gg"), var reachable: Boolean = true) : ProfileShares {
    private val kept = mutableMapOf<ShareCode, ByteArray>()

    override suspend fun keep(profile: SharedProfile): ShareCode? {
        if (!reachable || site == null) return null
        val bytes = profile.bytes()
        val code = checkNotNull(ShareCode.parse(sha256Hex(bytes).take(CodeLength).uppercase()))
        kept[code] = bytes
        return code
    }

    override suspend fun find(code: ShareCode): SharedLookup = when {
        !reachable || site == null -> SharedLookup.Unreachable
        else -> kept[code]?.let(SharedProfile::read)?.let(SharedLookup::Found) ?: SharedLookup.Missing
    }

    private companion object {
        const val CodeLength = 8
    }
}
