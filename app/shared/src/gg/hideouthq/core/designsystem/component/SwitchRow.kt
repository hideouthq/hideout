package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights

@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().height(HideoutDimens.Switch.RowHeight),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Fact.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) HideoutIcon(icon, contentDescription = null, tint = scheme.onSurfaceVariant)
        Text(
            text = label,
            style = HideoutTheme.typography.body.copy(fontWeight = HideoutWeights.Medium),
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        HideoutSwitch(checked = checked, onCheckedChange = onCheckedChange, contentDescription = label)
    }
}
