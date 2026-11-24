package gg.hideouthq.feature.customgames

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Asks once whether Hideout may put its UI into Deadlock's own screens, over a capture of the Custom Match popup with
 * Hideout's band under it. Escape counts as "Not now", so the question is answered either way.
 */
@Composable
fun InGameDialog(open: Boolean, modal: WindowModal, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    val title = stringResource(Res.string.cg_ingame_title)
    AlertModal(open, modal, title, onDismiss = { onAnswer(false) }, initialFocus = focus, width = 560.dp, padding = 0.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val surface = MaterialTheme.colorScheme.surfaceContainer
            Image(
                painterResource(Res.drawable.ingame_custom_match),
                null,
                Modifier.fillMaxWidth().aspectRatio(ArtAspect).clip(ArtShape).drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(0.8f to surface.copy(alpha = 0f), 1f to surface))
                },
                contentScale = ContentScale.Crop,
            )
            Text(
                title,
                Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                style = HideoutTheme.typography.displayHero.copy(fontSize = 26.sp, lineHeight = 30.sp, fontWeight = HideoutWeights.Title, letterSpacing = (-0.02).em),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Point(HideoutIcons.Gamepad, stringResource(Res.string.cg_ingame_point_band))
                Point(HideoutIcons.Message, stringResource(Res.string.cg_ingame_point_invites))
                Point(HideoutIcons.ViewList, stringResource(Res.string.cg_ingame_point_esc))
            }
            Box(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp)) {
                HintBox(HideoutIcons.Folder, stringResource(Res.string.cg_ingame_file))
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HideoutButton(stringResource(Res.string.cg_ingame_not_now), { onAnswer(false) }, variant = ButtonVariant.Text)
                HideoutButton(stringResource(Res.string.cg_ingame_turn_on), { onAnswer(true) }, Modifier.focusRequester(focus), leadingIcon = HideoutIcons.Check, leadingIconSize = 16.dp)
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBox(icon, Tone.Starting, 36.dp, 12.dp, 18.dp)
        Text(text, style = HideoutTheme.typography.bodyStrong.copy(fontSize = 14.5.sp, fontWeight = HideoutWeights.Label), color = scheme.onSurface)
    }
}

private const val ArtAspect = 1120f / 600f
private val ArtShape = RoundedCornerShape(topStart = HideoutDimens.AlertDialog.Radius, topEnd = HideoutDimens.AlertDialog.Radius)
