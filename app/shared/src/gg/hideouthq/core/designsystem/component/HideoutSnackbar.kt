package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun HideoutSnackbar(
    message: AnnotatedString,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionEnabled: Boolean = true,
    closeLabel: String? = null,
    onClose: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(14.dp)
    val trailing = actionLabel != null && onAction != null || closeLabel != null && onClose != null
    Row(
        modifier.height(48.dp)
            .dropShadow(shape, Shadow(radius = HideoutDimens.Snackbar.ShadowBlur, color = HideoutTheme.colors.dialogShadow, offset = DpOffset(0.dp, HideoutDimens.Snackbar.ShadowOffsetY)))
            .background(Color(0xFFE6E1E1), shape)
            .padding(start = 18.dp, end = if (trailing) 6.dp else 18.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f, fill = false), style = HideoutTheme.typography.labelNav, color = Color(0xFF1D1B1D), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (actionLabel != null && onAction != null) ButtonFrame(
            variant = ButtonVariant.Text,
            size = ButtonSize.Small,
            enabled = actionEnabled,
            modifier = Modifier.padding(start = 8.dp),
            contentPadding = PaddingValues(horizontal = HideoutDimens.Button.TextHorizontalPadding),
            contentColor = Color(0xFF4E4351),
            onClick = onAction,
        ) { content -> Text(actionLabel, style = HideoutTheme.typography.labelButton, color = content, maxLines = 1) }
        if (closeLabel != null && onClose != null) HideoutIconButton(HideoutIcons.Close, closeLabel, onClose, iconSize = 16.dp, tint = Color(0xFF4A474A), hoverColor = Color.Black.copy(alpha = 0.06f))
    }
}
