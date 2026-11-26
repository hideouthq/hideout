package gg.hideouthq.feature.customgames

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gg.hideouthq.core.customgames.CustomGames
import gg.hideouthq.core.customgames.FolderRead
import gg.hideouthq.core.customgames.InGameConsent
import gg.hideouthq.core.customgames.LibraryEntry
import gg.hideouthq.core.customgames.LinkProblem
import gg.hideouthq.core.customgames.ModeKind
import gg.hideouthq.core.customgames.ModeList
import gg.hideouthq.core.customgames.RestartReason
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.customgames.solo
import gg.hideouthq.core.customgames.startsHost
import gg.hideouthq.core.customgames.social.Social
import gg.hideouthq.core.customgames.social.SocialStatus
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.FilterChip
import gg.hideouthq.core.designsystem.component.FilterChipStyle
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutMenu
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.NoteCard
import gg.hideouthq.core.designsystem.component.MenuEntry
import gg.hideouthq.core.designsystem.component.NoticeCard
import gg.hideouthq.core.designsystem.component.PageHeader
import gg.hideouthq.core.designsystem.component.PillTabs
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.core.designsystem.component.SearchFieldSize
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.TabContent
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.component.rememberFieldValue
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.insetRing
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.leaveOnEscape
import gg.hideouthq.core.ui.trackFieldFocus
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate
import java.time.ZoneId

/** Asks the feed as it opens. */
@Composable
fun CustomGamesRoute(
    games: CustomGames,
    folderPicker: FolderPicker,
    modal: WindowModal,
    onOpenMode: (String) -> Unit,
    onSearch: (String) -> Unit,
    /** Whether one notification is wanted when servers go live, and the switch that says so. */
    notifyServers: Boolean,
    onNotifyServers: (Boolean) -> Unit,
    /** The player's answer to Hideout's UI inside Deadlock, null before it is read, and how a new one is given. */
    inGame: InGameConsent?,
    onInGame: (Boolean) -> Unit,
    social: Social? = null,
) {
    val modes by games.modes.collectAsStateWithLifecycle()
    val live by games.live.collectAsStateWithLifecycle()
    val restarts by games.restartsDeadlock.collectAsStateWithLifecycle()
    val inGameBlock by games.inGameBlockedBy.collectAsStateWithLifecycle()
    val inGameUnsaved by games.inGameUnsaved.collectAsStateWithLifecycle()
    val linkProblem by games.linkProblem.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }
    var tab by rememberSaveable { mutableStateOf(ModeTab.Browse) }
    var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(ModeFilter()) }
    val view = remember(modes, filter, today) { libraryView(modes, filter, today) }
    val block = hostBlock(live.session)
    var hosting by remember { mutableStateOf<InstalledRow?>(null) }
    val toasts = rememberCgToasts()
    val scope = rememberCoroutineScope()
    val pickTitle = stringResource(Res.string.cg_pick_folder)
    val copy = rememberCopy(toasts)
    LaunchedEffect(games) { games.refreshModes() }
    LaunchedEffect(linkProblem) {
        val problem = linkProblem ?: return@LaunchedEffect
        toasts.show(
            when (problem) {
                LinkProblem.Unreadable -> Res.string.cg_toast_link_unreadable
                LinkProblem.SessionRunning -> Res.string.cg_toast_session_running
            },
        )
        games.dismissLinkProblem()
    }
    val scroll = rememberScrollState()
    val title = stringResource(Res.string.nav_custom_games)
    val tabs = mapOf(ModeTab.Browse to stringResource(Res.string.cg_tab_browse), ModeTab.Servers to stringResource(Res.string.cg_tab_servers), ModeTab.Installed to stringResource(Res.string.cg_tab_installed))
    val soon = stringResource(Res.string.cg_tab_servers_soon)
    PaneScaffold(topBar = { SearchTopBar("", onSearch, stringResource(Res.string.search_catalogue)) }, scrolled = scroll.isScrolled, staggered = true) {
        Box(Modifier.fillMaxSize()) {
            ScrollColumn(
                Modifier.fillMaxSize(), state = scroll,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageHeader(title, stringResource(Res.string.custom_games_subtitle), Modifier.fillMaxWidth().staggeredEnter(0), subtitleMaxWidth = 620.dp) {
                    CodeField(games::openInvite)
                }
                social?.let { InvitesBlocked(it) { id -> copy(id, Res.string.cg_toast_id_copied) } }
                if (inGameUnsaved) NoteCard(stringResource(Res.string.cg_ingame_unsaved_lead), stringResource(Res.string.cg_ingame_unsaved_body))
                inGameBlock?.let { NoteCard(stringResource(Res.string.cg_ingame_blocked_lead), stringResource(Res.string.cg_ingame_blocked_body, it)) }
                if (menusAfterRestartShown(restarts, live.session, inGame)) NoteCard(stringResource(Res.string.cg_menus_after_restart_lead), stringResource(Res.string.cg_menus_after_restart_body))
                PillTabs(
                    ModeTab.entries, tab, { tab = it },
                    label = tabs::getValue,
                    icon = {
                        when (it) {
                            ModeTab.Browse -> HideoutIcons.Grid
                            ModeTab.Servers -> HideoutIcons.Server
                            ModeTab.Installed -> HideoutIcons.Download
                        }
                    },
                    groupLabel = stringResource(Res.string.cg_tabs),
                    modifier = Modifier.padding(vertical = 2.dp).staggeredEnter(1),
                    count = {
                        when (it) {
                            ModeTab.Browse -> view.browseCount.toString()
                            ModeTab.Servers -> soon
                            ModeTab.Installed -> view.installed.count.toString()
                        }
                    },
                )
                TabContent(tab, { it.ordinal }, Modifier.fillMaxWidth().padding(top = 8.dp).staggeredEnter(2)) { shown ->
                    when (shown) {
                        ModeTab.Browse -> BrowseTab(view, modes, filter, { filter = it }, onOpenMode, games::refreshModes)
                        ModeTab.Servers -> ServersSoon(notifyServers, onNotifyServers, onHost = {
                            val rows = view.installed.rows
                            when {
                                rows.size == 1 -> hosting = rows.single()
                                rows.isEmpty() -> tab = ModeTab.Browse
                                else -> tab = ModeTab.Installed
                            }
                        })
                        ModeTab.Installed -> InstalledTab(
                            view.installed, today, upkeepBlock(block, modes),
                            onHost = { hosting = it },
                            games = games,
                            onAddFolder = { scope.launch { folderPicker.pick(pickTitle, null)?.let(games::addFolder) } },
                        )
                    }
                }
            }
            CgToastHost(toasts)
        }
    }
    HostDialog(hosting?.mode, modes, block, restarts, games, modal) { hosting = null }
    InGameDialog(inGame == InGameConsent.Unanswered && live.session.startsHost && hosting == null, modal, onInGame)
}

/** Whether the page says Hideout games show in Deadlock's Play menu once it restarts, which only a yes puts there. */
internal fun menusAfterRestartShown(restarts: RestartReason?, session: Session, inGame: InGameConsent?): Boolean =
    inGame == InGameConsent.Yes && restarts == RestartReason.NotSetUp && session.startsHost

/** Shown once the site refused this PC's mailbox, with the player's own id, the one support resets it by. */
@Composable
private fun InvitesBlocked(social: Social, onCopy: (String) -> Unit) {
    val state by social.state.collectAsStateWithLifecycle()
    val blocked = social.status(state) as? SocialStatus.Blocked ?: return
    val id = blocked.self.value
    NoticeCard(stringResource(Res.string.cg_invites_blocked_lead), stringResource(Res.string.cg_invites_blocked_body, id), stringResource(Res.string.cg_copy), { onCopy(id) })
}

private val FilterSaver = listSaver<ModeFilter, String>(
    save = { listOf(it.kind?.name.orEmpty(), it.sort.name, it.query) },
    restore = { (kind, sort, query) -> ModeFilter(ModeKind.entries.firstOrNull { it.name == kind }, ModeSort.valueOf(sort), query) },
)

@Composable
private fun CodeField(onJoin: (String) -> Boolean) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val (field, onFieldChange) = rememberFieldValue(text, { text = it; error = false }, focused)
    val join = { if (onJoin(text.trim())) text = "" else error = true }
    val shape = RoundedCornerShape(14.dp)
    val ring by animateColorAsState(if (error) scheme.error else scheme.surfaceContainerHighest, HideoutTheme.motion.effectsDefault(), label = "codeRing")
    val ringWidth by animateDpAsState(if (error) 2.dp else 1.dp, HideoutTheme.motion.effectsDefault(), label = "codeRingWidth")
    val label = stringResource(Res.string.cg_code_label)
    Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = type.caption.copy(fontSize = 12.5.sp, fontWeight = HideoutWeights.Label), color = scheme.outline)
        Row(
            Modifier
                .fillMaxWidth()
                .trackFieldFocus(focused)
                .height(44.dp)
                .background(scheme.surface, shape)
                .focusRing(focused && !error, shape)
                .insetRing(shape, ring) { ringWidth.toPx() }
                .padding(start = 16.dp, end = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val textStyle = type.monoCode.copy(fontSize = 15.sp, letterSpacing = 0.06.em, color = scheme.onSurface)
            BasicTextField(
                value = field,
                onValueChange = onFieldChange,
                modifier = Modifier.weight(1f).leaveOnEscape().semantics { contentDescription = label },
                textStyle = textStyle,
                singleLine = true,
                cursorBrush = SolidColor(scheme.primary),
                interactionSource = source,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { join() }),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(stringResource(Res.string.cg_code_placeholder), style = type.body.copy(fontSize = 14.sp), color = scheme.outline, maxLines = 1)
                        inner()
                    }
                },
            )
            HideoutButton(stringResource(Res.string.cg_code_join), join, size = ButtonSize.Small, enabled = text.isNotBlank(), contentPadding = PaddingValues(horizontal = 16.dp))
        }
        AnimatedVisibility(error, enter = fadeIn(HideoutTheme.motion.enterFade()) + expandVertically(HideoutTheme.motion.spatialDefault()), exit = fadeOut(HideoutTheme.motion.exitFade()) + shrinkVertically(HideoutTheme.motion.spatialDefault())) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HideoutIcon(HideoutIcons.Warning, null, Modifier.padding(top = 1.dp), size = 15.dp, tint = scheme.error)
                Text(stringResource(Res.string.cg_code_error), style = type.caption.copy(fontSize = 12.5.sp), color = scheme.error)
            }
        }
    }
}

@Composable
private fun BrowseTab(view: LibraryView, modes: ModeList, filter: ModeFilter, onFilter: (ModeFilter) -> Unit, onOpenMode: (String) -> Unit, onRetry: () -> Unit) {
    val motion = HideoutTheme.motion
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (view.filtersShown) FilterRow(view, filter, onFilter)
        AnimatedContent(browseBody(view, modes), transitionSpec = { fadeIn(motion.enterFade()) togetherWith fadeOut(motion.exitFade()) }, label = "browseBody") { body ->
            when (body) {
                BrowseBody.Cards -> ModeGrid(view.shown, onOpenMode)
                BrowseBody.Loading -> Row(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    Spinner(MaterialTheme.colorScheme.onSurfaceVariant, 16.dp)
                    Text(stringResource(Res.string.cg_loading_modes), style = HideoutTheme.typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BrowseBody.NoModes -> EmptyState(HideoutIcons.Gamepad, stringResource(Res.string.cg_no_modes_title), stringResource(Res.string.cg_no_modes_body), Modifier.fillMaxWidth()) {
                    HideoutButton(stringResource(Res.string.cg_try_again), onRetry, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
                }
                BrowseBody.NoMatch -> EmptyState(HideoutIcons.Search, stringResource(Res.string.cg_no_match_title), stringResource(Res.string.cg_no_match_body), Modifier.fillMaxWidth()) {
                    HideoutButton(stringResource(Res.string.cg_clear_filters), { onFilter(filter.copy(kind = null, query = "")) }, variant = ButtonVariant.Text, size = ButtonSize.Small)
                }
            }
        }
    }
}

@Composable
private fun FilterRow(view: LibraryView, filter: ModeFilter, onFilter: (ModeFilter) -> Unit) {
    val sorts = mapOf(ModeSort.Featured to stringResource(Res.string.cg_sort_featured), ModeSort.Newest to stringResource(Res.string.cg_sort_newest))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        view.kinds.forEach { (kind, count) ->
            FilterChip(kindLabel(kind), filter.kind == kind, { onFilter(filter.copy(kind = kind)) }, style = FilterChipStyle.Outlined, count = count.toString())
        }
        Spacer(Modifier.weight(1f))
        SegmentedControl(
            ModeSort.entries, filter.sort, { onFilter(filter.copy(sort = it)) },
            label = sorts::getValue,
        )
        SearchField(filter.query, { onFilter(filter.copy(query = it)) }, stringResource(Res.string.cg_search_modes), Modifier.width(220.dp), size = SearchFieldSize.Small)
    }
}

@Composable
private fun kindLabel(kind: ModeKind?): String = stringResource(
    when (kind) {
        null -> Res.string.cg_kind_all
        ModeKind.Duel -> Res.string.cg_kind_duel
        ModeKind.Teams -> Res.string.cg_kind_teams
        ModeKind.FreeForAll -> Res.string.cg_kind_ffa
        ModeKind.Coop -> Res.string.cg_kind_coop
    },
)

@Composable
private fun InstalledTab(view: InstalledView, today: LocalDate, block: UpkeepBlock?, onHost: (InstalledRow) -> Unit, games: CustomGames, onAddFolder: () -> Unit) {
    val why = block?.let { upkeepText(it) }
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val sub = type.caption.copy(fontSize = 12.5.sp, lineHeight = 18.sp)
    Column(Modifier.wrapContentWidth(Alignment.Start).widthIn(max = 920.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        CgCard(Modifier.fillMaxWidth(), gap = 12.dp) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.cg_installed_title), Modifier.weight(1f), style = cardTitle(), color = scheme.onSurface)
                if (view.rows.isNotEmpty()) Text(if (view.rows.size == 1) stringResource(Res.string.cg_installed_summary_one, megabytes(view.bytes)) else stringResource(Res.string.cg_installed_summary, view.rows.size, megabytes(view.bytes)), style = type.caption.copy(fontSize = 13.sp, fontWeight = HideoutWeights.Label), color = scheme.outline)
            }
            if (view.rows.isEmpty()) {
                Text(stringResource(Res.string.cg_installed_empty), style = sub, color = scheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    view.rows.forEach { row -> InstalledLine(row, today, why, { onHost(row) }, games) }
                }
            }
            HorizontalDivider(Modifier.padding(top = 4.dp), thickness = 1.dp, color = scheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.cg_installed_footer), Modifier.weight(1f), style = sub, color = scheme.outline)
                if (view.updates > 0) Blocked(why) { HideoutButton(stringResource(Res.string.cg_update_all), games::updateAll, variant = ButtonVariant.Text, size = ButtonSize.Small, enabled = why == null) }
            }
        }
        if (view.folders != null) CgCard(Modifier.fillMaxWidth(), gap = 12.dp) {
            Text(stringResource(Res.string.cg_folders_title), style = cardTitle(), color = scheme.onSurface)
            Text(stringResource(Res.string.cg_folders_hint), style = sub, color = scheme.outline)
            view.folders.forEach { folder -> FolderLine(folder) { games.removeFolder(folder.path) } }
            HideoutButton(stringResource(Res.string.cg_add_folder), onAddFolder, variant = ButtonVariant.Tonal, size = ButtonSize.Small, leadingIcon = HideoutIcons.Plus, leadingIconSize = 16.dp)
        }
    }
}

@Composable
private fun upkeepText(block: UpkeepBlock): String = stringResource(
    when (block) {
        UpkeepBlock.CleaningUp -> Res.string.cg_block_cleaning_up
        UpkeepBlock.GameRunning -> Res.string.cg_block_game_running
        UpkeepBlock.Updating -> Res.string.cg_updating
    },
)

@Composable
internal fun Blocked(why: String?, modifier: Modifier = Modifier, button: @Composable () -> Unit) {
    if (why == null) Box(modifier) { button() } else HideoutTooltip(why, modifier, content = button)
}

@Composable
private fun cardTitle() = HideoutTheme.typography.titleCard.copy(fontSize = 16.sp, fontWeight = HideoutWeights.Title)

@Composable
private fun InstalledLine(row: InstalledRow, today: LocalDate, why: String?, onHost: () -> Unit, games: CustomGames) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val mode = row.record.mode
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val fill by animateColorAsState(if (states.hovered) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh.copy(alpha = 0f), HideoutTheme.motion.effectsDefault(), label = "installedRow")
    val status = row.status
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(fill).hoverable(source).padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeTile(mode.id.value, mode.listing.art.tile, 48.dp, 14.dp)
        Column(Modifier.weight(1f)) {
            Text(mode.listing.name, style = type.bodyStrong.copy(fontSize = 14.5.sp, fontWeight = HideoutWeights.Title), color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.cg_installed_line, mode.version, megabytes(row.record.bytes)) + " · " + playedText(playedDaysAgo(row.record.lastPlayed, today, ZoneId.systemDefault())),
                Modifier.padding(top = 2.dp), style = type.caption.copy(fontSize = 12.5.sp), color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        StatusLine(status)
        val update = row.update
        if (update != null) {
            Blocked(why) { HideoutButton(stringResource(Res.string.cg_update), { games.update(mode.id) }, size = ButtonSize.Small, enabled = why == null) }
        } else {
            HideoutButton(stringResource(if (row.mode.listing.solo) Res.string.cg_play else Res.string.cg_host), onHost, variant = ButtonVariant.Tonal, size = ButtonSize.Small, enabled = status !is InstalledStatus.Updating)
        }
        RowMenu(stringResource(Res.string.cg_more), listOf(MenuEntry.Item(stringResource(Res.string.cg_remove), HideoutIcons.Trash, { games.remove(mode.id) }, danger = true, enabled = why == null, hint = why)))
    }
}

@Composable
private fun StatusLine(status: InstalledStatus) {
    val scheme = MaterialTheme.colorScheme
    val (color, text) = when (status) {
        InstalledStatus.UpToDate -> scheme.secondary to stringResource(Res.string.cg_up_to_date)
        is InstalledStatus.UpdateTo -> scheme.primary to stringResource(Res.string.cg_update_to, status.version)
        is InstalledStatus.Updating -> scheme.primary to listOfNotNull(
            stringResource(Res.string.cg_updating),
            status.transfer?.let { stringResource(Res.string.cg_bytes_of, megabytes(it.done), megabytes(it.total)) },
        ).joinToString(" ")
        InstalledStatus.UpdateFailed -> scheme.error to stringResource(Res.string.cg_update_failed)
        InstalledStatus.NotListed -> scheme.outline to stringResource(Res.string.cg_not_listed)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status is InstalledStatus.Updating) Spinner(color, 12.dp) else StatusDot(color)
        Text(text, style = HideoutTheme.typography.caption.copy(fontSize = 12.5.sp, fontWeight = HideoutWeights.Label), color = color, maxLines = 1)
    }
}

@Composable
private fun playedText(days: Int?): String = when (days) {
    null -> stringResource(Res.string.cg_never_played)
    0 -> stringResource(Res.string.cg_played_today)
    1 -> stringResource(Res.string.cg_played_yesterday)
    else -> stringResource(Res.string.cg_played_days, days)
}

@Composable
private fun FolderLine(folder: LibraryEntry.Folder, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = HideoutTheme.typography
    val problem = when (folder.read) {
        is FolderRead.Read -> null
        FolderRead.NotThere -> Res.string.cg_folder_not_there
        FolderRead.NotAMode -> Res.string.cg_folder_not_a_mode
        FolderRead.BadListing -> Res.string.cg_folder_bad_listing
    }
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HideoutIcon(HideoutIcons.Folder, null, size = 18.dp, tint = scheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            (folder.read as? FolderRead.Read)?.let { Text(it.mode.listing.name, style = type.bodyStrong.copy(fontSize = 14.sp, fontWeight = HideoutWeights.Label), color = scheme.onSurface) }
            Text(middleTruncate(folder.path, 28), style = type.monoCode.copy(fontSize = 12.sp), color = scheme.outline, maxLines = 1)
            problem?.let { Text(stringResource(it), style = type.caption.copy(fontSize = 12.5.sp), color = HideoutTheme.colors.caution) }
        }
        HideoutButton(stringResource(Res.string.cg_remove), onRemove, variant = ButtonVariant.Text, size = ButtonSize.Small)
    }
}

@Composable
private fun RowMenu(label: String, entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        HideoutIconButton(HideoutIcons.MoreHorizontal, label, { open = true })
        if (open) HideoutMenu(entries, { open = false }, label)
    }
}
