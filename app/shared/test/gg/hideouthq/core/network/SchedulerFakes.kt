package gg.hideouthq.core.network

import gg.hideouthq.core.data.deadlockstats.StatsRequestGate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.time.Clock
import kotlin.time.Instant

internal fun schedulerEngine(scheduler: TestCoroutineScheduler, handler: MockRequestHandler): MockEngine =
    MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(scheduler); addHandler(handler) })

internal fun schedulerClock(scheduler: TestCoroutineScheduler, startMillis: Long = 0): Clock = object : Clock {
    override fun now() = Instant.fromEpochMilliseconds(startMillis + scheduler.currentTime)
}

internal fun schedulerGate(scheduler: TestCoroutineScheduler): StatsRequestGate = StatsRequestGate(schedulerClock(scheduler))
