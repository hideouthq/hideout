package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.leaveOnEscape
import gg.hideouthq.core.ui.trackFieldFocus

enum class TextInputSize { Regular, Compact }

enum class TextInputStyle { Title, Code }

/**
 * A single-line field: surface1 inside a 1dp surface4 ring, which a 2dp primary ring covers while it is focused.
 * [leading] sits before the text (the hex field's colour square). Enter calls [onSubmit]; Esc leaves the field rather
 * than going back. A line break pasted in is left out, unless [lineBreaks] keeps it, for text read line by line.
 */
@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    size: TextInputSize = TextInputSize.Regular,
    style: TextInputStyle = TextInputStyle.Code,
    textAlign: TextAlign = TextAlign.Start,
    onSubmit: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    lineBreaks: Boolean = false,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val regular = size == TextInputSize.Regular
    val shape = RoundedCornerShape(if (regular) HideoutRadius.Field else HideoutRadius.FieldCompact)
    val textStyle = (if (style == TextInputStyle.Title) type.titlePanel else type.monoCode).copy(color = scheme.onSurface, textAlign = textAlign)
    val horizontal = if (regular) HideoutDimens.Field.HorizontalPadding else HideoutDimens.Field.HorizontalPaddingCompact
    val (field, onFieldChange) = rememberFieldValue(value, onValueChange, focused)

    Row(
        modifier = modifier
            .trackFieldFocus(focused)
            .height(if (regular) HideoutDimens.Field.Height else HideoutDimens.Field.HeightCompact)
            .background(scheme.surface, shape)
            .focusRing(focused, shape)
            .insetRing(shape, scheme.surfaceContainerHighest) { HideoutDimens.Hairline.toPx() }
            .padding(start = if (leading != null) HideoutDimens.Field.LeadingPadding else horizontal, end = horizontal),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Field.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        BasicTextField(
            value = field,
            onValueChange = { onFieldChange(if (lineBreaks) it else it.withoutLineBreaks()) },
            modifier = Modifier.weight(1f).leaveOnEscape().semantics { contentDescription = label },
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(scheme.primary),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(imeAction = if (onSubmit != null) ImeAction.Done else ImeAction.Default),
            keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder != null) {
                        Text(placeholder, Modifier.fillMaxWidth(), style = textStyle.copy(color = scheme.outline), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * What a single-line field over [text], which its caller owns, shows, and the change to hand the field: typing keeps its
 * selection, a change from outside puts the cursor at the end, and [onTextChange] hears each new text. While the field
 * is [focused], what was typed stays as typed when [text] does not change with it, as when the caller trims a space
 * typed last or leaves a value as it was; once it is not, the field shows [text].
 */
@Composable
internal fun rememberFieldValue(text: String, onTextChange: (String) -> Unit, focused: Boolean = false): Pair<TextFieldValue, (TextFieldValue) -> Unit> {
    var field by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    val last = remember { LastText(text) }
    if (field.text != text && (text != last.text || !focused)) field = TextFieldValue(text, TextRange(text.length))
    last.text = text
    return field to { next ->
        field = next
        if (next.text != text) onTextChange(next.text)
    }
}

private class LastText(var text: String)

private fun TextFieldValue.withoutLineBreaks(): TextFieldValue {
    if (text.none { it == '\n' || it == '\r' }) return this
    fun kept(offset: Int) = offset - text.take(offset).count { it == '\n' || it == '\r' }
    return TextFieldValue(text.filterNot { it == '\n' || it == '\r' }, TextRange(kept(selection.start), kept(selection.end)))
}
