package gg.hideouthq.core.data.deadlockstats

import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.network.RemoteException
import gg.hideouthq.core.network.decodeLenient
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.URLBuilder
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import java.io.IOException

internal class DeadlockStatsApi(
    client: HttpClient,
    private val baseUrl: String = DefaultBaseUrl,
    private val gate: StatsRequestGate = StatsRequestGate(),
) : AutoCloseable {
    private val http = client.forStats()

    suspend fun matchHistory(accountId: SteamAccountId): List<MatchHistoryEntry> =
        list(MatchHistoryEntry.serializer(), get("/v1/players/$accountId/match-history"))

    suspend fun steamProfiles(accountIds: List<SteamAccountId>): List<PlayerProfile> {
        if (accountIds.isEmpty()) return emptyList()
        return list(PlayerProfile.serializer(), get("/v1/players/steam", mapOf("account_ids" to accountIds.distinct().joinToString(","))))
    }

    suspend fun searchPlayersByName(query: String): List<PlayerProfile> =
        list(PlayerProfile.serializer(), get("/v1/players/steam-search", mapOf("search_query" to query, "limit" to "20")))

    suspend fun rank(accountId: SteamAccountId): RankStatus = decodeLenient(RankResponseDto.serializer(), get("/v1/players/$accountId/rank")).toDomain()

    suspend fun mateStats(accountId: SteamAccountId, window: DateWindow = DateWindow(), sameParty: Boolean? = null): List<PlayedWithStats> =
        list(PlayedWithStatsDto.serializer(), get("/v1/players/$accountId/mate-stats", dates(window) + listOfNotNull(sameParty?.let { "same_party" to it.toString() }).toMap())).map { it.toDomain() }

    suspend fun enemyStats(accountId: SteamAccountId): List<PlayedWithStats> =
        list(PlayedWithStatsDto.serializer(), get("/v1/players/$accountId/enemy-stats")).map { it.toDomain() }

    suspend fun items(): List<ItemAsset> = list(ItemAsset.serializer(), get("/v1/assets/items"))

    suspend fun ranks(): List<RankAsset> = decodeLenient(JsonArray.serializer(), get("/v1/assets/ranks")).map { element ->
        val row = element.jsonObject
        val images = row["images"]?.jsonObject.orEmpty()
        fun image(key: String) = (images[key] as? JsonPrimitive)?.contentOrNull
        RankAsset(
            row.getValue("tier").jsonPrimitive.int, row.getValue("name").jsonPrimitive.content,
            row.getValue("color").jsonPrimitive.content, image("large_webp") ?: image("large"),
            (1..6).mapNotNull { n -> (image("subrank${n}_webp") ?: image("subrank$n") ?: image("large_subrank${n}_webp"))?.let { n to it } }.toMap(),
        )
    }

    suspend fun matchMetadata(matchId: Long): MatchDetail =
        decodeLenient(MatchMetadataJsonDto.serializer(), get("/v1/matches/$matchId/metadata", mapOf("disable_steam" to "true"))).toMatchDetail(matchId)

    /** These benchmarks select average lobby badge, not each participant's individual rank. */
    suspend fun heroBenchmarks(mode: MatchModeFilter, window: DateWindow, minBadge: Int, maxBadge: Int): List<HeroBenchmark> =
        list(HeroBenchmark.serializer(), get("/v1/analytics/hero-stats", analytics(mode, window) + mapOf("min_average_badge" to "$minBadge", "max_average_badge" to "$maxBadge")))

    /** Means of per-match metrics; with [accountId] only that player's matches, otherwise every player in the lobbies. */
    suspend fun playerMetrics(
        mode: MatchModeFilter, window: DateWindow, minBadge: Int? = null, maxBadge: Int? = null, heroId: Int? = null, accountId: SteamAccountId? = null,
    ): Map<String, MetricDistribution> {
        val parameters = analytics(mode, window) + listOfNotNull(
            minBadge?.let { "min_average_badge" to "$it" }, maxBadge?.let { "max_average_badge" to "$it" },
            heroId?.let { "hero_ids" to "$it" }, accountId?.let { "account_ids" to "$it" },
        )
        return decodeLenient(MetricsSerializer, get("/v1/analytics/player-stats/metrics", parameters))
    }

    suspend fun heroCounters(accountId: SteamAccountId, mode: MatchModeFilter, window: DateWindow): List<HeroCounter> =
        list(HeroCounter.serializer(), get("/v1/analytics/hero-counter-stats", analytics(mode, window) + mapOf("account_id" to "$accountId", "min_matches" to "1", "same_lane_filter" to "false")))

    suspend fun itemStats(accountId: SteamAccountId, heroId: Int, mode: MatchModeFilter, window: DateWindow): List<ItemUsageStat> =
        list(ItemUsageStat.serializer(), get("/v1/analytics/item-stats", analytics(mode, window) + mapOf("account_id" to "$accountId", "hero_id" to "$heroId")))

    /** One batch request for several accounts' hero totals; rows carry their account. */
    suspend fun heroCountsFor(accountIds: List<SteamAccountId>, mode: MatchModeFilter, window: DateWindow): List<AccountHeroCount> {
        if (accountIds.isEmpty()) return emptyList()
        return list(AccountHeroCount.serializer(), get("/v1/players/hero-stats", scope(mode, window) + ("account_ids" to accountIds.distinct().joinToString(","))))
    }

    suspend fun rankedSeasons(): List<RankedSeason> = list(RankedSeason.serializer(), get("/v1/assets/ranked-seasons"))

    suspend fun rankDistribution(window: DateWindow): List<RankDistributionEntry> =
        list(RankDistributionEntry.serializer(), get("/v1/players/rank/distribution", dates(window)))

    /** Final per-match numbers for [accountId] in [matchIds], from one bulk metadata request. Matches the API lacks are absent. */
    suspend fun matchSummaries(accountId: SteamAccountId, matchIds: List<Long>): List<MatchPlayerSummary> {
        if (matchIds.isEmpty()) return emptyList()
        val parameters = mapOf(
            "match_ids" to matchIds.distinct().joinToString(","), "account_ids" to "$accountId", "only_filtered_players" to "true",
            "include_player_info" to "true", "include_player_final_stats" to "true", "limit" to "${matchIds.size}",
        )
        return list(BulkMatchDto.serializer(), get("/v1/matches/metadata", parameters)).mapNotNull { it.summaryFor(accountId.value) }
    }

    /**
     * Gives deadlock-api [salts], at most [MaxSaltsBatch]. Throttling, server errors and lost connections are tried again
     * a few times; any other refusal is final, since the same body would get the same answer. Adapted from
     * deadlock-api-ingest/src/utils.rs (MIT; see bundled notice), without its `username`.
     */
    suspend fun submitSalts(salts: List<MatchSalts>): SaltsUpload {
        require(salts.size <= MaxSaltsBatch)
        val body = TextContent(SaltsJson.encodeToString(ListSerializer(MatchSalts.serializer()), salts), ContentType.Application.Json)
        repeat(MaxSaltAttempts) { attempt ->
            val response = try {
                gate.request("salts", 1000L) {
                    http.statsRequest("$baseUrl/v1/matches/salts", MaxSaltsReplyBytes) { method = HttpMethod.Post; setBody(body) }
                }
            } catch (e: StatsHttpFailure) { return SaltsUpload.Unavailable }
            catch (e: IOException) { null }
            val status = response?.status?.value
            if (response != null && response.status.isSuccess()) return SaltsUpload.Accepted
            if (status != null && status != 429 && status < 500) return SaltsUpload.Rejected
            val wait = retryAfterMillis(response?.headers?.get(HttpHeaders.RetryAfter), gate.nowMillis()) ?: (1000L shl attempt)
            if (wait > StatsRequestGate.LongCooldownMillis) return SaltsUpload.Unavailable
            gate.coolDown("salts", wait)
        }
        return SaltsUpload.Unavailable
    }

    private fun <T> list(serializer: KSerializer<T>, text: String) = decodeLenient(ListSerializer(serializer), text)

    private suspend fun get(path: String, parameters: Map<String, String> = emptyMap()): String {
        // Per address, analytics allow 200 requests a minute between them, bulk metadata 30 and the distribution 5.
        val (group, spacing) = when {
            "/analytics/" in path -> "analytics" to 350L
            path.endsWith("/rank/distribution") -> "distribution" to 12_000L
            path.endsWith("/rank") -> "rank" to 3000L
            path == "/v1/matches/metadata" -> "summaries" to 2000L
            path.endsWith("/metadata") -> "metadata" to 1000L
            path.endsWith("/match-history") -> "history" to 100L
            else -> "public" to 100L
        }
        val url = URLBuilder("$baseUrl$path").apply { parameters.forEach { (key, value) -> this.parameters.append(key, value) } }.buildString()
        try {
            var lastStatus = 0
            repeat(4) { attempt ->
                val response = gate.request(group, spacing) {
                    http.statsRequest(url, MaxJsonBytes)
                }
                lastStatus = response.status.value
                if (response.status.isSuccess()) return response.body.decodeToString()
                if (lastStatus != 429 && lastStatus != 503) throw StatsHttpFailure(lastStatus)
                val wait = retryAfterMillis(response.headers[HttpHeaders.RetryAfter], gate.nowMillis()) ?: (1000L shl attempt)
                gate.coolDown(group, wait)
                if (wait > StatsRequestGate.LongCooldownMillis || attempt == 3) throw StatsHttpFailure(lastStatus)
            }
            throw StatsHttpFailure(lastStatus)
        } catch (e: StatsHttpFailure) { throw RemoteException(if (e.status == 404) DataError.NotFound else DataError.Unavailable, "Deadlock API ${e.message}; retry later", e) }
        catch (e: IOException) { throw RemoteException(DataError.Offline, "Deadlock API request failed", e) }
    }

    private fun analytics(mode: MatchModeFilter, window: DateWindow) = scope(mode, window, window.fromInclusive ?: 0)
    private fun scope(mode: MatchModeFilter, window: DateWindow, from: Int? = window.fromInclusive) = dates(window, from) + mapOf(
        "game_mode" to "normal", "match_mode" to when (mode) { MatchModeFilter.All -> "ranked,unranked"; MatchModeFilter.Ranked -> "ranked"; MatchModeFilter.Normal -> "unranked" },
    )
    // Upstream upper bounds are inclusive; our query contract uses half-open windows.
    private fun dates(window: DateWindow, from: Int? = window.fromInclusive): Map<String, String> = buildMap {
        from?.let { put("min_unix_timestamp", "$it") }
        window.toExclusive?.let { put("max_unix_timestamp", "${it - 1}") }
    }

    override fun close() = http.close()
    companion object {
        const val DefaultBaseUrl = "https://api.deadlock-api.com"
        const val MaxHistoryEntries = 50_000
        const val MaxJsonBytes = 32 * 1024 * 1024
        /** Match ids in one bulk metadata request; the API takes up to 1000. */
        const val MaxSummaryBatch = 300
        /** Salts in one upload; the API takes up to 1000. */
        const val MaxSaltsBatch = 1000
        private const val MaxSaltAttempts = 4
        private const val MaxSaltsReplyBytes = 64 * 1024
        private val SaltsJson = Json { encodeDefaults = true }
        private val MetricsSerializer = MapSerializer(String.serializer(), MetricDistribution.serializer())
    }
}

@Serializable
data class HeroBenchmark(
    @SerialName("hero_id") val heroId: Int,
    val matches: Long,
    val wins: Long,
    val losses: Long,
    @SerialName("total_kills") val kills: Long? = null,
    @SerialName("total_deaths") val deaths: Long? = null,
    @SerialName("total_assists") val assists: Long? = null,
    @SerialName("total_net_worth") val souls: Long? = null,
    @SerialName("total_player_damage") val heroDamage: Long? = null,
    @SerialName("total_boss_damage") val objectiveDamage: Long? = null,
    @SerialName("total_shots_hit") val shotsHit: Long? = null,
    @SerialName("total_shots_missed") val shotsMissed: Long? = null,
)
