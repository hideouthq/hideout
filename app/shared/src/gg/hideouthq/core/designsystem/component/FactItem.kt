package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun FactItem(
    icon: ImageVector?,
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Fact.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier.size(HideoutDimens.Fact.IconTile).background(scheme.surfaceContainerHigh, HideoutShapes.Row),
                contentAlignment = Alignment.Center,
            ) {
                HideoutIcon(icon, contentDescription = null, tint = scheme.onSurface)
            }
        }
        Column {
            if (label != null) Text(label, style = type.caption, color = scheme.outline, maxLines = 1)
            RollingText(value, type.factValue, color = scheme.onSurface, overflow = TextOverflow.Ellipsis)
        }
    }
}
