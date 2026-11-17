package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.windowControlsInset
import gg.hideouthq.core.ui.windowDragArea

/**
 * The 56dp title row at the top of a side panel: optional icon, title, trailing action. It drags the window, and
 * where the window controls are drawn over the row's end, their inset replaces the end padding.
 */
@Composable
fun PaneHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val endInset = windowControlsInset()
    Row(
        modifier = modifier
            .windowDragArea()
            .fillMaxWidth()
            .height(HideoutDimens.SidePanel.HeaderHeight)
            .padding(start = HideoutDimens.SidePanel.HeaderStartPadding, end = if (endInset > 0.dp) endInset else HideoutDimens.SidePanel.HeaderEndPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.SidePanel.HeaderGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) HideoutIcon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            text = title,
            style = HideoutTheme.typography.titlePanel,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
        )
        trailing?.invoke()
    }
}
