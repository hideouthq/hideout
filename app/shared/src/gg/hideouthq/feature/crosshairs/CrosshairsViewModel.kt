package gg.hideouthq.feature.crosshairs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gg.hideouthq.core.crosshair.Adjustable
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairCodes
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.CrosshairId
import gg.hideouthq.core.crosshair.CrosshairPick
import gg.hideouthq.core.crosshair.Crosshairs
import gg.hideouthq.core.crosshair.YoursChange
import gg.hideouthq.core.crosshair.community.BrowseQuery
import gg.hideouthq.core.crosshair.community.CommunityCrosshairs
import gg.hideouthq.core.crosshair.community.CrosshairSort
import gg.hideouthq.core.crosshair.community.CrosshairStyle
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.navigation.CrosshairsTab
import gg.hideouthq.core.ui.crosshair.CrosshairBackdrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.ZoneId
import kotlin.time.Clock

interface CrosshairsPageActions {
    fun selectTab(tab: CrosshairsTab)
    fun setBackdrop(backdrop: CrosshairBackdrop)

    fun setQuery(text: String)
    fun setSort(sort: CrosshairSort)
    fun setStyle(style: CrosshairStyle, on: Boolean)

    fun clearStyles()

    /** "No crosshairs match." › Clear filters: the search and the styles, keeping the sort. */
    fun clearFilters()
    fun retry()
    fun selectCommunity(id: String)
    fun useCommunity()
    fun toggleSaved()
    fun editCopy()

    /** The In use pill: Yours, with the crosshair in use picked. */
    fun editInUse()

    fun selectYours(id: CrosshairId)
    fun create()
    fun duplicate(id: CrosshairId)
    fun delete(id: CrosshairId)
    fun setName(text: String)

    /** Enter in the name field: it shows the name Yours keeps again. */
    fun commitName()
    fun setColor(color: CrosshairColor)
    fun setBorderColor(color: CrosshairColor)
    fun setValue(setting: Adjustable, value: Double)
    fun setFixedGap(on: Boolean)
    fun setCollapse(on: Boolean)
    fun reset()
    fun useSelected()
    fun setZoom(zoom: PreviewZoom)

    /** Saves the edits a refused save left pending again. */
    fun retrySave()

    fun openImport()
    fun setImportText(text: String)
    fun confirmImport()
    fun dismissImport()
}

class CrosshairsViewModel(
    private val crosshairs: Crosshairs,
    private val community: CommunityCrosshairs,
    tab: CrosshairsTab,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel(), CrosshairsPageActions {
    private val local = MutableStateFlow(CrosshairsLocal(tab))

    val uiState: StateFlow<CrosshairsUiState> = combine(crosshairs.state, community.crosshairs, local) { crosshairs, community, local ->
        crosshairsUi(crosshairs, community, local, clock.now(), zone)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), current())

    init {
        community.refresh()
    }

    /** The share code Copy code puts on the clipboard: the community crosshair in the panel. */
    fun communityCode(): String? = selectedCommunity()?.let { CrosshairCodes.encode(it.crosshair.crosshair, it.crosshair.name) }

    /** The share code Share puts on the clipboard: the crosshair in the editor. */
    fun shareCode(): String? = current().yours.selected?.let { CrosshairCodes.encode(it.crosshair, it.name) }

    override fun selectTab(tab: CrosshairsTab) = local.update { it.copy(tab = tab) }
    override fun setBackdrop(backdrop: CrosshairBackdrop) = local.update { it.copy(backdrop = backdrop) }
    override fun setQuery(text: String) = query { it.copy(text = text) }
    override fun setSort(sort: CrosshairSort) = query { it.copy(sort = sort) }
    override fun setStyle(style: CrosshairStyle, on: Boolean) = query { it.copy(styles = if (on) it.styles + style else it.styles - style) }
    override fun clearStyles() = query { it.copy(styles = emptySet()) }
    override fun clearFilters() = query { it.copy(text = "", styles = emptySet()) }
    override fun retry() = community.refresh()
    override fun selectCommunity(id: String) = local.update { it.copy(community = id) }

    override fun useCommunity() {
        selectedCommunity()?.let { crosshairs.use(CrosshairPick.Community(it.crosshair)) }
        local.update { it.copy(used = true) }
    }

    override fun toggleSaved() {
        val card = selectedCommunity() ?: return
        crosshairs.change(if (card.saved) YoursChange.Unsave(card.crosshair.id) else YoursChange.Save(card.crosshair))
    }

    override fun editCopy() {
        selectedCommunity()?.let { crosshairs.change(YoursChange.Copy(it.crosshair)) }?.let(::openYours)
    }

    override fun editInUse() {
        crosshairs.state.value.inUse?.let { openYours(it.id) }
    }

    override fun selectYours(id: CrosshairId) = local.update { it.copy(yours = id, nameDraft = null) }

    override fun create() {
        crosshairs.change(YoursChange.Create)?.let(::selectYours)
    }

    override fun duplicate(id: CrosshairId) {
        crosshairs.change(YoursChange.Duplicate(id))?.let(::selectYours)
    }

    override fun delete(id: CrosshairId) {
        if (crosshairs.change(YoursChange.Delete(id)) != null) local.update { it.copy(nameDraft = null) }
    }

    override fun setName(text: String) {
        val id = current().yours.selected?.id ?: return
        if (text.isNotBlank()) crosshairs.change(YoursChange.Rename(id, text))
        local.update { it.copy(yours = id, nameDraft = text) }
    }

    override fun commitName() = local.update { it.copy(nameDraft = null) }
    override fun setColor(color: CrosshairColor) = edit { it.copy(color = color) }
    override fun setBorderColor(color: CrosshairColor) = edit { it.copy(borderColor = color) }
    override fun setValue(setting: Adjustable, value: Double) = edit { setting.set(it, value) }
    override fun setFixedGap(on: Boolean) = edit { it.copy(fixedGap = on) }
    override fun setCollapse(on: Boolean) = edit { it.copy(collapse = on) }

    override fun reset() {
        current().yours.selected?.let { crosshairs.change(YoursChange.Reset(it.id)) }
    }

    override fun useSelected() {
        current().yours.selected?.let { crosshairs.use(CrosshairPick.Yours(it.id)) }
        local.update { it.copy(used = true) }
    }

    override fun setZoom(zoom: PreviewZoom) = local.update { it.copy(zoom = zoom) }
    override fun retrySave() = crosshairs.retry()
    override fun openImport() = local.update { it.copy(importing = ImportUi()) }
    override fun setImportText(text: String) = local.update { it.copy(importing = ImportUi(text)) }

    override fun confirmImport() {
        val importing = local.value.importing ?: return
        val read = CrosshairCodes.parse(importing.text) ?: return local.update { it.copy(importing = importing.copy(unreadable = true)) }
        val id = crosshairs.change(YoursChange.Import(read.name, read.crosshair)) ?: return
        local.update { it.copy(tab = CrosshairsTab.Yours, yours = id, nameDraft = null, importing = null) }
    }

    override fun dismissImport() = local.update { it.copy(importing = null) }

    private fun current(): CrosshairsUiState = crosshairsUi(crosshairs.state.value, community.crosshairs.value, local.value, clock.now(), zone)

    private fun selectedCommunity() = (current().browse.results as? Loadable.Ready)?.value?.selected

    private fun query(change: (BrowseQuery) -> BrowseQuery) = local.update { it.copy(query = change(it.query)) }

    private fun openYours(id: CrosshairId) = local.update { it.copy(tab = CrosshairsTab.Yours, yours = id, nameDraft = null) }

    private fun edit(change: (Crosshair) -> Crosshair) {
        current().yours.selected?.let { crosshairs.change(YoursChange.Settings(it.id, change(it.crosshair))) }
    }
}

private const val StopTimeoutMillis = 5_000L
