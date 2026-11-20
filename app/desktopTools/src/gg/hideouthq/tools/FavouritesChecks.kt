package gg.hideouthq.tools

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.navigation.Route
import java.io.File

private const val Settle = 1_200L
private const val Lilith = "Lilith, the Hell Warden"

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/screenshots/favourites")
    checkModPageStar(out)
    checkBrowseStars(out)
    checkLibraryFavourites(out)
    println("All favourites render checks passed.")
}

private fun Script.star(label: String) = nodes().single { it.hasLabel(label) && it.config.contains(SemanticsProperties.ToggleableState) }

private fun checkModPageStar(out: File) {
    val container = AppContainer.fake()
    runCheck(out, "favourites_mod_page", HideoutAppState(container, Route.ModDetail("lilith"))) {
        advance(Settle)
        check(star("Favourite").config[SemanticsProperties.ToggleableState] == ToggleableState.Off)
        capture("not_starred")
        clickNode(star("Favourite"))
        advance(Settle)
        check(container.preferences.favourites.value == setOf("lilith")) { "the star saves the mod by its id" }
        check(star("Favourite").config[SemanticsProperties.ToggleableState] == ToggleableState.On)
        capture("starred")
    }
    println("Favourites on the mod page passed")
}

private fun checkBrowseStars(out: File) {
    val container = AppContainer.fake()
    val state = HideoutAppState(container, Route.Browse())
    runCheck(out, "favourites_browse", state) {
        advance(Settle)
        check(!shows("Favourites")) { "the panel offers favourites only once there is one" }
        val card = nodes().first { it.hasLabel(Lilith) && it.config.contains(SemanticsActions.OnClick) }
        moveTo(card)
        advance(400)
        capture("card_hovered")
        clickNode(star("Favourite $Lilith"))
        check(container.preferences.favourites.value == setOf("lilith"))
        check(state.navigator.current.route is Route.Browse) { "the star does not open the mod" }
        moveToDp(4f, 4f)
        advance(600)
        capture("card_starred")
        clickLabel("Favourites")
        advance(Settle)
        check(shows(Lilith) && !shows("Night of the Werecat")) { "Favourites lists only the starred mod" }
        capture("favourites_only")
    }
    println("Favourites in Browse passed")
}

private fun checkLibraryFavourites(out: File) {
    val fixture = InstalledFixture()
    runCheck(out, "favourites_library", fixture.state()) {
        advance(Settle)
        check(!shows("Favourites"))
        openRowMenu(Lilith)
        clickLabel("Favourite")
        advance(Settle)
        check(fixture.container.preferences.favourites.value == setOf("mod-551201")) { "a row is starred by its page's id" }
        check(shows("Favourites")) { "the pill appears once a mod is a favourite" }
        capture("favourites_pill")
        clickLabel("Favourites")
        advance(Settle)
        check(shows(Lilith) && !shows("Night of the Werecat"))
        capture("favourites_only")
        openRowMenu(Lilith)
        check(shows("Remove from favourites"))
        capture("row_menu")
    }
    println("Favourites in Library passed")
}
