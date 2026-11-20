package gg.hideouthq.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.library.ChangeStage
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.CitadelPath
import gg.hideouthq.core.library.CompatLabel
import gg.hideouthq.core.library.CompatStatus
import gg.hideouthq.core.library.ConflictReport
import gg.hideouthq.core.library.FilesObservation
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.library.InstallationUpdates
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledUpdates
import gg.hideouthq.core.library.Leftover
import gg.hideouthq.core.library.LeftoverOutcome
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.Operation
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.OrderView
import gg.hideouthq.core.library.ProfileBook
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ReinstallFrom
import gg.hideouthq.core.library.RepairChoice
import gg.hideouthq.core.library.RepairWork
import gg.hideouthq.core.library.Slot
import gg.hideouthq.core.library.SourceState
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.UpdatePlan
import gg.hideouthq.core.library.VersionSource
import gg.hideouthq.core.library.Winner
import gg.hideouthq.core.library.cancellable
import gg.hideouthq.core.library.citadelPath
import gg.hideouthq.core.library.folder
import gg.hideouthq.core.library.reinstallFrom
import gg.hideouthq.core.library.setFolder
import gg.hideouthq.core.library.versionNames
import gg.hideouthq.core.migration.ImportPhase
import gg.hideouthq.core.migration.MigrationState
import gg.hideouthq.core.migration.ModsChoice
import gg.hideouthq.core.migration.Preview
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.feature.imports.optionOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Instant

private const val StopTimeoutMillis = 5_000L
private const val StoredList = "list"
private const val StoredCards = "cards"
private const val StoredAll = "all"
private const val StoredProfile = "profile"

/**
 * [categories] are the labels rows show for each category, which search matches against; [conflicts], the models each
 * mod loses; [imports], the import that may be under way.
 */
class LibraryViewModel(
    private val library: ModLibrary,
    private val folders: FolderOpener,
    private val catalog: CatalogRepository? = null,
    private val preferences: Preferences? = null,
    private val categories: Map<ModCategory, String> = emptyMap(),
    conflicts: Flow<ConflictReport>? = null,
    imports: Flow<MigrationState> = flowOf(MigrationState()),
) : ViewModel(), LibraryActions {
    private val local = MutableStateFlow(Local())

    private var shown = Shown(emptyList(), emptyList())

    private val catalogUpdates = catalog?.mods?.onStart { emit(emptyList()) } ?: flowOf(emptyList())
    private val observations = catalog?.installedFiles ?: MutableStateFlow(emptyMap())
    private val checking = catalog?.freshness?.map { Checking(it.checking, it.busyUntil) }?.distinctUntilChanged() ?: flowOf(Checking())

    private val inputs = combine(library.state, conflicts ?: flowOf(ConflictReport()), imports, preferences?.favourites ?: flowOf(emptySet()), ::Inputs)

    val uiState: StateFlow<LibraryUiState> = combine(inputs, local, catalogUpdates, observations, checking) { inputs, local, _, observed, checking ->
        build(inputs, local, observed, checking)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), LibraryUiState())

    private class Inputs(val state: LibraryState, val report: ConflictReport, val migration: MigrationState, val favourites: Set<String>)

    private data class Checking(val running: Boolean = false, val busyUntil: Instant? = null)

    init {
        viewModelScope.launch {
            library.state.map { it.shownProfiles?.active }.distinctUntilChanged().drop(1).collect { forgetTurnedOff() }
        }
        val prefs = preferences
        if (prefs == null) {
            local.update { it.copy(layoutReady = true) }
        } else {
            viewModelScope.launch {
                if (prefs.runningNoteSeen()) return@launch
                library.state.first { it.deadlock == GameActivity.Running }
                local.update { it.copy(runningNote = true) }
                prefs.setRunningNoteSeen()
                library.state.first { it.deadlock != GameActivity.Running }
                local.update { it.copy(runningNote = false) }
            }
            viewModelScope.launch {
                val stored = prefs.libraryLayout()
                val scope = prefs.libraryScope()
                local.update { current ->
                    current.copy(
                        layout = when (stored) {
                            StoredCards -> LibraryLayout.Cards
                            StoredList -> LibraryLayout.List
                            else -> current.layout
                        },
                        scope = if (scope == StoredProfile) LibraryScope.Profile else current.scope,
                        layoutReady = true,
                    )
                }
            }
        }
    }

    override fun setQuery(value: String) { local.update { it.copy(query = value) } }

    override fun setFilter(filter: LibraryFilter) { local.update { it.copy(filter = filter) } }

    override fun setScope(scope: LibraryScope) {
        local.update { it.copy(scope = scope, turnedOff = emptySet()) }
        preferences?.let { prefs ->
            viewModelScope.launch { prefs.setLibraryScope(if (scope == LibraryScope.Profile) StoredProfile else StoredAll) }
        }
    }

    /** Lets the profile's own view drop the mods turned off in it, once Library is no longer on screen. */
    fun forgetTurnedOff() { local.update { it.copy(turnedOff = emptySet()) } }

    /** Keeps [ids] listed in the profile's own view after they turn off there, so rows don't leave from under the pointer. */
    private fun keepListed(ids: Collection<String>) {
        local.update { if (it.scope == LibraryScope.Profile) it.copy(turnedOff = it.turnedOff + ids) else it }
    }

    override fun setLayout(layout: LibraryLayout) {
        local.update { it.copy(layout = layout, layoutReady = true) }
        preferences?.let { prefs ->
            viewModelScope.launch { prefs.setLibraryLayout(if (layout == LibraryLayout.Cards) StoredCards else StoredList) }
        }
    }

    override fun select(installId: String, gesture: SelectGesture) {
        local.update { local ->
            val order = local.visibleIds()
            val current = local.selection.ids.filter { it in order }.ifEmpty { shown.selectedIds }
            val anchor = local.selection.anchor?.takeIf { it in order } ?: current.firstOrNull()
            val ids = when (gesture) {
                SelectGesture.Only -> listOf(installId)
                SelectGesture.Toggle -> if (installId in current) (current - installId).ifEmpty { current } else current + installId
                SelectGesture.Range -> {
                    val from = order.indexOf(anchor)
                    val to = order.indexOf(installId)
                    if (from < 0 || to < 0) listOf(installId) else order.subList(minOf(from, to), maxOf(from, to) + 1)
                }
            }
            local.copy(selection = Selection(ids, if (gesture == SelectGesture.Range) anchor else installId))
        }
    }

    /** Picks installed mod [installId] alone, letting go of the search and filter when they hide it and listing it in the profile's own view. */
    fun show(installId: String) {
        local.update { local ->
            val row = shown.rows.firstOrNull { it.installId == installId }
            val hidden = row != null && (!row.matches(local.query) || !row.matchesFilter(local.filter))
            val cleared = if (hidden) local.copy(query = "", filter = LibraryFilter.All) else local
            val listed = if (row != null && !cleared.scopes(row)) cleared.copy(turnedOff = cleared.turnedOff + installId) else cleared
            listed.copy(selection = Selection(listOf(installId), installId))
        }
    }

    override fun selectAll() {
        local.update { local ->
            val order = local.visibleIds()
            if (order.isEmpty()) local else local.copy(selection = Selection(order, local.selection.anchor ?: order.first()))
        }
    }

    private fun Local.visibleIds(): List<String> = shown.rows.filter { shows(it) }.map { it.installId }

    private fun Local.scopes(row: LibraryRow): Boolean = scope == LibraryScope.All || row.enabled || row.installId in turnedOff

    private fun Local.shows(row: LibraryRow): Boolean = scopes(row) && row.matches(query) && row.matchesFilter(filter)

    override fun clearSelection() {
        val current = shown.selectedIds
        val keep = local.value.selection.anchor?.takeIf { it in current } ?: current.firstOrNull() ?: return
        local.update { it.copy(selection = Selection(listOf(keep), keep)) }
    }

    override fun setSelectedEnabled(enabled: Boolean, profile: ProfileId?) = turn(shown.selectedIds, enabled, profile)

    override fun turnAllOff() = turn(shown.rows.map { it.installId }, false, null)

    private fun turn(ids: List<String>, enabled: Boolean, profile: ProfileId?) {
        val state = library.state.value
        val book = state.shownProfiles ?: return
        val chosen = book.profiles.firstOrNull { it.id == (profile ?: book.active) } ?: return
        val changing = ids.filterTo(HashSet()) { (it in chosen.enabled) != enabled }
        if (changing.isEmpty() || !state.canEditChoices) return
        if (!enabled) keepListed(changing)
        library.setEnabledTogether(changing, enabled, chosen.id)
    }

    /**
     * Turns a set on or off at once in [profile], the active one unless given, with no confirmation, while other sets
     * may still be turning. A refusal is the library's [gg.hideouthq.core.library.LibraryState.choiceProblem].
     */
    override fun setEnabled(installId: String, enabled: Boolean, profile: ProfileId?) {
        if (!library.state.value.canEditChoices) return
        if (!enabled) keepListed(setOf(installId))
        library.setEnabled(installId, enabled, profile)
    }

    override fun showAddons(installId: String) {
        val record = records().firstOrNull { it.installId == installId } ?: return
        viewModelScope.launch { folders.open(record.gameRoot, CitadelPath.Addons) }
    }

    /** Shows the set's folder, `addons` or its own off folder, in the file manager. */
    override fun showFiles(installId: String) {
        val record = records().firstOrNull { it.installId == installId } ?: return
        viewModelScope.launch { folders.open(record.gameRoot, record.setFolder().citadelPath) }
    }

    override fun setFavourite(installId: String, favourite: Boolean) {
        val row = shown.rows.firstOrNull { it.installId == installId } ?: return
        preferences?.let { prefs -> viewModelScope.launch { prefs.setFavourite(row.favouriteId, favourite) } }
    }

    override fun uninstall(installId: String) = library.uninstall(setOf(installId))

    override fun uninstallSelected() = library.uninstall(shown.selectedIds.toSet())

    override fun cancelDownload(modId: String) { library.cancel(modId) }

    override fun changeFiles(installId: String) = library.changeFiles(installId)

    override fun reinstall(installId: String) = library.reinstall(installId)

    override fun keepEdits(installId: String) = library.keepEdits(installId)

    /** Keeps [installId]'s files over a review against what its submission offers now, as the last check read it. */
    override fun keepCurrentFiles(installId: String) {
        val offered = InstalledUpdates.of(records(), observations.value)[installId]?.offered ?: return
        library.keepReviewed(installId, offered)
    }

    /** Keeps every reviewed mod's files as they are, as Keep current files does for one. */
    override fun keepAllCurrentFiles() = uiState.value.rows.filter { it.update == UpdateState.Review }.forEach { keepCurrentFiles(it.installId) }

    /** Asks GameBanana again about every installed mod and the catalogue, as Refresh does elsewhere, and who each local mod is. */
    override fun checkForUpdates() {
        catalog?.refresh()
        library.identifyLocal()
    }

    /** Starts every update the rows offer, whatever the search or filter shows and whether they are on or off; rows are this game's only. */
    override fun updateAll() = library.updateAll(uiState.value.rows.mapNotNull { row -> row.updatePlan?.takeIf { row.operation == null } })

    override fun update(plan: UpdatePlan) { library.update(plan) }

    /** Opens [installId]'s details, over Review when Review is open, so closing them goes back to it. */
    override fun openCompat(installId: String) { local.update { it.copy(compatOpen = CompatRequest.Mod(installId, back = it.compatOpen as? CompatRequest.Review)) } }

    /**
     * Opens Review with every mod that has a problem or a change now, and those whose change this screen asked for, so
     * the report of how they ended leads to them; they stay listed until it closes.
     */
    override fun reviewCompat() {
        val shown = uiState.value.rows.filter { it.inReview }.map { it.installId }.toSet()
        local.update { it.copy(compatOpen = CompatRequest.Review(shown + it.followed)) }
    }

    /** Closes the compatibility dialog, back to Review for details opened from it; repairs and restores it started go on, and their rows keep showing them. */
    override fun closeCompat() { local.update { it.copy(compatOpen = (it.compatOpen as? CompatRequest.Mod)?.back) } }

    /** Clears the summary's report of how the asked-for changes ended. */
    override fun dismissCompat() { local.update { it.copy(followed = emptySet()) } }

    /** Saves that [installId] is to use repaired files; they are made and placed once Deadlock is known closed. */
    override fun applyRepair(installId: String) {
        if (!library.state.value.canEditChoices) return
        follow(setOf(installId))
        viewModelScope.launch { library.useRepairedFiles(setOf(installId)) }
    }

    /** Saves that [installId] is to use its original files, put back once Deadlock is known closed. */
    override fun useOriginal(installId: String) {
        if (!library.state.value.canEditChoices) return
        follow(setOf(installId))
        viewModelScope.launch { library.useOriginalFiles(installId) }
    }

    /**
     * Asks for every repair the summary offers, and again for each that didn't finish, as one decision; mods whose
     * original files were chosen stay as they are. Review stays open on the same mods, each showing its progress.
     */
    override fun repairAll() {
        val ids = uiState.value.compat.offered.toSet()
        if (ids.isEmpty() || !library.state.value.canEditChoices) return
        follow(ids)
        viewModelScope.launch { library.useRepairedFiles(ids) }
    }

    private fun follow(ids: Set<String>) = local.update { it.copy(followed = it.followed + ids) }

    override fun checkCompatibility() = library.checkCompatibility()

    override fun reviewLeftovers() = local.update { it.copy(leftovers = Leftovers(open = true, working = it.leftovers.working)) }

    override fun closeLeftovers() = local.update { it.copy(leftovers = it.leftovers.copy(open = false)) }

    override fun addLeftovers(slots: List<Slot>) = changeLeftovers(LeftoverAction.Adding, slots, library::addLeftovers)

    override fun moveLeftoversOut(slots: List<Slot>) = changeLeftovers(LeftoverAction.Moving, slots, library::moveOut)

    override fun openMovedFolder() {
        val moved = local.value.leftovers.outcome as? LeftoverOutcome.Moved ?: return
        val root = (library.state.value.game as? GameStatus.Ready)?.root ?: return
        viewModelScope.launch { folders.open(root, moved.folder) }
    }

    private fun changeLeftovers(action: LeftoverAction, slots: List<Slot>, change: suspend (List<Leftover>) -> LeftoverOutcome) {
        val leftovers = uiState.value.leftovers
        val sets = leftovers.sets.filter { it.slot in slots && (action == LeftoverAction.Moving || it.slot !in leftovers.copies) }
        if (sets.isEmpty() || local.value.leftovers.working != null) return
        local.update { it.copy(leftovers = it.leftovers.copy(working = action, outcome = null)) }
        viewModelScope.launch {
            val outcome = change(sets)
            local.update { it.copy(leftovers = it.leftovers.copy(working = null, outcome = outcome)) }
        }
    }

    /** [toIndex] counts the rows Library lists; a mod uninstalled and not gone yet keeps its place among them. */
    override fun moveSet(view: OrderView, installId: String, toIndex: Int) {
        val state = library.state.value
        if (!state.canEditChoices) return
        val listed = view.sets.filter { it !in state.leaving }
        val from = listed.indexOf(installId)
        if (from < 0) return
        val target = toIndex.coerceIn(0, listed.lastIndex)
        if (target == from) return
        val moved = listed.toMutableList().apply { add(target, removeAt(from)) }.iterator()
        library.reorder(view, view.sets.map { if (it in state.leaving) it else moved.next() })
    }

    private fun records(): List<ManifestRecord> = (library.state.value.library as? LibraryStatus.Ready)?.records.orEmpty()

    private fun build(inputs: Inputs, local: Local, observed: Map<SubmissionRef, FilesObservation>, checking: Checking): LibraryUiState {
        if (!local.layoutReady) return LibraryUiState(loading = true, layout = local.layout)
        return when (val status = inputs.state.library) {
            LibraryStatus.Loading -> LibraryUiState(loading = true, layout = local.layout)
            is LibraryStatus.Blocked -> LibraryUiState(loading = false, blocked = status.reason, layout = local.layout)
            is LibraryStatus.Ready -> ready(inputs, status, local, observed, checking)
        }
    }

    private fun importing(migration: MigrationState): ImportBanner? {
        val run = migration.run?.takeIf { it.running } ?: return null
        val mods = (migration.previews[run.source] as? Preview.Ready)?.mods?.size?.takeIf { run.choice.mods != ModsChoice.None }
        return ImportBanner(optionOf(run.source), mods, (run.phase as? ImportPhase.Waiting)?.wait)
    }

    private fun ready(inputs: Inputs, status: LibraryStatus.Ready, local: Local, observed: Map<SubmissionRef, FilesObservation>, checking: Checking): LibraryUiState {
        val state = inputs.state
        val report = inputs.report
        val root = (state.game as? GameStatus.Ready)?.root
        val here = status.records.filter { it.gameRoot == root }
        val recordedModIds = here.mapTo(HashSet()) { it.modId }
        val recordedIds = status.records.mapTo(HashSet()) { it.installId }
        val busy = state.gameBananaBusy
        val onRows = state.operations.values.filter { it.installId in recordedIds }.mapNotNull { op -> rowOperation(op, state, busy)?.let { op.installId to it } }.toMap()
        val downloads = state.operations.values.mapNotNull { operation ->
            if (operation.installId in recordedIds || operation.installId == null && operation.modId in recordedModIds) null else downloadRow(operation, state, busy)
        }
        val updates = InstalledUpdates.of(here, observed)
        val draggable = root != null && status.interrupted == null && here.size == status.records.size && state.canEditChoices

        val profiles = state.shownProfiles
        val active = profiles?.activeProfile?.enabled.orEmpty()
        val byInstallId = status.records.associateBy { it.installId }
        val sorted = shownOrder(status.records, profiles ?: state.profiles, state.orderView).filter { it.installId !in state.leaving }
        val all = sorted.mapIndexed { index, record ->
            val change = state.changes[record.installId]
            val operation = onRows[record.installId]
            val running = change?.stage == ChangeStage.Running || operation != null
            val update = updates[record.installId]?.takeIf { !record.edited }
            val source = record.sets.first().source
            val mod = source.submission?.let { catalog?.cachedMod(it.modId) }
            val labels = installedLabels(mod)
            val hidden = report.hiddenIn(record, byInstallId)
            val installedFiles = record.sets.map { it.source }.distinct().size
            LibraryRow(
                installId = record.installId,
                modId = record.modId,
                partsOverlap = record.installId in report.overlapping,
                page = source.submission?.modId,
                finding = record.unasked && !state.findingLater && busy == null,
                canInstall = state.canInstall,
                canToggle = state.canEditChoices,
                applying = state.applying,
                rank = index + 1,
                title = record.title,
                variant = record.sets.mapNotNull { it.label(labels) }.distinct().takeIf { it.isNotEmpty() }?.joinToString(", "),
                installedFiles = installedFiles,
                installedVpks = record.sets.size,
                availableFiles = record.sets.mapNotNull { it.version?.count }.distinct().singleOrNull()?.takeIf { it >= installedFiles },
                category = mod?.category,
                heroOrKind = heroOrKind(mod),
                author = mod?.author,
                coverUrl = mod?.cover?.url,
                coverFullUrl = mod?.cover?.fullUrl ?: mod?.cover?.url,
                sizeBytes = record.files.sumOf { it.sizeBytes },
                enabled = if (record.edited) record.enabled else record.installId in active,
                pastLimit = record.installId in active && record.installId in state.pastLimit,
                damage = status.damaged[record.installId],
                held = record.edited,
                reinstall = if (record.installId in status.stored) ReinstallFrom.Backups else record.reinstallFrom,
                busy = running,
                update = update?.state(),
                updatePlan = update?.plan,
                operation = operation,
                compat = state.compatibility.installs[record.installId],
                hiddenModels = hidden?.models ?: 0,
                hiddenBy = hidden?.by.orEmpty().map { winner ->
                    when (winner) {
                        is Winner.Foreign -> HiddenBy.Foreign(winner.name)
                        is Winner.Part -> HiddenBy.Part(winner.set.label(labels) ?: winner.set.archiveName)
                        is Winner.Mod -> HiddenBy.Mod(winner.record.installId, winner.record.title, record.installId in winner.record.keptBeside || winner.record.installId in record.keptBeside)
                    }
                },
                visible = true,
            ).let { it.copy(favourite = it.favouriteId in inputs.favourites) }
        }

        val scoped = all.filter { local.scopes(it) }
        val rows = all.map { row -> row.copy(visible = local.shows(row), draggable = draggable) }
        val visibleIds = rows.filter { it.visible }.mapTo(HashSet()) { it.installId }
        fun pickable(ids: List<String>): List<String> = ids.filter { it in visibleIds }
        val firstShown = rows.firstOrNull { it.visible }?.installId
        val picked = local.selection.ids
        val selectedIds = pickable(picked).ifEmpty {
            if (picked.isEmpty()) listOfNotNull(firstShown)
            else pickable(shown.selectedIds).ifEmpty {
                listOfNotNull(neighbour(shown.selectedIds.firstOrNull() ?: picked.first(), rows)?.takeIf { it in visibleIds } ?: firstShown)
            }
        }
        shown = Shown(rows, selectedIds)
        val selected = selectedIds.singleOrNull()?.let { id -> rows.indexOfFirst { it.installId == id } }?.let { index ->
            val record = byInstallId.getValue(rows[index].installId)
            detail(rows[index], record, rows[index].page?.let { catalog?.cachedMod(it) }, waiting = state.choiceSync is ChoiceSync.Waiting)
        }
        val several = selectedIds.takeIf { it.size > 1 }?.toSet()?.let { ids ->
            val chosen = rows.filter { it.installId in ids }
            val coverage = profiles?.profiles.orEmpty().associate { profile ->
                profile.id to when (chosen.count { it.installId in profile.enabled }) {
                    0 -> Coverage.None
                    chosen.size -> Coverage.All
                    else -> Coverage.Some
                }
            }
            SelectedMods(chosen.size, chosen.count { it.enabled }, chosen.sumOf { it.sizeBytes }, state.canEditChoices, chosen.none { it.busy }, coverage)
        }
        val assessed = all.filter { it.update != null }
        val counts = assessed.groupingBy { it.update }.eachCount()
        return LibraryUiState(
            loading = false,
            profiles = profiles,
            canInstall = state.canInstall,
            gameNote = gameNote(state, local),
            enabledCount = all.count { it.enabled },
            pastLimit = all.count { it.pastLimit },
            enabledSizeBytes = all.filter { it.enabled }.sumOf { it.sizeBytes },
            query = local.query,
            layout = local.layout,
            downloads = downloads,
            scope = local.scope,
            scopedCount = scoped.size,
            filters = filterPills(scoped.filter { it.matches(local.query) }, local.filter),
            filter = local.filter,
            rows = rows,
            selectedIds = selectedIds.toSet(),
            selected = selected,
            several = several,
            interrupted = status.interrupted,
            orderView = state.orderView,
            updates = UpdatesUi(
                available = counts[UpdateState.Available] ?: 0,
                review = counts[UpdateState.Review] ?: 0,
                unchecked = (counts[UpdateState.NotChecked] ?: 0) + (counts[UpdateState.Unknown] ?: 0),
                checking = checking.running,
                busyUntil = checking.busyUntil,
                checkable = assessed.isNotEmpty(),
            ),
            compat = compatUi(state, all, local.compatOpen, local.followed),
            importing = importing(inputs.migration),
            leftovers = LeftoversUi(
                sets = status.leftovers.takeIf { status.interrupted == null }.orEmpty(),
                copies = status.leftovers.mapNotNull { set -> set.copyOf?.let { id -> all.firstOrNull { it.installId == id }?.title ?: byInstallId[id]?.title }?.let { set.slot to it } }.toMap(),
                open = local.leftovers.open,
                canChange = state.canModify,
                working = local.leftovers.working,
                last = when (val outcome = local.leftovers.outcome) {
                    null -> null
                    LeftoverOutcome.Added -> LeftoverResult.Added
                    is LeftoverOutcome.Moved -> LeftoverResult.Moved(outcome.folder.value.replace('/', library.separator))
                    is LeftoverOutcome.Refused -> LeftoverResult.Failed(outcome.problem)
                },
            ),
        )
    }

    private fun compatUi(state: LibraryState, rows: List<LibraryRow>, open: CompatRequest?, followed: Set<String>): CompatUi {
        val automatic = state.compatibility.automatic
        val (failed, repairable) = rows.filter { row ->
            row.compat?.let { it.repairable.isNotEmpty() && it.choice != RepairChoice.Original && it.pending == null } == true
        }.partition { it.compat?.problem != null }
        val asked = rows.filter { it.installId in followed }
        val pending = asked.mapNotNull { it.compat?.pending }
        val changing = pending.filter { it !is RepairWork.WaitingForGame }
        val finished = if (asked.isEmpty() || asked.any { it.compat?.pending != null }) null else CompatFinished(
            repaired = asked.count { row -> row.compat?.let { it.problem == null && it.repaired.isNotEmpty() } == true },
            restored = asked.count { row -> row.compat?.let { it.problem == null && it.choice == RepairChoice.Original && it.applied.isEmpty() } == true },
        ).takeIf { it.repaired > 0 || it.restored > 0 }
        return CompatUi(
            repairable = if (automatic) emptyList() else repairable.map { it.installId },
            retry = failed.map { it.installId },
            repairing = changing.count { !it.restoring },
            restoring = changing.count { it.restoring },
            waiting = pending.count { it is RepairWork.WaitingForGame },
            unfinished = rows.count { row -> row.compat?.let { it.problem != null && it.pending == null } == true },
            finished = finished,
            checking = state.compatibility.checking,
            unavailable = state.compatibility.unavailable,
            canChange = state.canEditChoices,
            automatic = automatic,
            open = when (open) {
                null -> null
                is CompatRequest.Mod -> rows.firstOrNull { it.installId == open.installId }?.let { row ->
                    CompatOpen.Mod(row.installId, row.title, row.compat, row.enabled)
                }
                is CompatRequest.Review -> CompatOpen.Review(rows.filter { it.installId in open.ids || it.inReview }.map { row ->
                    CompatReviewItem(row.installId, row.title, row.compat, row.enabled)
                })
            },
        )
    }

    private fun gameNote(state: LibraryState, local: Local): GameNote? = when {
        state.deadlock == GameActivity.Unread || state.choiceSync is ChoiceSync.Failed -> null
        state.choiceSync is ChoiceSync.Waiting -> when {
            state.sessionHold != null -> GameNote.SavedCustomGame
            state.deadlock == GameActivity.Running -> GameNote.SavedRunning
            else -> GameNote.SavedUnknown
        }
        state.deadlock == GameActivity.Running -> GameNote.Running.takeIf { local.runningNote }
        state.deadlock == GameActivity.Unknown -> GameNote.Unknown
        else -> null
    }

    private fun rowOperation(operation: Operation, state: LibraryState, busy: Instant?): RowOperation? =
        phaseOf(operation.stage, state, busy, remote = SubmissionRef.parse(operation.modId) != null)?.let { RowOperation(it, canCancel = operation.stage.cancellable) }

    private fun phaseOf(stage: OperationStage, state: LibraryState, busy: Instant?, remote: Boolean): DownloadPhase? = when (stage) {
        OperationStage.Preparing -> if (!remote) DownloadPhase.Reading else busy?.let { DownloadPhase.Busy(it) } ?: DownloadPhase.Preparing
        is OperationStage.ChoosingFile, is OperationStage.ChoosingSet, is OperationStage.ChoosingSwap -> DownloadPhase.Choosing
        is OperationStage.Waiting -> DownloadPhase.Waiting(stage.sizeBytes)
        is OperationStage.Downloading -> stage.progress.busyUntil?.let { DownloadPhase.Busy(it, stage.progress.doneBytes) }
            ?: DownloadPhase.Transferring(stage.progress.doneBytes, stage.progress.totalBytes, stage.progress.stalled)
        OperationStage.Unpacking, OperationStage.Installing -> DownloadPhase.Finishing
        is OperationStage.WaitingForGame -> if (state.sessionHold != null) DownloadPhase.WaitingForCustomGame else DownloadPhase.WaitingForGame
        OperationStage.Cancelling -> DownloadPhase.Cancelling
        is OperationStage.Failed -> null
    }

    private fun downloadRow(operation: Operation, state: LibraryState, busy: Instant?): DownloadRow? {
        val row = rowOperation(operation, state, busy) ?: return null
        val mod = catalog?.cachedMod(operation.modId)
        return DownloadRow(
            modId = operation.modId,
            title = operation.title ?: mod?.title,
            category = mod?.category,
            heroOrKind = heroOrKind(mod),
            author = mod?.author,
            phase = row.phase,
            canCancel = row.canCancel,
            hasModPage = SubmissionRef.parse(operation.modId) != null,
            coverUrl = mod?.cover?.url,
            coverFullUrl = mod?.cover?.fullUrl ?: mod?.cover?.url,
        )
    }

    private fun shownOrder(records: List<ManifestRecord>, book: ProfileBook?, view: OrderView?): List<ManifestRecord> {
        val orders = book?.activeProfile?.orders.orEmpty()
        val booked = records.sortedBy { record -> orders[record.gameRoot]?.indexOf(record.installId)?.takeIf { it >= 0 } ?: Int.MAX_VALUE }
        val shown = view ?: return booked
        val byId = booked.filter { it.gameRoot == shown.root }.associateBy { it.installId }
        if (byId.keys != shown.sets.toSet()) return booked
        val next = shown.sets.map(byId::getValue).iterator()
        return booked.map { if (it.gameRoot == shown.root) next.next() else it }
    }

    private fun filterPills(rows: List<LibraryRow>, selected: LibraryFilter): List<FilterPill> {
        val byCategory = rows.groupingBy { it.group }.eachCount()
        fun MutableList<FilterPill>.offer(filter: LibraryFilter, count: Int) {
            if (count > 0 && (count < rows.size || filter == selected)) add(FilterPill(filter, count))
        }
        val pills = buildList {
            add(FilterPill(LibraryFilter.All, rows.size))
            rows.count { it.favourite }.takeIf { it > 0 }?.let { add(FilterPill(LibraryFilter.Favourites, it)) }
            ModCategory.entries.forEach { category -> byCategory[category]?.let { offer(LibraryFilter.Category(category), it) } }
            offer(LibraryFilter.Updates, rows.count { it.matchesFilter(LibraryFilter.Updates) })
        }
        return if (pills.none { it.filter == selected }) pills + FilterPill(selected, rows.count { it.matchesFilter(selected) }) else pills
    }

    private fun neighbour(installId: String, rows: List<LibraryRow>): String? {
        val was = shown.rows.indexOfFirst { it.installId == installId }.takeIf { it >= 0 } ?: return null
        return rows.getOrNull(was.coerceAtMost(rows.lastIndex))?.installId
    }

    private fun detail(row: LibraryRow, record: ManifestRecord, mod: Mod?, waiting: Boolean): LibraryDetail {
        val labels = installedLabels(mod)
        return LibraryDetail(
            row = row,
            folderPath = record.folder().replace('/', library.separator),
            files = when {
                row.held || !waiting || row.enabled == record.enabled -> if (record.enabled) FilesPlace.InGame else FilesPlace.Out
                row.enabled -> FilesPlace.GoingIn
                else -> FilesPlace.GoingOut
            },
            held = HeldFiles(record.editedFiles.map { it.name }, record.numbers.map { it.pak.stem }).takeIf { row.held },
            downloads = record.sets.map { set ->
                DownloadDetail(
                    name = set.label(labels),
                    archiveName = set.archiveName,
                    number = record.numberOf(set)?.pak?.value,
                    files = record.filesOf(set).map { OwnedFileSummary(it.name, it.sizeBytes) },
                    componentPath = set.componentKey,
                )
            },
        )
    }

    private fun installedLabels(mod: Mod?): Map<Long, String> {
        val variants = mod?.previewVariants ?: return emptyMap()
        return versionNames(mod.title, variants.map { VersionSource(it.fileId, it.description, it.filename ?: it.fileId.toString()) })
    }

    private fun InstalledSet.label(labels: Map<Long, String>): String? = fileId?.let(labels::get) ?: variant

    private fun LibraryRow.matches(query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || listOfNotNull(title, variant, author, heroOrKind, category?.let(categories::get)).any { it.contains(q, ignoreCase = true) }
    }

    private fun heroOrKind(mod: Mod?): String? = mod?.heroId?.let { catalog?.cachedHero(it)?.name } ?: mod?.kind

    private fun LibraryRow.matchesFilter(filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.All -> true
        is LibraryFilter.Category -> group == filter.category
        LibraryFilter.Updates -> update == UpdateState.Available
        LibraryFilter.Review -> update == UpdateState.Review
        LibraryFilter.Favourites -> favourite
    }

    private val LibraryRow.group: ModCategory get() = category ?: ModCategory.Other

    private fun InstallationUpdates.state(): UpdateState {
        val states = sources.map { it.state }
        return when {
            states.any { it == SourceState.NotChecked } -> UpdateState.NotChecked
            states.any { it is SourceState.Unknown } -> UpdateState.Unknown
            plan != null -> UpdateState.Available
            kept -> UpdateState.Current
            replacements.isNotEmpty() || states.any { it == SourceState.Review } -> UpdateState.Review
            else -> UpdateState.Current
        }
    }

    private data class Shown(val rows: List<LibraryRow>, val selectedIds: List<String>)

    private val LibraryRow.inReview: Boolean get() = compatStatus.let { it != null && it != CompatStatus.Found(CompatLabel.RepairApplied) }

    private data class Local(
        val query: String = "",
        val selection: Selection = Selection(),
        val layout: LibraryLayout = LibraryLayout.List,
        val layoutReady: Boolean = false,
        val filter: LibraryFilter = LibraryFilter.All,
        val scope: LibraryScope = LibraryScope.All,
        /** Mods turned off while the profile's own view showed them, which it keeps listing until [scope] changes or Library leaves. */
        val turnedOff: Set<String> = emptySet(),
        val compatOpen: CompatRequest? = null,
        val followed: Set<String> = emptySet(),
        val runningNote: Boolean = false,
        val leftovers: Leftovers = Leftovers(),
    )

    private data class Leftovers(val open: Boolean = false, val working: LeftoverAction? = null, val outcome: LeftoverOutcome? = null)

    private sealed interface CompatRequest {
        data class Mod(val installId: String, val back: Review? = null) : CompatRequest

        data class Review(val ids: Set<String>) : CompatRequest
    }

    private data class Selection(val ids: List<String> = emptyList(), val anchor: String? = null)
}
