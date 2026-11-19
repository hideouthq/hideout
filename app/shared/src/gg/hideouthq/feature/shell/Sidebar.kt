package gg.hideouthq.feature.shell

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import gg.hideouthq.core.designsystem.component.*
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.navigation.Section
import gg.hideouthq.core.ui.AppPopup
import gg.hideouthq.core.ui.LocalReducedMotion
import gg.hideouthq.core.ui.windowDragArea
import gg.hideouthq.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

const val DiscordInvite = "https://discord.gg/369ndAKXmr"

@Composable
fun Sidebar(
    state: ShellUiState,
    activeSection: Section,
    onSection: (Section) -> Unit,
    onPlay: (Boolean) -> Unit,
    profileActions: ProfileActions,
    showStats: Boolean,
    showCustomGames: Boolean,
    inCustomGame: Boolean,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val width by animateDpAsState(
        if (collapsed) 76.dp else 240.dp,
        tween(if (LocalReducedMotion.current) 0 else 450, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)),
        label = "sidebarWidth",
    )
    val compact = width < 120.dp
    val labels = ((width.value - 120f) / 120f).coerceIn(0f, 1f)
    val uris = LocalUriHandler.current
    Column(
        modifier.width(width).fillMaxHeight().padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SidebarBrand(compact, labels, onToggle)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SidebarGroup(stringResource(Res.string.sidebar_mods), compact, labels)
            for (section in listOf(Section.Discover, Section.Browse, Section.Library)) {
                SidebarSection(section, activeSection, state, compact, labels, onSection)
            }
            SidebarGroup(stringResource(Res.string.sidebar_game), compact, labels)
            for (section in listOfNotNull(Section.CustomGames.takeIf { showCustomGames }, Section.Stats.takeIf { showStats }, Section.Crosshairs, Section.Autoexec)) {
                SidebarSection(section, activeSection, state, compact, labels, onSection)
            }
            ProfileSidebar(state, profileActions, compact)
        }
        SidebarItem(stringResource(Res.string.nav_discord), HideoutIcons.Discord, false, compact, labels,
            tooltip = stringResource(Res.string.sidebar_discord_tip), onClick = { uris.openUri(DiscordInvite) },
            trailing = { HideoutIcon(HideoutIcons.OpenExternal, null, size = 14.dp, tint = MaterialTheme.colorScheme.outline) })
        SidebarSection(Section.Settings, activeSection, state, compact, labels, onSection)
        Spacer(Modifier.height(8.dp))
        if (compact) RailLaunch(state, inCustomGame, onPlay) else SidebarLaunchCard(state, inCustomGame, onPlay, Modifier.alpha(labels))
    }
}

@Composable
private fun SidebarBrand(compact: Boolean, labelAlpha: Float, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val label = stringResource(if (compact) Res.string.sidebar_expand else Res.string.sidebar_collapse)
    Row(Modifier.fillMaxWidth().height(44.dp).windowDragArea(), verticalAlignment = Alignment.CenterVertically) {
        if (compact) {
            HideoutTooltip(label) {
                Box(Modifier.size(56.dp, 44.dp).hoverable(interaction).semantics { contentDescription = label }
                    .hideoutClickable(interaction, onClick = onToggle), contentAlignment = Alignment.Center) {
                    if (hovered) HideoutIcon(HideoutIcons.PanelExpand, null) else BrandRow("", compact = true)
                }
            }
        } else {
            BrandRow(stringResource(Res.string.app_name), Modifier.weight(1f).alpha(labelAlpha))
            HideoutTooltip(label) { HideoutIconButton(HideoutIcons.PanelCollapse, label, onToggle, size = 34.dp) }
        }
    }
}

@Composable
internal fun SidebarGroup(label: String, compact: Boolean, labelAlpha: Float = 1f) {
    Box(Modifier.fillMaxWidth().height(if (compact) 19.dp else 32.dp), contentAlignment = if (compact) Alignment.Center else Alignment.BottomStart) {
        if (compact) Box(Modifier.size(22.dp, 1.5.dp).background(MaterialTheme.colorScheme.outlineVariant))
        else Text(label, Modifier.padding(start = 12.dp, bottom = 7.dp).alpha(labelAlpha), style = HideoutTheme.typography.caption,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SidebarSection(section: Section, active: Section, state: ShellUiState, compact: Boolean, labelAlpha: Float, onSection: (Section) -> Unit) {
    val (name, icon) = when (section) {
        Section.Discover -> Res.string.nav_discover to HideoutIcons.Discover
        Section.Browse -> Res.string.nav_browse to HideoutIcons.Search
        Section.Library -> Res.string.nav_library to HideoutIcons.Layers
        Section.Stats -> Res.string.nav_stats to HideoutIcons.NavStats
        Section.Crosshairs -> Res.string.nav_crosshairs to HideoutIcons.Crosshairs
        Section.Autoexec -> Res.string.nav_autoexec to HideoutIcons.Autoexec
        Section.CustomGames -> Res.string.nav_custom_games to HideoutIcons.Gamepad
        Section.Settings -> Res.string.nav_settings to HideoutIcons.Settings
    }
    val label = stringResource(name)
    val transfers = state.transfers.takeIf { section == Section.Library }
    val held = transfers != null && state.transfersHeld
    val tooltip = if (held) stringResource(Res.string.busy_gamebanana) else transfers?.percent?.let { stringResource(Res.string.sidebar_downloading, it) } ?: label
    Box {
        SidebarItem(label, icon, section == active, compact, labelAlpha, tooltip, { onSection(section) }) {
            if (transfers != null) TransferTrailing(transfers.percent, transfers.count, held)
        }
        if (compact && transfers != null) {
            Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 7.dp).size(20.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, CircleShape).padding(2.dp)) {
                val progress = transfers.percent
                if (held) Icon(HideoutIcons.Clock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                else CircularProgressIndicator(progress = { progress / 100f }, modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun SidebarItem(label: String, icon: ImageVector, selected: Boolean, compact: Boolean, labelAlpha: Float, tooltip: String = label, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val content: @Composable () -> Unit = {
        NavItem(label, selected, onClick, compact = compact, icon = icon, labelAlpha = labelAlpha, trailing = trailing)
    }
    if (compact) HideoutTooltip(tooltip, content = content) else content()
}

@Composable
private fun SidebarLaunchCard(state: ShellUiState, inCustomGame: Boolean, onPlay: (Boolean) -> Unit, modifier: Modifier = Modifier, flyout: Boolean = false) {
    val running = state.activity == GameActivity.Running
    val profile = state.profiles?.activeProfile?.name.orEmpty()
    val status = when {
        state.saving -> stringResource(Res.string.profile_saving)
        state.applying -> stringResource(Res.string.profile_applying)
        state.choiceSync is ChoiceSync.Waiting -> stringResource(Res.string.profile_saved)
        state.choiceSync is ChoiceSync.Failed -> stringResource(Res.string.profile_not_applied)
        else -> null
    }
    LaunchCard(
        title = stringResource(Res.string.launch_game),
        subtitle = when (state.game) {
            GameStatus.Searching -> stringResource(Res.string.launch_searching)
            is GameStatus.Missing -> stringResource(Res.string.launch_game_not_found)
            is GameStatus.Ready -> when {
                running -> stringResource(Res.string.profile_running_status, profile)
                inCustomGame && !state.canPlay -> stringResource(Res.string.launch_in_custom_game)
                else -> pluralStringResource(Res.plurals.profile_launch_status, state.modsOn, profile, state.modsOn)
            }
        },
        primaryLabel = stringResource(if (running) Res.string.profile_in_game else Res.string.play_modded),
        secondaryLabel = stringResource(Res.string.play_vanilla),
        onPrimary = { onPlay(true) }, onSecondary = { onPlay(false) }, modifier = modifier,
        gameFound = state.game !is GameStatus.Missing, enabled = state.canPlay, running = running, status = status,
        containerColor = if (flyout) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
    )
}

@Composable
private fun RailLaunch(state: ShellUiState, inCustomGame: Boolean, onPlay: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    var flyHovered by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, flyHovered) {
        if (hovered || flyHovered) { delay(300); open = true }
        else { delay(160); open = false }
    }
    val running = state.activity == GameActivity.Running
    val label = stringResource(if (running) Res.string.sidebar_running else Res.string.play_modded)
    val scheme = MaterialTheme.colorScheme
    val gap = with(LocalDensity.current) { 10.dp.roundToPx() }
    Box {
        HideoutTooltip(label) {
            Box(Modifier.size(56.dp).alpha(if (state.game is GameStatus.Missing) .4f else 1f)
                .background(if (running) scheme.surfaceContainerHigh else scheme.primary, RoundedCornerShape(20.dp))
                .hoverable(source).semantics { contentDescription = label }
                .hideoutClickable(source, enabled = state.canPlay, onClick = { onPlay(true) }), contentAlignment = Alignment.Center) {
                if (running) Box(Modifier.size(22.dp).background(scheme.secondary.copy(alpha = .22f), CircleShape).padding(5.dp).background(scheme.secondary, CircleShape))
                else HideoutIcon(HideoutIcons.Play, null, tint = scheme.onPrimary, size = 22.dp)
            }
        }
        if (open) AppPopup(popupPositionProvider = remember(gap) { BesideAnchor(gap, Alignment.Bottom) }, onDismissRequest = { open = false }, properties = PopupProperties(focusable = false)) {
            val flySource = remember { MutableInteractionSource() }
            val over by flySource.collectIsHoveredAsState()
            LaunchedEffect(over) { flyHovered = over }
            DisposableEffect(Unit) { onDispose { flyHovered = false } }
            SidebarLaunchCard(state, inCustomGame, { open = false; onPlay(it) }, Modifier.width(236.dp).hoverable(flySource).shadow(18.dp, RoundedCornerShape(20.dp)), flyout = true)
        }
    }
}
