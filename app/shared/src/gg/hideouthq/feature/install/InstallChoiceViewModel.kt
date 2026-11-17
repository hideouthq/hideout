package gg.hideouthq.feature.install

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.library.ChoiceAnswer
import gg.hideouthq.core.library.ChoiceId
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.Operation
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.SetOption
import gg.hideouthq.core.library.VersionSource
import gg.hideouthq.core.library.choice
import gg.hideouthq.core.library.overlap
import gg.hideouthq.core.library.pickOneGroups
import gg.hideouthq.core.library.sharedFiles
import gg.hideouthq.core.library.versionNames
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Presents the library's open install questions one at a time, oldest first, and keeps the user's draft answer for
 * each. Questions are numbered as they are asked, so a newer one never takes the place of the one on screen. Settling
 * one answers the library, which owns the install and everything it downloaded or unpacked; the settled dialog is kept
 * as it was until the shell says it has gone, so the next question waits for it.
 */
class InstallChoiceViewModel(private val library: ModLibrary, catalog: CatalogRepository) : ViewModel() {
    private data class Draft(val selected: Set<VersionPick>, val profiles: Set<ProfileId>, val order: List<VersionPick>?)

    private data class Local(val drafts: Map<ChoiceId, Draft> = emptyMap(), val leaving: InstallDialogState? = null)

    private class Catalogue(val mods: Map<String, Mod> = emptyMap(), val heroes: Map<String, Hero> = emptyMap())

    private val local = MutableStateFlow(Local())

    private val catalogue: StateFlow<Catalogue> = combine(
        catalog.mods.onStart { emit(emptyList()) },
        catalog.heroes.onStart { emit(emptyList()) },
    ) { mods, heroes -> Catalogue(mods.associateBy { it.id }, heroes.associateBy { it.id }) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Catalogue())

    /** The install dialog the shell shows, if any: the oldest open question, or the one closing. */
    val uiState: StateFlow<InstallDialogState?> = combine(library.state, catalogue, local) { state, catalogue, local ->
        present(state, catalogue, local)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The oldest open "swap or keep both" question, with the titles of the mods it would turn off. */
    val swap: StateFlow<SwapQuestion?> = combine(library.state, catalogue) { state, catalogue ->
        val operation = state.operations.values.filter { it.stage is OperationStage.ChoosingSwap }.minByOrNull { it.stage.choice!!.value } ?: return@combine null
        val stage = operation.stage as OperationStage.ChoosingSwap
        val others = stage.others.mapNotNull { id -> (state.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == id } }
        SwapQuestion(
            id = stage.id,
            title = operation.title ?: catalogue.mods[operation.modId]?.title ?: operation.modId,
            heroName = catalogue.mods[operation.modId]?.heroId?.let { catalogue.heroes[it]?.name },
            others = others.map { it.title },
            skins = others.all { catalogue.mods[it.modId]?.category == ModCategory.Skins },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun toggle(id: ChoiceId, pick: VersionPick) = edit(id) { dialog ->
        Draft(if (pick in dialog.selected) dialog.selected - pick else dialog.selected + pick, dialog.selectedProfiles, dialog.order)
    }

    fun selectAll(id: ChoiceId) = edit(id) { dialog ->
        val picks = dialog.rows.map { it.pick }
        Draft(if (dialog.selected.size == picks.size) emptySet() else picks.toSet(), dialog.selectedProfiles, dialog.order)
    }

    fun movePart(id: ChoiceId, pick: VersionPick, by: Int) = edit(id) { dialog ->
        val picks = dialog.rows.map { it.pick }.toMutableList()
        val from = picks.indexOf(pick)
        val to = (from + by).coerceIn(picks.indices)
        picks.add(to, picks.removeAt(from))
        Draft(dialog.selected, dialog.selectedProfiles, picks)
    }

    fun setProfile(id: ChoiceId, profile: ProfileId, selected: Boolean) = edit(id) {
        if (it.replacing || !it.canInstall) return@edit null
        Draft(it.selected, if (selected) it.selectedProfiles + profile else it.selectedProfiles - profile, it.order)
    }

    /** Confirms the selected downloads or components in their displayed order. */
    fun confirm(id: ChoiceId) {
        val dialog = open(id)?.takeIf { it.canConfirm } ?: return
        val picks = dialog.selectedRows.map { it.pick }
        val answer = when (dialog.question) {
            Question.Files -> ChoiceAnswer.Files(picks.filterIsInstance<VersionPick.File>().map { it.fileId }.toSet(), dialog.selectedProfiles)
            Question.Sets -> ChoiceAnswer.Sets(picks.filterIsInstance<VersionPick.Set>().map { it.key }, dialog.selectedProfiles)
        }
        settle(dialog, answer, preview = null)
    }

    /** Cancels the question; the library puts away anything the install unpacked. */
    fun cancel(id: ChoiceId) {
        settle(open(id) ?: return, ChoiceAnswer.Cancel, preview = null)
    }

    /**
     * Cancels the file question and asks for [fileId]'s exact preview once the dialog has gone. Only a row that offers
     * a preview has one to open.
     */
    fun preview(id: ChoiceId, fileId: Long) {
        val dialog = open(id) ?: return
        val target = dialog.rows.firstOrNull { it.pick == VersionPick.File(fileId) }?.preview ?: return
        settle(dialog, ChoiceAnswer.Cancel, target)
    }

    /** The dialog for [id] has gone from the screen, and any preview it asked for has opened; the next question may show. */
    fun finished(id: ChoiceId) {
        val waiting = library.state.value.operations.values.mapNotNullTo(mutableSetOf()) { it.stage.choice }
        local.update { local ->
            Local(drafts = local.drafts.filterKeys { it in waiting }, leaving = local.leaving?.takeIf { it.id != id })
        }
    }

    private fun settle(dialog: InstallDialogState, answer: ChoiceAnswer, preview: PreviewTarget?) {
        val leaving = dialog.copy(closing = true, preview = preview)
        local.update { it.copy(leaving = leaving) }
        if (library.answer(dialog.id, answer)) {
            local.update { it.copy(drafts = it.drafts - dialog.id) }
        } else {
            local.update { if (it.leaving === leaving) it.copy(leaving = null) else it }
        }
    }

    private fun edit(id: ChoiceId, change: (InstallDialogState) -> Draft?) {
        val dialog = open(id) ?: return
        val draft = change(dialog) ?: return
        local.update { it.copy(drafts = it.drafts + (id to draft)) }
    }

    private fun open(id: ChoiceId): InstallDialogState? =
        present(library.state.value, catalogue.value, local.value)?.takeIf { it.id == id && !it.closing }

    private fun present(state: LibraryState, catalogue: Catalogue, local: Local): InstallDialogState? {
        local.leaving?.let { return it }
        val operation = state.operations.values.filter { it.stage is OperationStage.ChoosingFile || it.stage is OperationStage.ChoosingSet }.minByOrNull { it.stage.choice!!.value } ?: return null
        return dialog(operation, catalogue, local.drafts[operation.stage.choice], state)
    }

    private fun dialog(operation: Operation, catalogue: Catalogue, draft: Draft?, state: LibraryState): InstallDialogState? {
        val record = (state.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.installId == operation.installId }
        val replacing = record != null
        val mod = catalogue.mods[operation.modId]
        val header = DialogHeader(operation.title ?: mod?.title ?: operation.modId, mod?.cover?.url, mod?.cover?.tone)
        return when (val stage = operation.stage) {
            is OperationStage.ChoosingFile -> {
                val installed = record?.sets?.mapNotNull { it.fileId }.orEmpty().distinct()
                InstallDialogState(
                    id = stage.id,
                    modId = operation.modId,
                    header = header,
                    question = Question.Files,
                    rows = fileRows(operation.modId, header.title, stage.files, stage.delisted, installed, mod),
                    selected = draft?.selected ?: stage.preselected.map { VersionPick.File(it) }.toSet(),
                    selectedProfiles = draft?.profiles ?: stage.profiles,
                    profiles = state.profiles?.profiles.orEmpty(),
                    canInstall = state.canInstall,
                    replacing = replacing,
                    installed = installed.map { VersionPick.File(it) }.toSet(),
                    partsLeftOut = record?.declined?.keys.orEmpty().map { VersionPick.File(it) }.toSet(),
                    kept = stage.kept.mapTo(HashSet()) { VersionPick.File(it) },
                )
            }
            is OperationStage.ChoosingSet -> {
                val groups = pickOneGroups(stage.options)
                val selected = draft?.selected ?: stage.preselected.map { VersionPick.Set(it) }.toSet()
                InstallDialogState(
                    id = stage.id,
                    modId = operation.modId,
                    header = header,
                    question = Question.Sets,
                    rows = setRows(stage.options, groups).let { rows ->
                        draft?.order?.let { order -> rows.sortedBy { order.indexOf(it.pick) } } ?: rows
                    }.let { rows -> withCovers(rows, stage.options, selected) },
                    arranged = draft?.order != null,
                    selected = selected,
                    selectedProfiles = draft?.profiles ?: stage.profiles,
                    profiles = state.profiles?.profiles.orEmpty(),
                    canInstall = state.canInstall,
                    replacing = replacing,
                    download = stage.fileName,
                    leftOut = stage.leftOut,
                    notes = stage.notes,
                    shared = groups.withIndex().associate { (index, group) ->
                        index to sharedFiles(stage.options.filter { it.key in group && VersionPick.Set(it.key) in selected }.map { it.files }).size
                    }.filterValues { it > 0 },
                    movable = stage.options.filter { VersionPick.Set(it.key) in selected }.let { ticked ->
                        ticked.filter { part -> ticked.any { it !== part && overlap(it.files, part.files) } }.mapTo(HashSet()) { VersionPick.Set(it.key) }
                    },
                )
            }
            else -> null
        }
    }
}

private fun fileRows(modId: String, title: String, files: List<RemoteFile>, delisted: List<InstalledSet>, installed: List<Long>, mod: Mod?): List<VersionRow> {
    val most = files.maxOfOrNull { it.downloads }
    val unique = files.count { it.downloads == most } == 1
    val names = versionNames(title, files.map { VersionSource(it.id, it.description, it.fileName) })
    val listed = files.map { file ->
        VersionRow(
            pick = VersionPick.File(file.id),
            name = names.getValue(file.id),
            sizeBytes = file.sizeBytes,
            mostDownloaded = unique && file.downloads == most,
            preview = exactPreview(modId, file, mod),
            installed = file.id in installed,
        )
    }
    val rows = listed + delisted.distinctBy { it.fileId }.mapNotNull { set ->
        val fileId = set.fileId ?: return@mapNotNull null
        VersionRow(VersionPick.File(fileId), set.variant ?: set.archiveName, set.download?.sizeBytes ?: set.files.sumOf { it.image.sizeBytes }, installed = true, listed = false)
    }
    return rows.sortedBy { row -> installed.indexOf((row.pick as VersionPick.File).fileId).takeIf { it >= 0 } ?: Int.MAX_VALUE }
}

private fun exactPreview(modId: String, file: RemoteFile, mod: Mod?): PreviewTarget? {
    val variant = mod?.previewVariants?.firstOrNull { it.fileId == file.id } ?: return null
    val entry = variant.entry?.takeIf { it.fileId == file.id } ?: return null
    if (listOfNotNull(variant.fileMd5, entry.fileMd5).any { file.md5 != null && it != file.md5 }) return null
    return PreviewTarget(modId, file.id, file.md5)
}

private fun withCovers(rows: List<VersionRow>, options: List<SetOption>, selected: Set<VersionPick>): List<VersionRow> {
    val files: Map<VersionPick, Map<String, Long>> = options.associate { VersionPick.Set(it.key) to it.files }
    return rows.mapIndexed { index, row ->
        val own = files.getValue(row.pick)
        val bases = rows.filter { base ->
            val theirs = files.getValue(base.pick)
            base.pick in selected && base.pick != row.pick && (row.group == null || base.group != row.group) && theirs.size > own.size && overlap(own, theirs)
        }
        if (bases.isEmpty()) return@mapIndexed row
        val shared = bases.flatMapTo(HashSet()) { sharedFiles(listOf(own, files.getValue(it.pick))) }.size
        row.copy(covers = Covers(bases.joinToString(", ") { it.name }, shared, bases.all { rows.indexOf(it) > index }))
    }
}

private fun setRows(options: List<SetOption>, groups: List<List<String>>): List<VersionRow> {
    val names = options.associate { option ->
        val folder = option.key.substringBeforeLast('/', "").substringAfterLast('/')
        option.key to folder.ifEmpty {
            val stem = option.key.substringAfterLast('/').removeSuffix(".vpk").removeSuffix("_dir")
            stem.replace('_', ' ').trim().ifEmpty { stem }.replaceFirstChar { it.uppercaseChar() }
        }
    }
    return options.map { option ->
        val name = names.getValue(option.key)
        VersionRow(
            pick = VersionPick.Set(option.key),
            name = name,
            sizeBytes = option.sizeBytes,
            path = option.key.takeIf { names.values.count { it == name } > 1 },
            group = groups.indexOfFirst { option.key in it }.takeIf { it >= 0 },
            needsFonts = option.needsFonts,
        )
    }
}
