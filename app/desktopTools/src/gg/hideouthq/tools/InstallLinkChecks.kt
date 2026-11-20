@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.FileListing
import gg.hideouthq.core.data.RemoteFile
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeDownloader
import gg.hideouthq.core.data.fake.FakeGameBanana
import gg.hideouthq.core.data.fake.FakeVpk
import gg.hideouthq.core.data.fake.ForeignSeed
import gg.hideouthq.core.data.fake.LibrarySeed
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.OperationStage
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.SubmissionRef
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.tools.preview.advanceUntil
import kotlinx.coroutines.CompletableDeferred
import java.io.File

private class InstallLinkCheck(val name: String, val run: (File) -> Unit)

/** GameBanana's 1-click links, from the system to the mod page, rendered: the PNGs land in a folder per check. */
fun main(args: Array<String>) {
    val checks = listOf(
        InstallLinkCheck("install_link_waits_for_setup", ::checkWaitsForSetup),
        InstallLinkCheck("install_link_installed", ::checkInstalled),
        InstallLinkCheck("install_link_not_a_mod", ::checkNotAMod),
    )
    val selected = selectTargets(args.getOrNull(1), checks.map { it.name })
    val outDir = File(args.firstOrNull() ?: "build/screenshots/install-link-checks")
    checks.filter { it.name in selected }.forEach { check ->
        check.run(outDir)
        println("Install link check '${check.name}' passed")
    }
    println("All ${selected.size} install link checks passed.")
}

private val Shop = SubmissionRef(SubmissionKind.Mod, 551_230)
private val ShopFile = RemoteFile(1_402_230, "compact_item_shop.zip", 12_000_000, null, null, 820, 1_751_328_000, "https://gamebanana.com/dl/1402230")

private fun checkWaitsForSetup(outDir: File) {
    val config = FakeConfig(seed = LibrarySeed.Empty, foreign = ForeignSeed.Dmm, setupDone = false)
    val gate = CompletableDeferred<Unit>()
    val base = AppContainer.fake(
        config, files = sampleGameFiles(config),
        gameBanana = FakeGameBanana(mapOf(Shop to FileListing.Available("Compact item shop", listOf(ShopFile)))),
        downloader = { files -> FakeDownloader(files).apply { this.gate = gate; serve(ShopFile.id, listOf("shop/pak01_dir.vpk" to FakeVpk.directory(seed = 30))) } },
    )
    val container = withGameBananaIds(base, mapOf("compact-shop" to Shop.modId))
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "install_link_waits_for_setup", state) {
        toImport()
        container.openAppLink("hideout:https://gamebanana.com/mmdl/${ShopFile.id},Mod,${Shop.id}")
        advance(StepMillis)
        check(hasText("Bring your mods over")) { "setup stays where it was" }
        check(state.navigator.current.route == Route.Discover) { "no page opens behind setup" }
        check(container.modLibrary.state.value.operations[Shop.modId] == null) { "nothing installs before setup is done" }
        capture("setup")
        steps("Start fresh")
        advanceUntil("the mod's page") { state.navigator.current.route == Route.ModDetail(Shop.modId) && hasText("Compact item shop") }
        advanceUntil("its download") { container.modLibrary.state.value.operations[Shop.modId]?.stage is OperationStage.Downloading }
        advance(StepMillis)
        capture("downloading")
        gate.complete(Unit)
        advanceUntil("the install") { installedOn(container, Shop.modId) }
        val record = (container.modLibrary.state.value.library as LibraryStatus.Ready).records.single { it.modId == Shop.modId }
        check(record.sets.map { it.fileId } == listOf(ShopFile.id)) { "the link's file is the one installed" }
        advance(StepMillis)
        capture("installed")
    }
}

private fun checkInstalled(outDir: File) {
    val container = withGameBananaIds(AppContainer.fake(), InstalledWerecat)
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "install_link_installed", state) {
        advance(StepMillis)
        container.openAppLink("hideout://https://gamebanana.com/dl/1402102,Mod,551202")
        advanceUntil("the mod's page") { state.navigator.current.route == Route.ModDetail("mod-551202") && hasText("Night of the Werecat") }
        advance(StepMillis)
        check(container.modLibrary.state.value.operations["mod-551202"] == null) { "an installed mod's page opens and nothing starts" }
        capture("page")
    }
}

private fun checkNotAMod(outDir: File) {
    val container = AppContainer.fake()
    val state = HideoutAppState(container, Route.Discover)
    runCheck(outDir, "install_link_not_a_mod", state) {
        advance(StepMillis)
        container.openAppLink("hideout:https://gamebanana.com/mmdl/1851504,Tool,12345")
        advanceUntil("the note") { hasText("isn’t for a mod or a sound") }
        advance(StepMillis)
        check(state.navigator.current.route == Route.Discover) { "no page opens" }
        capture("note")
    }
}

private fun installedOn(container: AppContainer, modId: String): Boolean {
    val ready = container.modLibrary.state.value.library as? LibraryStatus.Ready ?: return false
    val active = container.modLibrary.state.value.profiles?.activeProfile ?: return false
    return ready.records.any { it.modId == modId && it.installId in active.enabled }
}
