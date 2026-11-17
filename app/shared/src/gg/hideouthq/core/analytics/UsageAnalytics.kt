package gg.hideouthq.core.analytics

import gg.hideouthq.core.data.CacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

enum class AnalyticsChoice { NotDisclosed, Enabled, Disabled }
enum class UsageOperation(val wire: String) {
    Setup("setup"), ModInstall("mod_install"), ManagerImport("manager_import"),
    PreviewLoad("preview_load"), CustomGameHost("custom_game_host"), CustomGameJoin("custom_game_join")
}
enum class UsageOutcome(val wire: String) { Completed("completed"), Failed("failed"), Cancelled("cancelled"), Skipped("skipped") }
enum class UsageStage(val wire: String) { Setup("setup"), Download("download"), Install("install"), Import("import"), Preview("preview"), Manifest("manifest"), Prepare("prepare"), Connect("connect"), Unknown("unknown") }
enum class UsageFailure(val wire: String) { Network("network"), InvalidContent("invalid_content"), Storage("storage"), Unavailable("unavailable"), Timeout("timeout"), Other("other") }
enum class UsageSource(val wire: String) { DeadlockModManager("deadlock_mod_manager"), Grimoire("grimoire"), Bundle("bundle") }
enum class DurationBucket(val wire: String) { UnderSecond("under_1s"), UnderTenSeconds("1_10s"), UnderMinute("10_60s"), UnderFiveMinutes("1_5m"), Longer("over_5m") }

sealed interface UsageEvent {
    data object AppActive : UsageEvent
    data class Started(val operation: UsageOperation, val source: UsageSource? = null) : UsageEvent
    data class Result(val operation: UsageOperation, val outcome: UsageOutcome, val duration: DurationBucket, val stage: UsageStage, val source: UsageSource? = null, val failure: UsageFailure? = null) : UsageEvent
}

fun interface UsageTransport {
    suspend fun send(installation: String, event: UsageEvent)
}

class UsageAttempt internal constructor(private val complete: (UsageOutcome, UsageStage, UsageFailure?) -> Unit) {
    private val finished = MutableStateFlow(false)
    fun finish(outcome: UsageOutcome, stage: UsageStage = UsageStage.Unknown, failure: UsageFailure? = null) {
        if (finished.compareAndSet(false, true)) complete(outcome, stage, failure)
    }
}

class UsageAnalytics(
    private val storage: CacheStorage,
    private val transport: UsageTransport,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Uuid.random().toString() },
) {
    private val mutex = Mutex()
    private val published = MutableStateFlow<AnalyticsChoice?>(null)
    val choice = published.asStateFlow()
    private data class Control(val revision: Long = 0, val delivery: Delivery? = null)
    private val control = MutableStateFlow(Control())
    private var saved = Saved()
    private var loaded = false

    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        val revision = control.value.revision
        saved = try { storage.read(FileName)?.let { Json.decodeFromString<Saved>(it) } ?: Saved() }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { Saved() }
        loaded = true
        if (revision != control.value.revision) return@withLock
        published.value = saved.choice
        if (saved.choice == AnalyticsChoice.Enabled && saved.installation != null && revision == control.value.revision) startDelivery(saved.installation!!, revision)
        else if (saved.choice == AnalyticsChoice.Enabled) published.value = AnalyticsChoice.NotDisclosed
    }

    suspend fun choose(enabled: Boolean) {
        val revision = if (enabled) control.updateAndGet { it.copy(revision = it.revision + 1) }.revision else stopDelivery()
        mutex.withLock {
            if (revision != control.value.revision) return@withLock
            val next = saved.copy(choice = if (enabled) AnalyticsChoice.Enabled else AnalyticsChoice.Disabled,
                installation = saved.installation ?: if (enabled) newId() else null)
            try {
                storage.write(FileName, Json.encodeToString(next))
            } catch (e: Exception) {
                if (!enabled && revision == control.value.revision && published.value != AnalyticsChoice.NotDisclosed) published.value = AnalyticsChoice.Disabled
                throw e
            }
            saved = next
            loaded = true
            if (revision != control.value.revision) return@withLock
            published.value = next.choice
            if (enabled && revision == control.value.revision && control.value.delivery == null) startDelivery(checkNotNull(next.installation), revision)
        }
    }

    fun begin(operation: UsageOperation, source: UsageSource? = null): UsageAttempt {
        val delivery = control.value.delivery ?: return UsageAttempt { _, _, _ -> }
        val start = TimeSource.Monotonic.markNow()
        if (operation != UsageOperation.PreviewLoad && operation != UsageOperation.ManagerImport) delivery.queue.trySend(UsageEvent.Started(operation, source))
        return UsageAttempt { outcome, stage, failure ->
            if (control.value.delivery === delivery) {
                val seconds = start.elapsedNow().inWholeSeconds
                val duration = when { seconds < 1 -> DurationBucket.UnderSecond; seconds < 10 -> DurationBucket.UnderTenSeconds; seconds < 60 -> DurationBucket.UnderMinute; seconds < 300 -> DurationBucket.UnderFiveMinutes; else -> DurationBucket.Longer }
                delivery.queue.trySend(UsageEvent.Result(operation, outcome, duration, stage, source, failure))
            }
        }
    }

    suspend fun recordActiveDay() = mutex.withLock {
        val delivery = control.value.delivery ?: return@withLock
        val day = clock.now().epochSeconds / 86_400
        if (saved.activeDay == day) return@withLock
        val next = saved.copy(activeDay = day)
        try { storage.write(FileName, Json.encodeToString(next)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@withLock }
        saved = next
        if (control.value.delivery === delivery) delivery.queue.trySend(UsageEvent.AppActive)
    }

    fun close() { stopDelivery() }

    private fun startDelivery(installation: String, revision: Long) {
        val delivery = Delivery()
        delivery.worker = scope.launch(start = CoroutineStart.LAZY) {
            for (event in delivery.queue) {
                if (control.value.delivery !== delivery) break
                try { transport.send(installation, event) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
        }
        delivery.daily = scope.launch(start = CoroutineStart.LAZY) {
            while (control.value.delivery === delivery) { recordActiveDay(); delay(15.minutes) }
        }
        val before = control.value
        if (before.revision == revision && before.delivery == null && control.compareAndSet(before, before.copy(delivery = delivery))) {
            delivery.worker?.start()
            delivery.daily?.start()
        } else delivery.cancel()
    }

    private fun stopDelivery(): Long {
        val before = control.getAndUpdate { it.copy(revision = it.revision + 1, delivery = null) }
        before.delivery?.cancel()
        return before.revision + 1
    }

    private class Delivery {
        val queue = Channel<UsageEvent>(32)
        var worker: Job? = null
        var daily: Job? = null
        fun cancel() { queue.cancel(); worker?.cancel(); daily?.cancel() }
    }

    @Serializable
    private data class Saved(val choice: AnalyticsChoice = AnalyticsChoice.NotDisclosed, val installation: String? = null, val activeDay: Long? = null)
    private companion object { const val FileName = "usage-analytics.json" }
}
