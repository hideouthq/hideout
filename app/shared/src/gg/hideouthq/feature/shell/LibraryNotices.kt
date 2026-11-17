package gg.hideouthq.feature.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import gg.hideouthq.core.designsystem.component.NoticeCard
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.core.ui.retryHelps
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.notice_dismiss
import gg.hideouthq.resources.notice_install_failed_lead
import gg.hideouthq.resources.notice_install_failed_unnamed
import gg.hideouthq.resources.notice_launch_failed_lead
import gg.hideouthq.resources.notice_reapply_failed_lead
import gg.hideouthq.resources.notice_show_in_library
import gg.hideouthq.resources.notice_try_again
import gg.hideouthq.resources.notice_uninstall_failed_lead
import gg.hideouthq.resources.profile_apply_failed
import gg.hideouthq.resources.profile_save_failed
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryNotices(notices: List<ShellNotice>, actions: NoticeActions, onShowInstalled: (String) -> Unit, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    val rise = with(LocalDensity.current) { HideoutMotion.RollOffset.roundToPx() }
    Column(modifier.widthIn(max = HideoutDimens.Notice.FloatingMaxWidth), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Notice.StackGap)) {
        notices.forEach { notice ->
            key(notice.key()) {
                val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(shown, enter = fadeIn(motion.effectsDefault()) + slideInVertically(motion.spatialDefault()) { rise }) {
                    Notice(notice, actions, onShowInstalled)
                }
            }
        }
    }
}

@Composable
private fun Notice(notice: ShellNotice, actions: NoticeActions, onShowInstalled: (String) -> Unit) {
    when (notice) {
        is ShellNotice.InstallFailed -> {
            val lead = notice.title?.let { stringResource(Res.string.notice_install_failed_lead, it) } ?: stringResource(Res.string.notice_install_failed_unnamed)
            val message = LibraryLabels.problem(notice.problem)
            val same = notice.problem as? LibraryProblem.SameFileInstalled
            if (same != null) NoticeCard(
                lead = lead,
                message = message,
                primaryLabel = stringResource(Res.string.notice_show_in_library),
                onPrimary = {
                    actions.dismissInstall(notice.modId)
                    onShowInstalled(same.installId)
                },
                secondaryLabel = stringResource(Res.string.notice_dismiss),
                onSecondary = { actions.dismissInstall(notice.modId) },
            ) else if (notice.problem.retryHelps) NoticeCard(
                lead = lead,
                message = message,
                primaryLabel = stringResource(Res.string.notice_try_again),
                onPrimary = { actions.retryInstall(notice.modId) },
                secondaryLabel = stringResource(Res.string.notice_dismiss),
                onSecondary = { actions.dismissInstall(notice.modId) },
            ) else NoticeCard(lead = lead, message = message, primaryLabel = stringResource(Res.string.notice_dismiss), onPrimary = { actions.dismissInstall(notice.modId) })
        }
        is ShellNotice.LaunchFailed -> NoticeCard(
            lead = stringResource(Res.string.notice_launch_failed_lead),
            message = LibraryLabels.problem(notice.problem),
            primaryLabel = stringResource(Res.string.notice_try_again),
            onPrimary = { actions.play(notice.modded) },
            secondaryLabel = stringResource(Res.string.notice_dismiss),
            onSecondary = actions::dismissFailure,
        )
        is ShellNotice.ReapplyFailed -> NoticeCard(
            lead = stringResource(Res.string.notice_reapply_failed_lead),
            message = LibraryLabels.problem(notice.problem),
            primaryLabel = stringResource(Res.string.notice_try_again),
            onPrimary = actions::reapply,
            secondaryLabel = stringResource(Res.string.notice_dismiss),
            onSecondary = actions::dismissFailure,
        )
        is ShellNotice.ChoiceSaveFailed -> NoticeCard(
            lead = stringResource(Res.string.profile_save_failed),
            message = LibraryLabels.problem(notice.problem),
            primaryLabel = stringResource(Res.string.notice_dismiss),
            onPrimary = actions::dismissChoiceSaveFailure,
        )
        is ShellNotice.SavedChoicesFailed -> NoticeCard(
            lead = stringResource(Res.string.profile_apply_failed),
            message = LibraryLabels.problem(notice.problem),
            primaryLabel = stringResource(Res.string.notice_try_again),
            onPrimary = actions::retrySavedChoices,
        )
        is ShellNotice.ChangeFailed -> NoticeCard(
            lead = stringResource(Res.string.notice_uninstall_failed_lead, notice.title),
            message = LibraryLabels.problem(notice.problem),
            primaryLabel = stringResource(Res.string.notice_try_again),
            onPrimary = { actions.retryChange(notice.installId) },
            secondaryLabel = stringResource(Res.string.notice_dismiss),
            onSecondary = { actions.dismissChange(notice.installId) },
        )
    }
}

private fun ShellNotice.key(): Any = when (this) {
    is ShellNotice.InstallFailed -> "install:$modId"
    is ShellNotice.LaunchFailed, is ShellNotice.ReapplyFailed -> "failed"
    is ShellNotice.SavedChoicesFailed -> "saved-choices"
    is ShellNotice.ChoiceSaveFailed -> "choice-save"
    is ShellNotice.ChangeFailed -> "change:$installId"
}
