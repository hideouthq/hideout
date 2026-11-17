package gg.hideouthq.core.data.collections

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.quietly
import gg.hideouthq.core.model.SkinCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** The published collections file. It throws when the host cannot be reached, has no such file, or the file fails its check. */
fun interface CollectionsSource {
    suspend fun collectionsText(): String
}

/** The skin collections in the order they are published; empty until a good list is known, and where there are none. */
interface CollectionsRepository {
    val collections: StateFlow<List<SkinCollection>>

    object None : CollectionsRepository {
        override val collections: StateFlow<List<SkinCollection>> = MutableStateFlow(emptyList<SkinCollection>()).asStateFlow()
    }
}

/**
 * The collections from the preview host, as [gg.hideouthq.core.data.preview.PreviewRepository] keeps its index:
 * the copy saved in [storage] at once, then a refresh in [scope] hourly, or after a minute when the host could not be
 * reached. A failed or rejected refresh keeps the last good list.
 */
class LiveCollectionsRepository(source: CollectionsSource, private val storage: CacheStorage, scope: CoroutineScope) : CollectionsRepository {
    private val current = MutableStateFlow(emptyList<SkinCollection>())
    override val collections: StateFlow<List<SkinCollection>> = current.asStateFlow()

    init {
        scope.launch {
            saved()?.let { current.value = it }
            while (true) delay(if (refresh(source)) 1.hours else 1.minutes)
        }
    }

    private suspend fun refresh(source: CollectionsSource): Boolean {
        val text = quietly { source.collectionsText() } ?: return false
        current.value = readable(text) ?: return true
        quietly { storage.write(FileName, text) }
        return true
    }

    private suspend fun saved(): List<SkinCollection>? = quietly { storage.read(FileName) }?.let(::readable)

    private fun readable(text: String): List<SkinCollection>? = try {
        decodeCollections(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    private companion object {
        const val FileName = "collections.json"
    }
}
