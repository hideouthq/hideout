package gg.hideouthq.feature.imports

import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.migration.ImportChoice
import gg.hideouthq.core.migration.ImportPhase
import gg.hideouthq.core.migration.ImportSource
import gg.hideouthq.core.migration.Migration
import gg.hideouthq.core.migration.MigrationState
import gg.hideouthq.core.migration.ModsChoice
import gg.hideouthq.core.migration.Preview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the user picked on the import page: the [source], null for the one manager found, what to bring from it, and
 * whether they asked for the list of sources instead. [asking] is the option whose folder is asked for, until its
 * picker closes.
 */
data class ImportPick(
    val source: ImportSource? = null,
    val choice: ImportChoice = ImportChoice.Everything,
    val listing: Boolean = false,
    val asking: SourceOption? = null,
)

/** Every import is [Migration]'s, so leaving a host never stops one. */
class ImportPicker(
    private val migration: Migration,
    private val folderPicker: FolderPicker,
    private val scope: CoroutineScope,
) : ImportActions {
    private val pick = MutableStateFlow(ImportPick())

    val ui: Flow<ImportUi> = combine(pick, migration.state) { pick, imports -> importUi(pick, imports) }

    init {
        scope.launch {
            migration.state.first { it.sources.isNotEmpty() }
            lookAgain()
        }
    }

    /** Counts again what every manager found would bring, as after the game's folder changed. */
    fun lookAgain() = migration.state.value.sources.filter { it.found }.forEach { migration.look(ImportSource.Installed(it.manager)) }

    override fun pickSource(option: SourceOption) {
        val found = option is SourceOption.Installed && migration.state.value.sources.any { it.manager == option.manager && it.found }
        if (found) choose(ImportSource.Installed(option.manager)) else pick.update { it.copy(asking = option) }
    }

    override fun toggleItem(item: ImportItem, ticked: Boolean) = pick.update { it.copy(choice = it.choice.with(item, ticked)) }

    override fun pickSourceFolder(title: String) {
        val option = pick.value.asking ?: return
        scope.launch {
            try {
                val chosen = folderPicker.pick(title, null) ?: return@launch
                choose(if (option is SourceOption.Installed) ImportSource.Installed(option.manager, chosen) else ImportSource.Bundle(chosen))
            } finally {
                pick.update { it.copy(asking = null) }
            }
        }
    }

    override fun otherSource() {
        migration.dismiss()
        pick.update { it.copy(source = null, listing = true) }
    }

    override fun startImport() {
        val imports = migration.state.value
        if (!importUi(pick.value, imports).canImport) return
        val source = sourceOf(pick.value, imports) ?: return
        pick.update { it.copy(source = source) }
        migration.import(source, pick.value.choice)
    }

    private fun choose(source: ImportSource) {
        migration.dismiss()
        pick.update { it.copy(source = source, choice = ImportChoice.Everything, listing = false) }
        if (migration.state.value.previews[source] !is Preview.Ready) migration.look(source)
    }
}

/**
 * The page as it stands. With one manager found that has something to bring, it opens on what that brings; with
 * several, or none, on the list. A run shows whichever host started it.
 */
fun importUi(pick: ImportPick, imports: MigrationState): ImportUi {
    fun previewOf(manager: Manager) = imports.previews[ImportSource.Installed(manager)]
    val looking = imports.sources.isEmpty() || imports.sources.any { it.found && previewOf(it.manager).let { preview -> preview == null || preview == Preview.Looking } }
    val sources = sourceCards(imports)
    val source = sourceOf(pick, imports) ?: return ImportUi(looking, sources, listing = true, asking = pick.asking)
    val run = imports.run
    val phase = run?.phase
    val importing = run?.running == true
    val preview = imports.previews[source]
    val ready = preview as? Preview.Ready
    return ImportUi(
        looking = looking,
        sources = sources,
        from = optionOf(source),
        preview = ready,
        rows = ready?.let { rows(it, if (importing) run.choice else pick.choice, enabled = !importing) }.orEmpty(),
        problem = (phase as? ImportPhase.Stopped)?.problem ?: (preview as? Preview.Failed)?.problem,
        importing = importing,
        stopped = phase is ImportPhase.Stopped,
        wait = (phase as? ImportPhase.Waiting)?.wait,
        finished = phase is ImportPhase.Finished,
    )
}

fun optionOf(source: ImportSource): SourceOption = when (source) {
    is ImportSource.Installed -> SourceOption.Installed(source.manager)
    is ImportSource.Bundle -> SourceOption.ExportFile
}

private fun sourceOf(pick: ImportPick, imports: MigrationState): ImportSource? =
    imports.run?.source ?: pick.source ?: sourceCards(imports).singleOrNull { it.found }?.takeUnless { pick.listing }?.let { ImportSource.Installed((it.option as SourceOption.Installed).manager) }

private fun sourceCards(imports: MigrationState): List<SourceCard> {
    val managers = imports.sources.mapNotNull { source ->
        val option = SourceOption.Installed(source.manager)
        val counts = (imports.previews[ImportSource.Installed(source.manager)] as? Preview.Ready)?.counts
        when {
            source.found -> SourceCard(option, found = true, counts).takeIf { counts?.isEmpty != true }
            source.manager in Pointable -> SourceCard(option)
            else -> null
        }
    }
    return managers + SourceCard(SourceOption.ExportFile)
}

private fun rows(preview: Preview.Ready, choice: ImportChoice, enabled: Boolean): List<ImportRow> = listOfNotNull(
    ImportRow(ImportItem.Mods, preview.mods.size, choice.mods != ModsChoice.None, enabled).takeIf { it.count > 0 },
    ImportRow(ImportItem.Profiles, preview.profiles.size, choice.mods == ModsChoice.ModsAndProfiles, enabled && choice.mods != ModsChoice.None, preview.profiles.map { it.name })
        .takeIf { it.count > 0 },
    ImportRow(ImportItem.Crosshair, preview.counts.crosshairs, choice.crosshair, enabled).takeIf { it.count > 0 },
    ImportRow(ImportItem.Autoexec, preview.commands.size, choice.commands, enabled).takeIf { it.count > 0 },
)

private fun ImportChoice.with(item: ImportItem, ticked: Boolean): ImportChoice = when (item) {
    ImportItem.Mods -> copy(mods = if (ticked) ModsChoice.Mods else ModsChoice.None)
    ImportItem.Profiles -> if (mods == ModsChoice.None) this else copy(mods = if (ticked) ModsChoice.ModsAndProfiles else ModsChoice.Mods)
    ImportItem.Crosshair -> copy(crosshair = ticked)
    ImportItem.Autoexec -> copy(commands = ticked)
}

private val Pointable = setOf(Manager.Grimoire)
