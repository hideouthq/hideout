package gg.hideouthq.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.rememberConstraintsSizeResolver
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import gg.hideouthq.core.designsystem.image.RemoteImage
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.data.preview.PreviewPictureFetcher
import io.ktor.client.HttpClient
import okio.Path.Companion.toOkioPath
import java.io.File

class CoilRemoteImages(client: HttpClient, diskCache: File, private val previewPictures: PreviewPictureFetcher? = null) : RemoteImages {
    private val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
        .components {
            add(KtorNetworkFetcherFactory(httpClient = { client }))
            add(SvgDecoder.Factory())
            previewPictures?.let { add(it) }
        }
        .memoryCache { MemoryCache.Builder().maxSizeBytes(MemoryCacheBytes).build() }
        .diskCache { DiskCache.Builder().directory(diskCache.toOkioPath()).maxSizeBytes(DiskCacheBytes).build() }
        .crossfade(true)
        .build()

    @Composable
    override fun rememberImage(url: String, contentScale: ContentScale): RemoteImage {
        val sizeResolver = rememberConstraintsSizeResolver()
        val context = LocalPlatformContext.current
        val request = remember(url, context, sizeResolver) { ImageRequest.Builder(context).data(url).size(sizeResolver).build() }
        val painter = rememberAsyncImagePainter(request, loader, contentScale = contentScale)
        val state = painter.state.collectAsState()
        return remember(painter, state, sizeResolver) {
            RemoteImage(
                painter = painter,
                loaded = derivedStateOf { state.value is AsyncImagePainter.State.Success },
                layoutModifier = sizeResolver,
                failed = derivedStateOf { state.value is AsyncImagePainter.State.Error },
            )
        }
    }

    override suspend fun prefetch(url: String) {
        previewPictures?.prefetch(url)
    }

    private companion object {
        const val DiskCacheBytes = 256L * 1024 * 1024

        /** Coil sizes its cache by the Java heap by default, but its bitmaps live in Skia's native memory. */
        const val MemoryCacheBytes = 48L * 1024 * 1024
    }
}
