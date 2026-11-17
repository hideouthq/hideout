package gg.hideouthq.core.data.fake

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.preview.IndexEntry

data class FakeConfig(
    val latencyMillis: Long = 0,
    val seed: LibrarySeed = LibrarySeed.Sample,
    val previews: Map<String, IndexEntry> = emptyMap(),
    val game: SampleGame = SampleGame.Found,
    val gameInfo: SampleGameInfo = SampleGameInfo.HideoutBlock,
    val setupDone: Boolean = true,
    val foreign: ForeignSeed = ForeignSeed.None,
    val autoexec: String? = null,
    val appUpdate: FakeAppUpdate = FakeAppUpdate.None,
    val mature: Set<String> = emptySet(),
)

enum class SampleGameInfo { HideoutBlock, Rewritten, AnotherTool }

enum class LibrarySeed { Sample, Empty }

internal object NoCacheStorage : CacheStorage {
    override suspend fun read(name: String): String? = null

    override suspend fun write(name: String, text: String) = Unit
}
