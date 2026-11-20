package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import gg.hideouthq.core.navigation.Route

internal val modPageTargets: List<Target> = listOf(
    Target("viewer_scene", 1440, 900, settleMillis = DetailMillis, interact = { openScene() }) { AppAt(Lilith) },
    Target("viewer_scene_dramatic", 1440, 900, settleMillis = DetailMillis, interact = { openScene(); pick("Dramatic") }) { AppAt(Lilith) },
    Target("viewer_turntable_off", 1440, 900, settleMillis = DetailMillis, interact = { openScene(); pick("Turntable and spotlight"); key(Key.Escape); advance(SettleMillis) }) {
        AppAt(Lilith)
    },
    Target("viewer_images", 1440, 900, settleMillis = DetailMillis, interact = { pick("Images"); pick("Next image") }) { AppAt(Lilith) },
    Target("sound_images", 1440, 900, settleMillis = DetailMillis, interact = { pick("Images"); pick("Next image") }) { AppAt(AnnouncerPack) },
    Target("viewer_changelog", 1440, 900, settleMillis = DetailMillis, interact = { pick("What’s new in 1.2") }) { AppAt(Lilith) },
    Target("viewer_variants_failed", 1440, 900, settleMillis = DetailMillis) { AppWith({ InstallDialogFixture.container() }, Route.ModDetail(InstallDialogFixture.Lilith.modId)) },
    Target("discover_v4", 1440, 1820) { AppAt(Route.Discover) },
    Target("discover_news_reader", 1440, 900, interact = { clickStarting("Read: Minor Update") }) { AppAt(Route.Discover) },
    Target("viewer_scene_compact", 1000, 680, scale = 1f, settleMillis = DetailMillis, interact = { openScene(); pick("Broadway") }) { AppAt(Lilith) },
    Target("viewer_images_compact", 1000, 680, scale = 1f, settleMillis = DetailMillis, interact = { pick("Images") }) { AppAt(Lilith) },
    Target("sound_images_compact", 1000, 680, scale = 1f, settleMillis = DetailMillis, interact = { pick("Images") }) { AppAt(AnnouncerPack) },
    Target("viewer_changelog_compact", 1000, 680, scale = 1f, settleMillis = DetailMillis, interact = { pick("Details"); pick("What’s new in 1.2") }) { AppAt(Lilith) },
    Target("viewer_variants_failed_compact", 1000, 680, scale = 1f, settleMillis = DetailMillis) {
        AppWith({ InstallDialogFixture.container() }, Route.ModDetail(InstallDialogFixture.Lilith.modId))
    },
    Target("discover_v4_compact", 1000, 1700, scale = 1f) { AppAt(Route.Discover) },
    Target("detail_needs", 1440, 900, settleMillis = DetailMillis) { AppWith(NeedsFixture::container, Lilith) },
    Target("detail_needs_compact", 1000, 680, scale = 1f, settleMillis = DetailMillis, interact = { pick("Details") }) { AppWith(NeedsFixture::container, Lilith) },
)

private val Lilith = Route.ModDetail("lilith")
private val AnnouncerPack = Route.ModDetail("announcer-pack")
private const val DetailMillis = 1_200L
private const val SettleMillis = 600L

private fun Script.openScene() {
    clickLabel("Scene")
    advance(SettleMillis)
}

private fun Script.pick(label: String) {
    clickLabel(label)
    advance(SettleMillis)
}

private fun Script.clickStarting(prefix: String) {
    val matches = nodes().filter { node ->
        node.config.contains(SemanticsActions.OnClick) && (
            node.config.getOrElseNullable(SemanticsProperties.Text) { null }?.any { it.text.startsWith(prefix) } == true ||
                node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }?.any { it.startsWith(prefix) } == true
            )
    }
    check(matches.size == 1) { "Expected one clickable starting '$prefix', found ${matches.size}" }
    checkNotNull(matches.single().config[SemanticsActions.OnClick].action).invoke()
    advance(SettleMillis)
}
