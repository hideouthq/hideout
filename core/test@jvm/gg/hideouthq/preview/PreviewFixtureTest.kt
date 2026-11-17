package gg.hideouthq.preview

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class PreviewFixtureTest {
    private fun resource(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/preview/store/$path")) { "missing fixture $path" }.use { it.readBytes() }

    @Test
    fun fixtureStoreIsAValidPublishedBundle() {
        val index = decodePreviewIndex(resource("index.json").decodeToString())
        val entry = index.entries.single()
        assertEquals(FIXTURE_BUNDLE_ID, entry.bundleId)

        val manifestBytes = resource("bundles/${entry.bundleId}/manifest.json")
        assertEquals(entry.manifestSha256, sha256Hex(manifestBytes))
        val manifest = decodeBundleManifest(manifestBytes.decodeToString())
        assertEquals(emptyList(), entry.problemsAgainst(manifest))
        assertEquals(fixtureInput, manifest.input)

        for (asset in manifest.assets) {
            val bytes = resource("bundles/${entry.bundleId}/${asset.path}")
            assertEquals(asset.bytes, bytes.size.toLong(), asset.path)
            assertEquals(asset.sha256, sha256Hex(bytes), asset.path)
        }
        val model = manifest.assets.single { it.path == manifest.scene!!.model }
        val decoded = checkNotNull(javaClass.getResourceAsStream("/preview/model.glb")) { "missing the decoded fixture model" }.use { it.readBytes() }
        assertEquals(model.encoding!!.decodedBytes, decoded.size.toLong())
        assertEquals(model.encoding.decodedSha256, sha256Hex(decoded))
        val glb = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x46546C67, glb.getInt(0))
        assertEquals(glb.capacity(), glb.getInt(8))
        assertEquals(0x4E4F534A, glb.getInt(16))
        val json = String(glb.array(), 20, glb.getInt(12), Charsets.UTF_8)
        assertEquals(emptyList(), gltfProblems(manifest.scene!!.model, json, manifest))
        assertEquals(emptyList(), packedImageProblems(manifest.scene.model, json))
        assertEquals(listOf(MODEL_MEDIA_TYPE, "image/webp", "image/webp"), manifest.assets.map { it.type })
    }
}
