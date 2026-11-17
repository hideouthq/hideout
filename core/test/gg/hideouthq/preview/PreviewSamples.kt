package gg.hideouthq.preview

internal val fixtureInput = InputIdentity(
    game = GameInput(
        appId = 1422450,
        build = 21000001,
        depots = mapOf("1422451" to "7310089211263420011", "1422452" to "4592231077154218804"),
        snapshotSha256 = "d9f2b7d771ad9079b0549b973293ac309efd7cb220ba12ee236adef5e62765b0",
    ),
    hero = "hero_inferno",
    submission = SubmissionRef("Mod", 612345),
    file = FileInput(
        id = 1500001,
        md5 = "77108bcd62e970276cfa1296c799e150",
        sha256 = "019c263c13b3ce5d2a9bf3c3b3ecc0bae3d2a8eb497e5018f7221188185ee2c4",
        bytes = 35,
        vpk = "pak01_dir.vpk",
        vpkSha256 = "c0f7cec01f0371c6c4bfad606f3cf258e0977103013ee41218791fc61bad2d9c",
    ),
    converter = ConverterInput(
        vrfCommit = "127f4ad58874f568d7fbd87b419a31d15a5bd8c7",
        convertImage = "sha256:84bf59cb85168a4183eaa3e9caafffe1674c599695cb404d3deaaf3bcc1188a9",
        captureImage = "sha256:cc123c547a2da6fb18185710424b62ad03c16756174610ce2f8ab1804a017e5a",
    ),
    recipeSha256 = "1389d30266d5bb987b076176feb97f52d9d7af72a8a9d88a3628755f94eaaab3",
    dependencies = emptyList(),
)

internal const val FIXTURE_BUNDLE_ID = "7fcfac9ff3fe9ce4937a8e2d635a8dad"

internal fun asset(path: String, type: String = "image/png") =
    PreviewAsset(path, bytes = 100, sha256 = sha256Hex(path.encodeToByteArray()), type = type)

internal fun sampleManifest(input: InputIdentity = fixtureInput) = BundleManifest(
    schema = PREVIEW_SCHEMA,
    bundleId = bundleIdOf(input),
    input = input,
    createdAt = "2026-10-07T16:30:00Z",
    fidelity = Fidelity.StudioApproximation,
    attribution = "Fixture mod on GameBanana. Studio render, not an in-game screenshot.",
    assets = listOf(
        asset("model.glb.zst", MODEL_MEDIA_TYPE).copy(encoding = AssetEncoding("zstd", 2068, "c".repeat(64))),
        asset("pictures/featured.webp", "image/webp"),
        asset("pictures/detail.webp", "image/webp"),
    ),
    scene = PreviewScene(model = "model.glb.zst", maxJointsPerSkin = 2, influenceSets = 1, morphTargets = 0),
    clips = listOf(PreviewClip("idle", 1250), PreviewClip("walk", 800)),
    missingClips = listOf("run"),
    pictures = listOf(
        PreviewPicture(
            role = PictureRole.Featured, asset = "pictures/featured.webp", clip = "idle", timeMillis = 500,
            width = 24, height = 16, azimuth = 30.0, lighting = "studio_three_point",
            hiddenMeshSuffixes = listOf(".flask_in_hand_l"),
        ),
        PreviewPicture(
            role = PictureRole.Detail, asset = "pictures/detail.webp",
            width = 16, height = 24, azimuth = 0.0, lighting = "studio_three_point",
        ),
    ),
    picture = Readiness(ReadinessState.Ready, checks = listOf("nonblank", "quality_review")),
    interactive = Readiness(ReadinessState.Ready, checks = listOf("glb_structure")),
)

internal fun entryFor(manifest: BundleManifest, manifestSha256: String = "e".repeat(64)) = IndexEntry(
    submission = manifest.input.submission,
    fileId = manifest.input.file.id,
    fileMd5 = manifest.input.file.md5,
    bundleId = manifest.bundleId,
    manifestSha256 = manifestSha256,
    build = manifest.input.game.build,
    picture = manifest.picture.state,
    interactive = manifest.interactive.state,
)

internal fun indexOf(vararg entries: IndexEntry) =
    PreviewIndex(schema = PREVIEW_SCHEMA, generation = 3, publishedAt = "2026-10-07T16:31:00Z", entries = entries.toList())

internal fun sampleGltf(
    buffers: String = """[{"byteLength":64}]""",
    animations: String = """[{"name":"idle","samplers":[{"input":0,"output":1}]},{"name":"walk","samplers":[{"input":2,"output":3}]}]""",
    skins: String = """[{"joints":[1,2]}]""",
    meshes: String = """[{"primitives":[{"attributes":{"POSITION":4,"JOINTS_0":5,"WEIGHTS_0":6}}]}]""",
    extensionsRequired: String = "[]",
    accessors: String = """[{"max":[1.25]},{},{"max":[0.8]},{},{},{},{}]""",
): String = """
    {"asset":{"version":"2.0"},"buffers":$buffers,
     "accessors":$accessors,
     "skins":$skins,"meshes":$meshes,"animations":$animations,"extensionsRequired":$extensionsRequired}
""".trimIndent()
