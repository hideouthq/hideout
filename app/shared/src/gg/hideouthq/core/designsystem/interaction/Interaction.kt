package gg.hideouthq.core.designsystem.interaction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import gg.hideouthq.core.ui.LocalFocusVisibility

/** A click target with Hideout's conventions: no ripple, pointer cursor, keyboard reachable. */
fun Modifier.hideoutClickable(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier = this
    .clickable(
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        onClickLabel = onClickLabel,
        role = role,
        onClick = onClick,
    )
    .handCursor(enabled)

fun Modifier.hideoutToggleable(
    value: Boolean,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role = Role.Switch,
    onValueChange: (Boolean) -> Unit,
): Modifier = this
    .toggleable(
        value = value,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onValueChange = onValueChange,
    )
    .handCursor(enabled)

fun Modifier.hideoutSelectable(
    selected: Boolean,
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
    role: Role = Role.Tab,
    onClick: () -> Unit,
): Modifier = this
    .selectable(
        selected = selected,
        interactionSource = interactionSource,
        indication = null,
        enabled = enabled,
        role = role,
        onClick = onClick,
    )
    .handCursor(enabled)

fun Modifier.handCursor(enabled: Boolean = true): Modifier =
    if (enabled) pointerHoverIcon(PointerIcon.Hand) else this

class InteractionStates internal constructor(
    hovered: State<Boolean>,
    pressed: State<Boolean>,
    focusVisible: State<Boolean>,
) {
    val hovered by hovered
    val pressed by pressed
    val focusVisible by focusVisible
}

@Composable
fun InteractionSource.collectInteractionStates(): InteractionStates {
    val hovered = collectIsHoveredAsState()
    val pressed = collectIsPressedAsState()
    val focusVisible = collectIsFocusVisibleAsState()
    return remember(this) { InteractionStates(hovered, pressed, focusVisible) }
}

/** Focused *and* reached by keyboard, like CSS `:focus-visible`. */
@Composable
fun InteractionSource.collectIsFocusVisibleAsState(): State<Boolean> {
    val focused = collectIsFocusedAsState()
    val visibility = LocalFocusVisibility.current
    return remember(focused, visibility) { derivedStateOf { focused.value && visibility.keyboardInUse } }
}
