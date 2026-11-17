package gg.hideouthq.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewManifestTest {
    @Test
    fun sampleManifestIsValid() {
        assertEquals(emptyList(), sampleManifest().problems())
    }

    @Test
    fun safeRelativePathsAreAccepted() {
        for (path in listOf("model.glb", "textures/body_01.png", "a/b/c-d.e.webp", "Pictures/Featured.PNG")) {
            assertNull(assetPathProblem(path), path)
        }
    }

    @Test
    fun pathsThatEscapeNeedEscapingOrBreakOnWindowsAreRejected() {
        val unsafe = listOf(
            "", "/model.glb", "a//b.png", "./a.png", "a/./b.png", "../a.png", "a/../b.png", "a/..", "..",
            "a\\b.png", "..\\a.png", "a%2fb.png", "%2e%2e/a.png", "a%00.png", "a\u0000.png", "a\n.png", "a\u007f.png",
            ".hidden.png", "a/.git/config.png", "a.png.", "a b.png", "a:b.png", "c:/a.png", "a?.png", "a#.png",
            "\u00e9.png", "con.png", "textures/NUL.png", "lpt1.tar.png", "manifest.json", "MANIFEST.JSON",
            "x".repeat(MAX_ASSET_PATH_LENGTH - 3) + ".png",
        )
        for (path in unsafe) assertNotNull(assetPathProblem(path), "accepted ${display(path)}")
    }

    @Test
    fun assetsMustBeUniqueIgnoringCaseAndNotNestInsideFiles() {
        val folded = sampleManifest().withAssets(asset("Pictures/Featured.WEBP", "image/webp"))
        assertProblem(folded, "asset paths repeat ignoring case")
        val nested = sampleManifest().withAssets(asset("pictures/featured.webp/inner.png"))
        assertProblem(nested, "is inside listed file")
    }

    @Test
    fun assetSizeHashAndTypeAreChecked() {
        assertProblem(sampleManifest().withAssets(asset("a.png").copy(bytes = 0)), "must be 1..")
        assertProblem(sampleManifest().withAssets(asset("b.png").copy(bytes = MAX_ASSET_BYTES + 1)), "must be 1..")
        assertProblem(sampleManifest().withAssets(asset("c.png").copy(sha256 = "A".repeat(64))), "sha256")
        assertProblem(sampleManifest().withAssets(asset("d.html", "text/html")), "unsupported type")
        assertProblem(sampleManifest().withAssets(asset("e.png", "image/webp")), "extension does not match")
        assertProblem(sampleManifest().withAssets(asset("f.zip", "application/zip")), "unsupported type")
        fun encoded(decodedBytes: Long) = sampleManifest().withAssets(
            asset("g.glb.zst", MODEL_MEDIA_TYPE).copy(encoding = AssetEncoding("zstd", decodedBytes, "a".repeat(64))))
        assertEquals(emptyList(), encoded(MAX_DECODED_MODEL_BYTES).problems())
        assertProblem(encoded(MAX_DECODED_MODEL_BYTES + 1), "decodedBytes must be 1..")
        assertEquals(emptyList(), sampleManifest().withAssets(asset("h.webp", "image/webp")).problems())
        assertProblem(sampleManifest().withAssets(asset("g.jpeg", "image/jpeg")), "unsupported type")
    }

    @Test
    fun readinessNeedsEvidence() {
        assertProblem(sampleManifest().copy(pictures = emptyList()), "picture is ready without pictures")
        assertProblem(sampleManifest().copy(scene = null, clips = emptyList()), "interactive is ready without a scene")
        assertProblem(sampleManifest().copy(picture = Readiness(ReadinessState.Ready)), "picture is ready without checks")
        assertProblem(sampleManifest().copy(interactive = Readiness(ReadinessState.Ready, checks = listOf("a", "a"))), "checks repeat")
    }

    @Test
    fun picturesReferenceListedImagesAndRealClipTimes() {
        val featured = sampleManifest().pictures.first()
        fun withFeatured(picture: PreviewPicture) = sampleManifest().copy(pictures = listOf(picture))
        assertProblem(withFeatured(featured.copy(asset = "pictures/missing.webp")), "is not listed")
        assertProblem(withFeatured(featured.copy(asset = "model.glb.zst")), "is not a WebP image")
        val png = sampleManifest().withAssets(asset("pictures/featured.png"))
        assertProblem(png.copy(pictures = listOf(featured.copy(asset = "pictures/featured.png"))), "is not a WebP image")
        assertProblem(withFeatured(featured.copy(clip = "run")), "is not an exported clip")
        assertProblem(withFeatured(featured.copy(timeMillis = 1251)), "time is outside clip")
        assertProblem(withFeatured(featured.copy(clip = null)), "without a clip must be at time zero")
        assertProblem(withFeatured(featured.copy(width = 0)), "dimensions")
        assertProblem(withFeatured(featured.copy(azimuth = Double.NaN)), "azimuth")
        assertProblem(withFeatured(featured.copy(lighting = "Studio Light")), "lighting")
        assertProblem(sampleManifest().copy(pictures = listOf(featured, featured)), "picture role featured is listed twice")
        assertEquals(emptyList(), withFeatured(featured.copy(timeMillis = 1250)).problems())
    }

    @Test
    fun clipsComeFromTheSceneWithRealDurations() {
        val manifest = sampleManifest()
        assertProblem(manifest.copy(clips = manifest.clips + PreviewClip("idle", 900)), "clip \"idle\" is listed twice")
        assertProblem(manifest.copy(clips = manifest.clips + PreviewClip("pose", 0)), "must last longer than zero")
        assertProblem(manifest.copy(missingClips = listOf("walk")), "both exported and missing")
        assertProblem(manifest.copy(scene = null), "clips are listed without a scene")
    }

    @Test
    fun sceneAndCapabilitiesMustBeExplicit() {
        val manifest = sampleManifest()
        assertProblem(manifest.copy(scene = manifest.scene!!.copy(model = "missing.glb")), "is not a listed asset")
        assertProblem(manifest.copy(scene = manifest.scene.copy(model = "pictures/detail.webp")), "is not model/gltf-binary")
        assertProblem(manifest.copy(scene = manifest.scene.copy(maxJointsPerSkin = -1)), "must not be negative")
        assertProblem(manifest.copy(scene = null, clips = emptyList(), capabilities = SceneCapabilities(weapon = true)), "capabilities are claimed without a scene")
        assertEquals(emptyList(), manifest.copy(capabilities = SceneCapabilities(weapon = true, styles = true)).problems())
    }

    @Test
    fun theBundleIdIsTheInputIdentitysHash() {
        assertProblem(sampleManifest().copy(bundleId = bundleIdOf(fixtureInput.copy(file = fixtureInput.file.copy(id = 1500002)))), "bundleId does not match")
        assertProblem(sampleManifest().copy(bundleId = FIXTURE_BUNDLE_ID.uppercase()), "bundleId must be 32 lowercase hex digits")
    }

    private fun BundleManifest.withAssets(vararg extra: PreviewAsset) = copy(assets = assets + extra)

    private fun assertProblem(manifest: BundleManifest, fragment: String) {
        val problems = manifest.problems()
        assertTrue(problems.any { fragment in it }, "expected a problem containing \"$fragment\" in $problems")
    }
}
