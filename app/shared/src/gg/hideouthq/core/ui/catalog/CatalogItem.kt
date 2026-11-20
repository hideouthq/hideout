package gg.hideouthq.core.ui.catalog

import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.preview.PreviewImageAddress
import gg.hideouthq.core.model.CoverTone
import gg.hideouthq.core.model.Hero
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.preview.PictureRole

data class CatalogItem(
    val id: String,
    val title: String,
    val category: ModCategory,
    val heroName: String?,
    val kind: String?,
    val author: String?,
    val downloads: Int?,
    val has3dPreview: Boolean,
    val coverTone: CoverTone?,
    val coverUrl: String?,
    val heroIconUrl: String? = null,
    val waveform: List<Float>? = null,
    val installation: InstallStatus? = null,
    val blurred: Boolean = false,
    val favourite: Boolean = false,
)

fun Mod.toCatalogItem(heroes: Map<String, Hero>): CatalogItem {
    val hero = heroId?.let { heroes[it] }
    return CatalogItem(
        id = id,
        title = title,
        category = category,
        heroName = hero?.name,
        kind = kind,
        author = author,
        downloads = downloads,
        has3dPreview = has3dPreview,
        coverTone = cover.tone,
        coverUrl = cover.url,
        heroIconUrl = hero?.iconUrl,
        waveform = soundWaveform(),
    )
}

val Mod.featuredRenderUrl: String?
    get() = preview?.takeIf { hasReadyPicture }?.let { PreviewImageAddress.Picture(it.bundleId, PictureRole.Featured).url }

fun Mod.soundWaveform(): List<Float>? =
    if (category == ModCategory.Sounds) SoundWaveforms.of(id, SoundWaveforms.CoverBars) else null

object SharedKeys {
    fun cover(modId: String) = "cover-$modId"
    fun title(modId: String) = "title-$modId"
}
