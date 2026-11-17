package gg.hideouthq.tools.preview

import gg.hideouthq.preview.AssetEncoding
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.ConverterInput
import gg.hideouthq.preview.Fidelity
import gg.hideouthq.preview.FileInput
import gg.hideouthq.preview.GameInput
import gg.hideouthq.preview.IndexEntry
import gg.hideouthq.preview.InputIdentity
import gg.hideouthq.preview.MODEL_MEDIA_TYPE
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
import gg.hideouthq.preview.decodePreviewIndex
import gg.hideouthq.preview.sha256Hex
import gg.hideouthq.viewer.glbChunks
import com.github.luben.zstd.Zstd
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToLong

internal object CoreFixture {
    val root: Path = Path.of("core/testResources/preview/store")
    val entry: IndexEntry get() = decodePreviewIndex(Files.readString(root.resolve("index.json"))).entries.single()
}

internal class HarnessStore(val root: Path, val infernus: IndexEntry, val wraith: IndexEntry)

internal object HarnessPreviews {
    const val InfernusModel = "models/heroes_wip/inferno/inferno.viewer.glb"
    const val Still = "pictures/still.webp"

    const val Attribution = "HARNESS LOCAL FIXTURE: research export from Deadlock build 25763945, not a GameBanana preview and not for publication. " +
        "Deadlock content belongs to Valve; the Klukai Wraith replacement belongs to its author."

    fun publish(root: Path, research: Path): HarnessStore {
        Files.createDirectories(root.resolve("bundles"))
        val still = CoreFixture.root.resolve("bundles/${CoreFixture.entry.bundleId}/pictures/featured.webp")
        val infernus = bundle(root, "hero_inferno", 900_000_001, research.resolve("current-infernus"), InfernusModel, still)
        val wraith = bundle(root, "hero_wraith", 900_000_002, research.resolve("current-mod-wraith"), "models/heroes_wip/wraith/wraith.viewer.glb", still)
        val index = PreviewIndex(1, generation = 1, publishedAt = "2026-10-07T17:00:00Z", entries = listOf(infernus, wraith))
        Files.writeString(root.resolve("index.json"), PreviewJson.encodeToString(PreviewIndex.serializer(), index))
        return HarnessStore(root, infernus, wraith)
    }

    private fun bundle(root: Path, hero: String, identity: Long, exportDirectory: Path, model: String, still: Path): IndexEntry {
        val glbFile = exportDirectory.resolve(model)
        val glb = Files.readAllBytes(glbFile)
        val gltf = PreviewJson.parseToJsonElement(glbChunks(glb).json).jsonObject
        val directory = model.substringBeforeLast('/') + "/"
        val stored = Zstd.compress(glb, 6)
        val storedModel = "$model.zst"
        val stillBytes = Files.readAllBytes(still)
        val assets = listOf(
            PreviewAsset(storedModel, stored.size.toLong(), sha256Hex(stored), MODEL_MEDIA_TYPE, AssetEncoding("zstd", glb.size.toLong(), sha256Hex(glb))),
            PreviewAsset(Still, stillBytes.size.toLong(), sha256Hex(stillBytes), "image/webp"),
        )
        val accessors = gltf.objects("accessors")
        val clips = gltf.objects("animations").map { animation ->
            val end = animation.objects("samplers").maxOf { accessors[it.getValue("input").jsonPrimitive.int].getValue("max").jsonArray[0].jsonPrimitive.double }
            PreviewClip(animation.getValue("name").jsonPrimitive.content, (end * 1000).roundToLong())
        }
        val stills = PictureRole.entries.map { role ->
            PreviewPicture(role, Still, clip = directory + "clips/ui_shop_idle", width = StillWidth, height = StillHeight, azimuth = -70.0, lighting = "studio_three_point")
        }
        val input = InputIdentity(
            game = GameInput(appId = 1422450, build = 25763945, depots = mapOf("1422451" to "1"), snapshotSha256 = sha256Hex(glb)),
            hero = hero,
            submission = SubmissionRef("Mod", identity),
            file = FileInput(id = identity, md5 = null, sha256 = sha256Hex(glb), bytes = glb.size.toLong(), vpk = "harness_fixture.vpk", vpkSha256 = sha256Hex(glb)),
            converter = ConverterInput(
                vrfCommit = "127f4ad58874f568d7fbd87b419a31d15a5bd8c7",
                convertImage = "sha256:" + sha256Hex("harness convert".encodeToByteArray()),
                captureImage = "sha256:" + sha256Hex("harness capture".encodeToByteArray()),
            ),
            recipeSha256 = sha256Hex("harness recipe".encodeToByteArray()),
            dependencies = emptyList(),
        )
        val scene = PreviewScene(
            model = storedModel,
            maxJointsPerSkin = gltf.objects("skins").maxOf { it.getValue("joints").jsonArray.size },
            influenceSets = gltf.objects("meshes").flatMap { it.objects("primitives") }.maxOf { p -> p.getValue("attributes").jsonObject.keys.count { it.startsWith("JOINTS_") } },
            morphTargets = gltf.objects("meshes").flatMap { it.objects("primitives") }.maxOf { it["targets"]?.jsonArray?.size ?: 0 },
        )
        val manifest = BundleManifest(
            schema = 1,
            bundleId = bundleIdOf(input),
            input = input,
            createdAt = "2026-10-07T17:00:00Z",
            fidelity = Fidelity.StudioApproximation,
            attribution = Attribution,
            assets = assets,
            scene = scene,
            clips = clips,
            pictures = stills,
            picture = Readiness(ReadinessState.Ready, checks = listOf("harness: core fixture still")),
            interactive = Readiness(ReadinessState.Ready, checks = listOf("harness: research export, structural inspection only")),
        )
        val bundle = root.resolve("bundles").resolve(manifest.bundleId)
        Files.createDirectories(bundle.resolve(storedModel).parent)
        Files.write(bundle.resolve(storedModel), stored)
        Files.createDirectories(bundle.resolve(Still).parent)
        Files.write(bundle.resolve(Still), stillBytes)
        val manifestBytes = PreviewJson.encodeToString(BundleManifest.serializer(), manifest).encodeToByteArray()
        Files.write(bundle.resolve("manifest.json"), manifestBytes)
        return IndexEntry(input.submission, input.file.id, null, manifest.bundleId, sha256Hex(manifestBytes), input.game.build, ReadinessState.Ready, ReadinessState.Ready)
    }

    private fun JsonObject.objects(key: String): List<JsonObject> = this[key]?.jsonArray?.map { it.jsonObject }.orEmpty()

    private const val StillWidth = 24
    private const val StillHeight = 16
}
