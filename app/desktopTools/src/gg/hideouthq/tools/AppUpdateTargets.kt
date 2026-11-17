package gg.hideouthq.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import gg.hideouthq.App
import gg.hideouthq.AppContainer
import gg.hideouthq.core.appupdate.AppUpdatePhase
import gg.hideouthq.core.appupdate.AppVersion
import gg.hideouthq.core.appupdate.PackageFormat
import gg.hideouthq.core.appupdate.ReleasePackage
import gg.hideouthq.core.appupdate.RestartStage
import gg.hideouthq.core.appupdate.UpdateFailure
import gg.hideouthq.core.data.fake.FakeAppUpdate
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.designsystem.image.RemoteImage
import gg.hideouthq.core.designsystem.image.RemoteImages
import gg.hideouthq.core.designsystem.theme.HideoutTheme
import gg.hideouthq.core.model.AppAbout
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.core.ui.LocalWindowChrome
import gg.hideouthq.core.appupdate.AppUpdateState
import gg.hideouthq.feature.appupdate.UpdateChip
import gg.hideouthq.feature.customgames.GameInProgress
import gg.hideouthq.feature.customgames.HostingChip
import gg.hideouthq.feature.customgames.HostingChipView
import gg.hideouthq.feature.customgames.HostingTitle
import gg.hideouthq.feature.customgames.InviteView
import gg.hideouthq.feature.customgames.ModeFace
import gg.hideouthq.feature.customgames.Status
import gg.hideouthq.core.customgames.DisplayName
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import gg.hideouthq.feature.appupdate.AppUpdateToast
import gg.hideouthq.rememberHideoutAppState
import kotlinx.coroutines.CompletableDeferred
import org.jetbrains.skia.Image

internal const val AppUpdateStepMillis = 900L
internal val UpdatedVersion = AppAbout("1.4.0")

private const val Available = "Update available · 1.4.0"
private const val Ready = "Update ready · 1.4.0"

internal val appUpdateTargets: List<Target> = listOf(
    Target("update_28_available", 1440, 900) { UpdateApp(FakeAppUpdate.Available) },
    Target("update_28_available_open", 1440, 900, interact = { openChip(Available) }) { UpdateApp(FakeAppUpdate.Available) },
    Target("update_28b_downloading", 1440, 900) { UpdateApp(FakeAppUpdate.Downloading) },
    Target("update_28b_downloading_open", 1440, 900, interact = { openChip("Downloading update · 62%") }) { UpdateApp(FakeAppUpdate.Downloading) },
    Target("update_28c_ready", 1440, 900) { UpdateApp(FakeAppUpdate.Ready) },
    Target("update_28c_ready_open", 1440, 900, interact = { openChip(Ready) }) { UpdateApp(FakeAppUpdate.Ready) },
    Target("update_28d_failed", 1440, 900, interact = { openChip("Update failed · 1.4.0") }) { UpdateApp(FakeAppUpdate.FailedConnection) },
    Target("update_28g_states", 1440, 640, content = { UpdateStates() }),
    Target("topbar_hosting_states", 1440, 640, content = { HostingStates() }),
    Target("update_28h_whats_new_ready", 1440, 900, interact = { openWhatsNew(Ready) }) { UpdateApp(FakeAppUpdate.Ready) },
    Target("update_28i_whats_new_available", 1440, 900, interact = { openWhatsNew(Available) }) { UpdateApp(FakeAppUpdate.Available) },
    Target("update_28j_whats_new_fix_release", 1440, 900, interact = { openWhatsNew(Ready); clickVersion("1.3.2") }) { UpdateApp(FakeAppUpdate.Ready) },
    Target("update_28k_toast", 1440, 900) { UpdateApp(FakeAppUpdate.JustUpdated, UpdatedVersion) },
    Target("update_28l_whats_new_after", 1440, 900, interact = { openWhatsNew() }) { UpdateApp(FakeAppUpdate.JustUpdated, UpdatedVersion) },
    Target("update_github_only", 1440, 900, interact = { openChip(Available) }) { UpdateApp(FakeAppUpdate.GitHubOnly) },
    Target("update_github_only_dialog", 1440, 900, interact = { openWhatsNew(Available) }) { UpdateApp(FakeAppUpdate.GitHubOnly) },
    Target("update_waiting_for_mods", 1440, 900, interact = { openChip(Ready); clickLabel("Restart"); advance(AppUpdateStepMillis) }) {
        AppWith({ modsDownloadingAndUpdateReady() }, Route.Discover)
    },
    Target("update_waiting_for_mods_dialog", 1440, 900, interact = { openChip(Ready); clickLabel("Restart"); advance(AppUpdateStepMillis); openWhatsNew() }) {
        AppWith({ modsDownloadingAndUpdateReady() }, Route.Discover)
    },
    Target("update_installing", 1440, 900, interact = { openChip(Ready); clickLabel("Restart"); advance(AppUpdateStepMillis) }) { UpdateApp(FakeAppUpdate.Password) },
    Target("update_exiting", 1440, 900, interact = { openChip(Ready); clickLabel("Restart"); advance(AppUpdateStepMillis) }) { UpdateApp(FakeAppUpdate.Ready) },
    Target("update_whats_new_narrow", 840, 700, interact = { openWhatsNew(Ready) }) { UpdateApp(FakeAppUpdate.Ready) },
    Target("update_settings_about", 1440, 900, interact = {
        clickLabel("Settings")
        advance(AppUpdateStepMillis)
        clickLabel("About")
        advance(AppUpdateStepMillis)
    }) { UpdateApp(FakeAppUpdate.Ready) },
)

@Composable
private fun UpdateApp(script: FakeAppUpdate, about: AppAbout? = null) {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        val container = remember {
            val config = FakeConfig(appUpdate = script)
            if (about != null) AppContainer.fake(config, about = about, images = ReleaseArt) else AppContainer.fake(config, images = ReleaseArt)
        }
        App(rememberHideoutAppState(container, Route.Discover))
    }
}

internal fun Script.openChip(label: String) {
    clickLabel(label)
    advance(AppUpdateStepMillis)
}

internal fun Script.openWhatsNew(chip: String? = null) {
    chip?.let { openChip(it) }
    clickLabel("What’s new")
    advance(AppUpdateStepMillis)
}

internal fun Script.clickVersion(version: String) {
    clickLabel(version)
    advance(AppUpdateStepMillis)
}

internal object ReleaseArt : RemoteImages {
    private val loaded = mutableStateOf(true)

    @Composable
    override fun rememberImage(url: String, contentScale: ContentScale): RemoteImage? = remember(url) {
        val name = url.substringAfterLast('/')
        ReleaseArt::class.java.getResourceAsStream("/release-art/$name")?.use { stream ->
            RemoteImage(BitmapPainter(Image.makeFromEncoded(stream.readBytes()).toComposeImageBitmap()), loaded)
        }
    }
}

private val SamplePackage = ReleasePackage(AppVersion(1, 4, 0), PackageFormat.AppImage, "", 19_293_798, "")

@Composable
private fun UpdateStates() {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        HideoutTheme {
            val updates = remember { AppContainer.fake().appUpdates }
            val running = AppVersion(1, 3, 2)
            val hosting = GameInProgress.Hosting("Bebop Wars", listOf("nori"))
            val states = listOf(
                Triple("AVAILABLE", AppUpdateState(running, AppUpdatePhase.Available(SamplePackage)), null),
                Triple("SEEN", AppUpdateState(running, AppUpdatePhase.Available(SamplePackage), openedFor = SamplePackage.version), null),
                Triple("DOWNLOADING", AppUpdateState(running, AppUpdatePhase.Downloading(SamplePackage, 11_962_155)), null),
                Triple("READY", AppUpdateState(running, AppUpdatePhase.Ready(SamplePackage, RestartStage.Idle)), null),
                Triple("FAILED", AppUpdateState(running, AppUpdatePhase.Failed(SamplePackage.version, SamplePackage, UpdateFailure.Connection, "")), null),
                Triple("AVAILABLE, HOSTING", AppUpdateState(running, AppUpdatePhase.Available(SamplePackage)), hosting),
                Triple("READY, HOSTING", AppUpdateState(running, AppUpdatePhase.Ready(SamplePackage, RestartStage.Idle)), hosting),
                Triple("AFTER YOUR GAME", AppUpdateState(running, AppUpdatePhase.Ready(SamplePackage, RestartStage.Waiting)), hosting),
            )
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(44.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                Text("Update chip", style = HideoutTheme.typography.headlinePage, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    states.forEach { (label, state, game) ->
                        Column(Modifier.widthIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(label, style = HideoutTheme.typography.eyebrow, color = MaterialTheme.colorScheme.outline)
                            UpdateChip(state, game, updates, expanded = false, onToggle = {}, onWhatsNew = {})
                        }
                    }
                }
                Text("After the restart", style = HideoutTheme.typography.titleSection, color = MaterialTheme.colorScheme.onSurface)
                AppUpdateToast(SamplePackage.version, {}, {})
            }
        }
    }
}

@Composable
private fun HostingStates() {
    CompositionLocalProvider(LocalWindowChrome provides RenderedWindowChrome) {
        HideoutTheme {
            val face = ModeFace("Bebop Wars", "1.0.0", null, "bebop-wars", emptyList())
            val hosting = HostingTitle.Hosting("Bebop Wars")
            val chips = listOf(
                HostingChipView(hosting, face, Status.Starting, null, null, null, opens = true),
                HostingChipView(hosting, face, Status.InMatch, 2 to 2, 3 to 2, null, opens = true),
                HostingChipView(hosting, face, Status.CouldNotStart, null, null, null, opens = true),
                HostingChipView(HostingTitle.InGame(DisplayName.parse("nori")), face, Status.InMatch, null, null, null, opens = true),
            )
            val ready = HostingChipView(hosting, face, Status.Ready, 1 to 2, null, InviteView("7KQ-2MR", "https://example.test/join#7KQ-2MR", live = true, paused = false), opens = true)
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(44.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                Text("Hosting chip", style = HideoutTheme.typography.headlinePage, color = MaterialTheme.colorScheme.onSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    chips.forEach { HostingChip(it, expanded = false, onToggle = {}, onOpen = {}) }
                }
                Box(Modifier.fillMaxWidth().padding(end = 400.dp), contentAlignment = Alignment.TopEnd) {
                    HostingChip(ready, expanded = true, onToggle = {}, onOpen = {})
                }
            }
        }
    }
}

private fun modsDownloadingAndUpdateReady(): AppContainer {
    val ivy = SubmissionRef(SubmissionKind.Mod, 628_739)
    val file = RemoteFile(1_691_864, "bg_ivy.zip", 23_159_953, null, null, 1_999, 1_777_795_519, "https://gamebanana.com/dl/1691864")
    return AppContainer.fake(
        FakeConfig(appUpdate = FakeAppUpdate.Ready),
        gameBanana = FakeGameBanana(mapOf(ivy to FileListing.Available("Bubblegum ivy V2", listOf(file)))),
        downloader = { files -> FakeDownloader(files).apply { gate = CompletableDeferred() } },
    ).also { it.modLibrary.install(ivy.modId) }
}
