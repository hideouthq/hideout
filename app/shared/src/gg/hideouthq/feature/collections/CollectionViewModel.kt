package gg.hideouthq.feature.collections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.collections.CollectionsRepository
import gg.hideouthq.core.library.InstallInput
import gg.hideouthq.core.library.InstallRequest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.editBlock
import gg.hideouthq.core.library.freeProfileName
import gg.hideouthq.core.library.installStatus
import gg.hideouthq.core.library.pageRecords
import gg.hideouthq.core.library.profileNameProblem
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.ui.catalog.toCatalogItem
import gg.hideouthq.core.ui.catalog.whileCatalogLoads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val StopTimeoutMillis = 5_000L

interface CollectionActions {
    fun filterHero(heroId: String?)
    fun sort(sort: CollectionSort)
    fun showMore()
    fun openProfileSheet()
    fun setProfileName(name: String)
    fun pick(heroId: String, modId: String?)
    fun dismissProfileSheet()
    fun confirmProfile()
}

class CollectionViewModel(
    private val id: String,
    private val catalog: CatalogRepository,
    collections: CollectionsRepository,
    private val library: ModLibrary,
) : ViewModel(), CollectionActions {
    private data class View(val hero: String? = null, val sort: CollectionSort = CollectionSort.Popular, val shown: Int = PageSize)

    private data class Draft(val name: String, val picks: Map<String, String?>, val submitting: Boolean = false, val problem: LibraryProblem? = null)

    private class Catalogue(val collection: JoinedCollection?, val mods: Map<String, Mod>, val heroes: Map<String, Hero>)

    private val view = MutableStateFlow(View())
    private val draft = MutableStateFlow<Draft?>(null)

    private val catalogue = combine(collections.collections, catalog.mods, catalog.heroes) { published, mods, heroes ->
        val byId = mods.associateBy { it.id }
        Catalogue(published.firstOrNull { it.id == id }?.let { join(it, byId) }, byId, heroes.associateBy { it.id })
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), replay = 1)

    val uiState: StateFlow<CollectionUiState> =
        combine(catalogue, view, library.state, draft) { catalogue, view, state, draft -> build(catalogue, view, state, draft) }
            .whileCatalogLoads(catalog.freshness) { CollectionUiState(loading = true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), CollectionUiState())

    override fun filterHero(heroId: String?) = view.update { it.copy(hero = heroId, shown = PageSize) }

    override fun sort(sort: CollectionSort) = view.update { it.copy(sort = sort, shown = PageSize) }

    override fun showMore() = view.update { it.copy(shown = it.shown + PageSize) }

    /** Opens the sheet named after the collection, with each hero's most downloaded skin picked. */
    override fun openProfileSheet() {
        val collection = catalogue.replayCache.lastOrNull()?.collection ?: return
        val profiles = library.state.value.shownProfiles?.profiles.orEmpty()
        val picks = buildMap<String, String?> { collection.members.byDownloads().forEach { mod -> mod.heroId?.let { getOrPut(it) { mod.id } } } }
        draft.value = Draft(freeProfileName(collection.collection.title, profiles), picks)
    }

    override fun setProfileName(name: String) = draft.update { it?.copy(name = name, problem = null) }

    override fun pick(heroId: String, modId: String?) = draft.update { it?.copy(picks = it.picks + (heroId to modId)) }

    override fun dismissProfileSheet() {
        if (draft.value?.submitting != true) draft.value = null
    }

    /** Creates the profile without switching to it, then puts each pick in it: installed ones join it, the rest install into it alone. */
    override fun confirmProfile() {
        val sheet = uiState.value.sheet?.takeIf { it.canConfirm } ?: return
        val book = library.state.value.shownProfiles ?: return
        val mods = catalogue.replayCache.lastOrNull()?.mods ?: return
        val picks = sheet.rows.mapNotNull { row -> row.picked?.let(mods::get) }
        draft.update { it?.copy(submitting = true, problem = null) }
        viewModelScope.launch {
            when (val made = library.createProfile(NewProfile(sheet.name.trim(), book.nextColour, ProfileSeed.Empty, activate = false))) {
                is MutationResult.Refused -> draft.update { it?.copy(submitting = false, problem = made.problem) }
                is MutationResult.Done -> {
                    draft.value = null
                    picks.forEach { add(it, made.value) }
                }
            }
        }
    }

    /** The collection as a list to paste: [heading], then each member and its GameBanana page. */
    fun shareText(heading: String): String? {
        val collection = catalogue.replayCache.lastOrNull()?.collection ?: return null
        val lines = collection.members.map { mod -> SubmissionRef.parse(mod.id)?.let { "${mod.title}: $GameBananaMods/${it.id}" } ?: mod.title }
        return (listOf(heading) + lines).joinToString("\n")
    }

    private fun add(mod: Mod, profile: ProfileId) {
        val records = (library.state.value.library as? LibraryStatus.Ready)?.records?.pageRecords(mod.id).orEmpty()
        (records.firstOrNull { it.enabled } ?: records.firstOrNull())?.let { return library.setEnabled(it.installId, true, profile) }
        val ref = SubmissionRef.parse(mod.id) ?: return library.install(mod.id)
        val pinned = mod.preview?.takeIf { mod.previewVariants.size > 1 }?.let { setOf(it.fileId) }.orEmpty()
        library.install(InstallRequest(InstallInput.Remote(ref, pinned), setOf(profile)))
    }

    private fun build(catalogue: Catalogue, view: View, state: LibraryState, draft: Draft?): CollectionUiState {
        val collection = catalogue.collection?.takeIf { it.members.isNotEmpty() } ?: return CollectionUiState(loading = false)
        val heroes = collection.heroes(catalogue.heroes)
        val hero = view.hero?.takeIf { selected -> heroes.any { it.id == selected } }
        val matching = collection.members.filter { hero == null || it.heroId == hero }.let { members ->
            when (view.sort) {
                CollectionSort.Popular -> members.byDownloads()
                CollectionSort.New -> members.sortedByDescending { it.publishedAt }
            }
        }
        val page = CollectionPage(
            summary = collection.summary(catalogue.heroes, catalog.today),
            heroes = heroes,
            hero = hero,
            sort = view.sort,
            cards = matching.take(view.shown).map { it.toCatalogItem(catalogue.heroes).copy(installation = state.installStatus(it.id)) },
            matching = matching.size,
        )
        val sheet = draft?.let {
            ProfileSheet(
                collection = collection.collection.title,
                name = it.name,
                nameProblem = if (it.submitting) null else profileNameProblem(it.name, state.shownProfiles?.profiles.orEmpty()),
                rows = heroes.sortedBy { hero -> hero.name }.map { hero ->
                    val options = collection.members.filter { mod -> mod.heroId == hero.id }.byDownloads()
                        .map { mod -> PickOption(mod.id, mod.title, state.installStatus(mod.id) is InstallStatus.Installed) }
                    HeroPick(hero, options, it.picks[hero.id])
                },
                blocked = state.editBlock(),
                submitting = it.submitting,
                problem = it.problem,
            )
        }
        return CollectionUiState(loading = false, page = page, sheet = sheet)
    }

    private companion object {
        const val PageSize = 12
        const val GameBananaMods = "https://gamebanana.com/mods"
    }
}

private fun List<Mod>.byDownloads(): List<Mod> = sortedByDescending { it.downloads }
