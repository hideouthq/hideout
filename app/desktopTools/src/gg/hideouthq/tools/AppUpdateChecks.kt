package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.appupdate.AppUpdatePhase
import gg.hideouthq.core.appupdate.RestartStage
import gg.hideouthq.core.data.fake.FakeAppUpdate
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.designsystem.image.RemoteImage
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.model.AppAbout
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.feature.settings.SettingsTab
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import gg.hideouthq.core.customgames.Choices
import gg.hideouthq.core.customgames.Session
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.library.GameActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.time.Duration.Companion.seconds

private const val WhatsNew = "What’s new"
private const val Settle = 900L
private const val ToastLifeMillis = 10_000L

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/appupdate")
    checkChipSitsInTheTopBarAndTheSidebarKeepsItsHeight(out)
    checkUpdateRunsToRestart(out)
    checkCancelGoesBackToAvailable(out)
    checkTheChipShrinksOnceItsPanelClosed(out)
    checkFailuresSayWhy(out)
    checkUndrawnStates(out)
    checkWhileHosting(out)
    checkToast(out)
    checkDialog(out)
    checkFailedImageLeavesACard(out)
    checkSettings(out)
    println("All Hideout update checks passed")
}

private fun app(script: FakeAppUpdate, about: AppAbout? = null, images: RemoteImages = ReleaseArt, start: Route = Route.Discover): HideoutAppState {
    val config = FakeConfig(appUpdate = script)
    val container = if (about != null) AppContainer.fake(config, about = about, images = images) else AppContainer.fake(config, images = images)
    return HideoutAppState(container, start)
}

private fun hostingApp(script: FakeAppUpdate): HideoutAppState {
    val state = app(script)
    val games = checkNotNull(state.container.customGames)
    runBlocking {
        withTimeout(10.seconds) {
            state.container.modLibrary.state.first { it.deadlock == GameActivity.Closed && it.canPlay }
            games.refreshModes()
            games.modes.first { it.feed != null }
            games.host(FakeCustomGamesFeed.SampleFolder, Choices.None, null)
            games.live.first { it.session is Session.Hosting }
        }
    }
    return state
}

private val HideoutAppState.phase get() = container.appUpdates.state.value.phase

private fun Script.settle(millis: Long = Settle) {
    runUiQueue(millis)
    advance(millis)
}

private fun Script.await(what: String, limitMillis: Long = 8_000, done: () -> Boolean) {
    val deadline = System.nanoTime() + limitMillis * 1_000_000
    while (!done()) {
        check(System.nanoTime() < deadline) { "Never saw $what" }
        settle(100)
    }
    settle(100)
}

private fun Script.single(label: String): SemanticsNode = nodes().single { it.hasLabel(label) }

private fun Script.clickClose() {
    clickNode(nodes().filter { it.hasLabel("Close") && it.config.contains(SemanticsActions.OnClick) }.maxBy { it.boundsInRoot.top })
}

private fun Script.isDisabled(label: String) = single(label).config.contains(SemanticsProperties.Disabled)

private fun Script.dialogShowing() = hasText("What’s new in Hideout")

private fun Script.hostingChips() = nodes().filter { it.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { label -> label.startsWith("Hosting Arena · ") } == true }

private fun Script.open(chip: String) {
    clickLabel(chip)
    settle()
}

private fun checkChipSitsInTheTopBarAndTheSidebarKeepsItsHeight(out: File) {
    var discord = Rect.Zero
    runCheck(out, "appupdate_none", app(FakeAppUpdate.None), heightDp = 700) {
        settle()
        discord = single("Discord").boundsInRoot
        check(!shows("Update ready · 1.4.0"))
    }
    runCheck(out, "appupdate_place", app(FakeAppUpdate.Ready), heightDp = 700) {
        settle()
        val chip = single("Update ready · 1.4.0").boundsInRoot
        val minimize = single("Minimize").boundsInRoot
        check(chip.height == 34f && chip.bottom <= 56f) { "The chip is 34 high, in the top bar: $chip" }
        check(chip.right < minimize.left) { "and goes before the window controls: $chip, $minimize" }
        check(hasText("Restart to update")) { "Ready is the one state that asks in words" }
        check(single("Discord").boundsInRoot == discord) { "The sidebar never changes height: ${single("Discord").boundsInRoot} against $discord" }
        capture("chip")
    }
}

private fun checkUpdateRunsToRestart(out: File) {
    val state = app(FakeAppUpdate.Available)
    runCheck(out, "appupdate_update", state) {
        settle()
        check(hasText("Update available")) { "Available says so until opened" }
        open("Update available · 1.4.0")
        check(hasText("1.4.0 · 18.4 MB · you have 1.3.2")) { "Available names the version, its size and what you have" }
        capture("available_open")
        clickLabel("Update")
        settle()
        check(state.phase is AppUpdatePhase.Downloading || state.phase is AppUpdatePhase.Ready) { "Update starts the download, got ${state.phase}" }
        check(hasText("Downloading 1.4.0") || hasText("Update ready")) { "and the panel stays open on it" }
        capture("downloading")
        await("the package ready") { state.phase is AppUpdatePhase.Ready }
        check(hasText("Update ready") && hasText("Version 1.4.0"))
        capture("ready")
        clickLabel("Restart")
        settle()
        check((state.phase as AppUpdatePhase.Ready).restart == RestartStage.Exiting) { "Restart goes straight to the way out" }
        check(isDisabled("Restarting…")) { "A restart under way can't be asked for twice" }
        capture("exiting")
    }
}

private fun checkCancelGoesBackToAvailable(out: File) {
    val state = app(FakeAppUpdate.Downloading)
    runCheck(out, "appupdate_cancel", state) {
        settle()
        check(!hasText("Update available")) { "A download is an icon with a ring" }
        open("Downloading update · 62%")
        check(hasText("11.4 of 18.4 MB") && !hasText("62%")) { "Downloading shows how far along it is, once: the bar and the amount" }
        capture("downloading")
        clickLabel("Cancel")
        settle()
        check(state.phase is AppUpdatePhase.Available) { "Cancel goes back to Available, got ${state.phase}" }
        check(hasText("Update available") && shows("Update"))
        capture("cancelled")
    }
}

private fun checkTheChipShrinksOnceItsPanelClosed(out: File) {
    val state = app(FakeAppUpdate.Available)
    runCheck(out, "appupdate_opened", state) {
        settle()
        val wide = single("Update available · 1.4.0").boundsInRoot
        open("Update available · 1.4.0")
        check(single("Update available · 1.4.0").boundsInRoot == wide) { "The chip keeps its text while its panel is open" }
        key(Key.Escape)
        settle()
        check(!hasText("1.4.0 · 18.4 MB · you have 1.3.2")) { "Esc closes the panel" }
        val narrow = single("Update available · 1.4.0").boundsInRoot
        check(narrow.width == 34f && narrow.height == 34f) { "Opened once, it is a 34 circle: $narrow" }
        check(runBlocking { state.container.preferences.appUpdateOpened() } == "1.4.0") { "and that is kept with the preferences" }
        capture("seen")
    }
}

private fun checkFailuresSayWhy(out: File) {
    listOf(
        FakeAppUpdate.FailedConnection to "Check your connection and try again.",
        FakeAppUpdate.FailedServer to "GitHub isn’t responding. Hideout tries again by itself later.",
        FakeAppUpdate.FailedMismatch to "The download was damaged. Try again to download it afresh.",
        FakeAppUpdate.FailedDisk to "Hideout couldn’t save the download. Check there’s room on the disk, then try again.",
        FakeAppUpdate.FailedInstall to "Hideout opened again on the version it had, so 1.4.0 didn’t take. Install it yourself from GitHub.",
    ).forEach { (script, line) ->
        runCheck(out, "appupdate_${script.name}", app(script)) {
            settle()
            open("Update failed · 1.4.0")
            check(hasText("Update failed") && hasText(line)) { "$script says $line" }
            val retries = script != FakeAppUpdate.FailedInstall
            check(shows("Try again") == retries && shows("Get it from GitHub")) { "$script offers Try again only when it can help" }
            capture("panel")
        }
    }
    val state = app(FakeAppUpdate.FailedConnection)
    runCheck(out, "appupdate_try_again", state) {
        settle()
        open("Update failed · 1.4.0")
        clickLabel("Try again")
        settle()
        check(state.phase is AppUpdatePhase.Failed) { "The script fails again, and the panel says so" }
        check(hasText("Check your connection and try again."))
        capture("failed")
    }
}

private fun checkUndrawnStates(out: File) {
    runCheck(out, "appupdate_github", app(FakeAppUpdate.GitHubOnly)) {
        settle()
        open("Update available · 1.4.0")
        check(hasText("1.4.0 · you have 1.3.2") && shows("Get it from GitHub") && shows(WhatsNew)) { "A copy with no install method only points at GitHub" }
        check(!shows("Update")) { "and offers nothing it can't do" }
        clickLabel(WhatsNew)
        settle()
        check(shows("Done")) { "The dialog of a copy that can't install has only the way to GitHub and Done" }
        capture("github_dialog")
    }
    val password = app(FakeAppUpdate.Password)
    runCheck(out, "appupdate_installing", password) {
        settle()
        open("Update ready · 1.4.0")
        clickLabel("Restart")
        settle(300)
        check(hasText("Confirm the password prompt")) { "An install at its prompt says so" }
        check(!shows("Restart")) { "and can't be stopped from the panel" }
        capture("installing")
    }
}

private fun checkWhileHosting(out: File) {
    val available = hostingApp(FakeAppUpdate.Available)
    runCheck(out, "appupdate_hosting_available", available) {
        settle()
        check(!hasText("Update available")) { "A game keeps the update chip to an icon" }
        val hosting = hostingChips().single()
        val update = single("Update available · 1.4.0").boundsInRoot
        check(hosting.boundsInRoot.right < update.left) { "Hosting goes first" }
        clickNode(hosting)
        settle()
        check(shows("Open game"))
        capture("hosting_open")
        clickLabel("Update available · 1.4.0")
        settle()
        check(!shows("Open game") && hasText("1.4.0 · 18.4 MB · you have 1.3.2")) { "One panel at a time" }
        key(Key.Escape)
        settle()
        clickNode(hostingChips().single())
        settle()
        clickLabel("Open game")
        settle()
        check(available.navigator.current.route == Route.CustomGame) { "Open game goes to the game" }
        check(hostingChips().isEmpty()) { "where the hosting chip isn't needed" }
    }
    val ready = hostingApp(FakeAppUpdate.Ready).apply { settingsTab = SettingsTab.About }
    runCheck(out, "appupdate_hosting_ready", ready) {
        settle()
        check(!hasText("Restart to update")) { "Ready while hosting is an icon with a dot" }
        open("Update ready · 1.4.0")
        check(nodes().any { it.hasText("You’re hosting Arena. Restarting now ends the game for Ann, Kit, Rook, Wren and Ash too.") }) { "The panel says what a restart would end, and for whom" }
        capture("ready_while_hosting")
        clickLabel("Restart after your game")
        settle()
        check((ready.phase as AppUpdatePhase.Ready).restart == RestartStage.Waiting) { "Restart after your game queues it, got ${ready.phase}" }
        check(hasText("Installs after your game") && hasText("1.4.0 · Hideout restarts when Arena ends"))
        capture("after_game")
        clickLabel("Cancel")
        settle()
        check((ready.phase as AppUpdatePhase.Ready).restart == RestartStage.Idle && shows("Restart after your game")) { "Cancel goes back to Ready" }
        clickLabel("Restart after your game")
        settle()
        key(Key.Escape)
        settle()
        clickLabel("Settings")
        settle()
        clickLabel("See what’s new")
        settle()
        check(hasText("1.4.0 · Hideout restarts when Arena ends")) { "What’s new's footer says the same" }
        clickClose()
        settle()
        checkNotNull(ready.container.customGames).stop()
        await("the update install once the game ended") { (ready.phase as? AppUpdatePhase.Ready)?.restart == RestartStage.Exiting }
    }
    val now = hostingApp(FakeAppUpdate.Ready)
    runCheck(out, "appupdate_hosting_restart_now", now) {
        settle()
        open("Update ready · 1.4.0")
        clickLabel("Restart after your game")
        settle()
        clickLabel("Restart now")
        settle()
        check((now.phase as AppUpdatePhase.Ready).restart == RestartStage.Exiting) { "Restart now doesn't wait for the game, got ${now.phase}" }
        check(now.quit.asking == null) { "and asks nothing more" }
    }
}

private fun checkToast(out: File) {
    val timed = app(FakeAppUpdate.JustUpdated, AppAbout("1.4.0"))
    runCheck(out, "appupdate_toast_times_out", timed) {
        settle()
        check(hasText("Hideout updated to 1.4.0")) { "The toast shows on the first start after updating" }
        capture("toast")
        await("the toast leave", limitMillis = ToastLifeMillis + 3_000) { timed.container.appUpdates.state.value.justUpdatedTo == null }
        settle()
        check(!hasText("Hideout updated to 1.4.0")) { "and goes by itself" }
    }
    val opened = app(FakeAppUpdate.JustUpdated, AppAbout("1.4.0"))
    runCheck(out, "appupdate_toast_whats_new", opened) {
        settle()
        clickLabel(WhatsNew)
        settle()
        check(dialogShowing() && !hasText("Hideout updated to 1.4.0")) { "What’s new opens the dialog and hides the toast" }
        check(hasText("You’re on 1.4.0, released 2 November 2026") && hasText("You’re up to date"))
        clickLabel("Done")
        settle()
        check(!dialogShowing() && opened.container.appUpdates.state.value.justUpdatedTo == null) { "and it doesn't come back" }
    }
    val closed = app(FakeAppUpdate.JustUpdated, AppAbout("1.4.0"))
    runCheck(out, "appupdate_toast_close", closed) {
        settle()
        clickClose()
        settle()
        check(!hasText("Hideout updated to 1.4.0") && closed.container.appUpdates.state.value.justUpdatedTo == null) { "x hides the toast" }
    }
}

private fun checkDialog(out: File) {
    val available = app(FakeAppUpdate.Available)
    runCheck(out, "appupdate_dialog_available", available) {
        settle()
        open("Update available · 1.4.0")
        clickLabel(WhatsNew)
        settle()
        check(dialogShowing() && hasText("1.4.0 is available · you have 1.3.2") && hasText("18.4 MB download"))
        check(hasText("Hideout 1.4.0") && hasText("2 November 2026") && !hasText("Feature update")) { "The newest version is selected as it opens" }
        check(hasText("Hand-picked sets on Discover") && hasText("New") && hasText("Improved") && hasText("Fixed"))
        clickVersion("1.3.2")
        check(hasText("Hideout 1.3.2") && hasText("18 October 2026") && !hasText("Hand-picked sets on Discover") && !hasText("Improved")) { "A fix release has no highlights, and empty groups are skipped" }
        check(shows("Full release notes on GitHub")) { "The link ends the notes" }
        capture("fix_release")
        clickClose()
        settle()
        check(!dialogShowing())
        open("Update available · 1.4.0")
        clickLabel(WhatsNew)
        settle()
        check(hasText("Hideout 1.4.0")) { "Reopening selects the newest again" }
        clickNode(nodes().last { it.hasLabel("Update") && it.config.contains(SemanticsActions.OnClick) })
        settle()
        check(!dialogShowing()) { "Update closes the dialog" }
        check(available.phase is AppUpdatePhase.Downloading || available.phase is AppUpdatePhase.Ready) { "and starts the download, got ${available.phase}" }
    }
    val held = app(FakeAppUpdate.Downloading, start = Route.Settings).apply { settingsTab = SettingsTab.About }
    runCheck(out, "appupdate_dialog_downloading", held) {
        settle()
        clickLabel("See what’s new")
        settle()
        check(hasText("Downloading · 62%")) { "The footer follows the download" }
        check(!shows("Update"))
        check(!shows("Later")) { "Close is the one way out" }
        clickClose()
        settle()
        check(!dialogShowing()) { "Close closes the dialog" }
        check(held.phase is AppUpdatePhase.Downloading) { "and only that: the download goes on" }
    }
    val ready = app(FakeAppUpdate.Ready)
    runCheck(out, "appupdate_dialog_ready", ready) {
        settle()
        open("Update ready · 1.4.0")
        clickLabel(WhatsNew)
        settle()
        check(hasText("1.4.0 is ready to install · you have 1.3.2"))
        clickNode(nodes().last { it.hasLabel("Restart to update") && it.config.contains(SemanticsActions.OnClick) })
        settle()
        check((ready.phase as AppUpdatePhase.Ready).restart == RestartStage.Exiting) { "Restart to update restarts straight away" }
    }
    val putOff = app(FakeAppUpdate.Ready)
    runCheck(out, "appupdate_dialog_closed", putOff) {
        settle()
        open("Update ready · 1.4.0")
        clickLabel(WhatsNew)
        settle()
        clickClose()
        settle()
        check(!dialogShowing() && shows("Update ready · 1.4.0")) { "Close closes the dialog, and the chip stays" }
    }
    runCheck(out, "appupdate_dialog_narrow", app(FakeAppUpdate.Ready), widthDp = 840, heightDp = 700) {
        settle()
        open("Update ready · 1.4.0")
        clickLabel(WhatsNew)
        settle()
        check(!hasText("Versions")) { "Under 860 the list gives way" }
        check(shows("Choose a version")) { "to a dropdown" }
        capture("narrow")
    }
}

private object FailingArt : RemoteImages {
    @Composable
    override fun rememberImage(url: String, contentScale: ContentScale): RemoteImage = remember(url) {
        RemoteImage(ColorPainter(Color.Magenta), mutableStateOf(false), failed = mutableStateOf(true))
    }
}

private fun checkFailedImageLeavesACard(out: File) {
    var withArt = 0f
    runCheck(out, "appupdate_art", app(FakeAppUpdate.Ready)) {
        settle()
        open("Update ready · 1.4.0")
        clickLabel(WhatsNew)
        settle()
        withArt = single("Hand-picked sets on Discover, like Halloween and Winter.").boundsInRoot.top
    }
    runCheck(out, "appupdate_art_failed", app(FakeAppUpdate.Ready, images = FailingArt)) {
        settle()
        open("Update ready · 1.4.0")
        clickLabel(WhatsNew)
        settle()
        val top = single("Hand-picked sets on Discover, like Halloween and Winter.").boundsInRoot.top
        check(withArt - top > 100f) { "A failed image leaves the card without one: $withArt then $top" }
        capture("no_art")
    }
}

private fun checkSettings(out: File) {
    val state = app(FakeAppUpdate.Ready, start = Route.Settings).apply { settingsTab = SettingsTab.About }
    runCheck(out, "appupdate_settings", state) {
        settle()
        check(hasText("Check for Hideout updates") && hasText("Download updates automatically") && hasText("What’s new"))
        check(nodes().any { it.hasText("hours ago", substring = true) || it.hasText("days ago", substring = true) || it.hasText("minutes ago", substring = true) || it.hasText("Checked just now", substring = true) }) { "The version row says when Hideout last looked" }
        check(hasText("1.4.0 is ready to install · you have 1.3.2")) { "About shows the update on offer" }
        capture("about")
        val switch = nodes().single { it.hasLabel("Download updates automatically") && it.config.contains(SemanticsProperties.ToggleableState) }
        check(switch.config[SemanticsProperties.ToggleableState] == ToggleableState.On) { "Downloads are automatic by default" }
        clickNode(switch)
        settle()
        check(state.container.preferences.downloadAppUpdates.value == false) { "The switch saves" }
        clickLabel("Check now")
        settle()
        check(!shows("Checking…")) { "The check finishes" }
        clickLabel("See what’s new")
        settle()
        check(dialogShowing()) { "About's row opens the dialog" }
        clickClose()
        settle()
        for (query in listOf("Hideout updates", "changelog", "automatically")) {
            clickNode(nodes().single { it.config.contains(SemanticsActions.SetText) })
            key(Key.A, ctrl = true)
            key(Key.Backspace)
            type(query)
            settle()
            check(!hasText("No settings match “$query”")) { "Search finds $query" }
        }
        capture("search")
    }
}

private fun SemanticsNode.hasText(text: String, substring: Boolean = false): Boolean =
    config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { if (substring) text in it.text else it.text == text } == true
