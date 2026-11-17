package gg.hideouthq.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import gg.hideouthq.core.designsystem.interaction.collectInteractionStates
import gg.hideouthq.core.designsystem.interaction.focusRing
import gg.hideouthq.core.designsystem.interaction.hideoutClickable
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutMotion
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.brightened

enum class ButtonVariant {
    Primary,

    Tonal,

    /** surface4, a secondary action on a surface3 row (Autoexec's crosshair row). */
    Raised,

    GlassOnHero,

    Text,

    /** Tertiary fill, for the main action inside a notice. */
    Warning,

    /** surface2 with muted text, for quiet destructive actions like Uninstall. */
    Subtle,

    /** errorContainer with onErrorContainer, for the action that confirms something destructive. */
    Danger,
}

enum class ButtonSize(val height: Dp) {
    Small(HideoutDimens.Button.HeightSmall),
    Medium(HideoutDimens.Button.HeightMedium),
    Regular(HideoutDimens.Button.Height),
    Large(HideoutDimens.Button.HeightLarge),
}

@Immutable
internal data class ButtonColors(val container: Color, val content: Color, val hoverContainer: Color)

private val ButtonVariant.scalesOnPress get() = this != ButtonVariant.Text

private val ButtonVariant.morphsOnPress get() = this == ButtonVariant.Primary || this == ButtonVariant.Tonal || this == ButtonVariant.Raised

@Composable
internal fun buttonColors(variant: ButtonVariant, contentOverride: Color?): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    val extra = HideoutTheme.colors
    val colors = when (variant) {
        ButtonVariant.Primary -> ButtonColors(scheme.primary, scheme.onPrimary, scheme.primary.brightened(HideoutMotion.HoverBrightness))
        ButtonVariant.Tonal -> ButtonColors(scheme.surfaceContainerHigh, scheme.onSurface, scheme.surfaceContainerHighest)
        ButtonVariant.Raised -> ButtonColors(scheme.surfaceContainerHighest, scheme.onSurface, scheme.surfaceContainerHighest.brightened(HideoutMotion.HoverBrightness))
        ButtonVariant.GlassOnHero -> ButtonColors(extra.glassOnHero, extra.onImage, extra.glassOnHeroHover)
        ButtonVariant.Text -> ButtonColors(scheme.surfaceContainerHigh.copy(alpha = 0f), scheme.onSurfaceVariant, scheme.surfaceContainerHigh)
        ButtonVariant.Warning -> ButtonColors(scheme.tertiary, scheme.onTertiary, scheme.tertiary.brightened(HideoutMotion.HoverBrightness))
        ButtonVariant.Subtle -> ButtonColors(scheme.surfaceContainer, scheme.onSurfaceVariant, scheme.surfaceContainerHigh)
        ButtonVariant.Danger -> ButtonColors(scheme.errorContainer, scheme.onErrorContainer, scheme.errorContainer.brightened(HideoutMotion.HoverBrightness))
    }
    return if (contentOverride != null) colors.copy(content = contentOverride) else colors
}

@Composable
private fun labelStyle(variant: ButtonVariant, size: ButtonSize): TextStyle {
    val type = HideoutTheme.typography
    return when {
        variant == ButtonVariant.Text -> type.labelPill
        size == ButtonSize.Small || size == ButtonSize.Medium -> type.labelPill.copy(fontWeight = type.labelButton.fontWeight)
        size == ButtonSize.Large -> type.labelButtonLarge
        else -> type.labelButton
    }
}

private fun defaultPadding(variant: ButtonVariant, size: ButtonSize): PaddingValues = PaddingValues(
    horizontal = when {
        variant == ButtonVariant.Text -> HideoutDimens.Button.TextHorizontalPadding
        size == ButtonSize.Small || size == ButtonSize.Medium -> HideoutDimens.Button.HorizontalPaddingSmall
        else -> HideoutDimens.Button.HorizontalPadding
    },
)

val PillButtonPadding = PaddingValues(start = 12.dp, end = 16.dp)

@Composable
fun HideoutButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Regular,
    leadingIcon: ImageVector? = null,
    leadingIconSize: Dp = HideoutDimens.Icon.Size,
    trailingIcon: ImageVector? = null,
    trailingIconSize: Dp = HideoutDimens.Icon.Size,
    enabled: Boolean = true,
    contentColor: Color? = null,
    contentPadding: PaddingValues = defaultPadding(variant, size),
) {
    ButtonFrame(
        variant = variant,
        size = size,
        contentColor = contentColor,
        enabled = enabled,
        modifier = modifier,
        contentPadding = contentPadding,
        onClick = onClick,
    ) { content ->
        if (leadingIcon != null) HideoutIcon(leadingIcon, contentDescription = null, size = leadingIconSize, tint = content)
        Text(text = text, style = labelStyle(variant, size), color = content, maxLines = 1)
        if (trailingIcon != null) HideoutIcon(trailingIcon, contentDescription = null, size = trailingIconSize, tint = content)
    }
}

@Composable
internal fun ButtonFrame(
    variant: ButtonVariant,
    size: ButtonSize,
    enabled: Boolean,
    modifier: Modifier,
    contentPadding: PaddingValues,
    contentColor: Color? = null,
    onClick: (() -> Unit)? = null,
    progress: (() -> Float)? = null,
    disabledAlpha: Float = HideoutMotion.DisabledAlpha,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val states = source.collectInteractionStates()
    val colors = buttonColors(variant, contentColor)
    val motion = HideoutTheme.motion
    val hovered = states.hovered && enabled
    val container by animateColorAsState(
        targetValue = if (hovered) colors.hoverContainer else colors.container,
        animationSpec = motion.effectsFast(),
        label = "buttonContainer",
    )
    val pressScale by animateFloatAsState(
        targetValue = if (states.pressed && variant.scalesOnPress && enabled) HideoutMotion.PressScale else 1f,
        animationSpec = motion.spatialFast(),
        label = "buttonPressScale",
    )
    val content by animateColorAsState(colors.content, motion.effectsDefault(), label = "buttonContent")
    val morph = animateFloatAsState(
        targetValue = if (states.pressed && variant.morphsOnPress) 1f else 0f,
        animationSpec = motion.spatialFast(),
        label = "buttonPressMorph",
    )
    val shape = remember(morph) { PressMorphShape(morph, HideoutRadius.ButtonPressed) }
    val sweep = HideoutTheme.colors.progressSweep

    Row(
        modifier = modifier
            .height(size.height)
            .alpha(if (enabled) 1f else disabledAlpha)
            .drawBehind {
                scale(pressScale) {
                    val outline = shape.createOutline(this.size, layoutDirection, this)
                    drawOutline(outline, container)
                    val done = progress?.invoke()?.coerceIn(0f, 1f) ?: 0f
                    if (done > 0f) {
                        clipPath(Path().apply { addOutline(outline) }) {
                            drawRect(sweep, size = Size(this.size.width * done, this.size.height))
                        }
                    }
                }
            }
            .focusRing(states.focusVisible, shape)
            .then(if (onClick != null) Modifier.hideoutClickable(source, enabled, onClick = onClick) else Modifier)
            .padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(HideoutDimens.Button.Gap, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content(content)
    }
}

internal class PressMorphShape(private val progress: State<Float>, private val pressedRadius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val full = minOf(size.width, size.height) / 2
        val pressed = with(density) { pressedRadius.toPx() }.coerceAtMost(full)
        val radius = lerp(full, pressed, progress.value.coerceIn(0f, 1f))
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }
}
