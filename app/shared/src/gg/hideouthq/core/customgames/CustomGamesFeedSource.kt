package gg.hideouthq.core.customgames

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.SignedAnswer
import gg.hideouthq.core.data.SignedDownload
import gg.hideouthq.core.data.quietly
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface CustomGamesFeedSource {
    /** The newest feed accepted on this computer, for when the network has nothing better. */
    suspend fun lastGood(): CustomGamesFeed?

    suspend fun fetch(): CustomGamesFetch
}

sealed interface CustomGamesFetch {
    data class Fresh(val feed: CustomGamesFeed) : CustomGamesFetch

    sealed interface Problem : CustomGamesFetch

    data class Unreachable(val detail: String) : Problem

    data class Refused(val why: FeedRefusal) : Problem
}

enum class FeedRefusal { BadSignature, Unreadable, Rollback }

/**
 * The feed at [feedUrl], accepted only when its detached signature matches under a key of its own. [verify] takes the
 * exact bytes and the signature as base64. Each accepted feed is kept in [storage] with its signature and checked again
 * when read back. A feed's `sequence` must not fall, so the kept one is the highest ever accepted, and an older feed,
 * however genuine, is refused while the kept one stays.
 */
class SignedCustomGamesFeed(
    client: HttpClient,
    private val feedUrl: String,
    private val storage: CacheStorage,
    verify: (bytes: ByteArray, signatureBase64: String) -> Boolean,
) : CustomGamesFeedSource {
    private val signed = SignedDownload(client, "the custom games feed", verify)
    private val keeping = Mutex()

    override suspend fun lastGood(): CustomGamesFeed? {
        val kept = quietly { storage.read(FileName)?.let { KeptJson.decodeFromString<KeptModes>(it) } } ?: return null
        if (!signed.genuine(kept.text.encodeToByteArray(), kept.signature)) return null
        return parseCustomGamesFeed(kept.text)
    }

    override suspend fun fetch(): CustomGamesFetch {
        val answer = when (val fetched = signed.fetch(feedUrl)) {
            is SignedAnswer.Unreachable -> return CustomGamesFetch.Unreachable(fetched.detail)
            SignedAnswer.Forged -> return CustomGamesFetch.Refused(FeedRefusal.BadSignature)
            is SignedAnswer.Genuine -> fetched
        }
        val text = answer.bytes.decodeToString()
        val feed = parseCustomGamesFeed(text) ?: return CustomGamesFetch.Refused(FeedRefusal.Unreadable)
        return keeping.withLock {
            val highest = lastGood()?.sequence ?: 0
            if (feed.sequence < highest) return@withLock CustomGamesFetch.Refused(FeedRefusal.Rollback)
            quietly { storage.write(FileName, KeptJson.encodeToString(KeptModes(text, answer.signature))) }
            CustomGamesFetch.Fresh(feed)
        }
    }

    private companion object {
        const val FileName = "custom-games-feed.json"
        val KeptJson = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class KeptModes(val text: String, val signature: String)
