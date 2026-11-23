package gg.hideouthq.feature.customgames

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.customgames.DisplayName
import gg.hideouthq.core.customgames.LinkPath
import gg.hideouthq.core.customgames.Transfer
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutSnackbar
import gg.hideouthq.core.designsystem.component.StatusCircle
import gg.hideouthq.core.designsystem.component.StepStatus
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.AlertModal
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.copyText
import gg.hideouthq.resources.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToLong

/** [value], or while it is null the last value it had, so a view that is leaving keeps drawing what it showed. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

data class ToneColors(val content: Color, val container: Color)

@Composable
fun statusLabel(status: Status): String = stringResource(
    when (status) {
        Status.CleaningUp -> Res.string.cg_status_cleaning_up
        Status.CleanupFailed -> Res.string.cg_status_cleanup_failed
        Status.Starting -> Res.string.cg_status_starting
        Status.RestartNeeded -> Res.string.cg_status_restart_needed
        Status.Restarting -> Res.string.cg_status_restarting
        Status.Joining -> Res.string.cg_status_joining
        Status.Ready -> Res.string.cg_status_ready
        Status.InMatch -> Res.string.cg_status_in_match
        Status.LostConnection -> Res.string.cg_status_lost_connection
        Status.Ending -> Res.string.cg_status_ending
        Status.Leaving -> Res.string.cg_status_leaving
        Status.CouldNotStart -> Res.string.cg_status_could_not_start
        Status.Ended -> Res.string.cg_status_ended
    },
)

@Composable
fun animatedTone(tone: Tone): ToneColors {
    val scheme = MaterialTheme.colorScheme
    val extra = HideoutTheme.colors
    val target = when (tone) {
        Tone.Starting -> ToneColors(scheme.primary, scheme.primary.copy(alpha = 0.14f))
        Tone.Ready -> ToneColors(scheme.secondary, scheme.secondaryContainer)
        Tone.Match -> ToneColors(extra.match, extra.matchContainer)
        Tone.Warn -> ToneColors(extra.caution, extra.cautionContainer)
        Tone.Fail -> ToneColors(scheme.error, extra.failContainer)
        Tone.Ended -> ToneColors(scheme.outline, scheme.surfaceContainerHigh)
    }
    val spec = HideoutTheme.motion.effectsDefault<Color>()
    val content by animateColorAsState(target.content, spec, label = "toneContent")
    val container by animateColorAsState(target.container, spec, label = "toneContainer")
    return ToneColors(content, container)
}

@Composable
fun StatusPill(status: Status) {
    val colors = animatedTone(status.tone)
    val motion = HideoutTheme.motion
    Row(
        Modifier.heightIn(min = 32.dp).background(colors.container, HideoutShapes.Full).padding(start = 11.dp, end = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status.busy) Spinner(colors.content, 14.dp) else StatusDot(colors.content)
        AnimatedContent(statusLabel(status), transitionSpec = { fadeIn(motion.enterFade()) togetherWith fadeOut(motion.exitFade()) }, label = "pillLabel") { label ->
            Text(label, style = HideoutTheme.typography.labelStatus.copy(fontWeight = HideoutWeights.Title), color = colors.content, maxLines = 1)
        }
    }
}

@Composable
fun StatusDot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
fun Spinner(color: Color, size: Dp, stroke: Dp = 2.dp) {
    val turn = if (HideoutTheme.motion.continuousMotion) {
        rememberInfiniteTransition(label = "spinner").animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "spinnerTurn").value
    } else 0f
    Canvas(Modifier.size(size).rotate(turn)) {
        val width = stroke.toPx()
        drawArc(color, 0f, 270f, false, Offset(width / 2, width / 2), Size(this.size.width - width, this.size.height - width), style = Stroke(width, cap = StrokeCap.Round))
    }
}

@Composable
fun ModeArtSlot(seed: String, url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(19.dp), glyph: Dp? = 38.dp, glyphAlignment: Alignment = Alignment.BottomStart) {
    Box(modifier) {
        CoverArt(seed, Modifier.fillMaxSize(), shape = shape, imageUrl = url)
        if (url == null && glyph != null) {
            val inset = if (glyphAlignment == Alignment.Center) 0.dp else glyph * 0.42f
            HideoutIcon(HideoutIcons.Gamepad, null, Modifier.align(glyphAlignment).padding(start = inset, bottom = inset), size = glyph, tint = Color.White.copy(alpha = 0.88f))
        }
    }
}

/** One transparent layer of a mode's art, drawn whole in [modifier]'s box, and nothing until it loads. */
@Composable
fun ArtLayer(url: String?, modifier: Modifier = Modifier) {
    val image = url?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Fit) } ?: return
    Image(image.painter, null, modifier.then(image.layoutModifier), contentScale = ContentScale.Fit)
}

@Composable
fun ModeTile(seed: String, url: String?, size: Dp, radius: Dp) {
    ModeArtSlot(seed, url, Modifier.size(size), RoundedCornerShape(radius), glyph = size * 0.5f, glyphAlignment = Alignment.Center)
}

@Composable
fun ModeTile(face: ModeFace?, size: Dp, radius: Dp) = ModeTile(face?.seed ?: SeedOfUnknown, face?.art?.tile, size, radius)

fun megabytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb < 10) ((mb * 10).roundToLong() / 10.0).toString().removeSuffix(".0") else mb.roundToLong().toString()
}

fun middleTruncate(text: String, keep: Int = 10): String =
    if (text.length <= keep * 2 + 1) text else text.take(keep) + "…" + text.takeLast(keep)

@Composable
fun ModeFace.name(): String = namePhrase.text()

@Composable
fun named(name: DisplayName?, named: StringResource, unnamed: StringResource): String = namedPhrase(name, named, unnamed).text()

@Composable
fun CgCard(modifier: Modifier = Modifier, padding: Dp = 20.dp, radius: Dp = 24.dp, gap: Dp = 14.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(radius)).padding(padding),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

@Composable
fun CardHeading(icon: ImageVector, title: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        HideoutIcon(icon, null, size = 18.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, Modifier.weight(1f), style = HideoutTheme.typography.titleCard.copy(fontSize = 15.sp, fontWeight = HideoutWeights.Title), color = MaterialTheme.colorScheme.onSurface)
        trailing()
    }
}

@Composable
fun IconBox(icon: ImageVector, tone: Tone, size: Dp, radius: Dp, iconSize: Dp) {
    val colors = animatedTone(tone)
    Box(Modifier.size(size).background(colors.container, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        HideoutIcon(icon, null, size = iconSize, tint = colors.content)
    }
}

@Composable
fun HintBox(icon: ImageVector, text: AnnotatedString) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HideoutIcon(icon, null, Modifier.padding(top = 1.dp), size = 17.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, lineHeight = 18.75.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HintBox(icon: ImageVector, text: String) = HintBox(icon, AnnotatedString(text))

@Composable
fun StepList(steps: List<StepView>, mode: Phrase, host: DisplayName?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        steps.forEach { step -> key(step.name) { StepRow(step, mode, host) } }
    }
}

@Composable
private fun StepRow(step: StepView, mode: Phrase, host: DisplayName?) {
    val scheme = MaterialTheme.colorScheme
    val motion = HideoutTheme.motion
    val type = HideoutTheme.typography
    val waiting = step.status == StepStatus.Waiting
    val title by animateColorAsState(if (waiting) scheme.outline else scheme.onSurface, motion.effectsDefault(), label = "stepTitle")
    val text = stepPhrase(step.name, mode, host).text()
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusCircle(step.status)
        Column(Modifier.weight(1f).animateContentSize(motion.spatialDefault())) {
            Text(text, style = type.body.copy(fontWeight = if (waiting) HideoutWeights.Medium else HideoutWeights.Label), color = title)
            step.note?.let { note -> StepNoteLine(note) }
        }
        (step.note as? StepNote.Downloading)?.let { DownloadBar(it.transfer, Modifier.weight(1f).widthIn(max = 220.dp)) }
    }
}

/** A step's line, the same for a host's start and a guest's join. [mode] names the mode the first step gets. */
fun stepPhrase(name: StepName, mode: Phrase, host: DisplayName?): Phrase = when (name) {
    StepName.GetMode -> phrase(if (host != null) Res.string.cg_step_get_mode_guest else Res.string.cg_step_get_mode, mode)
    StepName.StartServer -> phrase(Res.string.cg_step_start_server)
    StepName.OpenToFriends -> phrase(Res.string.cg_step_open_to_friends)
    StepName.Connect -> namedPhrase(host, Res.string.cg_step_connect, Res.string.cg_step_connect_friend)
    StepName.SendGameIn -> phrase(Res.string.cg_step_send_game_in)
}

fun notePhrase(note: StepNote): Phrase = when (note) {
    is StepNote.Downloading -> phrase(Res.string.cg_bytes_of, megabytes(note.transfer.done), megabytes(note.transfer.total))
    is StepNote.Installed -> phrase(Res.string.cg_note_installed, note.version)
    StepNote.SettingUpFiles -> phrase(Res.string.cg_note_setting_up)
    StepNote.Direct -> phrase(Res.string.cg_note_direct)
    StepNote.BackupRoute -> phrase(Res.string.cg_note_backup_route)
    StepNote.OpeningDeadlock -> phrase(Res.string.cg_note_opening_deadlock)
    StepNote.JoiningGame -> phrase(Res.string.cg_note_joining)
}

@Composable
private fun StepNoteLine(note: StepNote) {
    val text = notePhrase(note).text()
    Text(text, style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp), color = MaterialTheme.colorScheme.outline)
}

@Composable
fun DownloadBar(transfer: Transfer?, modifier: Modifier = Modifier) {
    val motion = HideoutTheme.motion
    val progress by animateFloatAsState(transfer?.fraction ?: 0f, motion.effectsDefault(), label = "downloadProgress")
    val wavelength = 46.dp
    LinearWavyProgressIndicator(
        progress = { progress },
        modifier = modifier.heightIn(min = 12.dp).clearAndSetSemantics {},
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        amplitude = { if (motion.continuousMotion && it in 0.01f..0.99f) 1f else 0f },
        wavelength = wavelength,
        waveSpeed = if (motion.continuousMotion) wavelength else 0.dp,
    )
}

@Composable
fun Avatar(name: String?, host: Boolean, size: Dp = 36.dp) {
    val color = if (host) HideoutTheme.colors.caution else HideoutTheme.colors.match
    Box(Modifier.size(size).background(color.copy(alpha = 0.22f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHigh), CircleShape), contentAlignment = Alignment.Center) {
        if (name.isNullOrBlank()) {
            HideoutIcon(HideoutIcons.Person, null, size = size * 0.45f, tint = color)
        } else {
            Text(name.take(1).uppercase(), style = HideoutTheme.typography.labelAvatar.copy(fontSize = (size.value * 0.39f).sp, fontWeight = HideoutWeights.Heavy), color = color)
        }
    }
}

@Composable
fun PlayersCard(view: PlayersView, modifier: Modifier = Modifier) {
    CgCard(modifier) {
        CardHeading(HideoutIcons.Users, stringResource(Res.string.cg_players_title)) {
            view.capacity?.let { capacity ->
                Text(stringResource(Res.string.cg_count_of, view.rows.size, capacity), style = HideoutTheme.typography.caption.copy(fontSize = 13.sp, fontWeight = HideoutWeights.Label), color = MaterialTheme.colorScheme.outline)
            }
        }
        Column(Modifier.animateContentSize(HideoutTheme.motion.spatialDefault()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            view.rows.forEachIndexed { index, row -> key(row.key) { PlayerLine(row, index) } }
            if (view.openSpots > 0) OpenSpots(view.openSpots)
        }
    }
}

@Composable
private fun PlayerLine(row: PlayerRow, index: Int) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val motion = HideoutTheme.motion
    val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(shown, enter = fadeIn(motion.enterFade(motion.staggerDelay(index))) + expandVertically(motion.spatialDefault())) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 60.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(18.dp)).padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(row.person.name, row.person.host)
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(row.person.name ?: stringResource(Res.string.cg_someone), style = type.body.copy(fontWeight = HideoutWeights.Label), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.person.host) HostTag()
                }
                val path = row.path?.let { stringResource(if (it == LinkPath.Relay) Res.string.cg_note_backup_route else Res.string.cg_note_direct) }
                val sub = listOfNotNull(stringResource(Res.string.cg_you).takeIf { row.person.you }, path).joinToString(" · ").ifEmpty { null }
                if (sub != null) Text(sub, Modifier.padding(top = 1.dp), style = type.caption.copy(fontSize = 12.5.sp), color = scheme.outline)
            }
            val inGame = row.presence == Presence.InGame
            val color by animateColorAsState(if (inGame) scheme.secondary else scheme.outline, motion.effectsDefault(), label = "presence")
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color)
                Text(stringResource(when (row.presence) { Presence.InGame -> Res.string.cg_presence_in_game; Presence.GettingReady -> Res.string.cg_presence_getting_ready; Presence.NotIn -> Res.string.cg_presence_not_in }), style = type.caption.copy(fontSize = 12.5.sp, fontWeight = HideoutWeights.Label), color = color)
            }
        }
    }
}

@Composable
internal fun HostTag() {
    Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest, HideoutShapes.Full).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(stringResource(Res.string.cg_tag_host), style = HideoutTheme.typography.captionCompact.copy(fontWeight = HideoutWeights.Title), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OpenSpots(count: Int) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).border(1.5.dp, scheme.surfaceContainerHighest, shape).padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).border(1.5.dp, scheme.surfaceContainerHighest, CircleShape), contentAlignment = Alignment.Center) {
            HideoutIcon(HideoutIcons.Person, null, size = 16.dp, tint = scheme.outline)
        }
        Text(pluralStringResource(Res.plurals.cg_open_spots, count, count), style = HideoutTheme.typography.body.copy(fontWeight = HideoutWeights.Label), color = scheme.onSurfaceVariant)
    }
}

@Composable
fun InviteCard(view: InviteView, mode: String, copy: CopyAction, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val motion = HideoutTheme.motion
    val dim by animateFloatAsState(if (view.paused) HideoutMotion.DimmedAlpha else 1f, motion.effectsDefault(), label = "inviteDim")
    CgCard(modifier) {
        CardHeading(HideoutIcons.Link, stringResource(Res.string.cg_invite_title))
        val banner = when {
            view.paused -> HideoutIcons.Users to Res.string.cg_invite_full_banner
            !view.live -> HideoutIcons.Clock to if (view.code == null) Res.string.cg_invite_starting_banner else Res.string.cg_invite_starting_banner_code
            else -> null
        }
        AnimatedVisibility(banner != null, enter = expandVertically(motion.spatialDefault()) + fadeIn(motion.enterFade()), exit = shrinkVertically(motion.spatialDefault()) + fadeOut(motion.exitFade())) {
            rememberLast(banner)?.let { (icon, text) -> HintBox(icon, stringResource(text)) }
        }
        val code = view.code
        val enabled = code != null && !view.paused
        Column(Modifier.alpha(dim), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.fillMaxWidth().background(scheme.surface, RoundedCornerShape(18.dp)).padding(start = 18.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.cg_code).uppercase(), style = type.captionCompact.copy(fontWeight = HideoutWeights.Title, letterSpacing = 0.7.sp), color = scheme.outline)
                    AnimatedContent(code, transitionSpec = { fadeIn(motion.enterFade()) togetherWith fadeOut(motion.exitFade()) }, label = "inviteCode") { shown ->
                        if (shown == null) {
                            Text(stringResource(Res.string.cg_invite_pending), style = type.body, color = scheme.outline, maxLines = 1)
                        } else {
                            Text(shown, style = type.mono.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em), color = scheme.onSurface, maxLines = 1)
                        }
                    }
                }
                HideoutIconButton(
                    HideoutIcons.Copy, stringResource(Res.string.cg_copy_code), { code?.let { copy(it, Res.string.cg_toast_code_copied) } },
                    size = 44.dp, iconSize = 18.dp, enabled = enabled, tint = scheme.onSurface, hoverColor = scheme.surfaceContainerHighest,
                    modifier = Modifier.background(scheme.surfaceContainerHigh, CircleShape),
                )
            }
            val message = code?.let { c -> view.link?.let { stringResource(Res.string.cg_invite_message_link, mode, it, c) } ?: stringResource(Res.string.cg_invite_message_code, mode, c) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view.link != null || code == null) {
                    HideoutButton(stringResource(Res.string.cg_copy_link), { view.link?.let { copy(it, Res.string.cg_toast_link_copied) } }, Modifier.weight(1f), leadingIcon = HideoutIcons.Link, leadingIconSize = 16.dp, enabled = enabled && view.link != null, contentPadding = InviteButtonPadding)
                }
                HideoutButton(stringResource(Res.string.cg_copy_message), { message?.let { copy(it, Res.string.cg_toast_message_copied) } }, Modifier.weight(1f), variant = ButtonVariant.Tonal, leadingIcon = HideoutIcons.Message, leadingIconSize = 16.dp, enabled = enabled, contentPadding = InviteButtonPadding)
            }
            Text(stringResource(Res.string.cg_invite_help), style = type.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp), color = scheme.outline)
        }
    }
}

private val InviteButtonPadding = PaddingValues(horizontal = 14.dp)

private enum class CopyFeedback { Idle, Copied, Failed }

/** Answers in place, for where no page toast reaches, as in the hosting panel or over a dialog. */
@Composable
fun CopyInPlace(
    text: String,
    label: StringResource,
    icon: ImageVector,
    variant: ButtonVariant,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.Small,
) {
    val clipboard = LocalClipboard.current
    var feedback by remember { mutableStateOf(CopyFeedback.Idle) }
    var presses by remember { mutableIntStateOf(0) }
    LaunchedEffect(presses) {
        if (presses == 0) return@LaunchedEffect
        feedback = if (clipboard.copyText(text)) CopyFeedback.Copied else CopyFeedback.Failed
        delay(CopyFeedbackMillis)
        feedback = CopyFeedback.Idle
    }
    val (shownIcon, shownLabel) = when (feedback) {
        CopyFeedback.Idle -> icon to label
        CopyFeedback.Copied -> HideoutIcons.Check to Res.string.cg_chip_copied
        CopyFeedback.Failed -> HideoutIcons.Warning to Res.string.cg_chip_copy_failed
    }
    HideoutButton(
        stringResource(shownLabel), { presses++ }, modifier,
        variant = variant, size = size, leadingIcon = shownIcon, leadingIconSize = 15.dp,
        contentColor = if (feedback == CopyFeedback.Failed) MaterialTheme.colorScheme.error else null,
        contentPadding = contentPadding,
    )
}

private const val CopyFeedbackMillis = 1_500L

/** Copies text, then says it in a toast: [success] when the clipboard took it, and "Couldn't copy" when it was held. */
fun interface CopyAction {
    operator fun invoke(text: String, success: StringResource)
}

@Composable
fun rememberCopy(toasts: CgToasts): CopyAction {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, toasts) {
        CopyAction { text, success -> scope.launch { toasts.show(if (clipboard.copyText(text)) success else Res.string.cg_toast_copy_failed) } }
    }
}

@Stable
class CgToasts {
    var message by mutableStateOf<Toast?>(null)
        private set
    private var serial = 0L

    fun show(text: StringResource) {
        message = Toast(text, ++serial)
    }

    fun dismiss() {
        message = null
    }

    data class Toast(val text: StringResource, val serial: Long)
}

@Composable
fun rememberCgToasts(): CgToasts = remember { CgToasts() }

@Composable
fun BoxScope.CgToastHost(toasts: CgToasts) {
    val motion = HideoutTheme.motion
    val message = toasts.message
    LaunchedEffect(message) {
        if (message != null) {
            delay(ToastMillis)
            toasts.dismiss()
        }
    }
    val last = rememberLast(message)
    val density = LocalDensity.current
    val rise = with(density) { motion.enterOffset.roundToPx() }
    val drop = if (motion.reduced) 0 else with(density) { ToastDrop.roundToPx() }
    AnimatedVisibility(
        message != null,
        Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
        enter = fadeIn(motion.enterFade()) + slideInVertically(motion.spatialFast()) { rise },
        exit = fadeOut(motion.exitFade()) + slideOutVertically(motion.spatialFast()) { drop },
    ) {
        AnimatedContent(last, transitionSpec = { (fadeIn(motion.enterFade()) + scaleIn(motion.spatialFast(), initialScale = 0.96f)) togetherWith fadeOut(motion.exitFade()) }, label = "toast") { shown ->
            HideoutSnackbar(AnnotatedString(shown?.let { stringResource(it.text) }.orEmpty()), closeLabel = stringResource(Res.string.cg_dismiss), onClose = toasts::dismiss)
        }
    }
}

private const val ToastMillis = 4_000L
private val ToastDrop = 8.dp

/** The one copy table for a game that never started, for a host on the game screen and a guest joining alike. */
fun problemWords(problem: Problem, host: Boolean, hostName: DisplayName?, mode: Phrase): Pair<Phrase, Phrase> {
    val (title, body) = when (problem) {
        Problem.DeadlockNotInstalled -> Res.string.cg_problem_not_installed_title to Res.string.cg_problem_not_installed_body
        Problem.SteamUnsupported -> Res.string.cg_problem_steam_unsupported_title to Res.string.cg_problem_steam_unsupported_body
        Problem.NoSteamAccount -> Res.string.cg_problem_no_account_title to Res.string.cg_problem_no_account_body
        Problem.DeadlockUpdated -> Res.string.cg_problem_deadlock_updated_title to Res.string.cg_problem_deadlock_updated_body
        Problem.NoModeList -> Res.string.cg_problem_no_mode_list_title to Res.string.cg_problem_no_mode_list_body
        Problem.ModeListUnreachable -> Res.string.cg_problem_list_unreachable_title to Res.string.cg_problem_list_unreachable_body
        Problem.NothingToHostWithHere -> Res.string.cg_problem_nothing_here_title to Res.string.cg_problem_nothing_here_body
        Problem.FolderMissing -> Res.string.cg_problem_folder_missing_title to Res.string.cg_problem_folder_missing_body
        Problem.DifferentVersion -> Res.string.cg_problem_different_version_title to Res.string.cg_problem_different_version_body
        Problem.DownloadStopped -> Res.string.cg_problem_download_title to Res.string.cg_problem_download_body
        Problem.DiskFull -> Res.string.cg_problem_download_title to Res.string.cg_problem_disk_body
        Problem.FilesDamaged -> Res.string.cg_problem_download_title to Res.string.cg_problem_damaged_body
        Problem.ContentNotStaged -> Res.string.cg_problem_content_title to Res.string.cg_problem_content_body
        Problem.MenusUnavailable -> Res.string.cg_problem_menus_title to Res.string.cg_problem_menus_body
        Problem.MenusBlocked -> Res.string.cg_problem_menus_blocked_title to Res.string.cg_problem_menus_blocked_body
        Problem.TookTooLong -> Res.string.cg_problem_slow_title to Res.string.cg_problem_slow_body
        Problem.CouldNotReach -> (if (hostName != null) Res.string.cg_problem_reach_title else Res.string.cg_problem_reach_title_friend) to Res.string.cg_problem_reach_body
        Problem.GameNotFound -> Res.string.cg_problem_not_found_title to Res.string.cg_problem_not_found_body
        Problem.FriendEnded -> (if (hostName != null) Res.string.cg_friend_ended_title else Res.string.cg_friend_ended_title_friend) to Res.string.cg_problem_friend_ended_body
        Problem.InviteNotWorking -> Res.string.cg_problem_invite_title to Res.string.cg_problem_invite_body
        Problem.ArenaFailed -> Res.string.cg_problem_arena_title to if (host) Res.string.cg_problem_arena_body else Res.string.cg_problem_arena_guest_body
        Problem.Unexpected -> Res.string.cg_problem_unexpected_title to Res.string.cg_problem_unexpected_body
    }
    val titled = when (problem) {
        Problem.ContentNotStaged, Problem.ArenaFailed -> phrase(title, mode)
        Problem.CouldNotReach, Problem.FriendEnded -> hostName?.let { phrase(title, it.value) } ?: phrase(title)
        else -> phrase(title)
    }
    return titled to phrase(body)
}

fun tryAgainHelps(problem: Problem): Boolean = when (problem) {
    Problem.NoModeList, Problem.NothingToHostWithHere, Problem.SteamUnsupported, Problem.FolderMissing, Problem.DifferentVersion, Problem.InviteNotWorking, Problem.FriendEnded -> false
    Problem.DeadlockNotInstalled, Problem.NoSteamAccount, Problem.DeadlockUpdated, Problem.MenusUnavailable, Problem.MenusBlocked, Problem.ModeListUnreachable, Problem.DownloadStopped, Problem.DiskFull,
    Problem.FilesDamaged, Problem.ContentNotStaged, Problem.TookTooLong, Problem.CouldNotReach, Problem.GameNotFound, Problem.ArenaFailed, Problem.Unexpected -> true
}

fun problemIcon(problem: Problem): ImageVector = when (problem) {
    Problem.DeadlockNotInstalled, Problem.NoSteamAccount, Problem.MenusUnavailable, Problem.MenusBlocked -> HideoutIcons.Monitor
    Problem.DeadlockUpdated, Problem.DifferentVersion -> HideoutIcons.SwapVersions
    Problem.DownloadStopped, Problem.DiskFull, Problem.FilesDamaged -> HideoutIcons.Download
    Problem.CouldNotReach, Problem.ModeListUnreachable -> HideoutIcons.WifiOff
    Problem.GameNotFound, Problem.InviteNotWorking -> HideoutIcons.Link
    Problem.FriendEnded -> HideoutIcons.Stop
    Problem.TookTooLong -> HideoutIcons.Clock
    Problem.SteamUnsupported, Problem.NoModeList, Problem.NothingToHostWithHere, Problem.FolderMissing, Problem.ContentNotStaged,
    Problem.ArenaFailed, Problem.Unexpected -> HideoutIcons.Shield
}

data class ConfirmWords(val title: String, val body: String, val keep: String, val confirm: String)

@Composable
fun ConfirmDialog(words: ConfirmWords?, modal: WindowModal, onKeep: () -> Unit, onConfirm: () -> Unit) {
    val focus = remember { FocusRequester() }
    val shown = rememberLast(words) ?: return
    AlertModal(visible = words != null, modal = modal, title = shown.title, onDismiss = onKeep, initialFocus = focus, width = 460.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBox(HideoutIcons.Stop, Tone.Fail, 48.dp, 16.dp, 22.dp)
            Column {
                Text(shown.title, style = HideoutTheme.typography.titleDialog, color = MaterialTheme.colorScheme.onSurface)
                Text(shown.body, Modifier.padding(top = 6.dp), style = HideoutTheme.typography.bodyDialog, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                HideoutButton(shown.keep, onKeep, Modifier.semantics { contentDescription = shown.keep }.focusRequester(focus), variant = ButtonVariant.Text, size = ButtonSize.Medium)
                HideoutButton(shown.confirm, onConfirm, variant = ButtonVariant.Danger, size = ButtonSize.Medium, enabled = words != null)
            }
        }
    }
}
