package gg.hideouthq.core.crosshair

internal fun Crosshair.with(
    color: CrosshairColor? = null,
    length: Double? = null,
    thickness: Double? = null,
    gap: Double? = null,
    opacity: Double? = null,
    lineBorder: Double? = null,
    dotOpacity: Double? = null,
): Crosshair = listOfNotNull(
    length?.let { Adjustable.Length to it },
    thickness?.let { Adjustable.Thickness to it },
    gap?.let { Adjustable.Gap to it },
    opacity?.let { Adjustable.Opacity to it },
    lineBorder?.let { Adjustable.LineBorder to it },
    dotOpacity?.let { Adjustable.DotOpacity to it },
).fold(color?.let { copy(color = it) } ?: this) { crosshair, (slider, value) -> slider.put(crosshair, ConvarNumber.of(value)) }
