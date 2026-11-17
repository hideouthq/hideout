package gg.hideouthq.core.data.live

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.gamebanana.Submission
import gg.hideouthq.core.data.gamebanana.SubmissionCategory
import gg.hideouthq.core.data.quietly
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.Hero
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

@Serializable
internal data class CatalogSnapshot(
    val schemaVersion: Int,
    val syncedAt: Long,
    val categories: List<SubmissionCategory>,
    val submissions: List<Submission>,
    val featured: List<SubmissionRef>,
    val checkedAt: Long = syncedAt,
    val modifiedThrough: Map<SubmissionKind, Long> = emptyMap(),
    @Transient val unheard: Map<SubmissionKind, UnheardLimit> = emptyMap(),
) {
    companion object {
        const val SchemaVersion = 9
    }
}

internal data class UnheardLimit(val addedAt: Long?, val downloads: Int, val likes: Int)

internal class SnapshotStore(private val storage: CacheStorage) {
    suspend fun load(): CatalogSnapshot? = quietly {
        storage.read(FileName)?.let { text ->
            SnapshotJson.decodeFromString(CatalogSnapshot.serializer(), text)
                .takeIf { it.schemaVersion == CatalogSnapshot.SchemaVersion }
        }
    }

    suspend fun save(snapshot: CatalogSnapshot) {
        storage.write(FileName, SnapshotJson.encodeToString(CatalogSnapshot.serializer(), snapshot))
    }

    suspend fun loadHeroes(): List<Hero> =
        quietly { storage.read("heroes.json")?.let { SnapshotJson.decodeFromString<List<Hero>>(it) } }.orEmpty()

    suspend fun saveHeroes(heroes: List<Hero>) {
        storage.write("heroes.json", SnapshotJson.encodeToString(heroes))
    }

    private companion object {
        const val FileName = "catalog.json"
        val SnapshotJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
