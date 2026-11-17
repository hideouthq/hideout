package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights

enum class GlassBadgeSize { Small, Regular }

@Composable
fun GlassBadge(text: String, modifier: Modifier = Modifier, size: GlassBadgeSize = GlassBadgeSize.Small, dot: Boolean = false, dotColor: Color = Color.Unspecified) {
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    val small = size == GlassBadgeSize.Small
    val padding = if (small) HideoutDimens.GlassBadge.Padding else HideoutDimens.GlassBadge.PaddingRegular
    Row(
        modifier
            .height(if (small) HideoutDimens.GlassBadge.Height else HideoutDimens.GlassBadge.HeightRegular)
            .background(if (small) colors.glassBadge else colors.glassPill, HideoutShapes.Full)
            .padding(start = if (dot && !small) HideoutDimens.GlassBadge.DotPaddingRegular else padding, end = padding),
        horizontalArrangement = Arrangement.spacedBy(if (small) HideoutDimens.GlassBadge.Gap else HideoutDimens.GlassBadge.GapRegular),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) Box(Modifier.size(if (small) HideoutDimens.GlassBadge.Dot else HideoutDimens.GlassBadge.DotRegular).background(dotColor.takeOrElse { colors.status }, CircleShape))
        Text(
            text,
            Modifier.weight(1f, fill = false),
            style = if (small) type.captionCompact.copy(fontWeight = HideoutWeights.Title) else type.labelChip,
            color = colors.onImage,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
