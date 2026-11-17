package gg.hideouthq.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.autoexec.AutoexecDocument
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeConfigFiles
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.library.GameStatus
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.feature.settings.SettingsTab
import java.awt.datatransfer.StringSelection
import java.io.File

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/autoexec")
    val page = AutoexecPage()
    checkPage(out, "autoexec", page) {
        check(page.state.navigator.current.route == Route.Autoexec) { "the sidebar's Autoexec should open the page" }
        check(hasText("sensitivity")) { "sensitivity shows in the list" }
        capture("list")

        view("Text")
        clickLine(1)
        type("0")
        advance(300)
        check(editorText().contains("\nfps_max 2400\n")) { "typing at the end of fps_max should edit it, not leave ${editorText()}" }
        capture("typed")

        view("List")
        check(fieldText("Value for fps_max") == "2400") { "the List should show what was typed in the Text view, not ${fieldText("Value for fps_max")}" }
        waitFor("the edit to be saved") { page.file()?.contains("\nfps_max 2400\n") == true }
        check(page.file() == DesignAutoexec.replace("fps_max 240\n", "fps_max 2400\n")) { "only the edited line should change, not ${page.file()}" }

        clickLabel("Run fps_max")
        advance(600)
        view("Text")
        check(editorText().contains("\n// fps_max 2400\n")) { "turning fps_max off should comment it out in the Text view, not ${editorText()}" }
        capture("off")
        clickLabel("Open in editor")
        waitFor("the editor to open") { page.opened.edited.isNotEmpty() }
        check(page.opened.edited.single()?.contains("\n// fps_max 2400\n") == true) { "Open in editor should save first, not open ${page.opened.edited}" }
        check(page.file()?.startsWith(AutoexecDocument.Header) == true)

        view("List")
        clickInto("Add a command")
        type("volume 0.5")
        key(Key.Enter)
        advance(600)
        check(fieldText("Value for volume") == "0.5") { "Enter in the Add field should add the command" }
        clickInto("Value for volume")
        key(Key.Escape)
        advance(300)
        check(page.state.navigator.current.route == Route.Autoexec) { "Esc in a field should leave the field, not the page" }
        check(nodes().none { it.hasLabel("Value for volume") && it.config.getOrElseNullable(SemanticsProperties.Focused) { null } == true }) { "Esc should take focus out of the field" }
        capture("added")
    }
    checkRowsKeepFocus(out)
    checkFirstSaveKeepsCursor(out)
    checkHeaderStaysFirst(out)
    checkUndoTakesBackTyping(out)
    checkValuesTakeTyping(out)
    checkPastedNewlinesStayOut(out)
    checkLinesBetweenSectionsCanGo(out)
    checkNotOpened(out)
    checkNoGame(out)
    checkLibraryScrolls(out)
    checkNarrowWindow(out)
}

private class AutoexecPage(autoexec: String? = DesignAutoexec) {
    private val config = FakeConfig(autoexec = autoexec)
    private val files = sampleGameFiles(config)
    val opened = FakeConfigFiles(files)
    val state = HideoutAppState(AppContainer.fake(config, files = files, configFiles = opened))

    fun file(): String? {
        val root = (state.container.modLibrary.state.value.game as GameStatus.Ready).root
        return files.bytes(root, InMemoryGameFiles.Autoexec)?.decodeToString()
    }
}

private fun checkPage(out: File, name: String, page: AutoexecPage = AutoexecPage(), widthDp: Int = 1440, heightDp: Int = 1180, script: Script.() -> Unit) =
    runCheck(out, name, page.state, widthDp = widthDp, heightDp = heightDp) {
        openPage()
        script()
    }

private fun checkRowsKeepFocus(out: File) {
    val page = AutoexecPage()
    checkPage(out, "autoexec_rows", page) {
        clickInto("Value for sensitivity")
        key(Key.MoveEnd)
        "25".forEach { char ->
            type(char.toString())
            advance(300)
            check(focused()?.hasLabel("Value for sensitivity") == true) { "the value field should keep focus after typing '$char'" }
        }
        check(fieldText("Value for sensitivity") == "1.625") { "every key should reach the value, not leave ${fieldText("Value for sensitivity")}" }
        clickInto("Value for fps_max")
        key(Key.Tab)
        check(focused()?.hasLabel("Run fps_max") == true) { "Tab should move from the value to the switch" }
        key(Key.Spacebar)
        advance(600)
        check(focused()?.hasLabel("Run fps_max") == true) { "turning the switch off should keep it focused" }
        capture("typed_and_turned")
        waitFor("the edits to be saved") { page.file()?.contains("\n// fps_max 240\n") == true }
        check(page.file() == DesignAutoexec.replace("fps_max 240\n", "// fps_max 240\n").replace("sensitivity 1.6\n", "sensitivity 1.625\n")) { "the file should hold both edits, not ${page.file()}" }
    }
}

private fun checkFirstSaveKeepsCursor(out: File) {
    val page = AutoexecPage(autoexec = null)
    checkPage(out, "autoexec_first_save", page) {
        view("Text")
        clickInto("autoexec.cfg")
        type("fps_max 240")
        advance(300)
        check(editorText() == "fps_max 240") { "each key should land after the one before, not leave ${editorText()}" }
        waitFor("the first save") { page.file() != null }
        advance(300)
        check(editorText() == "${AutoexecDocument.Header}\nfps_max 240") { "the first save should put the header first, not leave ${editorText()}" }
        type("0")
        advance(300)
        check(editorText() == "${AutoexecDocument.Header}\nfps_max 2400") { "typing after the save should go on after what was typed, not leave ${editorText()}" }
        capture("typed_after_save")
    }
}

private fun checkHeaderStaysFirst(out: File) {
    checkPage(out, "autoexec_header") {
        view("Text")
        clickLine(0)
        key(Key.MoveHome, ctrl = true)
        key(Key.Enter)
        advance(900)
        check(editorText().startsWith(AutoexecDocument.Header + "\nfps_max 240\n")) { "nothing should open above the header, not ${editorText()}" }
        clickLine(1)
        type("0")
        advance(300)
        check(editorText().contains("\nfps_max 2400\n")) { "the line under the header should still take typing, not leave ${editorText()}" }
    }
}

private fun checkUndoTakesBackTyping(out: File) {
    checkPage(out, "autoexec_undo") {
        view("Text")
        val read = editorText()
        clickInto("autoexec.cfg")
        key(Key.MoveHome, ctrl = true)
        key(Key.DirectionDown)
        key(Key.MoveEnd)
        type("25")
        advance(300)
        check(editorText() == read.replace("\nfps_max 240\n", "\nfps_max 24025\n")) { "typing at the end of fps_max should edit it, not leave ${editorText()}" }
        key(Key.Z, ctrl = true)
        advance(300)
        check(editorText() == read) { "Ctrl+Z should take back what was typed, not leave ${editorText()}" }
        key(Key.Z, ctrl = true)
        advance(300)
        check(editorText() == read) { "Ctrl+Z should take back nothing before it, not leave ${editorText()}" }
        capture("undone")
    }
}

private fun checkValuesTakeTyping(out: File) {
    val page = AutoexecPage()
    checkPage(out, "autoexec_value_typing", page) {
        clickInto("Value for snd_musicvolume")
        key(Key.A, ctrl = true)
        "hello world".forEachIndexed { at, char ->
            type(char.toString())
            advance(200)
            check(fieldText("Value for snd_musicvolume") == "hello world".take(at + 1)) { "typing '$char' should leave ${"hello world".take(at + 1)}, not ${fieldText("Value for snd_musicvolume")}" }
            if (char == ' ') waitFor("the value before the space to be saved") { page.file()?.contains("\nsnd_musicvolume hello\n") == true }
        }
        waitFor("the value to be saved") { page.file()?.contains("\nsnd_musicvolume hello world\n") == true }
        capture("two_words")

        clickInto("Value for cl_showfps")
        key(Key.A, ctrl = true)
        key(Key.Backspace)
        advance(300)
        check(fieldText("Value for cl_showfps") == "") { "the cleared value should stay empty while it is typed in, not ${fieldText("Value for cl_showfps")}" }
        type("2")
        advance(300)
        check(fieldText("Value for cl_showfps") == "2") { "the new value should be what was typed, not ${fieldText("Value for cl_showfps")}" }
        waitFor("the off line to be saved") { page.file()?.contains("\n// cl_showfps 2\n") == true }
        capture("cleared_and_typed")
    }
}

private fun checkPastedNewlinesStayOut(out: File) {
    val page = AutoexecPage()
    val board = PasteBoard()
    runScript(
        out, "autoexec_paste", 1440, 1180, { CompositionLocalProvider(LocalClipboard provides board) { SmokeRoot(page.state, reducedMotion = false) } },
        scale = 1f, outputDirectory = File(out, "autoexec_paste"), windowKeys = WindowKeys(page.state.keys::onPreviewKeyEvent, page.state.keys::onKeyEvent),
    ) {
        openPage()
        clickInto("Value for snd_musicvolume")
        key(Key.A, ctrl = true)
        board.text = "0.4\r\n"
        key(Key.V, ctrl = true)
        advance(300)
        check(fieldText("Value for snd_musicvolume") == "0.4") { "the pasted line break should stay out of the value, not leave ${fieldText("Value for snd_musicvolume")}" }
        waitFor("the value to be saved") { page.file()?.contains("\nsnd_musicvolume 0.4\n// cl_showfps 1\n") == true }

        clickInto("Add a command")
        board.text = "fps_max 240\n"
        key(Key.V, ctrl = true)
        advance(300)
        check(fieldText("Add a command") == "fps_max 240") { "the pasted line break should stay out of the Add field, not leave ${fieldText("Add a command")}" }
        capture("pasted")
    }
}

private fun checkLinesBetweenSectionsCanGo(out: File) {
    val page = AutoexecPage(autoexec = BetweenSections)
    checkPage(out, "autoexec_between_sections", page) {
        view("Text")
        clickLine(7)
        key(Key.MoveHome)
        key(Key.Backspace)
        advance(300)
        check(editorText() == BetweenSections.removeSuffix("\n").replace("volume 1\n\n", "volume 1\n")) { "Backspace at the start of Grimoire's section should take the blank line above it, not leave ${editorText()}" }
        clickLine(5)
        key(Key.MoveHome)
        key(Key.DirectionDown, shift = true)
        key(Key.Delete)
        advance(300)
        val left = BetweenSections.removeSuffix("\n").replace("volume 1\n\n", "")
        check(editorText() == left) { "deleting the line from its start to Grimoire's section should take it, not leave ${editorText()}" }
        waitFor("the deletions to be saved") { page.file() == "$left\n" }
        capture("deleted")
    }
}

private fun checkNotOpened(out: File) {
    val page = AutoexecPage()
    page.opened.opens = false
    checkPage(out, "autoexec_not_opened", page) {
        clickLabel("Open folder")
        waitFor("the note") { hasText("Couldn’t open this folder in your file manager.") }
        capture("folder_list")
        view("Text")
        clickLabel("Open in editor")
        waitFor("the note") { hasText("Couldn’t open autoexec.cfg in an editor.") }
        capture("editor_text")
        clickLabel("Dismiss")
        waitFor("the note to go") { !hasText("Couldn’t open autoexec.cfg in an editor.") }
        capture("dismissed")
    }
}

private fun checkNoGame(out: File) {
    val state = HideoutAppState(AppContainer.fake(FakeConfig(game = SampleGame.NotFound)), Route.Autoexec)
    state.settingsTab = SettingsTab.About
    runCheck(out, "autoexec_no_game", state) {
        advance(900)
        check(hasText("Deadlock not found")) { "the file card says the game is missing" }
        capture("no_game")
        clickLabel("Choose folder")
        advance(900)
        check(state.navigator.current.route == Route.Settings && state.settingsTab == SettingsTab.Game) { "Choose folder opens Settings › Game, got ${state.navigator.current.route} on ${state.settingsTab}" }
        capture("settings_game")
    }
}

private fun checkLibraryScrolls(out: File) {
    val page = AutoexecPage()
    checkPage(out, "autoexec_library_scrolls", page, heightDp = 900) {
        fun top(label: String) = nodes().single { it.hasLabel(label) }.boundsInRoot.top
        val library = nodes().single { it.hasLabel("Command library") }
        val search = field("Search commands").boundsInRoot
        val row = top("Add citadel_hint_system_disable")
        val file = top("In your autoexec")
        capture("top")
        moveTo(nodes().single { it.hasLabel("Add citadel_hint_system_disable") })
        repeat(6) { scroll(4f) }
        advance(600)
        check(field("Search commands").boundsInRoot == search) { "the search should stay put while the commands scroll" }
        check(top("Add citadel_hint_system_disable") < row - 100f) { "the commands should scroll inside the library, not stay at $row" }
        check(top("In your autoexec") == file) { "scrolling the library should leave the file card where it was" }
        check(nodes().single { it.hasLabel("Command library") }.boundsInRoot.top == library.boundsInRoot.top) { "the library's title should stay put" }
        capture("scrolled")
        val scrolled = top("Add citadel_hint_system_disable")
        clickLabel("Crosshairs")
        advance(900)
        key(Key.DirectionLeft, alt = true)
        advance(900)
        check(page.state.navigator.current.route == Route.Autoexec) { "Alt+Left should come back to Autoexec" }
        check(top("Add citadel_hint_system_disable") == scrolled) { "coming back should find the library where it was left, not at ${top("Add citadel_hint_system_disable")}" }
        capture("back")
    }
}

private fun checkNarrowWindow(out: File) {
    for (width in listOf(1000, 1040)) {
        checkPage(out, "autoexec_narrow_$width", widthDp = width, heightDp = 2000) {
            val file = nodes().single { it.hasLabel("In your autoexec") }.boundsInRoot
            val library = nodes().single { it.hasLabel("Command library") }.boundsInRoot
            check(library.top > file.bottom && library.left < file.right) { "at ${width}dp the library should go under the file, not sit beside it at ${library.left}" }
            capture("stacked")
        }
    }
}

private val BetweenSections = """
    // Written by Hideout. Lines you add by hand are kept.
    fps_max 240
    // === Deadlock Mod Manager - Map Command (DO NOT EDIT) ===
    map street_test
    // === End Map Command ===
    volume 1

    // === AUTOEXEC COMMANDS (Mod Manager) ===
    sensitivity 1.6
    // === END COMMANDS ===
""".trimIndent() + "\n"

@OptIn(ExperimentalComposeUiApi::class)
private class PasteBoard(var text: String = "") : Clipboard {
    override suspend fun getClipEntry(): ClipEntry = ClipEntry(StringSelection(text))

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = Unit

    override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("checks")
}

private const val EditorTop = 12f
private const val EditorLine = 22f

private fun Script.openPage() {
    advance(1_400)
    clickLabel("Autoexec")
    advance(900)
}

private fun Script.view(label: String) {
    clickLabel(label)
    advance(900)
}

private fun Script.clickLine(line: Int) {
    val editor = field("autoexec.cfg").boundsInRoot
    moveToDp(editor.right / scale - 30, editor.top / scale + EditorTop + EditorLine * line + EditorLine / 2)
    click()
    advance(300)
}

private fun Script.editorText(): String = fieldText("autoexec.cfg")

private fun Script.waitFor(what: String, done: () -> Boolean) {
    repeat(50) {
        if (done()) return
        runUiQueue(100)
        advance(16)
    }
    error("timed out waiting for $what")
}
