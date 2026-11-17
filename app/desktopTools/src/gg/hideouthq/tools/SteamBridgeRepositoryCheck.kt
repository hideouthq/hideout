package gg.hideouthq.tools

import gg.hideouthq.core.data.deadlockstats.*
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.deadlockstats.SteamBridgeDiagnostics
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

fun main(args: Array<String>) = runBlocking {
    require(args.size in 5..6) { "Usage: SteamBridgeRepositoryCheck LIBRARY ENDPOINT_DIRECTORY ACCOUNT_ID OUTPUT_DIRECTORY STEAM_ROOT [WATCH_SECONDS|latest]" }
    val library = File(args[0]).canonicalFile
    val endpoint = File(args[1]).canonicalFile
    val account = requireNotNull(SteamAccountId.ofAccountId(args[2].toLong()))
    val output = File(args[3]).canonicalFile
    Files.createDirectories(output.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    val http = hideoutHttpClient(OkHttp.create(), "Hideout/1.0 SteamBridgeRepositoryCheck")
    val port = SteamBridgeDiagnostics.open(this, endpoint, library, File(args[4]))
    val events = launch { port.events.collect { println("GC_EVENT ${it::class.simpleName} ${java.time.Instant.now()}") } }
    val sessions = launch {
        var previous: SteamGcSession? = null
        var interrupted = false
        port.session.collect { current ->
            if (current == null && previous != null) {
                interrupted = true
                println("SESSION invalidated ${java.time.Instant.now()}")
            } else if (current != null) {
                if (interrupted) {
                    check(current.generation != previous!!.generation)
                    println("VERIFIED session returned with a new epoch ${java.time.Instant.now()}")
                } else println("SESSION active ${java.time.Instant.now()}")
                previous = current; interrupted = false
            }
        }
    }
    val readiness = launch { port.gcState.collect { println("GC_STATE $it ${java.time.Instant.now()}") } }
    val startedAt = kotlin.time.Clock.System.now().epochSeconds
    val repository = DeadlockStatsRepository(http, FileCacheStorage(output), this,
        steamTrackingFactory = { port })
    val phases = launch { repository.state.map { it.steamTracking.phase }.distinctUntilChanged().collect {
        println("COLLECTOR_STATE $it ${java.time.Instant.now()}")
    } }
    try {
        repository.selectAccount(account)
        repository.configureSteamTracking(true)
        println("Repository collector started; waiting for real Steam data")
        val state = withTimeout(600_000) {
            repository.state.first { (it.fetchedAt["history"] ?: 0) >= startedAt && it.matchHistory.isNotEmpty() }
        }
        println("VERIFIED production collector baseline: history=${state.matchHistory.size}")
        if (args.getOrNull(5) == "latest") {
            val latest = state.matchHistory.maxBy { it.matchId }.matchId
            // Respect persisted GC rate-limit deadlines, which can be up to one hour away.
            val complete = withTimeout(3_900_000) {
                repository.state.first { it.matchDetails[latest]?.isComplete == true }
            }
            println("VERIFIED latest match archived by production collector: players=${complete.matchDetails.getValue(latest).players.size}")
            return@runBlocking
        }
        val seconds = args.getOrNull(5)?.toLong()?.coerceIn(1, 7200) ?: 120L
        println("Watching automatic updates for $seconds seconds; leave the game open")
        val original = state.matchHistory.map { it.matchId }.toSet()
        val update = withTimeoutOrNull(seconds * 1000) { repository.state.first { s -> s.matchHistory.any { it.matchId !in original } } }
        if (update == null) println("No new completed match observed during this window") else {
            val match = update.matchHistory.first { it.matchId !in original }
            println("VERIFIED new match arrived through the production collector at ${java.time.Instant.now()}")
            val detailed = withTimeout(3_900_000) { repository.state.first { it.matchDetails[match.matchId]?.isComplete == true } }
            println("VERIFIED new match automatically archived: players=${detailed.matchDetails.getValue(match.matchId).players.size}")
        }
    } finally { repository.close(); events.cancel(); sessions.cancel(); readiness.cancel(); phases.cancel(); http.close() }
}
