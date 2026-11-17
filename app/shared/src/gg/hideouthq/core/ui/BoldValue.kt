package gg.hideouthq.core.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

internal fun boldValue(text: String, value: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    val at = text.indexOf(value)
    if (at < 0) {
        append(text)
        return@buildAnnotatedString
    }
    append(text.substring(0, at))
    withStyle(style) { append(value) }
    append(text.substring(at + value.length))
}
