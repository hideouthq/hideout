package gg.hideouthq.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewEncodingTest {
    private val encoding = AssetEncoding("zstd", 4096, "a".repeat(64))
    private val model = asset("compressed.glb.zst", MODEL_MEDIA_TYPE).copy(encoding = encoding)

    @Test
    fun encodedModelRoundTripsWithStoredAndDecodedMetadata() {
        val base = sampleManifest()
        val manifest = base.copy(
            assets = base.assets.filter { it.path != base.scene!!.model } + model,
            scene = base.scene!!.copy(model = model.path),
        )
        assertEquals(emptyList(), manifest.problems())
        assertEquals(manifest, decodeBundleManifest(PreviewJson.encodeToString(BundleManifest.serializer(), manifest)))
    }

    @Test
    fun encodingRequiresModelTypeAndCompoundExtension() {
        assertInvalid(model.copy(path = "compressed.glb"))
        assertInvalid(model.copy(path = "compressed.zst"))
        assertInvalid(model.copy(type = "application/zstd"))
        assertInvalid(model.copy(type = "image/png"))
        assertInvalid(model.copy(encoding = null))
        assertInvalid(model.copy(encoding = encoding.copy(codec = "brotli")))
    }

    @Test
    fun aPlainGlbModelIsRefused() {
        val base = sampleManifest()
        val plain = asset("model.glb", MODEL_MEDIA_TYPE)
        val manifest = base.copy(assets = base.assets.filter { it.path != base.scene!!.model } + plain, scene = base.scene!!.copy(model = plain.path))
        assertTrue(manifest.problems().any { "must be zstd-encoded" in it }, manifest.problems().toString())
        assertInvalid(plain.copy(path = "other.glb"))
    }

    @Test
    fun aViewerTextureIsNoAssetOfItsOwn() {
        assertTrue(withAsset(asset("textures/body.ktx2", TEXTURE_MEDIA_TYPE)).problems().any { "unsupported type \"image/ktx2\"" in it })
    }

    private fun withAsset(asset: PreviewAsset) = sampleManifest().let { it.copy(assets = it.assets + asset) }

    private fun assertInvalid(asset: PreviewAsset) = assertTrue(withAsset(asset).problems().isNotEmpty(), asset.toString())
}
