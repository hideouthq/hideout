package gg.hideouthq.feature.imports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CheckboxRow
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.NoteCard
import gg.hideouthq.core.designsystem.component.OptionCard
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.ImportProblem
import gg.hideouthq.core.library.ImportWait
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.ui.LibraryLabels
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.import_choose_lead
import gg.hideouthq.resources.import_choose_lead_file
import gg.hideouthq.resources.import_choose_title
import gg.hideouthq.resources.import_count_commands
import gg.hideouthq.resources.import_count_crosshairs
import gg.hideouthq.resources.import_count_mods
import gg.hideouthq.resources.import_count_profiles
import gg.hideouthq.resources.import_dialog_import
import gg.hideouthq.resources.import_export_file
import gg.hideouthq.resources.import_export_file_detail
import gg.hideouthq.resources.import_from
import gg.hideouthq.resources.import_from_file
import gg.hideouthq.resources.import_importing
import gg.hideouthq.resources.import_manager_dmm
import gg.hideouthq.resources.import_manager_grimoire
import gg.hideouthq.resources.import_missing
import gg.hideouthq.resources.import_missing_file
import gg.hideouthq.resources.import_other_source
import gg.hideouthq.resources.import_pick_export
import gg.hideouthq.resources.import_pick_manager
import gg.hideouthq.resources.import_point_at
import gg.hideouthq.resources.import_problem_backup
import gg.hideouthq.resources.import_problem_busy
import gg.hideouthq.resources.import_problem_busy_why
import gg.hideouthq.resources.import_problem_game_missing
import gg.hideouthq.resources.import_problem_game_unreadable
import gg.hideouthq.resources.import_problem_not_a_bundle
import gg.hideouthq.resources.import_row_autoexec
import gg.hideouthq.resources.import_row_autoexec_detail
import gg.hideouthq.resources.import_row_crosshair
import gg.hideouthq.resources.import_row_crosshair_detail
import gg.hideouthq.resources.import_row_mods
import gg.hideouthq.resources.import_row_mods_detail
import gg.hideouthq.resources.import_row_profiles
import gg.hideouthq.resources.import_source_lead
import gg.hideouthq.resources.import_source_title
import gg.hideouthq.resources.import_sources
import gg.hideouthq.resources.import_step_waits_custom_game
import gg.hideouthq.resources.import_step_waits_game
import gg.hideouthq.resources.import_step_waits_manager
import gg.hideouthq.resources.import_stopped
import gg.hideouthq.resources.import_unavailable
import gg.hideouthq.resources.import_what
import gg.hideouthq.resources.library_import_needs_custom_game
import gg.hideouthq.resources.library_import_needs_game
import gg.hideouthq.resources.library_import_needs_manager
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

interface ImportActions {
    /** Takes [option] as the source: a manager found shows what it brings, anything else asks for its folder first. */
    fun pickSource(option: SourceOption)

    fun toggleItem(item: ImportItem, ticked: Boolean)

    /** Opens the folder picker, titled [title], for the folder the source asks for. */
    fun pickSourceFolder(title: String)

    /** Back to the list of sources, letting go of a run that stopped. */
    fun otherSource()

    fun startImport()
}

/** The page's title and lead, which each host sets in its own style; an export's mods are copied in, not adopted where they are. */
@Composable
fun importHeading(ui: ImportUi): Pair<String, String> = when {
    ui.listing -> stringResource(Res.string.import_source_title) to stringResource(Res.string.import_source_lead)
    else -> stringResource(Res.string.import_choose_title) to stringResource(if (ui.from == SourceOption.ExportFile) Res.string.import_choose_lead_file else Res.string.import_choose_lead)
}

@Composable
fun importNeed(wait: ImportWait): String = when (wait) {
    ImportWait.GameOpen -> stringResource(Res.string.library_import_needs_game)
    ImportWait.CustomGame -> stringResource(Res.string.library_import_needs_custom_game)
    is ImportWait.ManagerOpen -> stringResource(Res.string.library_import_needs_manager, managerName(wait.manager))
}

@Composable
fun fromLine(from: SourceOption): String = when (from) {
    is SourceOption.Installed -> stringResource(Res.string.import_from, managerName(from.manager))
    else -> stringResource(Res.string.import_from_file)
}

@Composable
fun importLabel(ui: ImportUi): String = stringResource(if (ui.importing) Res.string.import_importing else Res.string.import_dialog_import)

@Composable
fun ImportContent(state: ImportUi, actions: ImportActions, modifier: Modifier = Modifier) {
    state.asking?.let { option ->
        val title = when (option) {
            is SourceOption.Installed -> stringResource(Res.string.import_pick_manager, managerName(option.manager))
            else -> stringResource(Res.string.import_pick_export)
        }
        LaunchedEffect(option) { actions.pickSourceFolder(title) }
    }
    if (state.listing) SourceList(state.sources, actions, modifier) else ChooseList(state, actions, modifier)
}

@Composable
private fun SourceList(sources: List<SourceCard>, actions: ImportActions, modifier: Modifier) {
    val label = stringResource(Res.string.import_sources)
    Column(modifier.fillMaxWidth().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(StackGap)) {
        sources.forEach { card ->
            val option = card.option
            OptionCard(
                title = sourceName(option),
                detail = when (option) {
                    is SourceOption.Installed -> when {
                        !card.found -> stringResource(Res.string.import_point_at)
                        else -> card.counts?.let { counts -> countsLine(counts, commands = false).ifEmpty { countsLine(counts, commands = true) } }.orEmpty()
                    }
                    SourceOption.ExportFile -> stringResource(Res.string.import_export_file_detail)
                },
                icon = sourceIcon(option),
                selected = false,
                onClick = { actions.pickSource(option) },
            )
        }
    }
}

@Composable
private fun ChooseList(state: ImportUi, actions: ImportActions, modifier: Modifier) {
    val label = stringResource(Res.string.import_what)
    val from = state.from
    val problem = state.problem
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(StackGap)) {
        if (problem != null) NoteCard(stringResource(if (state.stopped) Res.string.import_stopped else Res.string.import_unavailable), problemText(problem))
        Column(Modifier.fillMaxWidth().semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(StackGap)) {
            state.rows.forEach { row ->
                CheckboxRow(
                    title = itemName(row.item),
                    detail = when (row.item) {
                        ImportItem.Mods -> stringResource(Res.string.import_row_mods_detail)
                        ImportItem.Profiles -> row.names.joinToString(", ")
                        ImportItem.Crosshair -> stringResource(Res.string.import_row_crosshair_detail)
                        ImportItem.Autoexec -> stringResource(Res.string.import_row_autoexec_detail)
                    },
                    checked = row.ticked,
                    onCheckedChange = { actions.toggleItem(row.item, it) },
                    count = row.count,
                    enabled = row.enabled,
                )
            }
        }
        if (state.missing > 0 && from != null) {
            Text(
                if (from is SourceOption.Installed) pluralStringResource(Res.plurals.import_missing, state.missing, state.missing, managerName(from.manager))
                else pluralStringResource(Res.plurals.import_missing_file, state.missing, state.missing),
                style = HideoutTheme.typography.subtitle, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 14.dp, top = NoteGap - StackGap),
            )
        }
        if (!state.importing) HideoutButton(stringResource(Res.string.import_other_source), actions::otherSource, variant = ButtonVariant.Text, size = ButtonSize.Small)
    }
}

@Composable
internal fun managerName(manager: Manager): String = stringResource(
    when (manager) {
        Manager.DeadlockModManager -> Res.string.import_manager_dmm
        Manager.Grimoire -> Res.string.import_manager_grimoire
    },
)

@Composable
internal fun sourceName(option: SourceOption): String = when (option) {
    is SourceOption.Installed -> managerName(option.manager)
    SourceOption.ExportFile -> stringResource(Res.string.import_export_file)
}

internal fun sourceIcon(option: SourceOption): ImageVector = when (option) {
    is SourceOption.Installed -> when (option.manager) {
        Manager.DeadlockModManager -> HideoutIcons.Layers
        Manager.Grimoire -> HideoutIcons.Book
    }
    SourceOption.ExportFile -> HideoutIcons.Braces
}

@Composable
internal fun itemName(item: ImportItem): String = stringResource(
    when (item) {
        ImportItem.Mods -> Res.string.import_row_mods
        ImportItem.Profiles -> Res.string.import_row_profiles
        ImportItem.Crosshair -> Res.string.import_row_crosshair
        ImportItem.Autoexec -> Res.string.import_row_autoexec
    },
)

@Composable
internal fun countsLine(counts: ImportCounts, commands: Boolean): String = countParts(counts, commands).joinToString(", ")

@Composable
internal fun countParts(counts: ImportCounts, commands: Boolean = true): List<String> = listOfNotNull(
    counts.mods.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.import_count_mods, it, it) },
    counts.profiles.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.import_count_profiles, it, it) },
    counts.crosshairs.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.import_count_crosshairs, it, it) },
    counts.commands.takeIf { it > 0 && commands }?.let { pluralStringResource(Res.plurals.import_count_commands, it, it) },
)

@Composable
fun waitDetail(wait: ImportWait): String = when (wait) {
    ImportWait.GameOpen -> stringResource(Res.string.import_step_waits_game)
    ImportWait.CustomGame -> stringResource(Res.string.import_step_waits_custom_game)
    is ImportWait.ManagerOpen -> stringResource(Res.string.import_step_waits_manager, managerName(wait.manager))
}

@Composable
internal fun problemText(problem: ImportProblem): String = when (problem) {
    ImportProblem.GameMissing -> stringResource(Res.string.import_problem_game_missing)
    is ImportProblem.LibraryBusy -> problem.reason?.let { stringResource(Res.string.import_problem_busy_why, it) } ?: stringResource(Res.string.import_problem_busy)
    is ImportProblem.SourceUnreadable -> problem.reason
    is ImportProblem.NotABundle -> stringResource(Res.string.import_problem_not_a_bundle, problem.reason)
    is ImportProblem.GameUnreadable -> stringResource(Res.string.import_problem_game_unreadable, problem.reason)
    is ImportProblem.BackupFailed -> stringResource(Res.string.import_problem_backup, problem.reason)
    is ImportProblem.Library -> LibraryLabels.problem(problem.problem)
}

private val StackGap = 8.dp
private val NoteGap = 14.dp
