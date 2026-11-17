package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.deadlock_icon
import org.jetbrains.compose.resources.painterResource

@Composable
fun LaunchCard(
    title: String,
    subtitle: String,
    primaryLabel: String,
    secondaryLabel: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    gameFound: Boolean = true,
    enabled: Boolean = true,
    running: Boolean = false,
    status: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor, HideoutShapes.Card)
            .padding(HideoutDimens.LaunchCard.Padding),
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.LaunchCard.Gap),
    ) {
        Row(
            modifier = Modifier.padding(bottom = HideoutDimens.LaunchCard.HeaderBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.LaunchCard.HeaderGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(HideoutDimens.LaunchCard.GameTile)
                    .background(if (running) scheme.secondary else if (gameFound) scheme.secondaryContainer else scheme.tertiaryContainer, HideoutShapes.GameTile),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(Res.drawable.deadlock_icon), contentDescription = null, modifier = Modifier.size(HideoutDimens.LaunchCard.GameTile))
                if (running) Box(Modifier.align(Alignment.TopEnd).offset(2.dp, (-2).dp).size(10.dp).background(scheme.secondary, CircleShape).border(2.dp, scheme.surfaceContainerLow, CircleShape))
            }
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.LaunchCard.Gap), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f, fill = false), style = HideoutTheme.typography.bodyStrong, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (status != null) Text(status, style = HideoutTheme.typography.caption, color = scheme.primary, maxLines = 1)
                }
                AnimatedContent(
                    targetState = subtitle,
                    transitionSpec = { fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.exitFade()) },
                    label = "launchSubtitle",
                ) { text ->
                    Text(text, style = HideoutTheme.typography.caption, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        HideoutButton(
            text = primaryLabel,
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = if (running) HideoutIcons.InGame else HideoutIcons.Play,
            leadingIconSize = HideoutDimens.Icon.Small,
            enabled = enabled,
        )
        HideoutButton(
            text = secondaryLabel,
            onClick = onSecondary,
            modifier = Modifier.fillMaxWidth(),
            variant = ButtonVariant.Text,
            size = ButtonSize.Small,
            enabled = enabled,
        )
    }
}
