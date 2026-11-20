package gg.hideouthq.feature.library.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.library_title
import gg.hideouthq.resources.problem_interrupted
import gg.hideouthq.resources.profile_library_summary
import gg.hideouthq.resources.profile_library_summary_none_on
import gg.hideouthq.resources.profile_library_summary_note
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LibraryHeader(
    enabledCount: Int,
    totalCount: Int,
    enabledSizeBytes: Long,
    interrupted: String?,
    profileName: String,
    note: String? = null,
    noteWarns: Boolean = false,
    noteAction: String? = null,
    onNoteAction: () -> Unit = {},
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = HideoutDimens.Library.HeaderHorizontalPadding,
                end = HideoutDimens.Library.HeaderHorizontalPadding,
                top = HideoutDimens.Library.HeaderTopPadding,
                bottom = HideoutDimens.Library.HeaderBottomPadding,
            ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.ToolbarGap), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.library_title),
                style = HideoutTheme.typography.headlinePage,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            trailing?.invoke()
        }
        val summary = if (totalCount == 0) {
            Summary("", note = note, warns = noteWarns, action = noteAction)
        } else {
            val line = if (enabledCount == 0) stringResource(Res.string.profile_library_summary_none_on, enabledCount, totalCount, NamePlace)
                else stringResource(Res.string.profile_library_summary, enabledCount, totalCount, NamePlace, ModLabels.size(enabledSizeBytes))
            val (before, after) = line.split(NamePlace)
            Summary(before, profileName, after, note, noteWarns, noteAction)
        }
        AnimatedContent(
            targetState = summary,
            transitionSpec = { fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.exitFade()) },
            modifier = Modifier.padding(top = HideoutDimens.Library.SummaryTopPadding),
            label = "librarySummary",
        ) { shown ->
            val note = shown.note?.let { if (shown.before.isEmpty()) it else stringResource(Res.string.profile_library_summary_note, it) }.orEmpty()
            val style = HideoutTheme.typography.captionSmall
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f, fill = false).clearAndSetSemantics { text = AnnotatedString(shown.before + shown.name + shown.after + note) }) {
                    Text(shown.before, style = style, color = scheme.outline, maxLines = 1)
                    Text(shown.name, Modifier.widthIn(max = HideoutDimens.Library.SummaryNameMax), style = style, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shown.after, style = style, color = scheme.outline, maxLines = 1)
                    if (shown.note != null) Text(note, Modifier.weight(1f, fill = false), style = style, color = if (shown.warns) scheme.tertiary else scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                shown.action?.let {
                    Text(ModLabels.Separator, style = style, color = scheme.onSurfaceVariant, maxLines = 1)
                    NoteLink(it, onNoteAction)
                }
            }
        }
        AnimatedVisibility(visible = interrupted != null, enter = fadeIn(motion.effectsDefault()), exit = fadeOut(motion.exitFade())) {
            Row(Modifier.padding(top = HideoutDimens.Library.NoteTopPadding), horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Library.NoteGap)) {
                HideoutIcon(HideoutIcons.Warning, contentDescription = null, tint = scheme.tertiary)
                Text(stringResource(Res.string.problem_interrupted), style = HideoutTheme.typography.notice, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NoteLink(text: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val scheme = MaterialTheme.colorScheme
    Text(
        text,
        Modifier.focusRing(states.focusVisible, HideoutShapes.Thumb).hideoutClickable(source, onClick = onClick),
        style = HideoutTheme.typography.captionSmall.copy(textDecoration = if (states.hovered) TextDecoration.Underline else null),
        color = scheme.primary,
        maxLines = 1,
    )
}

private data class Summary(val before: String, val name: String = "", val after: String = "", val note: String? = null, val warns: Boolean = false, val action: String? = null)

private const val NamePlace = "\u0000"
