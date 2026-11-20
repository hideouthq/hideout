package gg.hideouthq.feature.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.FolderPicker
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.library.FilePriorities
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.OrderView
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ReinstallFrom
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.UpdatePlan
import gg.hideouthq.core.migration.ImportPhase
import gg.hideouthq.core.migration.Migration
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.ui.LocalFileImport
import gg.hideouthq.core.ui.LocalPaneFrame
import gg.hideouthq.core.ui.ModLabels
import gg.hideouthq.core.ui.WindowModal
import gg.hideouthq.feature.imports.ImportDialog
import gg.hideouthq.feature.imports.ImportPicker
import gg.hideouthq.feature.library.components.FilePriorityDialog
import gg.hideouthq.feature.library.components.PartsOrderDialog

/** What Library's controls do: [LibraryViewModel]'s, and the four the route adds, which lead elsewhere. */
interface LibraryActions {
    fun discover() = Unit
    fun addFile() = Unit
    fun importFromManager() = Unit
    /** Opens the GameBanana page [LibraryRow.page] names, by its mod id, not the manifest's own [LibraryRow.installId]. */
    fun viewPage(modId: String) = Unit
    fun setQuery(value: String)
    fun setFilter(filter: LibraryFilter)
    /** Lists every installed mod or the active profile's, and keeps that choice for the next visit. */
    fun setScope(scope: LibraryScope)
    fun setLayout(layout: LibraryLayout)
    fun select(installId: String, gesture: SelectGesture = SelectGesture.Only)
    /** Picks every visible row. */
    fun selectAll()
    /** Leaves only the row picked last. */
    fun clearSelection()
    /** Turns the picked rows on or off together in [profile], the active one unless given, with an Undo. */
    fun setSelectedEnabled(enabled: Boolean, profile: ProfileId? = null)
    /** Turns off every mod that is on, with an Undo. */
    fun turnAllOff()
    fun setEnabled(installId: String, enabled: Boolean, profile: ProfileId? = null)
    fun showFiles(installId: String)
    fun setFavourite(installId: String, favourite: Boolean)
    /** Opens the `addons` folder of [installId]'s game, where files Hideout does not own sit. */
    fun showAddons(installId: String)
    /** Opens what shows [installId]'s models instead of it and what it shares files with. */
    fun inspectFilePriority(installId: String) = Unit
    fun changeFiles(installId: String)
    /** Opens the order of [installId]'s parts, for a mod of several. */
    fun reorderParts(installId: String) = Unit
    /** Puts back [installId]'s original files where they are missing or changed; the route asks for the file a local mod came from. */
    fun reinstall(installId: String)
    /** Keeps what was changed in [installId]'s files outside Hideout as its own. */
    fun keepEdits(installId: String)
    /** Leaves [installId]'s files as they are instead of reviewing them, until its GameBanana page lists other files. */
    fun keepCurrentFiles(installId: String)
    /** Keeps the files of every mod there are files to review for. */
    fun keepAllCurrentFiles()
    /** Asks GameBanana again about the installed mods, through the same check Refresh starts. */
    fun checkForUpdates()
    fun updateAll()
    fun update(plan: UpdatePlan)
    /** Uninstalls [installId] at once, with an Undo. */
    fun uninstall(installId: String)
    /** Uninstalls every picked mod at once, with one Undo. */
    fun uninstallSelected()
    fun cancelDownload(modId: String)
    /** Moves [installId] to [toIndex] of [view], the order [LibraryUiState.orderView] showed when the drag or menu began. */
    fun moveSet(view: OrderView, installId: String, toIndex: Int)
    fun openCompat(installId: String)
    /** Opens every mod with a confirmed compatibility problem or a change under way, grouped. */
    fun reviewCompat()
    fun closeCompat()
    /** Clears the summary's report of how the asked-for repairs and restores ended. */
    fun dismissCompat()
    fun applyRepair(installId: String)
    fun useOriginal(installId: String)
    fun repairAll()
    fun checkCompatibility()
    /** Opens the review of the sets that load and are not Hideout's. */
    fun reviewLeftovers()
    fun closeLeftovers()
    /** Takes the leftovers at [slots] into the library. */
    fun addLeftovers(slots: List<Slot>)
    /** Takes the leftovers at [slots] out of the game. */
    fun moveLeftoversOut(slots: List<Slot>)
    /** Opens the folder the last Move out took files to. */
    fun openMovedFolder()
}

@Composable
fun LibraryRoute(
    library: ModLibrary,
    migration: Migration,
    folderPicker: FolderPicker,
    folders: FolderOpener,
    modal: WindowModal,
    onViewPage: (String) -> Unit,
    catalog: CatalogRepository? = null,
    preferences: Preferences? = null,
    onDiscover: () -> Unit,
    filePriorities: FilePriorities? = null,
    show: String? = null,
    onShown: () -> Unit = {},
) {
    val fileImport = LocalFileImport.current
    val categories = ModCategory.entries.associateWith { ModLabels.singular(it) }
    val viewModel = viewModel { LibraryViewModel(library, folders, catalog, preferences, categories, filePriorities?.conflicts(), migration.state) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var inspecting by remember { mutableStateOf<String?>(null) }
    var reordering by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<ImportPicker?>(null) }
    val scope = rememberCoroutineScope()
    val actions = remember(viewModel, onViewPage, onDiscover, fileImport) {
        object : LibraryActions by viewModel {
            override fun discover() = onDiscover()
            override fun addFile() { fileImport?.pick() }
            override fun reinstall(installId: String) {
                val from = viewModel.uiState.value.rows.firstOrNull { it.installId == installId }?.reinstall
                if (from == ReinstallFrom.File) fileImport?.pick(reinstall = installId) else viewModel.reinstall(installId)
            }
            override fun importFromManager() {
                if (migration.state.value.run?.phase is ImportPhase.Finished) migration.dismiss()
                importing = ImportPicker(migration, folderPicker, scope)
            }
            override fun reorderParts(installId: String) { reordering = installId }
            override fun inspectFilePriority(installId: String) { if (filePriorities != null) inspecting = installId }
            override fun viewPage(modId: String) = onViewPage(modId)
        }
    }
    val frame = LocalPaneFrame.current
    DisposableEffect(viewModel) { onDispose(viewModel::forgetTurnedOff) }
    LaunchedEffect(show) {
        if (show != null) {
            viewModel.show(show)
            frame?.openSidePanel()
            onShown()
        }
    }
    LibraryScreen(state, actions, modal, onInspectFilePriority = if (filePriorities != null) actions::inspectFilePriority else null)
    val libraryState by library.state.collectAsStateWithLifecycle()
    val parts = reordering?.let { id -> (libraryState.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == id } }
    PartsOrderDialog(parts, modal, onSave = { order -> reordering?.let { library.reorderParts(it, order) } }, onDismiss = { reordering = null })
    ImportDialog(importing, modal) {
        migration.dismiss()
        importing = null
    }
    if (filePriorities != null) {
        val row = inspecting?.let { id -> state.rows.firstOrNull { it.installId == id } }
        FilePriorityDialog(
            filePriorities, library, inspecting, row?.title.orEmpty(), row?.hiddenBy.orEmpty(), modal,
            onReorderParts = { inspecting?.let { id -> inspecting = null; reordering = id } },
            onOpenFolder = { inspecting?.let(actions::showAddons) },
            onDismiss = { inspecting = null },
        )
    }
}
