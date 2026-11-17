package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.layout.ContentScale
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.resources.Res
import gg.hideouthq.resources.grain
import gg.hideouthq.resources.onboarding_skyline
import gg.hideouthq.resources.onboarding_skyline_wide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.decodeToImageBitmap
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.rememberResourceEnvironment

enum class ArtScene(private val tall: DrawableResource, private val wide: DrawableResource, val alignment: Alignment) {
    Skyline(Res.drawable.onboarding_skyline, Res.drawable.onboarding_skyline_wide, Alignment.BottomCenter),
    ;

    fun picture(aspect: Float): DrawableResource = if (aspect < WideFrom) tall else wide
}

private const val WideFrom = 1.15f

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun ArtPanel(scene: ArtScene, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val grain = imageResource(Res.drawable.grain)
    Box(modifier.clip(RoundedCornerShape(HideoutDimens.ArtPanel.Radius)).background(Base)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val picture = rememberScenePicture(scene, maxWidth / maxHeight)
            updateTransition(picture, label = "scene").Crossfade(animationSpec = HideoutTheme.motion.sceneFade(), contentKey = { it?.scene }) { shown ->
                if (shown != null) {
                    Image(
                        shown.bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(),
                        alignment = shown.scene.alignment, contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val noise = ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
                onDrawBehind { drawRect(noise) }
            },
        )
        content()
    }
}

private val Base = Color(0xFF0D0C0D)

private class ScenePicture(val scene: ArtScene, val bitmap: ImageBitmap)

/**
 * The picture [scene] takes at [aspect], read and decoded off the UI thread, and until it is ready the one before it,
 * or null before the first. Only the panel holds the bitmaps, so they go with it; painterResource's cache would keep
 * them, at up to 20 MB each, for the life of the app.
 */
@Composable
private fun rememberScenePicture(scene: ArtScene, aspect: Float): ScenePicture? {
    val resource = scene.picture(aspect)
    val environment = rememberResourceEnvironment()
    return produceState<ScenePicture?>(null, resource, environment) {
        value = ScenePicture(scene, withContext(Dispatchers.Default) { getDrawableResourceBytes(environment, resource).decodeToImageBitmap() })
    }.value
}

@Composable
fun ArtStage(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().padding(HideoutDimens.ArtPanel.StageInset), contentAlignment = Alignment.Center, content = content)
}

@Composable
fun FloatingCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(HideoutDimens.ArtPanel.CardRadius), content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .shadow(HideoutDimens.ArtPanel.CardShadow, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .background(CardFill, shape)
            .border(HideoutDimens.Hairline, CardEdge, shape)
            .clip(shape),
        content = content,
    )
}

private val CardFill = Color(0xE01D1C1D)
private val CardEdge = Color(0x0FFFFFFF)
