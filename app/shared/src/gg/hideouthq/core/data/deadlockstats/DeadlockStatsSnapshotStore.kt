package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DeadlockStatsSnapshot(
    val accountId: Long,
    val profile: PlayerProfile? = null,
    val matchHistory: List<MatchHistoryEntry> = emptyList(),
    val rank: RankStatus? = null,
    val mateStats: List<PlayedWithStats> = emptyList(),
    val enemyStats: List<PlayedWithStats> = emptyList(),
    val matchSummaries: List<MatchPlayerSummary> = emptyList(),
    val fetchedAt: Map<String, Long> = emptyMap(),
)

@Serializable
internal data class DeadlockAssetsSnapshot(
    val items: List<ItemAsset> = emptyList(),
    val ranks: List<RankAsset> = emptyList(),
    val fetchedAt: Long = 0,
)

internal class DeadlockStatsSnapshotStore(private val storage: CacheStorage) {
    suspend fun load(accountId: SteamAccountId): DeadlockStatsSnapshot? =
        read(fileName(accountId.value), DeadlockStatsSnapshot.serializer())?.takeIf { it.accountId == accountId.value }

    suspend fun save(snapshot: DeadlockStatsSnapshot) = write(fileName(snapshot.accountId), DeadlockStatsSnapshot.serializer(), snapshot)

    suspend fun loadAssets(): DeadlockAssetsSnapshot? = read(AssetsFileName, DeadlockAssetsSnapshot.serializer())

    suspend fun saveAssets(assets: DeadlockAssetsSnapshot) = write(AssetsFileName, DeadlockAssetsSnapshot.serializer(), assets)

    private suspend fun <T> read(name: String, serializer: KSerializer<T>): T? = try {
        storage.read(name)?.let { SnapshotJson.decodeFromString(serializer, it) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun <T> write(name: String, serializer: KSerializer<T>, value: T) {
        storage.write(name, SnapshotJson.encodeToString(serializer, value))
    }

    private fun fileName(accountId: Long) = "deadlock-stats-$accountId.json"

    private companion object {
        const val AssetsFileName = "deadlock-stats-assets.json"
        val SnapshotJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}
