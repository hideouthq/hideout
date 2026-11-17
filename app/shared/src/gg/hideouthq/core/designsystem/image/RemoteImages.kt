package gg.hideouthq.core.designsystem.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale

/**
 * Turns an image URL into something drawable. Components draw their placeholder (a tint, initials)
 * and the image on top of it, so a loader may hand back a painter that is still loading and say
 * through [RemoteImage.loaded] when the picture has arrived. Without a loader (the default),
 * components show only their placeholders; a real one, backed by an image library, is provided at
 * the entry point without touching any screen.
 */
@Stable
fun interface RemoteImages {
    /** The image at [url], or null when this loader shows no images. */
    @Composable
    fun rememberImage(url: String, contentScale: ContentScale): RemoteImage?

    /** Gets the image at [url] ready ahead of a screen asking for it, where this loader can; failures are left for that screen. */
    suspend fun prefetch(url: String) {}
}

/** A painter that draws nothing until its picture has loaded. */
@Stable
class RemoteImage(
    val painter: Painter,
    loaded: State<Boolean>,
    val layoutModifier: Modifier = Modifier,
    failed: State<Boolean> = NeverFails,
) {
    val loaded: Boolean by loaded

    val failed: Boolean by failed
}

private object NeverFails : State<Boolean> {
    override val value: Boolean = false
}

object NoRemoteImages : RemoteImages {
    @Composable
    override fun rememberImage(url: String, contentScale: ContentScale): RemoteImage? = null
}

val LocalRemoteImages = staticCompositionLocalOf<RemoteImages> { NoRemoteImages }
