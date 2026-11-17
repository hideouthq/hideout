package gg.hideouthq.core.data.preview

import gg.hideouthq.core.data.fake.NoCacheStorage
import gg.hideouthq.preview.AssetEncoding
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.ConverterInput
import gg.hideouthq.preview.FileInput
import gg.hideouthq.preview.Fidelity
import gg.hideouthq.preview.GameInput
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.InputIdentity
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.PreviewClip
import gg.hideouthq.preview.PreviewIndex
import gg.hideouthq.preview.PreviewJson
import gg.hideouthq.preview.PreviewPicture
import gg.hideouthq.preview.PreviewScene
import gg.hideouthq.preview.Readiness
import gg.hideouthq.preview.ReadinessState
import gg.hideouthq.preview.SubmissionRef
import gg.hideouthq.preview.bundleIdOf
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

internal fun noPreviews(): PreviewRepository = PreviewRepository(null, NoCacheStorage, CoroutineScope(Job()))

internal object PreviewFixtures {
    const val Md5 = "77108bcd62e970276cfa1296c799e150"

    fun manifest(
        submission: SubmissionRef = SubmissionRef("Mod", 1),
        fileId: Long = 101,
        md5: String? = Md5,
        featuredHides: List<String> = listOf(".flame_hair"),
        detailHides: List<String> = listOf(".flame_hair"),
    ): BundleManifest {
        val input = InputIdentity(
            game = GameInput(1422450, 21000001, mapOf("1422451" to "7310089211263420011"), hex('a', 64)),
            hero = "hero_inferno",
            submission = submission,
            file = FileInput(fileId, md5, hex('b', 64), 35, "pak01_dir.vpk", hex('c', 64)),
            converter = ConverterInput(hex('d', 40), "sha256:" + hex('e', 64), "sha256:" + hex('f', 64)),
            recipeSha256 = hex('0', 64),
            dependencies = emptyList(),
        )
        return BundleManifest(
            schema = 1,
            bundleId = bundleIdOf(input),
            input = input,
            createdAt = "2026-10-07T16:30:00Z",
            fidelity = Fidelity.StudioApproximation,
            attribution = "Test fixture, not a GameBanana preview.",
            assets = listOf(
                PreviewAsset("model.glb.zst", 714, hex('1', 64), "model/gltf-binary", AssetEncoding("zstd", 2044, hex('4', 64))),
                PreviewAsset("pictures/featured.webp", 32, hex('2', 64), "image/webp"),
                PreviewAsset("pictures/detail.webp", 32, hex('3', 64), "image/webp"),
            ),
            scene = PreviewScene("model.glb.zst", maxJointsPerSkin = 2, influenceSets = 1, morphTargets = 0),
            clips = listOf(PreviewClip("models/heroes_wip/inferno/clips/ui_shop_idle", 1250), PreviewClip("walk", 800)),
            pictures = listOf(
                PreviewPicture(PictureRole.Featured, "pictures/featured.webp", "walk", 400, 1200, 800, -70.0, "studio", featuredHides),
                PreviewPicture(PictureRole.Detail, "pictures/detail.webp", null, 0, 1200, 800, -70.0, "studio", detailHides),
            ),
            picture = Readiness(ReadinessState.Ready, checks = listOf("fixture review")),
            interactive = Readiness(ReadinessState.Ready, checks = listOf("fixture review")),
        )
    }

    fun entry(manifest: BundleManifest, manifestSha256: String = hex('9', 64)) = IndexEntry(
        submission = manifest.input.submission,
        fileId = manifest.input.file.id,
        fileMd5 = manifest.input.file.md5,
        bundleId = manifest.bundleId,
        manifestSha256 = manifestSha256,
        build = manifest.input.game.build,
        picture = manifest.picture.state,
        interactive = manifest.interactive.state,
    )

    fun index(vararg entries: IndexEntry, generation: Long = 1) = PreviewIndex(1, generation, "2026-10-07T16:31:00Z", entries.toList())

    fun text(index: PreviewIndex): String = PreviewJson.encodeToString(PreviewIndex.serializer(), index)

    fun text(manifest: BundleManifest): String = PreviewJson.encodeToString(BundleManifest.serializer(), manifest)

    private fun hex(digit: Char, length: Int) = digit.toString().repeat(length)
}

internal class FixturePreviewSource(var index: String, private val manifests: Map<String, String> = emptyMap()) : PreviewSource {
    var failIndex = false
    var indexRequests = 0
    var manifestRequests = 0

    val held = mutableListOf<String?>()

    override suspend fun indexText(held: String?): String? {
        indexRequests++
        this.held += held
        if (failIndex) throw IllegalStateException("Preview host unreachable")
        return index.takeUnless { held == sha256Hex(it.encodeToByteArray()) }
    }

    override suspend fun manifestText(entry: IndexEntry): String {
        manifestRequests++
        return manifests[entry.bundleId] ?: throw IllegalStateException("No such bundle")
    }
}
