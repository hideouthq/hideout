package gg.hideouthq.feature.customgames

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
 * Asks once whether Hideout may put its UI into Deadlock's own screens, over a capture of the Hideout games panel as it
 * opens in the game. Escape counts as "Not now", so the question is answered either way.
 */
@Composable
fun InGameDialog(open: Boolean, modal: WindowModal, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    val title = stringResource(Res.string.cg_ingame_title)
    AlertModal(open, modal, title, onDismiss = { onAnswer(false) }, initialFocus = focus, width = 560.dp, padding = 0.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Hero(title)
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun Hero(title: String) {
    Box(Modifier.fillMaxWidth().height(HeroHeight).clip(HeroShape)) {
        Image(painterResource(Res.drawable.ingame_games_panel), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.3f to Shade.copy(alpha = 0f), 0.62f to Shade.copy(alpha = 0.78f), 1f to Shade.copy(alpha = 0.96f))) })
        Badge()
        HeroTitle(title)
    }
}

@Composable
private fun BoxScope.Badge() {
    val onImage = HideoutTheme.colors.onImage
    Row(
        Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 14.dp)
            .background(Shade.copy(alpha = 0.82f), BadgeShape).border(1.dp, onImage.copy(alpha = 0.14f), BadgeShape)
            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(Res.drawable.deadlock_icon), null, Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
        Text(
            stringResource(Res.string.cg_ingame_badge),
            style = HideoutTheme.typography.caption.copy(fontSize = 12.sp, fontWeight = HideoutWeights.Label, letterSpacing = 0.02.em),
            color = onImage,
        )
    }
}

@Composable
private fun BoxScope.HeroTitle(title: String) {
    val onImage = HideoutTheme.colors.onImage
    Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 24.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.nav_custom_games),
            style = HideoutTheme.typography.caption.copy(fontSize = 12.sp, fontWeight = HideoutWeights.Label, letterSpacing = 0.04.em),
            color = onImage.copy(alpha = 0.72f),
        )
        Text(
            title,
            style = HideoutTheme.typography.displayHero.copy(fontSize = 30.sp, lineHeight = 33.sp, fontWeight = HideoutWeights.Title, letterSpacing = (-0.02).em),
            color = onImage,
        )
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

private val HeroHeight = 236.dp
private val HeroShape = RoundedCornerShape(topStart = HideoutDimens.AlertDialog.Radius, topEnd = HideoutDimens.AlertDialog.Radius)
private val BadgeShape = RoundedCornerShape(999.dp)
private val Shade = Color(0xFF0A0D0F)
