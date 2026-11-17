package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun AuthorStack(
    names: List<String>,
    moreLabel: String?,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    val avatar = HideoutDimens.Author.Avatar
    val overlap = HideoutDimens.AuthorStack.Overlap
    Row(
        modifier = modifier
            .focusRing(states.focusVisible, HideoutShapes.Full)
            .background(if (states.hovered) scheme.surfaceContainer else scheme.surface, HideoutShapes.Full)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .hideoutClickable(source, onClick = onClick)
            .padding(HideoutDimens.AuthorStack.Padding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.AuthorStack.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(avatar + (avatar - overlap) * (names.size - 1).coerceAtLeast(0), avatar)) {
            names.forEachIndexed { index, name ->
                AuthorInitial(
                    name, index,
                    Modifier
                        .zIndex((names.size - index).toFloat())
                        .offset(x = (avatar - overlap) * index)
                        .size(avatar)
                        .border(HideoutDimens.AuthorStack.Ring, scheme.surface, HideoutShapes.Full)
                        .padding(HideoutDimens.AuthorStack.Ring),
                )
            }
        }
        if (moreLabel != null) Text(moreLabel, style = HideoutTheme.typography.authorName, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}
