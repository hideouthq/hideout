package gg.hideouthq.data.preview

import gg.hideouthq.feature.moddetail.viewer.PreviewModel
import gg.hideouthq.preview.PreviewAsset
import gg.hideouthq.preview.decodeBundleManifest
import gg.hideouthq.preview.decodePreviewIndex
import gg.hideouthq.preview.sha256Hex
import gg.hideouthq.viewer.Ktx2Texture
import gg.hideouthq.viewer.glbChunks
import gg.hideouthq.viewer.sceneFiles
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.zip.GZIPOutputStream
import kotlin.random.Random
import org.junit.Rule
import org.junit.rules.Timeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PreviewCacheTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private val bundleId = "0123456789abcdef0123456789abcdef"
    private val body = Random(7).nextBytes(256 * 1024)
    private val asset = PreviewAsset("pictures/still.webp", body.size.toLong(), sha256Hex(body), "image/webp")
    private val tag = "\"${sha256Hex(body)}\""
    private val directory: Path = Files.createTempDirectory("preview-cache")
    private val PreviewCache.published get() = root.resolve("bundles/$bundleId/pictures/still.webp")

    @Test
    fun aDroppedConnectionIsAskedAgainForTheWholeFile() = runBlocking {
        ScriptedHost { exchange, attempt -> if (attempt == 1) exchange.send(body, stopAfter = body.size / 2) else exchange.send(body) }.use { host ->
            val cache = host.cache(directory)
            assertContentEquals(body, cache.asset(bundleId, asset))
            assertEquals(2, host.requests.size)
            assertContentEquals(body, Files.readAllBytes(cache.published))
        }
    }

    @Test
    fun aBodyThatIsWrongOrTooLongIsNeverPublished() = runBlocking {
        val corrupt = body.copyOf().also { it[100] = (it[100] + 1).toByte() }
        for (answer in listOf(corrupt, body + byteArrayOf(1))) for (contentLength in listOf(answer.size, null)) {
            val directory = Files.createTempDirectory("preview-cache")
            ScriptedHost { exchange, _ -> exchange.send(answer, etag = tag, contentLength = contentLength) }.use { host ->
                val cache = host.cache(directory)
                assertFailsWith<PreviewFileException> { cache.asset(bundleId, asset) }
                assertEquals(1, host.requests.size)
                assertFalse(Files.exists(cache.published))
            }
        }
    }

    @Test
    fun cachedFilesAreCheckedOnEveryReadAndReplacedWhenCorruptOrCut() = runBlocking {
        ScriptedHost { exchange, _ -> exchange.send(body, etag = tag) }.use { host ->
            val cache = host.cache(directory)
            cache.asset(bundleId, asset)
            assertContentEquals(body, cache.asset(bundleId, asset))
            assertEquals(1, host.requests.size)
            Files.write(cache.published, body.copyOf().also { it[0] = (it[0] + 1).toByte() })
            assertContentEquals(body, cache.asset(bundleId, asset))
            Files.write(cache.published, body.copyOf(1000))
            assertContentEquals(body, cache.asset(bundleId, asset))
            assertEquals(3, host.requests.size)
        }
    }

    @Test
    fun concurrentRequestsForOneAssetShareOneDownload() = runBlocking {
        ScriptedHost { exchange, _ ->
            Thread.sleep(200)
            exchange.send(body, etag = tag)
        }.use { host ->
            val cache = host.cache(directory)
            val results = List(8) { async(kotlinx.coroutines.Dispatchers.IO) { cache.asset(bundleId, asset) } }.awaitAll()
            results.forEach { assertContentEquals(body, it) }
            assertEquals(1, host.requests.size)
        }
    }

    @Test
    fun theIndexMustMatchAShaETagWhenTheHostSendsOne() = runBlocking {
        val index = """{"schema":1,"generation":1,"publishedAt":"2026-10-07T17:00:00Z","entries":[]}""".encodeToByteArray()
        for ((etag, accepted) in listOf("\"${sha256Hex(index)}\"" to true, null to true, "\"r2-style-md5-tag\"" to true, "\"${sha256Hex(byteArrayOf(1))}\"" to false)) {
            ScriptedHost { exchange, _ -> exchange.send(index, etag = etag) }.use { host ->
                val cache = host.cache(Files.createTempDirectory("preview-cache"))
                if (accepted) assertEquals(index.decodeToString(), cache.indexText(null)) else assertFailsWith<PreviewFileException> { cache.indexText(null) }
            }
        }
    }

    @Test
    fun theIndexIsAskedForZippedAndItsWeakShaETagMustMatchItUnzipped() = runBlocking {
        val index = """{"schema":1,"generation":1,"publishedAt":"2026-10-07T17:00:00Z","entries":[]}""".encodeToByteArray()
        for ((etag, accepted) in listOf("W/\"${sha256Hex(index)}\"" to true, "W/\"${sha256Hex(byteArrayOf(1))}\"" to false)) {
            ScriptedHost { exchange, _ -> exchange.send(gzip(index), etag = etag, encoding = "gzip") }.use { host ->
                val cache = host.cache(Files.createTempDirectory("preview-cache"))
                if (accepted) assertEquals(index.decodeToString(), cache.indexText(null)) else assertFailsWith<PreviewFileException> { cache.indexText(null) }
                assertEquals("gzip", host.requests.single().headers["accept-encoding"])
            }
        }
    }

    @Test
    fun anIndexThatUnzipsPastItsLimitIsRefused() = runBlocking {
        val bomb = gzip(ByteArray((16 shl 20) + 1) { ' '.code.toByte() })
        for (contentLength in listOf(bomb.size, null)) {
            ScriptedHost { exchange, _ -> exchange.send(bomb, contentLength = contentLength, encoding = "gzip") }.use { host ->
                assertFailsWith<PreviewFileException> { host.cache(Files.createTempDirectory("preview-cache")).indexText(null) }
            }
        }
    }

    @Test
    fun anIndexTheHostSaysIsUnchangedIsNotSentAgain() = runBlocking {
        val index = """{"schema":1,"generation":1,"publishedAt":"2026-10-07T17:00:00Z","entries":[]}""".encodeToByteArray()
        val tag = "\"${sha256Hex(index)}\""
        ScriptedHost { exchange, _ ->
            if (exchange.request.headers["if-none-match"] == tag) exchange.head(304, null, etag = tag).also { exchange.close() } else exchange.send(index, etag = tag)
        }.use { host ->
            val cache = host.cache(Files.createTempDirectory("preview-cache"))
            assertNull(cache.indexText(sha256Hex(index)))
            assertEquals(index.decodeToString(), cache.indexText(sha256Hex(byteArrayOf(1))))
            assertEquals(index.decodeToString(), cache.indexText(null))
            assertEquals(listOf(tag, "\"${sha256Hex(byteArrayOf(1))}\"", null), host.requests.map { it.headers["if-none-match"] })
        }
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test
    fun aManifestMustMatchTheIndexAndIsCheckedAgainWhenRead() = runBlocking {
        ServedStore().use { store ->
            val text = Files.readAllBytes(store.bundle().resolve("manifest.json"))
            val cache = store.cache(directory)
            assertFailsWith<PreviewFileException> { cache.manifestText(store.entry().copy(manifestSha256 = "0".repeat(64))) }
            assertFalse(Files.exists(cache.root.resolve("bundles/${store.entry().bundleId}/manifest.json")))
            assertEquals(text.decodeToString(), cache.manifestText(store.entry()))
            Files.writeString(cache.root.resolve("bundles/${store.entry().bundleId}/manifest.json"), "{}")
            assertEquals(text.decodeToString(), cache.manifestText(store.entry()))
        }
    }

    @Test
    fun clearingDeletesTheKeptFilesAndAPreviewDownloadsThemAgain() = runBlocking {
        ScriptedHost { exchange, _ -> exchange.send(body) }.use { host ->
            val cache = host.cache(directory)
            assertEquals(0, cache.bytes())
            cache.asset(bundleId, asset)
            assertEquals(body.size.toLong(), cache.bytes())
            cache.clear()
            assertEquals(0, cache.bytes())
            assertFalse(Files.exists(cache.published))
            assertContentEquals(body, cache.asset(bundleId, asset))
            assertEquals(2, host.requests.size)
        }
    }

    @Test
    fun aMissingFileIsRefusedWithoutAskingAgain() = runBlocking {
        ScriptedHost { exchange, _ -> exchange.missing() }.use { host ->
            assertFailsWith<PreviewFileException> { host.cache(directory).asset(bundleId, asset) }
            assertEquals(1, host.requests.size)
        }
    }

    @Test
    fun trimmingKeepsTheMostRecentlyUsedFilesWithinTheBudget() {
        val files = (1..4).map { i ->
            directory.resolve("bundles/$bundleId/$i.png").also {
                Files.createDirectories(it.parent)
                Files.write(it, ByteArray(100))
                Files.setLastModifiedTime(it, FileTime.fromMillis(i * 1000L))
            }
        }
        trim(directory, 250)
        assertEquals(listOf(false, false, true, true), files.map { Files.exists(it) })
    }

    @Test
    fun theHostSettingIsAPlainHttpUrl() {
        assertEquals("https://previews.example.test", PreviewCache.baseUrl(" https://previews.example.test/ "))
        assertEquals("http://127.0.0.1:8090", PreviewCache.baseUrl("http://127.0.0.1:8090"))
        for (value in listOf(null, "", "ftp://example.test", "https://user:secret@example.test", "https://example.test/?token=1", "https://example.test/#x", "file:///tmp/store", "not a url")) {
            assertNull(PreviewCache.baseUrl(value), value)
        }
    }

    @Test
    fun aServedStoreGivesAVerifiedScene() = runBlocking {
        ServedStore().use { store ->
            val cache = store.cache(directory)
            val entry = decodePreviewIndex(checkNotNull(cache.indexText(null))).entries.single()
            val files = cache.sceneFiles(PreviewModel(entry, decodeBundleManifest(cache.manifestText(entry)))) {}
            assertEquals(184L, glbChunks(files.glb).binaryBytes, "the fixture's meshes, its packed texture's view dropped")
            val image = files.textures.images.single()
            assertEquals(4 to 4, Ktx2Texture.parse("body", files.textures.bytes, image.offset, image.length).let { it.width to it.height })
        }
    }
}
