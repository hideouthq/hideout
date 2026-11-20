package gg.hideouthq.tools

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [base] with the sample catalogue's mods listed under the GameBanana ids [ids] gives them, as the live catalogue lists
 * them, so a mod the sample library installed from such an id shows as installed, and a 1-click link finds its page.
 */
internal fun withGameBananaIds(base: AppContainer, ids: Map<String, String>): AppContainer = AppContainer(
    catalog = GameBananaIds(base.catalog, ids),
    launcher = base.launcher,
    soundPlayer = base.soundPlayer,
    images = base.images,
    previews = base.previews,
    modLibrary = base.modLibrary,
    preferences = base.preferences,
    folderPicker = base.folderPicker,
    folderOpener = base.folderOpener,
    about = base.about,
    migration = base.migration, autoexec = base.autoexec, crosshairs = base.crosshairs, communityCrosshairs = base.communityCrosshairs,
)

/** Night of the Werecat as the sample library installed it, on in Main. */
internal val InstalledWerecat = mapOf("werecat" to "mod-551202")

private class GameBananaIds(private val catalog: CatalogRepository, private val ids: Map<String, String>) : CatalogRepository by catalog {
    private val samples = ids.entries.associate { (sample, id) -> id to sample }

    override val mods: Flow<List<Mod>> = catalog.mods.map { mods -> mods.map { it.copy(id = ids[it.id] ?: it.id) } }

    override fun detail(modId: String): Flow<Loadable<ModDetail>> = catalog.detail(samples[modId] ?: modId).map { loaded ->
        if (loaded !is Loadable.Ready) return@map loaded
        val detail = loaded.value
        Loadable.Ready(detail.copy(modId = ids[detail.modId] ?: detail.modId, requirements = detail.requirements.map { it.copy(modId = ids[it.modId] ?: it.modId) }))
    }

    override fun cachedMod(modId: String): Mod? = catalog.cachedMod(samples[modId] ?: modId)?.let { it.copy(id = ids[it.id] ?: it.id) }
}
