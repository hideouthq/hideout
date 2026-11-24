package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import gg.hideouthq.App
import gg.hideouthq.AppContainer
import gg.hideouthq.core.customgames.parseListing
import gg.hideouthq.core.data.Preferences
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.fake.FakeCustomGamesFeed
import gg.hideouthq.core.data.fake.FakeCustomGamesPlatform
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.MemoryStorage
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.network.hideoutHttpClient
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.data.CoilRemoteImages
import gg.hideouthq.rememberHideoutAppState
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

fun main(args: Array<String>) {
    val listing = File(requireNotNull(args.firstOrNull()) { "Name the folder that holds hideout.json and its art" })
    val outDir = File(args.getOrNull(1) ?: "build/customgames-render").apply { mkdirs() }
    val client = hideoutHttpClient(OkHttp.create(), "Hideout custom games render tool (dev evidence; not shipped)")
    val images = CoilRemoteImages(client, File(outDir, "image-cache"))
    val targets = customGamesTargets(images, listing)
    val selected = selectTargets(args.getOrNull(2), targets.map { it.name })
    targets.filter { it.name in selected }.forEach { target ->
        runScript(
            outDir, target.name, target.widthDp, target.heightDp, target.content,
            outputDirectory = File(outDir, target.name), coroutineContext = Dispatchers.Main.immediate,
        ) {
            waitForLocalImages()
            target.script(this)
        }
    }
}

private class CustomGamesTarget(val name: String, val widthDp: Int, val heightDp: Int, val content: @Composable () -> Unit, val script: Script.() -> Unit)

private fun customGamesTargets(images: RemoteImages, folder: File): List<CustomGamesTarget> {
    val app: @Composable (Route) -> Unit = { start -> CustomGamesApp(images, folder, start) }
    return listOf(
        CustomGamesTarget("page", 1440, 1900, { app(Route.CustomGames) }) {
            openBebopWars()
            check(hasText("Hook pulled teammates home.")) { "the banner shows the listing's summary" }
            check(hasText("Higher score after 8 minutes, then sudden death")) { "the side panel shows the listing's facts" }
            check(hasText("The canal")) { "the legend shows under the map" }
            capture("page")
        },
        CustomGamesTarget("page_compact", 1000, 2000, { app(Route.CustomGames) }) {
            openBebopWars()
            capture("page_compact")
        },
        CustomGamesTarget("library", 1440, 900, { app(Route.CustomGames) }) {
            capture("library")
            moveTo(nodes().first { it.hasLabel("Bebop Wars") })
            advance(600)
            capture("bebop_hovered")
        },
        CustomGamesTarget("optin", 1440, 900, { CustomGamesApp(images, folder, Route.CustomGames, inGame = null) }) {
            optInShown()
            capture("optin")
            clickLabel("Turn on")
            advance(600)
            check(!hasText(OptInTitle)) { "Turn on answers the question for good" }
            capture("optin_answered")
        },
        CustomGamesTarget("optin_compact", 1000, 700, { CustomGamesApp(images, folder, Route.CustomGames, inGame = null) }) {
            optInShown()
            capture("optin_compact")
            clickLabel("Not now")
            advance(600)
            check(!hasText(OptInTitle)) { "Not now answers it too" }
        },
        CustomGamesTarget("servers", 1440, 900, { app(Route.CustomGames) }) {
            clickLabel("Servers")
            advance(600)
            check(hasText("Play without hosting")) { "the Servers tab says what is coming" }
            check(!hasText("Search modes")) { "the Servers tab shows no search or filters" }
            capture("servers")
            clickLabel("Tell me when servers are live")
            advance(600)
            check(hasText("Done. We’ll let you know in Hideout.")) { "the notify switch says it is on" }
            capture("servers_notify_on")
        },
        CustomGamesTarget("servers_compact", 1000, 1000, { app(Route.CustomGames) }) {
            clickLabel("Servers")
            advance(600)
            capture("servers_compact")
        },
        CustomGamesTarget("discover", 1440, 2600, { app(Route.Discover) }) {
            capture("discover")
        },
        CustomGamesTarget("library_wide", 1920, 900, { app(Route.CustomGames) }) {
            capture("library_wide")
        },
        CustomGamesTarget("discover_wide", 1920, 2600, { app(Route.Discover) }) {
            capture("discover_wide")
        },
        CustomGamesTarget("host", 1440, 1000, { app(Route.CustomGames) }) { hostThrough("host") },
        CustomGamesTarget("host_running", 1440, 1000, { CustomGamesApp(images, folder, Route.CustomGames, running = true) }) { hostThrough("host_running") },
        CustomGamesTarget("join", 1440, 1000, { CustomGamesApp(images, folder, Route.CustomGames, link = FakeCustomGamesPlatform.SampleCode.shown) }) { joinThrough("join") },
        CustomGamesTarget("join_running", 1440, 1000, { CustomGamesApp(images, folder, Route.CustomGames, running = true, link = FakeCustomGamesPlatform.SampleCode.shown) }) { joinThrough("join_running") },
    )
}

private fun Script.hostThrough(name: String) {
    openBebopWars()
    clickLabel("Host a game")
    waitForLocalImages()
    capture("${name}_dialog")
    clickLabel("Start game")
    waitForLocalImages()
    capture("${name}_started")
}

private fun Script.joinThrough(name: String) {
    waitForLocalImages()
    capture("${name}_dialog")
    clickLabel("Join game")
    waitForLocalImages()
    capture("${name}_started")
}

private const val OptInTitle = "Play Hideout games inside Deadlock"

private fun Script.optInShown() {
    waitForLocalImages()
    check(hasText(OptInTitle)) { "the page asks before Hideout's UI goes into Deadlock" }
    check(hasText("Hideout games show up in Custom Match")) { "the first point" }
    check(hasText("Friends’ invites pop up in game")) { "the second point" }
    check(hasText("Stop or Leave from the Esc menu")) { "the third point" }
    check(hasText("Hideout adds a small menus file to Deadlock’s folder. Turn it off any time in Settings.")) { "what it changes" }
    check(shows("Turn on") && shows("Not now")) { "both answers" }
}

private fun Script.openBebopWars() {
    moveTo(nodes().first { it.hasLabel("Bebop Wars") })
    click()
    waitForLocalImages()
}

/** Coil reads the folder's files on its own threads, which [Script.advance]'s fake clock never waits for. */
private fun Script.waitForLocalImages() {
    repeat(20) {
        advance(50)
        runUiQueue(150)
    }
}

@Composable
private fun CustomGamesApp(images: RemoteImages, folder: File, start: Route, running: Boolean = false, link: String? = null, inGame: Boolean? = true) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        val container = remember {
            val json = Json.parseToJsonElement(File(folder, "hideout.json").readText()).jsonObject
            val listing = checkNotNull(parseListing(json) { File(folder, it).toURI().toString() }) { "$folder/hideout.json breaks a listing rule" }
            val feed = FakeCustomGamesFeed.Placeholder.copy(modes = listOf(FakeCustomGamesFeed.BebopWars.copy(listing = listing)))
            val preferences = Preferences(MemoryStorage()).also {
                runBlocking {
                    it.setCustomGames(true)
                    inGame?.let { on -> it.setInGameUi(on) }
                }
            }
            val processes = FakeProcesses(if (running) ProcessScan.Seen(game = true) else ProcessScan.Seen())
            AppContainer.fake(images = images, processes = processes, preferences = preferences, customGamesFeed = feed).also { app ->
                link?.let { app.customGames?.openLink(it) }
            }
        }
        App(rememberHideoutAppState(container, start))
    }
}
