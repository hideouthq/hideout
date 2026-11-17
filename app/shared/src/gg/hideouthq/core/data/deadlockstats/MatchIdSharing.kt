package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class MatchSalts(
    @SerialName("match_id") val matchId: Long,
    @SerialName("cluster_id") val clusterId: Long,
    @SerialName("metadata_salt") val metadataSalt: Long,
)

internal enum class SaltsUpload { Accepted, Rejected, Unavailable }

internal class MatchIdSharing(
    private val api: DeadlockStatsApi,
    private val storage: CacheStorage,
    private val scope: CoroutineScope,
) {
    @Serializable private data class Saved(val ids: List<Long> = emptyList())

    private val lock = Any()
    private val file = Mutex()
    private var since: Long? = null
    private val pending = LinkedHashMap<Long, MatchSalts>()
    private var sender: Job? = null
    private var sent: LinkedHashSet<Long>? = null

    fun configure(since: Long?) = synchronized(lock) {
        this.since = since
        if (since == null) {
            pending.clear()
            sender?.cancel()
            sender = null
        }
    }

    suspend fun offer(ref: ReplayMetadataRef, foundAt: Long) {
        if (!wanted(foundAt)) return
        val sent = file.withLock { loaded() }
        synchronized(lock) {
            if (!wanted(foundAt) || ref.matchId in sent || ref.matchId in pending) return
            pending[ref.matchId] = MatchSalts(ref.matchId, ref.clusterId, ref.metadataSalt)
            if (sender == null) sender = scope.launch { send() }
        }
    }

    private fun wanted(foundAt: Long) = synchronized(lock) { since?.let { foundAt >= it } == true }

    private suspend fun send() {
        val me = currentCoroutineContext().job
        while (true) {
            val batch = synchronized(lock) {
                if (sender !== me) return
                pending.values.take(DeadlockStatsApi.MaxSaltsBatch).ifEmpty { sender = null; return }
            }
            val answer = try { api.submitSalts(batch) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { SaltsUpload.Unavailable }
            if (answer != SaltsUpload.Unavailable) remember(batch.map { it.matchId })
            synchronized(lock) {
                if (sender !== me) return
                batch.forEach { pending.remove(it.matchId) }
            }
        }
    }

    private suspend fun loaded(): LinkedHashSet<Long> {
        synchronized(lock) { sent }?.let { return it }
        val saved = try {
            storage.read(FileName)?.let { json.decodeFromString<Saved>(it).ids }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { null }
        return LinkedHashSet(saved.orEmpty().takeLast(MaxRemembered)).also { synchronized(lock) { sent = it } }
    }

    private suspend fun remember(ids: List<Long>) = file.withLock {
        val sent = loaded()
        val snapshot = synchronized(lock) {
            ids.forEach { sent.remove(it); sent.add(it) }
            while (sent.size > MaxRemembered) sent.remove(sent.first())
            sent.toList()
        }
        try { storage.write(FileName, json.encodeToString(Saved(snapshot))) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { }
    }

    companion object {
        const val FileName = "deadlock-shared-matches.json"
        const val MaxRemembered = 5_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
