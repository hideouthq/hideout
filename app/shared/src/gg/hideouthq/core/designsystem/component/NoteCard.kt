package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights

@Composable
fun NoteCard(lead: String, message: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    NoteLine(
        HideoutIcons.Warning, scheme.tertiary, lead, HideoutTheme.colors.onImage, message, scheme.onTertiaryContainer, HideoutDimens.Notice.Gap,
        modifier.fillMaxWidth().background(scheme.tertiaryContainer, HideoutShapes.Card).padding(HideoutDimens.Notice.Padding),
    )
}

@Composable
fun NoticeCard(
    lead: String,
    message: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
    modifier: Modifier = Modifier,
    primaryEnabled: Boolean = true,
    tertiaryLabel: String? = null,
    onTertiary: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.tertiaryContainer, HideoutShapes.Card)
            .padding(HideoutDimens.Notice.Padding),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.Notice.Gap),
    ) {
        NoteLine(HideoutIcons.Warning, scheme.tertiary, lead, Color.Unspecified, message, scheme.onTertiaryContainer, HideoutDimens.Notice.IconGap)
        Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Notice.ActionGap)) {
            HideoutButton(primaryLabel, onPrimary, variant = ButtonVariant.Warning, size = ButtonSize.Small, enabled = primaryEnabled)
            if (secondaryLabel != null) HideoutButton(secondaryLabel, onSecondary, variant = ButtonVariant.Text, size = ButtonSize.Small, contentColor = scheme.onTertiaryContainer)
            if (tertiaryLabel != null) HideoutButton(tertiaryLabel, onTertiary, variant = ButtonVariant.Text, size = ButtonSize.Small, contentColor = scheme.onTertiaryContainer)
        }
    }
}

@Composable
private fun NoteLine(icon: ImageVector, iconTint: Color, lead: String, leadColor: Color, message: String, color: Color, gap: Dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        HideoutIcon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.padding(top = HideoutDimens.Hairline))
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = HideoutWeights.Title, color = leadColor)) { append(lead) }
                append(" ")
                append(message)
            },
            style = HideoutTheme.typography.notice,
            color = color,
        )
    }
}
