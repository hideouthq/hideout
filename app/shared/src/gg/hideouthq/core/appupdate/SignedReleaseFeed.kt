package gg.hideouthq.core.appupdate

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.SignedAnswer
import gg.hideouthq.core.data.SignedDownload
import gg.hideouthq.core.data.quietly
import io.ktor.client.HttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The feed at [feedUrl], believed only when the detached signature at [feedUrl]`.sig` matches its exact bytes. [verify]
 * takes those bytes and the signature as base64. Each genuine feed is kept in [storage] with its signature and proven
 * again when read back, so a file edited on disk is no feed at all.
 */
class SignedReleaseFeed(
    client: HttpClient,
    private val feedUrl: String,
    private val storage: CacheStorage,
    private val clock: Clock,
    verify: (bytes: ByteArray, signatureBase64: String) -> Boolean,
) : ReleaseFeedSource {
    private val signed = SignedDownload(client, "the release feed", verify)

    override suspend fun lastGood(): KnownFeed? {
        val kept = quietly { storage.read(FileName)?.let { KeptJson.decodeFromString<KeptFeed>(it) } } ?: return null
        if (!signed.genuine(kept.text.encodeToByteArray(), kept.signature)) return null
        val feed = parseReleaseFeed(kept.text, feedUrl) ?: return null
        return KnownFeed(feed, Instant.fromEpochMilliseconds(kept.fetchedAtMillis))
    }

    override suspend fun fetch(): FeedFetch {
        val answer = when (val fetched = signed.fetch(feedUrl)) {
            is SignedAnswer.Unreachable -> return FeedFetch.Unreachable(fetched.detail)
            SignedAnswer.Forged -> return FeedFetch.Refused("The release feed's signature doesn't match")
            is SignedAnswer.Genuine -> fetched
        }
        val text = answer.bytes.decodeToString()
        val feed = parseReleaseFeed(text, feedUrl) ?: return FeedFetch.Refused("The release feed can't be read by this version")
        val known = KnownFeed(feed, clock.now())
        quietly { storage.write(FileName, KeptJson.encodeToString(KeptFeed(text, answer.signature, known.fetchedAt.toEpochMilliseconds()))) }
        return FeedFetch.Fresh(known)
    }

    private companion object {
        const val FileName = "app-update-feed.json"
        val KeptJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class KeptFeed(val text: String, val signature: String, val fetchedAtMillis: Long)
