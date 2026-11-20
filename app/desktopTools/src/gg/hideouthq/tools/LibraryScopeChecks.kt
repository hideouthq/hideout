package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.MutationResult
import kotlinx.coroutines.runBlocking
import java.io.File

private const val Settle = 1_200L
private const val Lilith = "Lilith, the Hell Warden"
private const val Werecat = "Night of the Werecat"
private const val Faun = "Faun Celeste"
private const val Hudlock = "HUDlock 2.0"

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/library-scope")
    checkScope(out, 1440)
    checkScope(out, 1000)
    checkLongProfileName(out)
    println("All library scope render checks passed.")
}

private fun checkScope(out: File, width: Int) {
    val fixture = InstalledFixture()
    val container = fixture.container
    fun order() = container.modLibrary.state.value.orderView!!.sets
    fun installId(title: String) = (container.modLibrary.state.value.library as LibraryStatus.Ready).records.single { it.title == title }.installId
    runCheck(out, "library_scope_$width", fixture.state(), widthDp = width) {
        advance(Settle)
        check(shows("All mods") && shows("In Main")) { "Faun Celeste is off in Main, so the switch offers Main's own view" }
        check(shows(Faun))
        capture("all_mods")

        clickLabel("In Main")
        advance(Settle)
        check(!shows(Faun)) { "Main's view leaves out the mod it turns off" }
        check(listOf(Lilith, Werecat, Hudlock).all { shows(it) })
        check(runBlocking { container.preferences.libraryScope() } == "profile") { "the choice is kept for the next visit" }
        capture("in_main")

        clickNode(switchOf(Lilith))
        awaitChange(fixture)
        check(shows(Lilith)) { "a mod turned off in Main's view stays under the pointer" }
        check(hasText("2 of 4 on in Main"))
        capture("turned_off_stays")

        val before = order()
        check(before == listOf(Lilith, Werecat, Faun, Hudlock).map(::installId)) { "Faun Celeste sits hidden between Werecat and HUDlock, got $before" }
        openRowMenu(Hudlock)
        clickLabel("Move up")
        advance(Settle)
        check(order() == listOf(Lilith, Hudlock, Werecat, Faun).map(::installId)) { "Move up passes the hidden row and lands above Werecat, got ${order()}" }
        capture("moved_up_past_hidden")
        if (width == 1000) {
            key(Key.Escape)
            advance(500)
        }

        clickLabel("More")
        advance(Settle)
        clickLabel("Turn all off")
        awaitChange(fixture)
        check(listOf(Lilith, Werecat, Hudlock).all { shows(it) }) { "turning everything off keeps the rows listed" }
        clickLabel("All mods")
        advance(Settle)
        clickLabel("In Main")
        advance(Settle)
        check(hasText("Nothing’s on in Main")) { "an empty view of Main says so" }
        capture("nothing_on")
        clickLabel("Show all mods")
        advance(Settle)
        check(shows(Faun) && runBlocking { container.preferences.libraryScope() } == "all")
        capture("back_to_all")
    }
    println("Library scope at $width passed")
}

private fun checkLongProfileName(out: File) {
    val fixture = InstalledFixture()
    val library = fixture.container.modLibrary
    val main = library.state.value.profiles!!.active
    check(runBlocking { library.renameProfile(main, "Weekend ranked with the whole squad") } is MutationResult.Done)
    runCheck(out, "library_scope_long_name", fixture.state(), widthDp = 1000) {
        advance(Settle)
        clickLabel("In Weekend ranked with the whole squad")
        advance(Settle)
        check(!shows(Faun))
        capture("long_name")
    }
    println("Library scope with a long profile name passed")
}
