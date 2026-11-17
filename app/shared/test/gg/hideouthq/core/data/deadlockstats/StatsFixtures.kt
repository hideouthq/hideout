package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaData
import gg.hideouthq.core.data.deadlock.proto.CMsgMatchMetaDataContents
import gg.hideouthq.core.data.fake.MemoryStorage
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Instant

internal fun bzip2(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> BZip2CompressorOutputStream(out).use { it.write(bytes) } }.toByteArray()

internal fun metadataFile(info: CMsgMatchMetaDataContents.MatchInfo): ByteArray =
    bzip2(CMsgMatchMetaData(match_id = info.match_id, match_details = CMsgMatchMetaDataContents(match_info = info).encodeByteString()).encode())

internal fun TestScope.clock(): Clock = object : Clock {
    override fun now() = Instant.fromEpochMilliseconds(testScheduler.currentTime + 100_000)
}

internal fun TestScope.steamCollector(
    port: SteamTrackingPort,
    storage: CacheStorage = MemoryStorage(),
    downloaded: MutableSet<Long> = mutableSetOf(),
    spacingMillis: Long = 10,
    hasMetadata: suspend (Long) -> Boolean = { it in downloaded },
    history: suspend (List<MatchHistoryEntry>) -> Unit = {},
    publish: (SteamTrackingStatus) -> Unit = {},
    backgroundMetadataMillis: Long = 0,
    ingest: suspend (ReplayMetadataRef) -> Unit = { downloaded += it.matchId },
) = SteamTrackingCollector(555, port, storage, clock(), history, hasMetadata, ingest, publish, spacingMillis, backgroundMetadataMillis)

internal suspend fun CacheStorage.checkpoint(): SteamTrackingCollector.Checkpoint =
    Json.decodeFromString(assertNotNull(read(SteamTrackingCollector.checkpointName(555))))
