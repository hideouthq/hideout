package gg.hideouthq.tools

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.crosshair.Adjustable
import gg.hideouthq.core.crosshair.Crosshair
import gg.hideouthq.core.crosshair.CrosshairColor
import gg.hideouthq.core.crosshair.HeroSettings
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.HeroSettingsCopy
import gg.hideouthq.core.data.fake.FakeCommunityCrosshairs
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryHeroSettings
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.tools.preview.settle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

private const val Settle = 1_200L

fun main(args: Array<String>) {
    val outDir = File(args.firstOrNull() ?: "build/screenshots/crosshairs-checks")
    val files = sampleGameFiles(CrosshairsFixture.config)
    val settings = CrosshairsFixture.settings()
    val state = HideoutAppState(CrosshairsFixture.container(files, settings = settings))
    fun file() = checkNotNull(files.bytes(SampleGameRoot, InMemoryGameFiles.Autoexec)) { "the sample game has an autoexec.cfg" }.decodeToString()
    val mint = CrosshairsFixture.community.first { it.name == "Mint cross" }.crosshair

    runCheck(outDir, "crosshairs", state, heightDp = 1700) {
        choose("Crosshairs")
        check(state.navigator.current.route == Route.Crosshairs()) { "the sidebar should open Crosshairs on Browse, not ${state.navigator.current.route}" }
        check(selected("Mint cross")) { "Browse should pick the most popular crosshair first" }
        capture("browse")

        clickLabel("Use in game")
        settle("Use in game to reach the game's saved settings", 10_000) { settings.saved() == mint && settings.saved(HeroSettingsCopy.Game) == mint }
        check(file() == DesignAutoexec) { "autoexec.cfg should be left as it is" }
        check(hasText("In use · applies next time Deadlock starts")) { "the panel should say the crosshair is in use" }
        check(hasText("Mint cross") && tabCount() == "5") { "Mint cross should join Yours beside Mine, not leave ${tabCount()}" }
        capture("used")

        clickLabel("Plus")
        clickLabel("Save to yours")
        check(shows("Saved") && tabCount() == "6") { "Save to yours should add Plus to Yours" }
        capture("saved")

        choose("Edit")
        check(selected("Yours")) { "the In use pill should open Yours" }
        check(fieldText("Crosshair name") == "Mint cross") { "Yours should open on the crosshair in use" }
        moveTo(slider("Gap"))
        click()
        advance(Settle)
        val edited = Adjustable.Gap.set(mint, Adjustable.Gap.range.let { (it.start + it.endInclusive) / 2 })
        settle("an edit of the crosshair in use to save on its own", 10_000) { settings.saved() == edited }
        check(hasText("Saved · applies next time Deadlock starts")) { "the editor should say its edits are saved" }
        capture("edited")

        check(!switch("Collapse when still").config.contains(SemanticsProperties.Disabled)) { "with Mint cross's fixed gap, Collapse when still should be on offer" }
        clickLabel("Fixed gap")
        check(switch("Collapse when still").config.contains(SemanticsProperties.Disabled)) { "Collapse when still should wait for Fixed gap, as the game greys it out" }
        clickLabel("Fixed gap")
        clickLabel("Collapse when still")
        moveTo(slider("Dot size"))
        click()
        advance(Settle)
        clickInto("Border hex color")
        key(Key.A, ctrl = true)
        type("FF0000")
        advance(Settle)
        settle("the whole reticle to save on its own", 10_000) { settings.saved()?.let { it.fixedGap && it.collapse && it.borderColor == CrosshairColor(255, 0, 0) && it.dot.size != mint.dot.size } == true }
        val whole = settings.saved()
        check(whole == settings.saved(HeroSettingsCopy.Game)) { "both copies should hold it" }
        capture("whole_reticle")

        moreFor("Classic")
        choose("Duplicate")
        check(fieldText("Crosshair name") == "Classic copy") { "Duplicate should pick the copy" }
        moreFor("Classic copy")
        choose("Delete")
        check(!shows("Classic copy")) { "Delete should take the copy out of Yours" }
        moreFor("Mint cross")
        check(!shows("Delete")) { "the crosshair in use should offer no Delete" }
        key(Key.Escape)
        advance(Settle)
        moreFor("Classic")
        choose("Rename")
        type("s")
        key(Key.Enter)
        advance(Settle)
        check(selected("Classics")) { "Rename should put the cursor at the end of the name field" }
        capture("menu")

        choose("Import")
        key(Key.Escape)
        advance(Settle)
        check(!shows("Import a crosshair")) { "Esc should close Import" }
        choose("Import")
        type("nonsense")
        key(Key.Enter)
        advance(Settle)
        check(hasText("Hideout can’t find a crosshair in that.")) { "Import should say when it reads nothing" }
        repeat(8) { key(Key.Backspace) }
        type("citadel_crosshair_color_r 255; citadel_crosshair_pip_gap 3")
        key(Key.Enter)
        advance(Settle)
        check(fieldText("Crosshair name") == "Imported crosshair") { "Import should add the crosshair to Yours and pick it" }
        check(settings.saved() == whole) { "an import should leave the crosshair in use alone" }
        capture("imported")
    }
    checkScrolling(outDir)
    checkNarrowWindow(outDir)
    checkLongName(outDir)
    checkRefusedSave(outDir)
    checkYoursUnsaved(outDir)
    checkNoGame(outDir)
    checkNotSavedYet(outDir)
    checkYoursUnreadable(outDir)
    checkNoMatch(outDir)
    checkHeldClipboard(outDir)
    checkEmptyYours(outDir)
    println("Crosshairs checks passed.")
}

private fun checkScrolling(outDir: File) {
    fun many() = HideoutAppState(CrosshairsFixture.container(source = FakeCommunityCrosshairs(CrosshairsFixture.many)))
    runCheck(outDir, "crosshairs_scrolling", many()) {
        choose("Crosshairs")
        val search = field("Search crosshairs or creators").boundsInRoot
        val use = bounds("Use in game")
        val yours = nodes().filter { it.hasLabel("Yours") }.maxBy { it.boundsInRoot.left }.boundsInRoot
        check(!shows("Quiet 5")) { "the last crosshair should not be built before it is near" }
        moveToDp(600f, 700f)
        repeat(400) { scroll(1f) }
        advance(Settle)
        check(shows("Quiet 5")) { "scrolling to the end should bring in the last of the sixty crosshairs" }
        check(field("Search crosshairs or creators").boundsInRoot == search) { "the search should stay put while the grid scrolls" }
        check(bounds("Use in game") == use) { "the panel should stay put while the grid scrolls" }
        check(nodes().filter { it.hasLabel("Yours") }.maxBy { it.boundsInRoot.left }.boundsInRoot == yours) { "the tabs should stay put while the grid scrolls" }
        capture("end")
        val last = nodes().first { it.hasLabel("Quiet 5") }.boundsInRoot
        clickLabel("Autoexec")
        advance(Settle)
        key(Key.DirectionLeft, alt = true)
        advance(Settle)
        check(nodes().firstOrNull { it.hasLabel("Quiet 5") }?.boundsInRoot == last) { "going back to Crosshairs should find the grid where it was left" }
        capture("back")
    }
    runCheck(outDir, "crosshairs_no_match_typing", many()) {
        choose("Crosshairs")
        clickInto("Search crosshairs or creators")
        type("zzzz")
        advance(Settle)
        check(shows("No crosshairs match.")) { "a search for zzzz should match nothing" }
        type("q")
        advance(Settle)
        check(fieldText("Search crosshairs or creators") == "zzzzq") { "the search should keep the keyboard once nothing matches, not hold ${fieldText("Search crosshairs or creators")}" }
        capture("typed")
    }
    runCheck(outDir, "crosshairs_scrolling_narrow", many(), widthDp = 1000, heightDp = 680) {
        choose("Crosshairs")
        moveToDp(500f, 500f)
        repeat(150) { scroll(1f) }
        advance(Settle)
        check(nodes().none { it.hasLabel("Use in game") && it.boundsInRoot.bottom in 0f..680f }) { "scrolled down, the panel above the card should be out of view" }
        val tile = nodes().first { it.config.contains(SemanticsProperties.Selected) && it.boundsInRoot.width > 150f && it.boundsInRoot.top > 340f && it.boundsInRoot.bottom < 680f }
        val name = descendants(tile).firstNotNullOf { node -> node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text }
        moveTo(tile)
        click()
        advance(Settle)
        val use = bounds("Use in game")
        check(use.bottom <= 680f) { "picking a tile should scroll back to the panel, whose Use in game ends at ${use.bottom}" }
        check(nodes().any { it.hasLabel(name) && it.boundsInRoot.bottom < use.top }) { "the panel should show $name, the crosshair picked" }
        capture("picked")
    }
}

private fun Script.bounds(label: String) = nodes().single { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot

private fun checkNarrowWindow(outDir: File) {
    val state = HideoutAppState(CrosshairsFixture.container())
    runCheck(outDir, "crosshairs_narrow", state, widthDp = 1000, heightDp = 2100) {
        choose("Crosshairs")
        listOf("collapsed", "open").forEach { sidebar ->
            if (sidebar == "open") {
                key(Key.B, ctrl = true)
                advance(Settle)
                check(shows("Collapse sidebar (Ctrl+B)")) { "Ctrl+B should open the sidebar" }
            }
            pageTab("Browse")
            val search = field("Search crosshairs or creators").boundsInRoot
            val use = bounds("Use in game")
            check(search.width >= 240f) { "with the sidebar $sidebar the search should keep its room, not shrink to ${search.width}dp" }
            check(use.bottom < search.top) { "with the sidebar $sidebar the panel should go above the crosshairs, not beside or under them" }
            capture("browse_sidebar_$sidebar")
            pageTab("Yours")
            val name = field("Crosshair name").boundsInRoot
            check(name.top > nodes().single { it.hasLabel("Your crosshairs") }.boundsInRoot.bottom) { "with the sidebar $sidebar the editor should go under the preview and the cards" }
            capture("yours_sidebar_$sidebar")
        }
    }
}

private fun checkLongName(outDir: File) {
    val state = HideoutAppState(CrosshairsFixture.container())
    val long = "An extremely long crosshair name, sixty characters in all...".also { check(it.length == 60) }
    runCheck(outDir, "crosshairs_long_name", state) {
        choose("Crosshairs")
        choose("Edit")
        clickInto("Crosshair name")
        key(Key.A, ctrl = true)
        type(long)
        key(Key.Enter)
        advance(Settle)
        val badge = nodes().single { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "$long · in use" } == true }.boundsInRoot
        val zoom = nodes().single { it.hasLabel("Actual size") && it.config.contains(SemanticsProperties.Selected) }.boundsInRoot
        check(badge.width <= 360f && badge.right < zoom.left) { "the preview's badge should stop growing before the zoom control, not be ${badge.width}dp wide" }
        capture("yours")
        pageTab("Browse")
        val pill = nodes().single { it.hasLabel("Edit $long, the crosshair in use") }.boundsInRoot
        val title = nodes().single { it.hasLabel("Crosshairs") && it.config.getOrNull(SemanticsProperties.Text) != null && !it.config.contains(SemanticsActions.OnClick) }.boundsInRoot
        check(pill.width <= 360f) { "the In use pill should stop growing, not be ${pill.width}dp wide" }
        check(pill.top < title.bottom) { "the In use pill should stay beside the title" }
        capture("browse")
    }
}

private fun checkRefusedSave(outDir: File) {
    val settings = CrosshairsFixture.settings()
    val state = HideoutAppState(CrosshairsFixture.container(settings = settings))
    runCheck(outDir, "crosshairs_refused", state) {
        choose("Crosshairs")
        settings.failure = "the disk is full"
        clickLabel("Use in game")
        settle("the refused save to show", 10_000) { shows("Retry") }
        check(!hasText("applies next time Deadlock starts")) { "a refused save should not say it applies next time" }
        capture("refused")
        settings.failure = null
        clickLabel("Retry")
        settle("Retry to save", 10_000) { hasText("In use · applies next time Deadlock starts") }
    }
}

private fun checkYoursUnsaved(outDir: File) {
    val data = FullYoursDisk()
    val state = HideoutAppState(CrosshairsFixture.container(data = data))
    val unsaved = "Hideout couldn’t save your crosshairs."
    runCheck(outDir, "crosshairs_yours_unsaved", state, heightDp = 1700) {
        choose("Crosshairs")
        pageTab("Yours")
        choose("Classic")
        data.full = true
        clickLabel("Cyan")
        settle("the failed save of Yours to show on a crosshair not in use", 10_000) { hasText(unsaved) }
        check(shows("Use in game")) { "a crosshair not in use should still offer Use in game" }
        val use = bounds("Use in game")
        val name = field("Crosshair name").boundsInRoot
        check(use.top < name.bottom && use.bottom > name.top && use.left > name.right) { "Use in game should sit beside the name, where In use goes, not below the sliders" }
        capture("not_in_use")
        choose("Mine")
        check(hasText(unsaved)) { "the crosshair in use should say it too" }
        capture("in_use")
        data.full = false
        clickLabel("Retry")
        settle("Retry to save Yours", 10_000) { !hasText(unsaved) }
    }
}

private class FullYoursDisk : CacheStorage {
    private val memory = MemoryStorage()
    var full = false

    override suspend fun read(name: String): String? = memory.read(name)

    override suspend fun write(name: String, text: String) = if (full) throw IOException("the disk is full") else memory.write(name, text)
}

private fun checkNoGame(outDir: File) {
    val state = HideoutAppState(AppContainer.fake(FakeConfig(game = SampleGame.NotFound), communityCrosshairs = FakeCommunityCrosshairs(CrosshairsFixture.community)))
    runCheck(outDir, "crosshairs_no_game", state) {
        choose("Crosshairs")
        useInGameSays("Deadlock not found", "Hideout can’t find Deadlock. Choose its folder in Settings.")
        capture("tooltip")
    }
}

private fun checkNotSavedYet(outDir: File) {
    val settings = InMemoryHeroSettings(mapOf(HeroSettingsCopy.Game to HeroSettings.New.with(CrosshairsFixture.mine).render()))
    val state = HideoutAppState(CrosshairsFixture.container(settings = settings))
    runCheck(outDir, "crosshairs_not_saved_yet", state) {
        choose("Crosshairs")
        check(!hasText("In use")) { "the game folder's copy, which may be another account's, should not show as in use" }
        useInGameSays("Start Deadlock once first", "Start Deadlock once so it saves its settings, then use a crosshair.")
        capture("tooltip")
        pageTab("Yours")
        check(hasText("Start Deadlock once first")) { "the editor's Use in game says why under it too" }
        capture("yours")
    }
}

private fun checkYoursUnreadable(outDir: File) {
    val unreadable = object : CacheStorage by MemoryStorage() {
        override suspend fun read(name: String): String? = throw IOException("permission denied")
    }
    val state = HideoutAppState(CrosshairsFixture.container(data = unreadable))
    runCheck(outDir, "crosshairs_yours_unreadable", state) {
        choose("Crosshairs")
        useInGameSays("Can’t read your crosshairs", "Hideout couldn’t read your crosshairs. Restart Hideout to try again.")
        capture("tooltip")
    }
}

private fun checkHeldClipboard(outDir: File) {
    val state = HideoutAppState(CrosshairsFixture.container(sampleGameFiles(CrosshairsFixture.config), settings = CrosshairsFixture.settings()))
    val board = HeldBoard()
    val thrown = mutableListOf<Throwable>()
    runScript(
        outDir, "crosshairs_held_clipboard", 1440, 1700, { CompositionLocalProvider(LocalClipboard provides board) { SmokeRoot(state, reducedMotion = false) } },
        scale = 1f, outputDirectory = File(outDir, "crosshairs_held_clipboard"),
        coroutineContext = Dispatchers.Unconfined + CoroutineExceptionHandler { _, failure -> thrown += failure },
        windowKeys = WindowKeys(state.keys::onPreviewKeyEvent, state.keys::onKeyEvent),
    ) {
        choose("Crosshairs")
        choose("Yours")
        choose("Import")
        key(Key.V, ctrl = true)
        runUiQueue(1_000)
        advance(100)
        check(board.reads.get() == 5) { "Ctrl+V in the dialog should try the app's clipboard five times, not ${board.reads.get()}" }
        check(thrown.isEmpty()) { "a held clipboard should throw nothing, not $thrown" }
        type("nonsense")
        key(Key.Enter)
        advance(Settle)
        check(hasText("Hideout can’t find a crosshair in that.")) { "the dialog's field should still type after a paste that found nothing" }
        capture("still_typing")
    }
}

/** Refuses every read the way AWT does while another program has the Windows clipboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class HeldBoard : Clipboard {
    val reads = AtomicInteger()

    override suspend fun getClipEntry(): ClipEntry? {
        reads.incrementAndGet()
        error("cannot open system clipboard")
    }

    override suspend fun setClipEntry(clipEntry: ClipEntry?) = error("cannot open system clipboard")

    override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("held")
}

private fun checkNoMatch(outDir: File) {
    runCheck(outDir, "crosshairs_no_match", nothingKept()) {
        choose("Crosshairs")
        check(hasText("No crosshairs match.")) { "an empty list should say nothing matches" }
        check(!shows("Clear filters")) { "with no search or style to clear, Clear filters should not show" }
        capture("browse")
    }
}

private fun checkEmptyYours(outDir: File) {
    runCheck(outDir, "crosshairs_empty_yours", nothingKept()) {
        choose("Crosshairs")
        pageTab("Yours")
        listOf("Classic", "Dot", "Wide gap").forEach { name ->
            moreFor(name)
            choose("Delete")
        }
        check(shows("Your crosshairs") && shows("New")) { "with every card deleted, Yours should still offer New" }
        check(!shows("Crosshair name")) { "with nothing picked there is nothing to edit" }
        clickLabel("Share")
        check(!shows("Copied")) { "Share with nothing picked should copy nothing" }
        capture("yours_empty")
        choose("New")
        check(fieldText("Crosshair name") == "New crosshair") { "New should make a crosshair and pick it" }
    }
}

private fun nothingKept() = HideoutAppState(AppContainer.fake(FakeConfig(game = SampleGame.NotFound), communityCrosshairs = FakeCommunityCrosshairs(emptyList())))

private fun Script.useInGameSays(short: String, why: String) {
    check(hasText(short)) { "Use in game should say '$short' under it" }
    moveTo(nodes().single { it.hasLabel("Use in game") })
    settle("the disabled Use in game to say why", 3_000) { hasText(why) }
    advance(32)
    check(hasText(why)) { "the tooltip should open beside Use in game and stay, not cover it" }
}

private fun Script.pageTab(label: String) {
    moveTo(nodes().filter { it.hasLabel(label) && it.config.contains(SemanticsProperties.Selected) }.maxBy { it.boundsInRoot.left })
    click()
    advance(Settle)
}

private fun InMemoryHeroSettings.saved(copy: HeroSettingsCopy = InMemoryHeroSettings.Cloud): Crosshair? = file(copy)?.let { HeroSettings.parse(it)?.crosshair }

private fun Script.selected(label: String): Boolean = nodes().any { it.hasLabel(label) && it.config.getOrNull(SemanticsProperties.Selected) == true }

private fun Script.tabCount(): String? = nodes().single { it.hasLabel("Yours") && it.config.contains(SemanticsProperties.Selected) }
    .config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.firstOrNull { it.all(Char::isDigit) }

private fun Script.slider(label: String): SemanticsNode = nodes().single { it.hasLabel(label) && it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }

private fun Script.switch(label: String): SemanticsNode = nodes().single { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }

internal fun Script.choose(label: String) {
    clickLabel(label)
    advance(Settle)
}

private fun Script.moreFor(name: String) {
    moveTo(nodes().single { it.hasLabel(name) && it.config.contains(SemanticsProperties.Selected) })
    advance(300)
    choose("More for $name")
}
