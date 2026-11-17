package gg.hideouthq.feature.install.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.ChoiceAnswer
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.install.SwapQuestion
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.skin_swap_body
import gg.hideouthq.resources.skin_swap_cancel
import gg.hideouthq.resources.skin_swap_keep_both
import gg.hideouthq.resources.skin_swap_swap
import gg.hideouthq.resources.skin_swap_title
import gg.hideouthq.resources.skin_swap_title_mods
import gg.hideouthq.resources.skin_swap_title_unnamed
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SkinSwapDialog(question: SwapQuestion?, modal: WindowModal, onAnswer: (ChoiceAnswer) -> Unit) {
    var last by remember { mutableStateOf(question) }
    if (question != null) last = question
    val shown = last ?: return
    val swap = remember { FocusRequester() }
    val count = shown.others.size
    val title = when {
        !shown.skins -> pluralStringResource(Res.plurals.skin_swap_title_mods, count, count, shown.others.joinToString(", "))
        shown.heroName != null -> pluralStringResource(Res.plurals.skin_swap_title, count, count, shown.heroName)
        else -> pluralStringResource(Res.plurals.skin_swap_title_unnamed, count, count)
    }
    AlertModal(visible = question != null, modal = modal, title = title, onDismiss = { onAnswer(ChoiceAnswer.Cancel) }, initialFocus = swap) {
        val scheme = MaterialTheme.colorScheme
        val type = HideoutTheme.typography
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(title, style = type.titleDialog, color = scheme.onSurface)
            Text(pluralStringResource(Res.plurals.skin_swap_body, count, count, shown.others.joinToString(", "), shown.title), style = type.bodyDialog, color = scheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
            ) {
                HideoutButton(
                    stringResource(Res.string.skin_swap_cancel),
                    { onAnswer(ChoiceAnswer.Cancel) },
                    variant = ButtonVariant.Text,
                    size = ButtonSize.Regular,
                    contentPadding = PaddingValues(horizontal = HideoutDimens.AlertDialog.CancelPadding),
                )
                HideoutButton(stringResource(Res.string.skin_swap_keep_both), { onAnswer(ChoiceAnswer.KeepBoth) }, variant = ButtonVariant.Subtle, enabled = question != null)
                HideoutButton(stringResource(Res.string.skin_swap_swap), { onAnswer(ChoiceAnswer.Swap) }, Modifier.focusRequester(swap), enabled = question != null)
            }
        }
    }
}
