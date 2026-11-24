package gg.hideouthq.feature.customgames

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.customgames.Mode
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Asks once whether Hideout may put its UI into Deadlock's own screens. [mode] lends the art, the first in the feed.
 * Escape counts as "Not now", so the question is answered either way.
 */
@Composable
fun InGameDialog(open: Boolean, mode: Mode?, modal: WindowModal, onAnswer: (Boolean) -> Unit) {
    val focus = remember { FocusRequester() }
    val title = stringResource(Res.string.cg_ingame_title)
    AlertModal(open, modal, title, onDismiss = { onAnswer(false) }, initialFocus = focus, width = 560.dp, padding = 0.dp) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Hero(mode, title)
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

/** The mode banner's composition at dialog size: backdrop, the render standing at the right and the overlay over both. */
@Composable
private fun Hero(mode: Mode?, title: String) {
    val art = mode?.listing?.art
    val seed = mode?.id?.value ?: SeedOfUnknown
    Box(Modifier.fillMaxWidth().height(HeroHeight)) {
        Box(Modifier.fillMaxSize().clip(HeroShape)) {
            if (art?.layered == true) {
                ModeArtSlot(seed, art.backdrop, Modifier.fillMaxSize(), HeroShape, glyph = null)
                ArtLayer(art.render, Modifier.align(Alignment.BottomEnd).offset(x = (-36).dp, y = 18.dp).size(190.dp, 220.dp))
            } else {
                ModeArtSlot(seed, art?.card, Modifier.fillMaxSize(), HeroShape, glyph = null)
            }
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.horizontalGradient(0f to Shade.copy(alpha = 0.92f), 0.42f to Shade.copy(alpha = 0.7f), 0.7f to Shade.copy(alpha = 0f))) })
            Box(Modifier.fillMaxSize().drawBehind { drawRect(Brush.verticalGradient(0.55f to Shade.copy(alpha = 0f), 1f to Shade.copy(alpha = 0.55f))) })
            HeroTitle(title)
        }
        if (art?.layered == true) ArtLayer(art.overlay, Modifier.align(Alignment.TopEnd).offset(x = 14.dp, y = (-30).dp).size(290.dp, 160.dp))
    }
}

@Composable
private fun BoxScope.HeroTitle(title: String) {
    val onImage = HideoutTheme.colors.onImage
    Column(Modifier.align(Alignment.BottomStart).padding(start = 24.dp, end = 200.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
private val Shade = Color(0xFF0A0D0F)
