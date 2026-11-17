package gg.hideouthq.feature.autoexec.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.autoexec.FreeKind
import gg.hideouthq.core.autoexec.LockOwner
import gg.hideouthq.core.autoexec.LockedLines
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.autoexec.classify
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CodeEditor
import gg.hideouthq.core.designsystem.component.CodeRole
import gg.hideouthq.core.designsystem.component.CodeSpan
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.LockedRun
import gg.hideouthq.core.designsystem.component.PillButtonPadding
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.feature.autoexec.AutoexecPageActions
import gg.hideouthq.feature.autoexec.AutoexecOpen
import gg.hideouthq.feature.imports.managerName
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.autoexec_editor
import gg.hideouthq.resources.autoexec_managed_tool
import gg.hideouthq.resources.autoexec_open_in_editor
import gg.hideouthq.resources.autoexec_show_in_folder
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun AutoexecText(editor: TextFieldState, locked: List<LockedLines>, note: SavedNote, notOpened: AutoexecOpen?, actions: AutoexecPageActions) {
    val runs = locked.map { run ->
        when (val owner = run.owner) {
            LockOwner.Header -> LockedRun(run.lines, pinned = true)
            is LockOwner.Tool -> LockedRun(run.lines, stringResource(Res.string.autoexec_managed_tool, managerName(owner.markers.manager)))
        }
    }
    Column {
        CodeEditor(editor, runs, ::highlight, stringResource(Res.string.autoexec_editor), Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 4.dp))
        Row(Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp, end = 6.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HideoutButton(
                stringResource(Res.string.autoexec_open_in_editor), actions::openInEditor,
                variant = ButtonVariant.Tonal, size = ButtonSize.Medium, leadingIcon = HideoutIcons.Rename, leadingIconSize = 18.dp, contentPadding = PillButtonPadding,
            )
            HideoutButton(
                stringResource(Res.string.autoexec_show_in_folder), actions::showInFolder,
                variant = ButtonVariant.Tonal, size = ButtonSize.Medium, leadingIcon = HideoutIcons.Folder, leadingIconSize = 18.dp, contentPadding = PillButtonPadding,
            )
            Spacer(Modifier.weight(1f))
            PageNote(note, notOpened, actions::retry, actions::dismissNotOpened, Modifier.padding(end = 8.dp))
        }
    }
}

private fun highlight(line: String): List<CodeSpan> {
    val start = line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return emptyList()
    val command = (classify(line) as? FreeKind.Command)?.text
        ?: return listOf(CodeSpan(start until line.length, if (line.startsWith("//", start)) CodeRole.Comment else CodeRole.Command))
    val name = command.indent.length + command.marker.length + command.name.length
    val value = name + command.separator.length
    val tail = value + command.value.length
    val comment = line.indexOf("//", tail).takeIf { it >= 0 }
    return buildList {
        add(CodeSpan(start until name, if (command.on) CodeRole.Command else CodeRole.Comment))
        if (command.value.isNotEmpty()) add(CodeSpan(value until tail, CodeRole.Value))
        if (comment != null) add(CodeSpan(comment until line.length, CodeRole.Comment))
    }
}
