@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package gg.hideouthq.tools

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import gg.hideouthq.AppContainer
import gg.hideouthq.HideoutAppState
import gg.hideouthq.core.data.LibraryStore
import gg.hideouthq.core.data.ProcessScan
import gg.hideouthq.core.data.ReportExport
import gg.hideouthq.core.data.fake.FakeConfig
import gg.hideouthq.core.data.fake.FakeGameLauncher
import gg.hideouthq.core.data.fake.FakeProcesses
import gg.hideouthq.core.data.fake.FaultPoint
import gg.hideouthq.core.data.fake.InMemoryGameFiles
import gg.hideouthq.core.data.fake.InMemoryLibraryStore
import gg.hideouthq.core.data.fake.SampleGame
import gg.hideouthq.core.data.fake.SampleGameRoot
import gg.hideouthq.core.data.fake.sampleGameFiles
import gg.hideouthq.core.data.fake.sampleLibraryManifest
import gg.hideouthq.core.data.preview.PreviewFiles
import gg.hideouthq.core.library.ArchiveIdentity
import gg.hideouthq.core.library.BackupStage
import gg.hideouthq.core.library.BackupWork
import gg.hideouthq.core.library.ClearStage
import gg.hideouthq.core.library.FileImage
import gg.hideouthq.core.library.GameInfoEdit
import gg.hideouthq.core.library.GameInfoPatch
import gg.hideouthq.core.library.HideoutLines
import gg.hideouthq.core.library.LibraryManifest
import gg.hideouthq.core.library.LibraryProblem
import gg.hideouthq.core.library.LibraryStatus
import gg.hideouthq.core.library.LoadingOwner
import gg.hideouthq.core.library.Maintenance
import gg.hideouthq.core.library.ModLoading
import gg.hideouthq.core.library.OwnedDownload
import gg.hideouthq.core.library.PendingChange
import gg.hideouthq.core.library.RestoreStage
import gg.hideouthq.core.library.RetainedOriginal
import gg.hideouthq.core.library.SearchPathsCheck
import gg.hideouthq.core.library.SetDerivation
import gg.hideouthq.core.library.SetFile
import gg.hideouthq.core.library.SubmissionIdentity
import gg.hideouthq.core.library.SubmissionKind
import gg.hideouthq.core.library.VpkPart
import gg.hideouthq.core.navigation.Route
import gg.hideouthq.data.DesktopBuild
import gg.hideouthq.feature.settings.SettingsTab
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

private const val Settle = 600L
private const val EarlyMillis = 48L

internal class SettingsFixture(
    game: SampleGame = SampleGame.Found,
    val files: InMemoryGameFiles = sampleGameFiles(FakeConfig(game = game)),
    libraryStore: LibraryStore = InMemoryLibraryStore(sampleLibraryManifest(FakeConfig(game = game))),
    reportExport: ReportExport = ReportExport.Unavailable,
    val processes: FakeProcesses = FakeProcesses(),
    val previews: KeptPreviews = KeptPreviews(312_000_000),
) {
    val launcher = FakeGameLauncher()
    val about = DesktopBuild.read().about
    val container: AppContainer = AppContainer.fake(FakeConfig(game = game), files = files, libraryStore = libraryStore, processes = processes, about = about, launcher = launcher, reportExport = reportExport, previewFiles = previews)

    fun state(start: Route = Route.Discover) = HideoutAppState(container, start)

    fun gameInfo(): String = files.gameInfo(SampleGameRoot)!!.decodeToString()
}

internal class KeptPreviews(var bytes: Long) : PreviewFiles {
    override suspend fun bytes() = bytes

    override suspend fun clear() { bytes = 0 }
}

private fun SemanticsNode.label(): String =
    (config.getOrElseNullable(SemanticsProperties.Text) { null } ?: children.flatMap { it.config.getOrElseNullable(SemanticsProperties.Text) { null }.orEmpty() })
        .joinToString { it.text }

private fun Script.query(): String? = nodes().firstNotNullOfOrNull { it.config.getOrElseNullable(SemanticsProperties.EditableText) { null }?.text }

private fun Script.searchFocused(): Boolean = focused()?.config?.contains(SemanticsProperties.EditableText) == true

private fun Script.switchFor(title: String): SemanticsNode =
    nodes().single { it.hasLabel(title) && it.config.contains(SemanticsProperties.ToggleableState) }

private fun Script.search(text: String) {
    key(Key.K, ctrl = true)
    check(searchFocused()) { "Ctrl+K should focus the settings search" }
    type(text)
    advance(Settle)
}

private fun Script.clearSearch() {
    if (!searchFocused()) key(Key.K, ctrl = true)
    key(Key.Escape)
    advance(Settle)
    check(query().isNullOrEmpty()) { "Esc in the field should clear it, got ${query()}" }
}

private fun Script.openSettings(state: HideoutAppState) {
    key(Key.Comma, ctrl = true)
    advance(Settle)
    check(state.navigator.current.route == Route.Settings) { "Ctrl+, should open Settings" }
}

private fun repairedSample(): LibraryManifest {
    val sample = sampleLibraryManifest()
    val repaired = sample.records.first()
    val set = repaired.sets.first()
    val kept = FileImage(3_800_000_000L, "e".repeat(64))
    return sample.copy(
        records = sample.records.map { record ->
            if (record != repaired) record
            else record.copy(sets = listOf(set.copy(derivation = SetDerivation("kept1", listOf(kept), emptyList(), "codec-1", "build:1", 1, 4))) + record.sets.drop(1))
        },
        originals = listOf(RetainedOriginal("kept1", repaired.installId, set.source, set.componentKey, listOf(SetFile(VpkPart.Directory, kept)))),
    )
}

internal fun checkSettingsTabs(outDir: File) {
    for ((name, width, height) in listOf(Triple("settings_tabs", 1440, 900), Triple("settings_tabs_compact", 1000, 680))) {
        val fixture = SettingsFixture(libraryStore = InMemoryLibraryStore(repairedSample()))
        val state = fixture.state()
        runCheck(outDir, name, state, width, height) {
            openSettings(state)
            listOf("Deadlock", "Ready", "Mods folder", "Mod loading", "On", "Restore original", "Open").forEach { check(shows(it)) { "Game should show $it" } }
            check(hasText("""game\citadel\addons · """)) { "Mods folder should count the found game's files" }
            listOf("Game", "Mods", "Experimental", "About").forEach { tab -> check(labelled(tab).size == 1) { "one $tab tab" } }
            capture("game")

            clickLabel("Mods")
            listOf("Installing", "Ask which files to install", "Downloads", "Download folder", "Keep downloaded files", "Space used").forEach {
                check(shows(it)) { "Mods should show $it" }
            }
            check(shows("""C:\Users\you\AppData\Local\Hideout\downloads""")) { "the download folder in use" }
            check(hasText("1 download belongs to mods you’ve uninstalled (212 MB)")) { "the sample's unused download" }
            check(hasText(" · 3.8 GB of original files kept so Use original files works")) { "Space used counts the originals kept for repaired mods, and why" }
            check(labelled("Clear 212 MB").size == 1) { "Clear names the amount it frees" }
            check(switchFor("Keep downloaded files").config[SemanticsProperties.ToggleableState] == ToggleableState.On)
            check(hasText("312 MB kept on this computer. A preview downloads again when you open it.")) { "3D previews should say what is kept" }
            capture("mods")
            clickLabel("Clear 312 MB")
            advance(Settle)
            check(hasText("None kept on this computer") && labelled("Clear").size == 1) { "clearing the previews should leave nothing to clear" }

            clickLabel("Experimental")
            check(shows("Repair skins automatically") && hasText("A skin made for an older Deadlock is repaired without asking")) { "Experimental says what automatic repair does, in one sentence" }
            check(switchFor("Repair skins automatically").config[SemanticsProperties.ToggleableState] == ToggleableState.On) { "automatic repair is on by default" }
            capture("experimental")
            clickNode(switchFor("Repair skins automatically"))
            advance(Settle)
            check(fixture.container.preferences.autoRepair.value == false && switchFor("Repair skins automatically").config[SemanticsProperties.ToggleableState] == ToggleableState.Off) { "the switch saves the setting" }

            clickLabel("About")
            check(shows("Hideout ${fixture.about.version}")) { "About shows the generated version ${fixture.about.version}" }
            listOf("Where mods come from", "Open GameBanana", "Licences", "View").forEach { check(shows(it)) { "About should show $it" } }
            capture("about")

            clickLabel("Discover")
            openSettings(state)
            check(state.settingsTab == SettingsTab.About && shows("Hideout ${fixture.about.version}")) { "Settings reopens on the tab last shown" }
        }
    }
}

internal fun checkSettingsSearch(outDir: File) {
    for ((name, width, height) in listOf(Triple("settings_search", 1440, 900), Triple("settings_search_compact", 1000, 680))) {
        val fixture = SettingsFixture()
        val state = fixture.state()
        runCheck(outDir, name, state, width, height) {
            openSettings(state)
            search("folder")
            check(state.navigator.current.route == Route.Settings) { "typing searches Settings, not Browse" }
            check(shows("5 settings in 3 sections")) { "folder should find five settings in three sections" }
            check(shows("Back to Game"))
            listOf("Deadlock folder", "Mods folder", "Mod loading", "Download folder", "Host mode folders").forEach { check(shows(it)) { "folder should find $it" } }
            check(labelled("Change…").size == 2) { "Deadlock's compact row and the download folder each have Change…" }
            check(!shows("Ask which files to install")) { "settings without the word stay out" }
            capture("folder")

            tabTo("the Mods folder's Open button") { it.label() == "Open" }
            key(Key.Escape)
            advance(Settle)
            check(query().isNullOrEmpty() && state.navigator.current.route == Route.Settings) { "Esc on a result clears the search and stays in Settings" }
            check(searchFocused()) { "and puts focus back in the field" }
            check(labelled("Game").size == 1) { "the tabs come back" }
            key(Key.Escape)
            advance(Settle)
            check(!searchFocused() && state.navigator.current.route == Route.Settings) { "a second Esc leaves the empty field" }
            key(Key.Escape)
            advance(Settle)
            check(state.navigator.current.route == Route.Discover) { "and the next one goes back" }

            openSettings(state)
            search("storage")
            check(shows("2 settings in 1 section") && shows("Space used") && shows("3D previews")) { "a hidden keyword finds Space used and 3D previews" }
            capture("storage")
            clearSearch()
            search("report")
            check(shows("1 setting in 1 section") && shows("Save a bug report")) { "searching report finds Save a bug report" }
            capture("report")
            clearSearch()
            search("[")
            check(shows("No settings match “[”")) { "a bracket is a literal character" }
            clearSearch()

            search("keep")
            val inSearch = switchFor("Keep downloaded files")
            check(inSearch.config[SemanticsProperties.ToggleableState] == ToggleableState.On)
            clickNode(inSearch)
            advance(Settle)
            check(runBlocking { fixture.container.preferences.installDefaults() }.keepDownloads.not()) { "the switch in search saves the same setting" }
            clearSearch()
            clickLabel("Mods")
            check(switchFor("Keep downloaded files").config[SemanticsProperties.ToggleableState] == ToggleableState.Off) { "and the Mods tab shows it" }
        }
    }
}

internal fun checkSettingsMissingGame(outDir: File) {
    for ((name, width, height) in listOf(Triple("settings_missing", 1440, 900), Triple("settings_missing_compact", 1000, 680))) {
        val fixture = SettingsFixture(SampleGame.NotFound)
        val state = fixture.state(Route.Settings)
        runCheck(outDir, name, state, width, height) {
            advance(Settle)
            check(shows("Deadlock not found") && shows("Needs attention")) { "the card and the Game tab's dot" }
            listOf("Mods folder", "Mod loading").forEach { check(!shows(it)) { "$it needs the game" } }
            capture("game")
            search("folder")
            check(shows("3 settings in 3 sections") && !shows("Mods folder") && !shows("Mod loading")) { "Deadlock's folder, the download folder and the host mode folders; nothing that needs the game" }
            check(shows("Not found. Choose the folder Deadlock is in.") && labelled("Choose folder").size == 1)
            capture("search")
        }
    }
}

internal fun checkSettingsRestore(outDir: File) {
    for ((name, width, height) in listOf(Triple("settings_restore", 1440, 900), Triple("settings_restore_compact", 1000, 680))) {
        val fixture = SettingsFixture()
        val state = fixture.state(Route.Settings)
        val before = fixture.gameInfo()
        runCheck(outDir, name, state, width, height) {
            advance(Settle)
            clickLabel("Restore original")
            advance(Settle)
            check(state.modal.isOpen && shows("Restore the original gameinfo.gi?")) { "Restore original should ask first" }
            check(hasText("Your 3 mods that are on stay installed")) { "the dialog says how many sets stop loading" }
            check(focused()?.label() == "Cancel") { "Cancel has focus first, got ${focused()?.label()}" }
            capture("dialog")
            key(Key.K, ctrl = true)
            check(!searchFocused() && focused()?.label() == "Cancel") { "Ctrl+K stands aside while the dialog is open" }
            key(Key.Escape)
            advance(Settle)
            check(!state.modal.isOpen && !shows("Restore the original gameinfo.gi?")) { "Esc cancels" }
            check(state.navigator.current.route == Route.Settings) { "and goes nowhere" }
            check(fixture.gameInfo() == before) { "cancelling changes nothing" }

            clickLabel("Restore original")
            advance(Settle)
            clickNode(labelled("Restore").single())
            advance(Settle)
            val maintenance = fixture.container.modLibrary.state.value.maintenance
            check(maintenance is Maintenance.Restore && maintenance.stage == RestoreStage.Restored) { "the restore should finish, got $maintenance" }
            check(fixture.gameInfo() == withoutHideoutBlock(before.encodeToByteArray()).decodeToString()) { "only Hideout's block goes" }
            check(GameInfoPatch.check(fixture.gameInfo().encodeToByteArray()) == SearchPathsCheck.Read(coveredBy = null, customGames = false))
            check(fixture.container.modLibrary.state.value.loading.let { it is ModLoading.Off && it.vanilla }) { "mod loading reads off, by choice" }
            check(shows("Off") && labelled("Restore original").isEmpty()) { "the chip says Off and, with nothing left to restore, the button is gone" }
            check(fixture.launcher.launches.isEmpty()) { "the game was never started" }
            capture("restored")
            check(fixture.container.modLibrary.state.value.loading != ModLoading.Loads(LoadingOwner.Hideout))
        }
    }
}

internal fun checkSettingsLicences(outDir: File) {
    for ((name, width, height) in listOf(Triple("settings_licences", 1440, 900), Triple("settings_licences_compact", 1000, 680))) {
        val fixture = SettingsFixture()
        val state = fixture.state(Route.Settings)
        state.settingsTab = SettingsTab.About
        runCheck(outDir, name, state, width, height) {
            advance(Settle)
            tabTo("View") { it.label() == "View" }
            key(Key.Enter)
            advance(Settle)
            check(state.modal.isOpen && focused()?.label() == "Close") { "the dialog opens with Close focused, got ${focused()?.label()}" }
            check(shows("Junrar") && shows("UnRAR licence · Not an OSI-approved open-source licence")) { "Junrar's licence is named for what it is" }
            check(shows("Google Sans Flex font") && shows("JetBrains Mono font")) { "the bundled fonts are listed" }
            val firstTop = nodes().first { it.hasLabel("Show text") }.boundsInRoot.top
            capture("top")

            val buttons = nodes().count { it.hasLabel("Show text") }
            repeat(buttons) { key(Key.Tab) }
            val last = checkNotNull(focused()) { "Tab should reach the last component" }
            check(last.label() == "Show text") { "the last focus stop before Close is a component, got ${last.label()}" }
            check(last.boundsInRoot.bottom <= height * scale) { "focus scrolled the last component into view: ${last.boundsInRoot}" }
            check(nodes().first { it.hasLabel("Show text") }.boundsInRoot.top < firstTop) { "the list scrolled" }
            key(Key.Enter)
            advance(Settle)
            check(focused()?.label() == "Hide text") { "Enter opened that notice's text" }
            capture("scrolled_open")

            key(Key.Tab)
            check(focused()?.label() == "Close") { "focus stays inside the dialog" }
            key(Key.Escape)
            advance(Settle)
            check(!state.modal.isOpen && state.navigator.current.route == Route.Settings) { "Esc closes the licences and nothing else" }
        }
    }
}

internal fun checkSettingsBugReport(outDir: File) {
    val titles = mutableListOf<String>()
    var answer: () -> String? = { "hideout-report-2026-10-25.txt" }
    val fixture = SettingsFixture(reportExport = ReportExport { title, _, _, _ -> titles += title; answer() })
    val state = fixture.state(Route.Settings)
    state.settingsTab = SettingsTab.About
    runCheck(outDir, "settings_bug_report", state, 1440, 900) {
        advance(Settle)
        check(shows("Save a bug report") && hasText("Saves your setup, your mods and what Hideout did recently as a text file to attach to a bug report, without your user name.")) { "About offers the report" }
        clickLabel("Save…")
        advance(Settle)
        check(titles == listOf("Save a bug report")) { "the save dialog is called Save a bug report, got $titles" }
        check(hasText("Saved hideout-report-2026-10-25.txt. Attach it to your bug report.")) { "the row names the file it saved" }
        capture("saved")

        answer = { throw IOException("read-only folder") }
        clickLabel("Save…")
        advance(Settle)
        check(hasText("Hideout couldn’t save the report. Try another folder.")) { "the row says the save failed" }
        capture("failed")
    }
}

internal fun checkSettingsClear(outDir: File) {
    val fixture = SettingsFixture()
    val state = fixture.state(Route.Settings)
    state.settingsTab = SettingsTab.Mods
    val unused = """C:\Users\Player\Downloads\Hideout\gilded_seven_uninstalled.zip"""
    runCheck(outDir, "settings_clear", state) {
        advance(Settle)
        check(unused in fixture.files.archivePaths())
        clickLabel("Clear 212 MB")
        advance(Settle)
        val maintenance = fixture.container.modLibrary.state.value.maintenance
        check(maintenance is Maintenance.Clear && maintenance.stage == ClearStage.Cleared(removed = 1, freedBytes = 212_000_000L, kept = 0)) { "got $maintenance" }
        check(unused !in fixture.files.archivePaths() && fixture.files.archivePaths().size == 2) { "only the unused download went: ${fixture.files.archivePaths()}" }
        check(labelled("Clear").single().config.contains(SemanticsProperties.Disabled)) { "nothing is left to clear" }
        check(hasText("nothing to clear"))
        capture("cleared")
    }
}

internal fun checkSettingsClearStopped(outDir: File) {
    val spare = OwnedDownload(
        SubmissionIdentity(SubmissionKind.Mod, 551_260, 1_402_260, null),
        ArchiveIdentity("""C:\Users\Player\Downloads\Hideout\spare_uninstalled.zip""", 13_000_000L, "f".repeat(64)),
    )
    val sample = sampleLibraryManifest()
    val files = sampleGameFiles().apply { putArchive(spare.archive.path, emptyList(), FileImage(spare.archive.sizeBytes, spare.archive.sha256)) }
    val store = InMemoryLibraryStore(sample.copy(downloads = sample.downloads + spare)).apply {
        refuse = { manifest -> "the disk is full".takeIf { (manifest.pending as? PendingChange.ArchiveRemoval)?.download == spare } }
    }
    val fixture = SettingsFixture(files = files, libraryStore = store)
    val state = fixture.state(Route.Settings)
    state.settingsTab = SettingsTab.Mods
    val unused = """C:\Users\Player\Downloads\Hideout\gilded_seven_uninstalled.zip"""
    runCheck(outDir, "settings_clear_stopped", state) {
        advance(Settle)
        clickLabel("Clear 225 MB")
        advance(Settle)
        val maintenance = fixture.container.modLibrary.state.value.maintenance
        val stopped = ClearStage.Refused(LibraryProblem.ManifestSave("the disk is full"))
        check(maintenance is Maintenance.Clear && maintenance.stage == stopped) { "got $maintenance" }
        check(unused !in files.archivePaths() && spare.archive.path in files.archivePaths()) { "only the first download went: ${files.archivePaths()}" }
        check(hasText("Hideout stopped clearing. Hideout couldn’t save its install list. Check there’s free space on the drive Hideout keeps its data on, then try again.")) { "the note says why it stopped, without the system's own words" }
        check(labelled("Clear 13 MB").size == 1) { "the download left is offered again" }
        capture("stopped")
    }
}

internal fun checkSettingsBackups(outDir: File) {
    for ((name, width, height) in listOf(Triple("settings_backups", 1440, 900), Triple("settings_backups_compact", 1000, 680))) {
        val fixture = SettingsFixture()
        val state = fixture.state(Route.Settings)
        state.settingsTab = SettingsTab.Mods
        val library = fixture.container.modLibrary
        fun installed() = (library.state.value.library as LibraryStatus.Ready).records.map { it.installId }
        runCheck(outDir, name, state, width, height) {
            fun toBackups() {
                moveToDp(width / 2f, height / 2f)
                scroll(30f)
                advance(Settle)
            }
            advance(Settle)
            toBackups()
            listOf("Backups", "Back up your setup", "Not backed up yet", "Back up now", "Restore a backup", "Choose…").forEach { check(shows(it)) { "Mods should show $it" } }
            check(shows("Puts back your mods, their order, profiles, autoexec.cfg and crosshair as a backup has them.")) { "the restore row says what it puts back" }
            check(labelled("Choose…").single().config.contains(SemanticsProperties.Disabled)) { "there is nothing to choose before the first backup" }
            capture("rows")

            clickLabel("Back up now")
            advance(Settle)
            check(library.state.value.maintenance == Maintenance.Backups(BackupWork.BackUp, BackupStage.Done)) { "the backup should finish, got ${library.state.value.maintenance}" }
            check(hasText("Last backed up ") && !shows("Not backed up yet")) { "the row says when" }
            val backup = library.state.value.backups.single()
            library.uninstall(checkNotNull(library.uninstallConfirmation("faun-celeste")), deleteDownload = false)
            advance(Settle)
            capture("backed_up")

            clickLabel("Choose…")
            advance(Settle)
            check(state.modal.isOpen && focused()?.label() == "Close") { "the list opens with Close focused, got ${focused()?.label()}" }
            check(shows("4 mods") && labelled("Restore").size == 1 && labelled("Delete").size == 1) { "one backup of the sample's four mods" }
            capture("dialog")
            key(Key.Escape)
            advance(Settle)
            check(!state.modal.isOpen && state.navigator.current.route == Route.Settings) { "Esc closes the list and nothing else" }

            clickLabel("Choose…")
            advance(Settle)
            clickLabel("Restore")
            advance(Settle)
            check(hasText("Restore the backup from ") && hasText("Mods installed since are removed.") && hasText("so you can restore that to undo this.")) { "the confirmation says what a restore does" }
            check(focused()?.label() == "Cancel") { "Cancel has focus first, got ${focused()?.label()}" }
            capture("confirm_restore")
            key(Key.Escape)
            advance(Settle)
            check(state.modal.isOpen && focused()?.label() == "Close" && shows("4 mods")) { "Esc in the confirmation goes back to the list" }
            check("faun-celeste" !in installed()) { "and restores nothing" }
            clickLabel("Restore")
            advance(Settle)
            clickLabel("Restore")
            advance(Settle)
            check(!state.modal.isOpen) { "the dialog closes once the restore is confirmed" }
            check(library.state.value.maintenance == Maintenance.Backups(BackupWork.Revert(backup), BackupStage.Done)) { "the restore should finish, got ${library.state.value.maintenance}" }
            capture("restored")

            clickLabel("Library")
            advance(Settle)
            check(shows("Faun Celeste")) { "the Library shows the mod the backup put back" }
            capture("library")
            openSettings(state)
            toBackups()
            clickLabel("Choose…")
            advance(Settle)
            check(shows("3 mods · Before a restore") && shows("4 mods")) { "the newest backup comes first and says why it was made" }
            clickNode(labelled("Delete").first())
            advance(Settle)
            check(hasText("Delete the backup from ") && shows("This can’t be undone.") && focused()?.label() == "Cancel") { "the delete confirmation, Cancel focused first" }
            capture("confirm_delete")
            clickLabel("Delete")
            advance(Settle)
            check(library.state.value.backups == listOf(backup)) { "only the backup before the restore went" }
            check(state.modal.isOpen && labelled("Delete").size == 1) { "the list shows the one left" }
            key(Key.Escape)
            advance(Settle)

            search("backup")
            check(shows("2 settings in 1 section") && shows("Back up your setup") && shows("Restore a backup")) { "backup finds both rows" }
            capture("search")
            clearSearch()
        }
    }
}

internal fun checkSettingsBackupsRefused(outDir: File) {
    val fixture = SettingsFixture()
    val state = fixture.state(Route.Settings)
    state.settingsTab = SettingsTab.Mods
    val library = fixture.container.modLibrary
    runCheck(outDir, "settings_backups_refused", state) {
        advance(Settle)
        fixture.files.around = { if (it is FaultPoint.Publish) error("No space left on device") }
        clickLabel("Back up now")
        advance(Settle)
        check(hasText("Hideout didn’t back up your setup. Hideout couldn’t write its backups next to the game. Check there’s free space and that the game folder isn’t read-only, then try again.")) { "the note says why, without the system's own words" }
        check(shows("Not backed up yet") && labelled("Choose…").single().config.contains(SemanticsProperties.Disabled)) { "and there is still nothing to restore" }
        capture("backup_failed")

        fixture.files.around = null
        fixture.processes.scan = ProcessScan.Seen(game = true)
        clickLabel("Back up now")
        advance(Settle)
        check(library.state.value.maintenance == Maintenance.Backups(BackupWork.BackUp, BackupStage.Done)) { "a backup is made while Deadlock runs, got ${library.state.value.maintenance}" }
        clickLabel("Choose…")
        advance(Settle)
        clickLabel("Restore")
        advance(Settle)
        clickLabel("Restore")
        advance(Settle)
        check(hasText("Hideout didn’t restore the backup. Close Deadlock first. The game keeps its files locked while it runs.")) { "the note says Deadlock runs" }
        check(library.state.value.backups.size == 1) { "a refused restore backs nothing up" }
        capture("restore_refused")
    }
}

internal fun checkSettingsTabMotion(outDir: File) {
    for (reduced in listOf(false, true)) {
        val fixture = SettingsFixture()
        val state = fixture.state(Route.Settings)
        runCheck(outDir, if (reduced) "settings_motion_reduced" else "settings_motion", state, reducedMotion = reduced) {
            advance(Settle)
            val tab = labelled("Mods").single().boundsInRoot.center
            moveToDp(tab.x / scale, tab.y / scale)
            click()
            advance(EarlyMillis)
            val early = nodes().first { it.hasLabel("Installing") }.boundsInRoot.left
            capture("early")
            advance(Settle)
            val settled = nodes().first { it.hasLabel("Installing") }.boundsInRoot.left
            if (reduced) check(early == settled) { "reduced motion should not shift the tab's content: $early then $settled" }
            else check(early > settled) { "moving right should start shifted right: $early then $settled" }
        }
    }
}

private fun withoutHideoutBlock(bytes: ByteArray): ByteArray = (GameInfoPatch.withBlock(bytes, HideoutLines(mods = false, customGames = false)) as? GameInfoEdit.Patched)?.bytes ?: bytes
