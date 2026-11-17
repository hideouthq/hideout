package gg.hideouthq.tools

import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.Loadable
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.DiscoverFeed
import gg.hideouthq.core.model.Mod
import gg.hideouthq.core.model.ModDetail
import gg.hideouthq.core.model.PreviewVariant
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.ReadinessState
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object InstallDialogFixture {
    val Lilith = SubmissionRef(SubmissionKind.Mod, 551_210)
    val Ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
    private const val SampleId = "lilith"
    private const val Added = 1_759_536_000L

    val Default = RemoteFile(1_510_001, "lilith_hell_warden.zip", 31_200_000, md5('1'), "Default", 2_600, Added, "https://gamebanana.com/dl/1510001")
    val Werewolf = RemoteFile(1_510_002, "lilith_werewolf_transform.zip", 34_600_000, md5('2'), "Werewolf Transform", 1_100, Added, "https://gamebanana.com/dl/1510002")
    val Cabezas = RemoteFile(1_510_003, "lilith_werewolf_cabezas.zip", 27_400_000, md5('3'), "Werewolf Transform Lilith Cabezas", 412, Added, "https://gamebanana.com/dl/1510003")
    val Files = listOf(Werewolf, Cabezas, Default)

    val ManyFiles = (1..9).map { index ->
        RemoteFile(
            id = 1_520_000L + index,
            fileName = "lilith_hell_warden_extended_edition_variant_number_${index}_with_a_very_long_file_name.zip",
            sizeBytes = 20_000_000L + index * 1_300_000,
            md5 = null,
            description = "Hell Warden variant $index with an author description long enough to need ellipsis",
            downloads = 1_000L - index,
            addedAt = Added,
            downloadUrl = "https://gamebanana.com/dl/${1_520_000L + index}",
        )
    }

    val Colours: List<Pair<String, ByteArray>> = listOf("Pink" to 7, "Black" to 3, "Blue" to 5, "Red" to 9).map { (name, number) ->
        "Lilith/$name/pak0${number}_dir.vpk" to FakeVpk.of(mapOf(
            "materials/models/heroes/lilith/clothing_top.vmat_c" to "$name top".encodeToByteArray(),
            "materials/models/heroes/lilith/clothing_skirt.vmat_c" to "$name skirt".encodeToByteArray(),
        ))
    } + ("Lilith/Necklace/pak01_dir.vpk" to FakeVpk.of(mapOf("models/heroes/lilith/necklace.vmdl_c" to "necklace".encodeToByteArray()))) +
        ("Lilith/Trim/pak02_dir.vpk" to FakeVpk.of(mapOf("materials/models/heroes/lilith/clothing_top.vmat_c" to "Trim top".encodeToByteArray())))

    fun container(
        files: List<RemoteFile> = Files,
        twoSets: Boolean = false,
        archives: Map<RemoteFile, List<Pair<String, ByteArray>>> = emptyMap(),
        downloads: (FakeDownloader) -> Unit = {},
        folders: FolderOpener = FolderOpener { _, _ -> true },
        indexes: ResourceIndexSource? = null,
    ): AppContainer {
        val banana = FakeGameBanana(
            mapOf(
                Lilith to FileListing.Available("Lilith, the Hell Warden", files),
                Ivy to FileListing.Available("Bubblegum ivy V2", listOf(ivy(1_691_864, "bg_ivy.zip", 1_999), ivy(1_691_865, "bg_ivy_mini.zip", 120))),
            ),
        )
        val base = AppContainer.fake(
            gameBanana = banana,
            folderOpener = folders,
            resourceIndexSource = indexes,
            downloader = { gameFiles ->
                FakeDownloader(gameFiles).apply {
                    (files + Files).distinct().forEach { file ->
                        val sets = archives[file] ?: if (twoSets && file == Werewolf) {
                            listOf("Lilith/Default/pak01_dir.vpk" to FakeVpk.directory(seed = 1), "Lilith/Werewolf/pak01_dir.vpk" to FakeVpk.directory(seed = 2))
                        } else {
                            listOf("lilith.vpk" to FakeVpk.directory(seed = file.id.toInt()))
                        }
                        serve(file.id, sets)
                    }
                    serve(1_691_864, listOf("bg_ivy.vpk" to FakeVpk.directory(seed = 7)))
                }.also(downloads)
            },
        )
        runBlocking { base.modLibrary.createProfile(NewProfile("Ranked", ProfileColour.Blue, ProfileSeed.Empty, activate = false)) }
        return AppContainer(
            catalog = RenamedLilith(base.catalog, files),
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

    private fun ivy(id: Long, name: String, downloads: Long) =
        RemoteFile(id, name, 23_159_953, null, null, downloads, Added, "https://gamebanana.com/dl/$id")

    private fun md5(digit: Char) = digit.toString().repeat(32)

    private class RenamedLilith(private val catalog: CatalogRepository, private val files: List<RemoteFile>) : CatalogRepository by catalog {
        override val mods: Flow<List<Mod>> = catalog.mods.map { mods -> mods.map(::renamed) }
        override val discoverFeed: Flow<DiscoverFeed> = catalog.discoverFeed.map { feed ->
            feed.copy(featured = feed.featured.map(::id), trendingSkins = feed.trendingSkins.map(::id))
        }

        override fun detail(modId: String): Flow<Loadable<ModDetail>> =
            catalog.detail(if (modId == Lilith.modId) SampleId else modId).map { loaded ->
                if (loaded !is Loadable.Ready) return@map loaded
                val detail = loaded.value
                Loadable.Ready(detail.copy(modId = id(detail.modId), updates = detail.updates.mapIndexed { index, update -> if (index == 0) update.copy(files = setOf(Default.id)) else update }))
            }

        override fun cachedMod(modId: String): Mod? = catalog.cachedMod(if (modId == Lilith.modId) SampleId else modId)?.let(::renamed)

        private fun id(sample: String) = if (sample == SampleId) Lilith.modId else sample

        private fun renamed(mod: Mod): Mod = if (mod.id != SampleId) mod else mod.copy(
            id = Lilith.modId,
            previewVariants = files.map { file -> PreviewVariant(file.id, file.md5, file.fileName, file.description, entry(file), file.sizeBytes) },
        )

        private fun entry(file: RemoteFile) = IndexEntry(
            submission = gg.hideouthq.preview.SubmissionRef("Mod", Lilith.id),
            fileId = file.id,
            fileMd5 = file.md5,
            bundleId = "%032x".format(file.id),
            manifestSha256 = "%064x".format(file.id),
            build = 1,
            picture = ReadinessState.Ready,
            interactive = ReadinessState.Ready,
        )
    }
}
