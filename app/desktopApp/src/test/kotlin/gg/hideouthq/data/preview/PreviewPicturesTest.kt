package gg.hideouthq.data.preview

import coil3.fetch.SourceFetchResult
import gg.hideouthq.core.data.preview.PreviewImageAddress
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.core.data.preview.PreviewSource
import gg.hideouthq.data.FileCacheStorage
import gg.hideouthq.preview.PictureRole
import gg.hideouthq.preview.PreviewAbility
import gg.hideouthq.preview.PreviewIcon
import gg.hideouthq.preview.ResourceOwner
import gg.hideouthq.preview.ResourceSource
import gg.hideouthq.preview.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.rules.Timeout
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PreviewPicturesTest {
    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    @Test
    fun aWebpPictureIsFetchedWithItsTypeAndVerifiedBytes() = runBlocking {
        ServedStore().use { store ->
            val asset = store.add("pictures/featured.webp", "webp bytes".encodeToByteArray(), "image/webp")
            store.republish(emptyMap()) { manifest ->
                manifest.copy(pictures = manifest.pictures.map { if (it.role == PictureRole.Featured) it.copy(asset = asset.path) else it })
            }
            val cache = store.cache(Files.createTempDirectory("webp-pictures"))
            withPreviews(cache) { previews ->
                val fetched = assertIs<SourceFetchResult>(PreviewPictureFetcher(previews, cache).fetch(PreviewImageAddress.Picture(store.entry().bundleId, PictureRole.Featured)))
                assertEquals("image/webp", fetched.mimeType)
                assertEquals(asset.sha256, sha256Hex(fetched.source.use { it.source().readByteArray() }))
            }
        }
    }

    @Test
    fun aPictureIsAskedForAlongsideItsManifestAndUsedOnceTheManifestVouchesForIt() = runBlocking {
        ServedStore().use { store ->
            val webp = store.add("pictures/featured.webp", "webp bytes".encodeToByteArray(), "image/webp")
            store.republish(emptyMap()) { manifest ->
                manifest.copy(pictures = manifest.pictures.map { if (it.role == PictureRole.Featured) it.copy(asset = webp.path) else it })
            }
            val bundleId = store.entry().bundleId
            val picture = "/v1/bundles/$bundleId/${webp.path}"
            val pictureAsked = CountDownLatch(1)
            var manifestWaitedForPicture = false
            ScriptedHost { exchange, _ ->
                val path = exchange.request.path
                if (path == picture) pictureAsked.countDown()
                if (path.endsWith("/manifest.json")) manifestWaitedForPicture = pictureAsked.await(5, TimeUnit.SECONDS)
                exchange.send(Files.readAllBytes(store.root.resolve(path.removePrefix("/v1/"))))
            }.use { host ->
                val cache = host.cache(Files.createTempDirectory("early-picture"))
                withPreviews(cache) { previews ->
                    val fetched = assertIs<SourceFetchResult>(PreviewPictureFetcher(previews, cache).fetch(PreviewImageAddress.Picture(bundleId, PictureRole.Featured)))
                    assertEquals(webp.sha256, sha256Hex(fetched.source.use { it.source().readByteArray() }))
                }
                assertTrue(manifestWaitedForPicture, "the picture was asked for while its manifest was still on the way")
                assertEquals(1, host.requests.count { it.path == picture }, "the early bytes were used, not asked for again")
            }
        }
    }

    @Test
    fun aPrefetchAndTheLoaderShareOneDownloadAndLeaveThePictureKept() = runBlocking {
        ServedStore().use { store ->
            val webp = store.add("pictures/featured.webp", "webp bytes".encodeToByteArray(), "image/webp")
            store.republish(emptyMap()) { manifest ->
                manifest.copy(pictures = manifest.pictures.map { if (it.role == PictureRole.Featured) it.copy(asset = webp.path) else it })
            }
            val bundleId = store.entry().bundleId
            val address = PreviewImageAddress.Picture(bundleId, PictureRole.Featured)
            ScriptedHost { exchange, _ ->
                val path = exchange.request.path
                if (path.startsWith("/v1/bundles/")) Thread.sleep(300)
                exchange.send(Files.readAllBytes(store.root.resolve(path.removePrefix("/v1/"))))
            }.use { host ->
                val cache = host.cache(Files.createTempDirectory("prefetch"))
                withPreviews(cache) { previews ->
                    val fetcher = PreviewPictureFetcher(previews, cache)
                    val prefetch = async(Dispatchers.IO) { fetcher.prefetch(address.url) }
                    val fetched = async(Dispatchers.IO) { assertIs<SourceFetchResult>(fetcher.fetch(address)) }
                    prefetch.await()
                    assertEquals(webp.sha256, sha256Hex(fetched.await().source.use { it.source().readByteArray() }))
                    val asked = host.requests.size
                    fetcher.fetch(address)
                    assertEquals(asked, host.requests.size, "the prefetched picture is kept")
                }
                assertEquals(1, host.requests.count { it.path == "/v1/bundles/$bundleId/${webp.path}" })
            }
        }
    }

    @Test
    fun earlyBytesTheManifestDoesNotVouchForAreNeverUsed() = runBlocking {
        ServedStore().use { store ->
            val bundleId = store.entry().bundleId
            val guessed = "/v1/bundles/$bundleId/pictures/featured.webp"
            val listed = store.add("pictures/featured-2.webp", Files.readAllBytes(store.bundle().resolve("pictures/featured.webp")), "image/webp")
            store.republish(emptyMap()) { manifest ->
                manifest.copy(pictures = manifest.pictures.map { if (it.role == PictureRole.Featured) it.copy(asset = listed.path) else it })
            }
            ScriptedHost { exchange, _ ->
                val path = exchange.request.path
                exchange.send(if (path == guessed) "a stale picture".encodeToByteArray() else Files.readAllBytes(store.root.resolve(path.removePrefix("/v1/"))))
            }.use { host ->
                val cache = host.cache(Files.createTempDirectory("other-path"))
                withPreviews(cache) { previews ->
                    val fetched = assertIs<SourceFetchResult>(PreviewPictureFetcher(previews, cache).fetch(PreviewImageAddress.Picture(bundleId, PictureRole.Featured)))
                    assertEquals("image/webp", fetched.mimeType)
                    assertEquals(listed.sha256, sha256Hex(fetched.source.use { it.source().readByteArray() }), "the manifest lists another path")
                }
                assertEquals(1, host.requests.count { it.path == "/v1/bundles/$bundleId/${listed.path}" })
            }

            val webp = store.add("pictures/featured.webp", "fresh webp".encodeToByteArray(), "image/webp")
            store.republish(emptyMap()) { manifest ->
                manifest.copy(pictures = manifest.pictures.map { if (it.role == PictureRole.Featured) it.copy(asset = webp.path) else it })
            }
            val tampered = AtomicBoolean(false)
            ScriptedHost { exchange, _ ->
                val path = exchange.request.path
                val first = path == guessed && tampered.compareAndSet(false, true)
                exchange.send(if (first) "tampered".encodeToByteArray() else Files.readAllBytes(store.root.resolve(path.removePrefix("/v1/"))))
            }.use { host ->
                val cache = host.cache(Files.createTempDirectory("tampered-early"))
                withPreviews(cache) { previews ->
                    val fetched = assertIs<SourceFetchResult>(PreviewPictureFetcher(previews, cache).fetch(PreviewImageAddress.Picture(bundleId, PictureRole.Featured)))
                    assertEquals(webp.sha256, sha256Hex(fetched.source.use { it.source().readByteArray() }), "bytes that miss the listed hash are asked for again")
                }
                assertEquals(2, host.requests.count { it.path == guessed })
            }
        }
    }

    @Test
    fun anAbilityIconIsFetchedOnlyWhenItsVerifiedManifestListsItAndItsBytesMatch() = runBlocking {
        ServedStore().use { store ->
            val icons = (1..4).associateWith { slot -> store.add("icons/ability_$slot.png", "modded $slot".encodeToByteArray()) }
            val vanilla = store.add("icons/vanilla/ability_1.png", "vanilla 1".encodeToByteArray())
            val stray = store.add("icons/stray.png", "not an icon".encodeToByteArray())
            store.republish(emptyMap()) { manifest ->
                manifest.copy(abilities = icons.map { (slot, asset) ->
                    val path = "panorama/images/hud/abilities/ability_${slot}_psd.vtex_c"
                    PreviewAbility(
                        slot = slot,
                        id = "ability_$slot",
                        name = "Ability $slot",
                        clipId = "idle".takeIf { slot == 1 },
                        icon = PreviewIcon(asset.path, ResourceSource(if (slot == 1) ResourceOwner.Mod else ResourceOwner.Game, path, asset.sha256), changedByMod = slot == 1),
                        vanillaIcon = if (slot == 1) PreviewIcon(vanilla.path, ResourceSource(ResourceOwner.Game, path, vanilla.sha256)) else null,
                    )
                })
            }
            val bundleId = store.entry().bundleId
            val cache = store.cache(Files.createTempDirectory("icons"))
            withPreviews(cache) { previews ->
                val fetcher = PreviewPictureFetcher(previews, cache)
                for (asset in listOf(icons.getValue(1), vanilla, icons.getValue(4))) {
                    val fetched = assertIs<SourceFetchResult>(fetcher.fetch(PreviewImageAddress.Icon(bundleId, asset.path)))
                    assertEquals("image/png", fetched.mimeType)
                    assertEquals(asset.sha256, sha256Hex(fetched.source.source().readByteArray()))
                }
                val unlisted = assertFailsWith<PreviewFileException> { fetcher.fetch(PreviewImageAddress.Icon(bundleId, stray.path)) }
                assertEquals("${stray.path} is not an ability icon of bundle $bundleId", unlisted.message)
                assertFalse(Files.exists(cache.root.resolve("bundles/$bundleId/${stray.path}")), "an asset no ability names is never fetched")
                assertFailsWith<PreviewFileException> { fetcher.fetch(PreviewImageAddress.Icon("0".repeat(32), icons.getValue(1).path)) }
            }

            val tampered = "/v1/bundles/$bundleId/${icons.getValue(2).path}"
            ScriptedHost { exchange, _ ->
                val path = exchange.request.path
                exchange.send(if (path == tampered) "tampered".encodeToByteArray() else Files.readAllBytes(store.root.resolve(path.removePrefix("/v1/"))))
            }.use { host ->
                val fresh = host.cache(Files.createTempDirectory("icons"))
                withPreviews(fresh) { previews ->
                    val refused = assertFailsWith<PreviewFileException> { PreviewPictureFetcher(previews, fresh).fetch(PreviewImageAddress.Icon(bundleId, icons.getValue(2).path)) }
                    assertEquals("${icons.getValue(2).path} does not match its SHA-256", refused.message)
                }
                assertTrue(host.requests.any { it.path == tampered })
            }
        }
    }

    private suspend fun withPreviews(source: PreviewSource, block: suspend (PreviewRepository) -> Unit) {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val previews = PreviewRepository(source, FileCacheStorage(Files.createTempDirectory("storage").toFile()), scope)
            withTimeout(10_000) { previews.index.first { it != null } }
            block(previews)
        } finally {
            scope.cancel()
        }
    }
}
