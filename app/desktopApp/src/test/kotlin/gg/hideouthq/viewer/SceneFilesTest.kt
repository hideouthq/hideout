package gg.hideouthq.viewer

import gg.hideouthq.data.preview.PreviewFileException
import gg.hideouthq.data.preview.ScriptedHost
import gg.hideouthq.data.preview.missing
import gg.hideouthq.data.preview.send
import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.decodeBundleManifest
import gg.hideouthq.preview.decodePreviewIndex
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class SceneFilesTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val store: Path = Path.of(checkNotNull(System.getProperty("preview.fixture")) { "Run with ./kotlin test, which sets preview.fixture from module.yaml" })
    private val entry = decodePreviewIndex(Files.readString(store.resolve("index.json"))).entries.single()
    private val bundle = store.resolve("bundles").resolve(entry.bundleId)
    private val manifest = decodeBundleManifest(Files.readString(bundle.resolve("manifest.json")))
    private val root = createTempDirectory("scene-files")
    private val jsonBytes = ByteBuffer.wrap(BentStrip.glb).order(ByteOrder.LITTLE_ENDIAN).getInt(12)

    @AfterTest
    fun cleanUp() = root.deleteRecursively()

    private fun ByteArray.edited(change: ByteBuffer.() -> Unit) = copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).change() }

    private fun shortened(less: Int) = BentStrip.glb.copyOf(BentStrip.glb.size - less).edited {
        putInt(8, capacity())
        putInt(20 + jsonBytes, capacity() - 28 - jsonBytes)
    }

    private fun fixtureHost() = ScriptedHost { exchange, _ ->
        val file = bundle.resolve(exchange.request.path.removePrefix("/v1/bundles/${entry.bundleId}/"))
        if (Files.isRegularFile(file)) Files.readAllBytes(file).let { exchange.send(it, etag = "\"${sha256Hex(it)}\"") } else exchange.missing()
    }

    @Test
    fun aPackedModelIsOneRequestAndProgressCountsItsStoredBytes() = runBlocking {
        val red = TestKtx2.solid(8, 8, TestKtx2.Red)
        val glb = BentStrip.texturedGlb(listOf(red))
        val packed = com.github.luben.zstd.Zstd.compress(glb, 6)
        val asset = PreviewAsset("model.glb.zst", packed.size.toLong(), sha256Hex(packed), "model/gltf-binary",
            gg.hideouthq.preview.AssetEncoding("zstd", glb.size.toLong(), sha256Hex(glb)))
        val strip = BentStrip.manifest(manifest).let { it.copy(scene = it.scene!!.copy(model = asset.path), assets = listOf(asset)) }
        ScriptedHost { exchange, _ -> exchange.send(packed, etag = "\"${asset.sha256}\"") }.use { host ->
            val progress = mutableListOf<SceneProgress>()
            val files = host.cache(root).sceneFiles(PreviewModel(entry, strip), onProgress = progress::add)
            assertContentEquals(readModel(glb).glb, files.glb)
            val image = files.textures.images.single()
            assertContentEquals(red, files.textures.bytes.copyOfRange(image.offset, image.offset + image.length))
            assertEquals(asset.bytes, progress.last().doneBytes)
            assertTrue(progress.all { it.totalBytes == asset.bytes })
            assertEquals(listOf("/v1/bundles/${entry.bundleId}/model.glb.zst"), host.requests.map { it.path })

            val kept = host.cache(root).sceneFiles(PreviewModel(entry, strip))
            assertContentEquals(files.textures.bytes, kept.textures.bytes)
            assertEquals(1, host.requests.size, "a kept model is read from the cache")
        }
    }

    @Test
    fun aKeptModelThatNoLongerMatchesIsFetchedAgain() = runBlocking {
        val (model, stored) = encodedModel(manifest.assets.single { it.path == manifest.scene!!.model }, BentStrip.glb)
        val strip = BentStrip.manifest(manifest).let { strip -> strip.copy(assets = strip.assets.map { if (it.path == model.path) model else it }) }
        ScriptedHost { exchange, _ -> exchange.send(stored, etag = "\"${model.sha256}\"") }.use { host ->
            host.cache(root).sceneFiles(PreviewModel(entry, strip))
            val kept = root.resolve("bundles").resolve(entry.bundleId).resolve(model.path)
            Files.write(kept, Files.readAllBytes(kept).also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() })
            assertContentEquals(readModel(BentStrip.glb, strip.clips).glb, host.cache(root).sceneFiles(PreviewModel(entry, strip)).glb)
            assertEquals(2, host.requests.size)
            assertContentEquals(stored, Files.readAllBytes(kept))
        }
    }

    @Test
    fun aZippedModelIsUnzippedOnItsWayToTheCache() = runBlocking {
        val (model, stored) = encodedModel(manifest.assets.single { it.path == manifest.scene!!.model }, BentStrip.glb)
        val strip = BentStrip.manifest(manifest).let { strip -> strip.copy(assets = strip.assets.map { if (it.path == model.path) model else it }) }
        val zipped = java.io.ByteArrayOutputStream().also { out -> java.util.zip.GZIPOutputStream(out).use { it.write(stored) } }.toByteArray()
        ScriptedHost { exchange, _ -> exchange.send(zipped, etag = "W/\"${model.sha256}\"", encoding = "gzip") }.use { host ->
            assertContentEquals(readModel(BentStrip.glb, strip.clips).glb, host.cache(root).sceneFiles(PreviewModel(entry, strip)).glb)
            assertContentEquals(stored, Files.readAllBytes(root.resolve("bundles").resolve(entry.bundleId).resolve(model.path)))
        }
    }

    @Test
    fun aDownloadThatDoesNotMatchIsNotKept() = runBlocking {
        val (model, stored) = encodedModel(manifest.assets.single { it.path == manifest.scene!!.model }, BentStrip.glb)
        val strip = BentStrip.manifest(manifest).let { strip -> strip.copy(assets = strip.assets.map { if (it.path == model.path) model else it }) }
        ScriptedHost { exchange, _ -> exchange.send(stored.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) }.use { host ->
            val failure = assertFailsWith<PreviewFileException> { host.cache(root).sceneFiles(PreviewModel(entry, strip)) }
            assertTrue("does not match its SHA-256" in failure.message.orEmpty(), failure.message)
            val folder = root.resolve("bundles").resolve(entry.bundleId)
            assertEquals(emptyList(), Files.list(folder).use { it.toList() }, "neither the file nor its partial download is kept")
        }
    }

    @Test
    fun aBinaryGltfIsItsJsonChunkThenAtMostOneBinChunkThatEndsIt() {
        val chunks = glbChunks(BentStrip.glb)
        assertTrue(chunks.json.startsWith("{\"asset\""), chunks.json.take(40))
        assertEquals((BentStrip.glb.size - 28 - jsonBytes).toLong(), chunks.binaryBytes)
        val jsonOnly = BentStrip.glb.copyOf(20 + jsonBytes).edited { putInt(8, capacity()) }
        assertEquals(null, glbChunks(jsonOnly).binaryBytes)

        val refused = mapOf(
            "a short header" to BentStrip.glb.copyOf(16),
            "glTF 1" to BentStrip.glb.edited { putInt(4, 1) },
            "a length that is not the file's" to BentStrip.glb.edited { putInt(8, capacity() + 4) },
            "a BIN chunk first" to BentStrip.glb.edited { putInt(16, 0x004E4942) },
            "a JSON chunk longer than the file" to BentStrip.glb.edited { putInt(12, capacity()) },
            "a cut BIN chunk" to BentStrip.glb.copyOf(BentStrip.glb.size - 4).edited { putInt(8, capacity()) },
            "a chunk after the BIN chunk" to (BentStrip.glb + ByteArray(12)).edited { putInt(8, capacity()) },
            "an unaligned BIN chunk" to shortened(1),
        )
        for ((what, bytes) in refused) assertFailsWith<PreviewFileException>(what) { glbChunks(bytes) }
    }

    @Test
    fun theReaderRefusesWhatABinaryGltfMayNotBe() {
        val refused = mapOf(
            "a short header" to BentStrip.glb.copyOf(16),
            "glTF 1" to BentStrip.glb.edited { putInt(4, 1) },
            "a length that is not the file's" to BentStrip.glb.edited { putInt(8, capacity() + 4) },
            "a BIN chunk first" to BentStrip.glb.edited { putInt(16, 0x004E4942) },
            "a JSON chunk longer than the file" to BentStrip.glb.edited { putInt(12, capacity()) },
            "a cut BIN chunk" to BentStrip.glb.copyOf(BentStrip.glb.size - 4).edited { putInt(8, capacity()) },
            "a chunk after the BIN chunk" to (BentStrip.glb + ByteArray(12)).edited { putInt(8, capacity()) },
            "an unaligned BIN chunk" to shortened(1),
            "a BIN chunk shorter than its views" to shortened(4),
        )
        for ((what, bytes) in refused) assertFails(what) { readModel(bytes) }
    }

    @Test
    fun aModelThatDisagreesWithItsManifestIsRefused() = runBlocking {
        fixtureHost().use { host ->
            for (lie in listOf(
                manifest.copy(clips = manifest.clips.map { it.copy(durationMillis = it.durationMillis + 100) }),
                manifest.copy(scene = manifest.scene!!.copy(maxJointsPerSkin = 3)),
                manifest.copy(clips = manifest.clips.drop(1)),
            )) {
                assertFailsWith<PreviewFileException> { host.cache(root).sceneFiles(PreviewModel(entry, lie)) }
            }
            assertEquals(listOf("/v1/bundles/${entry.bundleId}/${manifest.scene!!.model}"), host.requests.map { it.path })
        }
    }

    @Test
    fun aSceneIsNotFetchedWhereTheViewerCannotDraw() = runBlocking {
        fixtureHost().use { host ->
            val gl = lazy<GlThread> { throw UnsupportedOperationException("No OpenGL context") }
            val session = ViewportSession(PreviewModel(entry, manifest), host.cache(root), gl)
            session.load()
            val state = assertIs<SessionState.Unavailable>(session.state.value)
            val detail = manifest.pictures.single { it.role == PictureRole.Detail }.asset
            assertEquals("No OpenGL context", state.reason)
            assertEquals(listOf("/v1/bundles/${entry.bundleId}/$detail"), host.requests.map { it.path })
            session.close()
        }
    }

    @Test
    fun progressCountsTheModelInBytesFromTheStartToItsWholeSize() = runBlocking {
        fixtureHost().use { host ->
            val seen = mutableListOf<SceneProgress>()
            host.cache(root).sceneFiles(PreviewModel(entry, manifest)) { seen += it }
            val whole = manifest.assets.single { it.path == checkNotNull(manifest.scene).model }.bytes
            assertEquals(SceneProgress(0, whole).totalBytes, seen.first().totalBytes)
            assertEquals(0, seen.first().doneBytes)
            assertTrue(seen.zipWithNext().all { (before, after) -> after.doneBytes >= before.doneBytes }, "it never goes back")
            assertEquals(whole, seen.last().doneBytes)
            assertEquals(1f, seen.last().fraction)
        }
    }
}
