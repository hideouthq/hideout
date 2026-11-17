package gg.hideouthq.feature.moddetail.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import gg.hideouthq.core.designsystem.component.HideoutSnackbar
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.moddetail.PictureNotice
import gg.hideouthq.feature.moddetail.PictureReveal
import gg.hideouthq.resources.save_image_reveal_failed
import gg.hideouthq.resources.notice_try_again
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.profile_close_notice
import gg.hideouthq.resources.save_image_failed
import gg.hideouthq.resources.save_image_saved
import gg.hideouthq.resources.save_image_show
import gg.hideouthq.resources.scene_image_failed
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun PictureSnackbar(notice: PictureNotice?, onShow: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(notice) }
    if (notice != null) last = notice
    val motion = HideoutTheme.motion
    AnimatedVisibility(notice != null, modifier, enter = fadeIn(motion.effectsDefault()) + slideInVertically(motion.spatialDefault()) { if (motion.reduced) 0 else it / 3 }, exit = fadeOut(motion.exitFade())) {
        val saved = last as? PictureNotice.Saved
        val revealFailed = saved?.reveal == PictureReveal.Failed
        HideoutSnackbar(
            message = AnnotatedString(
                stringResource(
                    when {
                        revealFailed -> Res.string.save_image_reveal_failed
                        saved != null -> Res.string.save_image_saved
                        last == PictureNotice.BackdropFailed -> Res.string.scene_image_failed
                        else -> Res.string.save_image_failed
                    },
                ),
            ),
            actionLabel = if (saved != null) stringResource(if (revealFailed) Res.string.notice_try_again else Res.string.save_image_show) else null,
            actionEnabled = saved?.reveal != PictureReveal.Opening,
            onAction = onShow,
            closeLabel = stringResource(Res.string.profile_close_notice),
            onClose = onClose,
        )
    }
}
