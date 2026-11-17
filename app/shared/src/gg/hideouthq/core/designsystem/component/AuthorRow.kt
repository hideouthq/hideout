package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme

@Composable
fun AuthorRow(
    name: String,
    role: String?,
    index: Int,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Author.Gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AuthorInitial(name, index, Modifier.size(HideoutDimens.Author.Avatar))
        Column {
            Text(name, style = HideoutTheme.typography.authorName, color = scheme.onSurface, maxLines = 1)
            if (role != null) Text(role, style = HideoutTheme.typography.authorRole, color = scheme.outline, maxLines = 1)
        }
    }
}

@Composable
internal fun AuthorInitial(name: String, index: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier.background(if (index % 2 == 0) scheme.primaryContainer else scheme.tertiaryContainer, HideoutShapes.Full), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), style = HideoutTheme.typography.initialsAuthor, color = if (index % 2 == 0) scheme.primary else scheme.tertiary)
    }
}
