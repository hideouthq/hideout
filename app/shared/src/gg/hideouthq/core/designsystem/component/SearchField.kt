package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.animatedHoverBrightness
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.theme.brightened
import gg.hideouthq.core.ui.trackFieldFocus
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

enum class SearchFieldSize { Regular, Small, Toolbar }

@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    size: SearchFieldSize = SearchFieldSize.Regular,
    shortcutHint: String? = null,
    focusedHint: String? = null,
    focusRequester: FocusRequester? = null,
) {
    val focusManager = LocalFocusManager.current
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val focused by source.collectIsFocusedAsState()
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val small = size == SearchFieldSize.Small
    val toolbar = size == SearchFieldSize.Toolbar
    val textStyle = (if (small) type.captionSmall else type.body).copy(color = scheme.onSurface)
    val brightness = animatedHoverBrightness(states.hovered && !focused)
    val container = if (toolbar) scheme.surfaceContainerHigh else scheme.surfaceContainer
    val motion = HideoutTheme.motion

    Row(
        modifier = modifier
            .trackFieldFocus(focused)
            .height(
                when (size) {
                    SearchFieldSize.Regular -> HideoutDimens.Search.Height
                    SearchFieldSize.Small -> HideoutDimens.Search.HeightSmall
                    SearchFieldSize.Toolbar -> HideoutDimens.Search.HeightToolbar
                },
            )
            .drawBehind {
                val radius = if (toolbar) HideoutRadius.Field.toPx() else this.size.height / 2
                drawRoundRect(container.brightened(brightness.value), cornerRadius = CornerRadius(radius))
            }
            .focusRing(focused, if (toolbar) RoundedCornerShape(HideoutRadius.Field) else HideoutShapes.Full)
            .padding(
                start = if (small) HideoutDimens.Search.StartPaddingSmall else HideoutDimens.Search.StartPadding,
                end = when (size) {
                    SearchFieldSize.Regular -> HideoutDimens.Search.EndPadding
                    SearchFieldSize.Small -> HideoutDimens.Search.StartPaddingSmall
                    SearchFieldSize.Toolbar -> HideoutDimens.Search.StartPadding
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(if (small) HideoutDimens.Search.GapSmall else HideoutDimens.Search.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(HideoutIcons.Search, contentDescription = null, size = HideoutDimens.Icon.Small, tint = if (toolbar) scheme.onSurfaceVariant else scheme.outline)
        val (field, onFieldChange) = rememberFieldValue(query, onQueryChange)
        BasicTextField(
            value = field,
            onValueChange = onFieldChange,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                    if (escape) if (query.isNotEmpty()) onQueryChange("") else focusManager.clearFocus()
                    escape
                }
                .padding(horizontal = HideoutDimens.Search.InputInset)
                .semantics { contentDescription = placeholder }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            textStyle = textStyle,
            singleLine = true,
            cursorBrush = SolidColor(scheme.primary),
            interactionSource = source,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { innerField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        AnimatedContent(
                            targetState = placeholder,
                            transitionSpec = {
                                fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.exitFade())
                            },
                            label = "searchPlaceholder",
                        ) { text ->
                            Text(text, style = textStyle, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    innerField()
                }
            },
        )
        val hint = if (focused) focusedHint else shortcutHint
        if (!small && hint != null) KeyboardHint(hint)
    }
}
