package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class MatchDetailArchive(private val storage: CacheStorage) {
    @Serializable private data class Index(val ids: Set<Long> = emptySet())
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val indices = mutableMapOf<Long, Set<Long>>()

    private suspend fun index(account: Long): Set<Long> {
        indices[account]?.let { return it }
        val text = storage.read(indexName(account)) ?: return emptySet()
        return (try { json.decodeFromString<Index>(text).ids } catch (e: IllegalArgumentException) { emptySet() }).also { indices[account] = it }
    }

    suspend fun load(account: Long, match: Long): MatchDetail? = try {
        if (match !in index(account)) null
        else storage.read(name(account, match))?.let { json.decodeFromString<MatchDetail>(it) }?.takeIf { it.matchId == match }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    suspend fun save(account: Long, detail: MatchDetail) {
        val old = index(account)
        storage.write(name(account, detail.matchId), json.encodeToString(detail))
        if (detail.matchId !in old) {
            val updated = old + detail.matchId
            storage.write(indexName(account), json.encodeToString(Index(updated)))
            indices[account] = updated
        }
    }

    suspend fun clear(account: Long) {
        for (id in index(account)) storage.write(name(account, id), "")
        storage.write(indexName(account), json.encodeToString(Index()))
        indices[account] = emptySet()
    }

    private fun indexName(account: Long) = "steam-match-index-$account.json"
    private fun name(account: Long, match: Long) = "steam-match-$account-$match.json"
}
