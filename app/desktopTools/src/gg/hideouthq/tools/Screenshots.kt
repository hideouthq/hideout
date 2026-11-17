package gg.hideouthq.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import gg.hideouthq.App
import gg.hideouthq.AppContainer
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.model.Accent
import gg.hideouthq.core.model.ModCardStyle
import gg.hideouthq.core.model.ModCategory
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.data.DesktopBuild
import gg.hideouthq.feature.settings.SettingsTab
import gg.hideouthq.rememberHideoutAppState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

private const val FrameNanos = 16_666_667L
private const val NanosPerMilli = 1_000_000L
private const val DefaultScale = 2f
private const val DefaultSettleMillis = 2_000L
private const val DetailClockMillis = 1_200L

private const val NeverMillis = 3_600_000L

internal class Target(
    val name: String,
    val widthDp: Int,
    val heightDp: Int,
    val scale: Float = DefaultScale,
    val settleMillis: Long = DefaultSettleMillis,
    val interact: (Script.() -> Unit)? = null,
    val content: @Composable () -> Unit,
)

private val targets = listOf(
    Target("app", 1440, 900) { AppAt(Route.Discover) },
    Target("app_discover_full", 1440, 1180) { AppAt(Route.Discover) },
    Target("app_browse", 1440, 900) { AppAt(Route.Browse()) },
    Target("app_library", 1440, 900) { AppAt(Route.Library) },
    Target("app_browse_war", 1440, 900) { AppAt(Route.Browse(query = "war")) },
    Target("app_browse_silver", 1440, 900) { AppAt(Route.Browse(heroId = "silver", allHeroes = true)) },
    Target("app_browse_maps", 1440, 900) { AppAt(Route.Browse(category = ModCategory.Maps)) },
    Target("app_browse_mature", 1440, 900) { AppWith({ AppContainer.fake(FakeConfig(mature = setOf("lilith", "plague-doctor"))) }, Route.Browse(category = ModCategory.Skins)) },
    Target("app_discover_loading", 1440, 900) { AppWith({ AppContainer.fake(FakeConfig(latencyMillis = NeverMillis)) }, Route.Discover) },
    Target("app_browse_loading", 1440, 900) { AppWith({ AppContainer.fake(FakeConfig(latencyMillis = NeverMillis)) }, Route.Browse()) },
    Target("app_detail", 1440, 900, settleMillis = DetailClockMillis) { AppAt(Route.ModDetail("lilith")) },
    Target("app_detail_full", 1440, 1140, settleMillis = DetailClockMillis) { AppAt(Route.ModDetail("lilith")) },
    Target("app_detail_hud", 1440, 900) { AppAt(Route.ModDetail("hudlock")) },
    Target("narrow_browse", 1100, 800, scale = 1f) { AppAt(Route.Browse()) },
    Target("narrow_detail", 1100, 800, scale = 1f) { AppAt(Route.ModDetail("lilith")) },
    Target("narrow_detail_min", 1000, 700, scale = 1f) { AppAt(Route.ModDetail("lilith")) },
    Target("narrow_discover", 1000, 800, scale = 1f) { AppAt(Route.Discover) },
    Target("wide_discover", 2560, 1080, scale = 1f) { AppAt(Route.Discover) },
    Target("rail_library", 840, 700, scale = 1f) { AppAt(Route.Library) },
    Target("settings_game", 1440, 900) { AppAt(Route.Settings) },
    Target("settings_game_not_found", 1440, 900) { AppAt(Route.Settings, game = SampleGame.NotFound) },
    Target("settings_game_wrong_folder", 1440, 900) { AppAt(Route.Settings, game = SampleGame.WrongFolder) },
    Target("rail_settings_game", 840, 700, scale = 1f) { AppAt(Route.Settings) },
    Target("rail_settings_game_not_found", 840, 700, scale = 1f) { AppAt(Route.Settings, game = SampleGame.NotFound) },
    Target("rail_settings_game_wrong_folder", 840, 700, scale = 1f) { AppAt(Route.Settings, game = SampleGame.WrongFolder) },
    Target("settings_mods", 1440, 900) { SettingsAt(SettingsTab.Mods) },
    Target("settings_about", 1440, 900) { SettingsAt(SettingsTab.About) },
    Target("settings_appearance", 1440, 900) { SettingsAt(SettingsTab.Appearance) },
    Target("settings_appearance_ember_under", 1440, 900, interact = {
        clickLabel("Ember"); advance(SettleDialogMillis); clickNode(nodes().first { it.hasLabel("Under the image") }); advance(SettleDialogMillis)
    }) { SettingsAt(SettingsTab.Appearance) },
    Target("app_discover_under", 1440, 1700) { AppWith({ withAppearance(Accent.Ember, ModCardStyle.UnderImage) }, Route.Discover) },
    Target("app_browse_under", 1440, 900) { AppWith({ withAppearance(Accent.Graphite, ModCardStyle.UnderImage) }, Route.Browse()) },
    Target("app_library_under", 1440, 900, interact = { clickLabel("Cards"); advance(SettleDialogMillis) }) {
        AppWith({ InstalledFixture().container.also { runBlocking { it.preferences.setModCards(ModCardStyle.UnderImage) } } }, Route.Library)
    },
    Target("settings_game_compact", 1000, 680, scale = 1f) { SettingsAt(SettingsTab.Game) },
    Target("settings_mods_compact", 1000, 680, scale = 1f) { SettingsAt(SettingsTab.Mods) },
    Target("settings_about_compact", 1000, 680, scale = 1f) { SettingsAt(SettingsTab.About) },
    Target("settings_game_not_found_compact", 1000, 680, scale = 1f) { SettingsAt(SettingsTab.Game, SampleGame.NotFound) },
    Target("app_install_downloading", 1440, 900) { AppInstalling(held = true) },
    Target("app_install_failed", 1440, 900) { AppInstalling(held = false) },
    Target("install_dialog", 1440, 900, interact = { openInstallDialog() }) { AppWith({ InstallDialogFixture.container() }, Route.Discover) },
    Target("install_dialog_options", 1440, 900, interact = { openInstallDialog(); clickRow("Werewolf Transform"); advance(SettleDialogMillis) }) {
        AppWith({ InstallDialogFixture.container() }, Route.Discover)
    },
    Target("install_dialog_multi", 1440, 900, interact = { openInstallDialog(); clickRow("Werewolf Transform Lilith Cabezas"); advance(SettleDialogMillis) }) {
        AppWith({ InstallDialogFixture.container() }, Route.ModDetail(InstallDialogFixture.Lilith.modId))
    },
    Target("install_dialog_change_files", 1440, 900, settleMillis = DetailClockMillis, interact = {
        openInstallDialog(); clickRow("Werewolf Transform Lilith Cabezas"); key(Key.Enter); advance(SettleDialogMillis * 3)
        clickLabel("Installed · 2 files"); advance(SettleDialogMillis); clickLabel("Change files…"); advance(SettleDialogMillis); clickRow("Werewolf Transform"); advance(SettleDialogMillis)
    }) { AppWith({ InstallDialogFixture.container() }, Route.ModDetail(InstallDialogFixture.Lilith.modId)) },
    Target("installed_two_files", 1440, 900) { AppWith({ InstalledFixture(InstalledFixture.twoFiles()).container }, Route.Library) },
    Target("install_dialog_sets", 1440, 900, interact = {
        openInstallDialog(); clickRow("Default"); clickRow("Werewolf Transform"); key(Key.Enter); advance(SettleDialogMillis * 2)
    }) { AppWith({ InstallDialogFixture.container(twoSets = true) }, Route.Discover) },
    Target("install_dialog_detail", 1440, 900, settleMillis = DetailClockMillis, interact = { openInstallDialog() }) {
        AppWith({ InstallDialogFixture.container() }, Route.ModDetail(InstallDialogFixture.Lilith.modId))
    },
    Target("install_dialog_compact", 1000, 680, scale = 1f, interact = { openInstallDialog() }) {
        AppWith({ InstallDialogFixture.container(files = InstallDialogFixture.ManyFiles) }, Route.Discover)
    },
    Target("rail_install_dialog", 840, 700, scale = 1f, interact = { openInstallDialog() }) { AppWith({ InstallDialogFixture.container() }, Route.Discover) },
    Target("installed_menu", 1440, 900, interact = { openRowMenu("Lilith, the Hell Warden") }) { AppWith({ InstalledFixture().container }, Route.Library) },
    Target("installed_off", 1440, 900, interact = { clickLabel("Faun Celeste"); advance(SettleDialogMillis) }) { AppWith({ InstalledFixture().container }, Route.Library) },
    Target("installed_uninstalled", 1440, 900, interact = {
        openRowMenu("Lilith, the Hell Warden"); clickNode(labelled("Uninstall").last()); advance(SettleDialogMillis)
    }) { AppWith({ InstalledFixture().container }, Route.Library) },
    Target("installed_menu_compact", 1000, 680, scale = 1f, interact = { openRowMenu("Sample mod 8") }) {
        AppWith({ InstalledFixture(InstalledFixture.many()).container }, Route.Library)
    },
    Target("rail_installed_menu", 840, 700, scale = 1f, interact = { openRowMenu("Lilith, the Hell Warden") }) { AppWith({ InstalledFixture().container }, Route.Library) },
) + modPageTargets + statsTargets + onboardingTargets + crosshairsTargets + autoexecTargets + collectionTargets + appUpdateTargets

fun main(args: Array<String>) {
    val selected = selectTargets(args.getOrNull(1), targets.map { it.name })
    val outDir = File(args.firstOrNull() ?: "build/screenshots").apply { mkdirs() }
    targets.filter { it.name in selected }.forEach { target ->
        val file = File(outDir, "${target.name}.png")
        render(target, file)
        println("Rendered ${file.absolutePath}")
    }
}

private fun withAppearance(accent: Accent, cards: ModCardStyle): AppContainer = AppContainer.fake(FakeConfig()).also {
    runBlocking {
        it.preferences.setAccent(accent)
        it.preferences.setModCards(cards)
    }
}

@Composable
internal fun AppAt(start: Route, game: SampleGame = SampleGame.Found) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        App(rememberHideoutAppState(remember { AppContainer.fake(FakeConfig(game = game)) }, start))
    }
}

@Composable
private fun SettingsAt(tab: SettingsTab, game: SampleGame = SampleGame.Found) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        val state = rememberHideoutAppState(remember { AppContainer.fake(FakeConfig(game = game), about = DesktopBuild.read().about) }, Route.Settings)
        remember(state) { state.settingsTab = tab }
        App(state)
    }
}

@Composable
internal fun AppWith(container: () -> AppContainer, start: Route) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        App(rememberHideoutAppState(remember { container() }, start))
    }
}

private const val SettleDialogMillis = 1_200L

internal fun Script.openInstallDialog() {
    clickLabel("Install")
    advance(SettleDialogMillis)
}

internal fun Script.clickRow(label: String) {
    moveTo(nodes().single { it.hasLabel(label) && it.config.contains(SemanticsProperties.ToggleableState) })
    click()
    advance(400)
}

@Composable
private fun AppInstalling(held: Boolean) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        val container = remember {
            val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
            val file = RemoteFile(1_691_864, "bg_ivy.zip", 23_159_953, "ae386fa2a8adb7bc07d17d47195a3c25", null, 1_999, 1_777_795_519, "https://gamebanana.com/dl/1691864")
            AppContainer.fake(
                gameBanana = FakeGameBanana(mapOf(ivy to FileListing.Available("Bubblegum ivy V2", listOf(file)))),
                downloader = { files ->
                    FakeDownloader(files).apply { if (held) gate = CompletableDeferred() else serve(file.id, listOf("a/pak70_dir.vpk" to FakeVpk.directory(), "A/PAK70_DIR.VPK" to FakeVpk.directory())) }
                },
            ).also { it.modLibrary.install(ivy.modId) }
        }
        App(rememberHideoutAppState(container, Route.Discover))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun render(target: Target, file: File) = onUiThread {
    val scene = ImageComposeScene(
        width = (target.widthDp * target.scale).toInt(),
        height = (target.heightDp * target.scale).toInt(),
        density = Density(target.scale),
        content = target.content,
    )
    try {
        var time = 0L
        while (time < target.settleMillis * NanosPerMilli) {
            scene.render(time).close()
            runUiQueue()
            time += FrameNanos
        }
        target.interact?.let { interact ->
            val script = Script(scene, file.absoluteFile.parentFile, target.scale).also { it.time = time / NanosPerMilli }
            script.interact()
            time = script.time * NanosPerMilli
        }
        val png = scene.render(time).use { requireNotNull(it.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed" } }
        file.writeBytes(png.bytes)
    } finally {
        scene.close()
    }
}
