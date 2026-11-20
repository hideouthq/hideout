package gg.hideouthq.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.EmptyStateTone
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIcon
import gg.hideouthq.core.designsystem.component.PaneHeader
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.EscapeClaimEffect
import gg.hideouthq.core.ui.LocalPaneFrame
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.ScrollToTopEffect
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.imports.SourceOption
import gg.hideouthq.feature.imports.importNeed
import gg.hideouthq.feature.imports.managerName
import gg.hideouthq.feature.imports.waitDetail
import gg.hideouthq.feature.library.components.CompatibilityBar
import gg.hideouthq.feature.library.components.CompatibilityDialogs
import gg.hideouthq.feature.library.components.DownloadingCardGroup
import gg.hideouthq.feature.library.components.DownloadingListGroup
import gg.hideouthq.feature.library.components.InstalledSetList
import gg.hideouthq.feature.library.components.LibraryCardGrid
import gg.hideouthq.feature.library.components.LibraryDetailsPanel
import gg.hideouthq.feature.library.components.LibraryFilterPills
import gg.hideouthq.feature.library.components.LibraryHeader
import gg.hideouthq.feature.library.components.LibraryMoreMenu
import gg.hideouthq.feature.library.components.LibraryScopeToggle
import gg.hideouthq.feature.library.components.LibraryViewToggle
import gg.hideouthq.feature.library.components.SelectionPanel
import gg.hideouthq.feature.library.components.UpdatesBar
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.details_title
import gg.hideouthq.resources.library_blocked_message
import gg.hideouthq.resources.library_blocked_title
import gg.hideouthq.resources.library_importing_empty_title
import gg.hideouthq.resources.library_importing_from
import gg.hideouthq.resources.library_importing_from_file
import gg.hideouthq.resources.library_importing_mods
import gg.hideouthq.resources.library_importing_mods_file
import gg.hideouthq.resources.library_scope_empty_message
import gg.hideouthq.resources.library_scope_empty_title
import gg.hideouthq.resources.library_scope_show_all
import gg.hideouthq.resources.profile_library_add_file
import gg.hideouthq.resources.profile_library_details_placeholder
import gg.hideouthq.resources.profile_library_discover
import gg.hideouthq.resources.profile_library_drop_hint
import gg.hideouthq.resources.profile_library_empty_message
import gg.hideouthq.resources.profile_library_empty_title
import gg.hideouthq.resources.profile_library_import
import gg.hideouthq.resources.profile_library_no_results
import gg.hideouthq.resources.profile_library_no_results_hint
import gg.hideouthq.resources.profile_library_past_limit
import gg.hideouthq.resources.profile_library_running
import gg.hideouthq.resources.profile_library_saved_custom_game
import gg.hideouthq.resources.profile_library_saved_running
import gg.hideouthq.resources.profile_library_saved_unknown
import gg.hideouthq.resources.profile_library_unknown
import gg.hideouthq.resources.search_library
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    actions: LibraryActions,
    modal: WindowModal,
    modifier: Modifier = Modifier,
    onInspectFilePriority: ((String) -> Unit)? = null,
) {
    val scrollState = rememberScrollState()
    ScrollToTopEffect(scrollState)
    val selected = state.selected
    val frame = LocalPaneFrame.current
    val motion = HideoutTheme.motion
    var viewport by remember { mutableStateOf(Rect.Zero) }
    EscapeClaimEffect(state.several != null, actions::clearSelection)
    PaneScaffold(
        modifier = modifier.onKeyEvent { event ->
            when {
                event.type != KeyEventType.KeyDown -> false
                event.key == Key.A && (event.isCtrlPressed || event.isMetaPressed) -> true.also { actions.selectAll() }
                else -> false
            }
        },
        topBar = { SearchTopBar(state.query, actions::setQuery, stringResource(Res.string.search_library)) },
        scrolled = scrollState.isScrolled,
        staggered = true,
        sidePanel = if (state.isEmpty) null else ({
            val several = state.several
            when {
                selected != null -> LibraryDetailsPanel(detail = selected, onViewPage = actions::viewPage, onOpenFolder = actions::showFiles, onUninstall = actions::uninstall,
                    onReinstall = actions::reinstall, onKeepEdits = actions::keepEdits, onChangeFiles = actions::changeFiles, onKeepCurrentFiles = actions::keepCurrentFiles, profiles = state.profiles, onMembership = actions::setEnabled, onInspect = onInspectFilePriority, onReorderParts = actions::reorderParts,
                    onCompat = if (state.compat.unavailable == null) actions::openCompat else null)
                several != null -> SelectionPanel(several, state.profiles?.profiles.orEmpty(), actions::setSelectedEnabled, actions::clearSelection, actions::uninstallSelected)
                else -> EmptyDetailsPanel()
            }
        }),
    ) {
        if (state.isEmpty) {
            Column(Modifier.fillMaxSize().padding(horizontal = HideoutDimens.MainPane.ContentPadding)) {
                ScreenHeader(state, actions, modal)
                EmptyLibrary(state.canInstall, state.importing, actions, Modifier.weight(1f).fillMaxWidth())
            }
            return@PaneScaffold
        }
        ScrollColumn(
            modifier = Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInRoot() },
            state = scrollState,
            contentPadding = PaddingValues(
                start = HideoutDimens.MainPane.ContentPadding,
                end = HideoutDimens.MainPane.ContentPadding,
                bottom = HideoutDimens.MainPane.ContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(HideoutDimens.MainPane.BlockGap),
        ) {
            ScreenHeader(state, actions, modal)
            UpdatesBar(state.updates, state.canInstall, actions, Modifier.staggeredEnter(0))
            CompatibilityBar(state.compat, actions, Modifier.staggeredEnter(0))
            if (state.showFilters) {
                LibraryFilterPills(state.filters, state.filter, actions::setFilter, Modifier.staggeredEnter(1))
            }
            val onSelect: (String, SelectGesture) -> Unit = { installId, gesture ->
                actions.select(installId, gesture)
                if (gesture == SelectGesture.Only) frame?.openSidePanel()
            }
            when (state.layout) {
                LibraryLayout.List -> DownloadingListGroup(state.downloads, actions)
                LibraryLayout.Cards -> DownloadingCardGroup(state.downloads, actions)
            }
            AnimatedContent(
                targetState = state.layout,
                transitionSpec = { fadeIn(motion.effectsDefault()) togetherWith fadeOut(motion.exitFade()) },
                label = "libraryLayout",
            ) { layout ->
                when (layout) {
                    LibraryLayout.List -> InstalledSetList(
                        rows = state.rows,
                        selectedIds = state.selectedIds,
                        onSelect = onSelect,
                        actions = actions,
                        modal = modal,
                        view = state.orderView,
                        scrollState = scrollState,
                        viewport = viewport,
                    )
                    LibraryLayout.Cards -> LibraryCardGrid(rows = state.rows, selectedIds = state.selectedIds, onSelect = onSelect, actions = actions, modal = modal, view = state.orderView)
                }
            }
            val listed = !state.loading && state.blocked == null && state.rows.isNotEmpty()
            val nothingOn = state.scope == LibraryScope.Profile && state.scopedCount == 0
            AnimatedVisibility(
                visible = listed && state.visibleCount == 0 && !nothingOn,
                enter = fadeIn(motion.effectsDefault()),
                exit = fadeOut(motion.exitFade()),
            ) {
                EmptyState(
                    icon = HideoutIcons.Check,
                    title = stringResource(Res.string.profile_library_no_results),
                    message = stringResource(Res.string.profile_library_no_results_hint),
                    tone = EmptyStateTone.Positive,
                )
            }
            AnimatedVisibility(visible = listed && nothingOn, enter = fadeIn(motion.effectsDefault()), exit = fadeOut(motion.exitFade())) {
                EmptyState(
                    icon = HideoutIcons.Layers,
                    title = stringResource(Res.string.library_scope_empty_title, state.profiles?.activeProfile?.name.orEmpty()),
                    message = stringResource(Res.string.library_scope_empty_message),
                    action = {
                        HideoutButton(stringResource(Res.string.library_scope_show_all), { actions.setScope(LibraryScope.All) }, variant = ButtonVariant.Tonal)
                    },
                )
            }
            AnimatedVisibility(
                visible = state.blocked != null,
                enter = fadeIn(motion.effectsDefault()),
                exit = fadeOut(motion.exitFade()),
            ) {
                EmptyState(
                    icon = HideoutIcons.Warning,
                    title = stringResource(Res.string.library_blocked_title),
                    message = stringResource(Res.string.library_blocked_message, state.blocked.orEmpty()),
                    tone = EmptyStateTone.Neutral,
                )
            }
        }
    }
    CompatibilityDialogs(state.compat, modal, actions)
}

@Composable
private fun ScreenHeader(state: LibraryUiState, actions: LibraryActions, modal: WindowModal) {
    val profileName = state.profiles?.activeProfile?.name.orEmpty()
    LibraryHeader(
        enabledCount = state.enabledCount,
        totalCount = state.totalCount,
        enabledSizeBytes = state.enabledSizeBytes,
        interrupted = state.interrupted,
        profileName = profileName,
        note = state.note?.let { noteText(it) },
        noteWarns = state.note is LibraryNote.PastLimit,
        modifier = Modifier.staggeredEnter(0),
        trailing = if (state.isEmpty) null else ({
            val motion = HideoutTheme.motion
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HideoutButton(stringResource(Res.string.profile_library_add_file), actions::addFile, variant = ButtonVariant.Subtle, leadingIcon = HideoutIcons.Plus, enabled = state.canInstall)
                AnimatedVisibility(
                    visible = state.showScope,
                    enter = expandHorizontally(motion.spatialDefault()) + fadeIn(motion.effectsDefault()),
                    exit = shrinkHorizontally(motion.spatialDefault()) + fadeOut(motion.exitFade()),
                ) {
                    LibraryScopeToggle(state.scope, profileName, actions::setScope)
                }
                LibraryViewToggle(state.layout, actions::setLayout)
                LibraryMoreMenu(canImport = state.importing == null, canTurnAllOff = state.enabledCount > 0, actions, modal)
            }
        }),
    )
}

@Composable
private fun noteText(note: LibraryNote): String = when (note) {
    is LibraryNote.Importing -> {
        val banner = note.banner
        val from = banner.from
        val what = when {
            banner.mods != null && from is SourceOption.Installed -> pluralStringResource(Res.plurals.library_importing_mods, banner.mods, banner.mods, managerName(from.manager))
            banner.mods != null -> pluralStringResource(Res.plurals.library_importing_mods_file, banner.mods, banner.mods)
            from is SourceOption.Installed -> stringResource(Res.string.library_importing_from, managerName(from.manager))
            else -> stringResource(Res.string.library_importing_from_file)
        }
        ModLabels.join(what, banner.wait?.let { waitDetail(it) })
    }
    is LibraryNote.PastLimit -> pluralStringResource(Res.plurals.profile_library_past_limit, note.count, note.count)
    is LibraryNote.Game -> stringResource(
        when (note.note) {
            GameNote.SavedRunning -> Res.string.profile_library_saved_running
            GameNote.SavedUnknown -> Res.string.profile_library_saved_unknown
            GameNote.SavedCustomGame -> Res.string.profile_library_saved_custom_game
            GameNote.Running -> Res.string.profile_library_running
            GameNote.Unknown -> Res.string.profile_library_unknown
        },
    )
}

@Composable
private fun EmptyLibrary(canInstall: Boolean, importing: ImportBanner?, actions: LibraryActions, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, Cookie.Eight.shape), contentAlignment = Alignment.Center) {
                HideoutIcon(HideoutIcons.Layers, null, size = 40.dp)
            }
            if (importing != null) {
                Text(importing.wait?.let { importNeed(it) } ?: stringResource(Res.string.library_importing_empty_title), style = HideoutTheme.typography.titleSection, textAlign = TextAlign.Center)
                return@Column
            }
            Text(stringResource(Res.string.profile_library_empty_title), style = HideoutTheme.typography.titleSection, textAlign = TextAlign.Center)
            Text(stringResource(Res.string.profile_library_empty_message), style = HideoutTheme.typography.notice, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HideoutButton(stringResource(Res.string.profile_library_discover), actions::discover, leadingIcon = HideoutIcons.Discover)
                HideoutButton(stringResource(Res.string.profile_library_add_file), actions::addFile, variant = ButtonVariant.Tonal, leadingIcon = HideoutIcons.Plus, enabled = canInstall)
            }
            Text(stringResource(Res.string.profile_library_drop_hint), style = HideoutTheme.typography.captionSmall, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
            HideoutButton(stringResource(Res.string.profile_library_import), actions::importFromManager, variant = ButtonVariant.Text, size = ButtonSize.Small)
        }
    }
}

@Composable
private fun EmptyDetailsPanel() {
    Column(Modifier.fillMaxSize()) {
        PaneHeader(stringResource(Res.string.details_title))
        Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(Res.string.profile_library_details_placeholder), style = HideoutTheme.typography.caption, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center)
        }
    }
}
