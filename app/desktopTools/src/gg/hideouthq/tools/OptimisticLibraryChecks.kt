package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.ModLibrary
import gg.hideouthq.core.library.MutationResult
import gg.hideouthq.core.library.NewProfile
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileSeed
import gg.hideouthq.core.library.relativePath
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/optimistic-library/rendered")
    for (width in listOf(1440, 1000)) {
        for (layout in listOf("List", "Cards")) checkRequestedToggle(out, width, layout)
        checkRequestedProfile(out, width)
        checkRefusedIntent(out, width, switching = false)
        checkRefusedIntent(out, width, switching = true)
    }
    println("PASS: Requested toggles, details, counts and profile selection render while persistence is held, and nothing is held meanwhile. A file changed outside is left alone and the choice stays. Play and Uninstall stay available throughout.")
}

private fun checkRequestedToggle(out: File, width: Int, layout: String) {
    val fixture = InstalledFixture(InstalledFixture.many())
    val library = fixture.container.modLibrary
    runCheck(out, "toggle_${layout.lowercase()}_$width", fixture.state(), widthDp = width) {
        advance(1000)
        clickLabel(layout)
        advance(400)
        openRowMenu("Sample mod 1")
        key(Key.Escape)
        advance(400)
        if (width == 1000) {
            key(Key.Escape)
            advance(400)
        }
        val gate = CompletableDeferred<Unit>()
        fixture.processes.gate = gate
        try {
            capture("before")
            clickLabel("Turn Sample mod 1 on or off")
            advance(200)
            if (width == 1000) {
                openRowMenu("Sample mod 1")
                key(Key.Escape)
                advance(400)
            }
            capture("pending")
            check(fixture.record("Sample mod 1")!!.enabled) { "The process gate must keep the applied mod enabled" }
            check(nodes().single { it.hasLabel("Turn Sample mod 1 on or off") && it.config.contains(SemanticsProperties.ToggleableState) }
                .config[SemanticsProperties.ToggleableState] == ToggleableState.Off)
            check(hasText("Off in Main")) { "Details must show requested membership before persistence completes" }
            check(counts(width, "Main", 7)) { "The count must follow the request before persistence completes" }
            check(nodes().filter { it.hasLabel("Turn Sample mod 2 on or off") }.none { it.config.contains(SemanticsProperties.Disabled) }) {
                "An unrelated toggle must stay available"
            }
            check(width <= 1000 || hasText("Applying…")) { "The open sidebar says the files are still following; on the rail the switch itself has moved" }
            checkNothingHeld(library)
            if (width == 1000) {
                key(Key.Escape)
                advance(400)
            }
            clickLabel("Turn Sample mod 1 on or off")
            clickLabel("Turn Sample mod 1 on or off")
            check(counts(width, "Main", 7))
        } finally {
            fixture.processes.gate = null
            gate.complete(Unit)
        }
        advance(1200)
        check(!fixture.record("Sample mod 1")!!.enabled)
        check(counts(width, "Main", 7))
        check(nodes().none { it.hasLabel("Applying…") })
        capture("settled")
    }
}

private fun checkRequestedProfile(out: File, width: Int) {
    val fixture = InstalledFixture(InstalledFixture.many())
    val library = fixture.container.modLibrary
    runCheck(out, "profile_$width", fixture.state(), widthDp = width) {
        advance(1000)
        val empty = runBlocking {
            val created = library.createProfile(NewProfile("Empty", ProfileColour.Blue, ProfileSeed.Empty, false))
            check(created is MutationResult.Done)
            created.value
        }
        advance(600)
        val gate = CompletableDeferred<Unit>()
        fixture.processes.gate = gate
        try {
            capture("before")
            clickLabel("Switch to profile Empty")
            advance(200)
            capture("pending")
            check(nodes().any { it.hasLabel("Active profile: Empty") }) {
                "Requested profile must render before the blocked file transaction finishes"
            }
            check(counts(width, "Empty", 0)) { "The count must follow the requested profile" }
            val toggles = nodes().filter { node ->
                node.config.contains(SemanticsProperties.ToggleableState) &&
                    node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
                        .orEmpty().any { it.startsWith("Turn Sample mod ") }
            }
            check(toggles.size == 8) { "The profile check must inspect all eight row toggles" }
            check(toggles.all { it.config[SemanticsProperties.ToggleableState] == ToggleableState.Off })
            check(width <= 1000 || hasText("Applying…")) { "The open sidebar says the files are still following; on the rail the profile itself has moved" }
            check(!hasText("Undo")) { "A switch shows no notice: the sidebar marks the profile" }
            check(library.state.value.profiles!!.active == empty) { "The choice is saved at once; the files follow" }
            check((library.state.value.library as LibraryStatus.Ready).records.all { it.enabled })
            checkNothingHeld(library)
            val browse = nodes().single { it.hasLabel("Browse") && it.config.contains(SemanticsActions.OnClick) }
            check(!browse.config.contains(SemanticsProperties.Disabled))
        } finally {
            fixture.processes.gate = null
            gate.complete(Unit)
        }
        advance(1200)
        check(library.state.value.profiles!!.active == empty)
        check((library.state.value.library as LibraryStatus.Ready).records.none { it.enabled })
        check(!hasText("Undo")) { "nor once it has applied" }
        check(nodes().none { it.hasLabel("Applying…") })
        capture("settled")
    }
}

private fun checkRefusedIntent(out: File, width: Int, switching: Boolean) {
    val fixture = InstalledFixture(InstalledFixture.many())
    val library = fixture.container.modLibrary
    runCheck(out, "refused_${if (switching) "profile" else "toggle"}_$width", fixture.state(), widthDp = width) {
        advance(1000)
        val main = checkNotNull(library.state.value.profiles).active
        val empty = if (switching) runBlocking {
            val result = library.createProfile(NewProfile("Empty", ProfileColour.Blue, ProfileSeed.Empty, false))
            check(result is MutationResult.Done)
            result.value
        } else null
        advance(400)
        val record = checkNotNull(fixture.record("Sample mod 1"))
        val file = record.files.single()
        val path = file.relativePath(record)
        val gate = CompletableDeferred<Unit>()
        fixture.processes.gate = gate
        try {
            if (switching) clickLabel("Switch to profile Empty") else clickLabel("Turn Sample mod 1 on or off")
            advance(200)
            check(if (switching) counts(width, "Empty", 0) else counts(width, "Main", 7))
            check(width <= 1000 || hasText("Applying…"))
            fixture.files.putFile(record.gameRoot, path, file.sizeBytes, "f".repeat(64))
        } finally {
            fixture.processes.gate = null
            gate.complete(Unit)
        }
        advance(1200)
        val state = library.state.value
        val records = (state.library as LibraryStatus.Ready).records
        check(state.profiles!!.active == (empty ?: main)) { "The asked profile stays active" }
        check(state.profiles!!.activeProfile.enabled.size == if (switching) 0 else 7) { "The choice stays as asked: ${state.choiceSync}" }
        check(width <= 1000 || if (switching) counts(width, "Empty", 0) else counts(width, "Main", 7)) { "The sidebar counts the profile's choice" }
        check(switching || hasText("8 of 8 on in Main")) { "The held mod's row shows where its files are: in the game" }
        check(hasText("File changed")) { "The row names the file Hideout left alone: ${state.choiceSync} ${records.map { it.enabled }}" }
        check(nodes().none { it.hasLabel("Applying…") })
        checkNothingHeld(library)
        capture("refused")
    }
}

private fun Script.counts(width: Int, profile: String, on: Int): Boolean =
    if (width > 1000) hasText("$profile · $on mods on") else hasText("$on of 8 on in $profile")

private fun Script.checkNothingHeld(library: ModLibrary) {
    check(library.state.value.canPlay) { "Play must not wait for a change Hideout is still applying" }
    fun enabled(label: String) = nodes().filter { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) }.none { it.config.contains(SemanticsProperties.Disabled) }
    check(enabled("Play modded") && enabled("Play vanilla")) { "Play stays enabled while a change applies" }
    check(enabled("Uninstall")) { "Uninstall stays available while a change applies" }
}
