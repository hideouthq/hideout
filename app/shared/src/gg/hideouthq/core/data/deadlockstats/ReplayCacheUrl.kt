package gg.hideouthq.core.data.deadlockstats

import java.net.URI
import java.net.URISyntaxException

/** A match's metadata file on the replay CDN: its match, the `replayN` cluster that serves it, and its salt. */
data class ReplayMetadataRef(val matchId: Long, val clusterId: Long, val metadataSalt: Long, val url: String)

/** Adapted from deadlock-api-ingest/src/scan_cache.rs and utils.rs (MIT; see bundled notice). */
object ReplayCacheUrl {
    private val host = Regex("replay([0-9]+)\\.valve\\.net")
    private val path = Regex("/1422450/([0-9]+)_([0-9]+)\\.meta\\.bz2")
    // Steam can store the host and path separately with binary separators, without a URL scheme.
    private val cacheReference = Regex("(?<![A-Za-z0-9._-])(replay[0-9]+\\.valve\\.net)[\\x00-\\x20]{0,16}(/1422450/[0-9]+_[0-9]+\\.meta\\.bz2)(?=[ '\\x00\\n\\r\"?]|$)")

    fun parseMetadataUrl(raw: String): ReplayMetadataRef? {
        if (raw.length !in 1..512) return null
        val uri = try { URI(raw) } catch (_: IllegalArgumentException) { return null } catch (_: URISyntaxException) { return null }
        if (uri.scheme !in listOf("http", "https") || uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port != if (uri.scheme == "http") 80 else 443) return null
        val cluster = host.matchEntire(uri.host ?: return null)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (cluster !in 0..4_294_967_295L) return null
        val parts = path.matchEntire(uri.rawPath ?: return null)?.groupValues ?: return null
        val id = parts[1].toLongOrNull()?.takeIf { it in 1..10_000_000_000L } ?: return null
        val salt = parts[2].toLongOrNull()?.takeIf { it in 0..4_294_967_295L } ?: return null
        if (uri.rawQuery != null && !Regex("v=[0-9]+").matches(uri.rawQuery)) return null
        return ReplayMetadataRef(id, cluster, salt, "${uri.scheme}://${uri.host}${uri.rawPath}")
    }

    /** The first metadata reference in a Steam cache file's first 4 KiB. */
    fun findMetadataUrl(bytes: ByteArray): ReplayMetadataRef? =
        cacheReference.findAll(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))
            .firstNotNullOfOrNull { parseMetadataUrl("http://${it.groupValues[1]}${it.groupValues[2]}") }
}
