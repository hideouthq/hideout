package gg.hideouthq.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.ProfileTile
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.handCursor
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.Profile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId

val ProfileColour.color: Color
    get() = when (this) {
        ProfileColour.Sage -> Color(0xFFB5CCBA)
        ProfileColour.Blue -> Color(0xFFB4C7D6)
        ProfileColour.Clay -> Color(0xFFD4B5A6)
        ProfileColour.Mauve -> Color(0xFFCBC4CB)
        ProfileColour.Sand -> Color(0xFFD8C9A8)
        ProfileColour.Rose -> Color(0xFFD1C3C6)
    }

@Composable
fun ProfileChips(
    profiles: List<Profile>,
    selected: Set<ProfileId>,
    onToggle: (ProfileId, Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    waiting: Boolean = false,
    /** Profiles that turn on some of several mods, not all; a click turns all of them on. */
    mixed: Set<ProfileId> = emptySet(),
) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        profiles.forEach { profile ->
            val state = when (profile.id) {
                in selected -> ToggleableState.On
                in mixed -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            }
            val on = state != ToggleableState.Off
            val interaction = remember(profile.id) { MutableInteractionSource() }
            Row(
                Modifier.height(32.dp).alpha(if (enabled || waiting) 1f else 0.45f)
                    .background(if (on) scheme.surfaceContainerHigh else Color.Transparent, CircleShape)
                    .then(if (on) Modifier else Modifier.border(1.5.dp, scheme.outlineVariant, CircleShape))
                    .triStateToggleable(state, interaction, indication = null, enabled = enabled, role = Role.Checkbox) { onToggle(profile.id, state != ToggleableState.On) }
                    .handCursor(enabled)
                    .padding(start = 6.dp, end = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileTile(profile.name.take(1).uppercase(), true, colour = profile.colour.color)
                Text(profile.name, style = HideoutTheme.typography.caption, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
                if (on) HideoutIcon(if (state == ToggleableState.On) HideoutIcons.Check else HideoutIcons.Minus, null, size = 14.dp)
            }
        }
    }
}
