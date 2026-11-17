package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutSelectable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

/** One tab. One that needs [attention] shows a small error dot, described to assistive technology as [attentionLabel]. */
data class TabItem<T>(val key: T, val label: String, val attention: Boolean = false, val attentionLabel: String? = null)

@Composable
fun <T : Any> HideoutTabs(
    tabs: List<TabItem<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val indicator = rememberSelectionIndicatorState(selected)
    val hairline = scheme.outlineVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val y = size.height - HideoutDimens.Hairline.toPx() / 2
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), HideoutDimens.Hairline.toPx())
            }
            .selectionIndicator(indicator, scheme.primary, HideoutShapes.TabIndicator, inFront = true)
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Tabs.Gap),
    ) {
        tabs.forEach { tab ->
            Tab(tab, tab.key == selected, { onSelect(tab.key) }, indicator)
        }
    }
}

@Composable
private fun <T : Any> Tab(tab: TabItem<T>, selected: Boolean, onClick: () -> Unit, indicator: SelectionIndicatorState) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val background by animateColorAsState(
        targetValue = if (states.hovered) scheme.surfaceContainer else scheme.surfaceContainer.copy(alpha = 0f),
        animationSpec = motion.effectsFast(),
        label = "tabBackground",
    )
    val text by animateColorAsState(
        targetValue = if (selected || states.hovered) scheme.onSurface else scheme.outline,
        animationSpec = motion.effectsFast(),
        label = "tabText",
    )
    Box(
        modifier = Modifier
            .height(HideoutDimens.Tabs.Height)
            .background(background, HideoutShapes.Tab)
            .focusRing(states.focusVisible, HideoutShapes.Tab)
            .hideoutSelectable(selected, source, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HideoutDimens.Tabs.HorizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Tabs.LabelGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(tab.label, style = HideoutTheme.typography.bodyStrong, color = text, maxLines = 1)
            if (tab.attention) AttentionDot(scheme.error, tab.attentionLabel)
        }
        Box(
            modifier = Modifier.matchParentSize().padding(horizontal = HideoutDimens.Tabs.IndicatorInset),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxWidth().height(HideoutDimens.Tabs.Indicator).indicatorTarget(indicator, tab.key))
        }
    }
}

@Composable
private fun AttentionDot(color: Color, label: String?) {
    Box(
        Modifier
            .size(HideoutDimens.Tabs.AttentionDot)
            .background(color, HideoutShapes.Full)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    )
}

/**
 * The content of the [selected] tab. A change cross-fades while the content shifts 24dp in the direction of travel,
 * which [order] gives: a tab later in the order arrives from the end.
 */
@Composable
fun <T : Any> TabContent(
    selected: T,
    order: (T) -> Int,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val motion = HideoutTheme.motion
    val distance = with(LocalDensity.current) { (if (motion.reduced) 0.dp else HideoutDimens.Tabs.ContentShift).roundToPx() }
    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        transitionSpec = {
            val direction = if (order(targetState) >= order(initialState)) 1 else -1
            (fadeIn(motion.enterFade()) + slideInHorizontally(motion.spatialDefault()) { direction * distance }) togetherWith
                (fadeOut(motion.exitFade()) + slideOutHorizontally(motion.spatialDefault()) { -direction * distance })
        },
        label = "tabContent",
    ) { tab -> content(tab) }
}
