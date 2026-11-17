package gg.hideouthq.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairPreset
import gg.hideouthq.core.designsystem.component.AppWindow
import gg.hideouthq.core.designsystem.component.ButtonSize
import gg.hideouthq.core.designsystem.component.ButtonVariant
import gg.hideouthq.core.designsystem.component.CodeEditor
import gg.hideouthq.core.designsystem.component.CodeRole
import gg.hideouthq.core.designsystem.component.CodeSpan
import gg.hideouthq.core.designsystem.component.FilterChip
import gg.hideouthq.core.designsystem.component.FilterChipStyle
import gg.hideouthq.core.designsystem.component.GlassBadge
import gg.hideouthq.core.designsystem.component.GlassBadgeSize
import gg.hideouthq.core.designsystem.component.HideoutButton
import gg.hideouthq.core.designsystem.component.HideoutSlider
import gg.hideouthq.core.designsystem.component.LockedRun
import gg.hideouthq.core.designsystem.component.PageHeader
import gg.hideouthq.core.designsystem.component.PillTabs
import gg.hideouthq.core.designsystem.component.SearchField
import gg.hideouthq.core.designsystem.component.SearchFieldSize
import gg.hideouthq.core.designsystem.component.SegmentedControl
import gg.hideouthq.core.designsystem.component.SegmentedStyle
import gg.hideouthq.core.designsystem.component.TextInput
import gg.hideouthq.core.designsystem.component.TextInputSize
import gg.hideouthq.core.designsystem.component.TextInputStyle
import gg.hideouthq.core.designsystem.icon.HideoutIcons
import gg.hideouthq.core.designsystem.theme.HideoutDimens
import gg.hideouthq.core.designsystem.theme.HideoutShapes
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.ui.crosshair.CrosshairBackdrop
import gg.hideouthq.core.ui.crosshair.CrosshairTile
import java.io.File

private class DesignCheck(val name: String, val run: (File) -> Unit)

fun main(args: Array<String>) {
    val checks = listOf(
        DesignCheck("slider", ::checkSlider),
        DesignCheck("pill_tabs", ::checkPillTabs),
        DesignCheck("page_header", ::checkPageHeader),
        DesignCheck("fields", ::checkFields),
        DesignCheck("glass", ::checkGlass),
        DesignCheck("toolbar", ::checkToolbar),
        DesignCheck("code_editor", ::checkCodeEditor),
        DesignCheck("crosshair_art", ::checkCrosshairArt),
    )
    val selected = selectTargets(args.getOrNull(1), checks.map { it.name })
    val outDir = File(args.firstOrNull() ?: "build/screenshots/design-system")
    checks.filter { it.name in selected }.forEach { check ->
        check.run(outDir)
        println("Design system check '${check.name}' passed")
    }
    println("All ${selected.size} design system checks passed.")
}

private class WindowEscapes {
    var count = 0
    val keys = WindowKeys(preview = { false }, after = { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) count++
        false
    })
}

private fun gallery(
    outDir: File, name: String, widthDp: Int, heightDp: Int, escapes: WindowEscapes = WindowEscapes(),
    content: @Composable ColumnScope.() -> Unit, script: Script.() -> Unit,
) = runScript(
    outDir, name, widthDp, heightDp,
    {
        HideoutTheme {
            AppWindow {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface, HideoutShapes.Pane).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = content,
                )
            }
        }
    },
    outputDirectory = File(outDir, name), windowKeys = escapes.keys, script = script,
)

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(24.dp)).padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 8.dp), content = content)
}

private fun Script.single(label: String, matches: (SemanticsNode) -> Boolean = { true }): SemanticsNode {
    val found = nodes().filter { it.hasLabel(label) && matches(it) }
    check(found.size == 1) { "Expected one '$label', found ${found.size}" }
    return found.single()
}

private fun SemanticsNode.isFocused() = config.getOrElseNullable(SemanticsProperties.Focused) { null } == true

private fun Script.clickAtDp(node: SemanticsNode, xDp: Float, yDp: Float) {
    moveToDp(node.boundsInRoot.left / scale + xDp, node.boundsInRoot.top / scale + yDp)
    click()
    advance(300)
}

private fun Script.clickEnd(node: SemanticsNode) = clickAtDp(node, node.boundsInRoot.width / scale - 2, node.boundsInRoot.height / scale / 2)

private fun checkSlider(outDir: File) {
    var length by mutableFloatStateOf(3f)
    var gap by mutableFloatStateOf(-7f)
    val outOfRange = 12f
    var outOfRangeMoved = false
    var sliderHeight = 0
    gallery(outDir, "slider", 440, 420, content = {
        Card(Modifier.width(400.dp)) {
            Column(Modifier.padding(start = 8.dp, top = 10.dp, end = 8.dp, bottom = 6.dp)) {
                SliderRow("Length", length, 0f..30f, 0.5f, length.toString().removeSuffix(".0")) { length = it }
                SliderRow("Thickness", 1.5f, 0f..10f, 0.5f, "1.5") {}
                SliderRow("Gap", gap, -20f..20f, 1f, gap.toInt().toString(), { sliderHeight = it }) { gap = it }
                SliderRow("Opacity", 1f, 0f..1f, 0.05f, "100%") {}
                SliderRow("Dot", 0f, 0f..1f, 0.05f, "0%") {}
                SliderRow("Wide", outOfRange, 0f..10f, 0.5f, "12") { outOfRangeMoved = true }
            }
        }
    }) {
        capture("rows")
        check(sliderHeight == (36 * scale).toInt()) { "the slider should be 36dp tall, not ${sliderHeight / scale}" }
        tabTo("the Gap slider") { it.hasLabel("Gap") && it.isFocused() }
        advance(300)
        capture("gap_focused")
        key(Key.DirectionRight)
        check(gap == -6f) { "Right should move the gap one step to -6, not $gap" }
        key(Key.DirectionLeft)
        key(Key.DirectionLeft)
        check(gap == -8f) { "Left twice should move the gap to -8, not $gap" }
        val range = single("Gap") { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }.config[SemanticsProperties.ProgressBarRangeInfo]
        check(range.range == -20f..20f && range.current == -8f) { "the slider should report -8 of -20..20, not $range" }
        val track = single("Length") { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
        moveTo(track)
        dragByDp(40f, 0f)
        release()
        advance(300)
        check(length > 3f && length * 2 == (length * 2).toInt().toFloat()) { "dragging right should raise the length in half steps, not to $length" }
        capture("length_dragged")
        check(!outOfRangeMoved) { "a value outside the range should not change until its handle moves" }
    }
}

@Composable
private fun SliderRow(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float, shown: String,
    onHeight: (Int) -> Unit = {}, onChange: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(92.dp), style = HideoutTheme.typography.captionSmall, color = scheme.onSurfaceVariant)
        HideoutSlider(value, onChange, range, step, label, Modifier.weight(1f).onSizeChanged { onHeight(it.height) })
        Text(shown, Modifier.width(44.dp), style = HideoutTheme.typography.monoCode.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium), color = scheme.onSurface, textAlign = TextAlign.End)
    }
}

private enum class SampleTab { Browse, Yours }

private fun checkPillTabs(outDir: File) {
    var tab by mutableStateOf(SampleTab.Browse)
    gallery(outDir, "pill_tabs", 440, 120, content = {
        PillTabs(
            SampleTab.entries, tab, { tab = it }, { it.name },
            icon = { if (it == SampleTab.Browse) HideoutIcons.Grid else HideoutIcons.Crosshairs },
            groupLabel = "Crosshairs",
            count = { if (it == SampleTab.Yours) "4" else null },
        )
    }) {
        capture("browse")
        moveTo(single("Yours") { it.config.contains(SemanticsActions.OnClick) })
        click()
        advance(64)
        capture("moving")
        advance(800)
        checkSelected("Yours", true)
        checkSelected("Browse", false)
        check(tab == SampleTab.Yours) { "clicking Yours should select it" }
        capture("yours")
    }
}

private fun checkPageHeader(outDir: File) {
    val subtitle = "Pick one from Deadlock Mod Manager’s community library, or make your own and check it on different backgrounds."
    gallery(outDir, "page_header", 1180, 300, content = {
        Box(Modifier.fillMaxWidth()) {
            PageHeader("Crosshairs", subtitle, Modifier.fillMaxWidth()) {
                HideoutButton("Import", {}, variant = ButtonVariant.Tonal, size = ButtonSize.Small, leadingIcon = HideoutIcons.FileText)
                HideoutButton("Share", {}, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
            }
        }
        Box(Modifier.width(560.dp)) {
            PageHeader("Autoexec", "Console commands Deadlock runs every time it starts, and a few more words to wrap.", Modifier.fillMaxWidth()) {
                HideoutButton("Open folder", {}, variant = ButtonVariant.Tonal, size = ButtonSize.Small)
            }
        }
    }) {
        capture("wide_and_narrow")
        val wideText = single(subtitle)
        val share = single("Share") { it.config.contains(SemanticsActions.OnClick) }
        check(share.boundsInRoot.bottom <= wideText.boundsInRoot.bottom + scale && share.boundsInRoot.bottom >= wideText.boundsInRoot.bottom - scale) {
            "beside the text, the actions should sit level with its bottom: ${share.boundsInRoot.bottom} vs ${wideText.boundsInRoot.bottom}"
        }
        val narrowText = single("Console commands Deadlock runs every time it starts, and a few more words to wrap.")
        val open = single("Open folder") { it.config.contains(SemanticsActions.OnClick) }
        check(open.boundsInRoot.top > narrowText.boundsInRoot.bottom) { "with no room beside the text, the actions should move under it" }
        check(open.boundsInRoot.right >= narrowText.boundsInRoot.right) { "under the text, the actions should stay at the right" }
    }
}

private fun checkFields(outDir: File) {
    var name by mutableStateOf("Mine")
    var hex by mutableStateOf("#00FF00")
    var command by mutableStateOf("")
    var value by mutableStateOf("240")
    var added: String? = null
    val escapes = WindowEscapes()
    gallery(outDir, "fields", 440, 330, escapes, content = {
        Card(Modifier.width(400.dp)) {
            Column(Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextInput(name, { name = it }, "Name", Modifier.fillMaxWidth(), style = TextInputStyle.Title)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(
                        hex, { hex = it.uppercase() }, "Hex colour", Modifier.width(114.dp), size = TextInputSize.Compact,
                        leading = { Box(Modifier.size(18.dp).background(Color(0xFF00FF00), RoundedCornerShape(6.dp))) },
                    )
                    TextInput(value, { value = it }, "Value for fps_max", Modifier.width(HideoutDimens.Field.ValueWidth), size = TextInputSize.Compact, textAlign = TextAlign.End)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextInput(command, { command = it }, "Add a command", Modifier.weight(1f), placeholder = "Add a command, e.g. fps_max 240", onSubmit = { added = command })
                    HideoutButton("Add", { added = command }, variant = ButtonVariant.Tonal)
                }
            }
        }
    }) {
        capture("rest")
        clickEnd(single("Name"))
        capture("name_focused")
        type("s")
        check(name == "Mines") { "typing should edit the name, not leave '$name'" }
        key(Key.Escape)
        check(!single("Name").isFocused()) { "Esc should leave the field" }
        check(escapes.count == 0) { "Esc in a field should not reach the window, where it goes back" }
        clickEnd(single("Add a command") { it.config.contains(SemanticsProperties.Focused) })
        type("fps_max 240")
        key(Key.Enter)
        check(added == "fps_max 240") { "Enter should submit the command, not '$added'" }
        clickEnd(single("Value for fps_max"))
        type("0")
        check(value == "2400") { "typing should edit the value, not leave '$value'" }
        capture("value_focused")
        moveToDp(30f, 300f)
        click()
        advance(300)
        check(nodes().none { it.isFocused() && (it.hasLabel("Value for fps_max") || it.hasLabel("Name")) }) { "a click outside should take focus from the field" }
    }
}

private val Picture = Brush.verticalGradient(listOf(Color(0xFF41506B), Color(0xFF2B2734), Color(0xFF14161D)))

private fun checkGlass(outDir: File) {
    var background by mutableStateOf("Dark")
    var zoom by mutableStateOf("3×")
    gallery(outDir, "glass", 700, 520, content = {
        Box(Modifier.width(640.dp).aspectRatio(16f / 9f).background(Picture, RoundedCornerShape(24.dp))) {
            SegmentedControl(listOf("Sky", "Dark", "Grey"), background, { background = it }, { it }, Modifier.align(Alignment.TopStart).padding(14.dp), segmentPadding = 12.dp, style = SegmentedStyle.Glass)
            SegmentedControl(listOf("Actual size", "3×", "6×"), zoom, { zoom = it }, { it }, Modifier.align(Alignment.BottomEnd).padding(14.dp), segmentPadding = 12.dp, style = SegmentedStyle.Glass)
            GlassBadge("Mine · in use", Modifier.align(Alignment.BottomStart).padding(14.dp), size = GlassBadgeSize.Regular, dot = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(200.dp, 125.dp).background(Picture, RoundedCornerShape(14.dp))) {
                GlassBadge("In use", Modifier.padding(8.dp), dot = true)
            }
            Box(Modifier.size(200.dp, 125.dp).background(Picture, RoundedCornerShape(14.dp))) {
                GlassBadge("Saved", Modifier.padding(8.dp))
            }
        }
    }) {
        capture("dark")
        clickLabel("Sky")
        clickLabel("6×")
        advance(800)
        checkSelected("Sky", true)
        checkSelected("6×", true)
        capture("sky_6x")
    }
}

private fun checkToolbar(outDir: File) {
    var query by mutableStateOf("")
    var sort by mutableStateOf("Popular")
    val styles = listOf("All", "Small", "Dot only", "With a dot", "Outline", "Fixed gap")
    var chosen by mutableStateOf(setOf("All"))
    val categories = listOf("All" to null, "Performance" to "4", "HUD & UI" to "1", "Network" to "1", "Audio" to "3")
    var category by mutableStateOf("All")
    gallery(outDir, "toolbar", 760, 330, content = {
        Card(Modifier.width(720.dp)) {
            Column(Modifier.padding(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchField(query, { query = it }, "Search crosshairs or creators", Modifier.weight(1f), size = SearchFieldSize.Toolbar)
                    SegmentedControl(listOf("Popular", "This week", "New"), sort, { sort = it }, { it }, style = SegmentedStyle.Card)
                }
                FlowRow(Modifier.padding(top = 12.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    styles.forEach { style ->
                        FilterChip(style, style in chosen, { on -> chosen = if (on) chosen + style else chosen - style }, style = FilterChipStyle.Outlined)
                    }
                }
                FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { (name, count) ->
                        FilterChip(name, name == category, { category = name }, style = FilterChipStyle.Outlined, count = count)
                    }
                }
            }
        }
    }) {
        capture("rest")
        clickEnd(single("Search crosshairs or creators"))
        type("kiri")
        check(query == "kiri") { "typing should search, not leave '$query'" }
        clickLabel("Small")
        advance(600)
        check(chosen == setOf("All", "Small")) { "clicking Small should tick it, leaving $chosen" }
        clickLabel("Performance")
        clickLabel("This week")
        advance(800)
        check(category == "Performance" && sort == "This week") { "the category and sort should follow the clicks" }
        moveTo(single("Outline"))
        advance(400)
        capture("chosen_and_hovered")
    }
}

private val SampleAutoexec = """
    // Written by Hideout. Lines you add by hand are kept.
    fps_max 240
    fps_max_ui 144
    engine_no_focus_sleep 0
    snd_musicvolume 0
    // cl_showfps 1
    sensitivity 1.6

    // === AUTOEXEC COMMANDS (Mod Manager) ===
    cl_showfps 0
    r_fullscreen_gamma 2.2
    volume 0.4
    snd_musicvolume 0.2
    // === END COMMANDS ===
""".trimIndent() + "\n"

private val SampleLocks = listOf(LockedRun(0..0), LockedRun(8..13, "Managed by Grimoire"))

private fun highlightCommand(line: String): List<CodeSpan> {
    val start = line.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return emptyList()
    if (line.startsWith("//", start)) return listOf(CodeSpan(start until line.length, CodeRole.Comment))
    val end = line.indexOf(' ', start).takeIf { it > 0 } ?: line.length
    return listOf(CodeSpan(start until end, CodeRole.Command)) + if (end < line.length) listOf(CodeSpan(end + 1 until line.length, CodeRole.Value)) else emptyList()
}

private fun checkCodeEditor(outDir: File) {
    val state = TextFieldState(SampleAutoexec)
    val escapes = WindowEscapes()
    gallery(outDir, "code_editor", 760, 470, escapes, content = {
        Card(Modifier.width(720.dp)) {
            CodeEditor(state, SampleLocks, ::highlightCommand, "autoexec.cfg", Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, end = 4.dp, bottom = 4.dp))
        }
    }) {
        capture("rest")
        val editor = single("autoexec.cfg")
        check(editor.boundsInRoot.height == (24 + 22 * 15) * scale) { "fifteen 22dp lines and 12dp above and below should make the editor 354dp tall, not ${editor.boundsInRoot.height / scale}" }
        fun lineY(line: Int) = 12f + 22f * line + 11f
        fun text() = state.text.toString()
        val right = editor.boundsInRoot.width / scale - 30

        clickAtDp(editor, right, lineY(1))
        type("0")
        check(text().contains("\nfps_max 2400\n")) { "typing at the end of a free line should edit it" }

        clickAtDp(editor, 120f, lineY(9))
        val before = text()
        type("x")
        key(Key.Backspace)
        check(text() == before) { "a locked line should refuse typing and deleting" }

        clickAtDp(editor, 60f, lineY(0))
        type("x")
        check(text() == before) { "the unlabelled header should refuse typing too" }

        clickAtDp(editor, right, lineY(13))
        key(Key.Enter)
        type("bind")
        check(text().endsWith("// === END COMMANDS ===\nbind\n")) { "Enter at the end of a locked run should open a line after it" }

        clickAtDp(editor, right, lineY(6))
        key(Key.Enter)
        type("volume 0.5")
        check(text().contains("sensitivity 1.6\nvolume 0.5\n\n// === AUTOEXEC COMMANDS (Mod Manager) ===")) { "a line added above a locked run should go in" }
        val shifted = text()
        clickAtDp(editor, 120f, lineY(10))
        type("x")
        check(text() == shifted) { "after a line is added above it, the run should still be locked where it moved to" }
        capture("edited")

        clickAtDp(editor, 4f, lineY(15))
        key(Key.Backspace)
        check(text() == shifted) { "Backspace at the start of the line after a run should not join it onto the run" }

        key(Key.Escape)
        check(!single("autoexec.cfg").isFocused()) { "Esc should leave the editor" }
        check(escapes.count == 0) { "Esc in the editor should not reach the window, where it goes back" }
        capture("left")
    }
}

private val SampleCrosshairs = listOf("Mint cross", "Pinpoint", "Plus", "Open frame", "Highlighter", "Ghost", "Game default", "Long reach").map { name ->
    name to if (name == "Game default") Crosshair.GameDefault else CrosshairsFixture.community.single { it.name == name }.crosshair
}

private fun checkCrosshairArt(outDir: File) {
    gallery(outDir, "crosshair_art", 760, 820, content = {
        SampleCrosshairs.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (name, crosshair) ->
                    Column(Modifier.width(170.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        CrosshairTile(crosshair, CrosshairBackdrop.Dark, 3f, RoundedCornerShape(14.dp), Modifier.fillMaxWidth().aspectRatio(16f / 10f))
                        Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CrosshairBackdrop.Choices.forEach { backdrop ->
                CrosshairTile(SampleCrosshairs.first().second, backdrop, 5f, RoundedCornerShape(18.dp), Modifier.width(170.dp).aspectRatio(16f / 10f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CrosshairPreset.entries.forEach { preset ->
                CrosshairTile(preset.crosshair, CrosshairBackdrop.Card, 2.4f, RoundedCornerShape(13.dp), Modifier.width(120.dp).height(76.dp))
            }
            CrosshairTile(Crosshair.GameDefault, CrosshairBackdrop.Grey, 1f, RoundedCornerShape(13.dp), Modifier.width(120.dp).height(76.dp))
        }
    }) {
        advance(400)
        capture("tiles")
    }
}
