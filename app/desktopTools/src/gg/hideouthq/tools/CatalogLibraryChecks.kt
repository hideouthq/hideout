package gg.hideouthq.tools

import androidx.compose.ui.semantics.SemanticsActions
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.CatalogFreshness
import gg.hideouthq.core.data.CatalogRepository
import gg.hideouthq.core.data.DataError
import gg.hideouthq.core.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import kotlin.math.abs

fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/catalog-library/rendered")
    for (width in listOf(1440, 1000)) {
        checkCatalogue(out, width)
    }
    runLibraryUpdateChecks(out)
    println("All 4 catalogue and Library workflows passed.")
}

private fun checkCatalogue(out: File, width: Int) {
    val base = AppContainer.fake()
    check(!base.previews.available)
    val seed = checkNotNull(base.catalog.cachedMod("lilith"))
    val old = seed.copy(id = "mod-1", title = "Earlier upload", publishedAt = 1_790_000_001, trendingRank = 1, has3dPreview = false)
    val newer = old.copy(id = "mod-2", title = "Later upload", publishedAt = 1_790_000_020, trendingRank = 2)
    val newest = old.copy(id = "mod-3", title = "Just uploaded", publishedAt = 1_790_000_030, trendingRank = 3)
    val mods = MutableStateFlow(listOf(old, newer))
    val freshness = MutableStateFlow(CatalogFreshness(checkedAt = 1_790_000_040))
    var refreshes = 0
    val catalog = object : CatalogRepository by base.catalog {
        override val previewsAvailable = false
        override val mods = mods
        override val freshness = freshness
        override fun cachedMod(modId: String) = mods.value.firstOrNull { it.id == modId }
        override fun refresh() {
            refreshes++
            if (refreshes == 1) freshness.value = freshness.value.copy(error = DataError.Offline)
            else {
                mods.value = listOf(old, newer, newest)
                freshness.value = CatalogFreshness(checkedAt = 1_790_000_050)
            }
        }
    }
    val app = AppContainer(catalog, base.launcher, base.soundPlayer, base.images, base.previews,
        base.modLibrary, base.preferences, base.folderPicker, base.folderOpener, base.about, migration = base.migration, autoexec = base.autoexec, crosshairs = base.crosshairs, communityCrosshairs = base.communityCrosshairs)
    runCheck(out, "catalogue_$width", HideoutAppState(app, Route.Browse()), widthDp = width, heightDp = 900) {
        advance(800)
        check(hasText(old.title) && hasText(newer.title))
        clickLabel("Newest")
        advance(600)
        fun firstTitle(): String = listOf(old.title, newer.title, newest.title).filter { title -> nodes().any { it.hasLabel(title) } }
            .minWith(compareBy({ title -> nodes().first { it.hasLabel(title) }.boundsInRoot.top },
                { title -> nodes().first { it.hasLabel(title) }.boundsInRoot.left }))
        check(firstTitle() == newer.title) { "Newest must preserve upload seconds, not trending rank" }
        fun checkRefreshAlignment() {
            val refreshRight = nodes().single { it.hasLabel("Refresh") && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot.right
            val sortRight = nodes().single { it.hasLabel("Newest") && it.config.contains(SemanticsActions.OnClick) }.boundsInRoot.right
            check(abs(refreshRight - sortRight) <= 8f * scale) { "Refresh must align with the content's right edge: $refreshRight vs $sortRight" }
        }
        capture("newest_without_preview_host")
        checkRefreshAlignment()
        clickLabel("Refresh")
        advance(500)
        check(refreshes == 1)
        check(hasText("Couldn’t check for new mods. GameBanana can’t be reached."))
        checkRefreshAlignment()
        check(hasText(old.title) && hasText(newer.title))
        capture("failed_refresh_keeps_cards")
        clickLabel("Refresh")
        advance(600)
        check(refreshes == 2)
        check(firstTitle() == newest.title)
        check(!hasText("Couldn’t check for new mods. GameBanana can’t be reached."))
        capture("new_upload_after_refresh")
        if (width == 1000) {
            clickLabel("Filters")
            advance(500)
        }
        check(!hasText("3D preview") && !hasText("Has animations"))
    }
    println("Catalogue $width passed")
}
