package gg.hideouthq.tools

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.InstallStatus
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.library.InstalledSource
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ManifestRecord
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.ResourceIndex
import gg.hideouthq.core.library.ResourceIndexSource
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.feature.install.InstallDialogTags
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap

private val ManifestRecord.remote
    get() = (sets.single().source as InstalledSource.Remote).identity

private const val Frame = 16L
private const val Settle = 1_200L
private const val WindowWidth = 1440f
private const val WindowHeight = 900f
private const val DialogWidth = 560f
private const val TabLimit = 60
private const val ShadowReach = 96f
private const val AntialiasingAllowancePx = 2f
private const val PermittedBrightnessIncrease = 6f

private fun Script.tagged(tag: String): SemanticsNode? = nodes().firstOrNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == tag }

internal fun Script.dialogNodes(): List<SemanticsNode> = scene.semanticsOwners.map { descendants(it.rootSemanticsNode) }
    .firstOrNull { owner -> owner.any { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == InstallDialogTags.Surface } }
    .orEmpty()

private fun Script.surface(): Rect? = tagged(InstallDialogTags.Surface)?.boundsInRoot

private fun Script.dialogTitle(): String? = tagged(InstallDialogTags.Content)?.config?.getOrElseNullable(SemanticsProperties.PaneTitle) { null }

private fun Script.outside(label: String): SemanticsNode {
    val dialog = surface()
    val matches = nodes().filter { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) && (dialog == null || !dialog.contains(it.boundsInRoot.center)) }
    check(matches.size == 1) { "Expected one '$label' outside the dialog, found ${matches.size}" }
    return matches.single()
}

private fun Script.clickAt(bounds: Rect) {
    val centre = bounds.center
    moveToDp(centre.x / scale, centre.y / scale)
    click()
}

private fun Script.hover(bounds: Rect) {
    moveToDp(bounds.center.x / scale, bounds.center.y / scale)
    advance(Frame * 2)
}

private fun Script.heroButtons(install: Rect): Rect {
    val dialog = dialogNodes().map { it.id }.toSet()
    val preview = nodes().single { it.id !in dialog && it.hasLabel("Preview in 3D") }.boundsInRoot
    return Rect(install.left, minOf(install.top, preview.top), preview.right, maxOf(install.bottom, preview.bottom)).inflate(AntialiasingAllowancePx)
}

private fun Script.dialogMainSelected(): Boolean =
    dialogNodes().single { it.hasLabel("Main") && it.config.contains(SemanticsProperties.ToggleableState) }
        .config[SemanticsProperties.ToggleableState] == ToggleableState.On

private fun Script.awaitSurface(limitMillis: Long = 1_000): Rect {
    var waited = 0L
    while (waited < limitMillis) {
        surface()?.let { return it }
        advance(Frame)
        waited += Frame
    }
    error("The install dialog did not appear within $limitMillis ms")
}

private fun Script.awaitNoSurface(limitMillis: Long = 2_000) {
    var waited = 0L
    while (surface() != null) {
        check(waited < limitMillis) { "The install dialog did not close within $limitMillis ms" }
        advance(Frame)
        waited += Frame
    }
}

private fun Script.awaitSettledPage(state: HideoutAppState, limitMillis: Long = 3_000) {
    var waited = 0L
    while (state.navigator.settledEntryId != state.navigator.current.id) {
        check(waited < limitMillis) { "The page did not settle within $limitMillis ms" }
        advance(Frame)
        waited += Frame
    }
    advance(Frame * 4)
}

private fun Script.checkPreviewFile(state: HideoutAppState, fileId: Long, fileMd5: String?) {
    val request = state.navigator.previewRequest(state.navigator.current.id)
    check(request?.fileId == fileId && request.fileMd5 == fileMd5) { "The viewer must receive the exact preview file, got $request" }
    check(hasText(OfferedFirstSize)) { "The page must show the size of the file Install would take, expected $OfferedFirstSize" }
}

private const val OfferedFirstSize = "31 MB"

private fun distance(a: Rect, b: Rect) = hypot(a.center.x - b.center.x, a.center.y - b.center.y) + abs(a.width - b.width) + abs(a.height - b.height)

private fun HideoutAppState.stage(modId: String) = container.modLibrary.state.value.operations[modId]?.stage

internal fun checkInstallDialogKeys(outDir: File) {
    val container = InstallDialogFixture.container()
    val state = HideoutAppState(container, Route.Discover)
    val lilith = InstallDialogFixture.Lilith.modId
    runCheck(outDir, "install_dialog_keys", state) {
        advance(600)
        val install = outside("Install").boundsInRoot
        clickAt(install)
        awaitSurface()
        key(Key.Enter)
        check(state.stage(lilith) is OperationStage.ChoosingFile) { "Enter before the dialog has appeared installs nothing" }
        advance(Settle)
        check(state.modal.isOpen) { "The window knows a modal is open" }
        capture("open")

        key(Key.DirectionLeft, alt = true)
        key(Key.Comma, ctrl = true)
        key(Key.K, ctrl = true)
        val page = state.navigator.current
        check(state.navigator.current == page && state.navigator.backStack.size == 1) { "Window shortcuts act on nothing behind the dialog" }
        clickAt(nodes().first { it.hasLabel("Library") && !surface()!!.contains(it.boundsInRoot.center) }.boundsInRoot)
        advance(400)
        check(state.navigator.current == page) { "The sidebar behind the scrim cannot be clicked" }
        check(surface() != null && state.stage(lilith) is OperationStage.ChoosingFile) { "The dialog stays open and waiting" }
        val minimizes = nodes().filter { it.hasLabel("Minimize") }.map { it.boundsInRoot }
        check(minimizes.size == 2 && minimizes.distinct().size == 1) { "The window's controls show again above the scrim, where the page has them: $minimizes" }

        checkTicked("Default", true)
        key(Key.DirectionDown)
        key(Key.DirectionUp)
        key(Key.DirectionUp)
        checkTicked("Default", true)
        checkTicked("Werewolf Transform", false)
        checkTicked("Werewolf Transform Lilith Cabezas", false)
        capture("arrows_leave_ticks")

        val dialog = surface()!!
        val visited = mutableListOf<String>()
        for (step in 1..TabLimit) {
            key(Key.Tab)
            val now = checkNotNull(focused()) { "Tab left nothing focused" }
            check(dialog.contains(now.boundsInRoot.center)) { "Tab moved focus out of the dialog to ${now.boundsInRoot}" }
            visited += now.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.joinToString()
                ?: now.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString { it.text }.orEmpty()
            if (visited.count { it == "Close" } == 2 && now.hasLabel("Cancel")) break
        }
        check(focused()?.hasLabel("Cancel") == true) { "Tab should reach Cancel again after wrapping, visited $visited" }
        File(dir, "tab_order.txt").writeText(visited.joinToString("\n"))
        key(Key.Enter)
        awaitNoSurface()
        advance(Frame * 4)
        check(state.stage(lilith) == null) { "Enter on a focused Cancel cancels" }
        check(container.modLibrary.state.value.library.let { it is LibraryStatus.Ready && it.records.none { record -> record.modId == InstallDialogFixture.Lilith.modId } }) { "Nothing was installed" }
        check(!state.modal.isOpen) { "The window's shortcuts work again once the dialog has gone" }
        check(focused()?.boundsInRoot == install) { "Focus goes back to the Install button the dialog grew from" }
        capture("cancelled_by_focused_cancel")

        clickAt(install)
        awaitSurface()
        advance(Settle)
        key(Key.Escape)
        awaitNoSurface()
        check(state.stage(lilith) == null) { "Esc cancels" }
        check(focused()?.boundsInRoot == install) { "Focus goes back after Esc too" }

        clickAt(install)
        awaitSurface()
        advance(Settle)
        clickAt(dialogNodes().single { it.hasLabel("Werewolf Transform Lilith Cabezas") && it.config.contains(SemanticsProperties.ToggleableState) }.boundsInRoot)
        advance(400)
        checkTicked("Default", true)
        checkTicked("Werewolf Transform Lilith Cabezas", true)
        check(dialogNodes().any { it.confirms("Install 2 files") }) { "The button counts the files" }
        capture("two_ticked")
        key(Key.Enter)
        awaitNoSurface()
        advance(1_000)
        val record = (container.modLibrary.state.value.library as LibraryStatus.Ready).records.single { it.modId == InstallDialogFixture.Lilith.modId }
        check(record.sets.map { it.fileId } == listOf(InstallDialogFixture.Default.id, InstallDialogFixture.Cabezas.id)) { "Enter installs exactly the ticked files, the higher row winning, got ${record.sets}" }
        capture("installed")
    }
}

private fun Script.openComponentQuestion(state: HideoutAppState) {
    advance(600)
    clickAt(outside("Install").boundsInRoot)
    awaitSurface()
    advance(Settle)
    clickAt(dialogNodes().single { it.hasLabel("Default") && it.config.contains(SemanticsProperties.ToggleableState) }.boundsInRoot)
    advance(400)
    clickAt(dialogNodes().single { it.hasLabel("Werewolf Transform") && it.config.contains(SemanticsProperties.ToggleableState) }.boundsInRoot)
    advance(400)
    key(Key.Enter)
    advance(Settle * 3)
    check(state.stage(InstallDialogFixture.Lilith.modId) is OperationStage.ChoosingSet) { "The component question must be open: ${state.stage(InstallDialogFixture.Lilith.modId)}" }
}

internal fun checkInstallDialogComponents(outDir: File) {
    val container = InstallDialogFixture.container(twoSets = true)
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "install_dialog_components", state) {
        openComponentQuestion(state)
        checkTicked("Default", true)
        checkTicked("Werewolf", true)
        clickAt(dialogNodes().single { it.hasLabel("Deselect all") && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot)
        advance(Frame)
        checkTicked("Default", false)
        checkTicked("Werewolf", false)
        clickAt(dialogNodes().single { it.hasLabel("Select all") && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot)
        advance(400)
        check(dialogNodes().none { it.hasLabel("Move Werewolf up") }) { "parts that share no files have no order to set, so no arrows" }
        capture("selected_components")
        clickAt(dialogNodes().single { it.confirms("Install 2 parts") }.boundsInRoot)
        awaitNoSurface()
        advance(1_000)
        val record = (container.modLibrary.state.value.library as LibraryStatus.Ready).records.single { it.modId == InstallDialogFixture.Lilith.modId }
        check(record.sets.map { it.componentKey } == listOf("Lilith/Default/pak01_dir.vpk", "Lilith/Werewolf/pak01_dir.vpk"))
        check(record.sets.map { it.fileId }.distinct() == listOf(InstallDialogFixture.Werewolf.id))
        check(record.files.size == 2 && record.enabled)
    }
}

internal fun checkInstallDialogPickOne(outDir: File) {
    val container = InstallDialogFixture.container(archives = mapOf(InstallDialogFixture.Werewolf to InstallDialogFixture.Colours))
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "install_dialog_pick_one", state) {
        openComponentQuestion(state)
        check(hasText("These change the same files. Only one shows.")) { "the colours are labelled as one pick-one group" }
        checkTicked("Necklace", true)
        checkTicked("Trim", true)
        checkTicked("Black", true)
        listOf("Pink", "Blue", "Red").forEach { checkTicked(it, false) }
        val names = dialogNodes().filter { it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Checkbox }
            .sortedBy { it.boundsInRoot.top }.mapNotNull { node -> listOf("Necklace", "Trim", "Black", "Blue", "Pink", "Red").firstOrNull { node.hasLabel(it) } }
        check(names == listOf("Necklace", "Trim", "Black", "Blue", "Pink", "Red")) { "parts follow the author's numbers, got $names" }
        check(hasText("Shows over 1 file of Black")) { "the add-on that changes one of the colour's files starts ticked with it and shows there" }
        check(!hasText("You can keep several parts installed")) { "the general note stays away while a group says only one shows" }
        capture("pick_one_group")
        clickAt(dialogNodes().single { it.hasLabel("Blue") && it.config.contains(SemanticsProperties.ToggleableState) }.boundsInRoot)
        advance(400)
        check(hasText("These change the same files. The ticked ones share 2 files.")) { "two ticked colours count the files they share" }
        check(dialogNodes().any { it.hasLabel("Move Blue up") }) { "ticked parts that share files can be ordered" }
        capture("pick_one_two_ticked")
        key(Key.Escape)
        awaitNoSurface()
    }
}

internal fun checkInstallDialogReadme(outDir: File) {
    val readme = "Lilith Werewolf Transform\n\nInstall: put pak01 in addons. If you also use the Hell Warden skin, name the skin pak02 so this one shows.\n\n" +
        (1..12).joinToString("\n") { "Changelog $it: tweaked fur shading and claw trails." }
    val archive = listOf(
        "Lilith/Werewolf/pak01_dir.vpk" to FakeVpk.directory(seed = 2),
        "Lilith/Font/pak03_dir.vpk" to FakeVpk.of(mapOf("panorama/fonts/lilith_runes.ttf" to "runes".encodeToByteArray())),
        "Lilith/Broken/pak05_dir.vpk" to FakeVpk.directory(chunkReads = mapOf(0 to 10L), seed = 4),
        "README.txt" to readme.encodeToByteArray(),
    )
    val container = InstallDialogFixture.container(archives = mapOf(InstallDialogFixture.Werewolf to archive))
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "install_dialog_readme", state) {
        openComponentQuestion(state)
        check(hasText("name the skin pak02 so this one shows")) { "the readme's install step is shown" }
        check(hasText("Its fonts load only through fonts.conf, which Hideout doesn’t change.")) { "the font part says it needs fonts.conf" }
        check(hasText("pak05_000.vpk is missing")) { "the broken part is listed as left out" }
        capture("readme_shown")
        key(Key.Escape)
        awaitNoSurface()
    }
}

internal fun checkInstallDialogPreview(outDir: File) {
    val container = InstallDialogFixture.container()
    val state = HideoutAppState(container, Route.Discover)
    val lilith = InstallDialogFixture.Lilith.modId
    runCheck(outDir, "install_dialog_preview", state) {
        advance(600)
        clickAt(outside("Install").boundsInRoot)
        awaitSurface()
        advance(Settle)
        container.modLibrary.install(InstallDialogFixture.Ivy.modId)
        advance(200)
        check(dialogTitle() == "Lilith, the Hell Warden") { "A later question never replaces the one on screen" }
        clickAt(nodes().single { it.hasLabel("Preview Werewolf Transform in 3D") }.boundsInRoot)
        check(state.stage(lilith) == null) { "Preview cancels the question first" }
        var closedAt = -1L
        var waited = 0L
        val frames = mutableListOf<String>()
        while (waited < 4_000) {
            advance(Frame)
            waited += Frame
            val shown = surface() != null
            frames += "$waited shown=$shown title=${dialogTitle()} route=${state.navigator.current.route} settled=${state.navigator.settledEntryId == state.navigator.current.id} modal=${state.modal.isOpen}"
            if (!shown && closedAt < 0) closedAt = waited
            if (closedAt >= 0 && shown) {
                check(state.navigator.settledEntryId == state.navigator.current.id) { "A queued dialog opened before the preview page settled" }
                break
            }
            if (waited == 160L) capture("preview_closing")
        }
        File(dir, "frames.txt").writeText(frames.joinToString("\n"))
        check(closedAt >= 0) { "The dialog closed" }
        check(state.navigator.current.route == Route.ModDetail(lilith) && state.navigator.backStack.size == 2) { "Preview opened the mod page once" }
        check(dialogTitle() == "Bubblegum ivy V2") { "The queued question shows once the page has settled" }
        capture("queued_after_preview")
        key(Key.Escape)
        awaitNoSurface()
        advance(400)
        checkPreviewFile(state, InstallDialogFixture.Werewolf.id, InstallDialogFixture.Werewolf.md5)
        capture("exact_file_previewed")

        container.modLibrary.install(lilith)
        awaitSurface()
        advance(Settle)
        clickAt(nodes().single { it.hasLabel("Preview Werewolf Transform Lilith Cabezas in 3D") }.boundsInRoot)
        awaitNoSurface()
        advance(800)
        check(state.navigator.backStack.size == 2 && state.navigator.current.route == Route.ModDetail(lilith)) { "Preview from the same mod's page stays in place" }
        checkPreviewFile(state, InstallDialogFixture.Cabezas.id, InstallDialogFixture.Cabezas.md5)
        capture("previewed_in_place")

        container.modLibrary.install(lilith)
        awaitSurface()
        advance(Settle)
        clickAt(dialogNodes().single { it.hasLabel("Main") && it.config.contains(SemanticsProperties.ToggleableState) }.boundsInRoot)
        advance(400)
        clickAt(dialogNodes().single { it.confirms("Install") }.boundsInRoot)
        awaitNoSurface()
        advance(1_000)
        val record = (container.modLibrary.state.value.library as LibraryStatus.Ready).records.single { it.modId == InstallDialogFixture.Lilith.modId }
        check(record.remote.fileId == InstallDialogFixture.Default.id) { "Install takes the preselected most downloaded file, got ${record.remote}" }
        check(!record.enabled) { "The switch turned off placed the set off, got $record" }
        check(runBlocking { container.modLibrary.status(lilith).first() } == InstallStatus.Installed(enabled = false))
        capture("installed_off")
    }
}

internal fun checkInstallSkinSwap(outDir: File) {
    val model = "models/heroes/silver/silver.vmdl_c"
    val werecatDirectory = "b".repeat(64)
    val container = InstallDialogFixture.container(
        archives = mapOf(InstallDialogFixture.Default to listOf("lilith.vpk" to FakeVpk.of(mapOf(model to "new lilith".encodeToByteArray())))),
        indexes = ResourceIndexSource { _, _, image -> if (image.sha256 == werecatDirectory) ResourceIndex.Ready(mapOf(model to 1L)) else ResourceIndex.Unavailable },
    )
    val state = HideoutAppState(container, Route.Discover)
    val lilith = InstallDialogFixture.Lilith.modId
    runCheck(outDir, "install_dialog_swap", state) {
        advance(600)
        clickAt(outside("Install").boundsInRoot)
        awaitSurface()
        advance(Settle)
        clickAt(dialogNodes().single { it.confirms("Install") }.boundsInRoot)
        awaitNoSurface()
        advance(1_000)
        check(state.stage(lilith) is OperationStage.ChoosingSwap) { "the new skin's model is the one the Werecat shows, got ${state.stage(lilith)}" }
        check(hasText("Night of the Werecat changes the same models") && hasText("Night of the Werecat is on.")) { "the question names the mod it would turn off, which the catalogue does not know as a skin" }
        check(hasText("Keeping both puts Lilith, the Hell Warden on top")) { "the question says what Keep both does to the order" }
        capture("swap_question")
        clickLabel("Swap")
        advance(1_500)
        val records = (container.modLibrary.state.value.library as LibraryStatus.Ready).records
        check(records.single { it.modId == lilith }.enabled && !records.single { it.installId == "werecat" }.enabled) { "the Werecat went off as the new Lilith went on" }
        check(hasText("Undo")) { "the swap can be undone" }
        capture("swapped")
    }
}

internal fun checkInstallDialogFocusedEnter(outDir: File) {
    val downloaders = mutableListOf<FakeDownloader>()
    val container = InstallDialogFixture.container(downloads = downloaders::add)
    val state = HideoutAppState(container, Route.Discover)
    val lilith = InstallDialogFixture.Lilith.modId
    fun requested() = downloaders.single().requested.toList()
    fun records() = (container.modLibrary.state.value.library as LibraryStatus.Ready).records
    runCheck(outDir, "install_dialog_focused_enter", state) {
        advance(600)
        clickAt(outside("Install").boundsInRoot)
        awaitSurface()
        advance(Settle)
        tabTo("Preview Werewolf Transform") { it.hasLabel("Preview Werewolf Transform in 3D") }
        capture("preview_focused")
        key(Key.Enter)
        check(requested().isEmpty() && state.stage(lilith) !is OperationStage.Downloading) { "Enter on a focused Preview downloads nothing, got ${requested()}" }
        check(state.stage(lilith) == null) { "Enter on a focused Preview cancels the question, got ${state.stage(lilith)}" }
        awaitNoSurface()
        awaitSettledPage(state)
        check(state.navigator.current.route == Route.ModDetail(lilith) && state.navigator.backStack.size == 2) { "Enter on a focused Preview opens the mod page once" }
        checkPreviewFile(state, InstallDialogFixture.Werewolf.id, InstallDialogFixture.Werewolf.md5)
        check(records().none { it.modId == InstallDialogFixture.Lilith.modId } && requested().isEmpty()) { "Preview installed nothing, got ${requested()}" }
        capture("preview_by_enter")
        clickLabel("Back")
        awaitSettledPage(state)

        val install = outside("Install").boundsInRoot
        clickAt(install)
        awaitSurface()
        advance(Settle)
        tabTo("the Main profile chip") { it.hasLabel("Main") && it.config.contains(SemanticsProperties.ToggleableState) }
        check(dialogMainSelected()) { "The active profile starts selected" }
        key(Key.Enter)
        advance(200)
        check(!dialogMainSelected() && surface() != null && state.stage(lilith) is OperationStage.ChoosingFile) { "Enter on the focused profile chip turns it off and keeps the dialog open" }
        key(Key.NumPadEnter)
        advance(200)
        check(dialogMainSelected() && state.stage(lilith) is OperationStage.ChoosingFile) { "Numpad Enter on the focused profile chip turns it back on" }
        capture("switch_by_keys")
        tabTo("Cancel") { it.hasLabel("Cancel") }
        key(Key.NumPadEnter)
        awaitNoSurface()
        advance(Frame * 4)
        check(state.stage(lilith) == null && requested().isEmpty()) { "Numpad Enter on a focused Cancel cancels" }
        check(focused()?.boundsInRoot == install) { "Focus goes back to the Install button" }

        clickAt(install)
        awaitSurface()
        advance(Settle)
        checkTicked("Default", true)
        tabTo("the Werewolf Transform row") { it.hasLabel("Werewolf Transform") && it.config.contains(SemanticsProperties.ToggleableState) }
        key(Key.Spacebar)
        advance(200)
        checkTicked("Werewolf Transform", true)
        check(surface() != null && state.stage(lilith) is OperationStage.ChoosingFile) { "Space on a focused row ticks it and keeps the dialog open" }
        tabTo("the Default row") { it.hasLabel("Default") && it.config.contains(SemanticsProperties.ToggleableState) }
        key(Key.Spacebar)
        advance(200)
        checkTicked("Default", false)
        capture("row_ticked_by_space")
        key(Key.Enter)
        awaitNoSurface()
        advance(1_000)
        val werewolf = records().single { it.modId == InstallDialogFixture.Lilith.modId }
        check(werewolf.sets.map { it.fileId } == listOf(InstallDialogFixture.Werewolf.id) && werewolf.enabled) { "Enter on a focused row installs the ticked files, got ${werewolf.sets}" }
        check(requested() == listOf(InstallDialogFixture.Werewolf.id)) { "Only the ticked file was downloaded, got ${requested()}" }
        capture("rows_installed")
    }

    val second = mutableListOf<FakeDownloader>()
    val installs = InstallDialogFixture.container(downloads = second::add)
    val page = HideoutAppState(installs, Route.Discover)
    fun installed() = (installs.modLibrary.state.value.library as LibraryStatus.Ready).records
    runCheck(outDir, "install_dialog_focused_install", page) {
        advance(600)
        clickAt(outside("Install").boundsInRoot)
        awaitSurface()
        advance(Settle)
        clickAt(dialogNodes().single { it.hasLabel("Werewolf Transform") && it.config.contains(SemanticsProperties.ToggleableState) }.boundsInRoot)
        advance(200)
        checkTicked("Werewolf Transform", true)
        checkTicked("Default", true)
        clickAt(dialogNodes().single { it.hasLabel("Default") && it.config.contains(SemanticsProperties.ToggleableState) }.boundsInRoot)
        advance(200)
        checkTicked("Werewolf Transform", true)
        checkTicked("Default", false)
        tabTo("Install") { it.confirms("Install") }
        key(Key.Enter)
        awaitNoSurface()
        advance(1_000)
        check(installed().single { it.modId == InstallDialogFixture.Lilith.modId }.remote.fileId == InstallDialogFixture.Werewolf.id) { "Enter on the focused Install installs the selection" }
        check(second.single().requested == listOf(InstallDialogFixture.Werewolf.id)) { "Got ${second.single().requested}" }
        capture("installed_by_focused_install")
    }
}

internal fun checkInstallMotion(outDir: File) {
    val state = HideoutAppState(InstallDialogFixture.container(), Route.Discover)
    runCheck(outDir, "install_motion", state) {
        advance(600)
        val button = outside("Install").boundsInRoot
        hover(button)
        val before = pixels()
        click()
        val first = awaitSurface()
        capture("open_0")
        val opening = mutableListOf(first)
        var elapsed = 0L
        while (elapsed < 800L) {
            advance(Frame)
            elapsed += Frame
            opening += surface()!!
            if (elapsed in listOf(48L, 96L, 160L, 240L, 400L, 800L)) capture("open_${elapsed}ms")
        }
        advance(Settle)
        val target = surface()!!
        check(abs(target.width - DialogWidth) <= 1f && abs(target.center.x - WindowWidth / 2) <= 1f && abs(target.center.y - WindowHeight / 2) <= 1f) { "The dialog settles centred and 560 wide, got $target" }
        val start = abs(first.left - button.left) + abs(first.top - button.top) + abs(first.height - button.height)
        check(start < distance(first, target) / 4) { "The first frame starts at the Install button $button, got $first" }
        check(opening.any { distance(it, button) > 4f && distance(it, target) > 4f }) { "The container travels through the frames between" }
        val scrimmed = pixels()
        val (x, y) = paneSample(before, target)
        val ratio = luminance(scrimmed, x, y) / luminance(before, x, y)
        check(abs(ratio - 0.38f) < 0.05f) { "The scrim leaves 38% of the page, measured $ratio at $x,$y" }
        val sidebar = nodes().first { it.hasLabel("Discover") }.boundsInRoot
        check(luminance(scrimmed, sidebar.center.x.toInt(), sidebar.center.y.toInt()) < luminance(before, sidebar.center.x.toInt(), sidebar.center.y.toInt()) * 0.5f) { "The scrim covers the sidebar" }

        key(Key.Escape)
        val closing = mutableListOf<Rect>()
        val containment = BrightnessContainmentCheck(before, target)
        var waited = 0L
        while (true) {
            val now = surface() ?: break
            closing += now
            if (waited in listOf(48L, 160L, 320L)) capture("close_${waited}ms")
            containment.frame(this, "close ${waited}ms", now)
            advance(Frame)
            waited += Frame
            check(waited < 2_000) { "The dialog did not close" }
        }
        check(distance(closing.last(), button) < distance(closing.last(), target) / 4) { "Closing ends in the Install button, got ${closing.last()}" }
        capture("closed")
        File(dir, "bounds.txt").writeText(
            "button $button\nopening ${opening.joinToString()}\ntarget $target\nclosing ${closing.joinToString()}\nscrim ratio $ratio at $x,$y\n",
        )

        click()
        awaitSurface()
        elapsed = 0L
        while (elapsed < 800L) {
            containment.frame(this, "reopen ${elapsed}ms", surface()!!) { heroButtons(button) }
            advance(Frame)
            elapsed += Frame
        }
        File(dir, "containment.txt").writeText(containment.report())
        containment.check()
    }
}

private class BrightnessContainmentCheck(private val before: Pixels, private val target: Rect) {
    private val lines = mutableListOf<String>()
    private var leaked = 0

    fun frame(script: Script, label: String, surface: Rect, changed: () -> Rect? = { null }) {
        val now = script.pixels()
        val inside = surface.inflate(AntialiasingAllowancePx)
        val skipped = changed()
        var count = 0
        for (y in target.top.toInt() until target.bottom.toInt()) for (x in target.left.toInt() until target.right.toInt()) {
            val at = Offset(x + 0.5f, y + 0.5f)
            if (inside.contains(at) || skipped?.contains(at) == true) continue
            if (luminance(now, x, y) > luminance(before, x, y) + PermittedBrightnessIncrease) count++
        }
        if (count > 0 && leaked == 0) script.capture("leak_${label.replace(' ', '_')}")
        leaked += count
        lines += "$label surface $surface leaked $count"
    }

    fun report() = "target $target\n" + lines.joinToString("\n") + "\n"

    fun check() = check(leaked == 0) { "Content showed outside the dialog's surface in ${lines.count { !it.endsWith(" 0") }} frames: ${lines.filterNot { it.endsWith(" 0") }.take(4)}" }
}

internal fun checkInstallMotionReduced(outDir: File) {
    val state = HideoutAppState(InstallDialogFixture.container(), Route.Discover)
    runCheck(outDir, "install_motion_reduced", state, reducedMotion = true) {
        advance(600)
        clickAt(outside("Install").boundsInRoot)
        val frames = mutableListOf(awaitSurface())
        repeat(30) { advance(Frame); frames += surface()!! }
        capture("open_reduced")
        check(frames.all { it == frames.last() }) { "Reduced motion moves nothing, got ${frames.distinct()}" }
        key(Key.Escape)
        while (true) {
            val now = surface() ?: break
            check(now == frames.last()) { "Reduced motion moves nothing while closing, got $now" }
            advance(Frame)
        }
    }
}

internal fun checkInstallMotionCentre(outDir: File) {
    val container = InstallDialogFixture.container()
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "install_motion_centre", state) {
        advance(600)
        container.modLibrary.install(InstallDialogFixture.Ivy.modId)
        val first = awaitSurface()
        advance(Settle)
        val target = surface()!!
        check(abs(first.center.x - target.center.x) <= 1f && abs(first.center.y - target.center.y) <= 1f) { "Without a button the dialog starts at its own centre, got $first" }
        check(first.width in target.width * 0.95f..target.width * 0.98f) { "and slightly smaller, got $first for $target" }
        capture("centred")
    }
}

private class Pixels(val bytes: ByteArray, val rowBytes: Int)

private fun Script.pixels(): Pixels = scene.render(time * 1_000_000).use { Bitmap.makeFromImage(it) }.use { bitmap ->
    Pixels(checkNotNull(bitmap.readPixels()), bitmap.rowBytes)
}

private fun luminance(pixels: Pixels, x: Int, y: Int): Float {
    val at = y * pixels.rowBytes + x * 4
    return ((pixels.bytes[at].toInt() and 0xFF) + (pixels.bytes[at + 1].toInt() and 0xFF) + (pixels.bytes[at + 2].toInt() and 0xFF)) / 3f
}

private fun paneSample(before: Pixels, dialog: Rect): Pair<Int, Int> {
    val shadowed = dialog.inflate(ShadowReach)
    for (y in 600 until 880 step 7) for (x in 1000 until 1430 step 7) {
        if (!shadowed.contains(Offset(x.toFloat(), y.toFloat())) && luminance(before, x, y) >= 15f) return x to y
    }
    error("No page pixel to measure the scrim against")
}

internal fun checkInstallDialogCompact(outDir: File) {
    val state = HideoutAppState(InstallDialogFixture.container(files = InstallDialogFixture.ManyFiles), Route.Discover)
    runCheck(outDir, "install_dialog_compact", state, widthDp = CompactWidth, heightDp = CompactHeight) {
        advance(600)
        clickAt(outside("Pause featured rotation").boundsInRoot)
        hover(outside("Install").boundsInRoot)
        val before = pixels()
        click()
        awaitSurface()
        advance(Settle)
        val dialog = surface()!!
        check(dialog.top >= Margin - 1 && dialog.bottom <= CompactHeight - Margin + 1 && dialog.left >= Margin - 1 && dialog.right <= CompactWidth - Margin + 1) { "The dialog keeps clear of the window's edges, got $dialog" }
        val install = dialogNodes().single { it.confirms("Install") }.boundsInRoot
        check(dialog.contains(install.topLeft) && dialog.contains(install.bottomRight - Offset(1f, 1f))) { "Install stays reachable at the foot of the dialog, got $install in $dialog" }
        capture("first_row")
        val ninth = "Hell Warden variant 9 with an author description long enough to need ellipsis"
        tabTo("the ninth row") { it.hasLabel(ninth) && it.config.contains(SemanticsProperties.ToggleableState) }
        key(Key.Spacebar)
        advance(600)
        val ticked = dialogNodes().filter { it.config.getOrElseNullable(SemanticsProperties.ToggleableState) { null } == ToggleableState.On && it.config.contains(SemanticsProperties.Role) }
        val last = ticked.single { it.hasLabel(ninth) }
        val footer = dialogNodes().single { it.confirms("Install 2 files") }.boundsInRoot
        check(last.boundsInRoot.top >= dialog.top && last.boundsInRoot.bottom < footer.top) { "The ninth row is scrolled into view above the footer, got ${last.boundsInRoot}" }
        capture("last_row")

        key(Key.Escape)
        val containment = BrightnessContainmentCheck(before, dialog)
        var waited = 0L
        while (true) {
            val now = surface() ?: break
            containment.frame(this, "close ${waited}ms", now)
            if (waited == 48L) capture("close_48ms")
            advance(Frame)
            waited += Frame
            check(waited < 2_000) { "The dialog did not close" }
        }
        File(dir, "containment.txt").writeText(containment.report())
        containment.check()
    }
}

private const val CompactWidth = 1000
private const val CompactHeight = 680
private const val Margin = 24f

private fun SemanticsNode.confirms(action: String): Boolean =
    config.contains(SemanticsActions.OnClick) && config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text == action || it.text.startsWith("$action · ") } == true
