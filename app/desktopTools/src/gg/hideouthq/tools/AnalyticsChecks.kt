@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
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
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.MemoryStorage
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
    fun app(setupDone: Boolean = true) = HideoutAppState(
        AppContainer.fake(FakeConfig(setupDone = setupDone), analytics = analytics), Route.Settings,
    ).apply { settingsTab = SettingsTab.About }
    override fun close() { analytics.close(); scope.cancel() }
}

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/analytics-checks")
    checkAnalyticsUnavailable(out)
    checkAnalyticsDecline(out)
    checkAnalyticsSettings(out)
    checkAnalyticsSaveFailure(out)
    println("All analytics availability, disclosure, settings, persistence, and save failure checks passed.")
}

private fun Script.analyticsSwitch(): SemanticsNode = nodes().single {
    it.hasLabel("Share usage statistics") && it.config.contains(SemanticsProperties.ToggleableState)
}

private fun Script.expectAnalyticsSwitch(on: Boolean) {
    check(analyticsSwitch().config[SemanticsProperties.ToggleableState] == if (on) ToggleableState.On else ToggleableState.Off) {
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

private fun checkAnalyticsDecline(out: File) {
    val storage = MemoryStorage()
    AnalyticsFixture(storage).use { fixture ->
        runCheck(out, "analytics_decline", fixture.app(setupDone = false), widthDp = 1000, heightDp = 680) {
            advanceUntil("analytics disclosure") { shows("Help improve Hideout") }
            expectAnalyticsSwitch(true)
            check(fixture.sent.isEmpty()) { "The default-on switch must not send before Continue" }
            capture("disclosure")
            clickNode(analyticsSwitch())
            expectAnalyticsSwitch(false)
            clickLabel("Continue")
            advanceUntil("the app after declining") { !shows("Help improve Hideout") && shows("Library") }
            check(fixture.analytics.choice.value == AnalyticsChoice.Disabled)
            check(fixture.sent.isEmpty()) { "Declining before setup must send no events" }
            capture("declined")
        }
    }
    AnalyticsFixture(storage).use { reopened ->
        runCheck(out, "analytics_declined_restart", reopened.app()) {
            advanceUntil("saved analytics choice") { shows("Share usage statistics") && !shows("Help improve Hideout") }
            expectAnalyticsSwitch(false)
            check(reopened.sent.isEmpty()) { "A restart must preserve opt-out without another prompt" }
        }
    }
}

private fun checkAnalyticsSettings(out: File) {
    val storage = MemoryStorage()
    AnalyticsFixture(storage).use { fixture ->
        runCheck(out, "analytics_settings", fixture.app()) {
            advanceUntil("disclosure for an existing installation") { shows("Help improve Hideout") }
            check(fixture.sent.isEmpty())
            clickLabel("Continue")
            advanceUntil("analytics enabled in settings") { !shows("Help improve Hideout") && shows("Share usage statistics") }
            expectAnalyticsSwitch(true)
            check(fixture.sent.any { it == UsageEvent.AppActive }) { "Acceptance must enable delivery" }
            capture("enabled")
            clickNode(analyticsSwitch())
            advanceUntil("settings opt-out") { fixture.analytics.choice.value == AnalyticsChoice.Disabled }
            expectAnalyticsSwitch(false)
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
    val refusing = object : CacheStorage {
        override suspend fun read(name: String): String? = null
        override suspend fun write(name: String, text: String) { error("disk full") }
    }
    AnalyticsFixture(refusing).use { fixture ->
        runCheck(out, "analytics_save_failure", fixture.app(), widthDp = 1000, heightDp = 680) {
            advanceUntil("analytics disclosure") { shows("Help improve Hideout") }
            clickNode(analyticsSwitch())
            clickLabel("Continue")
            advanceUntil("save error") { hasText("Could not save your choice") }
            check(shows("Help improve Hideout")) { "A failed save must keep the disclosure visible" }
            check(fixture.sent.isEmpty())
            capture("failed_decline")
            clickNode(analyticsSwitch())
            clickLabel("Continue")
            advance(400)
            check(shows("Help improve Hideout") && hasText("Could not save your choice"))
            check(fixture.sent.isEmpty()) { "A failed enable save must never permit delivery" }
            capture("failed_enable")
        }
    }
}
