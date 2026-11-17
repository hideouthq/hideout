package gg.hideouthq.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.order_move_cancel
import gg.hideouthq.resources.order_move_confirm
import gg.hideouthq.resources.order_move_to_position_title
import gg.hideouthq.resources.order_position_label
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MoveToPositionDialog(
    visible: Boolean,
    modal: WindowModal,
    title: String,
    totalCount: Int,
    initialPosition: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by remember(visible) {
        val initial = if (visible) initialPosition.toString() else ""
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val position = field.text.toIntOrNull()
    val focus = remember { FocusRequester() }
    val dialogTitle = stringResource(Res.string.order_move_to_position_title, title)
    val positionLabel = stringResource(Res.string.order_position_label, totalCount)
    fun confirm() {
        onConfirm(position ?: return)
        onDismiss()
    }
    AlertModal(visible = visible, modal = modal, title = dialogTitle, onDismiss = onDismiss, initialFocus = focus) {
        val scheme = MaterialTheme.colorScheme
        Column(verticalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.Gap)) {
            Text(dialogTitle, style = HideoutTheme.typography.titleDialog, color = scheme.onSurface)
            Column(
                Modifier
                    .heightIn(min = 56.dp)
                    .background(scheme.surface, RoundedCornerShape(14.dp))
                    .border(2.dp, scheme.primary, RoundedCornerShape(14.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(positionLabel, style = HideoutTheme.typography.caption, color = scheme.primary)
                BasicTextField(
                    value = field,
                    onValueChange = { field = it.copy(text = it.text.filter(Char::isDigit).take(MaxDigits)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = positionLabel }
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                confirm()
                                true
                            } else {
                                false
                            }
                        },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = HideoutTheme.typography.body.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HideoutDimens.AlertDialog.FooterTopPadding),
                horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AlertDialog.FooterGap, Alignment.End),
            ) {
                HideoutButton(stringResource(Res.string.order_move_cancel), onDismiss, variant = ButtonVariant.Text)
                HideoutButton(stringResource(Res.string.order_move_confirm), ::confirm, enabled = position != null)
            }
        }
    }
}

private const val MaxDigits = 6
