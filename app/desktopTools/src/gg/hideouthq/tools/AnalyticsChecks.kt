@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.analytics.AnalyticsChoice
import gg.hideouthq.core.analytics.UsageAnalytics
import gg.hideouthq.core.analytics.UsageEvent
import gg.hideouthq.core.analytics.UsageOperation
import gg.hideouthq.core.analytics.UsageOutcome
import gg.hideouthq.core.analytics.UsageTransport
import gg.hideouthq.core.data.CacheStorage
import gg.hideouthq.core.data.fake.FakeAppUpdate
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.feature.settings.SettingsTab
import gg.hideouthq.tools.preview.advanceUntil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

private class AnalyticsFixture(val storage: CacheStorage = MemoryStorage()) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val sent = CopyOnWriteArrayList<UsageEvent>()
    val analytics = UsageAnalytics(storage, UsageTransport { _, event -> sent += event }, scope)
    fun app(config: FakeConfig = FakeConfig(), route: Route = Route.Discover) = HideoutAppState(
        AppContainer.fake(config, analytics = analytics), route,
    ).apply { settingsTab = SettingsTab.About }
    override fun close() { analytics.close(); scope.cancel() }
}

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/analytics-checks")
    checkAnalyticsUnavailable(out)
    checkAnalyticsNotice(out)
    checkAnalyticsOnboarding(out)
    checkAnalyticsDecline(out)
    checkAnalyticsAccept(out)
    checkAnalyticsUpdateNotice(out)
    checkAnalyticsSettings(out)
    checkAnalyticsSaveFailure(out)
    println("All analytics notice, onboarding, settings, persistence, and save failure checks passed.")
}

private fun Script.analyticsSwitch(label: String = "Share usage"): SemanticsNode = nodes().single {
    it.hasLabel(label) && it.config.contains(SemanticsProperties.ToggleableState)
}

private fun Script.expectAnalyticsSwitch(on: Boolean, label: String = "Share usage") {
    check(analyticsSwitch(label).config[SemanticsProperties.ToggleableState] == if (on) ToggleableState.On else ToggleableState.Off) {
        "The analytics switch must show the current choice"
    }
}

private fun checkAnalyticsUnavailable(out: File) {
    val app = HideoutAppState(AppContainer.fake(FakeConfig(setupDone = true)), Route.Settings).apply { settingsTab = SettingsTab.About }
    runCheck(out, "analytics_unavailable", app) {
        advanceUntil("settings without analytics") { shows("Where mods come from") }
        check(!shows("Help improve Hideout")) { "Unconfigured builds must not ask for analytics consent" }
        check(!shows("Share usage statistics")) { "Unconfigured builds must not show an analytics switch" }
        key(Key.K, ctrl = true)
        type("usage statistics")
        advance(600)
        check(!shows("Share usage statistics")) { "Unconfigured analytics must not appear in settings search" }
        capture("unavailable")
    }
}

private fun checkAnalyticsNotice(out: File) {
    for ((name, width, height) in listOf(Triple("analytics_notice", 1440, 900), Triple("analytics_notice_narrow", 1000, 680))) {
        val storage = MemoryStorage()
        AnalyticsFixture(storage).use { fixture ->
            runCheck(out, name, fixture.app(), widthDp = width, heightDp = height) {
                advanceUntil("analytics notice in the app") { shows("Help improve Hideout") && shows("Library") }
                advance(900)
                expectAnalyticsSwitch(true)
                val chip = nodes().single { it.hasLabel("Help improve") && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot
                val minimize = nodes().single { it.hasLabel("Minimize") }.boundsInRoot
                val panel = nodes().single { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "analytics-notice" }.boundsInRoot
                check(chip.bottom <= 56f && chip.right < minimize.left) { "The helper chip belongs beside the other top-right controls" }
                check(panel.top >= chip.bottom && panel.top < 120f && panel.right > width - 200f) { "The helper panel must open below the top-right chip" }
                check(!hasText("app activity once a day")) { "Detailed collection information starts collapsed" }
                check(fixture.sent.isEmpty()) { "Showing the default-on choice must not start delivery" }
                capture("notice")
                clickLabel("Details")
                check(hasText("app activity once a day") && hasText("custom games list")) { "Details must explain collection" }
                check(fixture.sent.isEmpty()) { "Reading details must not start delivery" }
                capture("details")
                clickLabel("Less")
                clickNode(analyticsSwitch())
                if (width == 1440) key(Key.Escape) else clickLabel("Library")
                advanceUntil("dismissed helper") { !shows("Share usage") && !shows("Help improve") }
                check(fixture.analytics.choice.value == AnalyticsChoice.Disabled && fixture.sent.isEmpty()) { "Dismissal must save the displayed opt-out and remove the chip" }
                capture("dismissed")
            }
        }
        AnalyticsFixture(storage).use { reopened ->
            runCheck(out, "${name}_restart", reopened.app(route = Route.Settings)) {
                advanceUntil("saved dismissal") { shows("Share usage statistics") }
                expectAnalyticsSwitch(false, "Share usage statistics")
                check(!shows("Help improve") && !shows("Help improve Hideout")) { "A dismissed helper must not return after restart" }
                check(reopened.sent.isEmpty())
            }
        }
    }
}

private fun checkAnalyticsOnboarding(out: File) {
    AnalyticsFixture().use { fixture ->
        runCheck(out, "analytics_onboarding", fixture.app(FakeConfig(setupDone = false, game = SampleGame.NotFound))) {
            advanceUntil("game folder setup") { shows("Where is Deadlock?") }
            check(!shows("Help improve Hideout")) { "The notice must wait until setup finishes" }
            check(fixture.sent.isEmpty())
            clickLabel("Skip setup")
            advanceUntil("notice after setup") { shows("Library") && shows("Help improve Hideout") }
            check(fixture.sent.isEmpty())
        }
    }
}

private fun checkAnalyticsDecline(out: File) {
    val storage = MemoryStorage()
    AnalyticsFixture(storage).use { fixture ->
        runCheck(out, "analytics_decline", fixture.app(FakeConfig(setupDone = false)), widthDp = 1000, heightDp = 680) {
            advanceUntil("notice after automatic setup") { shows("Help improve Hideout") && shows("Library") }
            expectAnalyticsSwitch(true)
            clickNode(analyticsSwitch())
            expectAnalyticsSwitch(false)
            clickLabel("Save choice and close")
            advanceUntil("saved opt-out") { !shows("Help improve Hideout") }
            check(fixture.analytics.choice.value == AnalyticsChoice.Disabled)
            check(fixture.sent.isEmpty()) { "Declining after setup must send no events" }
            capture("declined")
        }
    }
    AnalyticsFixture(storage).use { reopened ->
        runCheck(out, "analytics_declined_restart", reopened.app(route = Route.Settings)) {
            advanceUntil("saved analytics choice") { shows("Share usage statistics") && !shows("Help improve Hideout") }
            expectAnalyticsSwitch(false, "Share usage statistics")
            check(reopened.sent.isEmpty()) { "A restart must preserve opt-out without another notice" }
        }
    }
}

private fun checkAnalyticsAccept(out: File) {
    for ((name, dismiss) in listOf("analytics_accept" to "Save choice and close", "analytics_chip_dismiss" to "Help improve")) {
        val storage = MemoryStorage()
        AnalyticsFixture(storage).use { fixture ->
            runCheck(out, name, fixture.app()) {
                advanceUntil("analytics notice") { shows("Help improve Hideout") }
                clickLabel(dismiss)
                advanceUntil("saved opt-in") { !shows("Help improve Hideout") && !shows("Help improve") }
                check(fixture.analytics.choice.value == AnalyticsChoice.Enabled)
                check(fixture.sent.any { it == UsageEvent.AppActive }) { "Saving the default choice must enable delivery" }
            }
        }
        AnalyticsFixture(storage).use { reopened ->
            runCheck(out, "${name}_restart", reopened.app(route = Route.Settings)) {
                advanceUntil("saved opt-in after restart") { shows("Share usage statistics") && !shows("Help improve Hideout") }
                expectAnalyticsSwitch(true, "Share usage statistics")
                check(!shows("Help improve")) { "A saved choice must remove the helper chip permanently" }
            }
        }
    }
}

private fun checkAnalyticsUpdateNotice(out: File) {
    AnalyticsFixture().use { fixture ->
        runCheck(out, "analytics_with_update", fixture.app(FakeConfig(appUpdate = FakeAppUpdate.JustUpdated)), widthDp = 1000, heightDp = 680) {
            advanceUntil("both notices") { shows("Help improve Hideout") && hasText("Hideout updated to") }
            advance(900)
            val notice = nodes().single { it.config.contains(SemanticsProperties.TestTag) && it.config[SemanticsProperties.TestTag] == "analytics-notice" }
            val update = nodes().single { it.config.contains(SemanticsProperties.Text) && it.config[SemanticsProperties.Text].any { text -> text.text.startsWith("Hideout updated to") } }
            check(notice.boundsInRoot.bottom < update.boundsInRoot.top) { "The top-right helper must not cover the update toast" }
            capture("both")
            clickNode(analyticsSwitch())
            clickNode(nodes().filter { it.hasLabel("Close") && it.config.contains(SemanticsActions.OnClick) }.maxBy { it.boundsInRoot.top })
            advanceUntil("update toast dismissed") { !hasText("Hideout updated to") }
            advanceUntil("helper dismissed outside") { !shows("Help improve") }
            check(fixture.analytics.choice.value == AnalyticsChoice.Disabled && fixture.sent.isEmpty()) { "An outside dismissal must preserve opt-out" }
        }
    }
    AnalyticsFixture().use { fixture ->
        runCheck(out, "analytics_update_panels", fixture.app(FakeConfig(appUpdate = FakeAppUpdate.Available))) {
            advanceUntil("analytics and update chips") { shows("Share usage") && shows("Update available · 1.4.0") }
            val updateBounds = nodes().single { it.hasLabel("Update available · 1.4.0") }.boundsInRoot
            clickNode(analyticsSwitch())
            clickLabel("Update available · 1.4.0")
            advance(900)
            check(!shows("Share usage") && !shows("Help improve") && hasText("1.4.0 · 18.4 MB · you have 1.3.2")) { "Opening an update must remove the helper and leave the update panel open" }
            check(nodes().single { it.hasLabel("Update available · 1.4.0") }.boundsInRoot == updateBounds) { "The helper disappearing must not move the update pill" }
            capture("update")
            key(Key.Escape)
            clickLabel("Update available · 1.4.0")
            advance(900)
            check(hasText("1.4.0 · 18.4 MB · you have 1.3.2") && !shows("Help improve")) { "The update pill must remain usable after the helper disappears" }
            check(fixture.analytics.choice.value == AnalyticsChoice.Disabled && fixture.sent.isEmpty())
        }
    }
}

private fun checkAnalyticsSettings(out: File) {
    val storage = MemoryStorage()
    AnalyticsFixture(storage).use { fixture ->
        runCheck(out, "analytics_settings", fixture.app(route = Route.Settings)) {
            advanceUntil("notice and settings") { shows("Help improve Hideout") && shows("Share usage statistics") }
            check(fixture.sent.isEmpty())
            expectAnalyticsSwitch(false, "Share usage statistics")
            key(Key.Escape)
            advanceUntil("settings after dismissal") { !shows("Share usage") && !shows("Help improve") }
            expectAnalyticsSwitch(true, "Share usage statistics")
            check(fixture.sent.any { it == UsageEvent.AppActive }) { "Dismissing the default choice must enable delivery" }
            capture("enabled")
            clickNode(analyticsSwitch("Share usage statistics"))
            advanceUntil("settings opt-out") { fixture.analytics.choice.value == AnalyticsChoice.Disabled }
            expectAnalyticsSwitch(false, "Share usage statistics")
            val count = fixture.sent.size
            fixture.analytics.begin(UsageOperation.ModInstall).finish(UsageOutcome.Completed)
            advance(400)
            check(fixture.sent.size == count) { "Settings opt-out must stop later collection" }
            capture("disabled")
        }
    }
    AnalyticsFixture(storage).use { reopened ->
        runBlocking { reopened.analytics.load() }
        check(reopened.analytics.choice.value == AnalyticsChoice.Disabled) { "Settings opt-out must survive restart" }
        check(reopened.sent.isEmpty())
    }
}

private fun checkAnalyticsSaveFailure(out: File) {
    val stored = MemoryStorage()
    var refuse = true
    val storage = object : CacheStorage {
        override suspend fun read(name: String): String? = stored.read(name)
        override suspend fun write(name: String, text: String) {
            check(!refuse) { "disk full" }
            stored.write(name, text)
        }
    }
    AnalyticsFixture(storage).use { fixture ->
        runCheck(out, "analytics_save_failure", fixture.app(), widthDp = 1000, heightDp = 680) {
            advanceUntil("analytics notice") { shows("Help improve Hideout") }
            clickNode(analyticsSwitch())
            clickLabel("Save choice and close")
            advanceUntil("save error") { hasText("Could not save your choice") }
            check(shows("Help improve Hideout") && shows("Library")) { "A failed save must keep the notice visible without blocking the app" }
            check(fixture.sent.isEmpty())
            capture("failed_decline")
            clickNode(analyticsSwitch())
            clickLabel("Save choice and close")
            advance(400)
            check(shows("Help improve Hideout") && hasText("Could not save your choice"))
            check(fixture.sent.isEmpty()) { "A failed enable save must never permit delivery" }
            capture("failed_enable")
            refuse = false
            clickNode(analyticsSwitch())
            clickLabel("Save choice and close")
            advanceUntil("retry saved") { !shows("Help improve Hideout") }
            check(fixture.analytics.choice.value == AnalyticsChoice.Disabled)
            check(fixture.sent.isEmpty())
        }
    }
}
