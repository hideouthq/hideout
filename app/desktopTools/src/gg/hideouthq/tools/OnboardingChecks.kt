@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.ForeignSeed
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.Manager
import gg.hideouthq.core.migration.ImportPhase
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.tools.preview.advanceUntil
import java.io.File

private class OnboardingCheck(val name: String, val run: (File) -> Unit)

fun main(args: Array<String>) {
    val checks = listOf(
        OnboardingCheck("onboarding_none", ::checkNothingToAsk),
        OnboardingCheck("onboarding_game", ::checkGameMissing),
        OnboardingCheck("onboarding_import", ::checkImport),
        OnboardingCheck("onboarding_start_fresh", ::checkStartFresh),
        OnboardingCheck("library_import", ::checkLibraryImport),
        OnboardingCheck("library_import_waits", ::checkImportWaits),
    )
    val selected = selectTargets(args.getOrNull(1), checks.map { it.name })
    val outDir = File(args.firstOrNull() ?: "build/screenshots/onboarding-checks")
    checks.filter { it.name in selected }.forEach { check ->
        check.run(outDir)
        println("Onboarding check '${check.name}' passed")
    }
    println("All ${selected.size} onboarding checks passed.")
}

private fun Script.expect(label: String, why: String) = check(shows(label)) { why }

private fun Script.expectLine(fragment: String, why: String) = check(hasText(fragment)) { why }

private fun checkNothingToAsk(outDir: File) {
    val container = OnboardingFixture.container()
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "onboarding_none", state) {
        advanceUntil("the shell") { shows("Library") }
        check(!hasText("Where is Deadlock?") && !hasText("Bring your mods over")) { "setup asks nothing it can settle itself" }
        check(container.preferences.setupDone.value == true) { "setup is finished without a click" }
        capture("shell")
    }
}

private fun checkGameMissing(outDir: File) {
    val container = OnboardingFixture.container(SampleGame.NotFound)
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "onboarding_game", state) {
        advanceUntil("the question") { hasText("Where is Deadlock?") }
        advance(StepMillis)
        expect("Choose folder", "the answer is the page's one button")
        check(!shows("Library")) { "the shell stays hidden while setup is unfinished" }
        check(!shows("Detect again") && !hasText("gameinfo.gi")) { "the page asks for the folder and explains nothing else" }
        capture("game")
        steps("Skip setup")
        advanceUntil("the shell") { shows("Library") }
        check(container.preferences.setupDone.value == true) { "Skip setup finishes setup" }
        capture("shell")
    }
}

private fun checkImport(outDir: File) {
    val files = sampleGameFiles(FakeConfig(seed = LibrarySeed.Empty, foreign = ForeignSeed.Dmm))
    val container = OnboardingFixture.container(foreign = ForeignSeed.Dmm, files = files)
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "onboarding_import", state) {
        toImport()
        expectLine("From Deadlock Mod Manager", "the eyebrow names the source")
        expectLine("Bring your mods over", "setup opens on the offer, with no page before it")
        listOf("Mods", "Profiles", "Crosshair").forEach { checkTicked(it, true) }
        check(nodes().none { it.hasLabel("Autoexec") }) { "a source with no commands has no Autoexec row" }
        check(!hasText("Found on this PC") && !shows("Next") && !shows("Back")) { "one page: no source list, no steps" }
        capture("import")
        steps("Import")
        advanceUntil("the import and the shell") { container.preferences.setupDone.value == true && shows("Library") }
        check(files.bytes(SampleGameRoot, "game/citadel/addons/.dmm.json") == null) { "the manager's manifest is taken over" }
        val records = (container.modLibrary.state.value.library as LibraryStatus.Ready).records
        check(records.map { it.title }.toSet() == setOf("Neon Haze", "Iron Sights", "Ranked HUD")) { "the manager's mods are Hideout's: ${records.map { it.title }}" }
        check(container.migration.state.value.run == null) { "the ended import is put away" }
        advanceUntil("Library, where the mods are") { shows("Neon Haze") }
        expect("Ranked", "the source's profile is in the sidebar")
        capture("library")
    }
}

private fun checkStartFresh(outDir: File) {
    val container = OnboardingFixture.container(foreign = ForeignSeed.Dmm)
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "onboarding_start_fresh", state) {
        toImport()
        steps("Start fresh")
        advanceUntil("the shell") { shows("Library") }
        check(container.preferences.setupDone.value == true) { "Start fresh finishes setup" }
        check((container.modLibrary.state.value.library as LibraryStatus.Ready).records.isEmpty()) { "nothing was brought over" }
        capture("shell")
    }
}

private fun checkLibraryImport(outDir: File) {
    val config = FakeConfig(foreign = ForeignSeed.Dmm)
    val container = AppContainer.fake(config, files = sampleGameFiles(config))
    val state = HideoutAppState(container, Route.Library)
    runCheck(outDir, "library_import", state) {
        advance(600)
        steps("More", "Import from a manager…")
        advanceUntil("what comes along") { hasText("With their on/off state and load order") }
        check(!shows("Start fresh") && !shows("Next") && !hasText("Where are your mods?")) { "the dialog is one page, open on what the manager brings" }
        capture("import")
        steps("Another source…")
        expect("An export file", "the other sources are a click away")
        check(nodes().none { it.hasLabel("Not found on this PC") }) { "a source that cannot bring anything is not listed" }
        capture("sources")
        steps("Deadlock Mod Manager")
        advanceUntil("what comes along") { hasText("With their on/off state and load order") }
        steps("Import")
        advanceUntil("the import ending and its dialog closing") { container.migration.state.value.run == null && !hasText("Bring your mods over") }
        advance(StepMillis)
        expect("Neon Haze", "the imported mods are Library's rows")
        expect("Ranked", "the source's profile is in the sidebar")
        capture("library")
    }
}

private fun checkImportWaits(outDir: File) {
    val config = FakeConfig(seed = LibrarySeed.Empty, foreign = ForeignSeed.Dmm)
    val processes = FakeProcesses(ProcessScan.Seen(game = true))
    val container = AppContainer.fake(config, files = sampleGameFiles(config), processes = processes)
    val state = HideoutAppState(container, Route.Library)
    runCheck(outDir, "library_import_waits", state) {
        advance(600)
        steps("Import from a manager…")
        advanceUntil("what comes along") { hasText("With their on/off state and load order") }
        steps("Import")
        advanceUntil("the import waiting") { container.migration.state.value.run?.phase is ImportPhase.Waiting }
        advance(StepMillis)
        check(!hasText("Bring your mods over")) { "the dialog closes itself once the import waits" }
        expect("Close Deadlock to bring your mods over", "a waiting import says what it needs, in place of the empty library")
        check(!hasText("Your library is empty")) { "Library does not call itself empty while mods are coming" }
        expectLine("Bringing 3 mods over from Deadlock Mod Manager", "the banner says what comes and from where")
        expectLine("Waiting for Deadlock to close", "the banner says what it waits for")
        capture("waiting")
        processes.scan = ProcessScan.Seen()
        advanceUntil("the import", maxMillis = 60_000) { container.migration.state.value.run?.phase is ImportPhase.Finished }
        advance(StepMillis)
        expect("Neon Haze", "the imported mods are Library's rows")
        check(!hasText("Bringing 3 mods over")) { "the banner goes once the import ran" }
        capture("done")
    }
}
