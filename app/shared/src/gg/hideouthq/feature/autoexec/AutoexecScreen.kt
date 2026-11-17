package gg.hideouthq.feature.autoexec

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.autoexec.CommandCategory
import gg.hideouthq.core.autoexec.CommandRef
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CardHeader
import gg.hideouthq.core.designsystem.component.ChipTone
import gg.hideouthq.core.designsystem.component.EmptyState
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutIconButton
import gg.hideouthq.core.designsystem.component.HideoutTooltip
import gg.hideouthq.core.designsystem.component.PageCard
import gg.hideouthq.core.designsystem.component.PageHeader
import gg.hideouthq.core.designsystem.component.ScrollColumn
import gg.hideouthq.core.designsystem.component.ScrollHairline
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.component.StatusChip
import gg.hideouthq.core.designsystem.component.TabContent
import gg.hideouthq.core.designsystem.component.isScrolled
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.MainAndSide
import gg.hideouthq.core.ui.PaneScaffold
import gg.hideouthq.core.ui.SearchTopBar
import gg.hideouthq.core.ui.fitsBeside
import gg.hideouthq.core.ui.transition.staggeredEnter
import gg.hideouthq.feature.autoexec.components.AutoexecText
import gg.hideouthq.feature.autoexec.components.CommandLibraryCard
import gg.hideouthq.feature.autoexec.components.CommandList
import gg.hideouthq.feature.autoexec.components.autoexecType
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.autoexec_commands
import gg.hideouthq.resources.autoexec_in_file
import gg.hideouthq.resources.autoexec_open_folder
import gg.hideouthq.resources.autoexec_reading
import gg.hideouthq.resources.autoexec_runs_at_launch
import gg.hideouthq.resources.autoexec_runs_at_launch_tip
import gg.hideouthq.resources.autoexec_subtitle
import gg.hideouthq.resources.autoexec_unreadable
import gg.hideouthq.resources.autoexec_view_list
import gg.hideouthq.resources.autoexec_view_text
import gg.hideouthq.resources.nav_autoexec
import gg.hideouthq.resources.problem_game_not_found
import gg.hideouthq.resources.search_catalogue
import gg.hideouthq.resources.settings_game_choose
import gg.hideouthq.resources.settings_game_not_found_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

interface AutoexecPageActions {
    fun setView(view: AutoexecView)
    fun setValue(ref: CommandRef, value: String)
    fun setOn(ref: CommandRef, on: Boolean)
    fun remove(ref: CommandRef)
    fun setAdding(text: String)
    fun add()
    fun search(text: String)
    fun filter(category: CommandCategory?)

    /** A library row's button: + adds its suggested value, the check takes every line of it out. */
    fun toggle(item: LibraryItem)
    fun openInEditor()
    fun showInFolder()
    fun retry()

    fun dismissNotOpened()
}

@Composable
fun AutoexecScreen(state: AutoexecUiState, editor: TextFieldState, actions: AutoexecPageActions, onSearch: (String) -> Unit, onChooseGame: () -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    var beside by remember { mutableStateOf(true) }
    PaneScaffold(
        modifier = modifier,
        topBar = { SearchTopBar(query = "", onQueryChange = onSearch, placeholder = stringResource(Res.string.search_catalogue)) },
        scrolled = !beside && scroll.isScrolled,
        staggered = true,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            beside = fitsBeside(maxWidth - PagePadding * 2, LibraryWidth)
            if (beside) {
                Column(Modifier.fillMaxSize().padding(horizontal = PagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Header(state.path, actions, Modifier.staggeredEnter(0))
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            ScrollColumn(Modifier.fillMaxSize(), scroll, PaddingValues(bottom = PagePadding)) {
                                FileCard(state, editor, actions, onChooseGame, Modifier.fillMaxWidth().staggeredEnter(1))
                            }
                            ScrollHairline(scroll.isScrolled, Modifier.align(Alignment.TopCenter))
                        }
                        CommandLibraryCard(state.library, actions, Modifier.width(LibraryWidth).padding(bottom = PagePadding).staggeredEnter(2), fitHeight = true)
                    }
                }
            } else {
                ScrollColumn(Modifier.fillMaxSize(), scroll, PaddingValues(start = PagePadding, end = PagePadding, bottom = PagePadding), Arrangement.spacedBy(12.dp)) {
                    Header(state.path, actions, Modifier.staggeredEnter(0))
                    MainAndSide(
                        LibraryWidth,
                        main = { FileCard(state, editor, actions, onChooseGame, it.staggeredEnter(1)) },
                        side = { CommandLibraryCard(state.library, actions, it.staggeredEnter(2)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(path: String, actions: AutoexecPageActions, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    PageHeader(stringResource(Res.string.nav_autoexec), stringResource(Res.string.autoexec_subtitle), modifier.fillMaxWidth()) {
        HideoutTooltip(stringResource(Res.string.autoexec_runs_at_launch_tip)) {
            StatusChip(stringResource(Res.string.autoexec_runs_at_launch), ChipTone.Good, leadingIcon = HideoutIcons.CheckBold)
        }
        Row(
            Modifier.height(36.dp).clip(HideoutShapes.Full).background(scheme.surfaceContainer).padding(start = 12.dp, end = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(path, style = HideoutTheme.typography.monoPath, color = scheme.onSurfaceVariant, maxLines = 1)
            HideoutIconButton(HideoutIcons.Folder, stringResource(Res.string.autoexec_open_folder), actions::showInFolder, size = 30.dp, iconSize = 16.dp)
        }
    }
}

@Composable
private fun FileCard(state: AutoexecUiState, editor: TextFieldState, actions: AutoexecPageActions, onChooseGame: () -> Unit, modifier: Modifier) {
    val file = state.file
    PageCard(modifier.animateContentSize(HideoutTheme.motion.spatialDefault())) {
        val count = (file as? AutoexecFile.Ready)?.commandCount
        CardHeader(stringResource(Res.string.autoexec_in_file), count?.let { pluralStringResource(Res.plurals.autoexec_commands, it, it) }.orEmpty()) {
            if (file is AutoexecFile.Ready) {
                val labels = mapOf(AutoexecView.List to stringResource(Res.string.autoexec_view_list), AutoexecView.Text to stringResource(Res.string.autoexec_view_text))
                SegmentedControl(AutoexecView.entries, state.view, actions::setView, { labels.getValue(it) }, style = SegmentedStyle.Card)
            }
        }
        when (file) {
            AutoexecFile.NoGame -> EmptyState(HideoutIcons.Monitor, stringResource(Res.string.settings_game_not_found_title), stringResource(Res.string.problem_game_not_found)) {
                HideoutButton(stringResource(Res.string.settings_game_choose), onChooseGame, variant = ButtonVariant.Tonal)
            }
            AutoexecFile.Reading -> Text(
                stringResource(Res.string.autoexec_reading), Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp),
                style = autoexecType().note, color = MaterialTheme.colorScheme.outline,
            )
            is AutoexecFile.Unreadable -> EmptyState(HideoutIcons.Warning, stringResource(Res.string.autoexec_unreadable), file.reason)
            is AutoexecFile.Ready -> TabContent(state.view, AutoexecView::ordinal) { view ->
                when (view) {
                    AutoexecView.List -> CommandList(file, state.adding, state.note, state.notOpened, actions)
                    AutoexecView.Text -> AutoexecText(editor, file.locked, state.note, state.notOpened, actions)
                }
            }
        }
    }
}

private val LibraryWidth = 420.dp
private val PagePadding = 16.dp
