package gg.hideouthq.core.designsystem.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutRadius
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.designsystem.theme.HideoutWeights
import gg.hideouthq.core.ui.leaveOnEscape
import gg.hideouthq.core.ui.trackFieldFocus

/**
 * A run of [lines] (0-based) in a [CodeEditor] that cannot be edited. A run with a [label] is tinted primary with an edge
 * on the left, and carries the label at the right of its first line. No line opens above a [pinned] run, so it stays
 * first.
 */
@Immutable
data class LockedRun(val lines: IntRange, val label: String? = null, val pinned: Boolean = false)

enum class CodeRole { Comment, Command, Value }

@Immutable
data class CodeSpan(val range: IntRange, val role: CodeRole)

/**
 * A mono 13/22 editor over [state], with line numbers. [highlight] splits each line into [CodeSpan]s: comments sub,
 * commands on-surface, values sage. [locked] lines refuse any edit that touches them, though Enter at either end of a
 * run still opens a line beside it, except above a pinned run, and whole lines above a run can be deleted up to its
 * start. Esc leaves the editor rather than going back.
 */
@Composable
fun CodeEditor(
    state: TextFieldState,
    locked: List<LockedRun>,
    highlight: (String) -> List<CodeSpan>,
    label: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = HideoutTheme.colors
    val type = HideoutTheme.typography
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val runs = remember(locked) { mutableStateOf(locked) }
    val input = remember(runs) { LockedRunsTransformation(runs) }
    val styles = mapOf(
        CodeRole.Comment to SpanStyle(scheme.outline),
        CodeRole.Command to SpanStyle(scheme.onSurface),
        CodeRole.Value to SpanStyle(scheme.secondary),
    )
    val output = remember(highlight, styles) {
        OutputTransformation {
            var start = 0
            asCharSequence().toString().split('\n').forEach { line ->
                highlight(line).forEach { span -> addStyle(styles.getValue(span.role), start + span.range.first, start + span.range.last + 1) }
                start += line.length + 1
            }
        }
    }
    val layout = remember { TextLayoutHandle() }
    val scroll = rememberScrollState()
    val measurer = rememberTextMeasurer()
    val numberStyle = type.monoCode.copy(color = colors.codeGutter)
    val labelStyle = type.captionCompact.copy(color = scheme.onSurfaceVariant, fontWeight = HideoutWeights.Title)
    val dimens = HideoutDimens.CodeEditor

    BasicTextField(
        state = state,
        modifier = modifier.trackFieldFocus(focused).leaveOnEscape().semantics { contentDescription = label },
        inputTransformation = input,
        outputTransformation = output,
        textStyle = type.monoCode.copy(color = scheme.onSurface),
        lineLimits = TextFieldLineLimits.MultiLine(),
        onTextLayout = { result -> layout.result = result },
        interactionSource = source,
        cursorBrush = SolidColor(scheme.primary),
        scrollState = scroll,
        decorator = { inner ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(HideoutRadius.Editor))
                    .background(scheme.surface)
                    .drawBehind {
                        val text = layout.result() ?: return@drawBehind
                        val top = dimens.VerticalPadding.toPx() - scroll.value
                        val starts = lineStarts(state.text)
                        runs.value.forEach { run ->
                            val tag = run.label ?: return@forEach
                            val first = text.getLineForOffset(starts[run.lines.first.coerceIn(starts.indices)])
                            val last = text.getLineForOffset(lineEnd(starts, run.lines.last.coerceIn(starts.indices), state.text.length))
                            val from = top + text.getLineTop(first)
                            val to = top + text.getLineBottom(last)
                            drawRect(colors.codeLocked, Offset(0f, from), Size(size.width, to - from))
                            drawRect(colors.codeLockedEdge, Offset(0f, from), Size(dimens.LockedEdge.toPx(), to - from))
                            val measured = measurer.measure(tag, labelStyle)
                            val lineHeight = text.getLineBottom(first) - text.getLineTop(first)
                            drawText(measured, topLeft = Offset(size.width - dimens.EndPadding.toPx() - measured.size.width, from + (lineHeight - measured.size.height) / 2))
                        }
                        starts.forEachIndexed { index, offset ->
                            val measured = measurer.measure((index + 1).toString(), numberStyle)
                            val y = top + text.getLineTop(text.getLineForOffset(offset))
                            drawText(measured, topLeft = Offset((dimens.Gutter - dimens.NumberEnd).toPx() - measured.size.width, y))
                        }
                    }
                    .padding(start = dimens.Gutter, end = dimens.EndPadding, top = dimens.VerticalPadding, bottom = dimens.VerticalPadding),
            ) { inner() }
        },
    )
}

private class TextLayoutHandle {
    var result: () -> TextLayoutResult? = { null }
}

private fun lineStarts(text: CharSequence): List<Int> = buildList {
    add(0)
    text.forEachIndexed { index, char -> if (char == '\n') add(index + 1) }
}

private fun lineEnd(starts: List<Int>, line: Int, length: Int) = if (line + 1 < starts.size) starts[line + 1] - 1 else length

@OptIn(ExperimentalFoundationApi::class)
private class LockedRunsTransformation(private val runs: MutableState<List<LockedRun>>) : InputTransformation {
    override fun TextFieldBuffer.transformInput() {
        val before = originalText
        val starts = lineStarts(before)
        val spans = runs.value.map { run ->
            val first = run.lines.first.coerceIn(starts.indices)
            starts[first] to lineEnd(starts, run.lines.last.coerceIn(starts.indices), before.length)
        }
        val edits = (0 until changes.changeCount).map { changes.getOriginalRange(it) to asCharSequence().subSequence(changes.getRange(it).min, changes.getRange(it).max) }
        if (edits.any { (was, inserted) -> spans.zip(runs.value).any { (span, run) -> was.touches(span.first, span.second, inserted, run.pinned, starts) } }) {
            revertAllChanges()
            return
        }
        val moved = runs.value.zip(spans) { run, (start, _) ->
            val shift = edits.filter { (was, _) -> was.max <= start }.sumOf { (was, inserted) -> inserted.count { it == '\n' } - before.subSequence(was.min, was.max).count { it == '\n' } }
            if (shift == 0) run else run.copy(lines = run.lines.first + shift..run.lines.last + shift)
        }
        if (moved != runs.value) runs.value = moved
    }
}

private fun TextRange.touches(start: Int, end: Int, inserted: CharSequence, pinned: Boolean, lineStarts: List<Int>): Boolean {
    if (max < start || min > end) return false
    val opensLine = collapsed && ((min == start && !pinned && inserted.endsWith('\n')) || (min == end && inserted.startsWith('\n')))
    val deletesLinesAbove = inserted.isEmpty() && max == start && min < start && min in lineStarts
    return !opensLine && !deletesLinesAbove
}
