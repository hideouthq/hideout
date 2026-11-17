package gg.hideouthq.feature.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import gg.hideouthq.core.data.deadlockstats.SteamTrackingInstallation
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton

@Composable
internal fun SteamInstallationPicker(installation: SteamTrackingInstallation, enabled: Boolean = true, select: (String) -> Unit) {
    if (installation.choices.isEmpty()) return
    val uniqueOwner = installation.choices.singleOrNull { it.ownsGame }
    Column {
        installation.choices.forEach { choice ->
            if (choice.id == installation.selectedId) {
                Text("${choice.label} · ${choice.root}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (uniqueOwner == null) {
                HideoutButton("${choice.label} · ${choice.root}", { select(choice.id) },
                    enabled = enabled, variant = ButtonVariant.Text, size = ButtonSize.Small)
            }
        }
    }
}
