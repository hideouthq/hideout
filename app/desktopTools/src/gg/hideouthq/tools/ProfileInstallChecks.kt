package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.FolderOpener
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.library.*
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.feature.install.InstallDialogTags
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/profiles/install/rendered")
    val failures = mutableListOf<Throwable>()
    for (width in listOf(1440, 1000)) {
        runCatching { checkProfilePage(out, width) }.exceptionOrNull()?.let { failures += it; it.printStackTrace() }
        runCatching { checkProfileDialog(out, width) }.exceptionOrNull()?.let { failures += it; it.printStackTrace() }
    }
    check(failures.isEmpty()) { "${failures.size} rendered profile-install checks failed" }
    println("Profile install page, dialog, version replacement, cancellation and badge checks passed.")
}

private fun Script.clickInstallNode(node: SemanticsNode) {
    moveToDp(node.boundsInRoot.center.x / scale, node.boundsInRoot.center.y / scale)
    click()
    advance(500)
}

private fun Script.profileChip(name: String): SemanticsNode = nodes().single {
    it.hasLabel(name) && it.config.contains(SemanticsProperties.ToggleableState)
}

private fun Script.checkDialogSelected(label: String, selected: Boolean) {
    val matches = dialogNodes().filter { it.hasLabel(label) && it.config.contains(SemanticsProperties.ToggleableState) }
    check(matches.size == 1) { "Expected one checkbox '$label' in the dialog, found ${matches.size}" }
    check(matches.single().checked() == selected) { "'$label' selection should be $selected" }
}

private fun SemanticsNode.checked(): Boolean = config.getOrElseNullable(SemanticsProperties.ToggleableState) { null } == ToggleableState.On

private fun Script.clickButtonStarting(prefix: String) {
    val dialog = nodes().firstOrNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == InstallDialogTags.Surface }?.boundsInRoot
    clickInstallNode(nodes().single {
        (dialog == null || dialog.contains(it.boundsInRoot.center)) && it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { text -> text.text.startsWith(prefix) } == true
    })
}

private fun checkProfilePage(out: File, width: Int) {
    lateinit var downloads: FakeDownloader
    val openedFolders = mutableListOf<Pair<GameRoot, SetFolder>>()
    val app = InstallDialogFixture.container(downloads = { downloads = it }, folders = FolderOpener { root, folder ->
        openedFolders += root to folder
        true
    })
    val library = app.modLibrary
    val main = library.state.value.profiles!!.active
    val state = HideoutAppState(app, Route.ModDetail(InstallDialogFixture.Lilith.modId))
    fun record() = (library.state.value.library as LibraryStatus.Ready).records.single { it.modId == InstallDialogFixture.Lilith.modId }
    runCheck(out, "page_$width", state, widthDp = width, heightDp = if (width == 1000) 680 else 900) {
        advance(1000)
        if (width == 1000) clickLabel("Details")
        check(nodes().none { it.hasLabel("Ranked") && it.config.contains(SemanticsProperties.ToggleableState) }) { "Before installing, the install dialog asks where to turn the mod on, not the page" }
        capture("before_install")
        downloads.gate = CompletableDeferred()
        clickButtonStarting("Install")
        clickInstallNode(profileChip("Ranked"))
        checkDialogSelected("Default", true)
        capture("page_install_dialog")
        clickButtonStarting("Install")
        check(hasText("Downloading"))
        capture("downloading")
        clickLabel("Cancel download")
        capture("cancelled")
        downloads.gate = null
        clickButtonStarting("Install")
        if (!profileChip("Ranked").checked()) clickInstallNode(profileChip("Ranked"))
        checkDialogSelected("Default", true)
        clickButtonStarting("Install")
        advance(1000)
        val before = record()
        check(!hasText("lilith_hell_warden.zip")) { "The page must not repeat the dialog's version and filename below Install" }
        clickLabel("What’s new in 1.2")
        check(hasText("You have this")) { "The changelog does not mark the update the installed file came with" }
        capture("changelog_installed")
        clickLabel("Back to the mod’s details")
        clickButtonStarting("Installed")
        check(hasText("Show in Library") && hasText("Show files in folder"))
        capture("installed_menu")
        clickLabel("Show files in folder")
        check(openedFolders == listOf(before.gameRoot to before.setFolder()))
        clickButtonStarting("Installed")
        clickLabel("Change files…")
        checkDialogSelected("Default", true)
        check(!hasText("lilith_hell_warden.zip")) { "The version dialog names a file by what tells it apart, not by its archive" }
        val dialogBounds = nodes().single { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == InstallDialogTags.Surface }.boundsInRoot
        check(nodes().none {
            dialogBounds.contains(it.boundsInRoot.center) && it.config.contains(SemanticsProperties.ToggleableState) &&
                (it.hasLabel("Main") || it.hasLabel("Ranked"))
        }) { "Changing files must not offer profile edits it cannot apply" }
        clickInstallNode(profileChip("Werewolf Transform"))
        checkDialogSelected("Default", true)
        checkDialogSelected("Werewolf Transform", true)
        advance(300)
        capture("change_files_dialog")
        clickButtonStarting("Change files")
        advance(1000)
        val after = record()
        check(!hasText("lilith_werewolf_transform.zip")) { "Changing files must not restore the redundant filename hint" }
        capture("switched")
        clickInstallNode(profileChip("Main"))
        if (width == 1000) key(Key.Escape)
        state.navigator.navigate(Route.Browse())
        advance(1000)
        check(hasText("In library"))
        capture("browse_in_library")
        runBlocking { library.setMembership(main, after.installId, true) }
        advance(500)
        check(hasText("On"))
        capture("browse_on")
        state.navigator.navigate(Route.Discover)
        advance(1000)
        capture("discover_on")
        clickButtonStarting("Installed")
        advance(1000)
        check(state.navigator.current.route == Route.ModDetail(InstallDialogFixture.Lilith.modId))
        if (width == 1000 && !state.sidePanelOverlay.isOpen) clickLabel("Details")
        clickButtonStarting("Installed")
        clickLabel("Show in Library")
        check(state.navigator.current.route == Route.Library)
        clickLabel("Back")
        advance(1000)
        if (width == 1000 && !state.sidePanelOverlay.isOpen) clickLabel("Details")
        clickButtonStarting("Installed")
        clickLabel("Uninstall")
        advance(1000)
        check(hasText("Undo")) { "Uninstall asks nothing and offers an Undo" }
        capture("undo_offered")
        awaitReal(limitMillis = 15_000, what = "the undo window passing") { (library.state.value.library as LibraryStatus.Ready).records.none { it.modId == InstallDialogFixture.Lilith.modId } }
        advance(1000)
        capture("uninstalled")
    }
}

private fun checkProfileDialog(out: File, width: Int) {
    val app = InstallDialogFixture.container()
    val library = app.modLibrary
    val ranked = library.state.value.profiles!!.profiles.single { it.name == "Ranked" }.id
    val state = HideoutAppState(app, Route.Discover)
    runCheck(out, "dialog_$width", state, widthDp = width, heightDp = if (width == 1000) 680 else 900) {
        advance(1000)
        clickButtonStarting("Install")
        advance(1200)
        clickInstallNode(profileChip("Main"))
        clickInstallNode(profileChip("Ranked"))
        checkDialogSelected("Default", true)
        clickInstallNode(profileChip("Werewolf Transform"))
        checkDialogSelected("Default", true)
        checkDialogSelected("Werewolf Transform", true)
        advance(500)
        capture("chosen_profiles")
        clickButtonStarting("Install")
        advance(1200)
        val record = (library.state.value.library as LibraryStatus.Ready).records.single { it.modId == InstallDialogFixture.Lilith.modId }
        check(record.sets.map { it.fileId } == listOf(InstallDialogFixture.Default.id, InstallDialogFixture.Werewolf.id))
        check(!record.enabled)
        check(library.state.value.profiles!!.profiles.filter { record.installId in it.enabled }.map { it.id } == listOf(ranked))
        capture("installed_in_ranked")
    }
}
