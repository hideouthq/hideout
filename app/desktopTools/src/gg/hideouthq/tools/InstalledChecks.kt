package gg.hideouthq.tools

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.ChangeStage
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameRoot
import gg.hideouthq.core.library.InstalledSet
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.library.OwnedDownload
import gg.hideouthq.core.library.PakNumber
import gg.hideouthq.core.library.Placement
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.library.SetOrigin
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.library.VersionPosition
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.library.relativePath
import gg.hideouthq.core.navigation.Route
import java.io.File

private const val Settle = 1_200L
private const val Frame = 16L
private const val Lilith = "Lilith, the Hell Warden"
private val MenuItems = listOf("Show files in folder", "Open mod page", "Uninstall")

internal class InstalledFixture(seed: List<ManifestRecord>? = null, indexes: ResourceIndexSource? = null, planted: InMemoryGameFiles.() -> Unit = {}) {
    val files: InMemoryGameFiles = if (seed == null) sampleGameFiles() else InMemoryGameFiles().apply {
        putBytes(SampleGameRoot, InMemoryGameFiles.GameInfo, sampleGameFiles(FakeConfig(seed = LibrarySeed.Empty)).gameInfo(SampleGameRoot)!!)
        seed.forEach { record -> record.files.forEach { putFile(SampleGameRoot, it.relativePath(record), it.sizeBytes, it.sha256) } }
        seed.flatMap { it.sets }.map { it.download!! }.forEach { putArchive(it.path, emptyList(), FileImage(it.sizeBytes, it.sha256)) }
    }.apply(planted)
    val opened = mutableListOf<Pair<GameRoot, SetFolder>>()
    val processes = FakeProcesses()
    private val opener = FolderOpener { root, folder -> opened += root to folder; true }
    val container: AppContainer = if (seed == null) {
        AppContainer.fake(files = files, processes = processes, folderOpener = opener, resourceIndexSource = indexes)
    } else {
        val downloads = seed.flatMap { it.sets }.map { OwnedDownload((it.source as InstalledSource.Remote).identity, it.download!!) }
        AppContainer.fake(
            files = files, processes = processes, folderOpener = opener, resourceIndexSource = indexes,
            libraryStore = InMemoryLibraryStore(LibraryManifest(records = seed, downloads = downloads)),
        )
    }

    fun state(start: Route = Route.Library) = HideoutAppState(container, start)

    fun record(title: String): ManifestRecord? = (container.modLibrary.state.value.library as? LibraryStatus.Ready)?.records?.firstOrNull { it.title == title }

    companion object {
        fun twoFiles(): List<ManifestRecord> {
            fun set(fileId: Long, name: String, variant: String, index: Int, files: List<Pair<VpkPart, Long>>) = InstalledSet(
                source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 551_210, fileId, null)),
                originalName = name, variant = variant,
                origin = SetOrigin.Downloaded(ArchiveIdentity("""C:\Users\Player\Downloads\Hideout\mod-551210\$fileId\$name""", files.sumOf { it.second }, "%064x".format(fileId))),
                version = VersionPosition(index, 3),
                files = files.map { (part, size) -> SetFile(part, FileImage(size, "%064x".format(size + index))) },
                componentKey = "pak01_dir.vpk",
            )
            val lilith = ManifestRecord(
                installId = "lilith", gameRoot = SampleGameRoot, title = "Lilith, the Hell Warden", placement = Placement.On(PakNumber(1)),
                sets = listOf(
                    set(1_510_001, "lilith_hell_warden.zip", "Default", 1, listOf(VpkPart.Directory to 900_000L, VpkPart.Chunk(0) to 30_300_000L)),
                    set(1_510_003, "lilith_werewolf_cabezas.zip", "Werewolf Transform Lilith Cabezas", 3, listOf(VpkPart.Directory to 27_400_000L)),
                ),
            )
            return listOf(lilith) + many().take(1).map { it.placed(Placement.On(PakNumber(3))) }
        }

        fun many(): List<ManifestRecord> = (1..8).map { n ->
            ManifestRecord(
                installId = "many$n", gameRoot = SampleGameRoot, title = "Sample mod $n", placement = Placement.On(PakNumber(n)),
                sets = listOf(
                    InstalledSet(
                        source = InstalledSource.Remote(SubmissionIdentity(SubmissionKind.Mod, 700_000L + n, 800_000L + n, null)),
                        originalName = "many$n.zip", variant = "Default",
                        origin = SetOrigin.Downloaded(ArchiveIdentity("""C:\Users\Player\Downloads\Hideout\many$n.zip""", 2_000_000L * n, "%064x".format(n))),
                        version = VersionPosition(1, 1),
                        files = listOf(SetFile(VpkPart.Directory, FileImage(2_000_000L * n, "%064x".format(100 + n)))),
                        componentKey = "pak01_dir.vpk",
                    ),
                ),
            )
        }
    }
}

private fun enclosing(a: Rect, b: Rect) = Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

private val LilithFolder = SetFolder.Off(SubmissionRef(SubmissionKind.Mod, 551_201))

internal fun Script.labelled(label: String): List<SemanticsNode> = nodes().filter { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }

internal fun Script.clickNode(node: SemanticsNode) {
    val centre = node.boundsInRoot.center
    moveToDp(centre.x / scale, centre.y / scale)
    click()
    advance(400)
}

internal fun Script.rightClickRow(title: String) {
    val row = nodes().first { it.hasLabel(title) }.boundsInRoot
    moveToDp(row.center.x / scale, row.center.y / scale)
    rightClick()
    advance(Settle)
}

internal fun Script.openRowMenu(title: String) {
    clickLabel("More for $title")
    advance(Settle)
}

private fun Script.menuOpen(): Boolean = MenuItems.all { item -> nodes().any { it.hasLabel(item) } }

private fun Script.checkMenu(context: String) {
    check(menuOpen()) { "$context: the menu should show ${MenuItems.joinToString()}" }
    val tops = MenuItems.map { item -> menuItem(item).boundsInRoot.top }
    check(tops == tops.sorted()) { "$context: the items should read ${MenuItems.joinToString()} from the top, got $tops" }
}

internal fun Script.switchOf(title: String): SemanticsNode =
    nodes().single { it.hasLabel("Turn $title on or off") && it.config.contains(SemanticsProperties.ToggleableState) }

private fun SemanticsNode.on(): Boolean = config[SemanticsProperties.ToggleableState] == ToggleableState.On

private const val ExitFrames = 40
private val ExitCaptures = setOf(1, 4, 8, 16)
private const val MinimumExitFrames = 3

internal fun Script.awaitChange(fixture: InstalledFixture, limitMillis: Long = 3_000) {
    var waited = 0L
    while (fixture.container.modLibrary.state.value.changes.values.any { it.stage == ChangeStage.Running }) {
        check(waited < limitMillis) { "The change did not finish within $limitMillis ms" }
        advance(Frame)
        waited += Frame
    }
    advance(Settle)
}

internal fun checkInstalledSwitch(outDir: File) {
    val fixture = InstalledFixture()
    val root = SampleGameRoot
    runCheck(outDir, "installed_switch", fixture.state()) {
        advance(600)
        check(hasText("3 of 4 on in Main · 184 MB")) { "The sample starts with three sets on" }
        check(switchOf(Lilith).on()) { "Lilith starts on" }
        clickNode(switchOf(Lilith))
        awaitChange(fixture)
        check(fixture.record(Lilith)?.enabled == false) { "The manifest should say Lilith is off" }
        check(fixture.files.addonsFiles(root).keys.none { it.startsWith("pak01") }) { "Lilith's files should have left addons" }
        val off = fixture.record(Lilith)?.files?.map { it.name }.orEmpty()
        check(off.size == 2 && off.all { it.startsWith("set-") } && fixture.files.filesIn(root, LilithFolder).keys == off.toSet()) { "Lilith's files should be in its off folder under its set's name, got $off" }
        check("unmanaged_skin_dir.vpk" in fixture.files.addonsFiles(root)) { "The user's own file stays" }
        check(!switchOf(Lilith).on()) { "The switch should show off" }
        check(hasText("2 of 4 on in Main · 98 MB")) { "The summary counts what the game loads" }
        capture("off")
        clickNode(switchOf(Lilith))
        awaitChange(fixture)
        check(fixture.record(Lilith)?.enabled == true && fixture.record(Lilith)?.numbers?.single() == PakNumber(1)) { "Lilith should be on again under 01" }
        check(fixture.files.addonsFiles(root).keys.containsAll(listOf("pak01_dir.vpk", "pak01_000.vpk"))) { "Lilith's files should be back in addons" }
        check(fixture.files.filesIn(root, LilithFolder).isEmpty()) { "The off folder should be empty" }
        check(hasText("3 of 4 on in Main · 184 MB"))
        capture("on")
    }
    val one = InstalledFixture(InstalledFixture.many().take(1))
    runCheck(outDir, "installed_switch_none_on", one.state()) {
        advance(600)
        clickNode(switchOf("Sample mod 1"))
        awaitChange(one)
        check(hasText("0 of 1 on in Main")) { "With every set off the summary has no size" }
    }
}

internal fun checkInstalledMenu(outDir: File) {
    val fixture = InstalledFixture()
    val state = fixture.state()
    runCheck(outDir, "installed_menu", state) {
        advance(600)
        openRowMenu(Lilith)
        checkMenu("⋯")
        check(state.modal.isOpen) { "The window's shortcuts stand aside while the menu is open" }
        capture("menu")
        key(Key.Escape)
        advance(Settle)
        check(!menuOpen() && !state.modal.isOpen) { "Esc closes the menu and gives the window back" }

        rightClickRow("Night of the Werecat")
        checkMenu("right-click")
        check(nodes().none { it.hasLabel("pak01_000.vpk") }) { "A right-click selects the row too, so Details no longer lists Lilith's files" }
        capture("menu_right_click")
        clickLabel("Show files in folder")
        advance(Settle)
        rightClickRow("Faun Celeste")
        clickLabel("Show files in folder")
        advance(Settle)
        check(fixture.opened == listOf(SampleGameRoot to SetFolder.Addons, SampleGameRoot to SetFolder.Off(SubmissionRef(SubmissionKind.Mod, 551_203)))) {
            "Show files should open each set's own folder, got ${fixture.opened}"
        }
        clickLabel("Open folder")
        advance(Settle)
        check(fixture.opened.last() == SampleGameRoot to SetFolder.Off(SubmissionRef(SubmissionKind.Mod, 551_203))) { "Details' Open folder opens the off folder too" }

        openRowMenu(Lilith)
        key(Key.DirectionDown)
        key(Key.DirectionDown)
        key(Key.Enter)
        advance(Settle)
        check(!menuOpen() && !state.modal.isOpen) { "Enter on an item closes the menu" }
        check(state.navigator.current.route == Route.ModDetail("mod-551201")) { "From Change files…, Down twice then Enter should open Lilith's page, got ${state.navigator.current.route}" }
    }
}

internal fun checkInstalledUninstall(outDir: File) {
    val fixture = InstalledFixture()
    val state = fixture.state()
    val archive = """C:\Users\Player\Downloads\Hideout\lilith_hell_warden.zip"""
    runCheck(outDir, "installed_uninstall", state) {
        advance(600)
        val werecat = { nodes().first { it.hasLabel("Night of the Werecat") }.boundsInRoot.top }
        val before = werecat()
        openRowMenu(Lilith)
        val uninstall = menuItem("Uninstall").boundsInRoot.center
        moveToDp(uninstall.x / scale, uninstall.y / scale)
        click()
        val path = mutableListOf<Float>()
        repeat(ExitFrames) { frame ->
            advance(Frame)
            path += werecat()
            if (frame in ExitCaptures) capture("exit_frame_$frame")
        }
        val after = path.last()
        check(after < before) { "The next row should move up into Lilith's place, from $before to $after" }
        val between = path.count { it < before - 1f && it > after + 1f }
        check(between >= MinimumExitFrames) { "Lilith's row should shrink out over several frames, not vanish; the next row went $before -> $path" }
        File(dir, "exit_path.txt").writeText("next row top by frame, ${scale}x: before=$before\n${path.joinToString("\n")}\n")
        check(!state.modal.isOpen) { "Nothing asks first" }
        check(hasText("Uninstalled $Lilith") && hasText("Undo")) { "An Undo is offered instead of a question" }
        capture("undo_offered")
        awaitReal(limitMillis = 15_000, what = "the undo window passing and Lilith's files going") { fixture.record(Lilith) == null }
        awaitChange(fixture)
        check(fixture.files.addonsFiles(SampleGameRoot).keys == setOf("pak02_dir.vpk", "pak02_000.vpk", "unmanaged_skin_dir.vpk")) {
            "Only Lilith's files should have gone, got ${fixture.files.addonsFiles(SampleGameRoot).keys}"
        }
        check(archive in fixture.files.archivePaths()) { "Settings keeps downloads, so the download stays" }
        advance(Settle)
        capture("uninstalled")
    }
}

private fun Script.menuItem(label: String): SemanticsNode =
    scene.semanticsOwners.map { descendants(it.rootSemanticsNode) }.first { owner -> owner.any { it.hasLabel("Show files in folder") } }
        .single { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }

internal fun checkInstalledCompact(outDir: File) {
    val fixture = InstalledFixture(InstalledFixture.many())
    runCheck(outDir, "installed_compact", fixture.state(), widthDp = 1000, heightDp = 680) {
        advance(600)
        val button = nodes().single { it.hasLabel("More for Sample mod 7") }.boundsInRoot
        openRowMenu("Sample mod 7")
        checkMenu("compact")
        val menu = MenuItems.map { item -> menuItem(item).boundsInRoot }.reduce(::enclosing)
        check(menu.bottom <= button.top) { "The menu should open above a low row, menu $menu, button $button" }
        check(menu.top >= 0f && menu.right <= 1000f * scale) { "The menu stays in the window, $menu" }
        capture("menu")
    }
}
