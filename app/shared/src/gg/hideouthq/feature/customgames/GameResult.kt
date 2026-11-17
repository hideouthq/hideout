package gg.hideouthq.feature.customgames

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.customgames.LibraryEntry
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.feature.shell.DiscordInvite
import gg.hideouthq.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LastMatchCard(match: LastMatch, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    CgCard(modifier) {
        CardHeading(HideoutIcons.Trophy, stringResource(Res.string.cg_last_match)) {
            match.note?.let { Text(it, style = HideoutTheme.typography.captionSmall.copy(fontWeight = HideoutWeights.Label), color = scheme.outline) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            match.rows.forEach { row -> MatchLine(row) }
        }
    }
}

@Composable
private fun MatchLine(row: MatchRow) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val person = row.person
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(person.name, person.host)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(person.name ?: stringResource(Res.string.cg_someone), style = type.bodyStrong, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (person.host) HostTag()
                if (row.finish == Finish.Won) WonTag()
            }
            if (person.you) Text(stringResource(Res.string.cg_you), Modifier.padding(top = 1.dp), style = type.subtitle, color = scheme.outline)
        }
        Text(
            row.score.toString(),
            style = type.titleSection.copy(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = HideoutWeights.Heavy, letterSpacing = (-0.01).em, fontFeatureSettings = "tnum"),
            color = if (row.finish == Finish.Behind) scheme.outline else scheme.onSurface,
        )
    }
}

@Composable
private fun WonTag() {
    val sage = MaterialTheme.colorScheme.secondary
    Box(Modifier.padding(start = 8.dp).heightIn(min = 22.dp).background(sage.copy(alpha = 0.18f), HideoutShapes.Full).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.cg_tag_won), style = HideoutTheme.typography.captionCompact.copy(fontWeight = HideoutWeights.Heavy), color = sage)
    }
}

@Composable
internal fun PlaySomethingElseCard(modes: List<LibraryEntry.Listed>, onOpenMode: (String) -> Unit, onLibrary: () -> Unit, modifier: Modifier = Modifier) {
    CgCard(modifier) {
        CardHeading(HideoutIcons.Gamepad, stringResource(Res.string.cg_play_something_else))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            modes.forEach { entry -> OtherModeLine(entry, { onOpenMode(entry.key) }) }
        }
        HideoutButton(
            stringResource(Res.string.cg_all_custom_games), onLibrary, Modifier.offset(x = (-12).dp, y = (-4).dp),
            variant = ButtonVariant.Text, size = ButtonSize.Small,
        )
    }
}

@Composable
private fun OtherModeLine(entry: LibraryEntry.Listed, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val listing = entry.mode.listing
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val fill by animateColorAsState(if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), HideoutTheme.motion.effectsDefault(), label = "otherMode")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(fill).hideoutClickable(source, onClick = onOpen).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeTile(entry.mode.id.value, listing.art.tile, 40.dp, 12.dp)
        Column(Modifier.weight(1f)) {
            Text(listing.name, style = type.bodyStrong, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(playersText(listing), minutesText(listing, about = false)).joinToString(" · "), style = type.caption, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HideoutIcon(HideoutIcons.ChevronRight, null, size = 18.dp, tint = scheme.outline)
    }
}

@Composable
internal fun WhatHappenedCard(trace: StartTrace, face: ModeFace, modifier: Modifier = Modifier) {
    val mode = face.name()
    CgCard(modifier) {
        CardHeading(HideoutIcons.Activity, stringResource(Res.string.cg_what_happened))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            trace.worked.forEach { step ->
                val (title, detail) = when (step) {
                    HostStep.GetMode -> stringResource(Res.string.cg_trace_mode_worked, mode, face.version.orEmpty()) to
                        (trace.download?.let { stringResource(Res.string.cg_trace_mode_worked_sized, megabytes(it)) } ?: stringResource(Res.string.cg_trace_mode_worked_body))
                    HostStep.StartServer -> stringResource(Res.string.cg_trace_server_worked) to stringResource(Res.string.cg_trace_server_worked_body)
                    HostStep.OpenToFriends -> stringResource(Res.string.cg_trace_friends_worked) to stringResource(Res.string.cg_trace_friends_worked_body)
                }
                TraceLine(title, detail, worked = true)
            }
            val (title, detail) = when (trace.failed) {
                HostStep.GetMode -> stringResource(Res.string.cg_trace_mode_failed, mode) to stringResource(Res.string.cg_trace_mode_failed_body)
                HostStep.StartServer -> stringResource(Res.string.cg_trace_server_failed) to stringResource(Res.string.cg_trace_server_failed_body)
                HostStep.OpenToFriends -> stringResource(Res.string.cg_trace_friends_failed) to stringResource(Res.string.cg_trace_friends_failed_body)
            }
            TraceLine(title, detail, worked = false)
        }
    }
}

@Composable
private fun TraceLine(title: String, detail: String, worked: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val color = if (worked) scheme.secondary else scheme.error
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).background(color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            HideoutIcon(if (worked) HideoutIcons.CheckBold else HideoutIcons.Close, null, size = 14.dp, tint = color)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = type.bodyStrong, color = scheme.onSurface)
            Text(detail, Modifier.padding(top = 2.dp), style = type.subtitle, color = scheme.outline)
        }
    }
}

@Composable
internal fun StillNotWorkingCard(report: String, copy: CopyAction, modifier: Modifier = Modifier) {
    val uris = LocalUriHandler.current
    CgCard(modifier) {
        CardHeading(HideoutIcons.Discord, stringResource(Res.string.cg_still_not_working))
        Text(stringResource(Res.string.cg_still_not_working_body), style = HideoutTheme.typography.subtitle.copy(lineHeight = 18.sp), color = MaterialTheme.colorScheme.outline)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            HideoutButton(
                stringResource(Res.string.cg_copy_details_short), { copy(report, Res.string.cg_toast_details_copied) },
                variant = ButtonVariant.Tonal, leadingIcon = HideoutIcons.Copy, leadingIconSize = 16.dp,
            )
            HideoutButton(
                stringResource(Res.string.cg_open_discord), { uris.openUri(DiscordInvite) },
                variant = ButtonVariant.Text, trailingIcon = HideoutIcons.ArrowUpRight, trailingIconSize = 14.dp,
            )
        }
    }
}

@Composable
internal fun NobodyWaitingNote(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    CgCard(modifier, padding = 16.dp, radius = 20.dp, gap = 0.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBox(HideoutIcons.Link, Tone.Starting, 34.dp, 11.dp, 18.dp)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.cg_nobody_waiting_title), Modifier.padding(bottom = 3.dp), style = type.body.copy(fontWeight = HideoutWeights.Title), color = scheme.onSurface)
                Text(stringResource(Res.string.cg_nobody_waiting_body), style = type.subtitle.copy(lineHeight = 18.75.sp), color = scheme.onSurfaceVariant)
            }
        }
    }
}
