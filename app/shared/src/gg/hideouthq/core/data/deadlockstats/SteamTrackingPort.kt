package gg.hideouthq.core.data.deadlockstats

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class SteamTrackingPhase { Disabled, Unsupported, WaitingForSteam, WaitingForGame, WaitingForGc, AccountMismatch, Connected, Backfilling, CoolingDown, Failed }

data class SteamTrackingStatus(
    val phase: SteamTrackingPhase = SteamTrackingPhase.Disabled,
    val message: String? = null,
    val summaries: Int = 0,
    val pendingMetadata: Int = 0,
)

enum class SteamGcReadiness { Unknown, Ready, Waiting }

class SteamGcInterrupted(message: String) : Exception(message)

data class SteamGcSession(val accountId: Long, val generation: Long)
data class SteamHistoryPage(val matches: List<MatchHistoryEntry>, val cursor: Long)

sealed interface SteamTrackingEvent {
    data class AccountUpdated(val session: SteamGcSession) : SteamTrackingEvent
    data class Gap(val session: SteamGcSession, val dropped: Long) : SteamTrackingEvent
}

/** Only the active Deadlock session is accessible; implementations must cancel work when it changes. */
interface SteamTrackingPort : AutoCloseable {
    val status: StateFlow<SteamTrackingStatus>
    val session: StateFlow<SteamGcSession?>
    val gcState: StateFlow<SteamGcReadiness>
    val events: Flow<SteamTrackingEvent>
    suspend fun start()
    suspend fun history(session: SteamGcSession, cursor: Long = 0): SteamHistoryPage
    suspend fun metadata(session: SteamGcSession, matchId: Long): ReplayMetadataRef
}

class SteamGcRateLimited(val request: String) : Exception("Steam limited $request requests")

data class SteamInstallationChoice(val id: String, val label: String, val root: String, val ownsGame: Boolean)

data class SteamTrackingInstallation(val supported: Boolean = false, val installed: Boolean = false,
    val reason: String? = null, val choices: List<SteamInstallationChoice> = emptyList(), val selectedId: String? = null)

/** Desktop-only mutations remain separate from the collection port. No restart/termination is implicit. */
interface SteamTrackingInstaller {
    suspend fun inspect(): SteamTrackingInstallation
    suspend fun install(): SteamTrackingInstallation
    suspend fun uninstall(): SteamTrackingInstallation
    suspend fun select(id: String): SteamTrackingInstallation
}
