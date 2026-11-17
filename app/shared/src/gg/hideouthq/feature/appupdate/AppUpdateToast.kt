package gg.hideouthq.feature.appupdate

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.designsystem.component.HideoutSnackbar
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.boldValue
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.app_update_close
import gg.hideouthq.resources.app_update_toast
import gg.hideouthq.resources.app_update_whats_new
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private const val ShownMillis = 10_000L

/** The note that Hideout has just updated to [version], once, until [onSeen] after a while or on its x. */
@Composable
fun AppUpdateToast(version: AppVersion?, onWhatsNew: () -> Unit, onSeen: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(version) }
    if (version != null) last = version
    LaunchedEffect(version) {
        if (version != null) {
            delay(ShownMillis)
            onSeen()
        }
    }
    val motion = HideoutTheme.motion
    AnimatedVisibility(version != null, modifier, enter = fadeIn(motion.effectsDefault()) + slideInVertically(motion.spatialDefault()) { if (motion.reduced) 0 else it / 3 }, exit = fadeOut(motion.exitFade())) {
        last?.let { shown ->
            HideoutSnackbar(
                boldValue(stringResource(Res.string.app_update_toast, shown.toString()), shown.toString(), SpanStyle(fontWeight = HideoutWeights.Title)),
                actionLabel = stringResource(Res.string.app_update_whats_new),
                onAction = onWhatsNew,
                closeLabel = stringResource(Res.string.app_update_close),
                onClose = onSeen,
            )
        }
    }
}
