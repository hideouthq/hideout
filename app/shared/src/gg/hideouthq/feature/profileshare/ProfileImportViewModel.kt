package gg.hideouthq.feature.profileshare

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.library.editBlock
import gg.hideouthq.core.library.freeProfileName
import gg.hideouthq.core.library.installStatus
import gg.hideouthq.core.library.pageRecords
import gg.hideouthq.core.library.profileNameProblem
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.sharing.ProfileImports
import gg.hideouthq.core.sharing.ProfileLink
import gg.hideouthq.core.sharing.ProfileShares
import gg.hideouthq.core.sharing.SharedLookup
import gg.hideouthq.core.sharing.SharedMod
import gg.hideouthq.core.sharing.SharedProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val StopTimeoutMillis = 5_000L

interface ProfileImportActions {
    fun setImportText(text: String)
    fun readImport()
    fun setImportName(name: String)
    fun confirmImport()
    fun dismissImport()
}

/**
 * Turns a shared profile's link or code into a new profile: it reads what [imports] hands it, asks [shares] for a code's
 * profile, then makes the profile without switching to it and puts each shared mod in it.
 */
class ProfileImportViewModel(
    private val library: ModLibrary,
    catalog: CatalogRepository,
    private val shares: ProfileShares,
    imports: ProfileImports,
) : ViewModel(), ProfileImportActions {
    private sealed interface Step {
        data class Asking(val text: String, val problem: ImportProblem? = null) : Step
        class Looking(val text: String) : Step
        data class Reviewing(val shared: SharedProfile, val name: String, val submitting: Boolean = false, val problem: LibraryProblem? = null) : Step
    }

    private class Catalogue(val mods: Map<String, Mod>, val heroes: Map<String, Hero>)

    private val step = MutableStateFlow<Step?>(null)
    private var looking: Job? = null

    private val catalogue = combine(catalog.mods.onStart { emit(emptyList()) }, catalog.heroes.onStart { emit(emptyList()) }) { mods, heroes ->
        Catalogue(mods.associateBy { it.id }, heroes.associateBy { it.id })
    }

    val uiState: StateFlow<ProfileImportUiState> = combine(step, library.state, catalogue) { step, state, catalogue ->
        ProfileImportUiState(when (step) {
            null -> null
            is Step.Asking -> ImportSheet.Asking(step.text, step.problem, shares.site?.value?.substringAfter("://"))
            is Step.Looking -> ImportSheet.Looking
            is Step.Reviewing -> ImportSheet.Reviewing(
                shared = step.shared.name,
                name = step.name,
                nameProblem = if (step.submitting) null else profileNameProblem(step.name, state.shownProfiles?.profiles.orEmpty()),
                mods = step.shared.mods.map { mod ->
                    val modId = mod.ref.modId
                    val known = catalogue.mods[modId]
                    val installed = (state.library as? LibraryStatus.Ready)?.records?.pageRecords(modId)?.firstOrNull()
                    SharedRow(modId, mod.submission, known?.title ?: installed?.title, known?.heroId?.let { catalogue.heroes[it]?.name }, known?.cover?.url, state.installStatus(modId) is InstallStatus.Installed)
                },
                blocked = state.editBlock(),
                submitting = step.submitting,
                problem = step.problem,
            )
        })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), ProfileImportUiState())

    init {
        viewModelScope.launch { imports.incoming.collect(::open) }
    }

    /** Opens on [link], or on an empty field when null; a profile being made stays until it is made. */
    private fun open(link: String?) {
        if ((step.value as? Step.Reviewing)?.submitting == true) return
        looking?.cancel()
        if (link == null) step.value = Step.Asking("") else read(link)
    }

    override fun setImportText(text: String) = step.update { (it as? Step.Asking)?.copy(text = text, problem = null) ?: it }

    override fun readImport() {
        val asking = step.value as? Step.Asking ?: return
        if (asking.text.isNotBlank()) read(asking.text)
    }

    private fun read(text: String) {
        when (val link = ProfileLink.parse(text)) {
            null -> step.value = Step.Asking(text, ImportProblem.Unreadable)
            is ProfileLink.Whole -> step.value = reviewing(link.profile)
            is ProfileLink.Code -> {
                val asked = Step.Looking(text)
                step.value = asked
                looking = viewModelScope.launch {
                    val found = shares.find(link.code)
                    step.update { current ->
                        if (current !== asked) current else when (found) {
                            is SharedLookup.Found -> reviewing(found.profile)
                            SharedLookup.Missing -> Step.Asking(text, ImportProblem.Missing)
                            SharedLookup.Unreachable -> Step.Asking(text, ImportProblem.Unreachable)
                        }
                    }
                }
            }
        }
    }

    private fun reviewing(shared: SharedProfile) = Step.Reviewing(shared, freeProfileName(shared.name, library.state.value.shownProfiles?.profiles.orEmpty()))

    override fun setImportName(name: String) = step.update { (it as? Step.Reviewing)?.takeUnless { r -> r.submitting }?.copy(name = name, problem = null) ?: it }

    override fun dismissImport() {
        if ((step.value as? Step.Reviewing)?.submitting == true) return
        looking?.cancel()
        step.value = null
    }

    override fun confirmImport() {
        if ((uiState.value.sheet as? ImportSheet.Reviewing)?.canConfirm != true) return
        val reviewing = step.value as? Step.Reviewing ?: return
        val book = library.state.value.shownProfiles ?: return
        step.value = reviewing.copy(submitting = true, problem = null)
        viewModelScope.launch {
            when (val made = library.createProfile(NewProfile(reviewing.name.trim(), book.nextColour, ProfileSeed.Empty, activate = false))) {
                is MutationResult.Refused -> step.update { (it as? Step.Reviewing)?.copy(submitting = false, problem = made.problem) ?: it }
                is MutationResult.Done -> {
                    step.value = null
                    fill(made.value, reviewing.shared.mods)
                }
            }
        }
    }

    /** Installs finish in any order and each goes first, so once none is installing the order is set to the shared one. */
    private suspend fun fill(profile: ProfileId, mods: List<SharedMod>) {
        mods.forEach { add(it, profile) }
        val modIds = mods.map { it.ref.modId }
        val settled = library.state.first { state -> modIds.none { id -> state.operations[id]?.stage.let { it != null && it !is OperationStage.Failed } } }
        val records = (settled.library as? LibraryStatus.Ready)?.records.orEmpty()
        val on = settled.shownProfiles?.profiles?.firstOrNull { it.id == profile }?.enabled ?: return
        library.putFirst(profile, modIds.flatMap { id -> records.pageRecords(id).map { it.installId }.filter { it in on } })
    }

    private fun add(mod: SharedMod, profile: ProfileId) {
        val records = (library.state.value.library as? LibraryStatus.Ready)?.records?.pageRecords(mod.ref.modId).orEmpty()
        (records.firstOrNull { it.enabled } ?: records.firstOrNull())?.let { return library.setEnabled(it.installId, true, profile) }
        library.install(InstallRequest(InstallInput.Remote(mod.ref, mod.files), setOf(profile)))
    }
}
