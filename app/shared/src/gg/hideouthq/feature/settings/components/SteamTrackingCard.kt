package gg.hideouthq.feature.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.deadlockstats.SteamTrackingPhase
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.settings.SettingsActions
import gg.hideouthq.feature.settings.SettingsUiState
import gg.hideouthq.feature.stats.SteamInstallationPicker
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SteamTrackingCard(state: SettingsUiState, actions: SettingsActions) {
    val tracking = state.steamTracking
    val enabled = state.stats?.steamTrackingEnabled == true
    LaunchedEffect(Unit) { actions.inspectSteamTracking() }
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.steam_tracking_title), style = HideoutTheme.typography.bodyStrong)
        Text(stringResource(Res.string.steam_tracking_developer), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(Res.string.steam_tracking_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SteamInstallationPicker(tracking.installation, select = actions::selectSteamInstallation)
        val phase = tracking.status.phase
        Text(when {
            tracking.installation.reason != null -> tracking.installation.reason
            !tracking.installation.supported -> stringResource(Res.string.steam_tracking_unavailable)
            !enabled || !state.stats.enabled -> stringResource(Res.string.steam_tracking_off)
            state.stats.accountId == null -> stringResource(Res.string.stats_no_account_title)
            phase == SteamTrackingPhase.AccountMismatch -> stringResource(Res.string.steam_tracking_account_mismatch)
            phase == SteamTrackingPhase.WaitingForGc -> stringResource(Res.string.steam_tracking_wait_gc)
            phase == SteamTrackingPhase.WaitingForGame -> stringResource(Res.string.steam_tracking_wait_game)
            phase == SteamTrackingPhase.Disabled || phase == SteamTrackingPhase.WaitingForSteam -> stringResource(Res.string.steam_tracking_wait_steam)
            phase == SteamTrackingPhase.CoolingDown -> stringResource(Res.string.steam_tracking_cooldown)
            phase == SteamTrackingPhase.Failed -> tracking.status.message ?: stringResource(Res.string.steam_tracking_unavailable)
            else -> stringResource(Res.string.steam_tracking_progress, tracking.status.summaries, tracking.status.pendingMetadata)
        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HideoutButton(stringResource(if (enabled || tracking.installation.installed) Res.string.steam_tracking_disable else Res.string.steam_tracking_enable),
                onClick = if (enabled || tracking.installation.installed) actions::disableSteamTracking else actions::installSteamTracking,
                enabled = enabled || tracking.installation.installed || tracking.installation.supported && state.stats?.enabled == true,
                size = ButtonSize.Small)
            HideoutButton(stringResource(Res.string.steam_tracking_check), actions::inspectSteamTracking,
                variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
        if (enabled && state.stats.accountId != null) HideoutButton(stringResource(Res.string.steam_tracking_backfill), actions::backfillSteamHistory,
            enabled = state.stats.enabled && tracking.installation.supported, variant = ButtonVariant.Text, size = ButtonSize.Small)
    }
}
