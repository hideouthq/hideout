package gg.hideouthq.core.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import gg.hideouthq.core.designsystem.component.CoverArt
import gg.hideouthq.core.designsystem.component.ImageLightbox
import gg.hideouthq.core.designsystem.component.LightboxLabels
import gg.hideouthq.core.model.CoverImage
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.carousel_next
import gg.hideouthq.resources.carousel_previous
import gg.hideouthq.resources.lightbox_close
import gg.hideouthq.resources.lightbox_position
import org.jetbrains.compose.resources.stringResource

@Composable
fun ImageViewer(
    seed: String,
    images: List<CoverImage>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
    modal: WindowModal,
) {
    ImageLightbox(
        count = images.size,
        index = index,
        onIndexChange = onIndexChange,
        onDismiss = onDismiss,
        modal = modal,
        labels = LightboxLabels(
            close = stringResource(Res.string.lightbox_close),
            previous = stringResource(Res.string.carousel_previous),
            next = stringResource(Res.string.carousel_next),
            position = stringResource(Res.string.lightbox_position, index + 1, images.size),
        ),
        image = { shown ->
            CoverArt(
                seed = "$seed-large-$shown",
                image = images[shown],
                modifier = Modifier.fillMaxSize(),
                tint = Color.Transparent,
                contentScale = ContentScale.Fit,
            )
        },
        thumbnail = { shown ->
            CoverArt(
                seed = "$seed-image-$shown",
                modifier = Modifier.fillMaxSize(),
                imageUrl = images[shown].url,
                tintIndex = ModLabels.tint(images[shown].tone),
            )
        },
    )
}
