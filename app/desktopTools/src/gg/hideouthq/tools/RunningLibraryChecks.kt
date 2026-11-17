package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.library.ChoiceSync
import gg.hideouthq.core.library.GameActivity
import gg.hideouthq.core.library.ProfileColour
import gg.hideouthq.core.library.ProfileId
import gg.hideouthq.core.library.SetFolder
import gg.hideouthq.core.navigation.Route
import java.io.File

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/saved-profile-edits")
    listOf(1440, 1000).forEach { width ->
        val base = sampleLibraryManifest()
        val original = base.records
        val files = InstalledFixture(original).files
        val store = InMemoryLibraryStore(base)
        val processes = FakeProcesses(ProcessScan.Seen(game = true))
        val container = AppContainer.fake(files = files, libraryStore = store, processes = processes)
        val info = files.gameInfo(SampleGameRoot)!!.copyOf()
        val addons = files.addonsFiles(SampleGameRoot).toMap()
        val (first, second) = original.filter { it.enabled }.sortedBy { it.numbers.single() }
        val state = HideoutAppState(container, Route.Library)
        runCheck(out, "saved_edits_$width", state, widthDp = width, heightDp = 900) {
            fun switchNode() = nodes().single {
                it.hasLabel("Turn ${first.title} on or off") && it.config.contains(SemanticsProperties.ToggleableState)
            }
            fun untouched() {
                check(store.manifest!!.records == original) { "Saved profile edits must not change applied records while Deadlock may run" }
                check(files.addonsFiles(SampleGameRoot) == addons && files.gameInfo(SampleGameRoot)!!.contentEquals(info)) {
                    "Saved edits must leave the running game's files unchanged"
                }
                check(files.linkedPaths.isEmpty() && files.removedPaths.isEmpty()) { "No file mutation may run before Deadlock is confirmed closed" }
                check(store.manifest!!.pending == null) { "Saving choices must not create a physical write journal" }
            }
            advance(900)
            check(!switchNode().config.contains(SemanticsProperties.Disabled)) { "Running-game toggles must remain editable" }
            check(nodes().filter { it.hasLabel("Add from file") }.all { !it.config.contains(SemanticsProperties.Disabled) }) { "Adding files must remain available during play" }
            capture("running_before")
            val top = switchNode().boundsInRoot.top
            clickNode(switchNode())
            advance(700)
            check(switchNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off)
            check(switchNode().boundsInRoot.top == top) { "The note that the change waits for Deadlock must not move the list" }
            untouched()
            val handle = nodes().single { it.hasLabel("Reorder ${first.title}") }
            check(!handle.config.contains(SemanticsProperties.Disabled)) { "Running-game load order must remain editable" }
            moveToDp(handle.boundsInRoot.center.x / scale, handle.boundsInRoot.center.y / scale)
            dragByDp(0f, 58f, steps = 8)
            release()
            advance(700)
            check(nodes().first { it.hasLabel(second.title) }.boundsInRoot.top < nodes().first { it.hasLabel(first.title) }.boundsInRoot.top)
            untouched()
            check(hasText("Saved: applies when Deadlock closes"))
            capture("running_saved_list")
            clickLabel("Cards")
            advance(700)
            check(!switchNode().config.contains(SemanticsProperties.Disabled))
            clickNode(switchNode())
            advance(700)
            check(switchNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On)
            clickNode(switchNode())
            advance(700)
            check(switchNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off)
            untouched()
            capture("running_saved_cards")
            processes.scan = ProcessScan.Unknown("process list unavailable")
            val unknownDeadline = System.nanoTime() + 5_000_000_000L
            while (container.modLibrary.state.value.deadlock != GameActivity.Unknown && System.nanoTime() < unknownDeadline) {
                runUiQueue(100)
                advance(50)
            }
            check(container.modLibrary.state.value.deadlock == GameActivity.Unknown)
            untouched()
            capture("unknown_still_saved")
            processes.scan = ProcessScan.Seen()
            val deadline = System.nanoTime() + 8_000_000_000L
            while (!container.modLibrary.state.value.canModify && System.nanoTime() < deadline) {
                runUiQueue(100)
                advance(50)
            }
            advance(500)
            val applied = store.manifest!!.records
            check(!applied.first { it.installId == first.installId }.enabled) { "The saved membership must apply after Deadlock closes" }
            check(store.manifest!!.profiles.activeProfile.orders.getValue(SampleGameRoot).take(2) == listOf(second.installId, first.installId)) { "The saved order must apply after Deadlock closes" }
            check(applied.first { it.installId == second.installId }.numbers == second.numbers) { "${second.title} keeps its number, since the mod dragged past it is off" }
            check(store.manifest!!.pending == null)
            check(state.navigator.current.route == Route.Library)
            capture("closed_applied")
        }
    }
    listOf(1440, 1000).forEach { width ->
        checkRunningMenuMoveActions(out, width, cards = false)
        checkRunningMenuMoveActions(out, width, cards = true)
        checkRunningSwitch(out, width)
    }
    checkRunningOffUninstall(out)
    checkRunningDetailsFiles(out)
    println("PASS: Running toggles and drags save choices without changing files. Unknown process state keeps them saved. Closing Deadlock applies the latest choices. Row-menu moves and Change files stay usable while Deadlock runs. A switch to a profile with another order while Deadlock runs shows it at once and touches no file. A mod that is off uninstalls while Deadlock runs, leaving addons and gameinfo.gi as they were; one that is on waits. Details says that a mod turned on or off while Deadlock runs moves when it closes.")
}

private fun checkRunningDetailsFiles(out: File) {
    val base = sampleLibraryManifest()
    val off = base.records.single { !it.enabled }
    val on = base.records.first { it.enabled }
    val files = InstalledFixture(base.records).files
    val processes = FakeProcesses(ProcessScan.Seen(game = true))
    val container = AppContainer.fake(files = files, libraryStore = InMemoryLibraryStore(base), processes = processes)
    runCheck(out, "running_details_files", HideoutAppState(container, Route.Library), widthDp = 1440, heightDp = 900) {
        fun turn(title: String) {
            clickNode(nodes().single { it.hasLabel("Turn $title on or off") && it.config.contains(SemanticsProperties.ToggleableState) })
            advance(700)
            clickLabel(title)
            advance(600)
        }
        advance(900)
        turn(off.title)
        check(hasText("On in Main") && hasText("Goes into the game when Deadlock closes.") && !hasText("Not in the game while it’s off")) { "${off.title}, turned on, goes into the game when Deadlock closes" }
        capture("running_details_going_in")
        turn(on.title)
        check(hasText("Off in Main") && hasText("Files in the game") && hasText("Leaves the game when Deadlock closes.")) { "${on.title}, turned off, stays in the game until Deadlock closes" }
        capture("running_details_going_out")
        processes.scan = ProcessScan.Seen()
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!container.modLibrary.state.value.canModify && System.nanoTime() < deadline) {
            runUiQueue(100)
            advance(50)
        }
        advance(500)
        check(hasText("Not in the game while it’s off") && !hasText("Leaves the game when Deadlock closes.")) { "once Deadlock closes, ${on.title} is out of the game" }
        clickLabel(off.title)
        advance(600)
        check(hasText("Files in the game") && !hasText("Goes into the game when Deadlock closes.")) { "and ${off.title} is in it" }
        capture("closed_details_in_game")
    }
}

private fun checkRunningOffUninstall(out: File) {
    val base = sampleLibraryManifest()
    val off = base.records.single { !it.enabled }
    val on = base.records.first { it.enabled }
    val files = InstalledFixture(base.records).files
    val store = InMemoryLibraryStore(base)
    val container = AppContainer.fake(files = files, libraryStore = store, processes = FakeProcesses(ProcessScan.Seen(game = true)))
    val addons = files.addonsFiles(SampleGameRoot).toMap()
    val info = files.gameInfo(SampleGameRoot)!!.copyOf()
    runCheck(out, "running_off_uninstall", HideoutAppState(container, Route.Library), widthDp = 1440, heightDp = 900) {
        advance(900)
        clickLabel(on.title)
        advance(600)
        check(!nodes().single { it.hasLabel("Uninstall") && it.config.contains(SemanticsActions.OnClick) }.config.contains(SemanticsProperties.Disabled)) { "${on.title} is on, and its Uninstall still works" }
        clickLabel(off.title)
        advance(600)
        clickLabel("Uninstall")
        advance(1_200)
        check(hasText("Undo") && !hasText("Close Deadlock")) { "${off.title} goes at once with an Undo" }
        capture("running_off_uninstall_undo")
        val deadline = System.nanoTime() + 15_000_000_000L
        while (store.manifest!!.records.any { it.installId == off.installId } && System.nanoTime() < deadline) {
            runUiQueue(100)
            advance(50)
        }
        advance(600)
        check(store.manifest!!.records.none { it.installId == off.installId }) { "${off.title} is uninstalled while Deadlock runs" }
        check(files.filesIn(SampleGameRoot, SetFolder.Off(off.modId)).isEmpty()) { "Its off folder is emptied" }
        check(files.addonsFiles(SampleGameRoot) == addons && files.gameInfo(SampleGameRoot)!!.contentEquals(info)) { "addons and gameinfo.gi stay exactly as they were" }
        check(container.modLibrary.state.value.deadlock == GameActivity.Running)
        capture("running_off_uninstalled")
    }
}

private fun checkRunningSwitch(out: File, width: Int) {
    val base = sampleLibraryManifest()
    val main = base.profiles.activeProfile
    val sorted = main.orders.getValue(SampleGameRoot).map { id -> base.records.single { it.installId == id } }
    val reversed = main.copy(id = ProfileId("reversed"), name = "Reversed", colour = ProfileColour.Rose, orders = main.orders.mapValues { (_, ids) -> ids.reversed() })
    val files = InstalledFixture(base.records).files
    val store = InMemoryLibraryStore(base.copy(profiles = base.profiles.copy(profiles = base.profiles.profiles + reversed)))
    val processes = FakeProcesses(ProcessScan.Seen(game = true))
    val container = AppContainer.fake(files = files, libraryStore = store, processes = processes)
    val addons = files.addonsFiles(SampleGameRoot).toMap()
    val info = files.gameInfo(SampleGameRoot)!!.copyOf()
    runCheck(out, "running_switch_$width", HideoutAppState(container, Route.Library), widthDp = width, heightDp = 900) {
        advance(900)
        clickLabel("Switch to profile Reversed")
        advance(700)
        check(container.modLibrary.state.value.orderView!!.sets == sorted.map { it.installId }.reversed()) { "The switched-to profile's order shows at once" }
        check(nodes().first { it.hasLabel(sorted.last().title) }.boundsInRoot.top < nodes().first { it.hasLabel(sorted.first().title) }.boundsInRoot.top)
        check(container.modLibrary.state.value.choiceSync == ChoiceSync.Waiting(GameActivity.Running))
        check(store.manifest!!.records == base.records && store.manifest!!.pending == null) { "A switch while Deadlock runs must not change applied records" }
        check(files.addonsFiles(SampleGameRoot) == addons && files.gameInfo(SampleGameRoot)!!.contentEquals(info)) { "A switch while Deadlock runs must leave its files unchanged" }
        check(files.linkedPaths.isEmpty() && files.removedPaths.isEmpty())
        capture("running_switch_saved")
    }
}

private fun checkRunningMenuMoveActions(out: File, width: Int, cards: Boolean) {
    val base = sampleLibraryManifest()
    val files = InstalledFixture(base.records).files
    val store = InMemoryLibraryStore(base)
    val processes = FakeProcesses(ProcessScan.Seen(game = true))
    val container = AppContainer.fake(files = files, libraryStore = store, processes = processes)
    val sorted = base.records.filter { it.enabled }.sortedBy { it.numbers.single() }
    val first = sorted[0]
    val second = sorted[1]
    val addons = files.addonsFiles(SampleGameRoot).toMap()
    val info = files.gameInfo(SampleGameRoot)!!.copyOf()
    runCheck(out, "running_menu_move_${width}_${if (cards) "cards" else "list"}", HideoutAppState(container, Route.Library), widthDp = width, heightDp = 900) {
        advance(900)
        if (cards) {
            clickLabel("Cards")
            advance(700)
        }
        openRowMenu(second.title)
        check(!nodes().single { it.hasLabel("Change files…") }.config.contains(SemanticsProperties.Disabled)) {
            "Change files must stay available while Deadlock runs, the same as any other install: it waits for closure before touching files"
        }
        val moveUp = "Move up"
        val moveDown = "Move down"
        check(nodes().any { it.hasLabel(moveUp) } && nodes().any { it.hasLabel(moveDown) }) { "Move actions must be offered from the row menu" }
        check(!nodes().single { it.hasLabel(moveUp) }.config.contains(SemanticsProperties.Disabled)) { "Running-game load order must remain editable from the menu too" }
        capture("running_menu_open")
        clickLabel(moveUp)
        advance(700)
        val secondBounds = nodes().first { it.hasLabel(second.title) }.boundsInRoot
        val firstBounds = nodes().first { it.hasLabel(first.title) }.boundsInRoot
        check(secondBounds.top < firstBounds.top || (cards && secondBounds.top == firstBounds.top && secondBounds.left < firstBounds.left)) {
            "Move up must take effect at once"
        }
        check(container.modLibrary.state.value.orderView!!.sets.take(2) == listOf(second.installId, first.installId))
        key(Key.Escape)
        advance(400)
        openRowMenu(second.title)
        clickLabel(moveDown)
        advance(700)
        check(container.modLibrary.state.value.orderView!!.sets.take(2) == listOf(first.installId, second.installId))
        key(Key.Escape)
        advance(400)
        openRowMenu(second.title)
        clickLabel("Move to position…")
        advance(400)
        repeat(3) { key(Key.Backspace) }
        type("1")
        key(Key.Enter)
        advance(700)
        check(container.modLibrary.state.value.orderView!!.sets.take(2) == listOf(second.installId, first.installId))
        check(hasText("Saved: applies when Deadlock closes"))
        check(store.manifest!!.records == base.records) { "A saved menu move must not change applied records while Deadlock may run" }
        check(files.addonsFiles(SampleGameRoot) == addons && files.gameInfo(SampleGameRoot)!!.contentEquals(info)) {
            "A saved menu move must leave the running game's files unchanged"
        }
        check(files.linkedPaths.isEmpty() && files.removedPaths.isEmpty())
        capture("running_menu_move_applied")
    }
}
