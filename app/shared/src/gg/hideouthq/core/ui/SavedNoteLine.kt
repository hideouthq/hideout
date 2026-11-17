package gg.hideouthq.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.designsystem.component.LinkButton
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.autoexec_retry
import gg.hideouthq.resources.autoexec_saved_next_launch
import gg.hideouthq.resources.autoexec_saved_next_start
import gg.hideouthq.resources.autoexec_yours_unsaved
import gg.hideouthq.resources.crosshairs_saved_game_folder
import gg.hideouthq.resources.crosshairs_saved_waiting
import org.jetbrains.compose.resources.stringResource

@Composable
fun SavedNoteLine(note: SavedNote, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = HideoutTheme.colors
    when (note) {
        SavedNote.NextLaunch -> NoteLine(colors.status, stringResource(Res.string.autoexec_saved_next_launch), modifier)
        SavedNote.NextStart -> NoteLine(colors.status, stringResource(Res.string.autoexec_saved_next_start), modifier)
        SavedNote.Waiting -> NoteLine(colors.caution, stringResource(Res.string.crosshairs_saved_waiting), modifier)
        SavedNote.GameFolderOnly -> NoteLine(colors.caution, stringResource(Res.string.crosshairs_saved_game_folder), modifier)
        is SavedNote.Failed -> {
            val text = when (note) {
                is SavedNote.Failed.File -> LibraryLabels.problem(note.problem)
                SavedNote.Failed.Yours -> stringResource(Res.string.autoexec_yours_unsaved)
            }
            NoteLine(colors.caution, text, modifier) { LinkButton(stringResource(Res.string.autoexec_retry), onRetry) }
        }
    }
}

@Composable
fun NoteLine(dot: Color, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Text(text, Modifier.weight(1f, fill = false), style = HideoutTheme.typography.subtitle, color = MaterialTheme.colorScheme.outline, maxLines = 2, overflow = TextOverflow.Ellipsis)
        action?.invoke()
    }
}
