package gg.hideouthq.feature.autoexec.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.data.SavedNote
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutSwitch
import gg.hideouthq.core.designsystem.component.PillButtonPadding
import gg.hideouthq.core.designsystem.component.TextInput
import gg.hideouthq.core.designsystem.component.TextInputSize
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.feature.autoexec.AutoexecPageActions
import gg.hideouthq.feature.autoexec.AutoexecFile
import gg.hideouthq.feature.autoexec.AutoexecOpen
import gg.hideouthq.feature.autoexec.CommandItem
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.autoexec_add
import gg.hideouthq.resources.autoexec_add_label
import gg.hideouthq.resources.autoexec_add_placeholder
import gg.hideouthq.resources.autoexec_no_commands_message
import gg.hideouthq.resources.autoexec_no_commands_title
import gg.hideouthq.resources.autoexec_off
import gg.hideouthq.resources.autoexec_on_off
import gg.hideouthq.resources.autoexec_remove
import gg.hideouthq.resources.autoexec_run
import gg.hideouthq.resources.autoexec_value_for
import gg.hideouthq.resources.autoexec_your_commands
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun CommandList(file: AutoexecFile.Ready, adding: String, note: SavedNote, notOpened: AutoexecOpen?, actions: AutoexecPageActions) {
    Column {
        if (file.commands.isEmpty()) {
            EmptyState(HideoutIcons.Autoexec, stringResource(Res.string.autoexec_no_commands_title), stringResource(Res.string.autoexec_no_commands_message))
        } else {
            GroupLabel(stringResource(Res.string.autoexec_your_commands), stringResource(Res.string.autoexec_on_off, file.on, file.off), start = 14.dp, top = 6.dp)
            file.commands.forEach { item -> key(item.row.ref.name.lowercase(), item.row.ref.occurrence) { CommandRowItem(item, actions) } }
        }
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextInput(
                adding, actions::setAdding, stringResource(Res.string.autoexec_add_label), Modifier.weight(1f),
                placeholder = stringResource(Res.string.autoexec_add_placeholder), onSubmit = actions::add,
            )
            HideoutButton(stringResource(Res.string.autoexec_add), actions::add, variant = ButtonVariant.Tonal, size = ButtonSize.Medium, leadingIcon = HideoutIcons.Plus, leadingIconSize = 18.dp, contentPadding = PillButtonPadding)
        }
        if (note is SavedNote.Failed || notOpened != null) PageNote(note, notOpened, actions::retry, actions::dismissNotOpened, Modifier.padding(start = 12.dp, top = 6.dp, end = 8.dp))
    }
}

@Composable
private fun CommandRowItem(item: CommandItem, actions: AutoexecPageActions) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val type = autoexecType()
    val row = item.row
    val name = row.ref.name
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var removeFocused by remember { mutableStateOf(false) }
    val fill by animateColorAsState(if (hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), motion.effectsFast(), label = "commandRow")
    val removeAlpha by animateFloatAsState(if (hovered || removeFocused) 1f else 0f, motion.effectsFast(), label = "commandRemove")
    val faded = if (row.on) 1f else OffAlpha
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(fill).hoverable(source)
            .padding(start = 14.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(Modifier.alpha(faded), verticalAlignment = Alignment.Bottom) {
                Text(name, Modifier.weight(1f, fill = false), style = type.command, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!row.on) Text(" " + stringResource(Res.string.autoexec_off), style = type.tag, color = scheme.outline, maxLines = 1)
            }
            item.description?.let { Text(it, Modifier.padding(top = 2.dp), style = type.note, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        TextInput(
            row.value, { actions.setValue(row.ref, it) }, stringResource(Res.string.autoexec_value_for, name), Modifier.alpha(faded).width(HideoutDimens.Field.ValueWidth),
            size = TextInputSize.Compact, textAlign = TextAlign.End,
        )
        Box(Modifier.width(44.dp)) {
            if (row.canTurnOff) HideoutSwitch(row.on, { actions.setOn(row.ref, it) }, stringResource(Res.string.autoexec_run, name))
        }
        HideoutIconButton(
            HideoutIcons.Trash, stringResource(Res.string.autoexec_remove, name), { actions.remove(row.ref) },
            Modifier.onFocusChanged { removeFocused = it.isFocused }.graphicsLayer { alpha = removeAlpha }, size = 32.dp,
        )
    }
}

private const val OffAlpha = 0.45f
