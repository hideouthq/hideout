package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.AppPopup
import androidx.compose.foundation.shape.RoundedCornerShape

sealed interface MenuEntry {
    /** A [danger] item reads in the error colour, for an action that removes something. [leading] is drawn in place of [icon]. */
    data class Item(
        val label: String,
        val icon: ImageVector?,
        val onClick: () -> Unit,
        val danger: Boolean = false,
        val enabled: Boolean = true,
        val hint: String? = null,
        val leading: (@Composable () -> Unit)? = null,
    ) : MenuEntry

    data object Separator : MenuEntry

    data class Content(val content: @Composable () -> Unit) : MenuEntry
}

enum class MenuAnchor { BelowEnd, Beside }

/**
 * A menu in its own focusable popup, right-aligned under the element it is composed in and flipped above it when the
 * window has no room below. It takes the keyboard while open: Up and Down move between items, Enter activates one, Esc
 * closes. A click anywhere else closes it. [onClose] says whether a key closed it, so focus can go back where it was.
 * A [header], such as a search field, stays above the entries and takes the focus first; entries taller than the
 * menu's maximum scroll, with the overlay scrollbar, and the last row in view is cut so there is plainly more.
 */
@Composable
fun HideoutMenu(
    entries: List<MenuEntry>,
    onClose: (byKey: Boolean) -> Unit,
    label: String,
    anchor: MenuAnchor = MenuAnchor.BelowEnd,
    header: (@Composable (focus: FocusRequester) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val gap = with(density) { HideoutDimens.Menu.AnchorGap.roundToPx() }
    val margin = with(density) { HideoutDimens.Notice.FloatingMargin.roundToPx() }
    val placement = remember(gap, margin, anchor) {
        if (anchor == MenuAnchor.Beside) Beside(margin) else BelowEnd(gap, margin)
    }
    AppPopup(
        popupPositionProvider = placement,
        onDismissRequest = { onClose(false) },
        properties = PopupProperties(focusable = true),
    ) {
        val motion = HideoutTheme.motion
        val shown = remember { MutableTransitionState(false).apply { targetState = true } }
        val first = remember { FocusRequester() }
        val headerFocus = remember { FocusRequester() }
        val focus = LocalFocusManager.current
        LaunchedEffect(Unit) { (if (header != null) headerFocus else first).requestFocus() }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(motion.effectsDefault()) + scaleIn(motion.spatialFast(), HideoutMotion.EnterScaleModel, TransformOrigin(1f, 0.5f)),
        ) {
            Column(
                modifier = Modifier
                    .width(HideoutDimens.Menu.Width)
                    .dropShadow(MenuShape, Shadow(radius = HideoutDimens.Menu.ShadowBlur, color = HideoutTheme.colors.dialogShadow, offset = DpOffset(0.dp, HideoutDimens.Menu.ShadowOffsetY)))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, MenuShape)
                    .padding(HideoutDimens.Menu.Padding)
                    .semantics {
                        role = Role.DropdownList
                        contentDescription = label
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> onClose(true)
                            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    },
                verticalArrangement = Arrangement.spacedBy(HideoutDimens.Menu.ItemGap),
            ) {
                header?.invoke(headerFocus)
                val scroll = rememberScrollState()
                Box(Modifier.heightIn(max = HideoutDimens.Menu.MaxListHeight)) {
                    Column(Modifier.verticalScroll(scroll).focusRequester(first), verticalArrangement = Arrangement.spacedBy(HideoutDimens.Menu.ItemGap)) {
                        entries.forEach { entry ->
                            when (entry) {
                                is MenuEntry.Content -> entry.content()
                                MenuEntry.Separator -> Box(
                                    Modifier
                                        .padding(horizontal = HideoutDimens.Menu.SeparatorHorizontal, vertical = HideoutDimens.Menu.SeparatorVertical)
                                        .fillMaxWidth()
                                        .height(HideoutDimens.Hairline)
                                        .background(MaterialTheme.colorScheme.outlineVariant),
                                )
                                is MenuEntry.Item -> MenuItem(entry) { byKey ->
                                    onClose(byKey)
                                    entry.onClick()
                                }
                            }
                        }
                    }
                    if (scroll.maxValue > 0) {
                        Box(Modifier.matchParentSize()) { OverlayScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.TopEnd)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(item: MenuEntry.Item, onActivate: (byKey: Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    var byKey by remember { mutableStateOf(false) }
    val content = if (item.danger) scheme.error else scheme.onSurface
    val icon = if (item.danger) scheme.error else scheme.onSurfaceVariant
    val hover = if (item.danger) scheme.errorContainer else scheme.surfaceContainerHighest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HideoutDimens.Menu.ItemHeight)
            .onPreviewKeyEvent { event ->
                byKey = event.key == Key.Enter || event.key == Key.NumPadEnter || event.key == Key.Spacebar
                false
            }
            .background(if (states.hovered || states.focusVisible) hover else hover.copy(alpha = 0f), ItemShape)
            .focusRing(states.focusVisible, ItemShape)
            .hideoutClickable(source, enabled = item.enabled) { onActivate(byKey) }
            .alpha(if (item.enabled) 1f else HideoutMotion.DimmedAlpha)
            .padding(horizontal = HideoutDimens.Menu.ItemHorizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Menu.ItemContentGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            item.leading != null -> item.leading.invoke()
            item.icon != null -> HideoutIcon(item.icon, contentDescription = null, tint = icon, modifier = Modifier.size(HideoutDimens.Menu.ItemIcon))
        }
        Text(item.label, modifier = Modifier.weight(1f), style = HideoutTheme.typography.labelMenuItem, color = content, maxLines = 1)
        item.hint?.let { Text(it, style = HideoutTheme.typography.caption, color = scheme.outline, maxLines = 1) }
    }
}

private val MenuShape = RoundedCornerShape(HideoutDimens.Menu.Radius)

private val ItemShape = RoundedCornerShape(HideoutDimens.Menu.ItemRadius)

private class Beside(private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
        (anchorBounds.right - margin / 2).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        anchorBounds.top.coerceIn(0, (windowSize.height - popupContentSize.height - margin).coerceAtLeast(0)),
    )
}

private class BelowEnd(private val gap: Int, private val margin: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val below = anchorBounds.bottom + gap
        val above = below + popupContentSize.height > windowSize.height - margin && anchorBounds.top - gap - popupContentSize.height >= 0
        return IntOffset(x, if (above) anchorBounds.top - gap - popupContentSize.height else below)
    }
}
