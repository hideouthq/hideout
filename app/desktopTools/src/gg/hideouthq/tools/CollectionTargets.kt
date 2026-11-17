package gg.hideouthq.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.navigation.Section
import gg.hideouthq.feature.collections.components.CollectionBackdrop
import gg.hideouthq.feature.collections.components.FigureLayout
import java.io.File

internal val collectionTargets: List<Target> = listOf(
    Target("app_collection", 1440, 1300) { AppAt(Route.Collection("halloween")) },
    Target("app_collections", 1440, 900) { AppAt(Route.Collections) },
    Target("narrow_collection", 1100, 800, scale = 1f) { AppAt(Route.Collection("halloween")) },
    Target("collection_make_profile", 1440, 900, interact = { clickLabel("Make a Halloween profile"); advance(SheetMillis) }) { AppAt(Route.Collection("halloween")) },
    Target("collection_backdrops", 1552, 1500, scale = 1f) { Backdrops() },
)

@Composable
private fun Backdrops() {
    val collections = remember { AppContainer.fake().collections.collections.value }
    HideoutTheme {
        Column(Modifier.fillMaxSize().background(Color(0xFF141313)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            collections.forEach { collection ->
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Labelled("${collection.title}  banner, 1180 x 320") { CollectionBackdrop(collection.look, FigureLayout.Banner, Modifier.size(1180.dp, 320.dp)) }
                    Labelled("card, 300 x 210") { CollectionBackdrop(collection.look, FigureLayout.Card, Modifier.size(300.dp, 210.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Labelled(label: String, content: @Composable () -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF9A9A9A))
    content()
}

internal fun checkCollections(outDir: File) {
    val state = HideoutAppState(AppContainer.fake())
    runCheck(outDir, "collections", state, heightDp = 1560) {
        check(state.navigator.current.route == Route.Discover) { "Should start at Discover" }
        val discover = state.navigator.current.id
        fun openHalloween() {
            clickLabel("Halloween collection, 6 skins")
            advance(600)
            check(state.navigator.current.route == Route.Collection("halloween")) { "The card should open Halloween, got ${state.navigator.current.route}" }
        }
        fun crumb(label: String) {
            moveTo(nodes().single { it.hasLabel(label) && it.config.contains(SemanticsActions.OnClick) && !it.config.contains(SemanticsProperties.Selected) })
            click()
            advance(600)
        }
        fun checkAtDiscover(from: String) =
            check(state.navigator.current.route == Route.Discover && state.navigator.current.id == discover) { "The breadcrumb should go to the same Discover $from, got ${state.navigator.current.route}" }

        openHalloween()
        check(state.navigator.activeSection == Section.Discover) { "A collection page should keep Discover active" }
        checkSelected("Discover", true)
        check(shows("Make a Halloween profile")) { "The page should offer Make a Halloween profile" }
        capture("collection")

        crumb("Collections")
        check(state.navigator.current.route == Route.Collections) { "The Collections crumb should open every collection" }
        openHalloween()
        crumb("Discover")
        checkAtDiscover("from a collection opened from every collection")
        capture("back")

        openHalloween()
        crumb("Collections")
        crumb("Discover")
        checkAtDiscover("from every collection")
    }
}

private const val SheetMillis = 1_200L
