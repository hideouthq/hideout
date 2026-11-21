@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import gg.hideouthq.AppContainer
import gg.hideouthq.AppContent
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.LocalReducedMotion
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.feature.settings.SettingsTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

private class SmokeCheck(val name: String, val run: (File) -> Unit)

fun main(args: Array<String>) {
    val checks = listOf(
        SmokeCheck("navigation", ::checkNavigation),
        SmokeCheck("collections", ::checkCollections),
        SmokeCheck("browse_filters", ::checkBrowseFiltersPreserved),
        SmokeCheck("launch_card", ::checkLaunchCard),
        SmokeCheck("settings_game", ::checkSettingsGame),
        SmokeCheck("installed_pages", ::checkInstalledPages),
        SmokeCheck("narrow_overlay", ::checkNarrowOverlay),
        SmokeCheck("scene_thread", ::checkSceneThread),
        SmokeCheck("install_dialog_keys", ::checkInstallDialogKeys),
        SmokeCheck("install_dialog_components", ::checkInstallDialogComponents),
        SmokeCheck("install_dialog_pick_one", ::checkInstallDialogPickOne),
        SmokeCheck("install_dialog_many_parts", ::checkInstallDialogManyParts),
        SmokeCheck("install_dialog_versions", ::checkInstallDialogVersions),
        SmokeCheck("install_dialog_readme", ::checkInstallDialogReadme),
        SmokeCheck("install_dialog_preview", ::checkInstallDialogPreview),
        SmokeCheck("install_dialog_swap", ::checkInstallSkinSwap),
        SmokeCheck("install_dialog_focused_enter", ::checkInstallDialogFocusedEnter),
        SmokeCheck("install_dialog_compact", ::checkInstallDialogCompact),
        SmokeCheck("install_motion", ::checkInstallMotion),
        SmokeCheck("install_motion_reduced", ::checkInstallMotionReduced),
        SmokeCheck("install_motion_centre", ::checkInstallMotionCentre),
        SmokeCheck("installed_switch", ::checkInstalledSwitch),
        SmokeCheck("installed_menu", ::checkInstalledMenu),
        SmokeCheck("installed_uninstall", ::checkInstalledUninstall),
        SmokeCheck("installed_compact", ::checkInstalledCompact),
        SmokeCheck("settings_tabs", ::checkSettingsTabs),
        SmokeCheck("settings_search", ::checkSettingsSearch),
        SmokeCheck("settings_missing", ::checkSettingsMissingGame),
        SmokeCheck("settings_restore", ::checkSettingsRestore),
        SmokeCheck("settings_licences", ::checkSettingsLicences),
        SmokeCheck("settings_bug_report", ::checkSettingsBugReport),
        SmokeCheck("settings_clear", ::checkSettingsClear),
        SmokeCheck("settings_clear_stopped", ::checkSettingsClearStopped),
        SmokeCheck("settings_backups", ::checkSettingsBackups),
        SmokeCheck("settings_backups_refused", ::checkSettingsBackupsRefused),
        SmokeCheck("settings_motion", ::checkSettingsTabMotion),
    )
    val selected = selectTargets(args.getOrNull(1), checks.map { it.name })
    val outDir = File(args.firstOrNull() ?: "build/screenshots/smoke")
    checks.filter { it.name in selected }.forEach { smokeCheck ->
        smokeCheck.run(outDir)
        println("Smoke check '${smokeCheck.name}' passed")
    }
    println("All ${selected.size} smoke checks passed.")
}

@Composable
internal fun SmokeRoot(state: HideoutAppState, reducedMotion: Boolean) {
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalWindowChrome provides RenderedWindowChrome) {
        HideoutTheme {
            Box(Modifier.fillMaxSize()) {
                AppContent(state)
            }
        }
    }
}

internal fun runCheck(
    outDir: File, name: String, state: HideoutAppState,
    widthDp: Int = 1440, heightDp: Int = 900, reducedMotion: Boolean = false,
    coroutineContext: CoroutineContext = Dispatchers.Unconfined,
    script: Script.() -> Unit,
) = runScript(
    outDir, name, widthDp, heightDp, { SmokeRoot(state, reducedMotion) },
    scale = 1f, outputDirectory = File(outDir, name), captureBeforeCoordinates = true, coroutineContext = coroutineContext,
    windowKeys = WindowKeys(state.keys::onPreviewKeyEvent, state.keys::onKeyEvent), script = script,
)

internal fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

internal fun Script.nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }

internal fun SemanticsNode.hasLabel(label: String): Boolean =
    config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == label } == true ||
        config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.contains(label) == true

internal fun Script.shows(label: String): Boolean = nodes().any { it.hasLabel(label) }

internal fun Script.clickLabel(label: String) {
    val matches = nodes().filter { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }
    check(matches.size == 1) { "Expected one clickable '$label', found ${matches.size}" }
    moveTo(matches.single())
    click()
    advance(400)
}

internal fun Script.checkSelected(label: String, selected: Boolean) {
    val matches = nodes().filter { it.hasLabel(label) && it.config.contains(SemanticsProperties.Selected) }
    check(matches.size == 1) { "Expected one selectable '$label', found ${matches.size}" }
    check(matches.single().config[SemanticsProperties.Selected] == selected) { "'$label' selection should be $selected" }
}

internal fun Script.checkTicked(label: String, ticked: Boolean) {
    val matches = nodes().filter { it.hasLabel(label) && it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Checkbox }
    check(matches.size == 1) { "Expected one checkbox '$label', found ${matches.size}" }
    val state = matches.single().config.getOrElseNullable(SemanticsProperties.ToggleableState) { null }
    check(state == if (ticked) ToggleableState.On else ToggleableState.Off) { "'$label' should be ${if (ticked) "ticked" else "unticked"}, is $state" }
}

private fun checkNavigation(outDir: File) {
    val state = HideoutAppState(AppContainer.fake())
    runCheck(outDir, "navigation", state) {
        check(state.navigator.current.route == Route.Discover) { "Should start at Discover" }
        clickLabel("Browse")
        check(state.navigator.current.route == Route.Browse()) { "Browse sidebar navigation" }
        val browseEntryId = state.navigator.current.id
        clickLabel("Library")
        check(state.navigator.current.route == Route.Library) { "Installed sidebar navigation" }
        check(state.navigator.backStack.size == 3) { "History should contain Discover, Browse and Library" }
        capture("after_forward_nav")

        key(Key.DirectionLeft, alt = true)
        advance(400)
        check(state.navigator.current.route == Route.Browse()) { "Alt+Left should go back" }
        check(state.navigator.current.id == browseEntryId && state.navigator.canGoForward) { "Back should retain the entry and forward history" }
        key(Key.DirectionRight, alt = true)
        advance(400)
        check(state.navigator.current.route == Route.Library) { "Alt+Right should go forward" }
        moveToDp(BackX, TopBarY); click(); advance(400)
        check(state.navigator.current.route == Route.Browse()) { "Top-bar back should return to Browse" }
        capture("after_back_button")
    }
}

private fun checkBrowseFiltersPreserved(outDir: File) {
    val state = HideoutAppState(AppContainer.fake(), Route.Browse(category = ModCategory.Skins))
    runCheck(outDir, "browse_filters", state) {
        checkSelected("Skins", true)
        clickLabel("HUD")
        clickLabel("Newest")
        checkSelected("HUD", true)
        checkSelected("Skins", false)
        checkSelected("Newest", true)
        key(Key.K, ctrl = true)
        type("HUD")
        advance(400)
        check(nodes().any { it.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == "HUD" }) {
            "Search should contain the typed query"
        }
        capture("filtered_before_detail")
        val browseEntryId = state.navigator.current.id

        clickLabel("HUDlock 2.0")
        check(state.navigator.current.route == Route.ModDetail("hudlock")) { "Filtered card should open HUDlock detail" }
        capture("detail")
        key(Key.DirectionLeft, alt = true)
        advance(600)
        check(state.navigator.current.id == browseEntryId) { "Back should restore the Browse entry" }
        checkSelected("All", true)
        checkSelected("HUD", false)
        checkSelected("Newest", true)
        checkSelected("Trending", false)
        check(nodes().any { it.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text == "HUD" }) {
            "Back should restore the search query"
        }
        check(nodes().any { it.hasLabel("HUDlock 2.0") }) { "HUD results should be restored" }
        capture("after_detail_back")
        key(Key.K, ctrl = true)
        key(Key.A, ctrl = true)
        key(Key.Backspace)
        advance(400)
        checkSelected("HUD", true)
        checkSelected("All", false)
        capture("search_cleared")
    }
}

internal fun Script.hasText(fragment: String): Boolean =
    nodes().any { node -> node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { fragment in it.text } == true }

internal fun Script.field(label: String): SemanticsNode = nodes().single { it.hasLabel(label) && it.config.contains(SemanticsProperties.EditableText) }

internal fun Script.fieldText(label: String): String = field(label).config[SemanticsProperties.EditableText].text

internal fun Script.clickInto(label: String) {
    moveTo(field(label))
    click()
    advance(300)
}

private fun checkLaunchCard(outDir: File) {
    val launcher = FakeGameLauncher()
    val launches = launcher.launches
    val found = HideoutAppState(AppContainer.fake(launcher = launcher))
    runCheck(outDir, "launch_card", found) {
        check(hasText("3 mods on")) { "The launch card should count the mods on" }
        clickLabel("Play modded")
        clickLabel("Play vanilla")
        check(launches.size == 2) { "Play should launch once the game is checked, got $launches" }
        capture("found")
    }
    val missing = HideoutAppState(AppContainer.fake(FakeConfig(game = SampleGame.NotFound), launcher = launcher))
    runCheck(outDir, "launch_card_missing", missing) {
        check(nodes().any { it.hasLabel("Game not found") }) { "The launch card should say the game is not found" }
        listOf("Play modded", "Play vanilla").forEach { label ->
            val button = nodes().single { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }
            check(button.config.contains(SemanticsProperties.Disabled)) { "'$label' should be disabled without the game" }
            moveTo(button)
            click()
            advance(400)
        }
        check(launches.size == 2) { "Disabled Play buttons should not launch, got $launches" }
        capture("missing")
    }
}

private fun checkSettingsGame(outDir: File) {
    val picks = ArrayDeque(listOf("""D:\SteamLibrary""", """D:\Games\Deadlock\game"""))
    val container = AppContainer.fake(FakeConfig(game = SampleGame.NotFound), folderPicker = { _, _ -> picks.removeFirst() })
    val state = HideoutAppState(container, Route.Settings)
    runCheck(outDir, "settings_game", state) {
        advance(600)
        check(nodes().any { it.hasLabel("Deadlock not found") }) { "The card should say the game is not found" }
        check(nodes().any { it.hasLabel("Needs attention") }) { "The Game tab should carry its dot" }
        check(nodes().any { it.hasLabel("Game not found") }) { "The launch card should agree" }
        capture("not_found")

        clickLabel("Choose folder")
        advance(400)
        check(hasText("Deadlock isn’t in this folder.")) { "Choosing a folder without Deadlock should say so" }
        check(nodes().any { it.hasLabel("""D:\SteamLibrary""") }) { "The rejected folder should be shown" }
        capture("wrong_folder")

        clickLabel("Choose folder")
        advance(400)
        check(picks.isEmpty()) { "Both picks should have been used" }
        check(nodes().any { it.hasLabel("Ready") }) { "The chosen Deadlock folder should be ready" }
        check(nodes().any { it.hasLabel("You chose this folder.") }) { "The card should say the folder was chosen" }
        check(nodes().any { it.hasLabel("""D:\Games\Deadlock""") }) { "The chosen folder should be shown" }
        check(!hasText("Deadlock isn’t in this folder.")) { "The old message should be gone" }
        check(nodes().none { it.hasLabel("Needs attention") }) { "The Game tab dot should be gone" }
        check(hasText("3 mods on")) { "The launch card should count mods once the game is found" }
        capture("found")

        clickLabel("Discover")
        check(state.navigator.current.route == Route.Discover) { "Discover should open" }
        key(Key.Comma, ctrl = true)
        advance(600)
        check(state.navigator.current.route == Route.Settings) { "Ctrl+, should open Settings" }
        check(state.settingsTab == SettingsTab.Game) { "Settings should reopen on the last tab" }
        capture("ctrl_comma")
    }
}

private fun listedContainer(): AppContainer {
    val base = AppContainer.fake()
    val listed = mapOf("lilith" to "mod-551201", "faun-celeste" to "mod-551203")
    val slugs = listed.entries.associate { (slug, id) -> id to slug }
    val catalog = object : CatalogRepository by base.catalog {
        override val mods = base.catalog.mods.map { mods -> mods.map { if (it.id in listed) it.copy(id = listed.getValue(it.id)) else it } }
        override fun detail(modId: String) = base.catalog.detail(slugs[modId] ?: modId)
    }
    return AppContainer(
        catalog = catalog, launcher = base.launcher, soundPlayer = base.soundPlayer, images = base.images,
        previews = base.previews, modLibrary = base.modLibrary, preferences = base.preferences, folderPicker = base.folderPicker,
        folderOpener = base.folderOpener, about = base.about, migration = base.migration, autoexec = base.autoexec, crosshairs = base.crosshairs, communityCrosshairs = base.communityCrosshairs,
    )
}

private fun Script.installButton(label: String): SemanticsNode {
    val counted = Regex("""^$label \d+ (file|files|part|parts)$""")
    val matches = nodes().filter { node ->
        node.config.contains(SemanticsActions.OnClick) && node.config.getOrElseNullable(SemanticsProperties.Text) { null }
            .orEmpty().any { it.text == label || it.text.startsWith("$label ·") || counted.matches(it.text) }
    }
    check(matches.size == 1) { "Expected one '$label' button, found ${matches.size}" }
    return matches.single()
}

private fun checkInstalledPages(outDir: File) {
    val state = HideoutAppState(listedContainer(), Route.Library)
    val library = state.container.modLibrary
    fun status(modId: String) = runBlocking { withTimeout(5_000) { library.status(modId).first() } }
    runCheck(outDir, "installed_pages", state) {
        advance(600)
        val manifest = library.state.value.library
        check(manifest is LibraryStatus.Ready && manifest.records.size == 4) { "Installed should read the sample manifest, got $manifest" }
        check(nodes().none { it.hasLabel("1 of 4") }) { "Details should not repeat the row's place in the load order" }
        check(nodes().any { it.hasLabel("pak01_000.vpk") }) { "Details should list the set's real files" }
        capture("installed")
        openRowMenu("Faun Celeste")
        key(Key.Escape)
        advance(400)
        check(hasText("Not in the game while it’s off")) { "An off set's details should say its files are out of the game" }
        val off = manifest.records.single { it.title == "Faun Celeste" }.files.map { it.name }
        check(nodes().none { node -> off.any { node.hasLabel(it) } }) { "An off set's details should not list files as in the game" }
        capture("installed_off")
        openRowMenu("HUDlock 2.0")
        key(Key.Escape)
        advance(400)
        check(hasText("is missing")) { "The damaged sample set should say a file is missing" }
        check(nodes().any { it.hasLabel("Reinstall") && it.config.contains(SemanticsActions.OnClick) }) { "A missing file should offer Reinstall" }
        check(!shows("Keep my changes")) { "A missing file has no changes to keep" }
        capture("installed_needs_repair")
        openRowMenu("Lilith, the Hell Warden")
        key(Key.Escape)
        advance(400)

        clickLabel("View page")
        check(state.navigator.current.route == Route.ModDetail("mod-551201")) { "View page should open the record's submission, got ${state.navigator.current.route}" }
        check(status("mod-551201") == InstallStatus.Installed(enabled = true)) { "The page should say what the manifest says" }
        check(!installButton("Installed").config.contains(SemanticsProperties.Disabled)) { "Installed should open the actions menu" }
        clickNode(installButton("Installed"))
        check(hasText("Show in Library") && hasText("Show files in folder") && hasText("Uninstall"))
        check(status("mod-551201") == InstallStatus.Installed(enabled = true)) { "Opening the menu must not change installed state" }
        check(library.state.value.library == manifest) { "Opening the menu must leave the manifest alone" }
        capture("page_installed")
    }
}

private fun checkNarrowOverlay(outDir: File) {
    val state = HideoutAppState(AppContainer.fake())
    runCheck(outDir, "narrow_overlay", state, widthDp = 1100, heightDp = 800) {
        clickLabel("Browse")
        check(state.navigator.current.route == Route.Browse()) { "Should reach Browse" }
        clickLabel("Filters")
        check(state.sidePanelOverlay.isOpen) { "Filters should open the narrow overlay" }
        capture("opened")
        moveToDp(ScrimX, ScrimY); click(); advance(400)
        check(!state.sidePanelOverlay.isOpen) { "Scrim should close the overlay" }
        capture("scrim_closed")
        clickLabel("Filters")
        check(state.sidePanelOverlay.isOpen) { "Overlay should reopen" }
        key(Key.Escape); advance(400)
        check(!state.sidePanelOverlay.isOpen) { "Escape should close the overlay" }
        check(state.navigator.current.route == Route.Browse()) { "Escape should close the panel before navigating" }
        capture("escape_closed_panel")
        key(Key.Escape); advance(400)
        check(state.navigator.current.route == Route.Discover) { "Escape with no overlay should go back" }
        capture("escape_back")
        clickLabel("Browse")
        clickLabel("Filters")
        check(state.sidePanelOverlay.isOpen) { "Overlay should reopen before dragging" }
        moveToDp(PanelX, PanelY)
        dragByDp(DragDistance, 0f, steps = 8)
        capture("dragging")
        release(); advance(400)
        check(!state.sidePanelOverlay.isOpen) { "Drag release should dismiss the overlay" }
        capture("drag_dismissed")
    }
}

/**
 * Compose for desktop defers part of its layout bookkeeping to the AWT event thread, about a frame after a layout pass
 * moves a rect, and fires layout rect callbacks from it. A placement slower than that, in a script driven from any other
 * thread, has that bookkeeping run beside the script's own layout.
 */
private fun checkSceneThread(outDir: File) {
    val callbackThreads = ConcurrentHashMap.newKeySet<Thread>()
    var shift by mutableIntStateOf(0)
    runScript(outDir, "scene_thread", 120, 40, {
        Layout({ Box(Modifier.size(10.dp).onLayoutRectChanged(throttleMillis = 0, debounceMillis = 0) { callbackThreads += Thread.currentThread() }) }) { measurables, constraints ->
            val child = measurables.single().measure(constraints)
            layout(constraints.maxWidth, child.height) {
                child.place(shift, 0)
                if (shift > 0) Thread.sleep(SlowPlacementMillis)
            }
        }
    }, scale = 1f, outputDirectory = File(outDir, "scene_thread")) {
        val scriptThread = Thread.currentThread()
        repeat(3) {
            shift += 4
            advance(16)
        }
        check(callbackThreads == setOf(scriptThread)) {
            "Layout rect callbacks ran on ${callbackThreads.map { it.name }}, not only on the script's thread ${scriptThread.name}"
        }
    }
}

private const val SlowPlacementMillis = 200L

private const val BackX = 286f
private const val TopBarY = 36f
private const val ScrimX = 400f
private const val ScrimY = 400f
private const val PanelX = 900f
private const val PanelY = 520f
private const val DragDistance = 220f
