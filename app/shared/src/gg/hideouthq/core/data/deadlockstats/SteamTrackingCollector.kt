package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.CacheStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

internal class SteamTrackingCollector(
    private val account: Long,
    private val port: SteamTrackingPort,
    private val storage: CacheStorage,
    private val clock: Clock,
    private val ingestHistory: suspend (List<MatchHistoryEntry>) -> Unit,
    private val hasMetadata: suspend (Long) -> Boolean,
    private val ingestMetadata: suspend (ReplayMetadataRef) -> Unit,
    private val publish: (SteamTrackingStatus) -> Unit,
    private val spacingMillis: Long = 3000,
    private val backgroundMetadataMillis: Long = 120_000,
) {
    @Serializable internal data class Checkpoint(
        val historyCursor: Long? = null,
        val seenCursors: Set<Long> = emptySet(),
        val pending: List<Long> = emptyList(),
        val requested: Set<Long> = emptySet(),
        val references: Map<Long, String> = emptyMap(),
        val cooldowns: Map<String, Long> = emptyMap(),
        val historyIds: Set<Long> = emptySet(),
        val retryMetadataAt: Map<Long, Long> = emptyMap(),
        val metadataFailures: Map<Long, Int> = emptyMap(),
        val freshUntil: Map<Long, Long> = emptyMap(),
        val requestFailures: Map<String, Int> = emptyMap(),
    )
    private sealed interface Command {
        data object Backfill : Command
        data object Refresh : Command
        data class Detail(val id: Long) : Command
        data class Event(val event: SteamTrackingEvent) : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val json = Json { ignoreUnknownKeys = true }
    private var saved = Checkpoint()
    private var persisted: String? = null

    fun backfill() { commands.trySend(Command.Backfill) }
    fun refresh() { commands.trySend(Command.Refresh) }
    fun detail(matchId: Long) { commands.trySend(Command.Detail(matchId)) }

    suspend fun run(): Unit = coroutineScope {
        persisted = storage.read(checkpointName(account))
        saved = persisted?.let { json.decodeFromString<Checkpoint>(it) } ?: Checkpoint()
        saved = saved.copy(cooldowns = saved.cooldowns.mapValues { minOf(it.value, now() + 3600) })
        val updates = launch { port.events.collect { commands.send(Command.Event(it)) } }
        try {
            port.start()
            var previous: SteamGcSession? = null
            var recentAt = 0L
            var recentRetries = 0
            var metadataTurn = false
            val urgent = (saved.requested + saved.freshUntil.keys).toMutableSet()
            while (isActive) {
                val session = port.session.value?.takeIf { it.accountId == account }
                if (session != previous) { previous = session; recentAt = 0 }
                val eligible = session != null && port.gcState.value != SteamGcReadiness.Waiting
                val now = now()
                var kind = "history"
                var metadataId: Long? = null
                try {
                    while (true) {
                        when (val command = commands.tryReceive().getOrNull() ?: break) {
                            Command.Backfill -> saved = saved.copy(historyCursor = 0, seenCursors = emptySet())
                            Command.Refresh -> { recentAt = 0; recentRetries = 3 }
                            is Command.Detail -> {
                                enqueue(listOf(command.id), first = true)
                                urgent += command.id
                                saved = saved.copy(requested = saved.requested + command.id, retryMetadataAt = saved.retryMetadataAt - command.id)
                            }
                            is Command.Event -> {
                                val eventSession = when (val event = command.event) {
                                    is SteamTrackingEvent.AccountUpdated -> event.session
                                    is SteamTrackingEvent.Gap -> event.session
                                }
                                if (eventSession == session) { recentAt = 0; recentRetries = 3 }
                            }
                        }
                    }
                    persist()
                    fun metadataEligible(id: Long) = now >= (saved.retryMetadataAt[id] ?: 0) &&
                        (id in saved.references || eligible && ready("metadata") &&
                            (id in urgent || ready(BackgroundMetadata)))
                    val candidate = saved.pending.firstOrNull { it in urgent && metadataEligible(it) }
                        ?: saved.pending.firstOrNull(::metadataEligible)
                    val recent = eligible && now >= recentAt && ready("history")
                    val page = eligible && saved.historyCursor != null && ready("history")
                    when {
                        candidate != null && (candidate in urgent || !recent && (metadataTurn || !page)) -> {
                            kind = "metadata"; metadataId = candidate
                            if (candidate in saved.requested || !hasMetadata(candidate)) {
                                val ref = saved.references[candidate]?.let(ReplayCacheUrl::parseMetadataUrl) ?: run {
                                    saved = saved.copy(cooldowns = saved.cooldowns +
                                        (BackgroundMetadata to now() + (backgroundMetadataMillis + 999) / 1000))
                                    persist()
                                    port.metadata(checkNotNull(session), candidate).also {
                                        saved = saved.copy(references = saved.references + (candidate to it.url),
                                            requestFailures = saved.requestFailures - kind)
                                        persist()
                                    }
                                }
                                ingestMetadata(ref)
                                check(hasMetadata(candidate)) { "Steam match metadata is incomplete" }
                            }
                            forget(candidate); urgent -= candidate; metadataTurn = false
                        }
                        recent || page -> {
                            kind = "history"
                            val cursor = if (recent) 0 else saved.historyCursor!!
                            val history = port.history(checkNotNull(session), cursor)
                            if (!recent && (history.cursor != 0L && (history.cursor == cursor || history.cursor in saved.seenCursors) || saved.seenCursors.size >= 1000)) {
                                saved = saved.copy(historyCursor = null)
                                error("Steam history pagination stopped: repeated cursor or page limit")
                            }
                            ingestHistory(history.matches)
                            val missing = history.matches.map { it.matchId }.filter { it !in saved.historyIds && !hasMetadata(it) }
                            enqueue(missing, first = recent)
                            val fresh = history.matches.mapNotNull { entry ->
                                val start = entry.startTime; val duration = entry.matchDurationS
                                val deadline = if (entry.matchId in missing && start != null && duration != null) start.toLong() + duration + 300 else null
                                deadline?.takeIf { it > now }?.let { entry.matchId to it }
                            }.toMap()
                            saved = saved.copy(historyIds = saved.historyIds + history.matches.map { it.matchId },
                                requestFailures = saved.requestFailures - kind, freshUntil = saved.freshUntil + fresh)
                            if (recent) {
                                urgent += fresh.keys
                                recentAt = now + if (recentRetries-- > 0) 10 else 1200
                            } else {
                                saved = saved.copy(historyCursor = history.cursor.takeIf { it != 0L }, seenCursors = saved.seenCursors + cursor)
                            }
                            metadataTurn = true
                        }
                    }
                    persist()
                    val cooldown = saved.cooldowns.filterKeys { it != BackgroundMetadata }.values.filter { it > now }.minOrNull()
                    publish(port.status.value.copy(phase = when {
                        session == null -> if (port.session.value != null) SteamTrackingPhase.AccountMismatch else port.status.value.phase
                        port.gcState.value != SteamGcReadiness.Ready -> SteamTrackingPhase.WaitingForGc
                        cooldown != null && candidate == null && !recent && !page -> SteamTrackingPhase.CoolingDown
                        saved.historyCursor != null || saved.pending.isNotEmpty() -> SteamTrackingPhase.Backfilling
                        else -> SteamTrackingPhase.Connected
                    }, summaries = saved.historyIds.size, pendingMetadata = saved.pending.size))
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val sessionLost = port.session.value != session && (metadataId == null || metadataId !in saved.references)
                    if (e is SteamGcInterrupted || sessionLost) {
                        publish(port.status.value.copy(summaries = saved.historyIds.size, pendingMetadata = saved.pending.size))
                        delay(spacingMillis)
                        continue
                    }
                    val requested = (e as? ReplayDownloadException)?.retryAfterMillis?.let { (it + 999) / 1000 } ?: 0
                    val fresh = metadataId != null && e is ReplayDownloadException && e.httpStatus == 404 && now() < (saved.freshUntil[metadataId] ?: 0)
                    val failures = if (fresh) saved.metadataFailures[metadataId] ?: 0
                        else ((if (e is SteamGcRateLimited || metadataId == null) saved.requestFailures[kind]
                            else saved.metadataFailures[metadataId]) ?: 0).plus(1).coerceAtMost(8)
                    val retry = if (fresh) now() + maxOf(requested, 15)
                        else if (e is SteamGcInvalidMatch || metadataId != null && e is ReplayDownloadException && e.httpStatus == 404 && failures >= 8) null
                        else now() + maxOf(requested, minOf(3600L, 30L shl (failures - 1)))
                    if (retry == null) metadataId?.let { forget(it); urgent -= it; saved = saved.copy(historyIds = saved.historyIds + it) }
                    else saved = if (fresh) saved.copy(retryMetadataAt = saved.retryMetadataAt + (metadataId to retry))
                    else if (e is SteamGcRateLimited || metadataId == null) saved.copy(
                        cooldowns = saved.cooldowns + (kind to retry), requestFailures = saved.requestFailures + (kind to failures))
                    else saved.copy(metadataFailures = saved.metadataFailures + (metadataId to failures),
                        retryMetadataAt = saved.retryMetadataAt + (metadataId to retry))
                    persist()
                    if (!fresh) publish(SteamTrackingStatus(if (e is SteamGcRateLimited) SteamTrackingPhase.CoolingDown else SteamTrackingPhase.Failed,
                        e.message, saved.historyIds.size, saved.pending.size))
                }
                delay(spacingMillis)
            }
        } finally { updates.cancel(); commands.close() }
    }

    private fun enqueue(ids: List<Long>, first: Boolean) {
        saved = saved.copy(pending = (if (first) ids + saved.pending else saved.pending + ids).distinct())
    }
    private fun forget(id: Long) {
        saved = saved.copy(pending = saved.pending - id, requested = saved.requested - id, references = saved.references - id,
            retryMetadataAt = saved.retryMetadataAt - id, metadataFailures = saved.metadataFailures - id,
            freshUntil = saved.freshUntil - id)
    }
    private fun ready(kind: String) = now() >= (saved.cooldowns[kind] ?: 0)
    private fun now() = clock.now().epochSeconds
    private suspend fun persist() {
        val text = json.encodeToString(saved)
        if (text != persisted) { storage.write(checkpointName(account), text); persisted = text }
    }

    companion object {
        private const val BackgroundMetadata = "background metadata"
        fun checkpointName(account: Long) = "steam-backfill-$account.json"
        suspend fun clear(storage: CacheStorage, account: Long) {
            val json = Json { ignoreUnknownKeys = true }
            val old = storage.read(checkpointName(account))?.let { json.decodeFromString<Checkpoint>(it) } ?: Checkpoint()
            storage.write(checkpointName(account), json.encodeToString(Checkpoint(cooldowns = old.cooldowns, requestFailures = old.requestFailures)))
        }
    }
}
