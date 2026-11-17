package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp
import androidx.graphics.shapes.Morph
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.image.LocalRemoteImages
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.shape.CirclePolygon
import gg.hideouthq.core.designsystem.shape.Cookie
import gg.hideouthq.core.designsystem.shape.MorphShape
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutTheme

enum class AvatarShape {
    Cookie8, Circle, Cookie6, RoundedSquare, Cookie12;

    companion object {
        fun forIndex(index: Int): AvatarShape = entries[index.mod(entries.size)]
    }
}

enum class AvatarFill {
    PrimaryContainer, Neutral, TertiaryContainer, Highlight;

    companion object {
        private val cycle = listOf(PrimaryContainer, Neutral, TertiaryContainer)
        fun forIndex(index: Int): AvatarFill = cycle[index.mod(cycle.size)]
    }
}

private val CookieToCircle: Map<Cookie, Morph> by lazy { Cookie.entries.associateWith { Morph(it.polygon, CirclePolygon) } }

private fun avatarShape(shape: AvatarShape, toCircle: Float): Shape {
    val cookie = when (shape) {
        AvatarShape.Cookie8 -> Cookie.Eight
        AvatarShape.Cookie6 -> Cookie.Six
        AvatarShape.Cookie12 -> Cookie.Twelve
        AvatarShape.Circle -> return CircleShape
        AvatarShape.RoundedSquare -> return RoundedCornerShape(lerp(HideoutRadius.AvatarSquare, HideoutDimens.HeroAvatar.Size / 2, toCircle.coerceIn(0f, 1f)))
    }
    return if (toCircle <= 0f) cookie.shape else MorphShape(CookieToCircle.getValue(cookie), toCircle.coerceIn(0f, 1f))
}

@Composable
private fun avatarColors(fill: AvatarFill): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (fill) {
        AvatarFill.PrimaryContainer -> scheme.primaryContainer to scheme.onPrimaryContainer
        AvatarFill.Neutral -> scheme.surfaceContainerHigh to scheme.onSurface
        AvatarFill.TertiaryContainer -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        AvatarFill.Highlight -> scheme.primary to scheme.onPrimary
    }
}

@Composable
fun ShapedAvatar(
    initials: String,
    shape: Shape,
    fill: AvatarFill,
    size: Dp,
    textStyle: TextStyle,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
) {
    val (container, content) = avatarColors(fill)
    val image = imageUrl?.let { LocalRemoteImages.current.rememberImage(it, ContentScale.Crop) }
    Box(modifier.size(size).background(container, shape).clip(shape), contentAlignment = Alignment.Center) {
        if (image?.loaded != true) Text(initials, style = textStyle, color = content, maxLines = 1)
        if (image != null) Image(image.painter, contentDescription = null, Modifier.fillMaxSize().then(image.layoutModifier), contentScale = ContentScale.Crop)
    }
}

@Composable
fun HeroAvatar(
    initials: String,
    name: String,
    shape: AvatarShape,
    fill: AvatarFill,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val progress by animateFloatAsState(
        targetValue = if (states.hovered || states.focusVisible) 1f else 0f,
        animationSpec = HideoutTheme.motion.spatialFast(),
        label = "heroAvatarHover",
    )
    val outline = avatarShape(shape, progress)
    Column(
        modifier = modifier
            .graphicsLayer { translationY = -HideoutDimens.HeroAvatar.HoverLift.toPx() * progress }
            .hideoutClickable(source, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(HideoutDimens.HeroAvatar.LabelGap),
    ) {
        ShapedAvatar(
            initials = initials,
            shape = outline,
            fill = fill,
            size = HideoutDimens.HeroAvatar.Size,
            textStyle = HideoutTheme.typography.initialsHero,
            modifier = Modifier.focusRing(states.focusVisible, outline),
            imageUrl = imageUrl,
        )
        Text(
            text = name,
            style = HideoutTheme.typography.labelAvatar,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
