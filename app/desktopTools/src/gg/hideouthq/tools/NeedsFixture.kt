package gg.hideouthq.tools

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModDetail
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The sample app with Night of the Werecat listed under the GameBanana id the sample library installed it from, so Lilith's page shows it installed and on. */
internal object NeedsFixture {
    private val InstalledAs = mapOf("werecat" to "mod-551202")

    fun container(): AppContainer {
        val base = AppContainer.fake()
        return AppContainer(
            catalog = Installed(base.catalog),
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
    }

    private class Installed(private val catalog: CatalogRepository) : CatalogRepository by catalog {
        override val mods: Flow<List<Mod>> = catalog.mods.map { mods -> mods.map { it.copy(id = InstalledAs[it.id] ?: it.id) } }

        override fun detail(modId: String): Flow<Loadable<ModDetail>> = catalog.detail(modId).map { loaded ->
            if (loaded !is Loadable.Ready) return@map loaded
            Loadable.Ready(loaded.value.copy(requirements = loaded.value.requirements.map { it.copy(modId = InstalledAs[it.modId] ?: it.modId) }))
        }
    }
}
