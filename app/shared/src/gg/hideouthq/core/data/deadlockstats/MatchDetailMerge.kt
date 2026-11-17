package gg.hideouthq.core.data.deadlockstats

import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

internal fun mergeMatchDetail(previous: MatchDetail?, incoming: MatchDetail): MatchDetail {
    if (previous == null) return incoming
    require(previous.matchId == incoming.matchId)
    val merged = coalesce(previous, incoming)
    return merged.copy(
        players = mergeBy(previous.players, incoming.players, { it.accountId }) { old, new ->
            coalesce(old, new).copy(
                items = mergeByOccurrence(old.items, new.items, { it.gameTimeS to it.itemId }, ::coalesce).sortedBy { it.gameTimeS },
                statSnapshots = mergeBy(old.statSnapshots, new.statSnapshots, { it.timeStampS }, ::coalesce).sortedBy { it.timeStampS },
                deathDetails = if (old.deathDetails == null && new.deathDetails == null) null
                    else mergeByOccurrence(old.deathDetails.orEmpty(), new.deathDetails.orEmpty(), { it.gameTimeS }, ::coalesce).sortedBy { it.gameTimeS },
            )
        },
        objectives = mergeBy(previous.objectives, incoming.objectives, { Triple(it.team, it.teamObjectiveId ?: it.legacyObjectiveId, it.destroyedTimeS) }, ::coalesce),
        midBoss = mergeBy(previous.midBoss, incoming.midBoss, { it.destroyedTimeS }, ::coalesce),
        fetchedAt = maxOf(previous.fetchedAt, incoming.fetchedAt),
    )
}

internal fun mergeHistory(previous: List<MatchHistoryEntry>, incoming: List<MatchHistoryEntry>): List<MatchHistoryEntry> =
    mergeBy(previous, incoming, { it.matchId }, ::coalesce)
        .sortedWith(compareByDescending<MatchHistoryEntry> { it.startTime }.thenByDescending { it.matchId })
        .take(DeadlockStatsApi.MaxHistoryEntries)

internal fun MatchDetail.historyFor(accountId: Long): MatchHistoryEntry? {
    val player = players.firstOrNull { it.accountId == accountId } ?: return null
    val outcome = player.playerMatchOutcome ?: if ((matchOutcome == null || matchOutcome == 0) && player.team in 0..1 && winningTeam in 0..1) {
        if (player.team == winningTeam) 1 else 2
    } else null
    return MatchHistoryEntry(
        matchId, player.heroId, player.level, startTime, gameMode, matchMode, player.team,
        player.kills, player.deaths, player.assists, player.denies, player.netWorth, player.lastHits,
        durationS, winningTeam.takeIf { matchOutcome == null || matchOutcome == 0 }, outcome, abandonedTimeS = player.abandonedTimeS,
    )
}

private fun <T> mergeByOccurrence(old: List<T>, new: List<T>, key: (T) -> Any?, merge: (T, T) -> T): List<T> {
    fun indexed(events: List<T>): List<Pair<Pair<Any?, Int>, T>> {
        val counts = mutableMapOf<Any?, Int>()
        return events.map { event -> key(event).let { (it to counts.merge(it, 1, Int::plus)!!) to event } }
    }
    return mergeBy(indexed(old), indexed(new), { it.first }) { a, b -> a.first to merge(a.second, b.second) }.map { it.second }
}

private fun <T, K> mergeBy(old: List<T>, new: List<T>, key: (T) -> K, merge: (T, T) -> T): List<T> {
    val result = old.associateByTo(LinkedHashMap(), key)
    new.forEach { value -> result[key(value)] = result[key(value)]?.let { if (it == value) it else merge(it, value) } ?: value }
    return result.values.toList()
}

private val MergeJson = Json { encodeDefaults = false; explicitNulls = false }
private inline fun <reified T> coalesce(old: T, new: T): T = MergeJson.decodeFromJsonElement(
    serializer<T>(), mergeKnown(MergeJson.encodeToJsonElement(serializer<T>(), old), MergeJson.encodeToJsonElement(serializer<T>(), new)),
)

private fun mergeKnown(old: JsonElement, new: JsonElement): JsonElement = when {
    new is JsonNull -> old
    old is JsonObject && new is JsonObject -> JsonObject(old.toMutableMap().apply {
        new.forEach { (key, value) -> this[key] = get(key)?.let { mergeKnown(it, value) } ?: value }
    })
    new is JsonArray && new.isEmpty() -> old
    else -> new
}
