package gg.hideouthq.feature.autoexec.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.autoexec.CommandCategory
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.designsystem.theme.cssLineHeight
import gg.hideouthq.core.ui.NoteLine
import gg.hideouthq.core.ui.SavedNoteLine
import gg.hideouthq.feature.autoexec.AutoexecOpen
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.autoexec_category_audio
import gg.hideouthq.resources.autoexec_category_hud
import gg.hideouthq.resources.autoexec_category_mouse
import gg.hideouthq.resources.autoexec_category_network
import gg.hideouthq.resources.autoexec_category_performance
import gg.hideouthq.resources.autoexec_editor_failed
import gg.hideouthq.resources.folder_open_failed
import gg.hideouthq.resources.notice_dismiss
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun GroupLabel(text: String, count: String, start: Dp, top: Dp) {
    val type = autoexecType()
    Row(Modifier.height(34.dp).padding(start = start, top = top), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = type.group, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(count, style = type.note.copy(fontWeight = HideoutWeights.Medium), color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
internal fun PageNote(note: SavedNote, notOpened: AutoexecOpen?, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (notOpened == null) return SavedNoteLine(note, onRetry, modifier)
    val text = stringResource(if (notOpened == AutoexecOpen.Editor) Res.string.autoexec_editor_failed else Res.string.folder_open_failed)
    NoteLine(HideoutTheme.colors.caution, text, modifier) { LinkButton(stringResource(Res.string.notice_dismiss), onDismiss) }
}

@Composable
internal fun categoryName(category: CommandCategory): String = stringResource(
    when (category) {
        CommandCategory.Performance -> Res.string.autoexec_category_performance
        CommandCategory.HudAndUi -> Res.string.autoexec_category_hud
        CommandCategory.Network -> Res.string.autoexec_category_network
        CommandCategory.Audio -> Res.string.autoexec_category_audio
        CommandCategory.Mouse -> Res.string.autoexec_category_mouse
    },
)

@Immutable
internal class AutoexecType(
    val rowTitle: TextStyle,
    val group: TextStyle,
    val note: TextStyle,
    val small: TextStyle,
    val tag: TextStyle,
    val command: TextStyle,
    val code: TextStyle,
    val badge: TextStyle,
)

@Composable
internal fun autoexecType(): AutoexecType {
    val type = HideoutTheme.typography
    return remember(type) {
        AutoexecType(
            rowTitle = type.titleRow.copy(fontWeight = HideoutWeights.Title),
            group = type.subtitle.copy(fontWeight = HideoutWeights.Title),
            note = type.subtitle,
            small = type.caption.copy(lineHeight = cssLineHeight(12.sp, 1.45f)),
            tag = type.captionCompact.copy(fontWeight = HideoutWeights.Title),
            command = type.monoCode.copy(fontSize = 13.5.sp, lineHeight = cssLineHeight(13.5.sp, 1.35f)),
            code = type.monoCode.copy(lineHeight = cssLineHeight(13.sp, 1.35f)),
            badge = type.mono,
        )
    }
}
