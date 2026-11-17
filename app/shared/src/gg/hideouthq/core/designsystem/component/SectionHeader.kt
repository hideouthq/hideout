package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = Color.Unspecified,
    keyword: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = HideoutDimens.SectionHeader.MinHeight)
            .padding(horizontal = HideoutDimens.SectionHeader.HorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(HideoutDimens.Discover.SectionIconTile)
                    .background(accent.copy(alpha = HideoutDimens.Discover.SectionTintAlpha), RoundedCornerShape(HideoutDimens.Discover.SectionIconRadius)),
                contentAlignment = Alignment.Center,
            ) {
                HideoutIcon(icon, contentDescription = null, size = HideoutDimens.Discover.SectionIcon, tint = accent)
            }
        }
        val styled = buildAnnotatedString {
            append(title)
            val start = keyword?.let(title::lastIndexOf) ?: -1
            if (keyword != null && start >= 0 && accent.isSpecified) addStyle(SpanStyle(color = accent), start, start + keyword.length)
        }
        Text(
            text = styled,
            style = HideoutTheme.typography.titleSection,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
            maxLines = 1,
        )
        trailing?.invoke()
    }
}
