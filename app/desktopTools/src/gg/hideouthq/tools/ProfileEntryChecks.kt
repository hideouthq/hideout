@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.tools.preview.advanceUntil
import java.io.File

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/profile-entry")
    for (width in listOf(1440, 1000)) checkProfileEntry(out, width)
    println("All profile entry, import handoff, focus, cancellation and creation checks passed.")
}

private fun checkProfileEntry(out: File, width: Int) {
    val container = AppContainer.fake()
    val state = HideoutAppState(container, Route.Library)
    runCheck(out, "profile_entry_$width", state, widthDp = width, heightDp = if (width == 1000) 680 else 900) {
        advanceUntil("profile sidebar") { shows("New profile") }
        check(!shows("Import profile")) { "The sidebar must have one profile entry action" }
        capture("sidebar")
        clickLabel("New profile")
        advanceUntil("new profile dialog") { shows("Import from link") && shows("Create profile") }
        advance(900)
        capture("new_profile")
        check(nodes().single { it.hasLabel("Create profile") }.config.contains(SemanticsProperties.Disabled)) { "An empty name must not create a profile" }
        type("Main")
        advance(400)
        check(hasText("already a profile called")) { "Duplicate names must still show their validation" }
        clickLabel("Import from link")
        advanceUntil("profile import field") { shows("Profile link or code") }
        advance(900)
        check(!shows("Import from link") && !shows("Create profile")) { "Import must replace the new-profile dialog" }
        capture("import_profile")
        type("K7Q2M9XD")
        check(fieldText("Profile link or code") == "K7Q2M9XD") { "Keyboard input must land in the import field" }
        key(Key.Escape)
        advanceUntil("closed import") { !state.modal.isOpen }
        check(!shows("Import from link") && !shows("Create profile") && !shows("Profile link or code")) { "Cancelling import must not restore the old profile dialog" }
        check(container.modLibrary.state.value.profiles!!.profiles.size == 1) { "Import cancellation must not create the abandoned draft" }
        clickLabel("New profile")
        advanceUntil("fresh profile dialog") { shows("Import from link") && shows("Create profile") }
        advance(900)
        check(fieldText("Profile name").isEmpty()) { "The abandoned draft must not return" }
        type("Practice")
        clickLabel("Create profile")
        advanceUntil("created profile") { container.modLibrary.state.value.profiles?.activeProfile?.name == "Practice" && !state.modal.isOpen }
        check(container.modLibrary.state.value.profiles!!.profiles.size == 2)
        check(!shows("Import from link") && !shows("Create profile"))
        capture("created")
    }
}
