package gg.hideouthq.data.preview

import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import gg.hideouthq.core.data.preview.PreviewImageAddress
import gg.hideouthq.core.data.preview.PreviewRepository
import gg.hideouthq.preview.BundleManifest
import gg.hideouthq.preview.PreviewAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okio.Buffer
import okio.FileSystem
import java.util.concurrent.ConcurrentHashMap

class PreviewPictureFetcher(private val previews: PreviewRepository, private val cache: PreviewCache) : Fetcher.Factory<Uri> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loads = ConcurrentHashMap<PreviewImageAddress, Deferred<Pair<ByteArray, PreviewAsset>>>()

    override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
        if (data.scheme != PreviewImageAddress.Scheme) return null
        val address = PreviewImageAddress.parse(data.toString()) ?: return null
        return Fetcher { fetch(address) }
    }

    suspend fun fetch(address: PreviewImageAddress): FetchResult {
        val (bytes, asset) = load(address).await()
        return SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), asset.type, DataSource.DISK)
    }

    suspend fun prefetch(url: String) {
        val address = PreviewImageAddress.parse(url) ?: return
        try {
            load(address).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
    }

    private fun load(address: PreviewImageAddress): Deferred<Pair<ByteArray, PreviewAsset>> =
        loads.computeIfAbsent(address) {
            scope.async(start = CoroutineStart.LAZY) { verified(address) }.apply { invokeOnCompletion { loads.remove(address, this) } }
        }.apply { start() }

    private suspend fun verified(address: PreviewImageAddress): Pair<ByteArray, PreviewAsset> = coroutineScope {
        val entry = previews.index.value?.entries?.firstOrNull { it.bundleId == address.bundleId }
            ?: throw PreviewFileException("Bundle ${address.bundleId} is not in the current index")
        val likely = (address as? PreviewImageAddress.Picture)?.let { "pictures/${it.role.name.lowercase()}.webp" }
        val early = likely?.let { async { cache.unchecked(entry.bundleId, it) } }
        val manifest = previews.manifest(entry)
        val path = manifest.imageOf(address)
        val asset = manifest.assets.firstOrNull { it.path == path } ?: throw PreviewFileException("$path is not a listed asset")
        val unchecked = if (path == likely) early?.await() else null.also { early?.cancel() }
        cache.asset(entry.bundleId, asset, unchecked) to asset
    }

    private fun BundleManifest.imageOf(address: PreviewImageAddress): String = when (address) {
        is PreviewImageAddress.Picture -> pictures.firstOrNull { it.role == address.role }?.asset ?: throw PreviewFileException("No ${address.role} picture")
        is PreviewImageAddress.Icon -> address.asset.takeIf { asset -> abilities.any { it.icon.asset == asset || it.vanillaIcon?.asset == asset } }
            ?: throw PreviewFileException("${address.asset} is not an ability icon of bundle ${address.bundleId}")
    }
}
