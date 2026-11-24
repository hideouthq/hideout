package gg.hideouthq.feature.customgames

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import gg.hideouthq.core.designsystem.component.HideoutSnackbar
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.cg_firewall_asking
import org.jetbrains.compose.resources.stringResource

/** The line that stays while Hideout has Windows ask about the game server, so its question comes with a word from Hideout. */
@Composable
fun FirewallAskNote(asking: Boolean, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    AnimatedVisibility(asking, modifier, enter = fadeIn(motion.effectsDefault()) + slideInVertically(motion.spatialDefault()) { if (motion.reduced) 0 else it / 3 }, exit = fadeOut(motion.exitFade())) {
        HideoutSnackbar(AnnotatedString(stringResource(Res.string.cg_firewall_asking)))
    }
}
