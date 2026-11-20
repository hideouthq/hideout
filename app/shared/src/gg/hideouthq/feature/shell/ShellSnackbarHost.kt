package gg.hideouthq.feature.shell

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
import gg.hideouthq.core.library.Undoable
import gg.hideouthq.core.sharing.InstallLink
import gg.hideouthq.feature.imports.managerName
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.install_link_not_a_mod
import gg.hideouthq.resources.install_link_unreadable
import gg.hideouthq.resources.library_turned_off
import gg.hideouthq.resources.library_turned_off_in
import gg.hideouthq.resources.library_turned_on
import gg.hideouthq.resources.library_turned_on_in
import gg.hideouthq.resources.library_uninstalled
import gg.hideouthq.resources.library_uninstalled_count
import gg.hideouthq.resources.mod_lines_put_back
import gg.hideouthq.resources.profile_close_notice
import gg.hideouthq.resources.profile_deleted
import gg.hideouthq.resources.profile_deleted_switched
import gg.hideouthq.resources.profile_undo
import gg.hideouthq.resources.share_copied
import gg.hideouthq.resources.share_copied_long
import gg.hideouthq.resources.share_copy_failed
import gg.hideouthq.resources.share_left_out
import gg.hideouthq.resources.share_making
import gg.hideouthq.resources.share_nothing
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ShellSnackbarHost(snackbar: ShellSnackbar?, enabled: Boolean, onUndo: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    var last by remember { mutableStateOf(snackbar) }
    if (snackbar != null) last = snackbar
    val motion = HideoutTheme.motion
    AnimatedVisibility(snackbar != null, modifier, enter = fadeIn(motion.effectsDefault()) + slideInVertically(motion.spatialDefault()) { if (motion.reduced) 0 else it / 3 }, exit = fadeOut(motion.exitFade())) {
        last?.let { shown ->
            val message = when (val content = shown.message) {
                is SnackbarMessage.LinesPutBack -> stringResource(Res.string.mod_lines_put_back, managerName(content.manager))
                is SnackbarMessage.Sharing -> stringResource(Res.string.share_making, content.profile)
                is SnackbarMessage.Shared -> {
                    val link = content.link
                    val copied = stringResource(if (link.short) Res.string.share_copied else Res.string.share_copied_long, link.profile)
                    if (link.leftOut == 0) copied else pluralStringResource(Res.plurals.share_left_out, link.leftOut, copied, link.leftOut)
                }
                is SnackbarMessage.NothingToShare -> stringResource(Res.string.share_nothing, content.profile)
                SnackbarMessage.CopyFailed -> stringResource(Res.string.share_copy_failed)
                is SnackbarMessage.LinkRefused -> stringResource(
                    when (content.why) {
                        InstallLink.Refused.NotAMod -> Res.string.install_link_not_a_mod
                        InstallLink.Refused.Unreadable -> Res.string.install_link_unreadable
                    },
                )
                is SnackbarMessage.Edit -> when (val edit = content.undoes) {
                    is Undoable.Deleted -> edit.switchedTo?.let { stringResource(Res.string.profile_deleted_switched, edit.name, it) } ?: stringResource(Res.string.profile_deleted, edit.name)
                    is Undoable.Turned -> edit.profile?.let { profile ->
                        pluralStringResource(if (edit.enabled) Res.plurals.library_turned_on_in else Res.plurals.library_turned_off_in, edit.count, edit.count, profile)
                    } ?: pluralStringResource(if (edit.enabled) Res.plurals.library_turned_on else Res.plurals.library_turned_off, edit.count, edit.count)
                    is Undoable.Uninstalled -> edit.title?.let { stringResource(Res.string.library_uninstalled, it) }
                        ?: pluralStringResource(Res.plurals.library_uninstalled_count, edit.installIds.size, edit.installIds.size)
                }
            }
            HideoutSnackbar(AnnotatedString(message), actionLabel = if (shown.undo != null) stringResource(Res.string.profile_undo) else null, onAction = onUndo, actionEnabled = enabled, closeLabel = stringResource(Res.string.profile_close_notice), onClose = onClose)
        }
    }
}
