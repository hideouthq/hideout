package gg.hideouthq.core.data.fake

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal class FakeStore(val config: FakeConfig) {
    val mods = MutableStateFlow(
        SampleCatalog.mods.map { mod ->
            val rated = mod.copy(mature = mod.id in config.mature)
            config.previews[mod.id]?.let { rated.copy(preview = it, has3dPreview = true) } ?: rated
        } + config.catalogued,
    )

    fun <T> Flow<T>.withLatency(): Flow<T> = if (config.latencyMillis <= 0) this else flow {
        delay(config.latencyMillis)
        emitAll(this@withLatency)
    }
}
