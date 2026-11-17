package gg.hideouthq.tools

import gg.hideouthq.core.data.deadlockstats.*
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.library.steamInstalls
import gg.hideouthq.library.SteamLoginIdentities
import gg.hideouthq.library.SteamReplayCacheWatcher
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

fun main(args: Array<String>) = runBlocking {
    require(args.firstOrNull() in setOf("api", "cache")) {
        "Usage: DeadlockStatsLiveCheckKt api|cache [seconds=60] [account-id-or-steamid64] [snapshot-directory]"
    }
    val mode = args[0]
    val seconds = args.getOrNull(1)?.toLong() ?: 60L
    val installs = steamInstalls()
    val identities = SteamLoginIdentities.at(installs)
    val rawId = args.getOrNull(2)?.toLong() ?: identities.singleOrNull { it.mostRecent }?.steamId64
        ?: identities.singleOrNull()?.steamId64 ?: error("Choose an account ID explicitly; no unique recent local account")
    val account = SteamAccountId.ofAccountId(rawId) ?: SteamAccountId.ofSteamId64(rawId) ?: error("Invalid account ID")
    val directory = File("build/deadlock-live/$mode").absoluteFile.apply { mkdirs() }
    val log = File(directory, "events.log")
    fun event(message: String) = synchronized(log) {
        val line = "${Instant.now()} $message"
        println(line)
        log.appendText("$line\n")
    }
    event("START mode=$mode account=${account.value} uploads=OFF storage=$directory")
    fun label(url: Url): String = ReplayCacheUrl.parseMetadataUrl(url.toString())?.let { "Valve match=${it.matchId}" }
        ?: (url.host + url.encodedPath)
    val trace = createClientPlugin("StatsLiveTrace") {
        onRequest { request, _ ->
            check(request.method == HttpMethod.Get) { "Live checks never upload" }
            event("HTTP_REQUEST ${label(request.url.build())}")
        }
        onResponse { response -> event("HTTP_HEADERS ${label(response.call.request.url)} status=${response.status.value}") }
    }
    val engine = OkHttp.create()
    val baseClient = hideoutHttpClient(engine, "Hideout/1.0 DeadlockStatsLiveCheck")
    val client = baseClient.config { install(trace) }
    val storage = FileCacheStorage(args.getOrNull(3)?.let(::File) ?: directory.resolve("snapshots"))
    event("STEAM_ROOTS candidates=${installs.size} accessible_caches=${installs.map { File(it.root, "appcache/httpcache").canonicalPath }.distinct().count { File(it).isDirectory }}")
    val realWatcher = SteamReplayCacheWatcher({ installs })
    val observed = ConcurrentHashMap.newKeySet<Long>()
    lateinit var repo: DeadlockStatsRepository
    val watcher = object : ReplayCacheWatcherPort {
        override suspend fun start(onCandidate: suspend (header: ByteArray, writtenAt: Long) -> Unit) = realWatcher.start { bytes, writtenAt ->
            val ref = ReplayCacheUrl.findMetadataUrl(bytes) ?: return@start
            if (observed.add(ref.matchId)) event("DISCOVERED match=${ref.matchId}")
            val before = repo.state.value.matchDetails[ref.matchId]?.fetchedAt
            val elapsed = measureTimeMillis { onCandidate(bytes, writtenAt) }
            val detail = repo.state.value.matchDetails[ref.matchId]
            if (detail != null && detail.fetchedAt != before) {
                event("INGESTED match=${ref.matchId} elapsed_ms=$elapsed players=${detail.players.size} snapshots=${detail.players.sumOf { it.statSnapshots.size }} selected_player=${detail.players.any { it.accountId == account.value }}")
            }
        }
        override fun close() = realWatcher.close()
    }
    repo = DeadlockStatsRepository(client, storage, this, watcher)
    val failures = mutableListOf<String>()
    suspend fun checkLive(name: String, block: suspend () -> String) {
        val start = System.nanoTime()
        try { event("PASS $name ${block()} elapsed_ms=${(System.nanoTime() - start) / 1_000_000}") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures += name; event("FAIL $name ${e.javaClass.simpleName}: ${e.message}") }
    }
    try {
        repo.selectAccount(account)
        if (mode == "api") {
            val existingIds = repo.state.value.matchHistory.map { it.matchId }.toSet()
            checkLive("core_refresh") {
                check(repo.refresh()) { "No match history arrived" }
                val s = repo.state.value
                check(s.matchHistory.map { it.matchId }.toSet().containsAll(existingIds)) { "Refresh lost cached history" }
                "profile=${s.profile != null} history=${s.matchHistory.size} badge=${s.rank?.badge} retained_existing=${existingIds.size}"
            }
            checkLive("references") {
                repo.refreshAssets()
                val s = repo.state.value
                check(s.itemAssets.isNotEmpty() && s.rankAssets.isNotEmpty()) { "Missing item/rank assets: items=${s.itemAssets.size} ranks=${s.rankAssets.size}" }
                "items=${s.itemAssets.size} ranks=${s.rankAssets.size} unsigned_item_ids=${s.itemAssets.count { it.id > Int.MAX_VALUE }}"
            }
            checkLive("numeric_search") { "profiles=${repo.searchPlayers(account.toString()).size}" }
            repo.state.value.profile?.personaName?.takeIf { it.isNotBlank() }?.let { name ->
                checkLive("name_search") { "profiles=${repo.searchPlayers(name).size}" }
            }
            checkLive("social") {
                repo.refreshSocial()
                val s = repo.state.value
                "mates=${s.mateStats.size} enemies=${s.enemyStats.size}"
            }
            val now = Instant.now().epochSecond.toInt()
            val window = DateWindow(now - 30 * 86400, now)
            checkLive("party_filter") { "mates=${repo.partyTeammates(window).size}" }
            checkLive("benchmarks") { "heroes=${repo.heroBenchmarks(window, MatchModeFilter.All, 0, 116).size}" }
            val matchId = repo.state.value.matchHistory.firstOrNull()?.matchId ?: 116576271L
            checkLive("match_metadata") {
                repo.fetchMatchDetail(matchId).getOrThrow()
                val detail = repo.state.value.matchDetails[matchId] ?: error("No metadata")
                "match=$matchId players=${detail.players.size} snapshots=${detail.players.sumOf { it.statSnapshots.size }} items=${detail.players.sumOf { it.items.size }}"
            }
            repo.state.value.matchDetails[matchId]?.let { detail ->
                checkLive("repeat_metadata_merge") {
                    repo.fetchMatchDetail(matchId).getOrThrow()
                    val refreshed = repo.state.value.matchDetails.getValue(matchId)
                    check(refreshed.players.sumOf { it.items.size } == detail.players.sumOf { it.items.size }) { "Refresh lost item timeline events" }
                    "match=$matchId item_events=${refreshed.players.sumOf { it.items.size }}"
                }
                checkLive("scoreboard_profiles") {
                    "profiles=${repo.profiles(detail.players.mapNotNull { SteamAccountId.ofAccountId(it.accountId) }).size}"
                }
            }
            checkLive("queries") {
                val s = repo.state.value
                val page = s.matches()
                check(s.matchHistory.map { it.matchId }.distinct().size == s.matchHistory.size)
                "tracked=${s.profileSummary()?.matchesTracked} page_rows=${page.rows.size} period=${s.periodSummary(window).totals.matches} rank_points=${s.rankHistory().size} hero_groups=${s.heroPeriodAggregates().size} mates=${s.mates().size}"
            }
        } else {
            val baselineIds = repo.state.value.matchHistory.map { it.matchId }.toSet()
            val collector = launch {
                val published = mutableSetOf<Long>()
                repo.state.collect { state ->
                    state.matchHistory.filter { it.matchId !in baselineIds && published.add(it.matchId) }.forEach {
                        event("PUBLISHED match=${it.matchId} start_time=${it.startTime} duration_s=${it.matchDurationS} detail=${state.matchDetails[it.matchId] != null}")
                    }
                }
            }
            repo.configure(watcherEnabled = true)
            event("WATCH_READY seconds=$seconds baseline_history=${baselineIds.size}; keep Deadlock running")
            val watchStarted = System.currentTimeMillis()
            withTimeoutOrNull(seconds * 1000) {
                while (File(directory, "stop-watching").lastModified() < watchStarted) delay(250)
            }
            repo.configure(watcherEnabled = false)
            collector.cancelAndJoin()
            event("CACHE_RESULT discovered=${observed.size} details=${repo.state.value.matchDetails.size} history=${repo.state.value.matchHistory.size}")
        }
        checkLive("persistence") {
            val before = repo.state.value
            check(before.lastPersistenceError == null) { "${before.lastPersistenceError}" }
            val reopened = DeadlockStatsRepository(client, storage, this)
            try {
                reopened.selectAccount(account)
                check(reopened.state.value.matchHistory == before.matchHistory) { "History changed after reopening" }
                val restored = before.matchDetails.keys.firstOrNull()?.let { reopened.fetchMatchDetail(it, prioritizeSteam = false).isSuccess }
                "history=${before.matchHistory.size} archived_detail_restored=$restored"
            } finally { reopened.close() }
        }
        event("DONE mode=$mode failed_checks=${failures.joinToString(",").ifEmpty { "none" }}")
    } finally { repo.close(); client.close(); baseClient.close(); engine.close() }
    check(failures.isEmpty()) { "Live checks failed: ${failures.joinToString()}" }
}
